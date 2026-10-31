cmake_minimum_required(VERSION 3.22.1)
project(monoicon_hyperos4 LANGUAGES CXX)

# One parser for JNI validation and the native_init compatibility engine.
add_library(monoicon_hyperos4 SHARED hyperos4_native.cpp hyperos4_profile.cpp)
target_compile_features(monoicon_hyperos4 PRIVATE cxx_std_17)
target_compile_options(monoicon_hyperos4 PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(monoicon_hyperos4 PRIVATE "-Wl,-z,now")

if("${ANDROID_ABI}" STREQUAL "arm64-v8a")
    add_library(monoicon_hyperos4_native_init SHARED
        hyperos4_native_init.cpp
        hyperos4_startup.cpp
        hyperos4_hook_backend.cpp
        hyperos4_palette_install.cpp
        hyperos4_icon_files.cpp
        hyperos4_geometry_bridge.cpp
        hyperos4_profile.cpp
        hyperos4_profile_runtime.cpp
    )

    target_compile_features(monoicon_hyperos4_native_init PRIVATE cxx_std_17)
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
