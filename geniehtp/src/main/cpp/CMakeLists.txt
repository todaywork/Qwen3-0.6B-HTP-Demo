cmake_minimum_required(VERSION 3.22.1)
project(qwen3genie)

if(NOT HTP_ARCH MATCHES "^(68|73)$")
    message(FATAL_ERROR "HTP_ARCH must be 68 or 73; select a Gradle flavor")
endif()
if(NOT QNN_VER MATCHES "^(234|246)$")
    message(FATAL_ERROR "QNN_VER must be 234 or 246; select a Gradle flavor")
endif()
set(RUNTIME_DIR "${CMAKE_SOURCE_DIR}/../../v${HTP_ARCH}Qnn${QNN_VER}/jniLibs/${ANDROID_ABI}")

add_library(qwen3genie SHARED qwen3_genie_jni.cpp)
target_compile_definitions(qwen3genie PRIVATE HTP_ARCH=${HTP_ARCH} QNN_VER=${QNN_VER})

target_include_directories(qwen3genie PRIVATE
        ${CMAKE_SOURCE_DIR}/qairt/include)

add_library(Genie SHARED IMPORTED)
set_target_properties(Genie PROPERTIES
        IMPORTED_LOCATION ${RUNTIME_DIR}/libGenie.so)

target_link_libraries(qwen3genie
        Genie
        android
        log
        dl)
