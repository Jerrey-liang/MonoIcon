cmake_minimum_required(VERSION 3.22.1)
project(monoicon_hyperos4 LANGUAGES CXX)

# ---------------------------------------------------------------------------
# Production patch library for the old (Java) HyperOS 4 launcher.
#
# This one is loaded by Kotlin through System.loadLibrary, so it only applies to
# a launcher process that actually runs ART. On HyperOS 4 the launcher is a
# hybrid native process with no Java runtime and never reaches that code path;
# the native_init payload below is what covers it. Kept unchanged for the legacy
# and SystemUI paths.
# ---------------------------------------------------------------------------
add_library(monoicon_hyperos4 SHARED hyperos4_native.cpp)
target_compile_features(monoicon_hyperos4 PRIVATE cxx_std_17)
target_compile_options(monoicon_hyperos4 PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(monoicon_hyperos4 PRIVATE "-Wl,-z,now")
target_link_libraries(monoicon_hyperos4 PRIVATE dl log)

# ---------------------------------------------------------------------------
# LSPosed native_init payload for the dex-less HyperOS 4 launcher.
#
# META-INF/xposed/native_init.list names libmonoicon_hyperos4_native_init.so;
# LSPosed loads it into the HYOS spawner process family
# (/system_ext/bin/hyos_spawner, cmdline usap64, which forks com.miui.home) and
# calls the exported native_init(). That is the only supported way into a
# launcher process that has no DEX and no Java class loading, where the Xposed
# onPackageReady / Application.attach bootstrap can never fire.
#
# Reference: wxxsfxyzm/MiuiBackGestureHook (miui-home-hyos-native) and
# zilewang7/HyperOS4SmallWindowInputFilter, which use the same contract.
# arm64-v8a only: the HyperOS 4 launcher ships libapp.so for arm64 alone.
# ---------------------------------------------------------------------------
if("${ANDROID_ABI}" STREQUAL "arm64-v8a")
    add_library(monoicon_hyperos4_native_init SHARED
        hyperos4_native_init.cpp
        hyperos4_hook_backend.cpp
        hyperos4_palette_install.cpp
    )

    target_compile_features(monoicon_hyperos4_native_init PRIVATE cxx_std_17)
    # Diagnostic-only switch for the one-shot Dobby branch-target measurement. Default
    # OFF: the probe must never run in a normal build. The spawner's environment cannot
    # be set from adb, so a diagnostic run turns this on explicitly with
    # -DMONOICON_HYOS_BRANCH_PROBE_ALWAYS=ON.
    option(MONOICON_HYOS_BRANCH_PROBE_ALWAYS
           "Enable the one-shot Dobby branch-target probe" OFF)
    if(MONOICON_HYOS_BRANCH_PROBE_ALWAYS)
        message(STATUS "[MonoIcon] branch-probe diagnostic enabled")
        target_compile_definitions(monoicon_hyperos4_native_init PRIVATE
            MONOICON_HYOS_BRANCH_PROBE_ALWAYS=1)
    endif()
    target_compile_options(monoicon_hyperos4_native_init PRIVATE
        -fno-exceptions
        -fno-rtti
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
        -mbranch-protection=standard
        -Wall
        -Wextra
    )
    target_link_options(monoicon_hyperos4_native_init PRIVATE
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
        -Wl,--gc-sections
        -Wl,--no-undefined
        -Wl,-z,relro
        -Wl,-z,now
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
    )
    target_link_libraries(monoicon_hyperos4_native_init PRIVATE log dl)

    set_target_properties(monoicon_hyperos4_native_init PROPERTIES
        CXX_EXTENSIONS OFF
        OUTPUT_NAME "monoicon_hyperos4_native_init"
    )
endif()

# ---------------------------------------------------------------------------
# Debug-only runtime trace probe for the HyperOS 4 Dart icon pipeline.
# Instrumentation only: entry probes + execution counters, no rendering changes.
# See external_resources/HyperOS4/analysis/runtime_trace/ for the generator and
# the verification of every hand-encoded instruction in the stub.
# ---------------------------------------------------------------------------
add_library(monoicon_hyperos4_trace SHARED hyperos4_trace_probe.cpp)
target_compile_features(monoicon_hyperos4_trace PRIVATE cxx_std_17)
target_compile_options(monoicon_hyperos4_trace PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(monoicon_hyperos4_trace PRIVATE "-Wl,-z,now")
target_link_libraries(monoicon_hyperos4_trace PRIVATE dl log)
