// MonoIcon's HyperOS 4 palette transaction, built into the LSPosed
// native_init payload.
//
// Why this exists
// ---------------
// The launcher child (com.miui.home) has no ART, so the Kotlin JNI path can
// never run there. This file ports the production patch transaction
// (hyperos4_native.cpp) into the payload: after both launcher images are
// mapped, the eight verified 6144 instruction substitutions and the
// foreground veneer are installed in-process with the same thread rendezvous,
// page protection dance, cache maintenance, read-back verification and full
// rollback.
//
// It runs in the launcher CHILD only. The HYOS spawner's pages are shared by
// every future child, so modifying them there would corrupt all later
// launchers; the child's private COW copies are the correct target.
//
// Palette delivery
// ----------------
// The palette arrives as a plain file the launcher process can read itself:
//
//   /data/user/0/com.miui.home/files/monoicon_palette.txt
//       "<background ARGB> <foreground ARGB>"   (hex 0x… or decimal)
//
// The MonoIcon app writes this file through its existing optional-root helper
// whenever the palette changes; adb (root) can write it directly for tests.
// Without the file nothing is installed and the launcher keeps its native
// look (fail closed). A refresh trigger is a Settings.System write or
// notifyChange on the launcher's own key (key_monochrome_color): the two
// equality-skip patches make every notification repaint, so the app does not
// need to coordinate with this process beyond writing the file.
//
// Identity
// --------
// Every patched function is compared byte-for-byte against embedded 6144
// snapshots (hyperos4_functions_6144_inc.h / hyperos4_observer_code.h),
// never against an APK on disk. A mismatch aborts before any write.

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
#include "hyperos4_functions_6144_inc.h"
#include "hyperos4_observer_code.h"
#include "hyperos4_thread_pause.h"
#include "hyperos4_icon_files.h"
#include "hyperos4_dynamic_icons_6144.h"

