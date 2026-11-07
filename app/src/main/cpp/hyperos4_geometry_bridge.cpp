#include "hyperos4_geometry_bridge.h"
#include "hyperos4_icon_files.h"
#include "hyperos4_paths.h"
#include "hyperos4_profile_runtime.h"

#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
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
// The Android Bundle C exports belong to schema, not the package-management
// library named bundle. Verified on the device and against the launcher imports.
constexpr char kBundleLibrary[] = "libhyper_os_schema_public.so";

struct BundleImage {
    char path[1024]{};
    uintptr_t bias{};
    unsigned count{};
};

BundleImage FindBundleImage() {
    BundleImage image;
    dl_iterate_phdr([](dl_phdr_info* info, size_t, void* data) {
        if (!info->dlpi_name) return 0;
        const char* name = std::strrchr(info->dlpi_name, '/');
        name = name ? name + 1 : info->dlpi_name;
        if (std::strcmp(name, kBundleLibrary) != 0) return 0;
        auto& found = *static_cast<BundleImage*>(data);
        ++found.count;
        const size_t length = strnlen(info->dlpi_name, sizeof(found.path));
        if (length == sizeof(found.path)) return 0;
        std::memcpy(found.path, info->dlpi_name, length + 1);
        found.bias = info->dlpi_addr;
        return 0;
    }, &image);
    return image;
}

bool SymbolInBundle(void* symbol, const BundleImage& image, const char* name) {
    Dl_info owner{};
    return symbol && dladdr(symbol, &owner) != 0 &&
        reinterpret_cast<uintptr_t>(owner.dli_fbase) == image.bias &&
        owner.dli_saddr == symbol && owner.dli_sname && std::strcmp(owner.dli_sname, name) == 0;
}

// Keep a bounded, read-only diagnostic alongside the request. A missing reply
// must distinguish an unavailable native ABI from a call waiting for Dart.
// Startup and the worker never write concurrently: started prevents a second
// installer from entering after ownership passes to the worker.
void WriteStatus(const char* phase, const char* digest, const char* detail = "") {
    const int savedErrno = errno;
    char path[256], output[768];
    monoicon::hyos::LauncherFilePath(path, sizeof(path), "monoicon_geometry.status");
    const int size = snprintf(output, sizeof(output), "phase=%s pid=%d digest=%s detail=%.480s\n",
        phase, static_cast<int>(getpid()), digest, detail ? detail : "");
    if (size > 0 && static_cast<size_t>(size) < sizeof(output)) {
        // Linker errors can contain line breaks; one status is one line.
        for (int i = 0; i < size - 1; ++i) if (output[i] == '\n' || output[i] == '\r') output[i] = ' ';
        const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path,
            O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600));
        if (fd >= 0) {
            size_t offset = 0;
            while (offset < static_cast<size_t>(size)) {
                const auto count = write(fd, output + offset, size - offset);
                if (count > 0) offset += static_cast<size_t>(count);
                else if (count < 0 && errno == EINTR) continue;
                else break;
            }
            close(fd);
        }
    }
    errno = savedErrno;
}

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
bool Sample(double (&values)[6], bool trace) {
    auto status = [&](const char* phase) { if (trace) WriteStatus(phase, state.digest); };
    // The bridge consumes nullable input and returns an owned native Bundle.
    // Every result, including one missing a required key, is dropped once.
    status("invoking-width");
    void* width = state.invoke("getCellWidth", 12, nullptr);
    if (!width) { status("width-bundle-unavailable"); return false; }
    status("reading-width");
    const bool widthOk = Double(width, "result", values[0]);
    if (!Double(width, "portrait_cell_width", values[2])) values[2] = 0;
    if (!Double(width, "landscape_cell_width", values[3])) values[3] = 0;
    state.drop(width);
    if (!widthOk) { status("width-result-unavailable"); return false; }
    status("invoking-height");
    void* height = state.invoke("getCellHeight", 13, nullptr);
    if (!height) { status("height-bundle-unavailable"); return false; }
    status("reading-height");
    const bool heightOk = Double(height, "result", values[1]);
    state.drop(height);
    if (!heightOk) { status("height-result-unavailable"); return false; }
    status("invoking-scales");
    void* scales = state.invoke("getIconScales", 13, nullptr);
    if (!scales) { status("scales-bundle-unavailable"); return false; }
    status("reading-scales");
    const bool portraitOk = Float(scales, "portrait_icon_scale", values[4]);
    const bool landscapeOk = Float(scales, "landscape_icon_scale", values[5]);
    if (!portraitOk) values[4] = 0;
    if (!landscapeOk) values[5] = 0;
    state.drop(scales);
    if (!portraitOk && !landscapeOk) status("scale-results-unavailable");
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
    char completed[33]{}, observed[33]{};
    WriteStatus("waiting-request", state.digest);
    while (true) {
        char nonce[33]{};
        if (monoicon_hyos_icons_enabled() && ReadRequest(nonce) && std::strcmp(nonce, completed) != 0) {
            // Trace only the first attempt for each nonce, so an unavailable
            // handler does not rewrite the status file every 200 milliseconds.
            const bool trace = std::strcmp(nonce, observed) != 0;
            if (trace) std::memcpy(observed, nonce, sizeof(observed));
            double first[6]{}, second[6]{};
            // Calls can block while Dart starts. This is the single dedicated
            // metric thread, isolated from rendering and palette transactions.
            if (Sample(first, trace) && Sample(second, trace)) {
                bool coherent = true;
                for (size_t i = 0; i < 6; ++i) coherent = coherent && first[i] == second[i];
                char current[33]{};
                if (coherent && monoicon_hyos_icons_enabled() && ReadRequest(current) && std::strcmp(nonce, current) == 0) {
                    if (WriteResponse(nonce, second)) {
                        std::memcpy(completed, nonce, sizeof(completed));
                        char metrics[256];
                        snprintf(metrics, sizeof(metrics), "nonce=%s values=%.17g,%.17g,%.17g,%.17g,%.17g,%.17g",
                            nonce, second[0], second[1], second[2], second[3], second[4], second[5]);
                        WriteStatus("response-written", state.digest, metrics);
                    } else if (trace) WriteStatus("response-write-failed", state.digest);
                } else if (trace) {
                    WriteStatus(coherent ? "request-changed" : "metrics-changing", state.digest);
                }
            }
        }
        usleep(200000);
    }
}
} // namespace

