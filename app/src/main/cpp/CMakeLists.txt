cmake_minimum_required(VERSION 3.22.1)
project(monoicon_launcher_safety LANGUAGES CXX)

add_library(monoicon_launcher_safety SHARED launcher_safety.cpp launcher_safety_jni.cpp)
target_compile_features(monoicon_launcher_safety PRIVATE cxx_std_17)
target_compile_options(monoicon_launcher_safety PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(monoicon_launcher_safety PRIVATE "-Wl,-z,now")