namespace monoicon::hyos {

constexpr char kLogTag[] = "MonoIcon.Palette";
constexpr size_t kConfigCapacity = 256;

void LauncherFilePath(char* path, size_t capacity, const char* name) {
    // Android isolates secondary users with a 100000 UID range. Never read the
    // owner's stale config or a global adb file when running as another user.
    std::snprintf(path, capacity, "/data/user/%u/com.miui.home/files/%s",
                  static_cast<unsigned>(getuid()) / 100000u, name);
}

// 6144 libapp.so patch table. word/replacement per instruction; function and
// functionSize bound the identity snapshot and the thread-pause exclusion.
struct InstructionPatch {
    uint32_t vaddr;
    uint32_t original;
    uint32_t replacement;  // 0 means "computed below" (background movz/movk)
};
constexpr InstructionPatch kInstructionPatches[] = {
    {0x1a04944, 0x36200261, 0x14000013},  // isSupportMonochrome tbz -> b
    {0x194adc4, 0x9a910204, 0x00000000},  // settings csel -> mov x4, x16 (enable)
    {0x194ae24, 0x93417c61, 0x00000000},  // settings sbfx -> movz w1 (background lo)
    {0x194ae28, 0x36000043, 0x00000000},  // settings tbz  -> movk w1 (background hi)
    {0x194ae2c, 0xf8407061, 0xd503201f},  // settings ldur -> nop
    {0x8cefb8,  0xf85d83a2, 0x00000000},  // foreground ldur -> veneer branch
    {0x194a79c, 0x540002e0, 0xd503201f},  // onSettingsChanged b.eq -> nop
    {0x12c8f4c, 0x54000040, 0xd503201f},  // didUpdateWidget b.eq -> nop
    // Dynamic/MAML sources bypass the exported PNGs. Complete their existing
    // Future with null so the caller proceeds to the normal theme source.
    {0x191a184, 0xf85f03a1, monoicon::hyperos4::dynamic6144::kFancyBranch},
    {0x191a644, 0xf85f03a1, monoicon::hyperos4::dynamic6144::kAnimatingBranch},
    {0x191af1c, 0xf85f03a1, monoicon::hyperos4::dynamic6144::kMamlAdaptiveBranch},
    // Existing theme archives and AI-HD art outrank ModIconSource. Let their
    // own async miss result select our PNG source (or the raw APK fallback).
    {0x1aad408, 0xf85e83a5, monoicon::hyperos4::dynamic6144::kThemeSourceBranch},
    {0x1aaacc4, 0xf85703a0, monoicon::hyperos4::dynamic6144::kAiHdSourceBranch},
    {0x1aab688, 0xf9403f40, monoicon::hyperos4::dynamic6144::kModSourceBranch},
    {0x1aab6d4, 0x94000280, 0x1400002d}, // skip layered mod art; use the complete PNG
    // Return the composed image via the existing async style identity path.
    // Running IconComposer.applyStyle again shrinks already-normalized circles.
    {0x17e743c, 0xa9bf79fd, monoicon::hyperos4::dynamic6144::kModStyleBranch},
    {0x1aab2b8, 0x94000031, monoicon::hyperos4::dynamic6144::kThemeStyleBranch},
    // Keep _hasMonochromeLayer's real result. Claiming a layer exists does not
    // create a MonoIcon mask; it turns legacy/system icons into solid blocks.
};
constexpr size_t kLegacyPatchCount = 8;
constexpr size_t kInstructionPatchCount =
    sizeof(kInstructionPatches) / sizeof(kInstructionPatches[0]);

constexpr uint32_t kSettingsFunctionVaddr = 0x194ad28;
constexpr uint32_t kSettingsFunctionSize = 624;
constexpr uint32_t kForegroundFunctionVaddr = 0x8ceef0;
constexpr uint32_t kForegroundFunctionSize = 256;
constexpr uint32_t kForegroundPatchVaddr = 0x8cefb8;
constexpr uint32_t kForegroundContinuationVaddr = 0x8cefbc;
// Canonical foreground ColorFilter pool slot (x27 + 0x859f8), 6144 build.
constexpr uint32_t kForegroundPoolAdd = 0x91421760;  // add x0, x27, #0x85, lsl 12
constexpr uint32_t kForegroundPoolLdr = 0xf944fc00;  // ldr x0, [x0, #0x9f8]

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
    char statusPath[160];
    LauncherFilePath(statusPath, sizeof(statusPath), "monoicon_hyos_palette.txt");
    const char* paths[] = {statusPath};
    char body[768] = {};
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
        if (!ParseUint32(p, end, config.version) || (config.version != 1 && config.version != 2) ||
            !ParseUint32(p, end, config.enabled) || config.enabled > 1 ||
            !ParseUint32(p, end, config.background) ||
            !ParseUint32(p, end, config.foreground) ||
            !ParseUint32(p, end, config.circle) || config.circle > 1 ||
            !ParseUint32(p, end, config.lawnicons) || config.lawnicons > 1) return kResultParseFailed;
        if (config.version == 2) {
            while (p < end && (*p == ' ' || *p == '\t')) ++p;
            if (end - p < 34 || p[0] != 'g' || p[1] != '-') return kResultParseFailed;
            for (size_t i = 2; i < 34; ++i) {
                if (!((p[i] >= '0' && p[i] <= '9') || (p[i] >= 'a' && p[i] <= 'f'))) return kResultParseFailed;
            }
            std::memcpy(config.generation, p, 34);
            p += 34;
        }
    } else if (!ParseUint32(p, end, config.background) ||
               !ParseUint32(p, end, config.foreground)) {
        return kResultParseFailed;
    }
    while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) ++p;
    return p == end ? kResultNone : kResultParseFailed;
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
struct ImageProbe {
    const char* fragment;
    uintptr_t bias;
};

int CaptureImage(dl_phdr_info* info, size_t, void* data) {
    auto* probe = static_cast<ImageProbe*>(data);
    if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;
    if (std::strstr(info->dlpi_name, probe->fragment) == nullptr) return 0;
    probe->bias = static_cast<uintptr_t>(info->dlpi_addr);
    return 1;
}

uintptr_t FindImageBias(const char* fragment) {
    ImageProbe probe{fragment, 0u};
    dl_iterate_phdr(CaptureImage, &probe);
    return probe.bias;
}

