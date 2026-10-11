#pragma once

#include <sys/mman.h>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace hyperos4 {
inline bool branchInstruction(uintptr_t from, uintptr_t to, uint32_t& word) {
    if (((from | to) & 3) != 0) return false;
    const bool forward = to >= from;
    const uintptr_t distance = forward ? to - from : from - to;
    if (distance > (uintptr_t{1} << 27) || (forward && distance == (uintptr_t{1} << 27))) return false;
    const int64_t delta = forward ? static_cast<int64_t>(distance) : -static_cast<int64_t>(distance);
    word = 0x14000000u | (static_cast<uint32_t>(delta / 4) & 0x03ffffffu);
    return true;
}

// Hints never replace a mapping. Check the actual return address: kernels may
// ignore a hint or another thread may have occupied the gap since discovery.
inline void* codePageHint(uintptr_t hint, uintptr_t from, size_t pageSize, size_t codeSize) {
    if (from > UINTPTR_MAX - 4 || codeSize < 4 || codeSize > pageSize) return nullptr;
    void* page = mmap(reinterpret_cast<void*>(hint), pageSize, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (page == MAP_FAILED) return nullptr;
    const uintptr_t address = reinterpret_cast<uintptr_t>(page);
    uint32_t ignored;
    if (address > UINTPTR_MAX - codeSize ||
        !branchInstruction(from, address, ignored) ||
        !branchInstruction(address + codeSize - 4, from + 4, ignored)) {
        munmap(page, pageSize);
        return nullptr;
    }
    return page;
}

inline bool sealCodePage(void* page, size_t pageSize, const void* code, size_t codeSize) {
    if (codeSize > pageSize) return false;
    memcpy(page, code, codeSize);
    auto* first = static_cast<char*>(page);
    __builtin___clear_cache(first, first + codeSize);
    return mprotect(page, pageSize, PROT_READ | PROT_EXEC) == 0;
}
} // namespace hyperos4
