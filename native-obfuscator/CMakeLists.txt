cmake_minimum_required(VERSION 3.20)
project(cloudlicense_obfuscator LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED)

add_library(cloudlicense_obfuscator SHARED src/native_class_transformer.cpp)
target_include_directories(cloudlicense_obfuscator PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(cloudlicense_obfuscator PRIVATE ${JNI_LIBRARIES})

if(MSVC)
    target_compile_options(cloudlicense_obfuscator PRIVATE /W4 /permissive-)
else()
    target_compile_options(cloudlicense_obfuscator PRIVATE -Wall -Wextra -Wpedantic)
endif()

