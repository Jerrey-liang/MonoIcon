#include <jni.h>

#if defined(__aarch64__)
#include "hyperos4_thread_pause.h"
#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits.h>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr char kTag[] = "MonoIcon.HyperOS4";
constexpr char kEntry[] = "lib/arm64-v8a/libapp.so";
enum Status : jint {
    kWaiting = 0, kInstalled = 1, kUnsupported = -1, kInvalidInput = -2,
    kIdentityMismatch = -3, kMapsUnavailable = -4, kTimedOut = -5,
    kProtectionDenied = -6, kCodeMismatch = -7, kWorkerFailed = -8,
    kRecoveryFailed = -9, kThreadPauseUnavailable = -10, kPaletteInUse = -11,
};

// Reuse P3's transaction for a scalar palette override. No Dart function
// pointers, foreign frames, heap writes or object/GC bridge.
// Evidence: external_resources/HyperOS4/report/CUSTOM_PALETTE_INJECTION.md.
struct Patch {
    uintptr_t vaddr;
    uint32_t original;
    uint32_t replacement;
    uintptr_t function;
    size_t functionSize;
};
constexpr std::array<Patch, 5> kPatches{{
    {0x1a9b538, 0x36200261, 0x14000013, 0x1a9b43c, 516},
    {0x19df288, 0x9a910204, 0xaa1003e4, 0x19df1ec, 624},
    // Settings' background value: replace only the integer unboxing. Keep the
    // original normalization, Dart integer allocation, Rx setter and renderer.
    {0x19df2e8, 0x93417c61, 0x00000000, 0x19df1ec, 624},
    {0x19df2ec, 0x36000043, 0x00000000, 0x19df1ec, 624},
    {0x19df2f0, 0xf8407061, 0xd503201f, 0x19df1ec, 624},
}};
using PatchSet = std::array<Patch, kPatches.size()>;

PatchSet makePatches(uint32_t background) {
    auto patches = kPatches;
    // W writes zero-extend the exact packed ARGB value into unboxed x1.
    patches[2].replacement = 0x52800001u | ((background & 0xffffu) << 5); // movz w1
    patches[3].replacement = 0x72a00001u | ((background >> 16) << 5);     // movk w1, lsl #16
    return patches;
}
constexpr std::array<uint8_t, 32> kBuildNote{{
    4, 0, 0, 0, 16, 0, 0, 0, 3, 0, 0, 0, 'G', 'N', 'U', 0,
    0x4f, 0x1b, 0xda, 0xed, 0xa6, 0xb1, 0xf9, 0x8d,
    0xde, 0x7d, 0x3e, 0xbc, 0x23, 0x16, 0x3c, 0xd1,
}};
constexpr uintptr_t kNoteVaddr = 0x1c8;
constexpr uintptr_t kTextVaddr = 0x850000;
constexpr uintptr_t kTextSize = 0x130ccf0;
constexpr size_t kElfSize = 29387672;
std::atomic<jint> gStatus{kWaiting};
std::atomic<bool> gStarted{false};

struct File {
    int fd = -1;
    explicit File(int value = -1) : fd(value) {}
    ~File() { if (fd >= 0) close(fd); }
    File(const File&) = delete;
    File& operator=(const File&) = delete;
};

bool readAt(int fd, uint64_t offset, void* output, size_t size) {
    auto* bytes = static_cast<uint8_t*>(output);
    if (offset > INT64_MAX || size > static_cast<uint64_t>(INT64_MAX) - offset) return false;
    while (size != 0) {
        const ssize_t n = pread(fd, bytes, size, static_cast<off_t>(offset));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        bytes += n;
        offset += n;
        size -= n;
    }
    return true;
}

uint16_t u16(const uint8_t* p) { return p[0] | (uint16_t(p[1]) << 8); }
uint32_t u32(const uint8_t* p) { return u16(p) | (uint32_t(u16(p + 2)) << 16); }

