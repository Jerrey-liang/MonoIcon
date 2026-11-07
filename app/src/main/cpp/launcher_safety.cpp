#include "launcher_safety.h"
#include "launcher_safety_policy.h"

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <pthread.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

namespace monoicon::safety {
namespace {
constexpr char kState[] = "monoicon_safety_state.txt";
constexpr char kReset[] = "monoicon_safety_reset.txt";
constexpr char kExpected[] = "monoicon_safety_expected.txt";
constexpr char kLock[] = "monoicon_safety.lock";
pthread_mutex_t gMutex = PTHREAD_MUTEX_INITIALIZER;
std::atomic<pid_t> gOwner{0};
std::atomic<bool> gAllowed{false};

bool Read(int parent, const char* name, std::string& output, bool& missing) {
    output.clear(); missing = false;
    const int fd = openat(parent, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) { missing = errno == ENOENT; return missing; }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) { close(fd); return false; }
    char bytes[513]; size_t total = 0;
    while (total < sizeof(bytes)) {
        const ssize_t count = read(fd, bytes + total, sizeof(bytes) - total);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) { close(fd); return false; }
        if (count == 0) break;
        total += static_cast<size_t>(count);
    }
    close(fd);
    if (total == sizeof(bytes)) return false;
    output.assign(bytes, total);
    return true;
}

bool IsLauncher() {
    if (getuid() == 0) return false;
    const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char name[64]{};
    ssize_t count;
    do { count = read(fd, name, sizeof(name)); } while (count < 0 && errno == EINTR);
    close(fd);
    constexpr char launcher[] = "com.miui.home";
    return count >= static_cast<ssize_t>(sizeof(launcher)) && std::memcmp(name, launcher, sizeof(launcher)) == 0;
}

bool Current(Identity& identity, uint64_t& nowMs) {
    std::string text; bool missing;
    if (!Read(AT_FDCWD, "/proc/sys/kernel/random/boot_id", text, missing) || missing) return false;
    const auto boot = Fields(text);
    if (boot.size() != 1 || !ValidBoot(boot[0])) return false;
    identity.boot = boot[0]; identity.pid = static_cast<uint64_t>(getpid());
    if (!Read(AT_FDCWD, "/proc/self/stat", text, missing) || missing) return false;
    const size_t end = text.rfind(')');
    if (end == std::string::npos) return false;
    const auto fields = Fields(text.substr(end + 1));
    if (fields.size() <= 19 || !Number(fields[19], identity.start) || identity.start == 0) return false;
    timespec now{};
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0 || now.tv_sec < 0) return false;
    nowMs = static_cast<uint64_t>(now.tv_sec) * 1000 + static_cast<uint64_t>(now.tv_nsec) / 1000000;
    return true;
}

// Every writer serializes through a separate inode so rename cannot invalidate
// the lock. The app only publishes independent reset/expected receipts by rename.
struct LockedDirectory {
    int directory = -1, lock = -1;
    LockedDirectory() {
        char path[160];
        std::snprintf(path, sizeof(path), "/data/user_de/%u/com.miui.home/files", static_cast<unsigned>(getuid()) / 100000u);
        if (mkdir(path, 0700) != 0 && errno != EEXIST) return;
        directory = open(path, O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW);
        if (directory < 0) return;
        lock = openat(directory, kLock, O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600);
        if (lock < 0) return;
        struct stat st{};
        if (fstat(lock, &st) != 0 || !S_ISREG(st.st_mode)) { close(lock); lock = -1; return; }
        for (int attempt = 0; attempt < 100; ++attempt) {
            if (flock(lock, LOCK_EX | LOCK_NB) == 0) return;
            if (errno != EINTR && errno != EWOULDBLOCK) break;
            usleep(1000);
        }
        close(lock); lock = -1;
    }
    ~LockedDirectory() {
        if (lock >= 0) { flock(lock, LOCK_UN); close(lock); }
        if (directory >= 0) close(directory);
    }
    bool valid() const { return lock >= 0; }
};

