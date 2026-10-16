// MonoIcon's LSPosed `NativeAPIEntries::hookFunc` backend for the HyperOS 4 launcher.
//
// Why this file exists
// --------------------
// An earlier attempt at runtime instrumentation wrote its own AArch64 trampolines
// and patched launcher code directly. It crashed com.miui.home three times, leaving
// a state that survived `pm disable` and required a device reboot, because the
// launcher child is forked from the HYOS spawner and inherited the modified page.
// All of that is removed. Hooking is delegated exclusively to the hook primitive
// LSPosed hands the module through `native_init`, which owns thread quiescence,
// origin tracking and unhooking.
//
// Nothing here writes machine code, allocates executable memory, uses mprotect, or
// emits BRK/SIGTRAP.
//
// Canary policy
// -------------
// The canary deliberately changes nothing observable:
//   * it reads no Dart object;
//   * it does not touch arguments;
//   * it does not touch the return value;
//   * it only counts entries and then tail-calls the trampoline backup.
// ABI evidence for the target (6144 build, `lib/arm64-v8a/libapp.so`):
//   * `IconCacheV2.getIconImageIfAbsent` @ 0x19d79c0: zero references to x8/w8, so
//     the function does not use the AAPCS64 indirect result register and its result
//     is returned in x0. The replacement is therefore safe to write without
//     preserving x8. `IconCacheV2.createImage` @ 0x19d7d30 was checked the same way
//     and is also x8-clean.
//   * Arguments are unknown at the Dart level, which is fine: the wrapper never
//     interprets them, and x0..x7 flow into the backup untouched.
//
// Reference: wxxsfxyzm/MiuiBackGestureHook, whose InstallInlineHook() calls
// entries->hookFunc(target, replacement, backup) and pairs it with an madvise page
// guard. This file is the same shape; the madvise guard is the next step and is
// reported as not yet installed.

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <fcntl.h>
#include <unistd.h>

#include <cstdarg>
#include <cstddef>
#include <cstdlib>
#include <ctime>
#include <cstdint>
#include <cstring>
#include "hyperos4_icon_files.h"

namespace monoicon::hyos {

constexpr char kLogTag[] = "MonoIcon.Hook";
constexpr char kStatusFileName[] = "monoicon_hyos_hook.txt";

// The LSPosed native entry API. Filled in by native_init().
struct NativeAPIEntries {
    uint32_t version;
    int (*hookFunc)(void* target, void* replacement, void** backup);
    int (*unhookFunc)(void* target);
};

const NativeAPIEntries* gApi = nullptr;

extern "C" bool monoicon_hyos_prepare_icon_files() {
    return gApi && monoicon_hyos_install_icon_files(gApi->hookFunc);
}

// Which function this build hooks. Reported in the status file so a run can never be
// attributed to the wrong experiment.
constexpr char kCanaryName[] = "IconCacheV2.createImage";

// Canary bookkeeping. Declared here because the status writer reports the counter;
// the replacement function below uses these cells.
void* gCanaryBackup = nullptr;
uint32_t gCanaryCounter = 0;

// ---------------------------------------------------------------------------
// Status, readable from a file and from /proc/<pid>/mem through the exported
// symbol. Field order is a contract with the on-device verifier.
// ---------------------------------------------------------------------------
struct HookStatus {
    uint32_t magic;              // kStatusMagic once the backend was initialized
    uint32_t api_version;
    uint32_t hook_pointer_present;
    uint32_t dart_base_known;
    uint32_t anchor_hits;
    uint32_t target_found;       // target address resolved
    uint32_t hook_result;        // last hookFunc() return value
    uint32_t backup_present;     // backup pointer non-null
    uint32_t canary_entries;     // times the canary was entered
    uint32_t last_event;         // EventCode
    uint32_t fail_closed;        // 1 when a precondition failed and nothing hooked
    uint32_t pid;
    // Ordering evidence. All values are CLOCK_MONOTONIC milliseconds relative to the
    // first event this process recorded, so the hook-install time and the first
    // icon-call time can be compared directly.
    uint32_t t_backend_init;
    uint32_t t_dart_base;
    uint32_t t_hooked;
    uint32_t t_library_loaded;
    uint32_t t_first_canary;
    uint32_t dart_base_low;
    uint32_t dart_base_high;
    uint32_t canary_second_sample;
    // Loader-timing observation (B-1). Purely informational: these fields only record
    // when libapp.so became visible and when its handle became obtainable, so the
    // existing install point can be compared against the earliest observable moment.
    uint32_t t_libapp_phdr;      // dl_iterate_phdr first saw libapp.so
    uint32_t t_libapp_handle;    // RTLD_NOLOAD first returned a handle
    uint32_t t_libapp_hook;      // libapp image handed to the Dart-image scan
    uint32_t libapp_phdr_base_low;
    uint32_t libapp_phdr_base_high;
    uint32_t libapp_visible;     // 1 once dl_iterate_phdr observed it
    uint32_t libapp_handle_ok;   // 1 once RTLD_NOLOAD returned a handle
    uint32_t delta_phdr_to_hook; // t_libapp_hook - t_libapp_phdr
    uint32_t delta_phdr_to_dartbase; // t_dart_base - t_libapp_phdr
    // Attempt-level diagnosis: why the first entry into the Dart-image scan could not
    // resolve the image yet, and when it finally could.
    uint32_t install_attempts;   // times the scan was entered
    uint32_t first_attempt_hits; // anchor hits on the first attempt
    uint32_t first_attempt_t;    // when the first attempt ran
    uint32_t resolve_attempt;    // attempt number that first resolved the image
    // One-shot Dobby branch-target verification. Read-only diagnostics: after a
    // temporary hook is installed on the Dart target, the entry instruction is
    // decoded to find where Dobby actually redirected control. Nothing in this group
    // changes control flow.
    uint32_t entry_word;             // raw 32-bit instruction at target+0
    uint32_t entry_is_b;             // 1 when it is an unconditional B
    uint32_t entry_target_low;       // decoded B target (or 0)
    uint32_t entry_target_high;
    uint32_t trampoline_word0;       // first instruction of the trampoline, if any
    uint32_t trampoline_word1;
    uint32_t trampoline_word2;
    uint32_t trampoline_word3;
    uint32_t trampoline_target_low;  // final address the chain resolves to
    uint32_t trampoline_target_high;
    uint32_t replacement_low;        // MonoIconCanaryReplacement runtime address
    uint32_t replacement_high;
    uint32_t chain_matches_replacement;  // 1 when the chain ends at the replacement
    uint32_t chain_steps;                // instructions followed before giving up
    // Temporary branch-probe bookkeeping (opt-in; the hook it creates is removed
    // immediately after the measurement).
    uint32_t branch_probe_result;   // hookFunc() return value for the probe target
    uint32_t branch_probe_unhooked; // 1 once the temporary hook was removed
};

constexpr uint32_t kStatusMagic = 0x4D4F484Bu;  // "MOHK"

// Pins the layout the on-device reader and the loader-timing report assume.
static_assert(offsetof(HookStatus, t_libapp_phdr) == 80,
              "loader-timing offsets changed");

enum EventCode : uint32_t {
    kEventNone = 0,
    kEventApiMissing = 1,
    kEventDartBaseMissing = 2,
    kEventTargetMissing = 3,
    kEventHookFailed = 4,
    kEventBackupMissing = 5,
    kEventHooked = 6,
    kEventDartBasePending = 7,  // image visible but not resolvable yet; will retry
};

extern "C" __attribute__((used, visibility("default")))
HookStatus monoicon_hyos_hook = {};

void Log(const char* message) {
    __android_log_write(ANDROID_LOG_INFO, kLogTag, message);
}

void Logf(const char* format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_INFO, kLogTag, format, args);
    va_end(args);
}