// Resolve the stored entry through the central directory, never a signature scan
// through arbitrary APK contents. ZIP64/compressed entries are deliberately not
// mapped candidates; an extracted nativeLibraryDir copy can still be supported.
bool apkEntryOffset(int fd, uint64_t fileSize, uint64_t& entryOffset) {
    if (fileSize < 22) return false;
    std::vector<uint8_t> tail(std::min<uint64_t>(fileSize, 65535 + 22));
    if (!readAt(fd, fileSize - tail.size(), tail.data(), tail.size())) return false;
    size_t eocd = tail.size();
    for (size_t pos = tail.size() - 22;; --pos) {
        if (u32(&tail[pos]) == 0x06054b50 && pos + 22 + u16(&tail[pos + 20]) == tail.size()) {
            eocd = pos;
            break;
        }
        if (pos == 0) break;
    }
    if (eocd == tail.size()) return false;
    const auto* e = &tail[eocd];
    const uint16_t count = u16(e + 10);
    const uint64_t cdSize = u32(e + 12), cdOffset = u32(e + 16);
    const uint64_t eocdOffset = fileSize - tail.size() + eocd;
    if (u16(e + 4) != 0 || u16(e + 6) != 0 || u16(e + 8) != count ||
        count == 0xffff || cdSize > 16 * 1024 * 1024 || cdOffset > eocdOffset ||
        cdSize > eocdOffset - cdOffset) return false;
    std::vector<uint8_t> directory(cdSize);
    if (!readAt(fd, cdOffset, directory.data(), directory.size())) return false;
    bool found = false;
    size_t pos = 0;
    for (uint32_t i = 0; i < count; ++i) {
        if (pos > directory.size() || directory.size() - pos < 46) return false;
        const auto* c = &directory[pos];
        if (u32(c) != 0x02014b50) return false;
        const size_t nameSize = u16(c + 28);
        const size_t recordSize = 46 + nameSize + u16(c + 30) + u16(c + 32);
        if (recordSize > directory.size() - pos) return false;
        if (nameSize == sizeof(kEntry) - 1 && memcmp(c + 46, kEntry, nameSize) == 0) {
            if (found || u16(c + 10) != 0 || (u16(c + 8) & 1) != 0 ||
                u16(c + 34) != 0 || u32(c + 20) != kElfSize || u32(c + 24) != kElfSize) return false;
            std::array<uint8_t, 30> local{};
            const uint64_t localOffset = u32(c + 42);
            if (localOffset >= cdOffset || !readAt(fd, localOffset, local.data(), local.size()) ||
                u32(local.data()) != 0x04034b50 || u16(local.data() + 8) != 0 ||
                u16(local.data() + 6) != u16(c + 8) || u16(local.data() + 26) != nameSize) return false;
            std::array<char, sizeof(kEntry) - 1> name{};
            if (!readAt(fd, localOffset + 30, name.data(), name.size()) ||
                memcmp(name.data(), kEntry, name.size()) != 0) return false;
            entryOffset = localOffset + 30 + nameSize + u16(local.data() + 28);
            if (entryOffset > cdOffset || kElfSize > cdOffset - entryOffset) return false;
            found = true;
        }
        pos += recordSize;
    }
    return found && pos == directory.size();
}

struct Backing {
    std::string path;
    dev_t device{};
    ino_t inode{};
    uint64_t offset{};
    std::array<uint8_t, 0x1e8> header{};
    std::array<std::vector<uint8_t>, kPatches.size()> functions;
};