std::array<uint32_t, kInstructionPatchCount> gCurrentInstructions = [] {
    std::array<uint32_t, kInstructionPatchCount> words{};
    for (size_t i = 0; i < words.size(); ++i) words[i] = kInstructionPatches[i].original;
    return words;
}();
bool gPaletteInstalled = false;
void* gForegroundPage = nullptr;

template <size_t N>
bool Matches(const uint8_t* memory, const std::array<uint8_t, N>& original,
             uint32_t vaddr = 0) {
    auto expected = original;
    // On reload validate our previous transaction, not the unmodified image.
    // All other bytes remain pinned to the verified 6144 snapshot.
    if (vaddr != 0) {
        for (size_t i = 0; i < kInstructionPatchCount; ++i) {
            const auto& patch = kInstructionPatches[i];
            if (patch.vaddr >= vaddr && patch.vaddr - vaddr + 4 <= N) {
                std::memcpy(expected.data() + patch.vaddr - vaddr,
                            &gCurrentInstructions[i], sizeof(uint32_t));
            }
        }
    }
    return std::memcmp(memory, expected.data(), N) == 0;
}

// Validate every patched function against its embedded 6144 snapshot.
bool ValidateImages(uintptr_t appBias, uintptr_t launcherBias) {
    namespace f = monoicon::hyperos4::functions6144;
    namespace d = monoicon::hyperos4::dynamic6144;
    uint32_t checked = 0;
    const bool appOk =
        Matches(reinterpret_cast<const uint8_t*>(appBias + 0x1a04848), f::kOriginalIsSupportMonochrome, 0x1a04848) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + 0x194ad28), f::kOriginalLoadCurrentSettings, 0x194ad28) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + 0x8ceef0), f::kOriginalForegroundHelper, 0x8ceef0) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + 0x194a6f0), f::kOriginalOnSettingsChanged, 0x194a6f0) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + 0x12c8e14), f::kOriginalDidUpdateWidget, 0x12c8e14) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + d::kFancyVaddr), d::kFancyOriginal, d::kFancyVaddr) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + d::kAnimatingVaddr), d::kAnimatingOriginal, d::kAnimatingVaddr) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + d::kMamlAdaptiveVaddr), d::kMamlAdaptiveOriginal, d::kMamlAdaptiveVaddr) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + d::kThemeSourceVaddr), d::kThemeSourceOriginal, d::kThemeSourceVaddr) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + d::kAiHdSourceVaddr), d::kAiHdSourceOriginal, d::kAiHdSourceVaddr) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + d::kModSourceVaddr), d::kModSourceOriginal, d::kModSourceVaddr) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + d::kModStyleVaddr), d::kModStyleOriginal, d::kModStyleVaddr) &&
        Matches(reinterpret_cast<const uint8_t*>(appBias + d::kThemeStyleVaddr), d::kThemeStyleOriginal, d::kThemeStyleVaddr);
    const bool launcherOk = Matches(
        reinterpret_cast<const uint8_t*>(launcherBias + monoicon::hyperos4::observer::kFunctionVaddr),
        monoicon::hyperos4::observer::kOriginalFunction);
    checked = f::kOriginalIsSupportMonochrome.size() +
              f::kOriginalLoadCurrentSettings.size() +
              f::kOriginalForegroundHelper.size() +
              f::kOriginalOnSettingsChanged.size() +
              f::kOriginalDidUpdateWidget.size() +
              d::kFancyOriginal.size() + d::kAnimatingOriginal.size() + d::kMamlAdaptiveOriginal.size() +
              d::kThemeSourceOriginal.size() + d::kAiHdSourceOriginal.size() +
              d::kModSourceOriginal.size() +
              d::kModStyleOriginal.size() + d::kThemeStyleOriginal.size() +
              monoicon::hyperos4::observer::kOriginalFunction.size();
    __atomic_store_n(&monoicon_hyos_palette.identity_bytes_checked, checked,
                     __ATOMIC_RELEASE);
    return appOk && launcherOk;
}

