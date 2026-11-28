// Optional large-icon geometry and MAML resource selection. No launcher instructions are modified.
#include <fcntl.h>
#include <pthread.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>

#include "hyperos4_builtin.h"
#include "hyperos4_profile_runtime.h"
#include <sys/syscall.h>
#include <time.h>
#include "hyperos4_icon_files.h"
#include "hyperos4_paths.h"
#include "hyperos4_geometry_bridge.h"
#include "launcher_safety.h"
#include "hyperos4_diagnostics.h"

namespace monoicon::hyos {

constexpr size_t kConfigCapacity = 256;

namespace profile = monoicon::profile;
profile::Profile gProfile;
profile::Runtime gRuntime;
std::string gProfileDigest, gProfileError;
std::vector<uint32_t> gCurrentInstructions;
bool gDiagnosticRuntimeReady = false, gDiagnosticImagesValid = false;
bool gDiagnosticMappingMatchesProfile = false;
std::string gDiagnosticRequestedProfileDigest;
bool gDiagnosticInstallationStopped = false;
bool gDiagnosticDirty = true;
char gDiagnosticPhase[96] = "startup";
std::string gDiagnosticNonce;

extern "C" const profile::Profile* monoicon_hyos_active_profile() { return &gProfile; }

// Diagnostic result values used by the runtime report.
enum LargeIconResult : uint32_t {
    kResultNone = 0, kResultInstalled = 1, kResultNoFile = 2, kResultParseFailed = 3,
    kResultImageMissing = 4, kResultIdentityMismatch = 5, kResultWorkerUnavailable = 7,
    kResultVerifyFailed = 10, kResultProfileInvalid = 13, kResultSafetyBlocked = 15
};
std::atomic<uint32_t> gResult{kResultNone};
std::atomic<uint32_t> gWorkerPid{0};

// Immutable generation selected for the optional large-icon extension.
// ---------------------------------------------------------------------------
struct LargeIconConfig {
    char generation[35] = {};
    char profileDigest[65] = {};
    char payload[kConfigCapacity] = {};
};

LargeIconResult ReadGeneration(LargeIconConfig& config) {
    char path[160];
    LauncherFilePath(path, sizeof(path), "monoicon_large_icons.active");
    const int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return kResultNoFile;
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size >= static_cast<off_t>(sizeof(config.payload))) {
        close(fd);
        return kResultParseFailed;
    }
    size_t total = 0;
    while (total < static_cast<size_t>(st.st_size)) {
        const ssize_t count = read(fd, config.payload + total, st.st_size - total);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { close(fd); return kResultParseFailed; }
        total += static_cast<size_t>(count);
    }
    close(fd);
    constexpr char prefix[] = "MONOICON_LARGE 1 ";
    constexpr size_t start = sizeof(prefix) - 1;
    if (total != start + 34 + 1 + 64 + 1 || memcmp(config.payload, prefix, start) ||
        config.payload[start] != 'g' || config.payload[start + 1] != '-' ||
        config.payload[start + 34] != ' ' || config.payload[total - 1] != '\n') return kResultParseFailed;
    memcpy(config.generation, config.payload + start, 34);
    memcpy(config.profileDigest, config.payload + start + 35, 64);
    if (strcmp(config.profileDigest, profile::kLargeIconDigest) != 0 ||
        !std::all_of(config.generation + 2, config.generation + 34, [](char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        })) return kResultProfileInvalid;
    return kResultNone;
}

bool ValidateImages() {
    uint32_t checked = 0;
    const bool valid = gRuntime.validate(gProfile, gCurrentInstructions, checked);
    gDiagnosticImagesValid = valid;
    return valid;
}