// Monotonic milliseconds, made relative to the first call in this process. Used to
// prove the ordering between "hook installed" and "first icon call" instead of
// assuming it.
uint32_t NowMs() {
    struct timespec now {};
    clock_gettime(CLOCK_MONOTONIC, &now);
    static uint64_t origin = 0u;
    const uint64_t value = static_cast<uint64_t>(now.tv_sec) * 1000u +
                           static_cast<uint64_t>(now.tv_nsec) / 1000000u;
    if (origin == 0u) origin = value;
    return static_cast<uint32_t>(value - origin);
}

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

// Persist the hook state. The launcher's logcat is unreliable for this payload, so
// the file is the primary record.
void WriteHookStatus(const char* phase) {
    const char* paths[] = {
        "/data/user/0/com.miui.home/files/monoicon_hyos_hook.txt",
        "/data/data/com.miui.home/files/monoicon_hyos_hook.txt",
        "/data/local/tmp/monoicon_hyos_hook.txt",
    };
    char body[640] = {};
    // The canary counter is incremented by the replacement; a relaxed read is enough
    // for reporting.
    const uint32_t canary = __atomic_load_n(&gCanaryCounter, __ATOMIC_RELAXED);
    AppendText(body, sizeof(body), "phase=");
    AppendText(body, sizeof(body), phase);
    // Identify which function is hooked in this build, so a status file can never be
    // misread as belonging to a different experiment.
    AppendText(body, sizeof(body), " target=");
    AppendText(body, sizeof(body), kCanaryName);
    AppendText(body, sizeof(body), " pid=");
    AppendUint(body, sizeof(body), static_cast<uint32_t>(getpid()));
    const char* fields[] = {
        " api=", " hookptr=", " dart_base=", " anchor=",
        " target=", " hook_result=", " backup=", " canary=", " event=", " fail_closed=",
        " t_init=", " t_dartbase=", " t_hooked=", " t_first_canary=", " t_second=",
        " t_phdr=", " t_handle=", " t_libapphook=", " visible=", " handle_ok=",
        " d_phdr_hook=", " d_phdr_dartbase=",
        " attempts=", " first_hits=", " t_first_attempt=", " resolve_attempt=",
        " entry=", " is_b=", " b_target=", " tramp0=", " tramp1=", " tramp2=", " tramp3=",
        " tramp_target=", " replacement=", " chain_ok=", " chain_steps=",
        " probe_result=", " probe_unhooked=",
    };    const uint32_t values[] = {
        __atomic_load_n(&monoicon_hyos_hook.api_version, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.hook_pointer_present, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.dart_base_known, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.anchor_hits, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.target_found, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.hook_result, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.backup_present, __ATOMIC_ACQUIRE),
        canary,
        __atomic_load_n(&monoicon_hyos_hook.last_event, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.fail_closed, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.t_backend_init, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.t_dart_base, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.t_hooked, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.t_first_canary, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.canary_second_sample, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.t_libapp_phdr, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.t_libapp_handle, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.t_libapp_hook, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.libapp_visible, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.libapp_handle_ok, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.delta_phdr_to_hook, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.delta_phdr_to_dartbase, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.install_attempts, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.first_attempt_hits, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.first_attempt_t, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.resolve_attempt, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.entry_word, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.entry_is_b, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.entry_target_low, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.trampoline_word0, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.trampoline_word1, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.trampoline_word2, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.trampoline_word3, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.trampoline_target_low, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.replacement_low, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.chain_matches_replacement, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.chain_steps, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.branch_probe_result, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_hook.branch_probe_unhooked, __ATOMIC_ACQUIRE),
    };
    for (size_t index = 0u; index < sizeof(values) / sizeof(values[0]); ++index) {
        AppendText(body, sizeof(body), fields[index]);
        AppendUint(body, sizeof(body), values[index]);
    }
    AppendText(body, sizeof(body), "\n");

    for (const char* path : paths) {
        const int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
        if (fd < 0) continue;
        ssize_t written;
        do {
            written = write(fd, body, std::strlen(body));
        } while (written < 0);
        close(fd);
        if (written > 0) return;
    }
}

