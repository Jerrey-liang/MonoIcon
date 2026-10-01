#pragma once

#include <atomic>
#include <cerrno>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <fcntl.h>
#include <linux/futex.h>
#include <signal.h>
#include <sys/syscall.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

namespace hyperos4 {
// AArch64 CSEL->MOV and TBZ->B are not concurrent-hotpatch-safe instruction
// pairs. Park every peer before writing, and execute ISB on every parked peer
// after cache maintenance. Refuse the patch if this cannot be established.
// All storage persists until process exit, including after a timed-out signal.
constexpr size_t kMaxThreads = 1024;
constexpr int kSignalCookie = 0x4d493450;
struct ThreadSlot {
    std::atomic<int> tid{0};
    std::atomic<int> parked{0};
    std::atomic<uintptr_t> interruptedPc{0};
};
inline ThreadSlot slots[kMaxThreads];
inline std::atomic<int> slotCount{0};
inline std::atomic<int> pauseActive{0};
inline std::atomic<int> ownerPid{0};
static_assert(std::atomic<int>::is_always_lock_free && sizeof(std::atomic<int>) == sizeof(int));
static_assert(std::atomic<uintptr_t>::is_always_lock_free);

inline void pauseHandler(int, siginfo_t* info, void* context) {
    const int savedErrno = errno;
    if (info != nullptr && info->si_code == SI_QUEUE &&
        info->si_pid == ownerPid.load() && info->si_value.sival_int == kSignalCookie && pauseActive.load()) {
        const int tid = static_cast<int>(syscall(SYS_gettid));
        const int count = slotCount.load();
        for (int i = 0; i < count; ++i) {
            if (slots[i].tid.load() != tid) continue;
            // The kernel supplies this AArch64 signal context. Publish the
            // interrupted PC before acknowledgment; zero marks unavailable or
            // malformed context and makes the optional span check fail closed.
            uintptr_t pc = 0;
            if (context != nullptr && reinterpret_cast<uintptr_t>(context) % alignof(ucontext_t) == 0) {
                pc = static_cast<uintptr_t>(static_cast<const ucontext_t*>(context)->uc_mcontext.pc);
                if ((pc & 3) != 0) pc = 0;
            }
            slots[i].interruptedPc.store(pc);
            slots[i].parked.store(1);
            const timespec retry{0, 10000000};
            while (pauseActive.load()) {
                syscall(SYS_futex, &pauseActive, FUTEX_WAIT_PRIVATE, 1, &retry, nullptr, 0);
            }
            // The patching thread completed D/I cache maintenance before release.
            asm volatile("isb" ::: "memory");
            slots[i].parked.store(0);
            break;
        }
    }
    errno = savedErrno;
}

// Raw getdents64 avoids libc/allocator/stdio locks held by a parked thread.
struct DirectoryEntry {
    uint64_t inode;
    int64_t offset;
    unsigned short recordLength;
    unsigned char type;
    char name[1];
};

inline int listThreads(int* tids) {
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, "/proc/self/task",
                                           O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0));
    if (fd < 0) return -1;
    alignas(8) char buffer[4096];
    int count = 0;
    bool valid = true;
    while (valid) {
        const long bytes = syscall(SYS_getdents64, fd, buffer, sizeof(buffer));
        if (bytes < 0 && errno == EINTR) continue;
        if (bytes < 0) { valid = false; break; }
        if (bytes == 0) break;
        for (long pos = 0; pos < bytes;) {
            constexpr size_t prefix = offsetof(DirectoryEntry, name);
            if (bytes - pos <= static_cast<long>(prefix)) { valid = false; break; }
            const auto* entry = reinterpret_cast<const DirectoryEntry*>(buffer + pos);
            if (entry->recordLength <= prefix || entry->recordLength > bytes - pos) { valid = false; break; }
            int tid = 0;
            bool numeric = true, terminated = false;
            const char* name = buffer + pos + prefix;
            for (size_t i = 0; i < entry->recordLength - prefix; ++i) {
                const char c = name[i];
                if (c == '\0') { terminated = true; break; }
                if (c < '0' || c > '9' || tid > (INT_MAX - (c - '0')) / 10) { numeric = false; break; }
                tid = tid * 10 + c - '0';
            }
            if (numeric && terminated && tid > 0) {
                if (count == static_cast<int>(kMaxThreads)) { valid = false; break; }
                tids[count++] = tid;
            }
            pos += entry->recordLength;
        }
    }
    syscall(SYS_close, fd);
    return valid ? count : -1;
}

inline int64_t monotonicNanos() {
    timespec now{};
    if (syscall(SYS_clock_gettime, CLOCK_MONOTONIC, &now) != 0) return -1;
    return int64_t(now.tv_sec) * 1000000000 + now.tv_nsec;
}

class ThreadPause {
public:
    ThreadPause() = default;
    ThreadPause(const ThreadPause&) = delete;
    ThreadPause& operator=(const ThreadPause&) = delete;
    ~ThreadPause() { release(); }
    bool finish() { return release(); }

