cmake_minimum_required(VERSION 3.22.1)
project(pixelmusic_discord CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Discord Social SDK native lib, exported via Prefab from app/libs/discord_partner_sdk.aar.
find_package(discord_partner_sdk REQUIRED CONFIG)

add_library(pixelmusic_discord SHARED discord_bridge.cpp)

target_include_directories(pixelmusic_discord PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/discord/include)
target_compile_options(pixelmusic_discord PRIVATE -Os -fvisibility=hidden -Wall)
target_link_options(pixelmusic_discord PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

target_link_libraries(pixelmusic_discord
        discord_partner_sdk::discord_partner_sdk
        log)
