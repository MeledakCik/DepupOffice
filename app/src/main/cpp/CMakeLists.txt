cmake_minimum_required(VERSION 3.22.1)
project(depup_integrity LANGUAGES CXX)

add_library(depup_integrity SHARED integrity_native.cpp)
target_compile_features(depup_integrity PRIVATE cxx_std_17)
target_compile_options(depup_integrity PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(depup_integrity android log)