// ---------------------------------------------------------------------------
// Canary replacement.
//
// A plain C function on purpose. An earlier revision wrote this as a `naked`
// function with hand-written assembly to avoid a compiler prologue; that version
// crashed com.miui.home on first entry at its own counter store, with the fault
// address equal to this module's status magic, i.e. the hand-managed register was
// not what the assembly assumed. Letting the compiler generate the prologue and
// epilogue removes that entire class of bug: every callee-saved register is
// preserved by construction and the stack is always ABI-correct.
//
// The cost is that a compiler prologue may use x8 (a caller-saved register, and the
// AAPCS64 indirect-result register). That is acceptable here because the canary
// target is x8-clean: `IconCacheV2.getIconImageIfAbsent` and
// `IconCacheV2.createImage` both contain zero references to x8/w8 in the 6144 image,
// so no indirect result pointer can be live across this call.
//
// Behaviour: increments a counter and forwards the call to the trampoline backup. It
// reads no Dart object, does not modify arguments, does not modify the result,
// allocates nothing, and takes no lock.
// ---------------------------------------------------------------------------
#if defined(__aarch64__)
// Fully transparent pass-through: branch straight to the trampoline backup.
//
// Nothing is saved, restored, counted or called. x0..x8 and LR are exactly as the
// caller (LSPosed's trampoline) left them, so the original function sees the call it
// would have seen without the hook, and its `ret` returns directly to the Dart
// caller.
//
// Why `naked` and not a plain C function: with -mbranch-protection=standard clang
// emits `paciasp`/`autiasp` plus a stack frame for a C function. The target itself
// is not PAC-protected (verified: `IconCacheV2.getIconImageIfAbsent` and
// `IconCacheV2.createImage` in the 6144 image contain zero paciasp/pacibsp and zero
// autiasp/autibsp, and no `bti`), so introducing a PAC prologue/epilogue pair on a
// path the prologue never signed is a gratuitous asymmetry on the return path. This
// version has no prologue and no epilogue at all.
//
// x9 and x10 are touched; both are caller-saved (scratch) in AAPCS64. The globals
// live in an anonymous namespace, so the assembler sees their mangled symbol names.
// B-2b: the single Dart hook is now `_IconDrawableWidgetState._renderAndCache`, used
// purely to measure when the first render pass happens relative to `libapp.so`
// becoming visible.
//
// Transparency is preserved exactly: x0..x8 and LR are passed through untouched and
// the original function runs via the trampoline backup. The only added work is a
// single counter increment. No arguments, no return value, no Dart object, no syscall,
// no stack frame, no logging.
//
// The B-2b revision of this stub additionally called clock_gettime on first entry; that
// probe is removed now that the experiment is over.
//
// The stub only touches gCanaryCounter / gCanaryBackup through the `adrp` + `:lo12:`
// form.
extern "C" __attribute__((naked, used)) void MonoIconCanaryReplacement() {
    __asm__ volatile(
        "adrp x9, _ZN8monoicon4hyos14gCanaryCounterE\n"
        "add  x9, x9, :lo12:_ZN8monoicon4hyos14gCanaryCounterE\n"
        "ldr  w10, [x9]\n"
        "add  w10, w10, #1\n"
        "str  w10, [x9]\n"
        // Transparent forward: x0..x8 and LR are untouched.
        "adrp x9, _ZN8monoicon4hyos13gCanaryBackupE\n"
        "add  x9, x9, :lo12:_ZN8monoicon4hyos13gCanaryBackupE\n"
        "ldr  x9, [x9]\n"
        "br   x9\n"
        :
        :
        : "x9", "x10", "memory");
}
#endif