// ---------------------------------------------------------------------------
// Foreground veneer: 9-word page near the patch site.
//   ldur x2, [x29, #-0x28]      (displaced instruction)
//   ldur x3, [x29, #-0x10]      (original ColorFilter identity)
//   add  x0, x27, #0x85, lsl 12 ; ldr x0, [x0, #0x9f8]  (pooled canonical filter)
//   cmp  x3, x0 ; b.ne +2
//   movz w2, lo ; movk w2, hi
//   b continuation
// ---------------------------------------------------------------------------
std::array<uint32_t, 9> ForegroundCode(uint32_t foreground, uint32_t continuation) {
    return {{
        0xf85d83a2, 0xf85f03a3, kForegroundPoolAdd, kForegroundPoolLdr,
        0xeb00007f, 0x54000061,
        0x52800002u | ((foreground & 0xffffu) << 5),
        0x72a00002u | ((foreground >> 16) << 5),
        continuation,
    }};
}

void* PrepareForegroundPage(uintptr_t from, size_t pageSize, uint32_t foreground,
                            uint32_t& branch) {
    constexpr size_t codeSize = 9 * sizeof(uint32_t);
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
        const auto code = ForegroundCode(foreground, continuation);
        if (::hyperos4::sealCodePage(page, pageSize, code.data(), sizeof(code))) return page;
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

uint32_t InstallPaletteTransaction(uintptr_t appBias, uintptr_t launcherBias,
                                   size_t pageSize, uint32_t background,
                                   uint32_t foreground, bool enabled, bool exportedIcons, bool circle) {
    if (!ValidateImages(appBias, launcherBias)) {
        Fail(kResultIdentityMismatch, "identity-mismatch");
        return kResultIdentityMismatch;
    }

    uint32_t foregroundBranch = 0;
    void* foregroundPage = enabled && !exportedIcons ? PrepareForegroundPage(
        appBias + kForegroundPatchVaddr, pageSize, foreground, foregroundBranch) : nullptr;
    if (enabled && !exportedIcons && foregroundPage == nullptr) {
        Fail(kResultCodePageUnavailable, "code-page-unavailable");
        return kResultCodePageUnavailable;
    }

    RuntimePatch patches[kInstructionPatchCount];
    for (size_t i = 0; i < kInstructionPatchCount; ++i) {
        const auto& p = kInstructionPatches[i];
        uint32_t replacement = p.replacement;
        switch (p.vaddr) {
            case 0x194adc4: replacement = 0xaa1003e4u; break;  // mov x4, x16
            case 0x194ae24:
                replacement = 0x52800001u | ((background & 0xffffu) << 5);
                break;
            case 0x194ae28:
                replacement = 0x72a00001u | ((background >> 16) << 5);
                break;
            case kForegroundPatchVaddr: replacement = foregroundBranch; break;
            default: break;
        }
        if (exportedIcons) {
            // Exported PNGs already contain MonoIcon's foreground, background and
            // shape. Select the normal theme loader, without the OEM tint pass.
            replacement = i >= kLegacyPatchCount ? p.replacement : p.original;
            if (p.vaddr == 0x194adc4) replacement = 0xaa1103e4u; // mov x4, x17 (false)
            if (p.vaddr == 0x194a79c || p.vaddr == 0x12c8f4c) replacement = 0xd503201fu;
            // Full-bleed exports still need the selected system icon mask. Only
            // circles are complete shapes and must skip that second mask/scale.
            if (!circle && (p.vaddr == 0x17e743c || p.vaddr == 0x1aab2b8)) replacement = p.original;
        } else if (i >= kLegacyPatchCount) {
            replacement = p.original;
        }
        patches[i] = {appBias + p.vaddr, gCurrentInstructions[i],
                      enabled ? replacement : p.original};
    }

    uintptr_t pages[kInstructionPatchCount] = {};
    size_t pageCount = 0;
    for (const auto& p : patches) {
        const uintptr_t page = p.address & ~(pageSize - 1);
        bool present = false;
        for (size_t i = 0; i < pageCount; ++i) present |= pages[i] == page;
        if (!present && pageCount < sizeof(pages) / sizeof(pages[0])) {
            pages[pageCount++] = page;
        }
    }

    if (exportedIcons) monoicon_hyos_protect_code_pages(pages, pageCount, pageSize);

    ::hyperos4::ThreadPause pause;
    if (!pause.acquire()) {
        if (foregroundPage != nullptr) munmap(foregroundPage, pageSize);
        Fail(kResultThreadPauseUnavailable, "thread-pause-unavailable");
        return kResultThreadPauseUnavailable;
    }
    // A peer must not resume midway through the MOVZ/MOVK sequence with an
    // old partial value. Reject active settings code; never edit saved registers.
    if (!pause.excludes(appBias + kSettingsFunctionVaddr,
                        appBias + kSettingsFunctionVaddr + kSettingsFunctionSize) ||
        !pause.excludes(appBias + kForegroundFunctionVaddr,
                        appBias + kForegroundFunctionVaddr + kForegroundFunctionSize) ||
        (gForegroundPage != nullptr && !pause.excludes(
            reinterpret_cast<uintptr_t>(gForegroundPage),
            reinterpret_cast<uintptr_t>(gForegroundPage) + 9 * sizeof(uint32_t)))) {
        const bool finished = pause.finish();
        if (finished && foregroundPage != nullptr) munmap(foregroundPage, pageSize);
        Fail(finished ? kResultPaletteInUse : kResultRollbackIncomplete,
             finished ? "palette-in-use" : "rollback-incomplete");
        return finished ? kResultPaletteInUse : kResultRollbackIncomplete;
    }
    // Re-validate with peers parked: no thread can change the pages now.
    if (!ValidateImages(appBias, launcherBias)) {
        const bool finished = pause.finish();
        if (finished && foregroundPage != nullptr) munmap(foregroundPage, pageSize);
        Fail(kResultIdentityMismatch, "identity-mismatch-under-pause");
        (void)finished;
        return kResultIdentityMismatch;
    }

    bool writable[kInstructionPatchCount] = {};
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
                     static_cast<uint32_t>(kInstructionPatchCount), __ATOMIC_RELEASE);

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
        for (size_t i = 0; i < kInstructionPatchCount; ++i) {
            gCurrentInstructions[i] = patches[i].replacement;
        }
        const bool finished = pause.finish();
        if (finished) {
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

void* PaletteWorker(void* data) {
    const size_t pageSize = reinterpret_cast<uintptr_t>(data);
    uintptr_t appBias = 0, launcherBias = 0;
    PaletteConfig applied;
    bool haveApplied = false, acknowledged = false;
    uint32_t previousFailure = UINT32_MAX;
    for (;;) {
        PaletteConfig next;
        uint32_t result = ReadPalette(next);
        if (result == kResultNone) {
            if (appBias == 0) appBias = FindImageBias("libapp.so");
            if (launcherBias == 0) launcherBias = FindImageBias("libapp_launcher.so");
            if (appBias == 0 || launcherBias == 0) result = kResultImageMissing;
        }
        if (result != kResultNone) {
            // A missing first file or temporarily absent image is recoverable.
            // Keep the last valid transaction until a complete replacement is
            // published; malformed/truncated writes never become instructions.
            if (result != previousFailure) {
                Fail(static_cast<PaletteResult>(result),
                     result == kResultNoFile ? "palette-file-missing" :
                     result == kResultImageMissing ? "image-missing" : "palette-parse-failed");
                previousFailure = result;
            }
            usleep(500000);
            continue;
        }
        // Some HYOS paths discard private file-backed pages through direct
        // syscalls. Only recover bytes equal to our last patch or the pinned
        // original; any third-party/unknown instruction remains a hard failure.
        bool discarded = false, known = true;
        std::array<uint32_t, kInstructionPatchCount> observed{};
        if (haveApplied) {
            for (size_t i = 0; i < kInstructionPatchCount; ++i) {
                const uint32_t word = ReadInstruction(appBias + kInstructionPatches[i].vaddr);
                observed[i] = word;
                if (word != gCurrentInstructions[i]) {
                    if (word != kInstructionPatches[i].original) { known = false; break; }
                    discarded = true;
                }
            }
            if (!known) {
                gIconsEnabled.store(false, std::memory_order_release);
                Fail(kResultIdentityMismatch, "unexpected-code-change");
                return nullptr;
            }
            if (discarded) {
                gCurrentInstructions = observed;
                haveApplied = false;
                acknowledged = false;
            }
        }
        if (haveApplied && std::strcmp(applied.payload, next.payload) == 0) {
            if (!acknowledged) acknowledged = AcknowledgePalette(applied);
            usleep(500000);
            continue;
        }
        __atomic_store_n(&monoicon_hyos_palette.app_bias_low,
                         static_cast<uint32_t>(appBias), __ATOMIC_RELEASE);
        __atomic_store_n(&monoicon_hyos_palette.app_bias_high,
                         static_cast<uint32_t>(appBias >> 32), __ATOMIC_RELEASE);
        __atomic_store_n(&monoicon_hyos_palette.launcher_bias_low,
                         static_cast<uint32_t>(launcherBias), __ATOMIC_RELEASE);
        __atomic_store_n(&monoicon_hyos_palette.launcher_bias_high,
                         static_cast<uint32_t>(launcherBias >> 32), __ATOMIC_RELEASE);
        __atomic_fetch_add(&monoicon_hyos_palette.attempts, 1u, __ATOMIC_RELAXED);
        const bool exportedIcons = next.version == 2;
        if (!ValidateImages(appBias, launcherBias)) {
            gIconsEnabled.store(false, std::memory_order_release);
            Fail(kResultIdentityMismatch, "identity-mismatch");
            return nullptr;
        }
        if (next.enabled && exportedIcons && !monoicon_hyos_icon_generation_ready(next.generation)) {
            Fail(kResultNoFile, "icon-generation-unavailable");
            usleep(500000);
            continue;
        }
        if (next.enabled && exportedIcons && !monoicon_hyos_prepare_icon_files()) {
            Fail(kResultNoFile, "icon-hooks-unavailable");
            usleep(500000);
            continue;
        }
        const bool sameCode = haveApplied && (applied.version == 2) == exportedIcons && applied.enabled == next.enabled &&
            (!next.enabled || !exportedIcons || applied.circle == next.circle) &&
            (!next.enabled || exportedIcons || (applied.background == next.background &&
                               applied.foreground == next.foreground));
        if (sameCode || (!next.enabled && !gPaletteInstalled)) {
            result = next.enabled ? kResultInstalled : kResultDisabled;
        } else {
            result = InstallPaletteTransaction(appBias, launcherBias, pageSize,
                                               next.background, next.foreground,
                                               next.enabled != 0, exportedIcons, next.circle != 0);
        }
        if (result == kResultInstalled || result == kResultDisabled) {
            __atomic_store_n(&monoicon_hyos_palette.background, next.background, __ATOMIC_RELEASE);
            __atomic_store_n(&monoicon_hyos_palette.foreground, next.foreground, __ATOMIC_RELEASE);
            monoicon_hyos_select_icon_generation(next.enabled && exportedIcons ? next.generation : nullptr);
            gIconsEnabled.store(next.enabled != 0 && exportedIcons, std::memory_order_release);
            applied = next;
            haveApplied = true;
            acknowledged = AcknowledgePalette(applied);
            previousFailure = UINT32_MAX;
            Fail(static_cast<PaletteResult>(result), next.enabled ? "config-applied" : "disabled-restored");
            LogPalette(next.enabled ? "updated MonoIcon configuration" : "restored launcher configuration");
        } else if (result == kResultRollbackIncomplete || result == kResultIdentityMismatch) {
            // Unknown code or an incomplete rendezvous must not be retried as
            // though our previous instruction state were still trustworthy.
            gIconsEnabled.store(false, std::memory_order_release);
            return nullptr;
        }
        usleep(500000);
    }
}

// Public entry: the loader callback never waits for configuration, Dart image
// discovery, or the peer rendezvous. Exactly one worker belongs to each child.
extern "C" __attribute__((used, visibility("default")))
uint32_t monoicon_hyos_palette_install() {
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
    pthread_t worker;
    const int error = pthread_create(&worker, nullptr, PaletteWorker,
                                     reinterpret_cast<void*>(static_cast<uintptr_t>(pageSize)));
    if (error != 0) {
        gPaletteWorkerPid.store(0, std::memory_order_release);
        Fail(kResultThreadPauseUnavailable, "worker-unavailable");
        return kResultThreadPauseUnavailable;
    }
    pthread_detach(worker);
    return kResultNone;
}

}  // namespace monoicon::hyos
