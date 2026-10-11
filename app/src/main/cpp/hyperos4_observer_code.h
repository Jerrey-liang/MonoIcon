#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

// Exact-build observer completion instrumentation. This emits AArch64 instructions
// only; it does not declare, call or manufacture a Dart/Rust/platform ABI.
// Targets the 6144 launcher build RELEASE-8.01.02.6144-260818-08251107-R
// (libapp_launcher.so Android note r27d/13750724; the libapp.so GNU build id is
// 4f1bdaedd19666f2b22817f1fabc965f). Evidence: analysis/port6144/ under
// external_resources/HyperOS4.
namespace monoicon::hyperos4::observer {

constexpr uintptr_t kPatchVaddr = 0x5aefec;
constexpr uint32_t kOriginalBranch = 0x17ffff3d;
constexpr uintptr_t kContinuationVaddr = 0x5aece0;
constexpr uintptr_t kFunctionVaddr = 0x5ae5b0;
constexpr size_t kFunctionSize = 2784;
constexpr size_t kVeneerSize = 64;
inline constexpr std::array<uint8_t, kFunctionSize> kOriginalFunction{{
#include "hyperos4_observer_original.inc"
}};

// The source and destination must both be instruction aligned and within the
// architectural signed imm26 range. Avoid unsigned subtraction or narrowing an
// unchecked address delta; both directions are used for the nearby veneer.
inline bool encodeBranch(uintptr_t from, uintptr_t to, uint32_t& word) {
    if ((from & 3u) != 0 || (to & 3u) != 0) return false;
    uint32_t immediate = 0;
    if (to >= from) {
        const uintptr_t distance = to - from;
        if (distance > 0x07fffffcu) return false;
        immediate = static_cast<uint32_t>(distance >> 2);
    } else {
        const uintptr_t distance = from - to;
        if (distance > 0x08000000u) return false;
        immediate = (0u - static_cast<uint32_t>(distance >> 2)) & 0x03ffffffu;
    }
    word = 0x14000000u | immediate;
    return true;
}

// Caller owns the mapped pages and their lifetime. Emit while output is RW,
// flush its instruction cache, make it RX, and keep the separate naturally
// aligned generation word RW before installing the source branch. The original
// branch and the veneer return branch must both be reachable. The caller reads
// generation with an acquire atomic operation, never an ordinary concurrent load.
//
// Every GPR, SIMD register, NZCV and the original SP are preserved. Only x14-x17
// are used, all saved on a 48-byte, 16-byte-aligned native stack frame. There are
// no calls or managed frames; a release exclusive store publishes completion.
inline bool emitVeneer(uint8_t* output, size_t capacity, uintptr_t veneerAddress,
                       uintptr_t continuationAddress, uint32_t* generation) {
    const uintptr_t generationAddress = reinterpret_cast<uintptr_t>(generation);
    if (output == nullptr || capacity < kVeneerSize || generation == nullptr ||
        (generationAddress & 3u) != 0 || (veneerAddress & 3u) != 0 ||
        veneerAddress > UINTPTR_MAX - kVeneerSize) return false;

    uint32_t returnBranch = 0;
    if (!encodeBranch(veneerAddress + 52, continuationAddress, returnBranch)) return false;

    const uint32_t code[14] = {
        0xa9bd3feeu, // stp x14, x15, [sp, #-48]!
        0xa90147f0u, // stp x16, x17, [sp, #16]
        0xd53b420eu, // mrs x14, nzcv
        0xf90013eeu, // str x14, [sp, #32]
        0x5800014eu, // ldr x14, [pc, #40] (generation pointer literal at +56)
        0x885ffdcfu, // ldaxr w15, [x14]
        0x110005efu, // add w15, w15, #1
        0x8810fdcfu, // stlxr w16, w15, [x14]
        0x35ffffb0u, // cbnz w16, #-12 (retry at +20)
        0xf94013eeu, // ldr x14, [sp, #32]
        0xd51b420eu, // msr nzcv, x14
        0xa94147f0u, // ldp x16, x17, [sp, #16]
        0xa8c33feeu, // ldp x14, x15, [sp], #48
        returnBranch,
    };
    const uint64_t generationLiteral = static_cast<uint64_t>(generationAddress);
    std::memcpy(output, code, sizeof(code));
    std::memcpy(output + sizeof(code), &generationLiteral, sizeof(generationLiteral));
    return true;
}

} // namespace monoicon::hyperos4::observer