// ---------------------------------------------------------------------------
// Dart image discovery (B-2a: direct resolve).
//
// The Dart AOT image is mapped from MiuiHome.apk rather than through the dynamic
// linker, but dl_iterate_phdr still reports it, so its load bias is available
// directly. The runtime address is therefore
//
//     runtime = dlpi_addr + confirmed RVA
//
// with the 32-byte signature used only to validate the computed address, not as a
// search key. Addresses stay build-derived (RVA + runtime bias); nothing is a fixed
// absolute address.
// ---------------------------------------------------------------------------
// Experiment C: the only hooked target is IconCacheV2.createImage. The previous
// target (getIconImageIfAbsent) is deliberately NOT hooked at the same time, so a
// zero counter here cannot be confused with interference from another probe.
constexpr uint64_t kAnchorVaddr = 0x19d7d30;  // IconCacheV2.createImage
constexpr uint64_t kCanaryVaddr = 0x19d7d30;  // IconCacheV2.createImage
// Opt-in branch-probe target: `_IconDrawableWidgetState._renderAndCache`. It is used
// only by the one-shot temporary measurement, never as the durable hook. Its entry word
// is the same `stp x29, x30, [x15, #-0x10]!` as the canary target, so it is validated
// with the same first-word check.
constexpr uint64_t kBranchProbeVaddr = 0x19d78c0;
constexpr uint32_t kBranchProbeFirstWord = 0xa9bf79fdu;
// First 32 bytes at 0x19d7d30 in the 6144 libapp.so, taken from the extracted image.
// The `sub x15, x15, #0x138` word (0xd104e1ef) distinguishes createImage from
// _renderAndCache (0xd100a1ef).
constexpr uint8_t kAnchorSignature[32] = {
    0xfd, 0x79, 0xbf, 0xa9, 0xfd, 0x03, 0x0f, 0xaa, 0xef, 0xe1, 0x04, 0xd1, 0xb6, 0x83, 0x1f, 0xf8,
    0xe4, 0x03, 0x02, 0xaa, 0xa2, 0x03, 0x12, 0xf8, 0xe2, 0x03, 0x05, 0xaa, 0xa5, 0x03, 0x11, 0xf8,
};
// First instruction of the canary target in the 6144 build, used to confirm the
// resolved address really is that function before hooking it. This is an independent
// second check, beyond the byte-for-byte signature comparison above.
constexpr uint32_t kCanaryFirstWord = 0xa9bf79fdu;

// ---------------------------------------------------------------------------
// B-2a: direct resolve instead of an address-space scan.
//
// The earlier implementation walked every executable MiuiHome.apk mapping and
// memcmp'd a 32-byte signature across ~31MB of image (7 segments, the largest being
// libapp.so's 18.6MB r-x segment). Measurement showed that scan cost ~110ms, which
// sat entirely between "libapp.so first visible" and "hook installed".
//
// Instead, take the load bias from dl_iterate_phdr and add the confirmed RVA. The
// signature is still checked, but now as a cheap validation of the computed address
// rather than as a search across the address space. No large scan is kept as a
// fallback: this change exists to prove the direct-RVA path, so a scan fallback would
// hide exactly the thing being measured.
// ---------------------------------------------------------------------------

// Path fragment that identifies the Dart AOT image in dl_iterate_phdr.
constexpr char kDartPathFragment[] = "libapp.so";

struct DartImageProbe {
    uintptr_t bias;
};

int CaptureDartImage(dl_phdr_info* info, size_t, void* data) {
    auto* probe = static_cast<DartImageProbe*>(data);
    if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;
    if (std::strstr(info->dlpi_name, kDartPathFragment) == nullptr) return 0;
    probe->bias = static_cast<uintptr_t>(info->dlpi_addr);
    return 1;  // stop at the first match
}

// Returns the Dart image base via bias + RVA, or 0 when the address cannot be
// validated. `hitsOut` reports how many of the 32 signature bytes matched at the
// computed address: 32 means the address is exactly createImage.
uintptr_t DiscoverDartBase(uint32_t* hitsOut) {
    *hitsOut = 0u;

    DartImageProbe probe{0u};
    dl_iterate_phdr(CaptureDartImage, &probe);
    if (probe.bias == 0u) return 0u;

    const uintptr_t base = probe.bias;
    const uintptr_t target = base + kAnchorVaddr;
    if (target <= base) return 0u;  // overflow guard

    // Validate the computed address by reading the function's own entry bytes. This
    // touches 32 bytes, not megabytes.
    uint32_t matched = 0u;
    for (size_t index = 0u; index < sizeof(kAnchorSignature); ++index) {
        const uint8_t actual =
            *reinterpret_cast<const uint8_t*>(target + index);
        if (actual != kAnchorSignature[index]) break;
        ++matched;
    }
    *hitsOut = matched;
    return matched == sizeof(kAnchorSignature) ? base : 0u;
}