bool validateElf(int fd, Backing& b) {
    if (!readAt(fd, b.offset, b.header.data(), b.header.size())) return false;
    Elf64_Ehdr e{};
    memcpy(&e, b.header.data(), sizeof(e));
    if (memcmp(e.e_ident, ELFMAG, SELFMAG) != 0 || e.e_ident[EI_CLASS] != ELFCLASS64 ||
        e.e_ident[EI_DATA] != ELFDATA2LSB || e.e_ident[EI_VERSION] != EV_CURRENT ||
        e.e_machine != EM_AARCH64 || e.e_type != ET_DYN || e.e_version != EV_CURRENT ||
        e.e_entry != 0 || e.e_ehsize != sizeof(e) || e.e_phentsize != sizeof(Elf64_Phdr) ||
        e.e_phnum == 0 || e.e_phnum > 16 || e.e_phoff > kNoteVaddr ||
        e.e_phnum * sizeof(Elf64_Phdr) > kNoteVaddr - e.e_phoff ||
        memcmp(b.header.data() + kNoteVaddr, kBuildNote.data(), kBuildNote.size()) != 0) return false;
    struct Segment { uint64_t address, size; uint32_t flags; };
    constexpr Segment expected[] = {
        {0, 0x84c3f2, PF_R}, {kTextVaddr, kTextSize, PF_R | PF_X},
        {0x1b60000, 0x90, PF_R | PF_W},
    };
    size_t loadCount = 0;
    for (size_t i = 0; i < e.e_phnum; ++i) {
        Elf64_Phdr p{};
        memcpy(&p, b.header.data() + e.e_phoff + i * sizeof(p), sizeof(p));
        if (p.p_type != PT_LOAD) continue;
        if (loadCount >= 3) return false;
        const auto& s = expected[loadCount++];
        if (p.p_vaddr != s.address || p.p_offset != s.address || p.p_filesz != s.size ||
            p.p_memsz != s.size || p.p_flags != s.flags || p.p_align != 0x10000) return false;
    }
    if (loadCount != 3) return false;
    for (size_t i = 0; i < kPatches.size(); ++i) {
        const auto& p = kPatches[i];
        auto& code = b.functions[i];
        code.resize(p.functionSize);
        if (!readAt(fd, b.offset + p.function, code.data(), code.size()) ||
            u32(code.data() + p.vaddr - p.function) != p.original) return false;
    }
    const auto& settings = b.functions[1];
    const size_t at = kPatches[1].vaddr - kPatches[1].function;
    return u32(settings.data() + at - 8) == 0x910082d0 &&
           u32(settings.data() + at - 4) == 0x9100c2d1;
}

