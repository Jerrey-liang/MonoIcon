#include <jni.h>

#if defined(__aarch64__)
#include "hyperos4_thread_pause.h"
#include "hyperos4_code_page.h"
#include "hyperos4_observer_code.h"
#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits.h>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr char kTag[] = "MonoIcon.HyperOS4";
constexpr char kEntry[] = "lib/arm64-v8a/libapp.so";
enum Status : jint {
    kWaiting = 0, kInstalled = 1, kUnsupported = -1, kInvalidInput = -2,
    kIdentityMismatch = -3, kMapsUnavailable = -4, kTimedOut = -5,
    kProtectionDenied = -6, kCodeMismatch = -7, kWorkerFailed = -8,
    kRecoveryFailed = -9, kThreadPauseUnavailable = -10, kPaletteInUse = -11,
    kCodePageUnavailable = -12,
};

// Reuse P3's transaction for a scalar palette override. No Dart function
// pointers, foreign frames, heap writes or object/GC bridge.
// Evidence: external_resources/HyperOS4/report/CUSTOM_PALETTE_INJECTION.md and
// external_resources/HyperOS4/analysis/port6144/ (6144 hook-point port).
// All addresses below belong to the launcher build on the test device:
//   RELEASE-8.01.02.6144-260818-08251107-R
//   libapp.so GNU build id 4f1bdaedd19666f2b22817f1fabc965f
//   libapp_launcher.so Android note r27d/13750724 (no GNU build id)
struct Patch {
    uintptr_t vaddr;
    uint32_t original;
    uint32_t replacement;
    uintptr_t function;
    size_t functionSize;
};
constexpr std::array<Patch, 9> kPatches{{
    {0x1a04944, 0x36200261, 0x14000013, 0x1a04848, 516},
    {0x194adc4, 0x9a910204, 0xaa1003e4, 0x194ad28, 624},
    // Settings' background value: replace only the integer unboxing. Keep the
    // original normalization, Dart integer allocation, Rx setter and renderer.
    {0x194ae24, 0x93417c61, 0x00000000, 0x194ad28, 624},
    {0x194ae28, 0x36000043, 0x00000000, 0x194ad28, 624},
    {0x194ae2c, 0xf8407061, 0xd503201f, 0x194ad28, 624},
    // Existing ColorFilter mode conversion: a scalar-only veneer selects the
    // exact pooled foreground filter. See FOREGROUND_INJECTION.md.
    {0x8cefb8, 0xf85d83a2, 0x00000000, 0x8ceef0, 256},
    // A notified settings reload must refresh existing icons even when its
    // forced background/enable values compare equal to the previous values.
    {0x194a79c, 0x540002e0, 0xd503201f, 0x194a6f0, 356},
    // A refreshed widget must reload its existing image state even when its
    // monochrome/size arguments compare equal after late palette installation.
    {0x12c8f4c, 0x54000040, 0xd503201f, 0x12c8e14, 344},
    // Per-icon monochrome decision: system apps lack a monochrome icon layer,
    // so _hasMonochromeLayer returns false and they keep the coloured icon.
    // Replace the layer query with the Dart boolean true (add x0, x22, #0x20)
    // so every icon takes the launcher's own monochrome rendering path.
    {0xe2abdc, 0x97fa1ff6, 0x910082c0, 0xe2ab68, 132},
}};
constexpr std::array<Patch, 1> kObserverPatches{{
    {0x5aefec, 0x17ffff3d, 0x00000000, 0x5ae5b0, 2784},
}};
using PatchSet = std::array<Patch, kPatches.size()>;

PatchSet makePatches(uint32_t background, uint32_t foregroundBranch) {
    auto patches = kPatches;
    // W writes zero-extend the exact packed ARGB value into unboxed x1.
    patches[2].replacement = 0x52800001u | ((background & 0xffffu) << 5); // movz w1
    patches[3].replacement = 0x72a00001u | ((background >> 16) << 5);     // movk w1, lsl #16
    patches[5].replacement = foregroundBranch;
    return patches;
}

