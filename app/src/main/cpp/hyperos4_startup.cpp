// A preloaded child may never dlopen its inherited images. The first launcher
// main-thread pthread_create is another startup boundary before Flutter's UI
// thread can run. Keep this hook inert in the spawner and all other processes.
#include "hyperos4_icon_files.h"
#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <unistd.h>

extern "C" bool monoicon_hyos_palette_started();
extern "C" uint32_t monoicon_hyos_palette_startup();
extern "C" uint32_t monoicon_hyos_palette_install();

namespace {
using CreateThread = int (*)(pthread_t*, const pthread_attr_t*, void* (*)(void*), void*);
CreateThread originalCreate = nullptr;
thread_local bool entering = false;

bool LauncherReady() {
    if (getuid() == 0) return false;
    const int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, "/proc/self/cmdline", O_RDONLY | O_CLOEXEC, 0));
    if (fd < 0) return false;
    char name[64]{};
    const long count = syscall(SYS_read, fd, name, sizeof(name));
    syscall(SYS_close, fd);
    constexpr char launcher[] = "com.miui.home";
    if (count < static_cast<long>(sizeof(launcher)) || std::memcmp(name, launcher, sizeof(launcher))) return false;
    unsigned images = 0;
    dl_iterate_phdr([](dl_phdr_info* info, size_t, void* context) {
        if (!info->dlpi_name) return 0;
        const char* slash = std::strrchr(info->dlpi_name, '/');
        const char* base = slash ? slash + 1 : info->dlpi_name;
        auto& found = *static_cast<unsigned*>(context);
        if (!std::strcmp(base, "libapp.so")) found |= 1;
        if (!std::strcmp(base, "libapp_launcher.so")) found |= 2;
        return found == 3 ? 1 : 0;
    }, &images);
    return images == 3;
}

int Create(pthread_t* thread, const pthread_attr_t* attributes, void* (*entry)(void*), void* argument) {
    const int saved = errno;
    if (!entering && !monoicon_hyos_palette_started()) {
        entering = true;
        if (LauncherReady()) {
            if (syscall(SYS_gettid) == getpid()) monoicon_hyos_palette_startup();
            else monoicon_hyos_palette_install();
        }
        entering = false;
    }
    errno = saved;
    return originalCreate(thread, attributes, entry, argument);
}
}

extern "C" void monoicon_hyos_install_startup_hook(MonoiconNativeHook hook) {
    if (!hook || originalCreate) return;
    void* target = dlsym(RTLD_DEFAULT, "pthread_create");
    const int result = target ? hook(target, reinterpret_cast<void*>(Create),
                                     reinterpret_cast<void**>(&originalCreate)) : -1;
    if (result != 0 || !originalCreate) {
        __android_log_print(ANDROID_LOG_WARN, "MonoIcon.HYOS", "startup thread hook unavailable: %d", result);
    }
}
