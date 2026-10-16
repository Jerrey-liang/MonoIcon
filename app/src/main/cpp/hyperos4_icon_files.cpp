#include "hyperos4_icon_files.h"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>

namespace {

// Verified in the 6144 Dart ObjectPool (0xca98 / 0xcaa8) and the complete
// IconAssets.loadFromMod body. These are icon candidates, not general file IO.
constexpr const char* kPrefixes[] = {
    "/data/system/theme_magic/customized_icons/",
    "/product/media/theme/miui_mod_icons/",
};
constexpr char kPngSignature[] = "\x89PNG\r\n\x1a\n";
constexpr size_t kPathCapacity = 1024;
std::atomic<pid_t> gInstalledPid{0};
std::atomic_flag gInstalling = ATOMIC_FLAG_INIT;
std::atomic<unsigned> gHookCount{0};
std::atomic<unsigned> gRedirectCount{0};
std::atomic<unsigned> gMissingCount{0};
std::atomic<bool> gComplete{false};
pthread_mutex_t gDirectoryMutex = PTHREAD_MUTEX_INITIALIZER;
char gDirectory[256]{};
char gStatusPath[192]{};

using OpenFn = int (*)(const char*, int, ...);
using OpenAtFn = int (*)(int, const char*, int, ...);
using Open2Fn = int (*)(const char*, int);
using OpenAt2Fn = int (*)(int, const char*, int);
using StatFn = int (*)(const char*, struct stat*);
using StatAtFn = int (*)(int, const char*, struct stat*, int);
using AccessFn = int (*)(const char*, int);
using AccessAtFn = int (*)(int, const char*, int, int);
using FopenFn = FILE* (*)(const char*, const char*);
OpenFn gOpen{};
OpenAtFn gOpenAt{};
Open2Fn gOpen2{};
OpenAt2Fn gOpenAt2{};
StatFn gStat{}, gStat64{}, gLstat64{};
StatAtFn gFstatAt64{}, gFstatAt{};
AccessFn gAccess{};
AccessAtFn gFaccessAt{};
FopenFn gFopen{};
using MadviseFn = int (*)(void*, size_t, int);
MadviseFn gMadvise{};
std::atomic<uintptr_t> gProtectedPages[24]{};
std::atomic<size_t> gProtectedPageSize{0};

bool IsLauncherChild() {
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD,
        "/proc/self/cmdline", O_RDONLY | O_CLOEXEC, 0));
    if (fd < 0) return false;
    char name[32]{};
    const ssize_t length = read(fd, name, sizeof(name));
    close(fd);
    constexpr char expected[] = "com.miui.home";
    return length >= static_cast<ssize_t>(sizeof(expected)) &&
           std::memcmp(name, expected, sizeof(expected)) == 0;
}

void WriteStatus(const char* phase, const char* basename = "") {
    const int savedErrno = errno;
    char body[768];
    const int count = snprintf(body, sizeof(body),
        "phase=%s pid=%d hooks=%u redirects=%u last=%s\n", phase,
        static_cast<int>(getpid()), gHookCount.load(std::memory_order_relaxed),
        gRedirectCount.load(std::memory_order_relaxed), basename);
    if (count > 0 && static_cast<size_t>(count) < sizeof(body)) {
        const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD,
            gStatusPath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600));
        if (fd >= 0) {
            size_t offset = 0;
            while (offset < static_cast<size_t>(count)) {
                const ssize_t written = write(fd, body + offset, count - offset);
                if (written > 0) offset += static_cast<size_t>(written);
                else if (written < 0 && errno == EINTR) continue;
                else break;
            }
            close(fd);
        }
    }
    errno = savedErrno;
}

bool ValidBasename(const char* name) {
    if (!name || !name[0]) return false;
    size_t length = 0;
    for (; name[length] && length < 512; ++length) {
        const unsigned char c = static_cast<unsigned char>(name[length]);
        // Package/class identifiers plus the launcher's documented '#' separator.
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '$' ||
              c == '#' || c == '-')) return false;
    }
    return length > 4 && length < 512 &&
           std::strcmp(name + length - 4, ".png") == 0 &&
           std::strstr(name, "..") == nullptr;
}

