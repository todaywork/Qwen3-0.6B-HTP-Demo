cmake_minimum_required(VERSION 3.22.1)
project(npu_diagnostics)
set(QAIRT_ROOT "" CACHE PATH "QAIRT SDK root")
if (NOT EXISTS "${QAIRT_ROOT}/include/QNN/QnnInterface.h")
    message(FATAL_ERROR "Missing QAIRT QNN headers under ${QAIRT_ROOT}/include")
endif()
add_library(npu_diagnostics SHARED qnn_runtime_probe.cpp)
target_include_directories(npu_diagnostics PRIVATE
        "${QAIRT_ROOT}/include"
        "${QAIRT_ROOT}/include/QNN")
target_link_libraries(npu_diagnostics android log dl)