std::array<uint32_t, 9> foregroundCode(uint32_t foreground, uint32_t continuation) {
    // X0/X3/NZCV are overwritten by the original continuation before any read.
    // Keep X1, X15/X29/X30 and every other register. No call, allocation, stack
    // change or Dart heap write; X2 remains the original unboxed ARGB argument.
    // The pool pair loads the 6144 canonical foreground ColorFilter slot
    // (x27 + 0x859f8); see analysis/port6144/elf_facts_6144.json.
    return {{
        0xf85d83a2, 0xf85f03a3, 0x91421760, 0xf944fc00,
        0xeb00007f, 0x54000061,
        0x52800002u | ((foreground & 0xffffu) << 5),
        0x72a00002u | ((foreground >> 16) << 5),
        continuation,
    }};
}
constexpr std::array<uint8_t, 32> kBuildNote{{
    4, 0, 0, 0, 16, 0, 0, 0, 3, 0, 0, 0, 'G', 'N', 'U', 0,
    0x4f, 0x1b, 0xda, 0xed, 0xd1, 0x96, 0x66, 0xf2,
    0xb2, 0x28, 0x17, 0xf1, 0xfa, 0xbc, 0x96, 0x5f,
}};
constexpr uintptr_t kNoteVaddr = 0x1c8;
constexpr uintptr_t kTextVaddr = 0x810000;
constexpr uintptr_t kTextSize = 0x12a9b80;
constexpr size_t kElfSize = 28700808;
namespace observer = monoicon::hyperos4::observer;
struct Segment {
    uint64_t offset, address, fileSize, memorySize;
    uint32_t flags;
    uint64_t alignment;
};
constexpr Segment kAppSegments[] = {
    {0, 0, 0x805f12, 0x805f12, PF_R, 0x10000},
    {kTextVaddr, kTextVaddr, kTextSize, kTextSize, PF_R | PF_X, 0x10000},
    {0x1ac0000, 0x1ac0000, 0x90, 0x90, PF_R | PF_W, 0x10000},
};
constexpr Segment kLauncherSegments[] = {
    {0, 0, 0x4be4e8, 0x4be4e8, PF_R, 0x4000},
    {0x4be500, 0x4c2500, 0x8a36b0, 0x8a36b0, PF_R | PF_X, 0x4000},
    {0xd61bc0, 0xd69bc0, 0xfc4d8, 0xfd440, PF_R | PF_W, 0x4000},
    {0xe5e100, 0xe6a100, 0xffc0, 0x15a20, PF_R | PF_W, 0x4000},
};
// libapp_launcher has no GNU build ID. Its Android note is only a layout
// guard: identity additionally requires every original observer-function byte.
constexpr std::array<uint8_t, 32> kLauncherNote{{
    0x08,0,0,0, 0x84,0,0,0, 0x01,0,0,0, 'A','n','d','r',
    'o','i','d',0, 0x23,0,0,0, 'r','2','7','d',0,0,0,0,
}};
struct ImageSpec {
    const char* library;
    const char* entry;
    size_t fileSize, noteOffset, headerSize;
    const uint8_t* note;
    size_t noteSize;
    const Segment* segments;
    size_t segmentCount;
    const Patch* patches;
    size_t patchCount;
    const uint8_t* originalFunction;
};
constexpr ImageSpec kAppImage{
    "libapp.so", kEntry, kElfSize, kNoteVaddr, 0x1e8,
    kBuildNote.data(), kBuildNote.size(), kAppSegments, 3,
    kPatches.data(), kPatches.size(), nullptr,
};
constexpr ImageSpec kLauncherImage{
    "libapp_launcher.so", "lib/arm64-v8a/libapp_launcher.so", 15505576, 0x270, 0x290,
    kLauncherNote.data(), kLauncherNote.size(), kLauncherSegments, 4,
    kObserverPatches.data(), kObserverPatches.size(), observer::kOriginalFunction.data(),
};
constexpr size_t kTransactionPatchCount = kPatches.size() + kObserverPatches.size();
std::atomic<jint> gStatus{kWaiting};
std::atomic<bool> gStarted{false};
// Both generated LDAXR/STLXR and JNI's __atomic_load_n operate on this exact
// aligned scalar. No std::atomic object-representation cast is needed.
alignas(4) uint32_t gObserverGeneration = 0;

struct File {
    int fd = -1;
    explicit File(int value = -1) : fd(value) {}
    ~File() { if (fd >= 0) close(fd); }
    File(const File&) = delete;
    File& operator=(const File&) = delete;
};

bool readAt(int fd, uint64_t offset, void* output, size_t size) {
    auto* bytes = static_cast<uint8_t*>(output);
    if (offset > INT64_MAX || size > static_cast<uint64_t>(INT64_MAX) - offset) return false;
    while (size != 0) {
        const ssize_t n = pread(fd, bytes, size, static_cast<off_t>(offset));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        bytes += n;
        offset += n;
        size -= n;
    }
    return true;
}

uint16_t u16(const uint8_t* p) { return p[0] | (uint16_t(p[1]) << 8); }
uint32_t u32(const uint8_t* p) { return u16(p) | (uint32_t(u16(p + 2)) << 16); }