const char* Redirect(const char* path, char (&replacement)[kPathCapacity]) {
    if (!path || !monoicon_hyos_icons_enabled()) return path;
    const char* basename = nullptr;
    for (const char* prefix : kPrefixes) {
        const size_t length = std::strlen(prefix);
        if (std::strncmp(path, prefix, length) == 0) {
            basename = path + length;
            break;
        }
    }
    if (!ValidBasename(basename)) return path;
    const int savedErrno = errno;
    pthread_mutex_lock(&gDirectoryMutex);
    const int length = gDirectory[0] ? snprintf(replacement, sizeof(replacement), "%s/%s",
                                gDirectory, basename) : -1;
    pthread_mutex_unlock(&gDirectoryMutex);
    if (length <= 0 || static_cast<size_t>(length) >= sizeof(replacement)) {
        errno = savedErrno;
        return path;
    }
    // A raw syscall avoids re-entering the libc hooks. Only existing regular PNG
    // files published by MonoIcon are eligible; a missing/bad export falls back.
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, replacement,
        O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0));
    bool valid = false;
    if (fd >= 0) {
        struct stat metadata{};
        unsigned char signature[8]{};
        const bool regular = syscall(SYS_fstat, fd, &metadata) == 0 &&
                             S_ISREG(metadata.st_mode) && metadata.st_size > 8;
        ssize_t count = -1;
        if (regular) {
            do { count = read(fd, signature, sizeof(signature)); }
            while (count < 0 && errno == EINTR);
        }
        valid = count == static_cast<ssize_t>(sizeof(signature)) &&
                std::memcmp(signature, kPngSignature, sizeof(signature)) == 0;
        close(fd);
    }
    if (valid) {
        const unsigned count = gRedirectCount.fetch_add(1, std::memory_order_relaxed) + 1;
        if (count <= 16 || (count & (count - 1)) == 0) WriteStatus("redirecting", basename);
    } else {
        const unsigned missing = gMissingCount.fetch_add(1, std::memory_order_relaxed);
        if (missing < 128) {
            char path[192];
            snprintf(path, sizeof(path), "/data/user/%u/com.miui.home/files/monoicon_hyos_icon_misses.txt",
                     static_cast<unsigned>(getuid()) / 100000u);
            const int out = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path,
                O_WRONLY | O_CREAT | O_CLOEXEC | (missing == 0 ? O_TRUNC : O_APPEND), 0600));
            if (out >= 0) { write(out, basename, std::strlen(basename)); write(out, "\n", 1); close(out); }
        }
    }
    errno = savedErrno;
    return valid ? replacement : path;
}

bool ReadOnly(int flags) {
    return (flags & O_ACCMODE) == O_RDONLY &&
           (flags & (O_CREAT | O_TRUNC | O_TMPFILE)) == 0;
}

bool HasMode(int flags) {
    return (flags & O_CREAT) != 0 || (flags & O_TMPFILE) == O_TMPFILE;
}