    // A peer stopped inside a multi-instruction rewrite could resume with only
    // part of the old sequence executed. Check the entire affected function (or
    // other proven independent span), not just the individual patched words.
    bool excludes(uintptr_t start, uintptr_t end) const {
        if (!acquired_ || !pauseActive.load() || start == 0 || start >= end || ((start | end) & 3) != 0) {
            return false;
        }
        const int count = slotCount.load();
        if (count < 0 || count > static_cast<int>(kMaxThreads)) return false;
        for (int i = 0; i < count; ++i) {
            // Slots for peers that exited before acknowledgment may remain.
            // acquire() already verified every currently live peer is parked.
            if (!slots[i].parked.load()) continue;
            const uintptr_t pc = slots[i].interruptedPc.load();
            if (pc == 0 || (pc & 3) != 0 || (pc >= start && pc < end)) return false;
        }
        return true;
    }

    bool acquire() {
        acquired_ = false;
        const uint64_t mask = UINT64_MAX;
        if (syscall(SYS_rt_sigprocmask, SIG_SETMASK, &mask, &previousMask_, sizeof(mask)) != 0) return false;
        maskChanged_ = true;
        // SIGURG's default is ignore. A late queued signal is therefore harmless
        // even if a blocked peer receives it only after restoration on timeout.
        struct sigaction observed{};
        if (sigaction(SIGURG, nullptr, &observed) != 0 || !ignored(observed)) return false;
        struct sigaction action{};
        action.sa_sigaction = pauseHandler;
        action.sa_flags = SA_SIGINFO | SA_RESTART | SA_ONSTACK;
        sigfillset(&action.sa_mask);
        ownerPid.store(static_cast<int>(syscall(SYS_getpid)));
        self_ = static_cast<int>(syscall(SYS_gettid));
        if (sigaction(SIGURG, &action, &previous_) != 0) return false;
        installed_ = true;
        if (!ignored(previous_)) return false;
        const int64_t started = monotonicNanos();
        if (started < 0) return false;
        pauseActive.store(1);
        while (true) {
            int tids[kMaxThreads];
            const int count = listThreads(tids);
            if (count < 0) return false;
            bool allParked = true;
            for (int i = 0; i < count; ++i) {
                if (tids[i] == self_) continue;
                int index = 0;
                int known = slotCount.load();
                while (index < known && slots[index].tid.load() != tids[i]) ++index;
                if (index == known) {
                    if (known == static_cast<int>(kMaxThreads)) return false;
                    slots[index].tid.store(tids[i]);
                    slotCount.store(known + 1);
                    siginfo_t signal{};
                    signal.si_signo = SIGURG;
                    signal.si_code = SI_QUEUE;
                    signal.si_pid = ownerPid.load();
                    signal.si_uid = static_cast<uid_t>(syscall(SYS_getuid));
                    signal.si_value.sival_int = kSignalCookie;
                    const long sent = syscall(SYS_rt_tgsigqueueinfo, ownerPid.load(), tids[i], SIGURG, &signal);
                    if (sent != 0 && errno != ESRCH) return false;
                    // Always enumerate again after sending, including ESRCH.
                    allParked = false;
                } else if (!slots[index].parked.load()) {
                    allParked = false;
                }
            }
            if (allParked) {
                // A peer could have cloned after the first enumeration but
                // before acknowledging its signal. Scan again only after the
                // first set has acknowledged; any new peer must also be parked.
                const int finalCount = listThreads(tids);
                if (finalCount < 0) return false;
                for (int i = 0; i < finalCount; ++i) {
                    if (tids[i] == self_) continue;
                    int index = 0;
                    const int known = slotCount.load();
                    while (index < known && slots[index].tid.load() != tids[i]) ++index;
                    if (index == known || !slots[index].parked.load()) { allParked = false; break; }
                }
            }
            if (allParked) {
                // Every current peer is inside our handler. No peer can now
                // create another thread, unmap the snapshot, or change handlers.
                struct sigaction current{};
                acquired_ = sigaction(SIGURG, nullptr, &current) == 0 && ours(current);
                return acquired_;
            }
            const int64_t now = monotonicNanos();
            if (now < 0 || now - started >= 150000000) return false;
            const timespec delay{0, 1000000};
            syscall(SYS_nanosleep, &delay, nullptr);
        }
    }

private:
    static bool ignored(const struct sigaction& action) {
        return action.sa_handler == SIG_DFL || action.sa_handler == SIG_IGN;
    }
    static bool ours(const struct sigaction& action) {
        return (action.sa_flags & SA_SIGINFO) && action.sa_sigaction == pauseHandler;
    }
    bool release() {
        acquired_ = false;
        bool restored = true;
        if (installed_) {
            // No storage is freed: a signal already in flight can finish safely.
            // Restore before waking peers, so the successful full-pause path
            // cannot race a resumed peer registering a new signal handler.
            // On acquisition failure some peers may still run; query/restore is
            // necessarily best-effort (sigaction has no compare-and-swap API).
            struct sigaction current{};
            if (sigaction(SIGURG, nullptr, &current) != 0) {
                restored = false;
            } else if (ours(current)) {
                restored = sigaction(SIGURG, &previous_, nullptr) == 0;
            }
            pauseActive.store(0);
            // Timed waits also release peers if this wake syscall is denied.
            syscall(SYS_futex, &pauseActive, FUTEX_WAKE_PRIVATE, INT_MAX, nullptr, nullptr, 0);
            installed_ = false;
        }
        if (maskChanged_) {
            restored &= syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previousMask_, nullptr, sizeof(previousMask_)) == 0;
            maskChanged_ = false;
        }
        return restored;
    }
    struct sigaction previous_{};
    int self_ = 0;
    bool installed_ = false;
    uint64_t previousMask_ = 0;
    bool maskChanged_ = false;
    bool acquired_ = false;
};
} // namespace hyperos4