extern "C" void monoicon_hyos_start_geometry_bridge(monoicon::profile::Runtime* runtime,
        const monoicon::profile::Profile* profile, const char* profileDigest) {
    if (!runtime || !profile || !profileDigest || std::strlen(profileDigest) != 64 ||
        started.load() == getpid() || profile->icons.geometryBridge >= profile->functions.size()) return;
    const auto& bridge = profile->functions[profile->icons.geometryBridge];
    if (bridge.image >= runtime->images.size()) return;
    // Reuse the launcher's bound imports rather than opening a library in the
    // injected module's different linker namespace. Their slots were found by
    // name in the fully hashed launcher ELF; reads use the safe runtime reader.
    // The verified launcher's DT_NEEDED keeps this ABI loaded for its lifetime.
    const auto image = FindBundleImage();
    if (image.count != 1 || !image.path[0]) {
        static bool reported = false;
        if (!reported) { WriteStatus("bundle-image-unavailable", profileDigest); reported = true; }
        return;
    }
    std::array<uintptr_t, 3> imports{};
    if (!runtime->geometryImports(bridge.image, imports)) {
        static bool reported = false;
        if (!reported) { WriteStatus("bundle-imports-unavailable", profileDigest); reported = true; }
        return;
    }
    State next;
    next.invoke = reinterpret_cast<Invoke>(runtime->images[bridge.image].bias + bridge.address);
    void* getDouble = reinterpret_cast<void*>(imports[0]);
    void* getFloat = reinterpret_cast<void*>(imports[1]);
    void* drop = reinterpret_cast<void*>(imports[2]);
    next.getDouble = SymbolInBundle(getDouble, image, "Bundle_get_f64") ? reinterpret_cast<GetDouble>(getDouble) : nullptr;
    next.getFloat = SymbolInBundle(getFloat, image, "Bundle_get_f32") ? reinterpret_cast<GetFloat>(getFloat) : nullptr;
    next.drop = SymbolInBundle(drop, image, "Bundle_drop") ? reinterpret_cast<Drop>(drop) : nullptr;
    if (!next.getDouble || !next.getFloat || !next.drop) {
        static bool reported = false;
        if (!reported) {
            char missing[96];
            snprintf(missing, sizeof(missing), "Bundle_get_f64=%d Bundle_get_f32=%d Bundle_drop=%d",
                next.getDouble != nullptr, next.getFloat != nullptr, next.drop != nullptr);
            WriteStatus("bundle-symbols-unavailable", profileDigest, missing);
            reported = true;
        }
        return;
    }
    std::memcpy(next.digest, profileDigest, sizeof(next.digest));
    pid_t expected = 0;
    if (!started.compare_exchange_strong(expected, getpid())) return;
    state = next;
    pthread_t thread;
    WriteStatus("worker-starting", state.digest);
    const int result = pthread_create(&thread, nullptr, Worker, nullptr);
    if (result == 0) pthread_detach(thread);
    else {
        char error[32];
        snprintf(error, sizeof(error), "pthread_create=%d", result);
        WriteStatus("worker-start-failed", state.digest, error);
        started.store(0);
    }
}