// Resolve the stored entry through the central directory, never a signature scan
// through arbitrary APK contents. ZIP64/compressed entries are deliberately not
// mapped candidates; an extracted nativeLibraryDir copy can still be supported.
bool apkEntryOffset(int fd, uint64_t fileSize, const ImageSpec& spec, uint64_t& entryOffset) {
    const size_t entryNameSize = strlen(spec.entry);
    if (fileSize < 22) return false;
    std::vector<uint8_t> tail(std::min<uint64_t>(fileSize, 65535 + 22));
    if (!readAt(fd, fileSize - tail.size(), tail.data(), tail.size())) return false;
    size_t eocd = tail.size();
    for (size_t pos = tail.size() - 22;; --pos) {
        if (u32(&tail[pos]) == 0x06054b50 && pos + 22 + u16(&tail[pos + 20]) == tail.size()) {
            eocd = pos;
            break;
        }
        if (pos == 0) break;
    }
    if (eocd == tail.size()) return false;
    const auto* e = &tail[eocd];
    const uint16_t count = u16(e + 10);
    const uint64_t cdSize = u32(e + 12), cdOffset = u32(e + 16);
    const uint64_t eocdOffset = fileSize - tail.size() + eocd;
    if (u16(e + 4) != 0 || u16(e + 6) != 0 || u16(e + 8) != count ||
        count == 0xffff || cdSize > 16 * 1024 * 1024 || cdOffset > eocdOffset ||
        cdSize > eocdOffset - cdOffset) return false;
    std::vector<uint8_t> directory(cdSize);
    if (!readAt(fd, cdOffset, directory.data(), directory.size())) return false;
    bool found = false;
    size_t pos = 0;
    for (uint32_t i = 0; i < count; ++i) {
        if (pos > directory.size() || directory.size() - pos < 46) return false;
        const auto* c = &directory[pos];
        if (u32(c) != 0x02014b50) return false;
        const size_t nameSize = u16(c + 28);
        const size_t recordSize = 46 + nameSize + u16(c + 30) + u16(c + 32);
        if (recordSize > directory.size() - pos) return false;
        if (nameSize == entryNameSize && memcmp(c + 46, spec.entry, nameSize) == 0) {
            if (found || u16(c + 10) != 0 || (u16(c + 8) & 1) != 0 ||
                u16(c + 34) != 0 || u32(c + 20) != spec.fileSize || u32(c + 24) != spec.fileSize) return false;
            std::array<uint8_t, 30> local{};
            const uint64_t localOffset = u32(c + 42);
            if (localOffset >= cdOffset || !readAt(fd, localOffset, local.data(), local.size()) ||
                u32(local.data()) != 0x04034b50 || u16(local.data() + 8) != 0 ||
                u16(local.data() + 6) != u16(c + 8) || u16(local.data() + 26) != nameSize) return false;
            std::vector<char> name(entryNameSize);
            if (!readAt(fd, localOffset + 30, name.data(), name.size()) ||
                memcmp(name.data(), spec.entry, name.size()) != 0) return false;
            entryOffset = localOffset + 30 + nameSize + u16(local.data() + 28);
            if (entryOffset > cdOffset || spec.fileSize > cdOffset - entryOffset) return false;
            found = true;
        }
        pos += recordSize;
    }
    return found && pos == directory.size();
}

struct Backing {
    const ImageSpec* spec{};
    std::string path;
    dev_t device{};
    ino_t inode{};
    uint64_t offset{};
    std::vector<uint8_t> header;
    std::vector<std::vector<uint8_t>> functions;
};

bool fileOffset(const ImageSpec& spec, uintptr_t address, size_t size, uint64_t& offset) {
    for (size_t i = 0; i < spec.segmentCount; ++i) {
        const auto& segment = spec.segments[i];
        if (address < segment.address || address - segment.address > segment.fileSize ||
            size > segment.fileSize - (address - segment.address)) continue;
        offset = segment.offset + address - segment.address;
        return true;
    }
    return false;
}

bool validateElf(int fd, Backing& b) {
    const auto& spec = *b.spec;
    b.header.resize(spec.headerSize);
    if (!readAt(fd, b.offset, b.header.data(), b.header.size())) return false;
    Elf64_Ehdr e{};
    memcpy(&e, b.header.data(), sizeof(e));
    if (memcmp(e.e_ident, ELFMAG, SELFMAG) != 0 || e.e_ident[EI_CLASS] != ELFCLASS64 ||
        e.e_ident[EI_DATA] != ELFDATA2LSB || e.e_ident[EI_VERSION] != EV_CURRENT ||
        e.e_machine != EM_AARCH64 || e.e_type != ET_DYN || e.e_version != EV_CURRENT ||
        e.e_entry != 0 || e.e_ehsize != sizeof(e) || e.e_phentsize != sizeof(Elf64_Phdr) ||
        e.e_phnum == 0 || e.e_phnum > 16 || e.e_phoff > spec.noteOffset ||
        e.e_phnum * sizeof(Elf64_Phdr) > spec.noteOffset - e.e_phoff ||
        memcmp(b.header.data() + spec.noteOffset, spec.note, spec.noteSize) != 0) return false;
    size_t loadCount = 0;
    for (size_t i = 0; i < e.e_phnum; ++i) {
        Elf64_Phdr p{};
        memcpy(&p, b.header.data() + e.e_phoff + i * sizeof(p), sizeof(p));
        if (p.p_type != PT_LOAD) continue;
        if (loadCount >= spec.segmentCount) return false;
        const auto& s = spec.segments[loadCount++];
        if (p.p_vaddr != s.address || p.p_offset != s.offset || p.p_filesz != s.fileSize ||
            p.p_memsz != s.memorySize || p.p_flags != s.flags || p.p_align != s.alignment) return false;
    }
    if (loadCount != spec.segmentCount) return false;
    b.functions.resize(spec.patchCount);
    for (size_t i = 0; i < spec.patchCount; ++i) {
        const auto& p = spec.patches[i];
        auto& code = b.functions[i];
        code.resize(p.functionSize);
        uint64_t offset;
        if (!fileOffset(spec, p.function, code.size(), offset) ||
            !readAt(fd, b.offset + offset, code.data(), code.size()) ||
            u32(code.data() + p.vaddr - p.function) != p.original) return false;
        if (spec.originalFunction != nullptr &&
            memcmp(code.data(), spec.originalFunction, code.size()) != 0) return false;
    }
    if (&spec != &kAppImage) return true;
    const auto& settings = b.functions[1];
    const size_t at = kPatches[1].vaddr - kPatches[1].function;
    return u32(settings.data() + at - 8) == 0x910082d0 &&
           u32(settings.data() + at - 4) == 0x9100c2d1;
}

