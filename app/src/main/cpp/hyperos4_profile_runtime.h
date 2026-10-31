#pragma once
#include "hyperos4_profile.h"
#include <sys/types.h>

namespace monoicon::profile {
struct MappedImage {
    uintptr_t bias{};
    std::string path;
    uint64_t fileOffset{}, inode{}, device{};
    std::vector<uint8_t> header;
};
// Discovery and all allocations happen before parking threads. Validation below
// uses preallocated storage, raw syscalls and bounded stack buffers only.
class Runtime {
public:
    ~Runtime();
    bool discover(const Profile& profile, std::string& error);
    bool validate(const Profile& profile, const std::vector<uint32_t>& current, uint32_t& checked);
    bool readMemory(uintptr_t address, void* output, size_t size) const;
    bool readWord(uintptr_t address, uint32_t& word) const;
    uintptr_t address(const Profile& p, const Patch& patch) const { return images[p.functions[patch.function].image].bias + patch.address; }
    std::vector<MappedImage> images;
private:
    int memory_ = -1;
    int pipeRead_ = -1, pipeWrite_ = -1;
    mutable bool pipeHealthy_ = true;
    std::vector<char> maps_;
};
bool LoadProfile(const char* digest, Profile& profile, std::string& error);
bool MatchSystem(const Profile& profile, std::string& error);
bool ValidDigest(const std::string& digest);
} // namespace monoicon::profile
