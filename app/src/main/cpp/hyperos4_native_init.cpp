// MonoIcon's LSPosed native_init payload for the dex-less HyperOS 4 launcher.
//
// Why this exists
// ---------------
// On HyperOS 4 the launcher is not an ART process. `hyos_spawner`
// (/system_ext/bin/hyos_spawner) is exec'd as `usap64`, and it spawns
// `com.miui.home` without a Java runtime, so LSPosed's Java module callbacks
// (onModuleLoaded / onPackageReady / onPackageLoaded) and `System.loadLibrary`
// from Kotlin can never run there. Verified on the test device: the launcher
// process maps no `app_process`, no `libart`, no `libdexfile`, and its parent is
// the native spawner rather than zygote.
//
// LSPosed's `META-INF/xposed/native_init.list` mechanism is the supported way
// into those processes. LSPosed loads each listed library and calls the exported
// `native_init(entries)`. That call happens in the root spawner (cmdline
// `usap64`, executable /system_ext/bin/hyos_spawner) before it forks the package
// child, and the value returned from `native_init` becomes the module's
// on-library-loaded callback for later `dlopen`/`android_dlopen_ext` calls.
//
// The chain this payload uses
// ---------------------------
//   native_init(entries)                     <- in the HYOS spawner process
//     -> return OnLibraryLoaded               <- LSPosed arms it as the hook
//   dlopen("libapp_launcher.so")              <- spawner loads the launcher entry
//     -> OnLibraryLoaded(name, handle)
//        -> dlsym(handle, "app_entry_point")  <- the launcher's native entry point
//   dlopen("libapp.so")                       <- Dart AOT snapshot
//     -> OnLibraryLoaded(name, handle)
//
// Because the spawner can preload those images *before* LSPosed invokes
// `native_init`, the callback only sees later loads; the already-loaded set is
// replayed with `RTLD_NOLOAD`, which never causes a new dependency to load.
//
// Evidence sinks
// --------------
// Two independent records are written on the device:
//   1. logcat, tag `MonoIcon.HYOS`;
//   2. a status file, `monoicon_hyos_init.txt`, in the first writable location
//      found (the launcher's own files dir first, then /data/local/tmp).
// The file exists because a HYOS spawner's logcat output can be lost; the file is
// readable from adb as root and therefore cannot be silently dropped.
// `monoicon_hyos_status` is also exported so the same state can be read out of
// /proc/<pid>/mem.
//
// A launch-breaking experiment switch is included on purpose: with
// MONOICON_HYOS_EXIT_PROBE=1 in the spawner environment, native_init terminates
// the process immediately. If the launcher stops starting, native_init provably
// ran; with the variable unset the payload never exits.
//
// Scope of this file: entry and observation only. It proves and reports that
// MonoIcon native code runs inside the HyperOS 4 launcher. Icon hooks are added
// on top of this once the entry is confirmed on a device.
//
// Reference: wxxsfxyzm/MiuiBackGestureHook (miui-home-hyos-native/) and
// zilewang7/HyperOS4SmallWindowInputFilter, which use the same LSPosed
// native_init contract.

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdlib.h>
#include <unistd.h>

#include <cstdarg>
#include <cstdint>
#include <cstring>

