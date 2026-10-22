cmake_minimum_required(VERSION 3.22.1)
project(switchmcp C)

# PORTMUX_LIBS_DIR / PORTMUX_INCLUDE_DIR are passed from Gradle and point at the
# prebuilt artifacts:
#   <PORTMUX_LIBS_DIR>/<abi>/{libportmux.a,libhev-task-system.a}
#   <PORTMUX_INCLUDE_DIR>/portmux.h
if(NOT DEFINED PORTMUX_LIBS_DIR)
    message(FATAL_ERROR "PORTMUX_LIBS_DIR not set (Gradle should pass it)")
endif()

set(ABI_LIB_DIR "${PORTMUX_LIBS_DIR}/${ANDROID_ABI}")

add_library(portmux STATIC IMPORTED)
set_target_properties(portmux PROPERTIES
    IMPORTED_LOCATION "${ABI_LIB_DIR}/libportmux.a")

add_library(hevtask STATIC IMPORTED)
set_target_properties(hevtask PROPERTIES
    IMPORTED_LOCATION "${ABI_LIB_DIR}/libhev-task-system.a")

# JNI bridge shared library loaded by the app (System.loadLibrary("switchmcp")).
add_library(switchmcp SHARED
    switch-mcp-jni.c)

target_include_directories(switchmcp PRIVATE
    "${PORTMUX_INCLUDE_DIR}")

# Link order matters for static archives: portmux depends on hevtask.
# libandroid provides multinetwork APIs (future egress pinning); log for logging.
target_link_libraries(switchmcp
    portmux
    hevtask
    android
    log)