bool addBacking(const std::string& path, bool apk, const ImageSpec& spec,
                size_t pageSize, std::vector<Backing>& backings) {
    char resolved[PATH_MAX];
    if (path.empty() || realpath(path.c_str(), resolved) == nullptr) return false;
    File file(open(resolved, O_RDONLY | O_CLOEXEC));
    struct stat st{};
    if (file.fd < 0 || fstat(file.fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) return false;
    Backing b;
    b.spec = &spec;
    b.path = resolved;
    b.device = st.st_dev;
    b.inode = st.st_ino;
    if (apk) {
        if (!apkEntryOffset(file.fd, st.st_size, spec, b.offset) || b.offset % pageSize != 0) return false;
    } else if (st.st_size != static_cast<off_t>(spec.fileSize)) {
        return false;
    }
    if (!validateElf(file.fd, b)) return false;
    backings.push_back(std::move(b));
    return true;
}

struct Mapping {
    uintptr_t start{}, end{};
    uint64_t offset{}, inode{};
    unsigned deviceMajor{}, deviceMinor{};
    std::string path;
    int protection{};
    bool privateMapping{};
};

bool readMaps(std::vector<Mapping>& maps) {
    std::unique_ptr<FILE, decltype(&fclose)> input(fopen("/proc/self/maps", "re"), fclose);
    if (!input) return false;
    // Fixed-size lines bound parser input. Overlong paths
    // are skipped instead of truncated into a different, apparently valid path.
    char line[PATH_MAX + 256];
    while (fgets(line, sizeof(line), input.get()) != nullptr) {
        if (strchr(line, '\n') == nullptr) {
            int c;
            while ((c = fgetc(input.get())) != '\n' && c != EOF) {}
            continue;
        }
        unsigned long long start, end, offset, inode;
        unsigned deviceMajor, deviceMinor;
        char permissions[5]{};
        int consumed = 0;
        if (sscanf(line, "%llx-%llx %4s %llx %x:%x %llu %n", &start, &end,
                   permissions, &offset, &deviceMajor, &deviceMinor, &inode, &consumed) != 7 ||
            start >= end) continue;
        char* path = line + consumed;
        path[strcspn(path, "\n")] = '\0';
        Mapping m;
        m.start = start; m.end = end; m.offset = offset; m.inode = inode;
        m.deviceMajor = deviceMajor; m.deviceMinor = deviceMinor; m.path = path;
        m.protection = (permissions[0] == 'r' ? PROT_READ : 0) |
                       (permissions[1] == 'w' ? PROT_WRITE : 0) |
                       (permissions[2] == 'x' ? PROT_EXEC : 0);
        m.privateMapping = permissions[3] == 'p';
        maps.push_back(std::move(m));
    }
    return ferror(input.get()) == 0;
}

void* prepareForeground(const std::vector<Mapping>& maps, uintptr_t bias,
                        size_t pageSize, uint32_t foreground, uint32_t& branch) {
    const uintptr_t from = bias + kPatches[5].vaddr;
    constexpr size_t codeSize = 9 * sizeof(uint32_t);
    constexpr uintptr_t reach = 0x07ff0000;
    const uintptr_t lower = from > reach ? from - reach : pageSize;
    const uintptr_t upper = from < UINTPTR_MAX - reach ? from + reach : UINTPTR_MAX - pageSize;
    uintptr_t previous = lower;
    // Reuse the discovery snapshot only to choose hints. mmap and branch range
    // checks validate the actual page; no MAP_FIXED or executable image cave.
    for (size_t i = 0; i <= maps.size(); ++i) {
        const uintptr_t gapEnd = i < maps.size() ? std::min(maps[i].start, upper) : upper;
        const uintptr_t start = (previous + pageSize - 1) & ~(pageSize - 1);
        if (start < gapEnd && pageSize <= gapEnd - start) {
            void* page = hyperos4::codePageHint(start, from, pageSize, codeSize);
            if (page != nullptr) {
                const uintptr_t address = reinterpret_cast<uintptr_t>(page);
                uint32_t continuation;
                const bool reachable = hyperos4::branchInstruction(from, address, branch) &&
                    hyperos4::branchInstruction(address + codeSize - 4, from + 4, continuation);
                const auto code = foregroundCode(foreground, reachable ? continuation : 0);
                if (reachable && hyperos4::sealCodePage(page, pageSize, code.data(), sizeof(code))) return page;
                munmap(page, pageSize);
            }
        }
        if (i == maps.size() || maps[i].end >= upper) break;
        previous = std::max(previous, maps[i].end);
    }
    return nullptr;
}

void* prepareObserver(const std::vector<Mapping>& maps, uintptr_t bias,
                      size_t pageSize, uint32_t& branch) {
    const uintptr_t from = bias + observer::kPatchVaddr;
    constexpr uintptr_t reach = 0x07ff0000;
    const uintptr_t lower = from > reach ? from - reach : pageSize;
    const uintptr_t upper = from < UINTPTR_MAX - reach ? from + reach : UINTPTR_MAX - pageSize;
    uintptr_t previous = lower;
    for (size_t i = 0; i <= maps.size(); ++i) {
        const uintptr_t gapEnd = i < maps.size() ? std::min(maps[i].start, upper) : upper;
        const uintptr_t start = (previous + pageSize - 1) & ~(pageSize - 1);
        if (start < gapEnd && pageSize <= gapEnd - start) {
            // The final executable instruction is at +52; +56 holds a literal.
            void* page = hyperos4::codePageHint(start, from, pageSize, 56);
            if (page != nullptr) {
                const uintptr_t address = reinterpret_cast<uintptr_t>(page);
                std::array<uint8_t, observer::kVeneerSize> code{};
                if (observer::encodeBranch(from, address, branch) &&
                    observer::emitVeneer(code.data(), code.size(), address,
                                         bias + observer::kContinuationVaddr, &gObserverGeneration) &&
                    hyperos4::sealCodePage(page, pageSize, code.data(), code.size())) return page;
                munmap(page, pageSize);
            }
        }
        if (i == maps.size() || maps[i].end >= upper) break;
        previous = std::max(previous, maps[i].end);
    }
    return nullptr;
}

bool sameBacking(const Mapping& m, const Backing& b) {
    return m.privateMapping && m.path == b.path && m.inode == b.inode &&
           m.deviceMajor == major(b.device) && m.deviceMinor == minor(b.device);
}

bool mappedRange(const std::vector<Mapping>& maps, const Backing& b, uintptr_t bias,
                 uintptr_t vaddr, size_t size, int protection) {
    if (bias > UINTPTR_MAX - vaddr || size > UINTPTR_MAX - (bias + vaddr)) return false;
    uint64_t expectedOffset;
    if (!fileOffset(*b.spec, vaddr, size, expectedOffset)) return false;
    uintptr_t current = bias + vaddr;
    const uintptr_t first = current;
    const uintptr_t end = current + size;
    for (const auto& m : maps) {
        if (m.end <= current) continue;
        if (m.start > current) return false;
        if (!sameBacking(m, b) || m.protection != protection || m.offset < b.offset ||
            m.offset - b.offset > UINTPTR_MAX - (current - m.start) ||
            m.offset - b.offset + current - m.start != expectedOffset + current - first) return false;
        current = std::min(m.end, end);
        if (current == end) return true;
    }
    return false;
}

bool mappedImage(const std::vector<Mapping>& maps, const Backing& b, uintptr_t bias, size_t pageSize) {
    if (!mappedRange(maps, b, bias, 0, b.header.size(), PROT_READ)) return false;
    for (size_t i = 0; i < b.spec->patchCount; ++i) {
        const auto& p = b.spec->patches[i];
        if (!mappedRange(maps, b, bias, p.function, p.functionSize, PROT_READ | PROT_EXEC) ||
            !mappedRange(maps, b, bias, p.vaddr & ~(pageSize - 1), pageSize, PROT_READ | PROT_EXEC)) return false;
    }
    return true;
}

bool number(const char*& p, const char* end, unsigned base, uint64_t& value) {
    while (p < end && *p == ' ') ++p;
    const char* first = p;
    value = 0;
    while (p < end) {
        const char c = *p;
        const unsigned digit = c >= '0' && c <= '9' ? c - '0' :
                               c >= 'a' && c <= 'f' ? c - 'a' + 10 : base;
        if (digit >= base) break;
        if (value > (UINT64_MAX - digit) / base) return false;
        value = value * base + digit;
        ++p;
    }
    return p != first;
}

// No allocations or stdio/locale locks: revalidate backing, offsets and page
// permissions inside the pause, closing the race with the discovery snapshot.
bool frozenImage(const Backing& b, uintptr_t bias, size_t pageSize, char* buffer, size_t capacity) {
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC, 0));
    if (fd < 0) return false;
    size_t used = 0;
    bool complete = false;
    while (used < capacity) {
        const long count = syscall(SYS_read, fd, buffer + used, capacity - used);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) break;
        if (count == 0) { complete = true; break; }
        used += count;
    }
    syscall(SYS_close, fd);
    if (!complete) return false;
    struct Range { uintptr_t current, end; int protection; uint64_t fileOffset; };
    Range ranges[1 + kPatches.size() * 2]{{bias, bias + b.header.size(), PROT_READ, 0}};
    for (size_t i = 0; i < b.spec->patchCount; ++i) {
        const auto& patch = b.spec->patches[i];
        uint64_t functionOffset, pageOffset;
        if (!fileOffset(*b.spec, patch.function, patch.functionSize, functionOffset) ||
            !fileOffset(*b.spec, patch.vaddr & ~(pageSize - 1), pageSize, pageOffset)) return false;
        ranges[1 + i * 2] = {bias + patch.function, bias + patch.function + patch.functionSize,
                            PROT_READ | PROT_EXEC, functionOffset};
        const uintptr_t page = (bias + patch.vaddr) & ~(pageSize - 1);
        ranges[2 + i * 2] = {page, page + pageSize, PROT_READ | PROT_EXEC, pageOffset};
    }
    const char* line = buffer;
    const char* end = buffer + used;
    while (line < end) {
        const char* next = line;
        while (next < end && *next != '\n') ++next;
        if (next == end) return false;
        const char* p = line;
        uint64_t start, stop, offset, deviceMajor, deviceMinor, inode;
        if (!number(p, next, 16, start) || p == next || *p++ != '-' || !number(p, next, 16, stop) || start >= stop) return false;
        while (p < next && *p == ' ') ++p;
        if (next - p < 5) return false;
        const int protection = (p[0] == 'r' ? PROT_READ : 0) | (p[1] == 'w' ? PROT_WRITE : 0) |
                               (p[2] == 'x' ? PROT_EXEC : 0);
        const bool privateMapping = p[3] == 'p';
        p += 4;
        if (!number(p, next, 16, offset) || !number(p, next, 16, deviceMajor) || p == next || *p++ != ':' ||
            !number(p, next, 16, deviceMinor) || !number(p, next, 10, inode)) return false;
        while (p < next && *p == ' ') ++p;
        for (auto& range : ranges) {
            if (range.current == range.end || stop <= range.current) continue;
            if (start > range.current || !privateMapping || protection != range.protection ||
                inode != b.inode || deviceMajor != major(b.device) || deviceMinor != minor(b.device) ||
                static_cast<size_t>(next - p) != b.path.size() || memcmp(p, b.path.data(), b.path.size()) != 0 ||
                offset < b.offset || offset - b.offset > UINT64_MAX - (range.current - start) ||
                offset - b.offset + range.current - start != range.fileOffset) return false;
            const uintptr_t nextCurrent = std::min<uintptr_t>(stop, range.end);
            range.fileOffset += nextCurrent - range.current;
            range.current = nextCurrent;
        }
        line = next + 1;
    }
    for (const auto& range : ranges) if (range.current != range.end) return false;
    return true;
}

