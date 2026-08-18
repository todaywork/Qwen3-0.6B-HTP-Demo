cmake_minimum_required(VERSION 3.22.1)
project(qwen3genie)

set(QAIRT_ROOT "E:/QualComm/AIStack/QAIRT/2.45.0.260326")

add_library(qwen3genie SHARED qwen3_genie_jni.cpp)

target_include_directories(qwen3genie PRIVATE
        ${CMAKE_SOURCE_DIR}/qairt/include)

add_library(Genie SHARED IMPORTED)
set_target_properties(Genie PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libGenie.so)

target_link_libraries(qwen3genie
        Genie
        android
        log)
