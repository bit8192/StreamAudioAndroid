cmake_minimum_required(VERSION 3.22.1)

project(oboe_player)

set(CMAKE_CXX_STANDARD 17)

add_library(oboe_player SHARED
    oboe_player.cpp
)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(oboe_player
    PRIVATE
    oboe::oboe
    log
)
