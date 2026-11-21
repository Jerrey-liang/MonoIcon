#pragma once

// Read-only launcher diagnostics. These helpers are used by the installer's
// existing status boundary, never by an icon hook or while peers are parked.
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <link.h>
#include <string>

namespace monoicon::hyos::diagnostics {

inline void Quote(std::string& output, const char* value) {
    output += '"';
    if (value) for (const unsigned char* p = reinterpret_cast<const unsigned char*>(value); *p; ++p) {
        switch (*p) {
            case '"': output += "\\\""; break;
            case '\\': output += "\\\\"; break;
            case '\n': output += "\\n"; break;
            case '\r': output += "\\r"; break;
            case '\t': output += "\\t"; break;
            default:
                if (*p < 0x20) {
                    char escaped[7];
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", *p);
                    output += escaped;
                } else output += static_cast<char>(*p);
        }
    }
    output += '"';
}

inline void Text(std::string& output, const char* name, const char* value) {
    Quote(output, name); output += ':'; Quote(output, value);
}
inline void Number(std::string& output, const char* name, uint64_t value) {
    Quote(output, name); output += ':'; output += std::to_string(value);
}
inline void Hex(std::string& output, const char* name, uint64_t value, bool available = true, bool word = false) {
    char text[24]{};
    if (available) std::snprintf(text, sizeof(text), word ? "0x%08llx" : "0x%llx", static_cast<unsigned long long>(value));
    Text(output, name, text);
}

// The startup hook may be installed in the spawner and inherited by a child.
// Atomic publication also allows a worker to report a partially attempted set
// without reading the hook function's backup variable concurrently.
struct HookPoint {
    const char* id;
    std::atomic<uintptr_t> target{0}, replacement{0}, backup{0};
    std::atomic<int> result{-1}, alias{-1};
    std::atomic<uint32_t> originPid{0};
    std::atomic<const char*> state{"not-attempted"};
};

inline void Record(HookPoint& point, void* target, void* replacement, void* backup,
        int result, uint32_t pid, const char* state, int alias = -1) {
    point.target.store(reinterpret_cast<uintptr_t>(target), std::memory_order_relaxed);
    point.replacement.store(reinterpret_cast<uintptr_t>(replacement), std::memory_order_relaxed);
    point.backup.store(reinterpret_cast<uintptr_t>(backup), std::memory_order_relaxed);
    point.result.store(result, std::memory_order_relaxed);
    point.alias.store(alias, std::memory_order_relaxed);
    point.originPid.store(pid, std::memory_order_relaxed);
    point.state.store(state, std::memory_order_release);
}

inline void AppendHook(std::string& output, const HookPoint& point, const char* kind,
        const char* aliasOf = "") {
    const char* state = point.state.load(std::memory_order_acquire);
    const uintptr_t target = point.target.load(std::memory_order_relaxed);
    const uintptr_t replacement = point.replacement.load(std::memory_order_relaxed);
    const uintptr_t backup = point.backup.load(std::memory_order_relaxed);
    Dl_info owner{};
    const bool resolved = target && dladdr(reinterpret_cast<void*>(target), &owner) != 0;
    struct ImageAddress { uintptr_t target, bias; bool found; } image{target, 0, false};
    if (target) dl_iterate_phdr([](dl_phdr_info* info, size_t, void* opaque) {
        auto& candidate = *static_cast<ImageAddress*>(opaque);
        for (size_t i = 0; i < info->dlpi_phnum; ++i) {
            const auto& segment = info->dlpi_phdr[i];
            if (segment.p_type != PT_LOAD || segment.p_vaddr > UINTPTR_MAX - info->dlpi_addr) continue;
            const uintptr_t start = info->dlpi_addr + segment.p_vaddr;
            if (candidate.target >= start && candidate.target - start < segment.p_memsz) {
                candidate.bias = info->dlpi_addr; candidate.found = true; return 1;
            }
        }
        return 0;
    }, &image);
    const uintptr_t bias = image.bias;
    const char* path = resolved && owner.dli_fname ? owner.dli_fname : "";
    const char* slash = std::strrchr(path, '/');
    output += '{'; Text(output, "id", point.id);
    output += ','; Text(output, "kind", kind);
    output += ','; Text(output, "state", state);
    output += ','; Text(output, "library", slash ? slash + 1 : path);
    output += ','; Text(output, "path", path);
    output += ','; Text(output, "function", point.id);
    output += ','; Hex(output, "offset", target >= bias ? target - bias : 0, image.found && target >= bias);
    output += ','; Hex(output, "target", target, target != 0);
    output += ','; Hex(output, "loadBias", bias, image.found);
    output += ','; Hex(output, "libraryBase", reinterpret_cast<uintptr_t>(owner.dli_fbase), resolved);
    output += ','; Text(output, "valueType", "pointer");
    output += ','; Hex(output, "original", backup, backup != 0);
    output += ','; Hex(output, "replacement", replacement, replacement != 0);
    output += ','; Text(output, "observed", "");
    output += ','; Text(output, "aliasOf", aliasOf);
    output += ','; Text(output, "detail", state);
    output += ','; Number(output, "originPid", point.originPid.load(std::memory_order_relaxed));
    output += ','; Quote(output, "resultCode"); output += ':';
    output += std::to_string(point.result.load(std::memory_order_relaxed));
    output += '}';
}

} // namespace monoicon::hyos::diagnostics

extern "C" void monoicon_hyos_append_init_diagnostics(std::string& output);
extern "C" void monoicon_hyos_append_startup_diagnostics(std::string& output);
extern "C" void monoicon_hyos_append_icon_diagnostics(std::string& output);
