// LSPosed owns the libc hook implementation. All version-dependent launcher
// instructions are handled by the verified Profile transaction instead.
#include "hyperos4_icon_files.h"
#include <atomic>
#include <cstdint>

namespace {
struct NativeAPIEntries {
    uint32_t version;
    MonoiconNativeHook hookFunc;
    int (*unhookFunc)(void*);
};
std::atomic<MonoiconNativeHook> hook{nullptr};
}
extern "C" void monoicon_hyos_install_startup_hook(MonoiconNativeHook hook);
extern "C" void monoicon_hyos_hook_backend_init(const void* entries) {
    const auto* api = static_cast<const NativeAPIEntries*>(entries);
    hook.store(api && api->hookFunc && api->unhookFunc ? api->hookFunc : nullptr, std::memory_order_release);
    monoicon_hyos_install_startup_hook(hook.load(std::memory_order_acquire));
}
extern "C" bool monoicon_hyos_prepare_icon_files() {
    return monoicon_hyos_install_icon_files(hook.load(std::memory_order_acquire));
}