int Open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (HasMode(flags)) {
        va_list arguments; va_start(arguments, flags);
        mode = static_cast<mode_t>(va_arg(arguments, int)); va_end(arguments);
    }
    char replacement[kPathCapacity];
    return gOpen(ReadOnly(flags) ? Redirect(path, replacement) : path, flags, mode);
}
int OpenAt(int directory, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (HasMode(flags)) {
        va_list arguments; va_start(arguments, flags);
        mode = static_cast<mode_t>(va_arg(arguments, int)); va_end(arguments);
    }
    char replacement[kPathCapacity];
    return gOpenAt(directory, ReadOnly(flags) ? Redirect(path, replacement) : path, flags, mode);
}
int Open2(const char* path, int flags) {
    char replacement[kPathCapacity];
    return gOpen2(ReadOnly(flags) ? Redirect(path, replacement) : path, flags);
}
int OpenAt2(int directory, const char* path, int flags) {
    char replacement[kPathCapacity];
    return gOpenAt2(directory, ReadOnly(flags) ? Redirect(path, replacement) : path, flags);
}
int Stat(const char* path, struct stat* metadata) {
    char replacement[kPathCapacity]; return gStat(Redirect(path, replacement), metadata);
}
int Stat64(const char* path, struct stat* metadata) {
    char replacement[kPathCapacity]; return gStat64(Redirect(path, replacement), metadata);
}
int Lstat64(const char* path, struct stat* metadata) {
    char replacement[kPathCapacity]; return gLstat64(Redirect(path, replacement), metadata);
}
int FstatAt64(int directory, const char* path, struct stat* metadata, int flags) {
    char replacement[kPathCapacity];
    return gFstatAt64(directory, Redirect(path, replacement), metadata, flags);
}
int FstatAt(int directory, const char* path, struct stat* metadata, int flags) {
    char replacement[kPathCapacity];
    return gFstatAt(directory, Redirect(path, replacement), metadata, flags);
}
int Access(const char* path, int mode) {
    char replacement[kPathCapacity];
    return gAccess((mode & (W_OK | X_OK)) == 0 ? Redirect(path, replacement) : path, mode);
}
int FaccessAt(int directory, const char* path, int mode, int flags) {
    char replacement[kPathCapacity];
    return gFaccessAt(directory,
        (mode & (W_OK | X_OK)) == 0 ? Redirect(path, replacement) : path, mode, flags);
}
FILE* Fopen(const char* path, const char* mode) {
    char replacement[kPathCapacity];
    const bool readOnly = mode && mode[0] == 'r' && !std::strchr(mode, '+');
    return gFopen(readOnly ? Redirect(path, replacement) : path, mode);
}

int Madvise(void* address, size_t length, int advice) {
    const size_t pageSize = gProtectedPageSize.load(std::memory_order_acquire);
    const uintptr_t start = reinterpret_cast<uintptr_t>(address);
    if (!pageSize || (advice != MADV_DONTNEED && advice != MADV_FREE) ||
        start % pageSize || length > UINTPTR_MAX - start || !length) {
        return gMadvise(address, length, advice);
    }
    // Do not discard the private code pages containing our verified patches.
    // Forward all non-overlapping portions, preserving the caller's reclamation.
    const uintptr_t end = start + length;
    uintptr_t cursor = start;
    while (cursor < end) {
        uintptr_t next = end;
        for (const auto& slot : gProtectedPages) {
            const uintptr_t page = slot.load(std::memory_order_relaxed);
            if (page >= cursor && page < next) next = page;
        }
        if (next > cursor && gMadvise(reinterpret_cast<void*>(cursor), next - cursor, advice) != 0) return -1;
        if (next == end) return 0;
        cursor = next > UINTPTR_MAX - pageSize ? end : next + pageSize;
    }
    return 0;
}

}  // namespace

extern "C" void monoicon_hyos_protect_code_pages(const uintptr_t* pages, size_t count, size_t pageSize) {
    if (!pages || !pageSize || count > 24) return;
    for (size_t i = 0; i < count; ++i) gProtectedPages[i].store(pages[i], std::memory_order_relaxed);
    gProtectedPageSize.store(pageSize, std::memory_order_release);
}

extern "C" bool monoicon_hyos_icon_generation_ready(const char* generation) {
    if (!generation || std::strlen(generation) != 34 || generation[0] != 'g' || generation[1] != '-') return false;
    for (size_t i = 2; i < 34; ++i) {
        if (!((generation[i] >= '0' && generation[i] <= '9') ||
              (generation[i] >= 'a' && generation[i] <= 'f'))) return false;
    }
    char path[256];
    snprintf(path, sizeof(path), "/data/user/%u/com.miui.home/files/monoicon_icons/%s/.ready",
             static_cast<unsigned>(getuid()) / 100000u, generation);
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path,
                                           O_RDONLY | O_CLOEXEC | O_NOFOLLOW, 0));
    if (fd < 0) return false;
    char token[35]{};
    const ssize_t count = read(fd, token, sizeof(token));
    close(fd);
    return count == 34 && std::memcmp(token, generation, 34) == 0;
}

