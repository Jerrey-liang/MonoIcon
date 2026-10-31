#include "hyperos4_geometry_bridge.h"
#include "hyperos4_icon_files.h"
#include "hyperos4_paths.h"

#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstring>

namespace {
// These are native Bundle C ABI values, never pointers into the Dart heap.
// Verified 6144 callers: get_f64 at 0xa5348c (x0 tag, x1 double bits),
// get_f32 at 0xa535a8 (x0 low32 tag, high32 float bits). A zero low tag bit
// indicates a value. Mixed integer/floating structs return in GP registers.
struct DoubleResult { uint64_t tag; double value; };
struct FloatResult { uint32_t tag; float value; };
static_assert(sizeof(DoubleResult) == 16 && offsetof(DoubleResult, value) == 8);
static_assert(sizeof(FloatResult) == 8 && offsetof(FloatResult, value) == 4);
using Invoke = void* (*)(const char*, size_t, void*);
using GetDouble = DoubleResult (*)(void*, const char*, size_t);
using GetFloat = FloatResult (*)(void*, const char*, size_t);
using Drop = void (*)(void*);
struct State { Invoke invoke{}; GetDouble getDouble{}; GetFloat getFloat{}; Drop drop{}; char digest[65]{}; };
State state;
std::atomic<pid_t> started{0};
constexpr char kRequest[] = "monoicon_geometry.request";
constexpr char kResponse[] = "monoicon_geometry.response";

bool ReadRequest(char (&nonce)[33]) {
    char path[256];
    monoicon::hyos::LauncherFilePath(path, sizeof(path), kRequest);
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path,
        O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0));
    if (fd < 0) return false;
    struct stat metadata{};
    char text[257]{};
    ssize_t count = -1;
    if (syscall(SYS_fstat, fd, &metadata) == 0 && S_ISREG(metadata.st_mode) &&
        metadata.st_size > 0 && metadata.st_size <= 256) {
        do { count = read(fd, text, sizeof(text) - 1); } while (count < 0 && errno == EINTR);
    }
    close(fd);
    unsigned version = 0;
    int pid = 0;
    char digest[65]{}, parsedNonce[33]{}, extra = 0;
    if (count != metadata.st_size || count <= 0 || std::memchr(text, '\0', static_cast<size_t>(count)) != nullptr ||
        sscanf(text, "MONOICON_GEOMETRY %u %d %64s %32s %c", &version, &pid, digest, parsedNonce, &extra) != 4 ||
        version != 1 || pid != getpid() || std::strcmp(digest, state.digest) != 0 || std::strlen(parsedNonce) != 32) return false;
    for (char c : parsedNonce) if (c && !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    std::memcpy(nonce, parsedNonce, sizeof(nonce));
    return true;
}

bool Double(void* bundle, const char* name, double& out) {
    const auto result = state.getDouble(bundle, name, std::strlen(name));
    if ((result.tag & 1) || !std::isfinite(result.value) || result.value <= 0 || result.value > 10000) return false;
    out = result.value;
    return true;
}
bool Float(void* bundle, const char* name, double& out) {
    const auto result = state.getFloat(bundle, name, std::strlen(name));
    if ((result.tag & 1) || !std::isfinite(result.value) || result.value <= 0 || result.value > 4) return false;
    out = result.value;
    return true;
}
bool Sample(double (&values)[6]) {
    // The bridge consumes nullable input and returns an owned native Bundle.
    // Every result, including one missing a required key, is dropped once.
    void* width = state.invoke("getCellWidth", 12, nullptr);
    if (!width) return false;
    const bool widthOk = Double(width, "result", values[0]);
    if (!Double(width, "portrait_cell_width", values[2])) values[2] = 0;
    if (!Double(width, "landscape_cell_width", values[3])) values[3] = 0;
    state.drop(width);
    if (!widthOk) return false;
    void* height = state.invoke("getCellHeight", 13, nullptr);
    if (!height) return false;
    const bool heightOk = Double(height, "result", values[1]);
    state.drop(height);
    if (!heightOk) return false;
    void* scales = state.invoke("getIconScales", 13, nullptr);
    if (!scales) return false;
    const bool portraitOk = Float(scales, "portrait_icon_scale", values[4]);
    const bool landscapeOk = Float(scales, "landscape_icon_scale", values[5]);
    if (!portraitOk) values[4] = 0;
    if (!landscapeOk) values[5] = 0;
    state.drop(scales);
    return portraitOk || landscapeOk;
}

