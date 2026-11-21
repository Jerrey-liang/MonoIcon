// Profile-driven execution of the existing HyperOS 4 palette transaction.
// Discovery and preparation precede the thread rendezvous; code writes retain
// private-page checks, full-function verification, cache maintenance and rollback.

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits.h>
#include <memory>

#include "hyperos4_code_page.h"
#include "hyperos4_profile_runtime.h"
#include <array>
#include "hyperos4_thread_pause.h"
#include "hyperos4_icon_files.h"
#include "hyperos4_paths.h"
#include "hyperos4_geometry_bridge.h"
#include "launcher_safety.h"
#include "hyperos4_diagnostics.h"

namespace monoicon::hyos {

constexpr char kLogTag[] = "MonoIcon.Palette";
constexpr size_t kConfigCapacity = 256;

namespace profile = monoicon::profile;
profile::Profile gProfile;
profile::Runtime gRuntime;
std::string gProfileDigest, gProfileError;
std::vector<uint32_t> gCurrentInstructions;
constexpr size_t kMaxPatches = profile::kMaxPatches;
std::vector<uint32_t> gDiagnosticExpected;
std::vector<uint8_t> gDiagnosticExpectedKnown;
bool gDiagnosticRuntimeReady = false, gDiagnosticImagesValid = false;
bool gDiagnosticMappingMatchesProfile = false;
std::string gDiagnosticLoadedProfileDigest, gDiagnosticRequestedProfileDigest;
bool gDiagnosticHaveCommit = false, gDiagnosticEnabled = false;
bool gDiagnosticInstallationStopped = false;
bool gDiagnosticDirty = true;
char gDiagnosticPhase[96] = "startup";
std::string gDiagnosticNonce;

extern "C" const profile::Profile* monoicon_hyos_active_profile() { return &gProfile; }

// Result codes, mirrored into the status file.
enum PaletteResult : uint32_t {
    kResultNone = 0,
    kResultInstalled = 1,
    kResultNoFile = 2,
    kResultParseFailed = 3,
    kResultImageMissing = 4,
    kResultIdentityMismatch = 5,
    kResultCodePageUnavailable = 6,
    kResultThreadPauseUnavailable = 7,
    kResultPaletteInUse = 8,
    kResultProtectionDenied = 9,
    kResultVerifyFailed = 10,
    kResultRollbackIncomplete = 11,
    kResultDisabled = 12,
    kResultProfileInvalid = 13,
    kResultRestartRequired = 14,
    kResultSafetyBlocked = 15,
};

struct PaletteStatus {
    uint32_t magic;            // kStatusMagic once the installer ran
    uint32_t result;           // PaletteResult
    uint32_t background;       // packed ARGB as configured
    uint32_t foreground;
    uint32_t attempts;         // bounded retry count
    uint32_t in_launcher_child;
    uint32_t pid;
    uint32_t page_size;
    uint32_t app_bias_low, app_bias_high;
    uint32_t launcher_bias_low, launcher_bias_high;
    uint32_t identity_bytes_checked;
    uint32_t words_written;
    uint32_t words_verified;
    uint32_t status_file_written;
};

constexpr uint32_t kStatusMagic = 0x4D4F504Cu;  // "MOPL"

extern "C" __attribute__((used, visibility("default")))
PaletteStatus monoicon_hyos_palette = {};

// A PID is the ownership token: an inherited spawner state cannot suppress a
// child worker, and compare_exchange(pid,pid) can never start a second worker.
std::atomic<uint32_t> gPaletteWorkerPid{0};

void LogPalette(const char* message) {
    __android_log_write(ANDROID_LOG_INFO, kLogTag, message);
}

namespace {
// AppendText/AppendUint keep internal linkage so they cannot collide with the
// same helper names in hyperos4_hook_backend.cpp.
void AppendText(char* buffer, size_t capacity, const char* text) {
    size_t length = 0u;
    while (buffer[length] != '\0' && length < capacity) ++length;
    for (size_t index = 0u; text[index] != '\0' && length + 1u < capacity; ++index) {
        buffer[length++] = text[index];
    }
    buffer[length] = '\0';
}

void AppendUint(char* buffer, size_t capacity, uint32_t value) {
    size_t length = 0u;
    while (buffer[length] != '\0' && length < capacity) ++length;
    if (length + 12u > capacity) return;
    if (value == 0u) {
        buffer[length++] = '0';
        buffer[length] = '\0';
        return;
    }
    char digits[10];
    size_t count = 0u;
    while (value > 0u && count < sizeof(digits)) {
        digits[count++] = static_cast<char>('0' + (value % 10u));
        value /= 10u;
    }
    while (count > 0u) buffer[length++] = digits[--count];
    buffer[length] = '\0';
}
}  // namespace

void WritePaletteStatus(const char* phase) {
    // Defer the richer read-only snapshot until PaletteStep has returned and
    // its thread rendezvous has ended. Existing legacy status stays unchanged.
    std::snprintf(gDiagnosticPhase, sizeof(gDiagnosticPhase), "%s", phase);
    gDiagnosticDirty = true;
    char statusPath[160];
    LauncherFilePath(statusPath, sizeof(statusPath), "monoicon_hyos_palette.txt");
    const char* paths[] = {statusPath};
    char body[2048] = {};
    AppendText(body, sizeof(body), "profile=");
    AppendText(body, sizeof(body), gProfile.id.c_str());
    AppendText(body, sizeof(body), " digest=");
    AppendText(body, sizeof(body), gProfileDigest.c_str());
    AppendText(body, sizeof(body), " reason=");
    AppendText(body, sizeof(body), gProfileError.c_str());
    AppendText(body, sizeof(body), " ");
    AppendText(body, sizeof(body), "phase=");
    AppendText(body, sizeof(body), phase);
    AppendText(body, sizeof(body), " result=");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.result, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " bg=0x");
    char hex[16];
    const uint32_t background =
        __atomic_load_n(&monoicon_hyos_palette.background, __ATOMIC_ACQUIRE);
    for (int i = 7; i >= 0; --i) {
        const uint32_t nibble = (background >> (i * 4)) & 0xFu;
        hex[7 - i] = static_cast<char>(nibble < 10 ? '0' + nibble : 'a' + (nibble - 10));
    }
    hex[8] = '\0';
    AppendText(body, sizeof(body), hex);
    AppendText(body, sizeof(body), " fg=0x");
    const uint32_t foreground =
        __atomic_load_n(&monoicon_hyos_palette.foreground, __ATOMIC_ACQUIRE);
    for (int i = 7; i >= 0; --i) {
        const uint32_t nibble = (foreground >> (i * 4)) & 0xFu;
        hex[7 - i] = static_cast<char>(nibble < 10 ? '0' + nibble : 'a' + (nibble - 10));
    }
    AppendText(body, sizeof(body), hex);
    AppendText(body, sizeof(body), " attempts=");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.attempts, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " child=");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.in_launcher_child, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " pid=");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.pid, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " pagesz=");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.page_size, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " app_bias=hi:");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.app_bias_high, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " lo:");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.app_bias_low, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " launcher_bias=hi:");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.launcher_bias_high, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " lo:");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.launcher_bias_low, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " identity_bytes=");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.identity_bytes_checked, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " words_written=");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.words_written, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), " words_verified=");
    AppendUint(body, sizeof(body),
               __atomic_load_n(&monoicon_hyos_palette.words_verified, __ATOMIC_ACQUIRE));
    AppendText(body, sizeof(body), "\n");
    for (const char* path : paths) {
        const int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd < 0) continue;
        ssize_t written;
        do {
            written = write(fd, body, std::strlen(body));
        } while (written < 0 && errno == EINTR);
        close(fd);
        if (written > 0) {
            __atomic_store_n(&monoicon_hyos_palette.status_file_written, 1u,
                             __ATOMIC_RELEASE);
            return;
        }
    }
}

