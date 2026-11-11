cmake_minimum_required(VERSION 3.22)

project(sandfly_lua_memory C)

add_library(sandfly_lua_memory SHARED lua_memory.c)
target_compile_options(sandfly_lua_memory PRIVATE -O2 -Wall -fvisibility=hidden)
# Devices with 16 KB memory pages (Android 15+) can only map libraries whose
# segments start on 16 KB boundaries, so link with that maximum page size.
target_link_options(sandfly_lua_memory PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(sandfly_lua_memory PRIVATE dl)