void FailClosed(EventCode event, const char* phase) {
    __atomic_store_n(&monoicon_hyos_hook.last_event,
                     static_cast<uint32_t>(event), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.fail_closed, 1u, __ATOMIC_RELEASE);
    Logf("hook backend fail-closed: event=%u (launcher behaviour unchanged)",
         static_cast<uint32_t>(event));
    WriteHookStatus(phase);
}

}  // namespace monoicon::hyos

// ---------------------------------------------------------------------------
// Entry points used by the native_init payload.
// ---------------------------------------------------------------------------

// Store the LSPosed hook primitive. Must succeed before any hook is attempted.
extern "C" __attribute__((used, visibility("default")))
void monoicon_hyos_hook_backend_init(const void* entries) {
    using namespace monoicon::hyos;
    const auto* api = static_cast<const NativeAPIEntries*>(entries);
    gApi = (api != nullptr && api->hookFunc != nullptr && api->unhookFunc != nullptr)
               ? api
               : nullptr;
    __atomic_store_n(&monoicon_hyos_hook.magic, kStatusMagic, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.pid,
                     static_cast<uint32_t>(getpid()), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.api_version,
                     api != nullptr ? api->version : 0u, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.hook_pointer_present,
                     gApi != nullptr ? 1u : 0u, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.t_backend_init, NowMs(), __ATOMIC_RELEASE);
    WriteHookStatus("backend-init");
}

// Install the canary. Called after libapp.so has been mapped.
// Snapshot the live canary counter on demand (fresh count, no hot-path I/O).
extern "C" __attribute__((used, visibility("default")))
void monoicon_hyos_dump_hook_status();

// Read-only: decode where Dobby redirected a hooked target's entry (defined below).
extern "C" __attribute__((used, visibility("default")))
void monoicon_hyos_capture_branch_target(void* target);