namespace {

constexpr char kLogTag[] = "MonoIcon.HYOS";

// The launcher generation this payload targets. Identity comes from the process
// image, never from an APK version code: the supported-build constants for the
// older 8.01.02.7726 launcher are deliberately not used here.
constexpr char kLauncherProcessName[] = "com.miui.home";
constexpr char kSpawnerPath[] = "/system_ext/bin/hyos_spawner";

constexpr char kLauncherLibrary[] = "libapp_launcher.so";
constexpr char kLauncherEntrySymbol[] = "app_entry_point";
constexpr char kDartLibrary[] = "libapp.so";

constexpr char kStatusFileName[] = "monoicon_hyos_init.txt";
constexpr char kExitProbeEnv[] = "MONOICON_HYOS_EXIT_PROBE";

// ---------------------------------------------------------------------------
// LSPosed native_init API. Layout confirmed against the reference modules:
//   struct NativeAPIEntries { uint32_t version; hookFunc; unhookFunc; };
//   using NativeOnModuleLoaded = void (*)(const char* name, void* handle);
// ---------------------------------------------------------------------------
struct NativeAPIEntries {
    uint32_t version;
    int (*hookFunc)(void* target, void* replacement, void** backup);
    int (*unhookFunc)(void* target);
};

using NativeOnModuleLoaded = void (*)(const char* name, void* handle);

// ---------------------------------------------------------------------------
// Cross-process readable status. Field order and layout are a contract with the
// on-device verifier, which locates this symbol in the payload's .dynsym and
// reads it out of /proc/<pid>/mem.
// ---------------------------------------------------------------------------
struct HyosStatus {
    uint32_t magic;              // kStatusMagic once native_init has run
    uint32_t api_version;        // NativeAPIEntries.version as observed
    uint32_t have_hook_func;
    uint32_t have_unhook_func;
    uint32_t in_spawner_process; // /proc/self/exe == hyos_spawner
    uint32_t in_launcher_child;  // /proc/self/cmdline == com.miui.home
    uint32_t launcher_library_seen;
    uint32_t launcher_library_backfilled;
    uint32_t entry_point_resolved;
    uint32_t dart_library_seen;
    uint32_t lifecycle_sequence;
    uint32_t pid;
    uint32_t has_app_entry_point;  // copied, so no code pointer is published
    uint32_t status_file_written;
};

constexpr uint32_t kStatusMagic = 0x4D4F4E49u;  // "MONI"

extern "C" __attribute__((used, visibility("default")))
HyosStatus monoicon_hyos_status = {};

void Log(int priority, const char* message) {
    __android_log_write(priority, kLogTag, message);
}

void Logf(int priority, const char* format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(priority, kLogTag, format, args);
    va_end(args);
}

// Minimal unsigned-to-decimal, to keep this payload free of stdio and locale.
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

void AppendText(char* buffer, size_t capacity, const char* text) {
    size_t length = 0u;
    while (buffer[length] != '\0' && length < capacity) ++length;
    for (size_t index = 0u; text[index] != '\0' && length + 1u < capacity; ++index) {
        buffer[length++] = text[index];
    }
    buffer[length] = '\0';
}

// Write the status file, preferring the launcher's own files dir (the process
// owns it) and falling back to the adb-visible /data/local/tmp. Failures are
// non-fatal: logcat and the exported struct remain.
void WriteStatusFile(const char* phase) {
    // Full paths are spelled out: these are separate string literals, not one
    // concatenated literal, so adjacent-literal joining does not apply.
    const char* candidates[] = {
        "/data/user/0/com.miui.home/files/monoicon_hyos_init.txt",
        "/data/data/com.miui.home/files/monoicon_hyos_init.txt",
        "/data/local/tmp/monoicon_hyos_init.txt",
    };
    char body[512] = {};
    AppendText(body, sizeof(body), "phase=");
    AppendText(body, sizeof(body), phase);
    AppendText(body, sizeof(body), " magic=0x");
    char hex[16];
    const uint32_t magic = __atomic_load_n(&monoicon_hyos_status.magic, __ATOMIC_ACQUIRE);
    for (int index = 7; index >= 0; --index) {
        const uint32_t nibble = (magic >> (index * 4)) & 0xFu;
        hex[7 - index] = static_cast<char>(nibble < 10 ? '0' + nibble : 'a' + (nibble - 10));
    }
    hex[8] = '\0';
    AppendText(body, sizeof(body), hex);
    const char* fields[] = {
        " pid=",  " api=",     " hook=", " unhook=", " spawner=",
        " child=", " lib=",    " backfill=", " entry=", " dart=",
    };
    const uint32_t values[] = {
        __atomic_load_n(&monoicon_hyos_status.pid, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_status.api_version, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_status.have_hook_func, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_status.have_unhook_func, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_status.in_spawner_process, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_status.in_launcher_child, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_status.launcher_library_seen, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_status.launcher_library_backfilled, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_status.entry_point_resolved, __ATOMIC_ACQUIRE),
        __atomic_load_n(&monoicon_hyos_status.dart_library_seen, __ATOMIC_ACQUIRE),
    };
    for (size_t index = 0u; index < sizeof(values) / sizeof(values[0]); ++index) {
        AppendText(body, sizeof(body), fields[index]);
        AppendUint(body, sizeof(body), values[index]);
    }
    AppendText(body, sizeof(body), "\n");

    for (const char* path : candidates) {
        const int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
        if (fd < 0) continue;
        ssize_t written;
        do {
            written = write(fd, body, std::strlen(body));
        } while (written < 0);
        close(fd);
        if (written > 0) {
            __atomic_store_n(&monoicon_hyos_status.status_file_written, 1u,
                             __ATOMIC_RELEASE);
            return;
        }
    }
}

constexpr size_t ConstLength(const char* value) {
    size_t length = 0u;
    while (value != nullptr && value[length] != '\0') ++length;
    return length;
}

constexpr bool StringsEqual(const char* left, const char* right) {
    if (left == nullptr || right == nullptr) return false;
    size_t index = 0u;
    while (left[index] != '\0' && right[index] != '\0') {
        if (left[index] != right[index]) return false;
        ++index;
    }
    return left[index] == right[index];
}

constexpr bool EndsWith(const char* value, const char* suffix) {
    if (value == nullptr || suffix == nullptr) return false;
    const size_t valueLength = ConstLength(value);
    const size_t suffixLength = ConstLength(suffix);
    if (suffixLength == 0u || suffixLength > valueLength) return false;
    const size_t start = valueLength - suffixLength;
    for (size_t index = 0u; index < suffixLength; ++index) {
        if (value[start + index] != suffix[index]) return false;
    }
    return true;
}

// /proc/self/exe == /system_ext/bin/hyos_spawner identifies the HYOS process
// family. On a current launcher the spawner still reports cmdline `usap64`, so
// the executable identity is what distinguishes it from an ordinary Android
// process.
bool IsHyosSpawnerProcess() {
    char executable[128] = {};
    const ssize_t length = readlink("/proc/self/exe", executable, sizeof(executable) - 1u);
    if (length <= 0 || static_cast<size_t>(length) >= sizeof(executable)) return false;
    executable[length] = '\0';
    return StringsEqual(executable, kSpawnerPath);
}

// Exact cmdline match for the launcher child. A prefix match would also accept
// `com.miui.home:remote`-style names, which are different processes.
bool IsLauncherChildProcess() {
    const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char commandLine[64] = {};
    ssize_t result;
    do {
        result = read(fd, commandLine, sizeof(commandLine));
    } while (result < 0);
    close(fd);
    if (result <= 0) return false;
    constexpr size_t expected = ConstLength(kLauncherProcessName);
    return static_cast<size_t>(result) > expected &&
        commandLine[expected] == '\0' &&
        StringsEqual(commandLine, kLauncherProcessName);
}

// Either half of the family is a valid place to act: `native_init` runs in the
// root spawner, while the launcher child is where the libraries actually load.
bool IsLauncherProcessFamily() {
    return IsHyosSpawnerProcess() || IsLauncherChildProcess();
}

bool IsLauncherLibraryPath(const char* path) {
    if (path == nullptr) return false;
    if (StringsEqual(path, kLauncherLibrary)) return true;
    return EndsWith(path, "/lib/arm64-v8a/libapp_launcher.so");
}

bool IsDartLibraryPath(const char* path) {
    if (path == nullptr) return false;
    return StringsEqual(path, kDartLibrary) || EndsWith(path, "/libapp.so");
}

uint32_t NextSequence() {
    return __atomic_add_fetch(&monoicon_hyos_status.lifecycle_sequence, 1u,
                              __ATOMIC_ACQ_REL);
}

void RefreshProcessIdentity() {
    __atomic_store_n(&monoicon_hyos_status.pid,
                     static_cast<uint32_t>(getpid()), __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_status.in_launcher_child,
                     IsLauncherChildProcess() ? 1u : 0u, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_status.in_spawner_process,
                     IsHyosSpawnerProcess() ? 1u : 0u, __ATOMIC_RELEASE);
}

void OnLibraryLoaded(const char* name, void* handle);

// Lives in hyperos4_hook_backend.cpp. All hooking goes through the LSPosed hook
// primitive; this module never writes machine code itself.
extern "C" void monoicon_hyos_hook_backend_init(const void* entries);
extern "C" void monoicon_hyos_install_canary_hook();
// B-1 loader-timing observation. These only record timestamps; they never load,
// unload, patch or reorder anything.
extern "C" uint32_t monoicon_hyos_observe_libapp_visible();
extern "C" uint32_t monoicon_hyos_observe_libapp_handle();
extern "C" uint32_t monoicon_hyos_observe_libapp_hook();
extern "C" void monoicon_hyos_observe_finalize();

void HandleLauncherLibrary(void* handle) {
    __atomic_fetch_add(&monoicon_hyos_status.launcher_library_seen, 1u,
                       __ATOMIC_RELAXED);
    Logf(ANDROID_LOG_INFO,
         "launcher image loaded (handle=%p, seen=%u) — MonoIcon native code is "
         "running inside the HyperOS 4 launcher",
         handle, __atomic_load_n(&monoicon_hyos_status.launcher_library_seen,
                                 __ATOMIC_ACQUIRE));

    // app_entry_point is resolved as an anchor for later stages. It is deliberately
    // not instrumented: the launcher child is forked from the HYOS spawner, so
    // modifying that page corrupts the shared image and every later child crashes
    // until a reboot. Hooking now goes exclusively through LSPosed's hookFunc.
    void* entryPoint = dlsym(handle, kLauncherEntrySymbol);
    if (entryPoint != nullptr) {
        __atomic_store_n(&monoicon_hyos_status.entry_point_resolved, 1u,
                         __ATOMIC_RELEASE);
        __atomic_store_n(&monoicon_hyos_status.has_app_entry_point, 1u,
                         __ATOMIC_RELEASE);
        Logf(ANDROID_LOG_INFO, "resolved %s at %p (not instrumented)",
             kLauncherEntrySymbol, entryPoint);
    } else {
        Logf(ANDROID_LOG_WARN, "could not resolve %s: %s", kLauncherEntrySymbol,
             dlerror());
    }
    NextSequence();
    WriteStatusFile("launcher-library");
}

void HandleDartLibrary(void* handle) {
    __atomic_fetch_add(&monoicon_hyos_status.dart_library_seen, 1u,
                       __ATOMIC_RELAXED);
    Logf(ANDROID_LOG_INFO, "Dart snapshot image loaded (handle=%p)", handle);
    // B-1 (observation only): libapp.so is now visible and a handle exists. Record
    // both moments, then record when the Dart-image scan actually starts, so the
    // current install point can be compared with the earliest observable moment.
    // Nothing here changes control flow or the hook target.
    monoicon_hyos_observe_libapp_visible();
    if (handle != nullptr) monoicon_hyos_observe_libapp_handle();
    monoicon_hyos_observe_libapp_hook();
    // The canary is armed only once the Dart image exists, because its address is
    // resolved from that image.
    monoicon_hyos_install_canary_hook();
    // Derived deltas are filled in after the hook attempt, when t_dart_base exists.
    monoicon_hyos_observe_finalize();
    NextSequence();
    WriteStatusFile("dart-library");
}

void OnLibraryLoaded(const char* name, void* handle) {
    if (name == nullptr || handle == nullptr) return;
    RefreshProcessIdentity();
    if (!IsLauncherProcessFamily()) return;

    if (IsLauncherLibraryPath(name)) {
        HandleLauncherLibrary(handle);
        return;
    }
    if (IsDartLibraryPath(name)) {
        HandleDartLibrary(handle);
    }
}

// The spawner may dlopen the launcher images before LSPosed calls native_init,
// in which case the callback above never sees them. Replay the small known set
// with RTLD_NOLOAD so nothing new is loaded merely because it is listed.
void BackfillLoadedLibrary(const char* name) {
    if (name == nullptr) return;
    // B-1 (observation only): the earliest moment this payload can observe anything is
    // native_init itself, so check whether libapp.so is already in the loaded-image
    // list before attempting the RTLD_NOLOAD lookup. This does not load anything.
    if (IsDartLibraryPath(name)) {
        monoicon_hyos_observe_libapp_visible();
    }
    void* handle = dlopen(name, RTLD_NOW | RTLD_NOLOAD);
    // Observation only: a successful RTLD_NOLOAD means a handle already existed, so
    // record when that first became true for libapp.so.
    if (handle != nullptr && IsDartLibraryPath(name)) {
        monoicon_hyos_observe_libapp_handle();
    }
    if (handle == nullptr) return;
    Logf(ANDROID_LOG_INFO, "backfilling already-loaded image: %s", name);
    if (IsLauncherLibraryPath(name)) {
        __atomic_store_n(&monoicon_hyos_status.launcher_library_backfilled, 1u,
                         __ATOMIC_RELEASE);
    }
    OnLibraryLoaded(name, handle);
    dlclose(handle);
}

}  // namespace