bool addBacking(const std::string& path, bool apk, size_t pageSize, std::vector<Backing>& backings) {
    char resolved[PATH_MAX];
    if (path.empty() || realpath(path.c_str(), resolved) == nullptr) return false;
    File file(open(resolved, O_RDONLY | O_CLOEXEC));
    struct stat st{};
    if (file.fd < 0 || fstat(file.fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) return false;
    Backing b;
    b.path = resolved;
    b.device = st.st_dev;
    b.inode = st.st_ino;
    if (apk) {
        if (!apkEntryOffset(file.fd, st.st_size, b.offset) || b.offset % pageSize != 0) return false;
    } else if (st.st_size != kElfSize) {
        return false;
    }
    if (!validateElf(file.fd, b)) return false;
    backings.push_back(std::move(b));
    return true;
}

struct Mapping {
    uintptr_t start{}, end{};
    uint64_t offset{}, inode{};
    unsigned deviceMajor{}, deviceMinor{};
    std::string path;
    int protection{};
    bool privateMapping{};
};

bool readMaps(std::vector<Mapping>& maps) {
    std::unique_ptr<FILE, decltype(&fclose)> input(fopen("/proc/self/maps", "re"), fclose);
    if (!input) return false;
    // Fixed-size lines bound parser input. Overlong paths
    // are skipped instead of truncated into a different, apparently valid path.
    char line[PATH_MAX + 256];
    while (fgets(line, sizeof(line), input.get()) != nullptr) {
        if (strchr(line, '\n') == nullptr) {
            int c;
            while ((c = fgetc(input.get())) != '\n' && c != EOF) {}
            continue;
        }
        unsigned long long start, end, offset, inode;
        unsigned deviceMajor, deviceMinor;
        char permissions[5]{};
        int consumed = 0;
        if (sscanf(line, "%llx-%llx %4s %llx %x:%x %llu %n", &start, &end,
                   permissions, &offset, &deviceMajor, &deviceMinor, &inode, &consumed) != 7 ||
            start >= end) continue;
        char* path = line + consumed;
        path[strcspn(path, "\n")] = '\0';
        Mapping m;
        m.start = start; m.end = end; m.offset = offset; m.inode = inode;
        m.deviceMajor = deviceMajor; m.deviceMinor = deviceMinor; m.path = path;
        m.protection = (permissions[0] == 'r' ? PROT_READ : 0) |
                       (permissions[1] == 'w' ? PROT_WRITE : 0) |
                       (permissions[2] == 'x' ? PROT_EXEC : 0);
        m.privateMapping = permissions[3] == 'p';
        maps.push_back(std::move(m));
    }
    return ferror(input.get()) == 0;
}

bool sameBacking(const Mapping& m, const Backing& b) {
    return m.privateMapping && m.path == b.path && m.inode == b.inode &&
           m.deviceMajor == major(b.device) && m.deviceMinor == minor(b.device);
}

bool mappedRange(const std::vector<Mapping>& maps, const Backing& b, uintptr_t bias,
                 uintptr_t vaddr, size_t size, int protection) {
    if (bias > UINTPTR_MAX - vaddr || size > UINTPTR_MAX - (bias + vaddr)) return false;
    uintptr_t current = bias + vaddr;
    const uintptr_t end = current + size;
    for (const auto& m : maps) {
        if (m.end <= current) continue;
        if (m.start > current) return false;
        if (!sameBacking(m, b) || m.protection != protection || m.offset < b.offset ||
            m.offset - b.offset > UINTPTR_MAX - (current - m.start) ||
            m.offset - b.offset + current - m.start != current - bias) return false;
        current = std::min(m.end, end);
        if (current == end) return true;
    }
    return false;
}

bool mappedImage(const std::vector<Mapping>& maps, const Backing& b, uintptr_t bias, size_t pageSize) {
    if (!mappedRange(maps, b, bias, 0, b.header.size(), PROT_READ)) return false;
    for (const auto& p : kPatches) {
        if (!mappedRange(maps, b, bias, p.function, p.functionSize, PROT_READ | PROT_EXEC) ||
            !mappedRange(maps, b, bias, p.vaddr & ~(pageSize - 1), pageSize, PROT_READ | PROT_EXEC)) return false;
    }
    return true;
}

bool number(const char*& p, const char* end, unsigned base, uint64_t& value) {
    while (p < end && *p == ' ') ++p;
    const char* first = p;
    value = 0;
    while (p < end) {
        const char c = *p;
        const unsigned digit = c >= '0' && c <= '9' ? c - '0' :
                               c >= 'a' && c <= 'f' ? c - 'a' + 10 : base;
        if (digit >= base) break;
        if (value > (UINT64_MAX - digit) / base) return false;
        value = value * base + digit;
        ++p;
    }
    return p != first;
}

// No allocations or stdio/locale locks: revalidate backing, offsets and page
// permissions inside the pause, closing the race with the discovery snapshot.
bool frozenImage(const Backing& b, uintptr_t bias, size_t pageSize, char* buffer, size_t capacity) {
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC, 0));
    if (fd < 0) return false;
    size_t used = 0;
    bool complete = false;
    while (used < capacity) {
        const long count = syscall(SYS_read, fd, buffer + used, capacity - used);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) break;
        if (count == 0) { complete = true; break; }
        used += count;
    }
    syscall(SYS_close, fd);
    if (!complete) return false;
    struct Range { uintptr_t current, end; int protection; };
    Range ranges[1 + kPatches.size() * 2]{{bias, bias + b.header.size(), PROT_READ}};
    for (size_t i = 0; i < kPatches.size(); ++i) {
        const auto& patch = kPatches[i];
        ranges[1 + i * 2] = {bias + patch.function, bias + patch.function + patch.functionSize, PROT_READ | PROT_EXEC};
        const uintptr_t page = (bias + patch.vaddr) & ~(pageSize - 1);
        ranges[2 + i * 2] = {page, page + pageSize, PROT_READ | PROT_EXEC};
    }
    const char* line = buffer;
    const char* end = buffer + used;
    while (line < end) {
        const char* next = line;
        while (next < end && *next != '\n') ++next;
        if (next == end) return false;
        const char* p = line;
        uint64_t start, stop, offset, deviceMajor, deviceMinor, inode;
        if (!number(p, next, 16, start) || p == next || *p++ != '-' || !number(p, next, 16, stop) || start >= stop) return false;
        while (p < next && *p == ' ') ++p;
        if (next - p < 5) return false;
        const int protection = (p[0] == 'r' ? PROT_READ : 0) | (p[1] == 'w' ? PROT_WRITE : 0) |
                               (p[2] == 'x' ? PROT_EXEC : 0);
        const bool privateMapping = p[3] == 'p';
        p += 4;
        if (!number(p, next, 16, offset) || !number(p, next, 16, deviceMajor) || p == next || *p++ != ':' ||
            !number(p, next, 16, deviceMinor) || !number(p, next, 10, inode)) return false;
        while (p < next && *p == ' ') ++p;
        for (auto& range : ranges) {
            if (range.current == range.end || stop <= range.current) continue;
            if (start > range.current || !privateMapping || protection != range.protection ||
                inode != b.inode || deviceMajor != major(b.device) || deviceMinor != minor(b.device) ||
                static_cast<size_t>(next - p) != b.path.size() || memcmp(p, b.path.data(), b.path.size()) != 0 ||
                offset < b.offset || offset - b.offset > UINT64_MAX - (range.current - start) ||
                offset - b.offset + range.current - start != range.current - bias) return false;
            range.current = std::min<uintptr_t>(stop, range.end);
        }
        line = next + 1;
    }
    for (const auto& range : ranges) if (range.current != range.end) return false;
    return true;
}

