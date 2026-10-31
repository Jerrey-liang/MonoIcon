#pragma once

#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace monoicon::hyos {
// Generated configuration/icons must be readable before the user's first unlock.
// Legacy CE commits remain readable until the app publishes its first DE commit.
// Once a DE commit exists, a corrupt/inaccessible DE file never falls back to CE.
inline void LauncherFilePath(char* output, size_t capacity, const char* name) {
    const int saved = errno;
    const unsigned user = static_cast<unsigned>(getuid()) / 100000u;
    char commit[192];
    auto exists = [&](const char* kind) {
        std::snprintf(commit, sizeof(commit), "/data/%s/%u/com.miui.home/files/monoicon_palette.txt", kind, user);
        const long fd = syscall(SYS_openat, AT_FDCWD, commit, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0);
        if (fd >= 0) { syscall(SYS_close, fd); return true; }
        return errno != ENOENT;
    };
    const char* storage = "user_de";
    if (!exists(storage) && exists("user")) storage = "user";
    std::snprintf(output, capacity, "/data/%s/%u/com.miui.home/files/%s", storage, user, name);
    errno = saved;
}
} // namespace monoicon::hyos
