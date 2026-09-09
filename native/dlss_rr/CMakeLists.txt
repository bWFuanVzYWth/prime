# Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

cmake_minimum_required(VERSION 3.24)
project(prime_dlss_rr LANGUAGES CXX)

set(DLSS_SDK_DIR "" CACHE PATH "Path to the NVIDIA DLSS SDK")
if(NOT DLSS_SDK_DIR AND DEFINED ENV{DLSS_SDK_DIR})
    set(DLSS_SDK_DIR "$ENV{DLSS_SDK_DIR}")
endif()

if(NOT EXISTS "${DLSS_SDK_DIR}/include/nvsdk_ngx_vk.h")
    message(FATAL_ERROR "Set DLSS_SDK_DIR to the NVIDIA DLSS SDK root")
endif()

include(FetchContent)
FetchContent_Declare(
    vulkan_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
    GIT_TAG v1.3.296
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(vulkan_headers)

add_library(prime_dlss_rr SHARED prime_dlss_rr_bridge.cpp)
target_compile_features(prime_dlss_rr PRIVATE cxx_std_20)
target_compile_definitions(prime_dlss_rr PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
target_include_directories(prime_dlss_rr PRIVATE
    "${DLSS_SDK_DIR}/include"
    "${vulkan_headers_SOURCE_DIR}/include")
target_link_libraries(prime_dlss_rr PRIVATE
    "${DLSS_SDK_DIR}/lib/Windows_x86_64/khr/x64/nvsdk_ngx_khr_s.lib")
set_target_properties(prime_dlss_rr PROPERTIES
    OUTPUT_NAME prime_dlss_rr
    MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

if(MSVC)
    target_compile_options(prime_dlss_rr PRIVATE /W4 /WX /permissive- /EHsc)
endif()

include(CTest)
if(BUILD_TESTING)
    add_executable(prime_dlss_rr_bridge_test prime_dlss_rr_bridge_test.cpp)
    target_compile_features(prime_dlss_rr_bridge_test PRIVATE cxx_std_20)
    target_compile_definitions(prime_dlss_rr_bridge_test PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
    target_include_directories(prime_dlss_rr_bridge_test PRIVATE
        "${DLSS_SDK_DIR}/include" "${vulkan_headers_SOURCE_DIR}/include")
    target_link_libraries(prime_dlss_rr_bridge_test PRIVATE
        "${DLSS_SDK_DIR}/lib/Windows_x86_64/khr/x64/nvsdk_ngx_khr_s.lib")
    set_target_properties(prime_dlss_rr_bridge_test PROPERTIES MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    add_test(NAME prime_dlss_rr_arguments COMMAND prime_dlss_rr_bridge_test)
endif()
