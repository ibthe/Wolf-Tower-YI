cmake_minimum_required(VERSION 3.18)
project(ciadpi C)

# репозиторий: wolf-tower/android/app/src/main/cpp -> wolf-tower/third_party/byedpi
set(BYEDPI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/byedpi)

file(GLOB BYEDPI_SRC ${BYEDPI_DIR}/*.c)
list(FILTER BYEDPI_SRC EXCLUDE REGEX ".*win_service\\.c$")

add_executable(ciadpi ${BYEDPI_SRC})
target_include_directories(ciadpi PRIVATE ${BYEDPI_DIR})

# Gradle кладёт в APK только lib*.so, поэтому исполняемый файл называем libciadpi.so
set_target_properties(ciadpi PROPERTIES OUTPUT_NAME "libciadpi" SUFFIX ".so")