bool matchesMemory(int mem, uintptr_t address, const uint8_t* expected, size_t size) {
    // pread fails on an unmapped range instead of dereferencing a stale scan.
    std::array<uint8_t, 1024> bytes{};
    return size <= bytes.size() && readAt(mem, address, bytes.data(), size) &&
           memcmp(bytes.data(), expected, size) == 0;
}

bool protect(uintptr_t page, size_t pageSize, int mode) {
    int result;
    do { result = mprotect(reinterpret_cast<void*>(page), pageSize, mode); }
    while (result != 0 && errno == EINTR);
    return result == 0;
}

void writeInstruction(uintptr_t address, uint32_t instruction) {
    // One naturally aligned AArch64 instruction, never a torn memcpy/trampoline.
    __atomic_store_n(reinterpret_cast<uint32_t*>(address), instruction, __ATOMIC_RELEASE);
    auto* first = reinterpret_cast<char*>(address);
    __builtin___clear_cache(first, first + sizeof(instruction));
}

jint install(const Backing& b, uintptr_t bias, size_t pageSize, int mem, const PatchSet& patches) {
    std::vector<Mapping> maps;
    if (!readMaps(maps) || !mappedImage(maps, b, bias, pageSize)) return kMapsUnavailable;
    std::vector<char> frozenMaps(2 * 1024 * 1024);
    hyperos4::ThreadPause pause;
    if (!pause.acquire()) return kThreadPauseUnavailable;
    // A peer must not resume midway through the MOVZ/MOVK sequence with an
    // old partial value. Reject active settings code; never edit saved registers.
    if (!pause.excludes(bias + kPatches[2].function,
                        bias + kPatches[2].function + kPatches[2].functionSize)) {
        return pause.finish() ? kPaletteInUse : kRecoveryFailed;
    }
    // From here through restoration/rollback: no allocation, log calls or libc
    // operations that can wait on locks held by the parked peers. Re-read the
    // complete functions after parking, before the first protection change.
    if (!frozenImage(b, bias, pageSize, frozenMaps.data(), frozenMaps.size())) return kMapsUnavailable;
    if (!matchesMemory(mem, bias, b.header.data(), b.header.size())) return kIdentityMismatch;
    for (size_t i = 0; i < kPatches.size(); ++i) {
        if (!matchesMemory(mem, bias + kPatches[i].function, b.functions[i].data(),
                           b.functions[i].size())) return kCodeMismatch;
    }
    std::array<uintptr_t, kPatches.size()> pages{};
    std::array<bool, kPatches.size()> writable{};
    size_t pageCount = 0;
    for (const auto& p : patches) {
        const uintptr_t page = (bias + p.vaddr) & ~(pageSize - 1);
        bool present = false;
        for (size_t i = 0; i < pageCount; ++i) present |= pages[i] == page;
        if (!present) pages[pageCount++] = page;
    }
    for (size_t i = 0; i < pageCount; ++i) {
        // Never circumvent a device's W^X/SELinux denial with a remap.
        if (!protect(pages[i], pageSize, PROT_READ | PROT_WRITE | PROT_EXEC)) {
            bool restored = true;
            for (size_t j = 0; j < i; ++j) restored &= protect(pages[j], pageSize, PROT_READ | PROT_EXEC);
            return pause.finish() && restored ? kProtectionDenied : kRecoveryFailed;
        }
        writable[i] = true;
    }
    for (const auto& p : patches) writeInstruction(bias + p.vaddr, p.replacement);
    bool restored = true;
    for (size_t i = 0; i < pageCount; ++i) {
        writable[i] = !protect(pages[i], pageSize, PROT_READ | PROT_EXEC);
        restored &= !writable[i];
    }
    bool verified = restored;
    for (const auto& p : patches) {
        uint32_t actual = 0;
        verified &= readAt(mem, bias + p.vaddr, &actual, sizeof(actual)) && actual == p.replacement;
    }
    // Every successful mprotect restored RX. With all peers parked there is no
    // concurrent unmap/protection change; do not allocate to reparse maps here.
    if (verified) {
        return pause.finish() ? kInstalled : kRecoveryFailed;
    }
    // Best-effort rollback is explicit. An unrecoverable protection/write state
    // is never described as installed or as an untouched original process.
    bool recovered = true;
    for (size_t i = 0; i < pageCount; ++i) {
        if (!writable[i]) writable[i] = protect(pages[i], pageSize, PROT_READ | PROT_WRITE | PROT_EXEC);
        for (const auto& p : patches) {
            if (((bias + p.vaddr) & ~(pageSize - 1)) != pages[i]) continue;
            if (writable[i]) writeInstruction(bias + p.vaddr, p.original);
            else recovered = false;
        }
        recovered &= protect(pages[i], pageSize, PROT_READ | PROT_EXEC);
    }
    for (const auto& p : patches) {
        uint32_t actual = 0;
        recovered &= readAt(mem, bias + p.vaddr, &actual, sizeof(actual)) && actual == p.original;
    }
    return pause.finish() && recovered ? kProtectionDenied : kRecoveryFailed;
}