bool WriteResponse(const char* nonce, const double (&values)[6]) {
    char output[512];
    const int size = snprintf(output, sizeof(output), "MONOICON_GEOMETRY 1 %d %s %s %.17g %.17g %.17g %.17g %.17g %.17g\n",
        static_cast<int>(getpid()), state.digest, nonce, values[0], values[1], values[2], values[3], values[4], values[5]);
    if (size <= 0 || static_cast<size_t>(size) >= sizeof(output)) return false;
    char stage[256], target[256];
    monoicon::hyos::LauncherFilePath(stage, sizeof(stage), "monoicon_geometry.response.new");
    monoicon::hyos::LauncherFilePath(target, sizeof(target), kResponse);
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, stage,
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600));
    if (fd < 0) return false;
    size_t offset = 0;
    while (offset < static_cast<size_t>(size)) {
        const auto count = write(fd, output + offset, size - offset);
        if (count > 0) offset += static_cast<size_t>(count);
        else if (count < 0 && errno == EINTR) continue;
        else break;
    }
    close(fd);
    if (offset == static_cast<size_t>(size) && rename(stage, target) == 0) return true;
    unlink(stage);
    return false;
}

void* Worker(void*) {
    char completed[33]{};
    while (true) {
        char nonce[33]{};
        if (monoicon_hyos_icons_enabled() && ReadRequest(nonce) && std::strcmp(nonce, completed) != 0) {
            double first[6]{}, second[6]{};
            // Calls can block while Dart starts. This is the single dedicated
            // metric thread, isolated from rendering and palette transactions.
            if (Sample(first) && Sample(second)) {
                bool coherent = true;
                for (size_t i = 0; i < 6; ++i) coherent = coherent && first[i] == second[i];
                char current[33]{};
                if (coherent && monoicon_hyos_icons_enabled() && ReadRequest(current) && std::strcmp(nonce, current) == 0) {
                    if (WriteResponse(nonce, second)) std::memcpy(completed, nonce, sizeof(completed));
                }
            }
        }
        usleep(200000);
    }
}
} // namespace

extern "C" void monoicon_hyos_start_geometry_bridge(uintptr_t invokeAddress, const char* profileDigest) {
    if (!invokeAddress || !profileDigest || std::strlen(profileDigest) != 64 || started.load() == getpid()) return;
    // This C API is an existing DT_NEEDED dependency of libapp_launcher.so.
    // Never load another library to satisfy a missing ABI.
    void* bundle = dlopen("libhyper_os_bundle_public.so", RTLD_NOW | RTLD_NOLOAD);
    if (!bundle) return;
    State next;
    next.invoke = reinterpret_cast<Invoke>(invokeAddress);
    next.getDouble = reinterpret_cast<GetDouble>(dlsym(bundle, "Bundle_get_f64"));
    next.getFloat = reinterpret_cast<GetFloat>(dlsym(bundle, "Bundle_get_f32"));
    next.drop = reinterpret_cast<Drop>(dlsym(bundle, "Bundle_drop"));
    if (!next.getDouble || !next.getFloat || !next.drop) { dlclose(bundle); return; }
    std::memcpy(next.digest, profileDigest, sizeof(next.digest));
    pid_t expected = 0;
    if (!started.compare_exchange_strong(expected, getpid())) { dlclose(bundle); return; }
    state = next;
    pthread_t thread;
    if (pthread_create(&thread, nullptr, Worker, nullptr) == 0) pthread_detach(thread);
    else { started.store(0); dlclose(bundle); }
    // Keep the successful NOLOAD handle pinned for the worker's lifetime.
}
