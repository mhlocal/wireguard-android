cmake_minimum_required(VERSION 3.10.2)

project("api-keys")

add_library(
        api-keys
        SHARED
        api-keys.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        api-keys
        ${log-lib})
