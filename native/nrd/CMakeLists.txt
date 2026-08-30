cmake_minimum_required(VERSION 3.22...3.30)

project(PrimeNrdBridge VERSION 1.0.0 LANGUAGES CXX)

if(MSVC)
    # The mod extracts a single native library from its JAR. Statically link the MSVC runtime so
    # users do not need a matching Visual C++ redistributable merely to load NRD.
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

set(NRD_SOURCE_DIR "" CACHE PATH "Path to an NRD source checkout")
if(NOT EXISTS "${NRD_SOURCE_DIR}/Include/NRD.h")
    message(FATAL_ERROR "NRD_SOURCE_DIR must point to an NRD 4.17 source checkout")
endif()
file(READ "${NRD_SOURCE_DIR}/Include/NRD.h" PRIME_NRD_VERSION_HEADER)
string(REGEX MATCH "NRD_VERSION_MAJOR ([0-9]+)" _ "${PRIME_NRD_VERSION_HEADER}")
set(PRIME_NRD_VERSION_MAJOR "${CMAKE_MATCH_1}")
string(REGEX MATCH "NRD_VERSION_MINOR ([0-9]+)" _ "${PRIME_NRD_VERSION_HEADER}")
set(PRIME_NRD_VERSION_MINOR "${CMAKE_MATCH_1}")
string(REGEX MATCH "NRD_VERSION_BUILD ([0-9]+)" _ "${PRIME_NRD_VERSION_HEADER}")
set(PRIME_NRD_VERSION_BUILD "${CMAKE_MATCH_1}")
if(NOT PRIME_NRD_VERSION_MAJOR EQUAL 4
        OR NOT PRIME_NRD_VERSION_MINOR EQUAL 17
        OR NOT PRIME_NRD_VERSION_BUILD EQUAL 4)
    message(FATAL_ERROR
        "Prime's native ABI and shader contract require NRD 4.17.4, found "
        "${PRIME_NRD_VERSION_MAJOR}.${PRIME_NRD_VERSION_MINOR}.${PRIME_NRD_VERSION_BUILD}")
endif()

set(NRD_STATIC_LIBRARY ON CACHE BOOL "" FORCE)
set(NRD_NRI OFF CACHE BOOL "" FORCE)
set(NRD_EMBEDS_SPIRV_SHADERS ON CACHE BOOL "" FORCE)
set(NRD_EMBEDS_DXIL_SHADERS OFF CACHE BOOL "" FORCE)
set(NRD_EMBEDS_DXBC_SHADERS OFF CACHE BOOL "" FORCE)
set(NRD_SUPPORTS_VIEWPORT_OFFSET OFF CACHE BOOL "" FORCE)
set(NRD_SUPPORTS_CHECKERBOARD OFF CACHE BOOL "" FORCE)
set(NRD_SUPPORTS_HISTORY_CONFIDENCE OFF CACHE BOOL "" FORCE)
set(NRD_SUPPORTS_DISOCCLUSION_THRESHOLD_MIX OFF CACHE BOOL "" FORCE)
set(NRD_SUPPORTS_ANTIFIREFLY ON CACHE BOOL "" FORCE)
set(NRD_SUPPORTS_QUAD_INTRINSICS OFF CACHE BOOL "" FORCE)
# Direct signed components. NRD names this mode RGBA16_SNORM, but its contract explicitly accepts
# RGBA32_SFLOAT and performs no component remapping before normalization.
set(NRD_NORMAL_ENCODING 4 CACHE STRING "" FORCE)
set(NRD_ROUGHNESS_ENCODING 1 CACHE STRING "" FORCE)

add_subdirectory("${NRD_SOURCE_DIR}" nrd EXCLUDE_FROM_ALL)

add_library(prime_nrd SHARED prime_nrd_bridge.cpp)
target_compile_features(prime_nrd PRIVATE cxx_std_17)
target_compile_definitions(prime_nrd PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
target_include_directories(prime_nrd PRIVATE "${NRD_SOURCE_DIR}/Include")
target_link_libraries(prime_nrd PRIVATE NRD)
if(MSVC)
    target_compile_options(prime_nrd PRIVATE /W4 /WX /permissive-)
endif()

set_target_properties(prime_nrd PROPERTIES
    PREFIX ""
    OUTPUT_NAME "prime_nrd"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
)