bool matchesMemory(int mem, uintptr_t address, const uint8_t* expected, size_t size) {
    // pread fails on an unmapped range instead of dereferencing a stale scan.
    std::array<uint8_t, 1024> bytes{};
    while (size != 0) {
        const size_t count = std::min(size, bytes.size());
        if (!readAt(mem, address, bytes.data(), count) || memcmp(bytes.data(), expected, count) != 0) return false;
        address += count;
        expected += count;
        size -= count;
    }
    return true;
}

bool protect(uintptr_t page, size_t pageSize, int mode) {
    int result;
    do { result = mprotect(reinterpret_cast<void*>(page), pageSize, mode); }
    while (result != 0 && errno == EINTR);
    return result == 0;
}

void writeInstruction(uintptr_t address, uint32_t instruction) {
    // One naturally aligned AArch64 instruction, never a torn memcpy/trampoline.
    __atomic_store_n(reinterpret_cast<uint32_t*>(address), instruction, __ATOMIC_RELEASE);
    auto* first = reinterpret_cast<char*>(address);
    __builtin___clear_cache(first, first + sizeof(instruction));
}

struct SelectedImage {
    const Backing* backing{};
    uintptr_t bias{};
};
using SelectedImages = std::array<SelectedImage, 2>;

jint install(const SelectedImages& images, size_t pageSize, int mem,
             const PatchSet& appPatches, uint32_t observerBranch) {
    std::vector<Mapping> maps;
    if (!readMaps(maps)) return kMapsUnavailable;
    for (const auto& image : images) {
        if (image.backing == nullptr || !mappedImage(maps, *image.backing, image.bias, pageSize)) return kMapsUnavailable;
    }
    struct RuntimePatch { uintptr_t address; uint32_t original, replacement; };
    std::array<RuntimePatch, kTransactionPatchCount> patches{};
    for (size_t i = 0; i < appPatches.size(); ++i) {
        const auto& patch = appPatches[i];
        patches[i] = {images[0].bias + patch.vaddr, patch.original, patch.replacement};
    }
    patches[appPatches.size()] = {images[1].bias + kObserverPatches[0].vaddr,
                                  kObserverPatches[0].original, observerBranch};
    std::vector<char> frozenMaps(2 * 1024 * 1024);
    // Both images use this one rendezvous. Do not reacquire/reuse ThreadPause
    // slots in separate per-image transactions.
    hyperos4::ThreadPause pause;
    if (!pause.acquire()) return kThreadPauseUnavailable;
    // A peer must not resume midway through the MOVZ/MOVK sequence with an
    // old partial value. Reject active settings code; never edit saved registers.
    if (!pause.excludes(images[0].bias + kPatches[2].function,
                        images[0].bias + kPatches[2].function + kPatches[2].functionSize)) {
        return pause.finish() ? kPaletteInUse : kRecoveryFailed;
    }
    // From here through restoration/rollback: no allocation, log calls or libc
    // operations that can wait on locks held by the parked peers. Re-read the
    // complete functions after parking, before the first protection change.
    for (const auto& image : images) {
        const auto& b = *image.backing;
        if (!frozenImage(b, image.bias, pageSize, frozenMaps.data(), frozenMaps.size())) {
            return pause.finish() ? kMapsUnavailable : kRecoveryFailed;
        }
        if (!matchesMemory(mem, image.bias, b.header.data(), b.header.size())) {
            return pause.finish() ? kIdentityMismatch : kRecoveryFailed;
        }
        for (size_t i = 0; i < b.spec->patchCount; ++i) {
            if (!matchesMemory(mem, image.bias + b.spec->patches[i].function,
                               b.functions[i].data(), b.functions[i].size())) {
                return pause.finish() ? kCodeMismatch : kRecoveryFailed;
            }
        }
    }
    std::array<uintptr_t, kTransactionPatchCount> pages{};
    std::array<bool, kTransactionPatchCount> writable{};
    size_t pageCount = 0;
    for (const auto& p : patches) {
        const uintptr_t page = p.address & ~(pageSize - 1);
        bool present = false;
        for (size_t i = 0; i < pageCount; ++i) present |= pages[i] == page;
        if (!present) pages[pageCount++] = page;
    }
    for (size_t i = 0; i < pageCount; ++i) {
        // Never circumvent a device's W^X/SELinux denial with a remap.
        if (!protect(pages[i], pageSize, PROT_READ | PROT_WRITE | PROT_EXEC)) {
            bool restored = true;
            for (size_t j = 0; j < i; ++j) restored &= protect(pages[j], pageSize, PROT_READ | PROT_EXEC);
            return pause.finish() && restored ? kProtectionDenied : kRecoveryFailed;
        }
        writable[i] = true;
    }
    for (const auto& p : patches) writeInstruction(p.address, p.replacement);
    bool restored = true;
    for (size_t i = 0; i < pageCount; ++i) {
        writable[i] = !protect(pages[i], pageSize, PROT_READ | PROT_EXEC);
        restored &= !writable[i];
    }
    bool verified = restored;
    for (const auto& p : patches) {
        uint32_t actual = 0;
        verified &= readAt(mem, p.address, &actual, sizeof(actual)) && actual == p.replacement;
    }
    // Every successful mprotect restored RX. With all peers parked there is no
    // concurrent unmap/protection change; do not allocate to reparse maps here.
    if (verified) {
        return pause.finish() ? kInstalled : kRecoveryFailed;
    }
    // Best-effort rollback is explicit. An unrecoverable protection/write state
    // is never described as installed or as an untouched original process.
    bool recovered = true;
    for (size_t i = 0; i < pageCount; ++i) {
        if (!writable[i]) writable[i] = protect(pages[i], pageSize, PROT_READ | PROT_WRITE | PROT_EXEC);
        for (const auto& p : patches) {
            if ((p.address & ~(pageSize - 1)) != pages[i]) continue;
            if (writable[i]) writeInstruction(p.address, p.original);
            else recovered = false;
        }
        recovered &= protect(pages[i], pageSize, PROT_READ | PROT_EXEC);
    }
    for (const auto& p : patches) {
        uint32_t actual = 0;
        recovered &= readAt(mem, p.address, &actual, sizeof(actual)) && actual == p.original;
    }
    return pause.finish() && recovered ? kProtectionDenied : kRecoveryFailed;
}

