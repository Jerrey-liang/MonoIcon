cmake_minimum_required(VERSION 3.22.1)
project(monoicon_hyperos4 LANGUAGES CXX)

add_library(monoicon_hyperos4 SHARED hyperos4_native.cpp)
target_compile_features(monoicon_hyperos4 PRIVATE cxx_std_17)
target_compile_options(monoicon_hyperos4 PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(monoicon_hyperos4 PRIVATE "-Wl,-z,now")
target_link_libraries(monoicon_hyperos4 PRIVATE dl log)