// ---------------------------------------------------------------------------
// Versioned app/native contract; legacy two-color files remain readable.
// ---------------------------------------------------------------------------
bool ParseUint32(const char*& p, const char* end, uint32_t& out) {
    while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) ++p;
    if (p >= end) return false;
    unsigned base = 10;
    if (p + 1 < end && p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) {
        base = 16;
        p += 2;
    }
    uint64_t value = 0;
    bool any = false;
    while (p < end) {
        const char c = *p;
        unsigned digit;
        if (c >= '0' && c <= '9') digit = static_cast<unsigned>(c - '0');
        else if (base == 16 && c >= 'a' && c <= 'f') digit = static_cast<unsigned>(c - 'a' + 10);
        else if (base == 16 && c >= 'A' && c <= 'F') digit = static_cast<unsigned>(c - 'A' + 10);
        else break;
        if (digit >= base || value > (UINT64_MAX - digit) / base) return false;
        value = value * base + digit;
        any = true;
        ++p;
    }
    if (!any || value > 0xffffffffull ||
        (p < end && *p != ' ' && *p != '\t' && *p != '\n' && *p != '\r')) return false;
    out = static_cast<uint32_t>(value);
    return true;
}

struct PaletteConfig {
    uint32_t enabled = 1, background = 0, foreground = 0;
    uint32_t circle = 0, lawnicons = 0;
    uint32_t version = 0;
    char generation[35] = {};
    char profileDigest[65] = {};
    char payload[kConfigCapacity] = {};
};

PaletteResult ReadPalette(PaletteConfig& config) {
    char path[160];
    LauncherFilePath(path, sizeof(path), "monoicon_palette.txt");
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
    const char* p = config.payload;
    const char* end = p + total;
    constexpr char prefix[] = "MONOICON ";
    if (total >= sizeof(prefix) - 1 && std::memcmp(p, prefix, sizeof(prefix) - 1) == 0) {
        p += sizeof(prefix) - 1;
        if (!ParseUint32(p, end, config.version) || (config.version != 1 && config.version != 2 && config.version != 3) ||
            !ParseUint32(p, end, config.enabled) || config.enabled > 1 ||
            !ParseUint32(p, end, config.background) ||
            !ParseUint32(p, end, config.foreground) ||
            !ParseUint32(p, end, config.circle) || config.circle > 1 ||
            !ParseUint32(p, end, config.lawnicons) || config.lawnicons > 1) return kResultParseFailed;
        if (config.version >= 2) {
            while (p < end && (*p == ' ' || *p == '\t')) ++p;
            if (end - p < 34 || p[0] != 'g' || p[1] != '-') return kResultParseFailed;
            for (size_t i = 2; i < 34; ++i) {
                if (!((p[i] >= '0' && p[i] <= '9') || (p[i] >= 'a' && p[i] <= 'f'))) return kResultParseFailed;
            }
            std::memcpy(config.generation, p, 34);
            p += 34;
        }
        if (config.version == 3) {
            while (p < end && (*p == ' ' || *p == '\t')) ++p;
            if (end - p < 64) return kResultParseFailed;
            std::memcpy(config.profileDigest, p, 64);
            if (!profile::ValidDigest(config.profileDigest)) return kResultParseFailed;
            p += 64;
        }
    } else if (!ParseUint32(p, end, config.background) ||
               !ParseUint32(p, end, config.foreground)) {
        return kResultParseFailed;
    }
    while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) ++p;
    if (p != end) return kResultParseFailed;
    // Legacy scalar / PNG transports may explicitly reuse the selected profile.
    // No embedded build fallback exists when the pointer is absent.
    if (config.version < 3) {
        char selected[192]; LauncherFilePath(selected, sizeof(selected), "monoicon_profile.active");
        const int active = open(selected, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        if (active < 0) return kResultProfileInvalid;
        const ssize_t count = read(active, config.profileDigest, 65); close(active);
        if (count != 64) return kResultProfileInvalid;
        config.profileDigest[64] = '\0';
        if (!profile::ValidDigest(config.profileDigest)) return kResultProfileInvalid;
    }
    return kResultNone;
}