extern "C" __attribute__((used, visibility("default")))
void monoicon_hyos_install_canary_hook() {
    using namespace monoicon::hyos;

    if (gApi == nullptr) {
        FailClosed(kEventApiMissing, "api-missing");
        return;
    }
    // Guard against re-entry: a forked launcher child inherits this process image.
    static uint32_t installed = 0u;
    if (__atomic_exchange_n(&installed, 1u, __ATOMIC_ACQ_REL) != 0u) return;

    // Attempt-level diagnosis. The Dart image is mapped before it is resolvable: the
    // first entry into this function can see libapp.so through dl_iterate_phdr while
    // the anchor signature is not yet readable. Scan a bounded number of times with a
    // short pause, recording every attempt, so the moment the image becomes resolvable
    // is measured rather than assumed. Nothing is patched while the image is not
    // resolvable, and the launcher is untouched by these retries.
    constexpr uint32_t kMaxAttempts = 12u;
    constexpr unsigned kPauseMicros = 2000u;
    uintptr_t dartBase = 0u;
    uint32_t anchorHits = 0u;
    uint32_t attempt = 0u;
    for (; attempt < kMaxAttempts; ++attempt) {
        const uint32_t sequence = __atomic_add_fetch(
            &monoicon_hyos_hook.install_attempts, 1u, __ATOMIC_ACQ_REL);
        anchorHits = 0u;
        dartBase = DiscoverDartBase(&anchorHits);
        if (attempt == 0u) {
            __atomic_store_n(&monoicon_hyos_hook.first_attempt_hits, anchorHits,
                             __ATOMIC_RELEASE);
            __atomic_store_n(&monoicon_hyos_hook.first_attempt_t, NowMs(),
                             __ATOMIC_RELEASE);
        }
        if (dartBase != 0u) {
            attempt = sequence;  // report the global attempt number
            break;
        }
        usleep(kPauseMicros);
    }
    __atomic_store_n(&monoicon_hyos_hook.anchor_hits, anchorHits, __ATOMIC_RELEASE);
    if (dartBase == 0u) {
        // Still not resolvable after the bounded retries. Nothing was patched; release
        // the guard so a later notification can try again.
        __atomic_store_n(&installed, 0u, __ATOMIC_RELEASE);
        __atomic_store_n(&monoicon_hyos_hook.last_event,
                         static_cast<uint32_t>(kEventDartBasePending), __ATOMIC_RELEASE);
        Logf("dart image not resolvable after %u attempts (anchor hits %u)",
             kMaxAttempts, anchorHits);
        WriteHookStatus("dart-base-pending");
        return;
    }
    __atomic_store_n(&monoicon_hyos_hook.resolve_attempt,
                     __atomic_load_n(&monoicon_hyos_hook.install_attempts,
                                     __ATOMIC_ACQUIRE),
                     __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.dart_base_known, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.dart_base_low,
                     static_cast<uint32_t>(dartBase & 0xffffffffu), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.dart_base_high,
                     static_cast<uint32_t>(dartBase >> 32), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.t_dart_base, NowMs(), __ATOMIC_RELEASE);

    void* target = reinterpret_cast<void*>(dartBase + kCanaryVaddr);
    uint32_t firstWord = 0u;
    std::memcpy(&firstWord, target, sizeof(firstWord));
    if (firstWord != kCanaryFirstWord) {
        // The resolved address is not the expected function: refuse rather than
        // hook unknown code.
        FailClosed(kEventTargetMissing, "target-mismatch");
        return;
    }
    __atomic_store_n(&monoicon_hyos_hook.target_found, 1u, __ATOMIC_RELEASE);

    void* backup = nullptr;
    const int result = gApi->hookFunc(target,
                                      reinterpret_cast<void*>(&MonoIconCanaryReplacement),
                                      &backup);
    __atomic_store_n(&monoicon_hyos_hook.hook_result,
                     static_cast<uint32_t>(result), __ATOMIC_RELEASE);
    if (result != 0) {
        FailClosed(kEventHookFailed, "hook-failed");
        return;
    }
    if (backup == nullptr) {
        // A hook without a backup cannot be made transparent; undo it.
        gApi->unhookFunc(target);
        FailClosed(kEventBackupMissing, "backup-missing");
        return;
    }
    gCanaryBackup = backup;
    __atomic_store_n(&monoicon_hyos_hook.backup_present, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.t_hooked, NowMs(), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.last_event,
                     static_cast<uint32_t>(kEventHooked), __ATOMIC_RELEASE);
    Logf("canary hook installed: target=%p backup=%p", target, backup);
    WriteHookStatus("canary-hooked");

    // One-shot Dobby branch-target verification, opt-in via MONOICON_HYOS_BRANCH_PROBE=1.
    //
    // A second function is temporarily hooked purely to observe where Dobby redirects
    // its entry, then unhooked immediately so the process is left exactly as the
    // baseline had it. Only if that flag is set is the extra hook ever created; with
    // the default environment this block does not run at all.
    void* branchProbeReplace = nullptr;
    (void)branchProbeReplace;
    // The spawner's environment cannot be set from adb, so a diagnostic build can force
    // the probe on with -DMONOICON_HYOS_BRANCH_PROBE_ALWAYS. The probe still unhooks
    // immediately; only whether it runs at all changes.
#if defined(MONOICON_HYOS_BRANCH_PROBE_ALWAYS)
    constexpr bool kBranchProbeEnabled = true;
#else
    const char* enabled = getenv("MONOICON_HYOS_BRANCH_PROBE");
    const bool kBranchProbeEnabled = enabled != nullptr && enabled[0] == '1';
#endif
    if (kBranchProbeEnabled) {
        void* probeTarget = reinterpret_cast<void*>(dartBase + kBranchProbeVaddr);
        uint32_t probeWord = 0u;
        std::memcpy(&probeWord, probeTarget, sizeof(probeWord));
        if (probeWord == kBranchProbeFirstWord) {
            void* probeBackup = nullptr;
            const int probeResult = gApi->hookFunc(
                probeTarget, reinterpret_cast<void*>(&MonoIconCanaryReplacement),
                &probeBackup);
            if (probeResult == 0) {
                // Read and decode while the temporary hook is in place.
                monoicon_hyos_capture_branch_target(probeTarget);
                // Restore immediately: the process returns to the baseline state.
                gApi->unhookFunc(probeTarget);
                __atomic_store_n(&monoicon_hyos_hook.branch_probe_unhooked, 1u,
                                 __ATOMIC_RELEASE);
                Logf("branch probe: target=%p backup=%p, unhooked", probeTarget,
                     probeBackup);
            } else {
                __atomic_store_n(&monoicon_hyos_hook.branch_probe_result,
                                 static_cast<uint32_t>(probeResult), __ATOMIC_RELEASE);
            }
        }
        WriteHookStatus("branch-probe-done");
    }
}

// Snapshot the live canary counter and write the status file. Called on demand by
// the on-device verifier, so the hot path itself performs no I/O.
extern "C" __attribute__((used, visibility("default")))
void monoicon_hyos_dump_hook_status() {
    using namespace monoicon::hyos;
    __atomic_store_n(&monoicon_hyos_hook.canary_entries,
                     __atomic_load_n(&gCanaryCounter, __ATOMIC_RELAXED),
                     __ATOMIC_RELEASE);
    WriteHookStatus("dump");
}

// ---------------------------------------------------------------------------
// B-1: loader-timing observation. Read-only: these functions inspect already-loaded
// images and record timestamps. They never load, unload, patch or reorder anything.
// ---------------------------------------------------------------------------

namespace monoicon::hyos {

struct PhdrProbe {
    uintptr_t base;
    bool found;
};

// dl_iterate_phdr callback. It only records the first match and stops the walk.
int ObserveDartPhdr(dl_phdr_info* info, size_t, void* data) {
    auto* probe = static_cast<PhdrProbe*>(data);
    if (info->dlpi_name == nullptr) return 0;
    if (std::strstr(info->dlpi_name, "libapp.so") == nullptr) return 0;
    probe->base = static_cast<uintptr_t>(info->dlpi_addr);
    probe->found = true;
    return 1;  // stop
}

}  // namespace monoicon::hyos