namespace {
bool ReadDiagnosticText(const char* path, char* output, size_t capacity, bool regular = false) {
    if (capacity < 2) return false;
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path,
        O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0));
    if (fd < 0) return false;
    struct stat metadata{};
    bool valid = !regular || (syscall(SYS_fstat, fd, &metadata) == 0 && S_ISREG(metadata.st_mode) &&
        metadata.st_size > 0 && static_cast<uint64_t>(metadata.st_size) < capacity);
    size_t used = 0;
    while (valid && used < capacity - 1) {
        const auto count = syscall(SYS_read, fd, output + used, capacity - 1 - used);
        if (count > 0) used += static_cast<size_t>(count);
        else if (count < 0 && errno == EINTR) continue;
        else { valid = count == 0; break; }
    }
    syscall(SYS_close, fd);
    output[used] = '\0';
    return valid && used > 0 && used < capacity - 1 && (!regular ||
        (used == static_cast<size_t>(metadata.st_size) && std::memchr(output, '\0', used) == nullptr));
}

uint64_t DiagnosticStartTicks() {
    char status[4096]{};
    if (!ReadDiagnosticText("/proc/self/stat", status, sizeof(status))) return 0;
    // comm (field 2) can itself contain spaces and parentheses.
    const char* end = std::strrchr(status, ')');
    if (!end) return 0;
    const char* value = end + 1;
    for (unsigned field = 3; field < 22; ++field) {
        while (*value == ' ') ++value;
        while (*value && *value != ' ') ++value;
        if (!*value) return 0;
    }
    while (*value == ' ') ++value;
    uint64_t ticks = 0;
    bool digit = false;
    while (*value >= '0' && *value <= '9') {
        const unsigned number = static_cast<unsigned>(*value++ - '0');
        if (ticks > (UINT64_MAX - number) / 10) return 0;
        ticks = ticks * 10 + number; digit = true;
    }
    return digit && (*value == ' ' || *value == '\n' || !*value) ? ticks : 0;
}

std::string ReadDiagnosticNonce() {
    char path[256], text[129]{};
    LauncherFilePath(path, sizeof(path), "monoicon_hyos_hooks.request");
    if (!ReadDiagnosticText(path, text, sizeof(text), true)) return {};
    size_t length = std::strlen(text);
    if (length && text[length - 1] == '\n') text[--length] = '\0';
    if (length < 16 || length > 64) return {};
    for (size_t i = 0; i < length; ++i) if (!((text[i] >= '0' && text[i] <= '9') ||
        (text[i] >= 'a' && text[i] <= 'f'))) return {};
    return std::string(text, length);
}