jint run(const std::string& nativeDir, const std::string& apkPath, uint32_t background) {
    const auto patches = makePatches(background);
    const long size = sysconf(_SC_PAGESIZE);
    if (size <= 0 || size > 65536 || (size & (size - 1)) != 0) return kUnsupported;
    const size_t pageSize = size;
    std::vector<Backing> backings;
    if (!nativeDir.empty()) addBacking(nativeDir + "/libapp.so", false, pageSize, backings);
    addBacking(apkPath, true, pageSize, backings);
    if (backings.empty()) return kIdentityMismatch;
    File mem(open("/proc/self/mem", O_RDONLY | O_CLOEXEC));
    if (mem.fd < 0) return kMapsUnavailable;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    do {
        std::vector<Mapping> maps;
        if (!readMaps(maps)) return kMapsUnavailable;
        const Backing* selected = nullptr;
        uintptr_t selectedBias = 0;
        for (const auto& b : backings) {
            for (const auto& m : maps) {
                if (!sameBacking(m, b) || m.protection != (PROT_READ | PROT_EXEC) ||
                    m.offset < b.offset) continue;
                const uint64_t relative = m.offset - b.offset;
                if (relative < kTextVaddr || relative >= kTextVaddr + kTextSize || relative > m.start) continue;
                // Validated PT_LOAD has p_offset == p_vaddr. APK mapping offsets
                // include the ZIP data offset, which must be removed first.
                const uintptr_t bias = m.start - relative;
                if (bias % pageSize != 0 || !mappedImage(maps, b, bias, pageSize)) continue;
                if (selected != nullptr && (selected != &b || selectedBias != bias)) return kIdentityMismatch;
                selected = &b;
                selectedBias = bias;
            }
        }
        if (selected != nullptr) {
            const jint result = install(*selected, selectedBias, pageSize, mem.fd, patches);
            if (result == kInstalled) {
                __android_log_print(ANDROID_LOG_INFO, kTag,
                    "Custom background ARGB=%08x and P3 installed at load_bias=%p; rendering RUNTIME UNVERIFIED",
                    background, reinterpret_cast<void*>(selectedBias));
            }
            return result;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    } while (std::chrono::steady_clock::now() < deadline);
    return kTimedOut;
}

bool launcherProcess() {
    File file(open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC));
    char name[64]{};
    const ssize_t size = file.fd >= 0 ? read(file.fd, name, sizeof(name)) : -1;
    constexpr char expected[] = "com.miui.home";
    return size >= static_cast<ssize_t>(sizeof(expected)) && memcmp(name, expected, sizeof(expected)) == 0;
}

