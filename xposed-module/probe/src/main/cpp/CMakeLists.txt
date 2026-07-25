cmake_minimum_required(VERSION 3.22.1)
project(probe)
add_library(probe SHARED native-probe.cpp)
# EGL + GLESv2 for the native GPU-string read (the direct-JNI path Specter's glGetString hook targets);
# log for diagnostics.
target_link_libraries(probe EGL GLESv2 android log)
