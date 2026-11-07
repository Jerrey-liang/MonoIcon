#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <vector>

namespace monoicon::hyos {
// Parse only AArch64 ELF64 RELA jump slots. The reader enforces the verified
// image's PT_LOAD/file bounds and requested ELF flags (R=4, RW=6). Addresses
// remain ELF virtual addresses until the caller adds its validated load bias.
template <typename Reader>
bool ReadImportedFunctionSlots(uint64_t dynamicAddress, uint64_t dynamicSize,
        const char* dependency, const std::array<const char*, 3>& names,
        Reader read, std::array<uint64_t, 3>& output) {
    if (!dynamicAddress || !dynamicSize || dynamicSize > 65536 || dynamicSize % 16) return false;
    auto word = [](const uint8_t* bytes) {
        uint64_t value = 0;
        for (unsigned i = 0; i < 8; ++i) value |= uint64_t(bytes[i]) << (8 * i);
        return value;
    };
    auto dword = [&](const uint8_t* bytes) { return static_cast<uint32_t>(word(bytes) & 0xffffffffu); };
    std::vector<uint8_t> dynamic(dynamicSize);
    if (!read(dynamicAddress, dynamic.data(), dynamic.size(), 4)) return false;
    uint64_t strings = 0, stringsSize = 0, symbols = 0, symbolSize = 0;
    uint64_t relocations = 0, relocationSize = 0, relocationKind = 0;
    std::vector<uint64_t> needed;
    unsigned seen = 0;
    bool terminated = false;
    for (size_t at = 0; at < dynamic.size(); at += 16) {
        const uint64_t tag = word(dynamic.data() + at), value = word(dynamic.data() + at + 8);
        if (tag == 0) { terminated = true; break; }
        if (tag == 1) { if (needed.size() >= 256) return false; needed.push_back(value); continue; }
        uint64_t* destination = nullptr;
        unsigned bit = 0;
        switch (tag) {
            case 5: destination = &strings; bit = 1; break;
            case 10: destination = &stringsSize; bit = 2; break;
            case 6: destination = &symbols; bit = 4; break;
            case 11: destination = &symbolSize; bit = 8; break;
            case 23: destination = &relocations; bit = 16; break;
            case 2: destination = &relocationSize; bit = 32; break;
            case 20: destination = &relocationKind; bit = 64; break;
            default: break;
        }
        if (destination) {
            if (seen & bit) return false;
            seen |= bit;
            *destination = value;
        }
    }
    if (!terminated || seen != 127 || !strings || !symbols || !relocations ||
        !stringsSize || stringsSize > 2 * 1024 * 1024 || symbolSize != 24 ||
        relocationKind != 7 || !relocationSize || relocationSize > 16384 * 24 || relocationSize % 24) return false;
    std::vector<uint8_t> text(stringsSize), entries(relocationSize);
    if (!read(strings, text.data(), text.size(), 4) || !read(relocations, entries.data(), entries.size(), 4)) return false;
    auto equal = [&](uint64_t offset, const char* expected) {
        const size_t size = std::strlen(expected) + 1;
        return offset < text.size() && size <= text.size() - offset &&
            std::memcmp(text.data() + offset, expected, size) == 0;
    };
    if (std::none_of(needed.begin(), needed.end(), [&](uint64_t offset) { return equal(offset, dependency); })) return false;
    std::array<uint64_t, 3> slots{};
    for (size_t at = 0; at < entries.size(); at += 24) {
        const auto* relocation = entries.data() + at;
        const uint64_t info = word(relocation + 8);
        if (static_cast<uint32_t>(info) != 1026) continue; // R_AARCH64_JUMP_SLOT
        const uint64_t index = info >> 32;
        if (!index || index >= 65536 || symbols > UINT64_MAX - index * 24) return false;
        uint8_t symbol[24];
        if (!read(symbols + index * 24, symbol, sizeof(symbol), 4)) return false;
        for (size_t i = 0; i < names.size(); ++i) {
            if (!equal(dword(symbol), names[i])) continue;
            // A strong, undefined function import, with no relocation addend.
            if (symbol[4] != 0x12 || symbol[6] || symbol[7] || word(symbol + 8) ||
                word(relocation + 16) || slots[i]) return false;
            const uint64_t slot = word(relocation);
            uint64_t original = 0;
            if (!slot || slot % 8 || !read(slot, &original, sizeof(original), 6)) return false;
            slots[i] = slot;
        }
    }
    if (std::any_of(slots.begin(), slots.end(), [](uint64_t slot) { return slot == 0; })) return false;
    if (slots[0] == slots[1] || slots[0] == slots[2] || slots[1] == slots[2]) return false;
    output = slots;
    return true;
}
} // namespace monoicon::hyos
