#pragma once
#include <cstdint>
#include <cstddef>

// The hook primitive is provided by LSPosed's native_init API. Call this only
// after that API is available; the implementation rejects non-launcher children.
using MonoiconNativeHook = int (*)(void*, void*, void**);

extern "C" bool monoicon_hyos_install_icon_files(MonoiconNativeHook hook);
extern "C" bool monoicon_hyos_prepare_icon_files();
extern "C" bool monoicon_hyos_icon_generation_ready(const char* generation);
extern "C" void monoicon_hyos_select_icon_generation(const char* generation);
extern "C" void monoicon_hyos_protect_code_pages(const uintptr_t* pages, size_t count, size_t pageSize);

// The native configuration owner supplies this process-local, atomic gate.
extern "C" bool monoicon_hyos_icons_enabled();