// Record the moment libapp.so first becomes visible in the loaded-image list.
extern "C" __attribute__((used, visibility("default")))
uint32_t monoicon_hyos_observe_libapp_visible() {
    using namespace monoicon::hyos;
    if (__atomic_load_n(&monoicon_hyos_hook.libapp_visible, __ATOMIC_ACQUIRE) != 0u) {
        return 0u;
    }
    PhdrProbe probe{0u, false};
    dl_iterate_phdr(ObserveDartPhdr, &probe);
    if (!probe.found) return 0u;
    const uint32_t now = NowMs();
    __atomic_store_n(&monoicon_hyos_hook.libapp_phdr_base_low,
                     static_cast<uint32_t>(probe.base & 0xffffffffu), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.libapp_phdr_base_high,
                     static_cast<uint32_t>(probe.base >> 32), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.t_libapp_phdr, now, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.libapp_visible, 1u, __ATOMIC_RELEASE);
    WriteHookStatus("libapp-visible-phdr");
    return now;
}

// Record the moment a handle for libapp.so first becomes obtainable.
extern "C" __attribute__((used, visibility("default")))
uint32_t monoicon_hyos_observe_libapp_handle() {
    using namespace monoicon::hyos;
    if (__atomic_load_n(&monoicon_hyos_hook.libapp_handle_ok, __ATOMIC_ACQUIRE) != 0u) {
        return 0u;
    }
    const uint32_t now = NowMs();
    __atomic_store_n(&monoicon_hyos_hook.t_libapp_handle, now, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.libapp_handle_ok, 1u, __ATOMIC_RELEASE);
    WriteHookStatus("libapp-handle");
    return now;
}

// Record the moment the Dart-image scan is entered for libapp.so, i.e. the point at
// which the current code would install its hook.
extern "C" __attribute__((used, visibility("default")))
uint32_t monoicon_hyos_observe_libapp_hook() {
    using namespace monoicon::hyos;
    if (__atomic_load_n(&monoicon_hyos_hook.t_libapp_hook, __ATOMIC_ACQUIRE) != 0u) {
        return 0u;
    }
    const uint32_t now = NowMs();
    const uint32_t phdr = __atomic_load_n(&monoicon_hyos_hook.t_libapp_phdr,
                                          __ATOMIC_ACQUIRE);
    __atomic_store_n(&monoicon_hyos_hook.t_libapp_hook, now, __ATOMIC_RELEASE);
    if (phdr != 0u && now >= phdr) {
        __atomic_store_n(&monoicon_hyos_hook.delta_phdr_to_hook, now - phdr,
                         __ATOMIC_RELEASE);
    }
    WriteHookStatus("libapp-hook-entry");
    return now;
}

// Fill in the derived deltas once both sides are known.
extern "C" __attribute__((used, visibility("default")))
void monoicon_hyos_observe_finalize() {
    using namespace monoicon::hyos;
    const uint32_t phdr = __atomic_load_n(&monoicon_hyos_hook.t_libapp_phdr,
                                          __ATOMIC_ACQUIRE);
    const uint32_t dartbase = __atomic_load_n(&monoicon_hyos_hook.t_dart_base,
                                              __ATOMIC_ACQUIRE);
    if (phdr != 0u && dartbase >= phdr) {
        __atomic_store_n(&monoicon_hyos_hook.delta_phdr_to_dartbase,
                         dartbase - phdr, __ATOMIC_RELEASE);
    }
    WriteHookStatus("timing-finalize");
}

namespace monoicon::hyos {

// Decode an AArch64 unconditional B: target = PC + (sign_extended_imm26 << 2).
// Only B is handled; BL/BCond/trampoline encodings return 0 and the caller reports
// the raw word instead of guessing.
uintptr_t DecodeUnconditionalBranch(uint32_t instruction, uintptr_t pc) {
    if ((instruction >> 26) != 0x05u) return 0u;  // opcode[31:26] == 0b000101
    int32_t imm26 = static_cast<int32_t>(instruction & 0x03FFFFFFu);
    if (imm26 & 0x02000000) imm26 -= 0x04000000;  // sign-extend 26 bits
    return pc + (static_cast<intptr_t>(imm26) << 2);
}

// Read the four words at an address without faulting if the page is unreadable.
bool ReadWords(uintptr_t address, uint32_t* out, size_t count) {
    for (size_t index = 0u; index < count; ++index) {
        uint32_t word = 0u;
        const auto* source = reinterpret_cast<const uint32_t*>(address + index * 4u);
        if (source == nullptr) return false;
        word = *source;
        out[index] = word;
    }
    return true;
}

}  // namespace monoicon::hyos

