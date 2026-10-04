cmake_minimum_required(VERSION 3.20)
project(ojp_odbc_client VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Protobuf REQUIRED)
find_package(gRPC CONFIG REQUIRED)
find_package(ODBC REQUIRED)

set(OJP_PROTO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../ojp-grpc-commons/src/main/proto")
set(GOOGLEAPIS_PROTO_DIR "" CACHE PATH "Root directory containing google/type/date.proto")
if(NOT EXISTS "${GOOGLEAPIS_PROTO_DIR}/google/type/date.proto" OR
   NOT EXISTS "${GOOGLEAPIS_PROTO_DIR}/google/type/timeofday.proto")
    message(FATAL_ERROR
        "Set GOOGLEAPIS_PROTO_DIR to a googleapis checkout containing google/type/date.proto")
endif()

set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GENERATED_DIR}")
set(PROTO_FILE "${OJP_PROTO_DIR}/StatementService.proto")
set(GOOGLE_TYPE_DATE "${GOOGLEAPIS_PROTO_DIR}/google/type/date.proto")
set(GOOGLE_TYPE_TIME "${GOOGLEAPIS_PROTO_DIR}/google/type/timeofday.proto")
set(PROTO_SRCS
    "${GENERATED_DIR}/StatementService.pb.cc"
    "${GENERATED_DIR}/StatementService.grpc.pb.cc"
    "${GENERATED_DIR}/google/type/date.pb.cc"
    "${GENERATED_DIR}/google/type/timeofday.pb.cc")
set(PROTO_HDRS
    "${GENERATED_DIR}/StatementService.pb.h"
    "${GENERATED_DIR}/StatementService.grpc.pb.h"
    "${GENERATED_DIR}/google/type/date.pb.h"
    "${GENERATED_DIR}/google/type/timeofday.pb.h")
set(PROTO_INCLUDE_ARGS)
foreach(PROTO_INCLUDE_DIR IN LISTS Protobuf_INCLUDE_DIRS)
    list(APPEND PROTO_INCLUDE_ARGS "--proto_path=${PROTO_INCLUDE_DIR}")
endforeach()

add_custom_command(
    OUTPUT ${PROTO_SRCS} ${PROTO_HDRS}
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${GENERATED_DIR}/google/type"
    COMMAND $<TARGET_FILE:protobuf::protoc>
        --proto_path="${OJP_PROTO_DIR}"
        --proto_path="${GOOGLEAPIS_PROTO_DIR}"
        ${PROTO_INCLUDE_ARGS}
        --cpp_out="${GENERATED_DIR}"
        "${PROTO_FILE}" "${GOOGLE_TYPE_DATE}" "${GOOGLE_TYPE_TIME}"
    COMMAND $<TARGET_FILE:protobuf::protoc>
        --proto_path="${OJP_PROTO_DIR}"
        --proto_path="${GOOGLEAPIS_PROTO_DIR}"
        ${PROTO_INCLUDE_ARGS}
        --grpc_out="${GENERATED_DIR}"
        --plugin=protoc-gen-grpc=$<TARGET_FILE:gRPC::grpc_cpp_plugin>
        "${PROTO_FILE}"
    DEPENDS "${PROTO_FILE}" "${GOOGLE_TYPE_DATE}" "${GOOGLE_TYPE_TIME}"
    VERBATIM)

add_library(ojp_odbc SHARED
    src/ojp_odbc_driver.cpp
    ${PROTO_SRCS}
    ${PROTO_HDRS})
target_include_directories(ojp_odbc PRIVATE "${GENERATED_DIR}")
target_include_directories(ojp_odbc PRIVATE ${ODBC_INCLUDE_DIRS})
target_link_libraries(ojp_odbc PRIVATE
    gRPC::grpc++
    protobuf::libprotobuf)
set_target_properties(ojp_odbc PROPERTIES
    OUTPUT_NAME "ojp_odbc"
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN YES)

include(CTest)
if(BUILD_TESTING)
    add_executable(ojp_odbc_h2_l1_integration_test
        tests/h2_l1_integration_test.cpp)
    target_link_libraries(ojp_odbc_h2_l1_integration_test PRIVATE ODBC::ODBC)
    target_compile_definitions(ojp_odbc_h2_l1_integration_test PRIVATE
        "OJP_ODBC_TEST_CSV=\"${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l1_connection.csv\"")
    add_dependencies(ojp_odbc_h2_l1_integration_test ojp_odbc)

    file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/odbc")
    file(GENERATE
        OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/odbc/odbcinst.ini"
        CONTENT "[OJP]\nDriver=$<TARGET_FILE:ojp_odbc>\n")
    add_test(NAME OjpOdbcH2L1Integration
        COMMAND ojp_odbc_h2_l1_integration_test)
    set_tests_properties(OjpOdbcH2L1Integration PROPERTIES
        SKIP_RETURN_CODE 77
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc")
endif()
