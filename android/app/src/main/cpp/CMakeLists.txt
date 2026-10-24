cmake_minimum_required(VERSION 3.22.1)
project(kemzyliveavatar LANGUAGES CXX)

add_library(kemzyliveavatar SHARED
    native-lib.cpp
    faceit_engine.cpp
    humanoid_rig.cpp
)

target_compile_features(kemzyliveavatar PRIVATE cxx_std_17)
target_compile_options(kemzyliveavatar PRIVATE -Wall -Wextra -Werror=return-type)

find_library(log-lib log REQUIRED)
find_library(gles-lib GLESv3 REQUIRED)

target_link_libraries(kemzyliveavatar PRIVATE ${log-lib} ${gles-lib})