// One-shot, read-only verification of where Dobby redirected the Dart target's entry.
//
// Called immediately after hookFunc() returns for the target, while the temporary hook
// is still installed. It only reads and decodes instructions; it does not write code,
// change permissions, or alter the hook. The caller unhooks right afterwards.
extern "C" __attribute__((used, visibility("default")))
void monoicon_hyos_capture_branch_target(void* target) {
    using namespace monoicon::hyos;
    if (target == nullptr) return;

    const uintptr_t targetAddress = reinterpret_cast<uintptr_t>(target);
    const uintptr_t replacement =
        reinterpret_cast<uintptr_t>(&MonoIconCanaryReplacement);

    __atomic_store_n(&monoicon_hyos_hook.replacement_low,
                     static_cast<uint32_t>(replacement & 0xffffffffu), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.replacement_high,
                     static_cast<uint32_t>(replacement >> 32), __ATOMIC_RELEASE);

    // Entry instruction.
    uint32_t entry = 0u;
    if (!ReadWords(targetAddress, &entry, 1u)) {
        WriteHookStatus("branch-capture-entry-unreadable");
        return;
    }
    __atomic_store_n(&monoicon_hyos_hook.entry_word, entry, __ATOMIC_RELEASE);

    uintptr_t next = DecodeUnconditionalBranch(entry, targetAddress);
    __atomic_store_n(&monoicon_hyos_hook.entry_is_b, next != 0u ? 1u : 0u,
                     __ATOMIC_RELEASE);
    if (next == 0u) {
        // Not an unconditional B: record the raw word and stop rather than guess.
        WriteHookStatus("branch-capture-not-b");
        return;
    }

    __atomic_store_n(&monoicon_hyos_hook.entry_target_low,
                     static_cast<uint32_t>(next & 0xffffffffu), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_hook.entry_target_high,
                     static_cast<uint32_t>(next >> 32), __ATOMIC_RELEASE);

    uintptr_t final = 0u;
    uint32_t steps = 0u;

    if (next == replacement) {
        final = next;  // entry B already lands directly on our replacement
        steps = 1u;
    } else {
        // Record the trampoline's first 16 bytes so the encoding is on record.
        uint32_t words[4] = {0u, 0u, 0u, 0u};
        if (ReadWords(next, words, 4u)) {
            __atomic_store_n(&monoicon_hyos_hook.trampoline_word0, words[0],
                             __ATOMIC_RELEASE);
            __atomic_store_n(&monoicon_hyos_hook.trampoline_word1, words[1],
                             __ATOMIC_RELEASE);
            __atomic_store_n(&monoicon_hyos_hook.trampoline_word2, words[2],
                             __ATOMIC_RELEASE);
            __atomic_store_n(&monoicon_hyos_hook.trampoline_word3, words[3],
                             __ATOMIC_RELEASE);

            // Walk a short forward chain of unconditional B / LDR-literal + BR forms.
            uintptr_t cursor = next;
            for (uint32_t step = 0u; step < 8u; ++step) {
                uint32_t current = 0u;
                if (!ReadWords(cursor, &current, 1u)) break;
                ++steps;

                const uintptr_t branch = DecodeUnconditionalBranch(current, cursor);
                if (branch != 0u) {
                    if (branch == replacement) {
                        final = branch;
                        break;
                    }
                    // Avoid cycling forever.
                    if (branch == cursor) break;
                    cursor = branch;
                    continue;
                }

                // adrp/add pair building an absolute address, then br.
                if ((current & 0x9F000000u) == 0x90000000u) {  // ADRP
                    const int32_t immlo = static_cast<int32_t>((current >> 29) & 0x3u);
                    int64_t immhi = static_cast<int64_t>((current >> 5) & 0x7FFFFu);
                    if (immhi & 0x40000) immhi -= 0x80000;
                    const int64_t pages = (immhi << 2) | immlo;
                    const uintptr_t page =
                        (cursor & ~static_cast<uintptr_t>(0xFFFu)) +
                        static_cast<uintptr_t>(pages << 12);
                    uint32_t addWord = 0u;
                    if (!ReadWords(cursor + 4u, &addWord, 1u)) break;
                    if ((addWord & 0xFFC00000u) == 0x91000000u) {  // ADD imm
                        const uintptr_t imm12 = (addWord >> 10) & 0xFFFu;
                        const uintptr_t resolved = page + imm12;
                        if (resolved == replacement) {
                            final = resolved;
                            break;
                        }
                        cursor = resolved;
                        continue;
                    }
                    break;
                }
                break;
            }
        }
    }

    __atomic_store_n(&monoicon_hyos_hook.chain_steps, steps, __ATOMIC_RELEASE);
    if (final != 0u) {
        __atomic_store_n(&monoicon_hyos_hook.trampoline_target_low,
                         static_cast<uint32_t>(final & 0xffffffffu), __ATOMIC_RELEASE);
        __atomic_store_n(&monoicon_hyos_hook.trampoline_target_high,
                         static_cast<uint32_t>(final >> 32), __ATOMIC_RELEASE);
    }
    __atomic_store_n(&monoicon_hyos_hook.chain_matches_replacement,
                     final == replacement ? 1u : 0u, __ATOMIC_RELEASE);
    WriteHookStatus("branch-capture");
}
