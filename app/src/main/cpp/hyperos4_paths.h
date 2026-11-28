#pragma once
#include <cerrno>
#include <cstdio>
#include <unistd.h>

namespace monoicon::hyos {
// Large icon resources and diagnostics live in launcher device-encrypted storage.
inline void LauncherFilePath(char* output, size_t capacity, const char* name) {
    const int saved = errno;
    const unsigned user = static_cast<unsigned>(getuid()) / 100000u;
    std::snprintf(output, capacity, "/data/user_de/%u/com.miui.home/files/%s", user, name);
    errno = saved;
}
} // namespace monoicon::hyos
