#include "hyperos4_profile_runtime.h"
#include "profile_sha256.h"
#include "hyperos4_paths.h"
#include "hyperos4_elf_imports.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/system_properties.h>
#include <unistd.h>

namespace monoicon::profile {
namespace {
struct File { int fd; explicit File(int value) : fd(value) {} ~File() { if (fd >= 0) close(fd); } };
std::string SystemProperty(const char* name) {
    std::string result;
    if (const auto* property = __system_property_find(name)) {
        // Read-only build fingerprints may exceed the legacy PROP_VALUE_MAX.
        __system_property_read_callback(property, [](void* cookie, const char*, const char* value, uint32_t) {
            *static_cast<std::string*>(cookie) = value;
        }, &result);
    }
    return result;
}
bool ReadAt(int fd, uint64_t offset, void* output, size_t size) {
    if (offset > INT64_MAX || size > INT64_MAX - offset) return false;
    auto* p = static_cast<uint8_t*>(output);
    while (size) {
        const auto n = syscall(SYS_pread64, fd, p, size, offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n; offset += n; size -= n;
    }
    return true;
}
uint16_t U16(const uint8_t* p) { return p[0] | (uint16_t(p[1]) << 8); }
uint32_t U32(const uint8_t* p) { return U16(p) | (uint32_t(U16(p + 2)) << 16); }

// Stored APK entry lookup retained from the former JNI installer. A compressed
// entry cannot back an mmap; extracted libraries are supported separately.
bool ApkOffset(int fd, uint64_t fileSize, const Image& spec, uint64_t& entryOffset) {
    const std::string entry = "lib/arm64-v8a/" + spec.library;
    const size_t entryNameSize = entry.size();
    if (fileSize < 22) return false;
    std::vector<uint8_t> tail(std::min<uint64_t>(fileSize, 65535 + 22));
    if (!ReadAt(fd, fileSize - tail.size(), tail.data(), tail.size())) return false;
    size_t eocd = tail.size();
    for (size_t pos = tail.size() - 22;; --pos) {
        if (U32(&tail[pos]) == 0x06054b50 && pos + 22 + U16(&tail[pos + 20]) == tail.size()) { eocd = pos; break; }
        if (pos == 0) break;
    }
    if (eocd == tail.size()) return false;
    const auto* e = &tail[eocd];
    const uint16_t count = U16(e + 10);
    const uint64_t cdSize = U32(e + 12), cdOffset = U32(e + 16), eocdOffset = fileSize - tail.size() + eocd;
    if (U16(e + 4) || U16(e + 6) || U16(e + 8) != count || count == 0xffff ||
        cdSize > 16 * 1024 * 1024 || cdOffset > eocdOffset || cdSize > eocdOffset - cdOffset) return false;
    std::vector<uint8_t> directory(cdSize);
    if (!ReadAt(fd, cdOffset, directory.data(), directory.size())) return false;
    bool found = false; size_t pos = 0;
    for (uint32_t i = 0; i < count; ++i) {
        if (pos > directory.size() || directory.size() - pos < 46) return false;
        const auto* c = &directory[pos];
        if (U32(c) != 0x02014b50) return false;
        const size_t nameSize = U16(c + 28), recordSize = 46 + nameSize + U16(c + 30) + U16(c + 32);
        if (recordSize > directory.size() - pos) return false;
        if (nameSize == entryNameSize && memcmp(c + 46, entry.data(), nameSize) == 0) {
            if (found || U16(c + 10) || (U16(c + 8) & 1) || U16(c + 34) || U32(c + 20) != spec.fileSize || U32(c + 24) != spec.fileSize) return false;
            std::array<uint8_t, 30> local{}; const uint64_t localOffset = U32(c + 42);
            if (localOffset >= cdOffset || !ReadAt(fd, localOffset, local.data(), local.size()) || U32(local.data()) != 0x04034b50 ||
                U16(local.data() + 8) || U16(local.data() + 6) != U16(c + 8) || U16(local.data() + 26) != nameSize) return false;
            std::vector<char> name(entryNameSize);
            if (!ReadAt(fd, localOffset + 30, name.data(), name.size()) || memcmp(name.data(), entry.data(), name.size())) return false;
            entryOffset = localOffset + 30 + nameSize + U16(local.data() + 28);
            if (entryOffset > cdOffset || spec.fileSize > cdOffset - entryOffset) return false;
            found = true;
        }
        pos += recordSize;
    }
    return found && pos == directory.size();
}
bool DiskImage(const Image& spec, MappedImage& image, std::string& error, bool geometry) {
    const size_t bang = image.path.find('!');
    const bool apk = bang != std::string::npos;
    if (apk) {
        if (image.path.substr(bang) != "!/lib/arm64-v8a/" + spec.library) { error = "unexpected APK library entry"; return false; }
        image.path.resize(bang);
    }
    File input(open(image.path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    struct stat st{};
    if (input.fd < 0 || fstat(input.fd, &st) || !S_ISREG(st.st_mode) || st.st_size < 0) { error = "cannot open mapped ELF backing"; return false; }
    if (apk ? !ApkOffset(input.fd, st.st_size, spec, image.fileOffset) : uint64_t(st.st_size) != spec.fileSize) { error = "ELF size or APK entry mismatch"; return false; }
    image.inode = st.st_ino; image.device = st.st_dev;
    Sha256 hash; uint8_t block[65536];
    for (uint64_t pos = 0; pos < spec.fileSize;) {
        const size_t count = std::min<uint64_t>(sizeof(block), spec.fileSize - pos);
        if (!ReadAt(input.fd, image.fileOffset + pos, block, count)) { error = "ELF read failed"; return false; }
        hash.update(block, count); pos += count;
    }
    if (hash.finish() != spec.sha256) { error = "ELF SHA-256 mismatch: " + spec.library; return false; }
    Elf64_Ehdr eh{};
    if (!ReadAt(input.fd, image.fileOffset, &eh, sizeof(eh)) || memcmp(eh.e_ident, ELFMAG, SELFMAG) ||
        eh.e_ident[EI_CLASS] != ELFCLASS64 || eh.e_ident[EI_DATA] != ELFDATA2LSB || eh.e_ident[EI_VERSION] != EV_CURRENT ||
        eh.e_machine != EM_AARCH64 || eh.e_type != ET_DYN || eh.e_version != EV_CURRENT || eh.e_ehsize != sizeof(eh) ||
        eh.e_phentsize != sizeof(Elf64_Phdr) || !eh.e_phnum || eh.e_phnum > 128 || eh.e_phoff < sizeof(eh) ||
        eh.e_phoff > 65536 || eh.e_phoff + eh.e_phnum * sizeof(Elf64_Phdr) > spec.fileSize) { error = "invalid AArch64 ELF header"; return false; }
    image.header.resize(eh.e_phoff + eh.e_phnum * sizeof(Elf64_Phdr));
    uint64_t headerOffset;
    if (!FileOffset(spec, 0, image.header.size(), headerOffset) || headerOffset != 0 ||
        !ReadAt(input.fd, image.fileOffset, image.header.data(), image.header.size())) { error = "ELF header not mapped at bias"; return false; }
    size_t count = 0;
    for (size_t i = 0; i < eh.e_phnum; ++i) {
        Elf64_Phdr ph{}; memcpy(&ph, image.header.data() + eh.e_phoff + i * sizeof(ph), sizeof(ph));
        if (ph.p_type != PT_LOAD) continue;
        if (count >= spec.segments.size()) { error = "unexpected ELF segment"; return false; }
        const auto& s = spec.segments[count++];
        if (s.address != ph.p_vaddr || s.offset != ph.p_offset || s.fileSize != ph.p_filesz || s.memorySize != ph.p_memsz ||
            s.flags != ph.p_flags || s.alignment != ph.p_align || image.bias > UINTPTR_MAX - s.address - s.memorySize) { error = "ELF segment layout mismatch"; return false; }
    }
    if (count != spec.segments.size()) { error = "missing ELF segment"; return false; }
    if (geometry) {
        uint64_t dynamicAddress = 0, dynamicSize = 0;
        unsigned dynamicCount = 0;
        for (size_t i = 0; i < eh.e_phnum; ++i) {
            Elf64_Phdr ph{}; memcpy(&ph, image.header.data() + eh.e_phoff + i * sizeof(ph), sizeof(ph));
            if (ph.p_type != PT_DYNAMIC) continue;
            ++dynamicCount; dynamicAddress = ph.p_vaddr; dynamicSize = ph.p_filesz;
        }
        auto read = [&](uint64_t address, void* output, size_t size, uint32_t flags) {
            uint64_t offset = 0;
            return FileOffset(spec, address, size, offset, flags) && offset <= UINT64_MAX - image.fileOffset &&
                ReadAt(input.fd, image.fileOffset + offset, output, size);
        };
        if (dynamicCount == 1) {
            // Failure disables only geometry: ordinary icons keep their already
            // verified profile. No import address or system-library offset is fixed.
            monoicon::hyos::ReadImportedFunctionSlots(dynamicAddress, dynamicSize,
                "libhyper_os_schema_public.so", {"Bundle_get_f64", "Bundle_get_f32", "Bundle_drop"},
                read, image.geometryImportSlots);
        }
    }
    return true;
}
struct Probe { const Profile* profile; std::vector<MappedImage>* images; bool duplicate = false; };
int Capture(dl_phdr_info* info, size_t, void* data) {
    auto& probe = *static_cast<Probe*>(data);
    if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
    const char* basename = strrchr(info->dlpi_name, '/'); basename = basename ? basename + 1 : info->dlpi_name;
    for (size_t i = 0; i < probe.profile->images.size(); ++i) if (probe.profile->images[i].library == basename) {
        auto& image = (*probe.images)[i];
        if (image.bias) { probe.duplicate = true; return 1; }
        image.bias = info->dlpi_addr; image.path = info->dlpi_name;
    }
    return 0;
}
bool Number(const char*& p, const char* end, unsigned base, uint64_t& out) {
    while (p < end && *p == ' ') ++p;
    const char* first = p; out = 0;
    while (p < end) {
        char c = *p; unsigned digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : base;
        if (digit >= base) break;
        if (out > (UINT64_MAX - digit) / base) return false;
        out = out * base + digit; ++p;
    }
    return p != first;
}
struct Range { uintptr_t current, end; uint64_t offset; bool executable; };
bool CheckRanges(const MappedImage& image, Range* ranges, size_t count, const char* maps, size_t size) {
    const char* end = maps + size;
    for (const char* line = maps; line < end;) {
        const char* next = line; while (next < end && *next != '\n') ++next;
        if (next == end) return false;
        const char* p = line; uint64_t start, stop, offset, deviceMajor, deviceMinor, inode;
        if (!Number(p,next,16,start) || p == next || *p++ != '-' || !Number(p,next,16,stop) || start >= stop) return false;
        while (p < next && *p == ' ') ++p;
        if (next - p < 5) return false;
        const bool read = p[0] == 'r', write = p[1] == 'w', exec = p[2] == 'x', priv = p[3] == 'p'; p += 4;
        if (!Number(p,next,16,offset) || !Number(p,next,16,deviceMajor) || p == next || *p++ != ':' || !Number(p,next,16,deviceMinor) || !Number(p,next,10,inode)) return false;
        while (p < next && *p == ' ') ++p;
        for (size_t i = 0; i < count; ++i) {
            auto& range = ranges[i];
            if (range.current == range.end || stop <= range.current) continue;
            if (start > range.current || !read || write || !priv || exec != range.executable || inode != image.inode ||
                deviceMajor != major(image.device) || deviceMinor != minor(image.device) || static_cast<size_t>(next - p) != image.path.size() ||
                memcmp(p,image.path.data(),image.path.size()) || offset > UINT64_MAX - (range.current - start) ||
                offset + range.current - start != image.fileOffset + range.offset) return false;
            const uintptr_t advance = std::min<uintptr_t>(stop,range.end) - range.current;
            range.current += advance; range.offset += advance;
        }
        line = next + 1;
    }
    for (size_t i = 0; i < count; ++i) if (ranges[i].current != ranges[i].end) return false;
    return true;
}
bool MemoryEqual(const Runtime& runtime, uintptr_t address, const uint8_t* bytes, size_t size) {
    uint8_t buffer[1024];
    for (size_t at = 0; at < size;) {
        const size_t count = std::min(sizeof(buffer), size - at);
        if (!runtime.readMemory(address + at,buffer,count) || memcmp(buffer,bytes + at,count)) return false;
        at += count;
    }
    return true;
}
} // namespace

Runtime::~Runtime() {
    if (memory_ >= 0) close(memory_);
    if (pipeRead_ >= 0) close(pipeRead_);
    if (pipeWrite_ >= 0) close(pipeWrite_);
}

bool Runtime::readMemory(uintptr_t address, void* output, size_t size) const {
    if (!output || address > UINTPTR_MAX - size) return false;
    if (memory_ >= 0) return ReadAt(memory_, address, output, size);
    if (pipeRead_ < 0 || pipeWrite_ < 0 || !pipeHealthy_) return false;
    // SELinux may deny /proc/self/mem; vendor seccomp may trap process_vm_readv.
    // A private nonblocking pipe uses ordinary read/write instead. The kernel's
    // copy_from_user safely rejects an unmapped source with EFAULT. Never memcpy
    // a launcher address based solely on an earlier /proc/maps snapshot.
    // Only the palette worker owns these FDs. Each <= PIPE_BUF chunk is drained
    // before the next write, including a short write, so no peer is needed and
    // no userspace lock/allocation occurs while launcher threads are parked.
    auto* target = static_cast<uint8_t*>(output);
    while (size) {
        const size_t chunk = std::min<size_t>(size, 1024);
        long written;
        do { written = syscall(SYS_write, pipeWrite_, reinterpret_cast<const void*>(address), chunk); }
        while (written < 0 && errno == EINTR);
        if (written <= 0 || static_cast<size_t>(written) > chunk) { pipeHealthy_ = false; return false; }
        size_t drained = 0;
        while (drained < static_cast<size_t>(written)) {
            const long count = syscall(SYS_read, pipeRead_, target + drained, written - drained);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) { pipeHealthy_ = false; return false; }
            drained += static_cast<size_t>(count);
        }
        if (drained != chunk) { pipeHealthy_ = false; return false; }
        address += chunk; target += chunk; size -= chunk;
    }
    return true;
}

bool Runtime::discover(const Profile& profile, std::string& error) {
    images.clear(); images.resize(profile.images.size());
    Probe probe{&profile, &images}; dl_iterate_phdr(Capture, &probe);
    if (probe.duplicate) { error = "ambiguous loaded ELF image"; return false; }
    // Wait for all images before hashing any of them. Loader timing must not
    // repeatedly rehash a large AOT image while another library is still absent.
    for (size_t i = 0; i < images.size(); ++i) {
        if (!images[i].bias) { error = "image-missing: " + profile.images[i].library; return false; }
    }
    for (size_t i = 0; i < images.size(); ++i) {
        const bool geometry = profile.icons.geometryBridge < profile.functions.size() &&
            profile.functions[profile.icons.geometryBridge].image == i;
        if (!DiskImage(profile.images[i], images[i], error, geometry)) return false;
    }
    if (memory_ < 0) memory_ = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (memory_ < 0 && pipeRead_ < 0) {
        int descriptors[2];
        if (syscall(SYS_pipe2, descriptors, O_CLOEXEC | O_NONBLOCK) != 0) {
            error = "safe process memory reader unavailable"; return false;
        }
        pipeRead_ = descriptors[0]; pipeWrite_ = descriptors[1];
    }
    const uint32_t sentinel = 0x4d495052;
    uint32_t observed = 0;
    if (!readMemory(reinterpret_cast<uintptr_t>(&sentinel), &observed, sizeof(observed)) || observed != sentinel) {
        error = "safe process memory reader probe failed"; return false;
    }
    maps_.resize(2 * 1024 * 1024);
    return true;
}
bool Runtime::readWord(uintptr_t address, uint32_t& word) const { return readMemory(address, &word, sizeof(word)); }
bool Runtime::geometryImports(size_t index, std::array<uintptr_t, 3>& addresses) const {
    if (index >= images.size()) return false;
    const auto& image = images[index];
    std::array<uintptr_t, 3> found{};
    for (size_t i = 0; i < found.size(); ++i) {
        const uint64_t slot = image.geometryImportSlots[i];
        if (!slot || slot > UINTPTR_MAX - image.bias ||
            !readMemory(image.bias + slot, &found[i], sizeof(found[i])) || !found[i]) return false;
    }
    addresses = found;
    return true;
}
bool Runtime::validate(const Profile& p, const std::vector<uint32_t>& current, uint32_t& checked) {
    checked = 0;
    if (images.size() != p.images.size() || current.size() != p.patches.size() || maps_.empty()) return false;
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC, 0));
    if (fd < 0) return false;
    size_t used = 0; bool complete = false;
    while (used < maps_.size()) {
        const long count = syscall(SYS_read, fd, maps_.data() + used, maps_.size() - used);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) break;
        if (!count) { complete = true; break; }
        used += count;
    }
    syscall(SYS_close, fd); if (!complete) return false;
    const size_t pageSize = static_cast<size_t>(getpagesize());
    for (size_t i = 0; i < images.size(); ++i) {
        const auto& image = images[i]; const auto& spec = p.images[i];
        Range ranges[1 + 32 + 256 + kMaxPatches]; size_t count = 0;
        ranges[count++] = {image.bias, image.bias + image.header.size(), 0, false};
        for (const auto& note : spec.identity) {
            uint64_t offset; if (!FileOffset(spec,note.address,note.bytes.size(),offset)) return false;
            ranges[count++] = {image.bias + note.address,image.bias + note.address + note.bytes.size(),offset,false};
        }
        for (const auto& f : p.functions) if (f.image == i) {
            uint64_t offset; if (!FileOffset(spec,f.address,f.bytes.size(),offset,5)) return false;
            ranges[count++] = {image.bias + f.address,image.bias + f.address + f.bytes.size(),offset,true};
        }
        for (const auto& patch : p.patches) if (p.functions[patch.function].image == i) {
            const uintptr_t page = (image.bias + patch.address) & ~(pageSize - 1);
            uint64_t offset; if (!FileOffset(spec,patch.address,4,offset,5) || offset < image.bias + patch.address - page) return false;
            ranges[count++] = {page,page + pageSize,offset - (image.bias + patch.address - page),true};
        }
        if (!CheckRanges(image,ranges,count,maps_.data(),used) || !MemoryEqual(*this,image.bias,image.header.data(),image.header.size())) return false;
        for (const auto& note : spec.identity) if (!MemoryEqual(*this,image.bias + note.address,note.bytes.data(),note.bytes.size())) return false;
    }
    uint8_t actual[1024], expected[1024];
    for (size_t fi = 0; fi < p.functions.size(); ++fi) {
        const auto& f = p.functions[fi];
        for (size_t at = 0; at < f.bytes.size();) {
            const size_t count = std::min(sizeof(actual),f.bytes.size() - at);
            memcpy(expected,f.bytes.data() + at,count);
            for (size_t j = 0; j < p.patches.size(); ++j) {
                const auto& patch = p.patches[j];
                if (patch.function == fi && patch.address >= f.address + at && patch.address - f.address - at < count) {
                    memcpy(expected + patch.address - f.address - at,&current[j],4);
                }
            }
            if (!readMemory(images[f.image].bias + f.address + at,actual,count) || memcmp(actual,expected,count)) return false;
            checked += count; at += count;
        }
    }
    return true;
}
bool ValidDigest(const std::string& s) { return s.size() == 64 && std::all_of(s.begin(),s.end(),[](char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); }); }
bool LoadProfile(const char* digest, Profile& profile, std::string& error) {
    if (!digest || !ValidDigest(digest)) { error = "missing or invalid profile digest"; return false; }
    char path[256], name[96]; snprintf(name,sizeof(name),"monoicon_profiles/%s.json",digest);
    monoicon::hyos::LauncherFilePath(path,sizeof(path),name);
    File input(open(path,O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK)); struct stat st{};
    if (input.fd < 0 || fstat(input.fd,&st) || !S_ISREG(st.st_mode) || st.st_size <= 0 || st.st_size > static_cast<off_t>(kMaxProfileBytes)) { error = "profile file missing or invalid"; return false; }
    std::string source(static_cast<size_t>(st.st_size),'\0');
    if (!ReadAt(input.fd,0,source.data(),source.size())) { error = "profile read failed"; return false; }
    Sha256 hash; hash.update(source.data(),source.size());
    if (hash.finish() != digest) { error = "profile content digest mismatch"; return false; }
    if (!Parse(source,profile,error) || !MatchSystem(profile,error)) return false;
    if (profile.match.systemVersions.empty()) {
        // This check must run in the launcher: a previous published commit may
        // outlive an app upgrade or OTA and be loaded before the module UI runs.
        const auto fingerprint = SystemProperty("ro.build.fingerprint");
        const auto incremental = SystemProperty("ro.build.version.incremental");
        const auto sdk = SystemProperty("ro.build.version.sdk");
        if (fingerprint.empty() || incremental.empty() || sdk.empty()) {
            error = "system identity unavailable for legacy Profile approval"; return false;
        }
        const std::string identity = fingerprint + '\n' + incremental + '\n' + std::to_string(strtoul(sdk.c_str(),nullptr,10));
        Sha256 approvalHash; approvalHash.update(identity.data(),identity.size());
        const std::string expected = approvalHash.finish();
        snprintf(name,sizeof(name),"monoicon_profiles/%s.system",digest);
        monoicon::hyos::LauncherFilePath(path,sizeof(path),name);
        File approval(open(path,O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
        char actual[64]; struct stat approvalStat{};
        if (approval.fd < 0 || fstat(approval.fd,&approvalStat) || !S_ISREG(approvalStat.st_mode) ||
            approvalStat.st_size != 64 || !ReadAt(approval.fd,0,actual,sizeof(actual)) ||
            memcmp(actual,expected.data(),sizeof(actual)) != 0) {
            error = "legacy Profile requires manual approval for this system build"; return false;
        }
    }
    return true;
}
bool MatchSystem(const Profile& p, std::string& error) {
    char sdk[PROP_VALUE_MAX]{}; __system_property_get("ro.build.version.sdk",sdk);
    char* end = nullptr; const unsigned long value = strtoul(sdk,&end,10);
    if (!sdk[0] || !end || *end || value < p.match.sdkMin || value > p.match.sdkMax) { error = "Android SDK outside profile range"; return false; }
    if (!p.match.systemVersions.empty()) {
        const auto incremental = SystemProperty("ro.build.version.incremental");
        if (std::find(p.match.systemVersions.begin(),p.match.systemVersions.end(),incremental) == p.match.systemVersions.end()) {
            error = "system version mismatch: " + incremental; return false;
        }
    }
    for (const auto& property : p.match.properties) {
        const auto value = SystemProperty(property.name.c_str());
        if (std::none_of(property.prefixes.begin(),property.prefixes.end(),[&](const std::string& prefix) { return value.rfind(prefix,0) == 0; })) {
            error = "system property mismatch: " + property.name + "=" + value; return false;
        }
    }
    return true;
}
} // namespace monoicon::profile