bool javaString(JNIEnv* env, jstring input, std::string& output) {
    if (input == nullptr) return false;
    const jsize length = env->GetStringUTFLength(input);
    if (length < 0 || length >= PATH_MAX) return false;
    const char* value = env->GetStringUTFChars(input, nullptr);
    if (value == nullptr) return false;
    try {
        output.assign(value, static_cast<size_t>(length));
    } catch (...) {
        env->ReleaseStringUTFChars(input, value);
        throw;
    }
    env->ReleaseStringUTFChars(input, value);
    return true;
}
} // namespace
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4NativeHooks_nativeConfigure(
        JNIEnv* env, jobject, jstring nativeDir, jstring apkPath, jint background) {
#if defined(__aarch64__)
    if (!launcherProcess()) return kUnsupported;
    try {
        std::string directory, apk;
        if (!javaString(env, nativeDir, directory) || !javaString(env, apkPath, apk) ||
            apk.empty() || apk.front() != '/' || (!directory.empty() && directory.front() != '/')) return kInvalidInput;
        if (gStarted.exchange(true)) return gStatus.load();
        std::thread([directory, apk, background] {
            jint result;
            try { result = run(directory, apk, static_cast<uint32_t>(background)); }
            catch (...) { result = kWorkerFailed; }
            gStatus.store(result);
            if (result != kInstalled) {
                __android_log_print(ANDROID_LOG_WARN, kTag, "Palette installation stopped: status=%d%s", result,
                    result == kRecoveryFailed ? "; recovery incomplete, launcher restart required" : "");
            }
        }).detach();
        return gStatus.load();
    } catch (...) {
        gStatus.store(kWorkerFailed);
        return kWorkerFailed;
    }
#else
    (void)env; (void)nativeDir; (void)apkPath; (void)background;
    return -1; // Other ABIs retain the legacy Java module; no native patching.
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4NativeHooks_nativeStatus(JNIEnv*, jobject) {
#if defined(__aarch64__)
    return gStatus.load();
#else
    return -1;
#endif
}