extern "C" void monoicon_hyos_select_icon_generation(const char* generation) {
    pthread_mutex_lock(&gDirectoryMutex);
    if (generation && generation[0]) {
        snprintf(gDirectory, sizeof(gDirectory), "/data/user/%u/com.miui.home/files/monoicon_icons/%s",
                 static_cast<unsigned>(getuid()) / 100000u, generation);
    } else {
        gDirectory[0] = '\0';
    }
    pthread_mutex_unlock(&gDirectoryMutex);
}

extern "C" bool monoicon_hyos_install_icon_files(MonoiconNativeHook hook) {
    if (!hook || !IsLauncherChild()) return false;
    if (gInstalledPid.load(std::memory_order_acquire) == getpid()) return gComplete.load();
    if (gInstalling.test_and_set(std::memory_order_acquire)) return false;

    const unsigned user = static_cast<unsigned>(getuid()) / 100000u;
    snprintf(gStatusPath, sizeof(gStatusPath),
        "/data/user/%u/com.miui.home/files/monoicon_hyos_icon_files.txt", user);
    struct Entry { const char* name; void* replacement; void** backup; };
    Entry entries[] = {
        {"open", reinterpret_cast<void*>(Open), reinterpret_cast<void**>(&gOpen)},
        {"openat", reinterpret_cast<void*>(OpenAt), reinterpret_cast<void**>(&gOpenAt)},
        {"__open_2", reinterpret_cast<void*>(Open2), reinterpret_cast<void**>(&gOpen2)},
        {"__openat_2", reinterpret_cast<void*>(OpenAt2), reinterpret_cast<void**>(&gOpenAt2)},
        {"stat", reinterpret_cast<void*>(Stat), reinterpret_cast<void**>(&gStat)},
        {"stat64", reinterpret_cast<void*>(Stat64), reinterpret_cast<void**>(&gStat64)},
        {"lstat64", reinterpret_cast<void*>(Lstat64), reinterpret_cast<void**>(&gLstat64)},
        {"fstatat64", reinterpret_cast<void*>(FstatAt64), reinterpret_cast<void**>(&gFstatAt64)},
        {"fstatat", reinterpret_cast<void*>(FstatAt), reinterpret_cast<void**>(&gFstatAt)},
        {"access", reinterpret_cast<void*>(Access), reinterpret_cast<void**>(&gAccess)},
        {"faccessat", reinterpret_cast<void*>(FaccessAt), reinterpret_cast<void**>(&gFaccessAt)},
        {"fopen", reinterpret_cast<void*>(Fopen), reinterpret_cast<void**>(&gFopen)},
        {"madvise", reinterpret_cast<void*>(Madvise), reinterpret_cast<void**>(&gMadvise)},
    };
    void* targets[sizeof(entries) / sizeof(entries[0])]{};
    for (size_t index = 0; index < sizeof(entries) / sizeof(entries[0]); ++index) {
        auto& entry = entries[index];
        void* target = dlsym(RTLD_DEFAULT, entry.name);
        if (!target) continue;
        bool alias = false;
        for (size_t previous = 0; previous < index; ++previous) {
            if (targets[previous] == target) { alias = true; break; }
        }
        if (alias) continue;  // bionic's 64-bit stat aliases share the same ABI.
        const int result = hook(target, entry.replacement, entry.backup);
        if (result == 0 && *entry.backup) {
            targets[index] = target;
            gHookCount.fetch_add(1, std::memory_order_relaxed);
        } else {
            __android_log_print(ANDROID_LOG_WARN, "MonoIcon.IconFiles",
                "failed to hook %s: %d", entry.name, result);
        }
    }
    const bool installed = gOpen && gOpenAt && gStat && gAccess && gMadvise;
    gComplete.store(installed, std::memory_order_release);
    // Partial successful hooks remain safe pass-throughs and must never be
    // installed twice. A launcher restart retries an incomplete installation.
    gInstalledPid.store(getpid(), std::memory_order_release);
    WriteStatus(installed ? "installed" : "partial");
    __android_log_print(ANDROID_LOG_INFO, "MonoIcon.IconFiles",
        "icon file hooks=%u complete=%d", gHookCount.load(), installed);
    gInstalling.clear(std::memory_order_release);
    return installed;
}
