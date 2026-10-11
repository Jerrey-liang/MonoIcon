// Runtime-only trace probe for the HyperOS 4 launcher's Dart AOT icon pipeline.
//
// Goal: establish WHICH icon-pipeline functions actually execute inside the real
// com.miui.home process, and record each hit's caller link register. This library
// is instrumentation only: it never changes a return value, never allocates or
// touches a Dart object, and never alters the Dart heap. Each probe is a private
// stub (the target's own first four instructions plus a nearby branch), so nodes
// can be armed and disarmed independently.
//
// It also reports whether this process may map executable memory at all, which is
// the precondition for any native inline hook in com.miui.home.
//
// Offsets belong to the launcher build installed on the test device:
//   RELEASE-8.01.02.6144-260818-08251107-R
//   libapp.so GNU build id 4f1bdaedd19666f2b22817f1fabc965f
// Any other build is refused.
//
// Regenerate the table with
//   external_resources/HyperOS4/analysis/runtime_trace/device_prologues.py

#include <jni.h>

#if !defined(__aarch64__)

extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativePreflight(JNIEnv*, jobject) { return -1; }
extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeInstall(JNIEnv*, jobject, jstring) { return -1; }
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeSnapshot(JNIEnv* env, jobject) {
    return env->NewLongArray(0);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeNodeCount(JNIEnv*, jobject) { return 0; }
extern "C" JNIEXPORT jstring JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeNodeIds(JNIEnv* env, jobject) {
    return env->NewStringUTF("");
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeBuildId(JNIEnv* env, jobject) {
    return env->NewStringUTF("");
}

#else  // __aarch64__

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

namespace {

constexpr char kTag[] = "MonoIcon.Trace";
constexpr size_t kPage = 4096;

// Stack frame the stub builds before calling the probe:
//   +0x00 x0 x1 | +0x10 x2 x3 | ... | +0x80 x16 x17 | +0x90 x18 x30 | +0xa0 nzcv
constexpr uint32_t kFrameSize = 0x100;
constexpr uint32_t kFrameLrOff = 0x90;
static_assert(kFrameSize % 16 == 0, "stack pointer must stay 16-byte aligned");

constexpr uint8_t kExpectedBuildId[16] = {
    0x4f, 0x1b, 0xda, 0xed, 0xd1, 0x96, 0x66, 0xf2,
    0xb2, 0x28, 0x17, 0xf1, 0xfa, 0xbc, 0x96, 0x5f,
};

struct Node {
    const char* id;
    uintptr_t vaddr;      // vaddr in libapp.so; equals file offset for this image
    uint32_t stolen[4];   // verified original first four instructions
};

// Bit order equals the trace bit order. Keep in sync with the Kotlin id strings.
constexpr Node kNodes[] = {
    {"cache_lookup",             0x19d79c0, {0xa9bf79fd, 0xaa0f03fd, 0xd10081ef, 0xf81f83b6}},
    {"create_image",             0x19d7d30, {0xa9bf79fd, 0xaa0f03fd, 0xd104e1ef, 0xf81f83b6}},
    {"render_and_cache",         0x19d78c0, {0xa9bf79fd, 0xaa0f03fd, 0xd100a1ef, 0xf81f83b6}},
    {"reload_via_activity_icon", 0x19d84f4, {0xa9bf79fd, 0xaa0f03fd, 0xd101e1ef, 0xf81a03a1}},
    {"apply_style",              0x1845a68, {0xa9bf79fd, 0xaa0f03fd, 0xd10441ef, 0xf81f83b6}},
    {"render_themed",            0x1845e9c, {0xa9bf79fd, 0xaa0f03fd, 0xd106a1ef, 0xf81f83b6}},
    {"get_activity_icon2",       0xe6e2a4,  {0xa9bf79fd, 0xaa0f03fd, 0xd100e1ef, 0xf81f83a1}},
    {"drawable_to_image",        0x1aa90f4, {0xa9bf79fd, 0xaa0f03fd, 0xd10321ef, 0xf81f83b6}},
    {"render_drawable_to_image", 0x1aa9798, {0xa9bf79fd, 0xaa0f03fd, 0xd101c1ef, 0xf81f83b6}},
    {"bitmap_to_image",          0x1845824, {0xa9bf79fd, 0xaa0f03fd, 0xd10241ef, 0xf81f83b6}},
    {"appgrid_load_icon_data",   0x1a0fbc4, {0xa9bf79fd, 0xaa0f03fd, 0xd10161ef, 0xf81f83b6}},
    {"mono_widget_load_icon",    0x19d890c, {0xa9bf79fd, 0xaa0f03fd, 0xd103e1ef, 0xf81f83b6}},
    {"mono_cache_get",           0x12c90bc, {0xa9bf79fd, 0xaa0f03fd, 0xd10061ef, 0xaa0203e0}},
    {"mono_cache_put",           0x12c8f6c, {0xa9bf79fd, 0xaa0f03fd, 0xd10081ef, 0xaa0203e0}},
    {"mono_load_settings",       0x194ad28, {0xa9bf79fd, 0xaa0f03fd, 0xd102c1ef, 0xf81f83b6}},
    {"mono_is_supported",        0x1a04848, {0xa9bf79fd, 0xaa0f03fd, 0xd10181ef, 0xf81f83b6}},
    {"icon_cache_v2_key",        0xa35a80,  {0xa9bf79fd, 0xaa0f03fd, 0xd100a1ef, 0xf81f83b6}},
};
constexpr size_t kNodeCount = sizeof(kNodes) / sizeof(kNodes[0]);

// Stub layout, one page per node:
//   0x000 entry   (target branches here)
//   0x060 index constant: movz x0 / movk x0
//   0x078 adrp/add x17 -> probe          (patched)
//   0x088 blr x17
//   0x08c adrp/add x17 -> this page data (patched)
//   0x09c ldr x16, [x17, #8]             (continuation address)
//   0x0a0..0xc4 restore x0..x18, x30     (ldp x2i, x2i+1, [sp, #i*0x10])
//   0x0c8 ldr x17, [sp, #0xa0] ; msr nzcv, x17
//   0x0d0 stolen[4]                      (copy of the original entry)
//   0x0e0 add sp, sp, #0x100
//   0x0e4 br x16
//   0x100 data: probe(8), continuation(8), index(8)
constexpr uint32_t kDataOff = 0x100;
constexpr uint32_t kStubBytes = 0xf0;

constexpr uint32_t kMovz = 0x060;
constexpr uint32_t kMovk = 0x064;
constexpr uint32_t kProbeAddr = 0x078;
constexpr uint32_t kBlrProbe = 0x088;
constexpr uint32_t kContAddr = 0x08c;
constexpr uint32_t kLdrCont = 0x09c;
constexpr uint32_t kRestoreStart = 0x0a0;
constexpr uint32_t kRestoreNzcv = 0x0c8;
constexpr uint32_t kStolenOff = 0x0d0;
constexpr uint32_t kAddSp = 0x0e0;
constexpr uint32_t kBrCont = 0x0e4;

// ldp x2i, x2i+1, [sp, #i*0x10] for i in 0..8, and ldp x18, x30, [sp, #0x90] for
// the final slot (the frame keeps x30 next to x18). opc=10, V=0, L=1, imm7=bits21:15,
// Rt2=bits14:10, Rn=31 (sp), Rt=bits4:0. Verified against a real assembler by
// verify_stub_encoding.py and simulate_stub.py.
constexpr uint32_t ldpPair(uint32_t frameOffset) {
    const uint32_t imm7 = (frameOffset / 8) & 0x7fu;
    const uint32_t first = (frameOffset / 0x10) * 2;
    const uint32_t second = frameOffset == 0x90 ? 30u : first + 1;
    return 0xa9400000u | (imm7 << 15) | (second << 10) | (31u << 5) | first;
}

alignas(64) std::atomic<uint64_t> gHits[kNodeCount];
alignas(64) std::atomic<uint64_t> gLastCaller[kNodeCount];
alignas(64) std::atomic<uint64_t> gLastTid[kNodeCount];
std::atomic<uint32_t> gArmedMask{0};
std::atomic<bool> gInstalled{false};
std::string gBuildIdHex = "<unread>";

void logLine(const std::string& text) {
    __android_log_write(ANDROID_LOG_INFO, kTag, text.c_str());
}

// Probe body. It runs mid-Dart with every register live, so it must not call
// anything that can allocate, log, or touch the Dart heap: relaxed atomics and
// one gettid syscall only.
extern "C" __attribute__((noinline)) void monoiconTraceProbe(uint64_t index, const uint64_t* frame) {
    if (index < kNodeCount) {
        gHits[index].fetch_add(1, std::memory_order_relaxed);
        gLastCaller[index].store(frame[kFrameLrOff / 8], std::memory_order_relaxed);
        gLastTid[index].store(static_cast<uint64_t>(::syscall(__NR_gettid)),
                              std::memory_order_relaxed);
    }
}

uint32_t* wordAt(uint8_t* page, uint32_t byteOffset) {
    return reinterpret_cast<uint32_t*>(page + byteOffset);
}

void emit(uint8_t* page, uint32_t byteOffset, uint32_t instruction) {
    *wordAt(page, byteOffset) = instruction;
}

// adrp x17, #target ; add x17, x17, #(target & 0xfff)
void emitAddressOf(uint8_t* page, uint32_t byteOffset, const void* target) {
    const uintptr_t pc = reinterpret_cast<uintptr_t>(page + byteOffset);
    const uintptr_t address = reinterpret_cast<uintptr_t>(target);
    const int64_t delta = static_cast<int64_t>(address >> 12) - static_cast<int64_t>(pc >> 12);
    const uint32_t immlo = static_cast<uint32_t>(delta) & 0x3u;
    const uint32_t immhi = (static_cast<uint32_t>(delta) >> 2) & 0x7ffffu;
    emit(page, byteOffset, 0x90000011u | (immlo << 29) | (immhi << 5));  // adrp x17, ...
    emit(page, byteOffset + 4,
         0x91000231u | ((static_cast<uint32_t>(address) & 0xfffu) << 10));  // add x17, x17, #lo12
}

void buildStub(uint8_t* page, uint32_t index, const uint32_t stolen[4]) {
    for (uint32_t offset = 0; offset < kStubBytes; offset += 4) {
        emit(page, offset, 0xd503201fu);  // nop; patched below
    }
    emit(page, 0x000, 0xa9b007e0);  // stp x0,  x1,  [sp, #-0x100]!
    emit(page, 0x004, 0xa9010fe2);  // stp x2,  x3,  [sp, #0x10]
    emit(page, 0x00c, 0xa90217e4);  // stp x4,  x5,  [sp, #0x20]
    emit(page, 0x014, 0xa9031fe6);  // stp x6,  x7,  [sp, #0x30]
    emit(page, 0x01c, 0xa90427e8);  // stp x8,  x9,  [sp, #0x40]
    emit(page, 0x024, 0xa9052fea);  // stp x10, x11, [sp, #0x50]
    emit(page, 0x02c, 0xa90637ec);  // stp x12, x13, [sp, #0x60]
    emit(page, 0x034, 0xa9073fee);  // stp x14, x15, [sp, #0x70]
    emit(page, 0x03c, 0xa90847f0);  // stp x16, x17, [sp, #0x80]
    emit(page, 0x044, 0xa9097bf2);  // stp x18, x30, [sp, #0x90]
    emit(page, 0x04c, 0xd53b4211);  // mrs x17, nzcv
    emit(page, 0x050, 0xf90053f1);  // str x17, [sp, #0xa0]
    emit(page, kMovz, 0xd2800000u | ((index & 0xffffu) << 5));           // movz x0, #lo
    emit(page, kMovk, 0xf2a00000u | (((index >> 16) & 0xffffu) << 5));   // movk x0, #hi, lsl 16
    emitAddressOf(page, kProbeAddr,
                  reinterpret_cast<const void*>(&monoiconTraceProbe));  // adrp/add x17 -> probe
    emit(page, kBlrProbe, 0xd63f0220);                                   // blr x17
    emitAddressOf(page, kContAddr, nullptr);                             // adrp/add x17 (patched)
    emit(page, kLdrCont, 0xf9400620u | (8u << 10));                      // ldr x16, [x17, #8]

    // Restore x0..x18 and x30 from the frame (10 pairs, one word each).
    for (uint32_t i = 0; i < 10; ++i) {
        emit(page, kRestoreStart + i * 4, ldpPair(i * 0x10));
    }
    emit(page, kRestoreNzcv, 0xf94053f1);      // ldr x17, [sp, #0xa0]
    emit(page, kRestoreNzcv + 4, 0xd51b4211);  // msr nzcv, x17

    std::memcpy(page + kStolenOff, stolen, 4 * sizeof(uint32_t));
    emit(page, kAddSp, 0x910403ff);   // add sp, sp, #0x100
    emit(page, kBrCont, 0xd61f0200);  // br x16
}

struct LibraryMatch {
    uintptr_t bias = 0;
    std::string path;
};

int findLibrary(dl_phdr_info* info, size_t, void* data) {
    auto* match = static_cast<LibraryMatch*>(data);
    if (info->dlpi_name != nullptr && std::strstr(info->dlpi_name, "libapp.so") != nullptr) {
        match->bias = static_cast<uintptr_t>(info->dlpi_addr);
        match->path = info->dlpi_name;
        return 1;
    }
    return 0;
}

void* allocateExecutable() {
    void* memory = mmap(nullptr, kPage, PROT_READ | PROT_WRITE | PROT_EXEC,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    return memory == MAP_FAILED ? nullptr : memory;
}

bool readBuildId(int fd, uint64_t noteOffset, uint8_t out[16]) {
    uint8_t note[32];
    if (pread(fd, note, sizeof(note), static_cast<off_t>(noteOffset)) !=
        static_cast<ssize_t>(sizeof(note))) {
        return false;
    }
    if (note[0] != 4 || note[4] != 16 || note[8] != 3) return false;
    if (std::memcmp(note + 12, "GNU", 4) != 0) return false;
    std::memcpy(out, note + 16, 16);
    return true;
}

std::string toHex(const uint8_t* bytes, size_t count) {
    static const char* digits = "0123456789abcdef";
    std::string text;
    text.reserve(count * 2);
    for (size_t i = 0; i < count; ++i) {
        text.push_back(digits[bytes[i] >> 4]);
        text.push_back(digits[bytes[i] & 0xf]);
    }
    return text;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativePreflight(JNIEnv*, jobject) {
    LibraryMatch match;
    dl_iterate_phdr(findLibrary, &match);
    if (match.path.empty()) return -1;
    void* memory = allocateExecutable();
    if (memory == nullptr) return -2;
    munmap(memory, kPage);
    return 1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeBuildId(JNIEnv* env, jobject) {
    return env->NewStringUTF(gBuildIdHex.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeInstall(
    JNIEnv* env, jobject, jstring apkPath) {
    if (gInstalled.load()) return 1;

    LibraryMatch match;
    dl_iterate_phdr(findLibrary, &match);
    if (match.path.empty()) {
        logLine("install: libapp.so is not mapped in this process");
        return -1;
    }

    uint8_t buildId[16] = {0};
    const char* path = env->GetStringUTFChars(apkPath, nullptr);
    int fd = path == nullptr ? -1 : open(path, O_RDONLY | O_CLOEXEC);
    if (path != nullptr) env->ReleaseStringUTFChars(apkPath, path);
    if (fd < 0) {
        logLine("install: cannot open the launcher apk");
        return -2;
    }
    const bool idOk = readBuildId(fd, 0x1c8, buildId);
    close(fd);
    gBuildIdHex = toHex(buildId, 16);
    if (!idOk || std::memcmp(buildId, kExpectedBuildId, 16) != 0) {
        logLine("install: refusing unknown libapp.so build id " + gBuildIdHex);
        return -3;
    }

    size_t installed = 0;
    size_t prologueMismatch = 0;
    size_t noExecMemory = 0;
    size_t mprotectFailed = 0;
    uint32_t armed = 0;

    for (size_t i = 0; i < kNodeCount; ++i) {
        const Node& node = kNodes[i];
        auto* target = reinterpret_cast<uint32_t*>(match.bias + node.vaddr);

        if (std::memcmp(target, node.stolen, sizeof(node.stolen)) != 0) {
            ++prologueMismatch;
            continue;
        }

        auto* page = static_cast<uint8_t*>(allocateExecutable());
        if (page == nullptr) {
            ++noExecMemory;
            continue;
        }
        buildStub(page, static_cast<uint32_t>(i), node.stolen);

        // Per-node data: probe address, continuation address, node index.
        auto* data = page + kDataOff;
        void* continuation = reinterpret_cast<void*>(target + 4);
        auto probeAddress = reinterpret_cast<const void*>(&monoiconTraceProbe);
        std::memcpy(data + 0x00, &probeAddress, sizeof(void*));
        std::memcpy(data + 0x08, &continuation, sizeof(void*));
        std::memcpy(data + 0x10, &i, sizeof(uint64_t));

        emitAddressOf(page, kContAddr, data);
        // ldr x16, [x17, #8]  ->  x16 = continuation
        emit(page, kLdrCont, 0xf9400620u | (8u << 10));
        // ldr x17, [sp, #0xa0] ; msr nzcv, x17
        emit(page, kRestoreNzcv, 0xf94053f1);
        emit(page, kRestoreNzcv + 4, 0xd51b4211);

        // Branch at the entry: B stub.
        uint32_t branch;
        {
            const intptr_t off = (reinterpret_cast<intptr_t>(page) -
                                  reinterpret_cast<intptr_t>(target)) >> 2;
            if (off < -(1 << 25) || off >= (1 << 25)) {
                munmap(page, kPage);
                continue;
            }
            branch = 0x14000000u | (static_cast<uint32_t>(off) & 0x03ffffffu);
        }

        void* pageBase = reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(target) & ~(kPage - 1));
        if (mprotect(pageBase, kPage, PROT_READ | PROT_WRITE) != 0) {
            ++mprotectFailed;
            munmap(page, kPage);
            continue;
        }
        target[0] = branch;
        __builtin___clear_cache(reinterpret_cast<char*>(target),
                                reinterpret_cast<char*>(target) + 4);
        if (mprotect(pageBase, kPage, PROT_READ | PROT_EXEC) != 0) {
            target[0] = node.stolen[0];
            __builtin___clear_cache(reinterpret_cast<char*>(target),
                                    reinterpret_cast<char*>(target) + 4);
            ++mprotectFailed;
            munmap(page, kPage);
            continue;
        }
        __builtin___clear_cache(reinterpret_cast<char*>(page),
                                reinterpret_cast<char*>(page) + kStubBytes);

        armed |= (1u << i);
        ++installed;
    }

    gArmedMask.store(armed);
    gInstalled.store(installed != 0);

    char summary[320];
    std::snprintf(summary, sizeof(summary),
                  "install: buildId=%s nodes=%zu installed=%zu mismatch=%zu noExec=%zu "
                  "mprotectFail=%zu armed=0x%08x bias=0x%zx",
                  gBuildIdHex.c_str(), kNodeCount, installed, prologueMismatch,
                  noExecMemory, mprotectFailed, armed, match.bias);
    logLine(summary);
    return static_cast<jint>(installed);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeSnapshot(JNIEnv* env, jobject) {
    constexpr size_t kFields = 3;
    jlong values[kNodeCount * kFields];
    for (size_t i = 0; i < kNodeCount; ++i) {
        values[i * kFields + 0] = static_cast<jlong>(gHits[i].load());
        values[i * kFields + 1] = static_cast<jlong>(gLastCaller[i].load());
        values[i * kFields + 2] = static_cast<jlong>(gLastTid[i].load());
    }
    jlongArray array = env->NewLongArray(static_cast<jsize>(kNodeCount * kFields));
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, static_cast<jsize>(kNodeCount * kFields), values);
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeNodeCount(JNIEnv*, jobject) {
    return static_cast<jint>(kNodeCount);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4TraceProbe_nativeNodeIds(JNIEnv* env, jobject) {
    std::string ids;
    for (size_t i = 0; i < kNodeCount; ++i) {
        if (i != 0) ids.push_back(',');
        ids += kNodes[i].id;
    }
    return env->NewStringUTF(ids.c_str());
}

#endif  // __aarch64__
