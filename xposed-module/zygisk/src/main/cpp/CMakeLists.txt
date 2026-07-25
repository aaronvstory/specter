cmake_minimum_required(VERSION 3.22.1)
project(specter_zygisk)

# One self-contained .so: our module + the vendored single-file inline hooker (And64InlineHook).
# ZygiskNext's builtin linker only loads self-contained modules, so we must NOT pull in any external
# shared library — everything is compiled in and the .so has no non-system DT_NEEDED.
add_library(specter_zygisk SHARED main.cpp And64InlineHook.cpp)

target_compile_options(specter_zygisk PRIVATE -Wall)
# The version script is the single source of exported symbols: the two Zygisk entry points are global,
# everything else is local.
target_link_options(specter_zygisk PRIVATE
        -Wl,--gc-sections
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map)
target_link_libraries(specter_zygisk log)