jint run(const std::string& nativeDir, const std::string& apkPath, uint32_t background, uint32_t foreground) {
    const long size = sysconf(_SC_PAGESIZE);
    if (size <= 0 || size > 65536 || (size & (size - 1)) != 0) return kUnsupported;
    const size_t pageSize = size;
    std::vector<Backing> backings;
    for (const auto* spec : {&kAppImage, &kLauncherImage}) {
        bool found = false;
        if (!nativeDir.empty()) found |= addBacking(nativeDir + "/" + spec->library, false, *spec, pageSize, backings);
        found |= addBacking(apkPath, true, *spec, pageSize, backings);
        if (!found) return kIdentityMismatch;
    }
    File mem(open("/proc/self/mem", O_RDONLY | O_CLOEXEC));
    if (mem.fd < 0) return kMapsUnavailable;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    do {
        std::vector<Mapping> maps;
        if (!readMaps(maps)) return kMapsUnavailable;
        SelectedImages selected{};
        for (const auto& b : backings) {
            for (const auto& m : maps) {
                if (!sameBacking(m, b) || m.protection != (PROT_READ | PROT_EXEC) ||
                    m.offset < b.offset) continue;
                const uint64_t relative = m.offset - b.offset;
                for (size_t i = 0; i < b.spec->segmentCount; ++i) {
                    const auto& segment = b.spec->segments[i];
                    if ((segment.flags & PF_X) == 0) continue;
                    const uint64_t first = segment.offset & ~(pageSize - 1);
                    const uint64_t end = (segment.offset + segment.fileSize + pageSize - 1) & ~(pageSize - 1);
                    if (relative < first || relative >= end) continue;
                    // Translate a file-backed mapping through its PT_LOAD.
                    // libapp_launcher RX vaddr is file offset + 0x4000.
                    const uint64_t address = relative < segment.offset ?
                        segment.address - (segment.offset - relative) :
                        segment.address + (relative - segment.offset);
                    if (address > m.start) continue;
                    const uintptr_t bias = m.start - address;
                    if (bias % pageSize != 0 || !mappedImage(maps, b, bias, pageSize)) continue;
                    auto& image = selected[b.spec == &kAppImage ? 0 : 1];
                    if (image.backing != nullptr && (image.backing != &b || image.bias != bias)) return kIdentityMismatch;
                    image = {&b, bias};
                }
            }
        }
        if (selected[0].backing != nullptr && selected[1].backing != nullptr) {
            uint32_t foregroundBranch = 0, observerBranch = 0;
            void* foregroundPage = prepareForeground(maps, selected[0].bias, pageSize, foreground, foregroundBranch);
            if (foregroundPage == nullptr) return kCodePageUnavailable;
            // The first allocation changes available gaps. Refresh only this
            // discovery snapshot before choosing the second non-replacing hint.
            std::vector<Mapping> observerMaps;
            if (!readMaps(observerMaps)) {
                munmap(foregroundPage, pageSize);
                return kMapsUnavailable;
            }
            void* observerPage = prepareObserver(observerMaps, selected[1].bias, pageSize, observerBranch);
            if (observerPage == nullptr) {
                // Neither source branch has been published yet.
                munmap(foregroundPage, pageSize);
                return kCodePageUnavailable;
            }
            const auto patches = makePatches(background, foregroundBranch);
            // Once branch publication is attempted, keep both RX pages for the
            // process lifetime, including incomplete rollback/peer release.
            const jint result = install(selected, pageSize, mem.fd, patches, observerBranch);
            if (result == kInstalled) {
                __android_log_print(ANDROID_LOG_INFO, kTag,
                    "Custom ARGB background=%08x foreground=%08x and P3 installed at load_bias=%p; rendering RUNTIME UNVERIFIED",
                    background, foreground, reinterpret_cast<void*>(selected[0].bias));
            }
            return result;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    } while (std::chrono::steady_clock::now() < deadline);
    return kTimedOut;
}

bool launcherProcess() {
    File file(open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC));
    char name[64]{};
    const ssize_t size = file.fd >= 0 ? read(file.fd, name, sizeof(name)) : -1;
    constexpr char expected[] = "com.miui.home";
    return size >= static_cast<ssize_t>(sizeof(expected)) && memcmp(name, expected, sizeof(expected)) == 0;
}

