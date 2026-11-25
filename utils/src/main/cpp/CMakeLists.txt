cmake_minimum_required(VERSION 3.22.1)
project(meridian_aaudio LANGUAGES CXX)

add_library(
    meridian_aaudio
    SHARED
    meridian_aaudio.cpp
)

target_compile_features(meridian_aaudio PRIVATE cxx_std_17)
target_compile_options(meridian_aaudio PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)

find_library(aaudio-lib aaudio)
find_library(log-lib log)

target_link_libraries(
    meridian_aaudio
    ${aaudio-lib}
    ${log-lib}
)