bool WriteHookDiagnostics(const std::string& nonce) {
    using namespace diagnostics;
    const uint64_t startTicks = DiagnosticStartTicks();
    // A snapshot that cannot establish process ownership is unusable; preserve
    // the last file so the reader can explicitly reject its old identity.
    if (!startTicks) return false;
    char boot[64]{};
    ReadDiagnosticText("/proc/sys/kernel/random/boot_id", boot, sizeof(boot));
    if (const size_t length = std::strlen(boot); length && boot[length - 1] == '\n') boot[length - 1] = '\0';
    timespec now{};
    const bool haveTime = syscall(SYS_clock_gettime, CLOCK_BOOTTIME, &now) == 0;
    timespec wall{};
    const bool haveWall = syscall(SYS_clock_gettime, CLOCK_REALTIME, &wall) == 0;
    std::string output; output.reserve(32 * 1024);
    output += '{'; Number(output, "schema", 1);
    output += ','; Number(output, "pid", static_cast<uint32_t>(getpid()));
    output += ','; Number(output, "startTicks", startTicks);
    output += ','; Text(output, "process", "com.miui.home");
    output += ','; Text(output, "bootId", boot);
    output += ','; Number(output, "updatedElapsedMs", haveTime ? uint64_t(now.tv_sec) * 1000 + now.tv_nsec / 1000000 : 0);
    output += ','; Number(output, "capturedAtMillis", haveWall ? uint64_t(wall.tv_sec) * 1000 + wall.tv_nsec / 1000000 : 0);
    output += ','; Text(output, "requestNonce", nonce.c_str());
    output += ','; Text(output, "profileId", gProfile.id.c_str());
    output += ','; Text(output, "profileDigest", gProfileDigest.c_str());
    output += ','; Text(output, "requestedProfileDigest", gDiagnosticRequestedProfileDigest.c_str());
    output += ','; Text(output, "phase", gDiagnosticPhase);
    output += ','; Number(output, "result", gResult.load(std::memory_order_acquire));
    output += ','; Text(output, "reason", gProfileError.c_str());
    output += ','; Number(output, "pageSize", static_cast<unsigned>(getpagesize()));
    output += ','; Number(output, "installerStopped", gDiagnosticInstallationStopped ? 1 : 0);
    output += ','; Number(output, "iconsEnabled", monoicon_hyos_icons_enabled() ? 1 : 0);
    output += ','; Quote(output, "nativeInit"); output += ':'; monoicon_hyos_append_init_diagnostics(output);
    output += ','; Quote(output, "images"); output += ":[";
    for (size_t i = 0; i < gProfile.images.size(); ++i) {
        if (i) output += ',';
        const auto& image = gProfile.images[i];
        const bool mapped = gDiagnosticMappingMatchesProfile && i < gRuntime.images.size() && gRuntime.images[i].bias;
        output += '{'; Text(output, "id", image.id.c_str());
        output += ','; Text(output, "library", image.library.c_str());
        output += ','; Text(output, "path", mapped ? gRuntime.images[i].path.c_str() : "");
        output += ','; Hex(output, "loadBias", mapped ? gRuntime.images[i].bias : 0, mapped);
        output += ','; Hex(output, "fileOffset", mapped ? gRuntime.images[i].fileOffset : 0, mapped);
        output += ','; Hex(output, "inode", mapped ? gRuntime.images[i].inode : 0, mapped);
        output += ','; Text(output, "state", mapped && gDiagnosticRuntimeReady && gDiagnosticImagesValid ? "verified" : "unverified");
        output += '}';
    }
    output += "],"; Quote(output, "points"); output += ":[";
    monoicon_hyos_append_startup_diagnostics(output);
    monoicon_hyos_append_icon_diagnostics(output);
    output += "]}\n";
    if (output.size() > 1024 * 1024) return false;
    char path[256], stage[256], name[96];
    LauncherFilePath(path, sizeof(path), "monoicon_hyos_hooks.json");
    std::snprintf(name, sizeof(name), "monoicon_hyos_hooks.json.%d.new", static_cast<int>(getpid()));
    LauncherFilePath(stage, sizeof(stage), name);
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, stage,
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600));
    if (fd < 0) return false;
    struct stat metadata{};
    const bool regular = syscall(SYS_fstat, fd, &metadata) == 0 && S_ISREG(metadata.st_mode);
    size_t written = 0;
    while (regular && written < output.size()) {
        const auto count = syscall(SYS_write, fd, output.data() + written, output.size() - written);
        if (count > 0) written += static_cast<size_t>(count);
        else if (count < 0 && errno == EINTR) continue;
        else break;
    }
    syscall(SYS_close, fd);
    if (written == output.size() && syscall(SYS_renameat, AT_FDCWD, stage, AT_FDCWD, path) == 0) return true;
    syscall(SYS_unlinkat, AT_FDCWD, stage, 0);
    return false;
}

void MaybeWriteHookDiagnostics() {
    const int savedErrno = errno;
    const auto nonce = ReadDiagnosticNonce();
    if ((gDiagnosticDirty || (!nonce.empty() && nonce != gDiagnosticNonce)) && WriteHookDiagnostics(nonce)) {
        gDiagnosticDirty = false;
        gDiagnosticNonce = nonce;
    }
    errno = savedErrno;
}
} // namespace

void Report(LargeIconResult result, const char* phase) {
    gResult.store(result, std::memory_order_release);
    std::snprintf(gDiagnosticPhase, sizeof(gDiagnosticPhase), "%s", phase);
    gDiagnosticDirty = true;
}

std::atomic<bool> gIconsEnabled{false};

extern "C" bool monoicon_hyos_icons_enabled() {
    return gIconsEnabled.load(std::memory_order_acquire);
}

struct LargeIconState {
    bool profileReady = false;
    std::string applied;
    std::string previousFailure;
};