bool javaString(JNIEnv* env, jstring input, std::string& output) {
    if (input == nullptr) return false;
    const jsize length = env->GetStringUTFLength(input);
    if (length < 0 || length >= PATH_MAX) return false;
    const char* value = env->GetStringUTFChars(input, nullptr);
    if (value == nullptr) return false;
    try {
        output.assign(value, static_cast<size_t>(length));
    } catch (...) {
        env->ReleaseStringUTFChars(input, value);
        throw;
    }
    env->ReleaseStringUTFChars(input, value);
    return true;
}
} // namespace
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4NativeHooks_nativeConfigure(
        JNIEnv* env, jobject, jstring nativeDir, jstring apkPath, jint background, jint foreground) {
#if defined(__aarch64__)
    if (!launcherProcess()) return kUnsupported;
    try {
        std::string directory, apk;
        if (!javaString(env, nativeDir, directory) || !javaString(env, apkPath, apk) ||
            apk.empty() || apk.front() != '/' || (!directory.empty() && directory.front() != '/')) return kInvalidInput;
        if (gStarted.exchange(true)) return gStatus.load();
        std::thread([directory, apk, background, foreground] {
            jint result;
            try { result = run(directory, apk, static_cast<uint32_t>(background), static_cast<uint32_t>(foreground)); }
            catch (...) { result = kWorkerFailed; }
            gStatus.store(result);
            if (result != kInstalled) {
                __android_log_print(ANDROID_LOG_WARN, kTag, "Palette installation stopped: status=%d%s", result,
                    result == kRecoveryFailed ? "; recovery incomplete, launcher restart required" : "");
            }
        }).detach();
        return gStatus.load();
    } catch (...) {
        gStatus.store(kWorkerFailed);
        return kWorkerFailed;
    }
#else
    (void)env; (void)nativeDir; (void)apkPath; (void)background; (void)foreground;
    return -1; // Other ABIs retain the legacy Java module; no native patching.
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4NativeHooks_nativeStatus(JNIEnv*, jobject) {
#if defined(__aarch64__)
    return gStatus.load();
#else
    return -1;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jerrey_monoicon_hook_HyperOs4NativeHooks_nativeReplayGeneration(JNIEnv*, jobject) {
#if defined(__aarch64__)
    return static_cast<jlong>(__atomic_load_n(&gObserverGeneration, __ATOMIC_ACQUIRE));
#else
    return -1;
#endif
}