extern "C" __attribute__((visibility("default"))) NativeOnModuleLoaded
native_init(const NativeAPIEntries* entries) {
    const bool spawner = IsHyosSpawnerProcess();
    const bool launcherChild = IsLauncherChildProcess();

    __atomic_store_n(&monoicon_hyos_status.api_version,
                     entries != nullptr ? entries->version : 0u, __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_status.have_hook_func,
                     entries != nullptr && entries->hookFunc != nullptr ? 1u : 0u,
                     __ATOMIC_RELEASE);
    __atomic_store_n(&monoicon_hyos_status.have_unhook_func,
                     entries != nullptr && entries->unhookFunc != nullptr ? 1u : 0u,
                     __ATOMIC_RELEASE);
    RefreshProcessIdentity();
    __atomic_store_n(&monoicon_hyos_status.magic, kStatusMagic, __ATOMIC_RELEASE);
    NextSequence();

    Logf(ANDROID_LOG_INFO,
         "native_init: api=%u hook=%s unhook=%s hyos_spawner=%s launcher_child=%s pid=%d",
         entries != nullptr ? entries->version : 0u,
         entries != nullptr && entries->hookFunc != nullptr ? "yes" : "no",
         entries != nullptr && entries->unhookFunc != nullptr ? "yes" : "no",
         spawner ? "yes" : "no", launcherChild ? "yes" : "no",
         static_cast<int>(getpid()));

    // Recorded before any rejection path, so the file alone proves the entry ran.
    WriteStatusFile(spawner ? "native_init-spawner" : "native_init");

    // Launch-breaking experiment, opt-in only. With MONOICON_HYOS_EXIT_PROBE=1 in
    // the spawner environment, a launcher that fails to start is direct proof that
    // native_init executed. Unset (the default) this is never taken.
    const char* probe = getenv(kExitProbeEnv);
    if (probe != nullptr && probe[0] == '1') {
        Log(ANDROID_LOG_WARN,
            "MONOICON_HYOS_EXIT_PROBE=1: terminating the HYOS process to prove native_init ran");
        _exit(99);
    }

    // LSPosed only installs the native_init entry for the injections it owns; the
    // executable check keeps this payload inert if it is ever loaded elsewhere.
    if (!spawner && !launcherChild) {
        Log(ANDROID_LOG_WARN,
            "native_init rejected a non-HYOS process; MonoIcon native entry stays inert");
        return nullptr;
    }

    // Hand the LSPosed hook primitive to the backend before anything else can try
    // to use it. Failing here leaves the launcher completely untouched.
    monoicon_hyos_hook_backend_init(entries);

    // B-1 (observation only): record whether libapp.so is already visible at the
    // earliest point this payload runs. Purely a timestamp; nothing is loaded.
    monoicon_hyos_observe_libapp_visible();

    BackfillLoadedLibrary(kLauncherLibrary);
    BackfillLoadedLibrary(kDartLibrary);

    Log(ANDROID_LOG_INFO,
        "MonoIcon native_init armed the HYOS library callback");
    WriteStatusFile("armed");
    return &OnLibraryLoaded;
}