// Published only after the transaction finishes. The app waits for these exact
// bytes before notifying the launcher, so a notification cannot race a reload.
bool AcknowledgePalette(const PaletteConfig& config) {
    char path[160], temp[160];
    LauncherFilePath(path, sizeof(path), "monoicon_palette.applied");
    LauncherFilePath(temp, sizeof(temp), "monoicon_palette.applied.tmp");
    const int fd = open(temp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return false;
    size_t written = 0;
    const size_t length = std::strlen(config.payload);
    while (written < length) {
        const ssize_t count = write(fd, config.payload + written, length - written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        written += static_cast<size_t>(count);
    }
    const bool synced = written == length && fsync(fd) == 0;
    close(fd);
    if (!synced || rename(temp, path) != 0) { unlink(temp); return false; }
    return true;
}

// ---------------------------------------------------------------------------
// Image discovery and identity validation.
// ---------------------------------------------------------------------------
bool gPaletteInstalled = false;
void* gForegroundPage = nullptr;

bool ValidateImages() {
    uint32_t checked = 0;
    const bool valid = gRuntime.validate(gProfile, gCurrentInstructions, checked);
    gDiagnosticImagesValid = valid;
    __atomic_store_n(&monoicon_hyos_palette.identity_bytes_checked, checked, __ATOMIC_RELEASE);
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

void AppendProfileDiagnostics(std::string& output) {
    using namespace diagnostics;
    const uint32_t result = __atomic_load_n(&monoicon_hyos_palette.result, __ATOMIC_ACQUIRE);
    for (size_t i = 0; i < gProfile.patches.size(); ++i) {
        const auto& patch = gProfile.patches[i];
        const auto& function = gProfile.functions[patch.function];
        const auto& image = gProfile.images[function.image];
        const bool mapped = gDiagnosticMappingMatchesProfile && function.image < gRuntime.images.size() &&
            gRuntime.images[function.image].bias && patch.address <= UINTPTR_MAX - gRuntime.images[function.image].bias;
        const uintptr_t bias = mapped ? gRuntime.images[function.image].bias : 0;
        const uintptr_t address = mapped ? bias + patch.address : 0;
        const bool expectedKnown = i < gDiagnosticExpected.size() && i < gDiagnosticExpectedKnown.size() && gDiagnosticExpectedKnown[i];
        const uint32_t expected = expectedKnown ? gDiagnosticExpected[i] : 0;
        uint32_t observed = 0;
        const bool read = mapped && gDiagnosticRuntimeReady && gRuntime.readWord(address, observed);
        const bool committed = gDiagnosticHaveCommit && gDiagnosticImagesValid && result != kResultRollbackIncomplete;
        const char* state = !mapped || !gDiagnosticRuntimeReady ? "blocked" : !read ? "read-failed" :
            expectedKnown && observed != expected ? "mismatch" : !committed || !expectedKnown ? "blocked" :
            !gDiagnosticEnabled ? "restored" : expected == patch.original ? "unchanged" : "verified";
        const char* detail = !mapped ? "image-unavailable" : !gDiagnosticRuntimeReady ? "image-not-verified" :
            !read ? "safe-memory-read-failed" : !expectedKnown ? "instruction-not-prepared" :
            observed != expected ? "observed-instruction-differs" : !committed ? gDiagnosticPhase : "instruction-verified";
        output += ','; output += '{'; Text(output, "id", patch.id.c_str());
        output += ','; Text(output, "kind", "profile-patch");
        output += ','; Text(output, "state", state);
        output += ','; Text(output, "library", image.library.c_str());
        output += ','; Text(output, "function", function.id.c_str());
        output += ','; Hex(output, "offset", patch.address);
        output += ','; Hex(output, "target", address, mapped);
        output += ','; Hex(output, "loadBias", bias, mapped);
        output += ','; Text(output, "valueType", "instruction");
        output += ','; Hex(output, "original", patch.original, true, true);
        output += ','; Hex(output, "replacement", expected, expectedKnown, true);
        output += ','; Hex(output, "observed", observed, read, true);
        output += ','; Hex(output, "committed", i < gCurrentInstructions.size() ? gCurrentInstructions[i] : 0,
            gDiagnosticHaveCommit && i < gCurrentInstructions.size(), true);
        output += ','; Text(output, "detail", detail);
        output += ','; Number(output, "originPid", static_cast<uint32_t>(getpid()));
        output += '}';
    }
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
    output += ','; Text(output, "profileDigest", gProfileDigest.empty() ? gDiagnosticLoadedProfileDigest.c_str() : gProfileDigest.c_str());
    output += ','; Text(output, "requestedProfileDigest", gDiagnosticRequestedProfileDigest.c_str());
    output += ','; Text(output, "phase", gDiagnosticPhase);
    output += ','; Number(output, "result", __atomic_load_n(&monoicon_hyos_palette.result, __ATOMIC_ACQUIRE));
    output += ','; Text(output, "reason", gProfileError.c_str());
    output += ','; Number(output, "pageSize", __atomic_load_n(&monoicon_hyos_palette.page_size, __ATOMIC_ACQUIRE));
    output += ','; Number(output, "installerStopped", gDiagnosticInstallationStopped ? 1 : 0);
    output += ','; Number(output, "iconsEnabled", monoicon_hyos_icons_enabled() ? 1 : 0);
    output += ','; Hex(output, "foregroundPage", reinterpret_cast<uintptr_t>(gForegroundPage), gForegroundPage != nullptr);
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
    AppendProfileDiagnostics(output);
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
    if (::hyperos4::pauseActive.load(std::memory_order_acquire) != 0) return;
    const int savedErrno = errno;
    const auto nonce = ReadDiagnosticNonce();
    if ((gDiagnosticDirty || (!nonce.empty() && nonce != gDiagnosticNonce)) && WriteHookDiagnostics(nonce)) {
        gDiagnosticDirty = false;
        gDiagnosticNonce = nonce;
    }
    errno = savedErrno;
}
} // namespace

void* PrepareForegroundPage(uintptr_t from, size_t pageSize, uint32_t background, uint32_t foreground,
                            uint32_t& branch) {
    const size_t codeSize = gProfile.veneer.words.size() * sizeof(uint32_t);
    constexpr uintptr_t reach = 0x07ff0000;  // signed B imm26 range minus margin
    const uintptr_t lower = from > reach ? from - reach : pageSize;
    const uintptr_t upper = from < UINTPTR_MAX - reach ? from + reach : UINTPTR_MAX - pageSize;

    // Walk /proc/self/maps gaps within branch range and use each gap start as a
    // non-replacing hint; codePageHint re-validates the actual placement and
    // both branch displacements. A single fixed set of hints is not enough: the
    // region around libapp.so can be fully occupied by adjacent mappings.
    std::unique_ptr<FILE, decltype(&fclose)> input(fopen("/proc/self/maps", "re"), fclose);
    if (!input) return nullptr;
    struct Gap {
        uintptr_t start, end;
    };
    Gap gaps[64];
    size_t gapCount = 0;
    char line[PATH_MAX + 256];
    uintptr_t previous = lower;
    while (fgets(line, sizeof(line), input.get()) != nullptr &&
           gapCount < sizeof(gaps) / sizeof(gaps[0])) {
        unsigned long long start = 0, end = 0;
        if (sscanf(line, "%llx-%llx", &start, &end) != 2 || end <= start) continue;
        const uintptr_t mapStart = static_cast<uintptr_t>(std::min<uintptr_t>(start, upper));
        const uintptr_t gapStart = (previous + pageSize - 1) & ~(pageSize - 1);
        if (gapStart < mapStart && pageSize <= mapStart - gapStart) {
            gaps[gapCount++] = {gapStart, mapStart};
        }
        if (end >= upper) break;
        previous = std::max(previous, static_cast<uintptr_t>(end));
    }
    if (gapCount == 0) return nullptr;

    for (size_t i = 0; i < gapCount; ++i) {
        void* page = ::hyperos4::codePageHint(gaps[i].start, from, pageSize, codeSize);
        if (page == nullptr) continue;
        const uintptr_t address = reinterpret_cast<uintptr_t>(page);
        uint32_t continuation = 0;
        if (!::hyperos4::branchInstruction(from, address, branch) ||
            !::hyperos4::branchInstruction(address + codeSize - 4, from + 4, continuation)) {
            munmap(page, pageSize);
            continue;
        }
        std::vector<uint32_t> code;
        for (const auto& value : gProfile.veneer.words) code.push_back(profile::Resolve(value, 0, background, foreground, continuation));
        if (::hyperos4::sealCodePage(page, pageSize, code.data(), codeSize)) return page;
        munmap(page, pageSize);
    }
    return nullptr;
}

// ---------------------------------------------------------------------------
// The transaction.
// ---------------------------------------------------------------------------
bool Protect(uintptr_t page, size_t pageSize, int mode) {
    int result;
    do {
        result = mprotect(reinterpret_cast<void*>(page), pageSize, mode);
    } while (result != 0 && errno == EINTR);
    return result == 0;
}

void WriteInstruction(uintptr_t address, uint32_t instruction) {
    __atomic_store_n(reinterpret_cast<uint32_t*>(address), instruction, __ATOMIC_RELEASE);
    auto* first = reinterpret_cast<char*>(address);
    __builtin___clear_cache(first, first + sizeof(instruction));
}

uint32_t ReadInstruction(uintptr_t address) {
    uint32_t value = 0;
    std::memcpy(&value, reinterpret_cast<const void*>(address), sizeof(value));
    return value;
}

void Fail(PaletteResult result, const char* phase) {
    __atomic_store_n(&monoicon_hyos_palette.result,
                     static_cast<uint32_t>(result), __ATOMIC_RELEASE);
    WritePaletteStatus(phase);
}

// Builds the runtime patch set with the computed replacements.
struct RuntimePatch {
    uintptr_t address;
    uint32_t original;
    uint32_t replacement;
};

uint32_t InstallPaletteTransaction(size_t pageSize, uint32_t background,
                                   uint32_t foreground, bool enabled, bool exportedIcons, bool circle) {
    if (!ValidateImages()) {
        Fail(kResultIdentityMismatch, "identity-mismatch");
        return kResultIdentityMismatch;
    }

    uint32_t foregroundBranch = 0;
    const bool needsVeneer = enabled && !exportedIcons && !gProfile.veneer.words.empty();
    void* foregroundPage = needsVeneer ? PrepareForegroundPage(
        gRuntime.address(gProfile, gProfile.patches[gProfile.veneer.patch]), pageSize, background, foreground, foregroundBranch) : nullptr;
    if (needsVeneer && foregroundPage == nullptr) {
        Fail(kResultCodePageUnavailable, "code-page-unavailable");
        return kResultCodePageUnavailable;
    }

    const size_t patchCount = gProfile.patches.size();
    std::vector<RuntimePatch> patches(patchCount);
    for (size_t i = 0; i < patchCount; ++i) {
        const auto& p = gProfile.patches[i];
        const auto& value = exportedIcons ? (circle ? p.circle : p.icons) : p.palette;
        const uint32_t replacement = enabled ? profile::Resolve(value, p.original, background, foreground, foregroundBranch) : p.original;
        patches[i] = {gRuntime.address(gProfile, p), gCurrentInstructions[i], replacement};
    }
    // Record the exact branch/literal chosen for this attempt before touching
    // code. A failed transaction must not pretend this was a committed word.
    gDiagnosticExpected.resize(patchCount);
    gDiagnosticExpectedKnown.assign(patchCount, 1);
    for (size_t i = 0; i < patchCount; ++i) gDiagnosticExpected[i] = patches[i].replacement;

    uintptr_t pages[kMaxPatches] = {};
    size_t pageCount = 0;
    for (const auto& p : patches) {
        const uintptr_t page = p.address & ~(pageSize - 1);
        bool present = false;
        for (size_t i = 0; i < pageCount; ++i) present |= pages[i] == page;
        if (!present && pageCount < sizeof(pages) / sizeof(pages[0])) {
            pages[pageCount++] = page;
        }
    }

    monoicon_hyos_protect_code_pages(pages, pageCount, pageSize);

    ::hyperos4::ThreadPause pause;
    if (!pause.acquire()) {
        const bool finished = pause.finish();
        if (!finished) { Fail(kResultRollbackIncomplete, "rollback-incomplete"); return kResultRollbackIncomplete; }
        if (foregroundPage != nullptr) munmap(foregroundPage, pageSize);
        Fail(kResultThreadPauseUnavailable, "thread-pause-unavailable");
        return kResultThreadPauseUnavailable;
    }
    // Exclude every complete function touched by this profile, including multi-
    // instruction sequences and branches, before changing any instruction.
    bool excluded = true;
    for (const auto& patch : gProfile.patches) {
        const auto& f = gProfile.functions[patch.function];
        const uintptr_t start = gRuntime.images[f.image].bias + f.address;
        excluded &= pause.excludes(start, start + f.bytes.size());
    }
    if (!excluded || (gForegroundPage != nullptr && !pause.excludes(
            reinterpret_cast<uintptr_t>(gForegroundPage),
            reinterpret_cast<uintptr_t>(gForegroundPage) + gProfile.veneer.words.size() * sizeof(uint32_t)))) {
        const bool finished = pause.finish();
        if (finished && foregroundPage != nullptr) munmap(foregroundPage, pageSize);
        Fail(finished ? kResultPaletteInUse : kResultRollbackIncomplete,
             finished ? "palette-in-use" : "rollback-incomplete");
        return finished ? kResultPaletteInUse : kResultRollbackIncomplete;
    }
    // Re-validate with peers parked: no thread can change the pages now.
    if (!ValidateImages()) {
        const bool finished = pause.finish();
        if (finished && foregroundPage != nullptr) munmap(foregroundPage, pageSize);
        Fail(kResultIdentityMismatch, "identity-mismatch-under-pause");
        (void)finished;
        return kResultIdentityMismatch;
    }

    bool writable[kMaxPatches] = {};
    for (size_t i = 0; i < pageCount; ++i) {
        if (!Protect(pages[i], pageSize, PROT_READ | PROT_WRITE | PROT_EXEC)) {
            bool restored = true;
            for (size_t j = 0; j < i; ++j) {
                restored &= Protect(pages[j], pageSize, PROT_READ | PROT_EXEC);
            }
            const bool finished = pause.finish();
            if (finished && foregroundPage != nullptr) munmap(foregroundPage, pageSize);
            Fail(finished && restored ? kResultProtectionDenied : kResultRollbackIncomplete,
                 finished && restored ? "protection-denied" : "rollback-incomplete");
            return finished && restored ? kResultProtectionDenied : kResultRollbackIncomplete;
        }
        writable[i] = true;
    }

    for (const auto& p : patches) WriteInstruction(p.address, p.replacement);
    __atomic_store_n(&monoicon_hyos_palette.words_written,
                     static_cast<uint32_t>(patchCount), __ATOMIC_RELEASE);

    bool restored = true;
    for (size_t i = 0; i < pageCount; ++i) {
        writable[i] = !Protect(pages[i], pageSize, PROT_READ | PROT_EXEC);
        restored &= !writable[i];
    }

    bool verified = restored;
    uint32_t verifiedWords = 0;
    for (const auto& p : patches) {
        if (ReadInstruction(p.address) == p.replacement) ++verifiedWords;
        else verified = false;
    }
    __atomic_store_n(&monoicon_hyos_palette.words_verified, verifiedWords,
                     __ATOMIC_RELEASE);

    if (verified) {
        void* previousPage = gForegroundPage;
        gForegroundPage = foregroundPage;
        gPaletteInstalled = enabled;
        for (size_t i = 0; i < patchCount; ++i) {
            gCurrentInstructions[i] = patches[i].replacement;
        }
        const bool finished = pause.finish();
        if (finished) {
            gDiagnosticEnabled = enabled;
            if (previousPage != nullptr) munmap(previousPage, pageSize);
            Fail(enabled ? kResultInstalled : kResultDisabled,
                 enabled ? "installed" : "disabled-restored");
        } else {
            Fail(kResultRollbackIncomplete, "rollback-incomplete");
        }
        return finished ? (enabled ? kResultInstalled : kResultDisabled) : kResultRollbackIncomplete;
    }

    // Rollback: restore every original word with peers still parked.
    bool recovered = true;
    for (size_t i = 0; i < pageCount; ++i) {
        if (!writable[i]) writable[i] = Protect(pages[i], pageSize, PROT_READ | PROT_WRITE | PROT_EXEC);
        for (const auto& p : patches) {
            if ((p.address & ~(pageSize - 1)) != pages[i]) continue;
            if (writable[i]) WriteInstruction(p.address, p.original);
            else recovered = false;
        }
        recovered &= Protect(pages[i], pageSize, PROT_READ | PROT_EXEC);
    }
    for (const auto& p : patches) {
        recovered &= ReadInstruction(p.address) == p.original;
    }
    const bool finished = pause.finish();
    if (finished && recovered && foregroundPage != nullptr) munmap(foregroundPage, pageSize);
    Fail(finished && recovered ? kResultVerifyFailed : kResultRollbackIncomplete,
         finished && recovered ? "verify-failed-rolled-back" : "rollback-incomplete");
    return finished && recovered ? kResultVerifyFailed : kResultRollbackIncomplete;
}

std::atomic<bool> gIconsEnabled{false};

extern "C" bool monoicon_hyos_icons_enabled() {
    return gIconsEnabled.load(std::memory_order_acquire);
}

struct PaletteWorkerState {
    size_t pageSize;
    std::atomic<int> start{0}; // 0: loader owns the first pass; 1: run; 2: stop
    bool startup = false;
    bool profileReady = false;
    PaletteConfig applied;
    bool haveApplied = false, acknowledged = false;
    std::string previousFailure, failedImageDigest, failedImageReason;
};

// The loader's first synchronous pass and subsequent worker use identical
// validation/rollback logic and share the committed state.
bool PaletteStep(PaletteWorkerState& state) {
    const size_t pageSize = state.pageSize;
    auto& profileReady = state.profileReady;
    auto& applied = state.applied;
    auto& haveApplied = state.haveApplied;
    auto& acknowledged = state.acknowledged;
    auto& previousFailure = state.previousFailure;
    auto& failedImageDigest = state.failedImageDigest;
    auto& failedImageReason = state.failedImageReason;
    do {
        PaletteConfig next;
        uint32_t result = ReadPalette(next);
        if (result == kResultNone) gDiagnosticRequestedProfileDigest = next.profileDigest;
        // This runs before profile loading, native icon hooks or code writes.
        // A native abort cannot run cleanup, so the next process detects the
        // pending checkpoint and permanently stops here until explicit retry.
        if (result == kResultNone && next.enabled && !safety::Begin()) {
            gIconsEnabled.store(false, std::memory_order_release);
            gProfileError = "launcher safety mode: short-lived restart or protection unavailable";
            Fail(kResultSafetyBlocked, "safety-blocked");
            return false;
        }
        if (result == kResultNone && !profileReady) {
            gDiagnosticRuntimeReady = false;
            gDiagnosticImagesValid = false;
            gDiagnosticMappingMatchesProfile = false;
            if (!profile::LoadProfile(next.profileDigest, gProfile, gProfileError)) result = kResultProfileInvalid;
            else if (failedImageDigest == next.profileDigest) {
                gProfileError = failedImageReason;
                result = kResultIdentityMismatch;
            } else {
                gDiagnosticLoadedProfileDigest = next.profileDigest;
                gDiagnosticMappingMatchesProfile = true;
                if (!gRuntime.discover(gProfile, gProfileError)) {
                    result = gProfileError.rfind("image-missing:", 0) == 0 ? kResultImageMissing : kResultIdentityMismatch;
                    if (result == kResultIdentityMismatch) { failedImageDigest = next.profileDigest; failedImageReason = gProfileError; }
                } else {
                    gProfileDigest = next.profileDigest;
                    gCurrentInstructions.clear();
                    for (const auto& patch : gProfile.patches) gCurrentInstructions.push_back(patch.original);
                    gDiagnosticExpected = gCurrentInstructions;
                    gDiagnosticExpectedKnown.assign(gCurrentInstructions.size(), 1);
                    gDiagnosticRuntimeReady = true;
                    profileReady = true; gProfileError.clear();
                }
            }
        }
        if (result != kResultNone) {
            // A missing first file or temporarily absent image is recoverable.
            // Keep the last valid transaction until a complete replacement is
            // published; malformed/truncated writes never become instructions.
            const std::string failureKey = std::to_string(result) + next.profileDigest + gProfileError;
            if (failureKey != previousFailure) {
                Fail(static_cast<PaletteResult>(result),
                     result == kResultNoFile ? "palette-file-missing" :
                     result == kResultImageMissing ? "image-unavailable" : result == kResultIdentityMismatch ? "identity-mismatch" : result == kResultProfileInvalid ? "profile-invalid" : "palette-parse-failed");
                previousFailure = failureKey;
            }
            continue;
        }
        // Startup callbacks run on native launcher stacks. Only Dart-image
        // patches are safe to apply there before Dart starts; a custom Profile
        // that patches native callers keeps the original worker-only path.
        if (state.startup && std::any_of(gProfile.patches.begin(), gProfile.patches.end(), [](const profile::Patch& patch) {
                return gProfile.images[gProfile.functions[patch.function].image].library != "libapp.so";
            })) return true;
        // Some HYOS paths discard private file-backed pages through direct
        // syscalls. Only recover bytes equal to our last patch or the pinned
        // original; any third-party/unknown instruction remains a hard failure.
        bool discarded = false, known = true;
        const size_t patchCount = gProfile.patches.size();
        std::vector<uint32_t> observed(patchCount);
        if (haveApplied || gPaletteInstalled) {
            for (size_t i = 0; i < patchCount; ++i) {
                uint32_t word;
                if (!gRuntime.readWord(gRuntime.address(gProfile, gProfile.patches[i]), word)) { known = false; break; }
                observed[i] = word;
                if (word != gCurrentInstructions[i]) {
                    if (word != gProfile.patches[i].original) { known = false; break; }
                    discarded = true;
                }
            }
            if (!known) {
                gDiagnosticImagesValid = false;
                gIconsEnabled.store(false, std::memory_order_release);
                Fail(kResultIdentityMismatch, "unexpected-code-change");
                return false;
            }
            if (discarded) {
                gCurrentInstructions = observed;
                haveApplied = false;
                acknowledged = false;
            }
        }
        if (gProfileDigest != next.profileDigest) {
            // Revert using the OLD verified profile; never mix offsets from the
            // replacement into a process that has already adopted a profile.
            gIconsEnabled.store(false, std::memory_order_release);
            const uint32_t restored = gPaletteInstalled ? InstallPaletteTransaction(pageSize, 0, 0, false, false, false) : kResultDisabled;
            if (restored == kResultPaletteInUse || restored == kResultThreadPauseUnavailable ||
                restored == kResultProtectionDenied || restored == kResultVerifyFailed) {
                continue;
            }
            gProfileError = "profile changed; restart launcher to adopt the new profile";
            Fail(restored == kResultDisabled ? kResultRestartRequired : static_cast<PaletteResult>(restored), "profile-restart-required");
            return false;
        }
        if (haveApplied && std::strcmp(applied.payload, next.payload) == 0) {
            if (!state.startup && next.enabled && next.version >= 2 && gProfile.icons.geometryBridge < gProfile.functions.size()) {
                monoicon_hyos_start_geometry_bridge(&gRuntime, &gProfile, gProfileDigest.c_str());
            }
            if (!acknowledged) acknowledged = AcknowledgePalette(applied);
            continue;
        }
        __atomic_fetch_add(&monoicon_hyos_palette.attempts, 1u, __ATOMIC_RELAXED);
        const bool exportedIcons = next.version >= 2;
        // The dynamic veneer branch is filled by InstallPaletteTransaction.
        // Other words remain available even if a precondition stops installation.
        gDiagnosticExpected.resize(patchCount);
        gDiagnosticExpectedKnown.resize(patchCount);
        for (size_t i = 0; i < patchCount; ++i) {
            const auto& patch = gProfile.patches[i];
            const auto& value = exportedIcons ? (next.circle ? patch.circle : patch.icons) : patch.palette;
            gDiagnosticExpected[i] = next.enabled ? profile::Resolve(value, patch.original,
                next.background, next.foreground, 0) : patch.original;
            gDiagnosticExpectedKnown[i] = !next.enabled || value.kind != profile::ValueKind::Veneer;
        }
        if (!ValidateImages()) {
            gIconsEnabled.store(false, std::memory_order_release);
            Fail(kResultIdentityMismatch, "identity-mismatch");
            return false;
        }
        if (next.enabled && exportedIcons && !monoicon_hyos_icon_generation_ready(next.generation)) {
            Fail(kResultNoFile, "icon-generation-unavailable");
            continue;
        }
        if (next.enabled && exportedIcons && !monoicon_hyos_prepare_icon_files()) {
            Fail(kResultNoFile, "icon-hooks-unavailable");
            continue;
        }
        // Custom profiles may use palette scalars in PNG mode too. Compare the
        // actual resolved instructions rather than assuming a build's modes use
        // only literals. Veneers additionally depend on both palette scalars.
        bool sameCode = haveApplied;
        for (size_t i = 0; sameCode && i < patchCount; ++i) {
            const auto& patch = gProfile.patches[i];
            const auto& value = exportedIcons ? (next.circle ? patch.circle : patch.icons) : patch.palette;
            if (next.enabled && value.kind == profile::ValueKind::Veneer) {
                sameCode = applied.enabled && applied.version < 2 &&
                    applied.background == next.background && applied.foreground == next.foreground;
            } else {
                const uint32_t expected = next.enabled ? profile::Resolve(value, patch.original,
                    next.background, next.foreground, 0) : patch.original;
                sameCode = gCurrentInstructions[i] == expected;
            }
        }
        if (sameCode || (!next.enabled && !gPaletteInstalled)) {
            result = next.enabled ? kResultInstalled : kResultDisabled;
        } else {
            result = InstallPaletteTransaction(pageSize,
                                               next.background, next.foreground,
                                               next.enabled != 0, exportedIcons, next.circle != 0);
        }
        if (result == kResultInstalled || result == kResultDisabled) {
            gDiagnosticHaveCommit = true;
            gDiagnosticEnabled = next.enabled != 0;
            gDiagnosticExpected = gCurrentInstructions;
            gDiagnosticExpectedKnown.assign(gCurrentInstructions.size(), 1);
            __atomic_store_n(&monoicon_hyos_palette.background, next.background, __ATOMIC_RELEASE);
            __atomic_store_n(&monoicon_hyos_palette.foreground, next.foreground, __ATOMIC_RELEASE);
            monoicon_hyos_select_icon_generation(next.enabled && exportedIcons ? next.generation : nullptr);
            gIconsEnabled.store(next.enabled != 0 && exportedIcons, std::memory_order_release);
            if (!state.startup && next.enabled && exportedIcons && gProfile.icons.geometryBridge < gProfile.functions.size()) {
                monoicon_hyos_start_geometry_bridge(&gRuntime, &gProfile, gProfileDigest.c_str());
            }
            applied = next;
            haveApplied = true;
            acknowledged = AcknowledgePalette(applied);
            previousFailure.clear();
            Fail(static_cast<PaletteResult>(result), next.enabled ? "config-applied" : "disabled-restored");
            LogPalette(next.enabled ? "updated MonoIcon configuration" : "restored launcher configuration");
        } else if (result == kResultRollbackIncomplete || result == kResultIdentityMismatch) {
            // Unknown code or an incomplete rendezvous must not be retried as
            // though our previous instruction state were still trustworthy.
            gIconsEnabled.store(false, std::memory_order_release);
            return false;
        }
    } while (false);
    return true;
}

void* PaletteWorker(void* data) {
    std::unique_ptr<PaletteWorkerState> state(static_cast<PaletteWorkerState*>(data));
    while (state->start.load(std::memory_order_acquire) == 0) usleep(1000);
    bool keepInstalling = state->start.load(std::memory_order_acquire) == 1;
    gDiagnosticInstallationStopped = !keepInstalling;
    while (true) {
        // A fatal/safety stop is permanent for this process. Retain this thread
        // solely to answer read-only diagnostic nonces; never re-enter the
        // safety guard, configuration engine or instruction transaction.
        if (keepInstalling) {
            keepInstalling = PaletteStep(*state);
            gDiagnosticInstallationStopped = !keepInstalling;
        }
        MaybeWriteHookDiagnostics();
        usleep(500000);
    }
}

// Exactly one engine belongs to each child. Only a safe startup loader callback
// requests the synchronous pass; late discovery always uses the worker.
uint32_t StartPalette(bool synchronous) {
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
    uint32_t owner = gPaletteWorkerPid.load(std::memory_order_acquire);
    do {
        if (owner == pid) return __atomic_load_n(&monoicon_hyos_palette.result, __ATOMIC_ACQUIRE);
    } while (!gPaletteWorkerPid.compare_exchange_weak(owner, pid,
                std::memory_order_acq_rel, std::memory_order_acquire));

    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0 || pageSize > 65536 || (pageSize & (pageSize - 1)) != 0) {
        gPaletteWorkerPid.store(0, std::memory_order_release);
        return kResultImageMissing;
    }
    __atomic_store_n(&monoicon_hyos_palette.pid, pid, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_palette.in_launcher_child, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_palette.magic, kStatusMagic, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_palette.page_size, static_cast<uint32_t>(pageSize), __ATOMIC_RELEASE);
    auto* state = new PaletteWorkerState();
    state->pageSize = static_cast<size_t>(pageSize);
    state->startup = synchronous;
    pthread_t worker;
    const int error = pthread_create(&worker, nullptr, PaletteWorker, state);
    if (error != 0) {
        delete state;
        gPaletteWorkerPid.store(0, std::memory_order_release);
        Fail(kResultThreadPauseUnavailable, "worker-unavailable");
        return kResultThreadPauseUnavailable;
    }
    pthread_detach(worker);
    // The worker touches neither the linker nor the transaction until release.
    // Never wait for a worker holding/waiting on linker locks in a loader callback.
    const bool keepRunning = !synchronous || PaletteStep(*state);
    if (synchronous) gDiagnosticInstallationStopped = !keepRunning;
    if (synchronous && state->haveApplied && state->acknowledged) {
        WritePaletteStatus(state->applied.enabled ? "startup-applied" : "startup-disabled");
    }
    // The synchronous owner still holds the worker's start gate here; only one
    // thread reads the runtime pipe or publishes the snapshot at any time.
    if (synchronous) MaybeWriteHookDiagnostics();
    state->startup = false;
    state->start.store(keepRunning ? 1 : 2, std::memory_order_release);
    return __atomic_load_n(&monoicon_hyos_palette.result, __ATOMIC_ACQUIRE);
}

extern "C" __attribute__((used, visibility("default")))
uint32_t monoicon_hyos_palette_install() { return StartPalette(false); }

extern "C" uint32_t monoicon_hyos_palette_startup() { return StartPalette(true); }
extern "C" bool monoicon_hyos_palette_started() {
    return gPaletteWorkerPid.load(std::memory_order_acquire) == static_cast<uint32_t>(getpid());
}

}  // namespace monoicon::hyos