bool UpdateGeneration(LargeIconState& state) {
    LargeIconConfig next;
    LargeIconResult result = ReadGeneration(next);
    if (result == kResultNone && !state.applied.empty() && state.applied == next.payload) return true;
    if (result == kResultNone && !state.profileReady) {
        gDiagnosticRequestedProfileDigest = next.profileDigest;
        if (!profile::LoadProfile(next.profileDigest, gProfile, gProfileError)) result = kResultProfileInvalid;
        else if (!gRuntime.discover(gProfile, gProfileError)) {
            result = gProfileError.rfind("image-missing:", 0) == 0 ? kResultImageMissing : kResultIdentityMismatch;
        }
        else {
            gCurrentInstructions.clear();
            for (const auto& patch : gProfile.patches) gCurrentInstructions.push_back(patch.original);
            if (!ValidateImages()) result = kResultIdentityMismatch;
            else if (!safety::Begin()) result = kResultSafetyBlocked;
            else {
                gProfileDigest = next.profileDigest;
                state.profileReady = gDiagnosticRuntimeReady = gDiagnosticMappingMatchesProfile = true;
                gProfileError.clear();
            }
        }
    }
    if (result == kResultNone && !monoicon_hyos_icon_generation_ready(next.generation)) result = kResultNoFile;
    if (result == kResultNone && !monoicon_hyos_prepare_icon_files()) result = kResultVerifyFailed;
    if (result != kResultNone) {
        gIconsEnabled.store(false, std::memory_order_release);
        state.applied.clear();
        const std::string failure = std::to_string(result) + gProfileError;
        if (failure != state.previousFailure) Report(result, result == kResultNoFile ? "inactive" : "large-icon-unavailable");
        state.previousFailure = failure;
        return result != kResultSafetyBlocked && result != kResultIdentityMismatch;
    }
    monoicon_hyos_select_icon_generation(next.generation);
    gIconsEnabled.store(true, std::memory_order_release);
    monoicon_hyos_start_geometry_bridge(&gRuntime, &gProfile, gProfileDigest.c_str());
    state.applied = next.payload;
    state.previousFailure.clear();
    Report(kResultInstalled, "large-icons-ready");
    return true;
}

void* LargeIconWorker(void* data) {
    std::unique_ptr<LargeIconState> state(static_cast<LargeIconState*>(data));
    bool keepInstalling = true;
    while (true) {
        // A blocked worker still serves read-only diagnostics.
        if (keepInstalling) {
            keepInstalling = UpdateGeneration(*state);
            gDiagnosticInstallationStopped = !keepInstalling;
        }
        MaybeWriteHookDiagnostics();
        usleep(500000);
    }
}

// Start one worker in the specialized launcher process.
extern "C" uint32_t monoicon_hyos_start_large_icons() {
    // Do not create installer threads before HYOS has specialized the child UID.
    if (getuid() == 0) return kResultImageMissing;
    const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return kResultImageMissing;
    char name[64] = {};
    ssize_t count;
    do { count = read(fd, name, sizeof(name)); } while (count < 0 && errno == EINTR);
    close(fd);
    constexpr char launcher[] = "com.miui.home";
    if (count < static_cast<ssize_t>(sizeof(launcher)) ||
        std::memcmp(name, launcher, sizeof(launcher)) != 0) return kResultImageMissing;

    const uint32_t pid = static_cast<uint32_t>(getpid());
    uint32_t owner = gWorkerPid.load(std::memory_order_acquire);
    do {
        if (owner == pid) return gResult.load(std::memory_order_acquire);
    } while (!gWorkerPid.compare_exchange_weak(owner, pid,
                std::memory_order_acq_rel, std::memory_order_acquire));

    auto* state = new LargeIconState();
    pthread_t worker;
    const int error = pthread_create(&worker, nullptr, LargeIconWorker, state);
    if (error != 0) {
        delete state;
        gWorkerPid.store(0, std::memory_order_release);
        Report(kResultWorkerUnavailable, "worker-unavailable");
        return kResultWorkerUnavailable;
    }
    pthread_detach(worker);
    return gResult.load(std::memory_order_acquire);
}

extern "C" bool monoicon_hyos_large_icons_started() {
    return gWorkerPid.load(std::memory_order_acquire) == static_cast<uint32_t>(getpid());
}

}  // namespace monoicon::hyos