bool WriteState(int directory, const State& state) {
    char temporary[80];
    std::snprintf(temporary, sizeof(temporary), "monoicon_safety_state.tmp.%u", static_cast<unsigned>(getpid()));
    const int fd = openat(directory, temporary, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600);
    if (fd < 0) return false;
    struct stat st{};
    bool okay = fstat(fd, &st) == 0 && S_ISREG(st.st_mode);
    const std::string bytes = Encode(state);
    size_t total = 0;
    while (okay && total < bytes.size()) {
        const ssize_t count = write(fd, bytes.data() + total, bytes.size() - total);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { okay = false; break; }
        total += static_cast<size_t>(count);
    }
    okay = okay && fsync(fd) == 0;
    close(fd);
    if (okay) okay = renameat(directory, temporary, directory, kState) == 0 && fsync(directory) == 0;
    if (!okay) unlinkat(directory, temporary, 0);
    return okay;
}

bool ReadReset(int directory, std::string& reset) {
    std::string bytes; bool missing;
    if (!Read(directory, kReset, bytes, missing)) return false;
    if (missing) { reset = "-"; return true; }
    const auto fields = Fields(bytes);
    if (fields.size() != 1 || !ValidReset(fields[0])) return false;
    reset = fields[0]; return true;
}

struct Checkpoint { State state; };
void* MarkHealthy(void* data) {
    std::unique_ptr<Checkpoint> checkpoint(static_cast<Checkpoint*>(data));
    for (;;) {
        timespec delay{1, 0};
        while (nanosleep(&delay, &delay) != 0 && errno == EINTR) {}
        Identity current; uint64_t now = 0;
        if (!Current(current, now) || !(current == checkpoint->state.process)) return nullptr;
        if (now < checkpoint->state.armedMs || now - checkpoint->state.armedMs < kHealthyAfterMs) continue;
        LockedDirectory files;
        if (!files.valid()) return nullptr; // Pending remains fail-closed on the next process.
        std::string bytes, reset; bool missing; State state;
        if (!Read(files.directory, kState, bytes, missing) || missing || !ParseState(bytes, state) ||
            !ReadReset(files.directory, reset)) return nullptr;
        if (reset == "-") reset = state.reset;
        if (reset != checkpoint->state.reset ||
            !CanMarkHealthy(state, current, reset, now)) return nullptr;
        state.healthy = true;
        WriteState(files.directory, state);
        return nullptr;
    }
}
} // namespace

bool Begin() {
    if (!IsLauncher()) return false;
    const pid_t pid = getpid();
    if (gOwner.load(std::memory_order_acquire) == pid) return gAllowed.load(std::memory_order_acquire);
    pthread_mutex_lock(&gMutex);
    struct Unlock { ~Unlock() { pthread_mutex_unlock(&gMutex); } } unlock;
    if (gOwner.load(std::memory_order_acquire) == pid) return gAllowed.load(std::memory_order_acquire);
    gAllowed.store(false, std::memory_order_release);
    // Publish the owner only once the startup decision is complete; another
    // hook thread waits on the mutex instead of observing a temporary rejection.
    struct Publish { pid_t pid; ~Publish() { gOwner.store(pid, std::memory_order_release); } } publish{pid};
    Identity current; uint64_t now = 0;
    if (!Current(current, now)) return false;
    LockedDirectory files;
    if (!files.valid()) return false;
    std::string bytes, reset; bool missing;
    if (!ReadReset(files.directory, reset)) return false;
    State previous;
    const bool readable = Read(files.directory, kState, bytes, missing);
    const bool parsed = readable && !missing && ParseState(bytes, previous);
    Identity expected;
    std::string expectedBytes; bool expectedMissing;
    const bool hasExpected = Read(files.directory, kExpected, expectedBytes, expectedMissing) &&
        !expectedMissing && ParseExpected(expectedBytes, expected);
    // A receipt names the old process, so even failure to unlink cannot exempt
    // this newly armed process. A blocked state never consults this receipt.
    unlinkat(files.directory, kExpected, 0);
    State next = Start(parsed ? &previous : nullptr, current, now, reset, hasExpected ? &expected : nullptr);
    if (!readable || (!missing && !parsed)) next.blocked = true;
    if (!WriteState(files.directory, next) || next.blocked) return false;
    if (!next.healthy) {
        auto* checkpoint = new Checkpoint{next};
        pthread_t worker;
        if (pthread_create(&worker, nullptr, MarkHealthy, checkpoint) != 0) {
            delete checkpoint; next.blocked = true; WriteState(files.directory, next); return false;
        }
        pthread_detach(worker);
    }
    gAllowed.store(true, std::memory_order_release);
    return true;
}

bool IsBlocked() {
    return gOwner.load(std::memory_order_acquire) == getpid() && !gAllowed.load(std::memory_order_acquire);
}
} // namespace monoicon::safety
