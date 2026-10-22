cmake_minimum_required(VERSION 3.20)
project(ojp_client_cpp_odbc VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Protobuf REQUIRED)
find_package(gRPC CONFIG REQUIRED)
find_package(ODBC REQUIRED)
find_package(Threads REQUIRED)

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
        "--proto_path=${OJP_PROTO_DIR}"
        "--proto_path=${GOOGLEAPIS_PROTO_DIR}"
        ${PROTO_INCLUDE_ARGS}
        "--cpp_out=${GENERATED_DIR}"
        "${PROTO_FILE}" "${GOOGLE_TYPE_DATE}" "${GOOGLE_TYPE_TIME}"
    COMMAND $<TARGET_FILE:protobuf::protoc>
        "--proto_path=${OJP_PROTO_DIR}"
        "--proto_path=${GOOGLEAPIS_PROTO_DIR}"
        ${PROTO_INCLUDE_ARGS}
        "--grpc_out=${GENERATED_DIR}"
        "--plugin=protoc-gen-grpc=$<TARGET_FILE:gRPC::grpc_cpp_plugin>"
        "${PROTO_FILE}"
    DEPENDS "${PROTO_FILE}" "${GOOGLE_TYPE_DATE}" "${GOOGLE_TYPE_TIME}"
    VERBATIM)

add_library(ojp_odbc SHARED
    src/ojp_odbc_driver.cpp
    src/ojp_odbc_dsn.cpp
    ${PROTO_SRCS}
    ${PROTO_HDRS})
target_include_directories(ojp_odbc PRIVATE "${GENERATED_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_include_directories(ojp_odbc PRIVATE ${ODBC_INCLUDE_DIRS})
target_compile_definitions(ojp_odbc PRIVATE OJP_ODBC_BUILD_DLL)
target_link_libraries(ojp_odbc PRIVATE
    gRPC::grpc++
    protobuf::libprotobuf)

# DSN resolution reads odbc.ini/registry through the ODBC installer library.
if(WIN32)
    target_compile_definitions(ojp_odbc PRIVATE OJP_ODBC_HAVE_ODBCINST)
    target_link_libraries(ojp_odbc PRIVATE odbccp32 legacy_stdio_definitions)
else()
    find_library(OJP_ODBCINST_LIBRARY NAMES odbcinst iodbcinst)
    find_path(OJP_ODBCINST_INCLUDE_DIR odbcinst.h HINTS ${ODBC_INCLUDE_DIRS})
    if(OJP_ODBCINST_LIBRARY AND OJP_ODBCINST_INCLUDE_DIR)
        target_compile_definitions(ojp_odbc PRIVATE OJP_ODBC_HAVE_ODBCINST)
        target_include_directories(ojp_odbc PRIVATE "${OJP_ODBCINST_INCLUDE_DIR}")
        target_link_libraries(ojp_odbc PRIVATE "${OJP_ODBCINST_LIBRARY}")
    else()
        message(WARNING "odbcinst not found: DSN connections will be unavailable")
    endif()
endif()
set_target_properties(ojp_odbc PROPERTIES
    OUTPUT_NAME "ojp_odbc"
    WINDOWS_EXPORT_ALL_SYMBOLS ON
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN YES)

include(GNUInstallDirs)
install(TARGETS ojp_odbc
    RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
    LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
    ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
install(FILES include/ojp_odbc_xa.h DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}")

option(OJP_ODBC_BUILD_SETUP "Build the Windows ODBC DSN setup DLL" ON)
if(WIN32 AND OJP_ODBC_BUILD_SETUP)
    add_library(ojp_odbc_setup SHARED
        src/setup/ojp_odbc_setup.cpp
        src/setup/ojp_odbc_setup.rc
        src/setup/ojp_odbc_setup.def
        src/setup/ojp_odbc_setup.manifest
        src/ojp_odbc_dsn.cpp)
    target_include_directories(ojp_odbc_setup PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
    target_compile_definitions(ojp_odbc_setup PRIVATE
        OJP_ODBC_HAVE_ODBCINST UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN
        ISOLATION_AWARE_ENABLED=1)
    target_link_libraries(ojp_odbc_setup PRIVATE
        odbccp32 legacy_stdio_definitions odbc32 comctl32 comdlg32 user32)
    install(TARGETS ojp_odbc_setup
        RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
        LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
        ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
endif()

include(CTest)
if(BUILD_TESTING)
    add_executable(ojp_odbc_dsn_test tests/dsn_test.cpp src/ojp_odbc_dsn.cpp)
    target_include_directories(ojp_odbc_dsn_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
    add_test(NAME OjpOdbcDsn COMMAND ojp_odbc_dsn_test)

    add_executable(ojp_odbc_parameter_test tests/parameter_test.cpp)
    target_include_directories(ojp_odbc_parameter_test PRIVATE "${GENERATED_DIR}" ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_parameter_test PRIVATE
        ojp_odbc gRPC::grpc++ protobuf::libprotobuf)
    add_test(NAME OjpOdbcParameters COMMAND ojp_odbc_parameter_test)

    # The fake server runs in its own process: on Windows gRPC is static and two copies
    # (test executable and ojp_odbc.dll) cannot share one process.
    add_executable(ojp_odbc_fake_server tests/fake_statement_server.cpp ${PROTO_SRCS})
    target_include_directories(ojp_odbc_fake_server PRIVATE "${GENERATED_DIR}")
    target_link_libraries(ojp_odbc_fake_server PRIVATE gRPC::grpc++ protobuf::libprotobuf)

    add_executable(ojp_odbc_cancel_test tests/cancel_test.cpp)
    target_include_directories(ojp_odbc_cancel_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_cancel_test PRIVATE ojp_odbc)
    add_test(NAME OjpOdbcCancel
        COMMAND ojp_odbc_cancel_test "$<TARGET_FILE:ojp_odbc_fake_server>")
    set_tests_properties(OjpOdbcCancel PROPERTIES TIMEOUT 120)

    add_executable(ojp_odbc_batch_test tests/batch_test.cpp)
    target_include_directories(ojp_odbc_batch_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_batch_test PRIVATE ojp_odbc)
    add_test(NAME OjpOdbcBatch
        COMMAND ojp_odbc_batch_test "$<TARGET_FILE:ojp_odbc_fake_server>")
    set_tests_properties(OjpOdbcBatch PROPERTIES TIMEOUT 120)

    add_executable(ojp_odbc_sql_routing_test tests/sql_routing_test.cpp)
    target_include_directories(ojp_odbc_sql_routing_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_sql_routing_test PRIVATE ojp_odbc)
    add_test(NAME OjpOdbcSqlRouting
        COMMAND ojp_odbc_sql_routing_test "$<TARGET_FILE:ojp_odbc_fake_server>")
    set_tests_properties(OjpOdbcSqlRouting PROPERTIES TIMEOUT 120)

    add_executable(ojp_odbc_procedure_metadata_test tests/procedure_metadata_test.cpp)
    target_include_directories(ojp_odbc_procedure_metadata_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_procedure_metadata_test PRIVATE ojp_odbc)
    add_test(NAME OjpOdbcProcedureMetadata
        COMMAND ojp_odbc_procedure_metadata_test "$<TARGET_FILE:ojp_odbc_fake_server>")
    set_tests_properties(OjpOdbcProcedureMetadata PROPERTIES TIMEOUT 120)

    add_executable(ojp_odbc_tables_metadata_test tests/tables_metadata_test.cpp)
    target_include_directories(ojp_odbc_tables_metadata_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_tables_metadata_test PRIVATE ojp_odbc)
    add_test(NAME OjpOdbcTablesMetadata
        COMMAND ojp_odbc_tables_metadata_test "$<TARGET_FILE:ojp_odbc_fake_server>")
    set_tests_properties(OjpOdbcTablesMetadata PROPERTIES TIMEOUT 120)

    add_executable(ojp_odbc_driver_api_test tests/driver_api_test.cpp)
    target_include_directories(ojp_odbc_driver_api_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_driver_api_test PRIVATE ojp_odbc)
    set(OJP_TEST_DRIVER_API_LOG "${CMAKE_CURRENT_BINARY_DIR}/ojp_odbc_driver_api_test.log")
    add_test(NAME OjpOdbcDriverApi
        COMMAND ojp_odbc_driver_api_test "$<TARGET_FILE:ojp_odbc_fake_server>"
            "${OJP_TEST_DRIVER_API_LOG}")
    set_tests_properties(OjpOdbcDriverApi PROPERTIES TIMEOUT 120
        ENVIRONMENT "OJP_ODBC_LOG_FILE=${OJP_TEST_DRIVER_API_LOG};OJP_ODBC_LOG_LEVEL=debug")

    find_program(OJP_TEST_OPENSSL openssl)
    if(OJP_TEST_OPENSSL)
        add_executable(ojp_odbc_tls_test tests/tls_test.cpp)
        target_include_directories(ojp_odbc_tls_test PRIVATE "${GENERATED_DIR}" ${ODBC_INCLUDE_DIRS})
        target_link_libraries(ojp_odbc_tls_test PRIVATE
            ojp_odbc gRPC::grpc++ protobuf::libprotobuf)
        add_test(NAME OjpOdbcTls COMMAND ojp_odbc_tls_test "${OJP_TEST_OPENSSL}")
        set_tests_properties(OjpOdbcTls PROPERTIES TIMEOUT 120)
    endif()

    add_executable(ojp_odbc_l1_integration_test
        tests/l1_integration_test.cpp)
    target_link_libraries(ojp_odbc_l1_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_l1_integration_test ojp_odbc)

    file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/odbc")
    file(GENERATE
        OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/odbc/odbcinst.ini"
        CONTENT "[OJP]\nDriver=$<TARGET_FILE:ojp_odbc>\n")
    add_test(NAME OjpOdbcH2L1Integration
        COMMAND ojp_odbc_l1_integration_test
            "H2"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l1_connection.csv"
            "OJP_TEST_H2"
            "OJP_TEST_H2_ADDR"
            "42001")
    add_executable(ojp_odbc_h2_l2_integration_test
        tests/h2_l2_integration_test.cpp)
    target_link_libraries(ojp_odbc_h2_l2_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_h2_l2_integration_test ojp_odbc)
    add_test(NAME OjpOdbcH2L2Integration
        COMMAND ojp_odbc_h2_l2_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l1_connection.csv"
            "OJP_TEST_H2"
            "OJP_TEST_H2_ADDR")
    add_executable(ojp_odbc_h2_l3_integration_test
        tests/h2_l3_integration_test.cpp)
    target_link_libraries(ojp_odbc_h2_l3_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_h2_l3_integration_test ojp_odbc)
    add_test(NAME OjpOdbcH2L3Integration
        COMMAND ojp_odbc_h2_l3_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l1_connection.csv"
            "OJP_TEST_H2"
            "OJP_TEST_H2_ADDR")
    add_executable(ojp_odbc_h2_l4_integration_test
        tests/h2_l4_integration_test.cpp)
    target_link_libraries(ojp_odbc_h2_l4_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_h2_l4_integration_test ojp_odbc)
    add_test(NAME OjpOdbcH2L4Integration
        COMMAND ojp_odbc_h2_l4_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l1_connection.csv"
            "OJP_TEST_H2"
            "OJP_TEST_H2_ADDR")
    add_executable(ojp_odbc_h2_l5_integration_test
        tests/h2_l5_integration_test.cpp)
    target_link_libraries(ojp_odbc_h2_l5_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_h2_l5_integration_test ojp_odbc)
    add_test(NAME OjpOdbcH2L5Integration
        COMMAND ojp_odbc_h2_l5_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l1_connection.csv"
            "OJP_TEST_H2"
            "OJP_TEST_H2_ADDR")
    add_executable(ojp_odbc_h2_l6_integration_test
        tests/h2_l6_integration_test.cpp)
    target_link_libraries(ojp_odbc_h2_l6_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_h2_l6_integration_test ojp_odbc)
    add_test(NAME OjpOdbcH2L6Integration
        COMMAND ojp_odbc_h2_l6_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l1_connection.csv"
            "OJP_TEST_H2"
            "OJP_TEST_H2_ADDR")
    add_executable(ojp_odbc_h2_l7_integration_test
        tests/h2_l7_integration_test.cpp)
    target_link_libraries(ojp_odbc_h2_l7_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_h2_l7_integration_test ojp_odbc)
    add_test(NAME OjpOdbcH2L7Integration
        COMMAND ojp_odbc_h2_l7_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l7_connection.csv"
            "OJP_TEST_H2_L7")
    add_executable(ojp_odbc_h2_l8_integration_test
        tests/h2_l8_integration_test.cpp)
    target_link_libraries(ojp_odbc_h2_l8_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_h2_l8_integration_test ojp_odbc)
    add_test(NAME OjpOdbcH2L8Integration
        COMMAND ojp_odbc_h2_l8_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l7_connection.csv"
            "OJP_TEST_H2_L8"
            "OJP_TEST_H2_L8_ADDRS")
    add_executable(ojp_odbc_h2_l9_integration_test
        tests/h2_l9_integration_test.cpp)
    target_include_directories(ojp_odbc_h2_l9_integration_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_include_directories(ojp_odbc_h2_l9_integration_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_h2_l9_integration_test PRIVATE ojp_odbc ${CMAKE_DL_LIBS})
    add_dependencies(ojp_odbc_h2_l9_integration_test ojp_odbc)
    add_test(NAME OjpOdbcH2L9Integration
        COMMAND ojp_odbc_h2_l9_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/h2_l7_connection.csv"
            "OJP_TEST_H2_L9"
            "OJP_TEST_H2_L9_ADDRS"
            "OJP_TEST_H2_L9_TARGET_PID_FILE"
            "$<TARGET_FILE:ojp_odbc>")
    add_test(NAME OjpOdbcPostgreSqlL1Integration
        COMMAND ojp_odbc_l1_integration_test
            "PostgreSQL"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL"
            "OJP_TEST_POSTGRESQL_ADDR"
            "42601")
    add_executable(ojp_odbc_postgresql_l2_integration_test
        tests/postgresql_l2_integration_test.cpp)
    target_link_libraries(ojp_odbc_postgresql_l2_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_postgresql_l2_integration_test ojp_odbc)
    add_test(NAME OjpOdbcPostgreSqlL2Integration
        COMMAND ojp_odbc_postgresql_l2_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL"
            "OJP_TEST_POSTGRESQL_ADDR")
    add_executable(ojp_odbc_postgresql_l3_integration_test
        tests/postgresql_l3_integration_test.cpp)
    target_link_libraries(ojp_odbc_postgresql_l3_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_postgresql_l3_integration_test ojp_odbc)
    add_test(NAME OjpOdbcPostgreSqlL3Integration
        COMMAND ojp_odbc_postgresql_l3_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL"
            "OJP_TEST_POSTGRESQL_ADDR")
    add_executable(ojp_odbc_postgresql_l4_integration_test
        tests/postgresql_l4_integration_test.cpp)
    target_link_libraries(ojp_odbc_postgresql_l4_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_postgresql_l4_integration_test ojp_odbc)
    add_test(NAME OjpOdbcPostgreSqlL4Integration
        COMMAND ojp_odbc_postgresql_l4_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL"
            "OJP_TEST_POSTGRESQL_ADDR")
    add_executable(ojp_odbc_postgresql_l5_integration_test
        tests/postgresql_l5_integration_test.cpp)
    target_link_libraries(ojp_odbc_postgresql_l5_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_postgresql_l5_integration_test ojp_odbc)
    add_test(NAME OjpOdbcPostgreSqlL5Integration
        COMMAND ojp_odbc_postgresql_l5_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL"
            "OJP_TEST_POSTGRESQL_ADDR")
    add_executable(ojp_odbc_postgresql_l6_integration_test
        tests/postgresql_l6_integration_test.cpp)
    target_link_libraries(ojp_odbc_postgresql_l6_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_postgresql_l6_integration_test ojp_odbc)
    add_test(NAME OjpOdbcPostgreSqlL6Integration
        COMMAND ojp_odbc_postgresql_l6_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL"
            "OJP_TEST_POSTGRESQL_ADDR")
    add_executable(ojp_odbc_postgresql_l7_integration_test
        tests/postgresql_l7_integration_test.cpp)
    target_link_libraries(ojp_odbc_postgresql_l7_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_postgresql_l7_integration_test ojp_odbc)
    add_test(NAME OjpOdbcPostgreSqlL7Integration
        COMMAND ojp_odbc_postgresql_l7_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL_L7"
            "OJP_TEST_POSTGRESQL_L7_ADDRS")
    add_executable(ojp_odbc_postgresql_l8_integration_test
        tests/postgresql_l8_integration_test.cpp)
    target_link_libraries(ojp_odbc_postgresql_l8_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_postgresql_l8_integration_test ojp_odbc)
    add_test(NAME OjpOdbcPostgreSqlL8Integration
        COMMAND ojp_odbc_postgresql_l8_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL_L8"
            "OJP_TEST_POSTGRESQL_L8_ADDRS")
    add_executable(ojp_odbc_postgresql_l9_integration_test
        tests/postgresql_l9_integration_test.cpp)
    target_include_directories(ojp_odbc_postgresql_l9_integration_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_include_directories(ojp_odbc_postgresql_l9_integration_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_postgresql_l9_integration_test PRIVATE
        ojp_odbc ${CMAKE_DL_LIBS})
    add_dependencies(ojp_odbc_postgresql_l9_integration_test ojp_odbc)
    add_test(NAME OjpOdbcPostgreSqlL9Integration
        COMMAND ojp_odbc_postgresql_l9_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL_L9"
            "OJP_TEST_POSTGRESQL_L9_ADDRS"
            "OJP_TEST_POSTGRESQL_L9_TARGET_PID_FILE"
            "$<TARGET_FILE:ojp_odbc>")
    add_executable(ojp_odbc_postgresql_l10_integration_test
        tests/postgresql_l10_integration_test.cpp)
    target_include_directories(ojp_odbc_postgresql_l10_integration_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_include_directories(ojp_odbc_postgresql_l10_integration_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_postgresql_l10_integration_test PRIVATE
        ojp_odbc ${CMAKE_DL_LIBS})
    add_dependencies(ojp_odbc_postgresql_l10_integration_test ojp_odbc)
    add_test(NAME OjpOdbcPostgreSqlL10Integration
        COMMAND ojp_odbc_postgresql_l10_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/postgresql_l1_connection.csv"
            "OJP_TEST_POSTGRESQL_L10"
            "OJP_TEST_POSTGRESQL_L10_ADDRS"
            "OJP_TEST_POSTGRESQL_L10_TARGET_PID_FILE"
            "$<TARGET_FILE:ojp_odbc>")
    add_executable(ojp_odbc_mariadb_l2_integration_test
        tests/mariadb_l2_integration_test.cpp)
    target_link_libraries(ojp_odbc_mariadb_l2_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_mariadb_l2_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL2Integration
        COMMAND ojp_odbc_mariadb_l2_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB"
            "OJP_TEST_MARIADB_ADDR")
    add_executable(ojp_odbc_mariadb_l3_integration_test
        tests/mariadb_l3_integration_test.cpp)
    target_link_libraries(ojp_odbc_mariadb_l3_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_mariadb_l3_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL3Integration
        COMMAND ojp_odbc_mariadb_l3_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB"
            "OJP_TEST_MARIADB_ADDR")
    add_executable(ojp_odbc_mariadb_l1_integration_test
        tests/mariadb_l1_integration_test.cpp)
    target_link_libraries(ojp_odbc_mariadb_l1_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_mariadb_l1_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL1Integration
        COMMAND ojp_odbc_mariadb_l1_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB"
            "OJP_TEST_MARIADB_ADDR")
    add_executable(ojp_odbc_mariadb_l4_integration_test
        tests/mariadb_l4_integration_test.cpp)
    target_link_libraries(ojp_odbc_mariadb_l4_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_mariadb_l4_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL4Integration
        COMMAND ojp_odbc_mariadb_l4_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB"
            "OJP_TEST_MARIADB_ADDR")
    add_executable(ojp_odbc_mariadb_l5_integration_test
        tests/mariadb_l5_integration_test.cpp)
    target_link_libraries(ojp_odbc_mariadb_l5_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_mariadb_l5_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL5Integration
        COMMAND ojp_odbc_mariadb_l5_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB"
            "OJP_TEST_MARIADB_ADDR")
    add_executable(ojp_odbc_mariadb_l6_integration_test
        tests/mariadb_l6_integration_test.cpp)
    target_link_libraries(ojp_odbc_mariadb_l6_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_mariadb_l6_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL6Integration
        COMMAND ojp_odbc_mariadb_l6_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB"
            "OJP_TEST_MARIADB_ADDR")
    add_executable(ojp_odbc_mariadb_l7_integration_test
        tests/mariadb_l7_integration_test.cpp)
    target_link_libraries(ojp_odbc_mariadb_l7_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_mariadb_l7_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL7Integration
        COMMAND ojp_odbc_mariadb_l7_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB_L7"
            "OJP_TEST_MARIADB_L7_ADDRS")
    add_executable(ojp_odbc_mariadb_l8_integration_test
        tests/mariadb_l8_integration_test.cpp)
    target_link_libraries(ojp_odbc_mariadb_l8_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_mariadb_l8_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL8Integration
        COMMAND ojp_odbc_mariadb_l8_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB_L8"
            "OJP_TEST_MARIADB_L8_ADDRS")
    add_executable(ojp_odbc_mariadb_l9_integration_test
        tests/mariadb_l9_integration_test.cpp)
    target_include_directories(ojp_odbc_mariadb_l9_integration_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_include_directories(ojp_odbc_mariadb_l9_integration_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_mariadb_l9_integration_test PRIVATE
        ojp_odbc ${CMAKE_DL_LIBS})
    add_dependencies(ojp_odbc_mariadb_l9_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL9Integration
        COMMAND ojp_odbc_mariadb_l9_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB_L9"
            "OJP_TEST_MARIADB_L9_ADDRS"
            "OJP_TEST_MARIADB_L9_TARGET_PID_FILE"
            "$<TARGET_FILE:ojp_odbc>")
    add_executable(ojp_odbc_mariadb_l10_integration_test
        tests/mariadb_l10_integration_test.cpp)
    target_include_directories(ojp_odbc_mariadb_l10_integration_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_include_directories(ojp_odbc_mariadb_l10_integration_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_mariadb_l10_integration_test PRIVATE
        ojp_odbc ${CMAKE_DL_LIBS})
    add_dependencies(ojp_odbc_mariadb_l10_integration_test ojp_odbc)
    add_test(NAME OjpOdbcMariaDBL10Integration
        COMMAND ojp_odbc_mariadb_l10_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/mariadb_l1_connection.csv"
            "OJP_TEST_MARIADB_L10"
            "OJP_TEST_MARIADB_L10_ADDRS"
            "OJP_TEST_MARIADB_L10_TARGET_PID_FILE"
            "$<TARGET_FILE:ojp_odbc>")
    add_test(NAME OjpOdbcSqlServerL1Integration
        COMMAND ojp_odbc_l1_integration_test
            "SQLServer"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER"
            "OJP_TEST_SQLSERVER_ADDR"
            "42S01")
    add_executable(ojp_odbc_sqlserver_l2_integration_test
        tests/sqlserver_l2_integration_test.cpp)
    target_link_libraries(ojp_odbc_sqlserver_l2_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_sqlserver_l2_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerL2Integration
        COMMAND ojp_odbc_sqlserver_l2_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER"
            "OJP_TEST_SQLSERVER_ADDR")
    add_executable(ojp_odbc_sqlserver_data_types_integration_test
        tests/sqlserver_data_types_integration_test.cpp)
    target_link_libraries(ojp_odbc_sqlserver_data_types_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_sqlserver_data_types_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerDataTypesIntegration
        COMMAND ojp_odbc_sqlserver_data_types_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER"
            "OJP_TEST_SQLSERVER_ADDR")
    add_executable(ojp_odbc_sqlserver_l3_integration_test
        tests/sqlserver_l3_integration_test.cpp)
    target_link_libraries(ojp_odbc_sqlserver_l3_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_sqlserver_l3_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerL3Integration
        COMMAND ojp_odbc_sqlserver_l3_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER"
            "OJP_TEST_SQLSERVER_ADDR")
    add_executable(ojp_odbc_sqlserver_l4_integration_test
        tests/sqlserver_l4_integration_test.cpp)
    target_link_libraries(ojp_odbc_sqlserver_l4_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_sqlserver_l4_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerL4Integration
        COMMAND ojp_odbc_sqlserver_l4_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER"
            "OJP_TEST_SQLSERVER_ADDR")
    add_executable(ojp_odbc_sqlserver_l5_integration_test
        tests/sqlserver_l5_integration_test.cpp)
    target_link_libraries(ojp_odbc_sqlserver_l5_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_sqlserver_l5_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerL5Integration
        COMMAND ojp_odbc_sqlserver_l5_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER"
            "OJP_TEST_SQLSERVER_ADDR")
    add_executable(ojp_odbc_sqlserver_l6_integration_test
        tests/sqlserver_l6_integration_test.cpp)
    target_link_libraries(ojp_odbc_sqlserver_l6_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_sqlserver_l6_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerL6Integration
        COMMAND ojp_odbc_sqlserver_l6_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER"
            "OJP_TEST_SQLSERVER_ADDR")
    add_executable(ojp_odbc_sqlserver_l7_integration_test
        tests/sqlserver_l7_integration_test.cpp)
    target_link_libraries(ojp_odbc_sqlserver_l7_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_sqlserver_l7_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerL7Integration
        COMMAND ojp_odbc_sqlserver_l7_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER"
            "OJP_TEST_SQLSERVER_L7_ADDRS")
    add_executable(ojp_odbc_sqlserver_l8_integration_test
        tests/sqlserver_l8_integration_test.cpp)
    target_link_libraries(ojp_odbc_sqlserver_l8_integration_test PRIVATE ODBC::ODBC)
    add_dependencies(ojp_odbc_sqlserver_l8_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerL8Integration
        COMMAND ojp_odbc_sqlserver_l8_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER_L8"
            "OJP_TEST_SQLSERVER_L8_ADDRS")
    add_executable(ojp_odbc_sqlserver_l9_integration_test
        tests/sqlserver_l9_integration_test.cpp)
    target_include_directories(ojp_odbc_sqlserver_l9_integration_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_include_directories(ojp_odbc_sqlserver_l9_integration_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_sqlserver_l9_integration_test PRIVATE
        ojp_odbc ${CMAKE_DL_LIBS})
    add_dependencies(ojp_odbc_sqlserver_l9_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerL9Integration
        COMMAND ojp_odbc_sqlserver_l9_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER_L9"
            "OJP_TEST_SQLSERVER_L9_ADDRS"
            "OJP_TEST_SQLSERVER_L9_TARGET_PID_FILE"
            "$<TARGET_FILE:ojp_odbc>")
    add_executable(ojp_odbc_sqlserver_l10_integration_test
        tests/sqlserver_l10_integration_test.cpp)
    target_include_directories(ojp_odbc_sqlserver_l10_integration_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_include_directories(ojp_odbc_sqlserver_l10_integration_test PRIVATE ${ODBC_INCLUDE_DIRS})
    target_link_libraries(ojp_odbc_sqlserver_l10_integration_test PRIVATE
        ojp_odbc ${CMAKE_DL_LIBS})
    add_dependencies(ojp_odbc_sqlserver_l10_integration_test ojp_odbc)
    add_test(NAME OjpOdbcSqlServerL10Integration
        COMMAND ojp_odbc_sqlserver_l10_integration_test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/testdata/sqlserver_l1_connection.csv"
            "OJP_TEST_SQLSERVER_L10"
            "OJP_TEST_SQLSERVER_L10_ADDRS"
            "OJP_TEST_SQLSERVER_L10_TARGET_PID_FILE"
            "$<TARGET_FILE:ojp_odbc>")
    set_tests_properties(
        OjpOdbcH2L1Integration
        OjpOdbcH2L2Integration
        OjpOdbcH2L3Integration
        OjpOdbcH2L4Integration
        OjpOdbcH2L5Integration
        OjpOdbcH2L6Integration
        OjpOdbcH2L7Integration
        OjpOdbcH2L8Integration
        OjpOdbcH2L9Integration
        OjpOdbcPostgreSqlL1Integration
        OjpOdbcPostgreSqlL2Integration
        OjpOdbcPostgreSqlL3Integration
        OjpOdbcPostgreSqlL4Integration
        OjpOdbcPostgreSqlL5Integration
        OjpOdbcPostgreSqlL6Integration
        OjpOdbcPostgreSqlL7Integration
        OjpOdbcPostgreSqlL8Integration
        OjpOdbcPostgreSqlL9Integration
        OjpOdbcPostgreSqlL10Integration
        OjpOdbcMariaDBL1Integration
        OjpOdbcMariaDBL2Integration
        OjpOdbcMariaDBL3Integration
        OjpOdbcMariaDBL4Integration
        OjpOdbcMariaDBL5Integration
        OjpOdbcMariaDBL6Integration
        OjpOdbcMariaDBL7Integration
        OjpOdbcMariaDBL8Integration
        OjpOdbcMariaDBL9Integration
        OjpOdbcMariaDBL10Integration
        OjpOdbcSqlServerL1Integration
        OjpOdbcSqlServerL2Integration
        OjpOdbcSqlServerL3Integration
        OjpOdbcSqlServerL4Integration
        OjpOdbcSqlServerL5Integration
        OjpOdbcSqlServerL6Integration
        OjpOdbcSqlServerL7Integration
        OjpOdbcSqlServerL8Integration
        OjpOdbcSqlServerL9Integration
        OjpOdbcSqlServerL10Integration
        PROPERTIES
        SKIP_RETURN_CODE 77
        TIMEOUT 0
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc")
    set_tests_properties(OjpOdbcH2L8Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcH2L9Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcSqlServerL8Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcPostgreSqlL8Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcMariaDBL8Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcMariaDBL9Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcMariaDBL10Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcPostgreSqlL9Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcPostgreSqlL10Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcSqlServerL9Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    set_tests_properties(OjpOdbcSqlServerL10Integration PROPERTIES
        ENVIRONMENT "ODBCSYSINI=${CMAKE_CURRENT_BINARY_DIR}/odbc;OJP_HEALTH_CHECK_INTERVAL_MS=500"
        TIMEOUT 0)
    get_property(TEST_TARGETS DIRECTORY PROPERTY BUILDSYSTEM_TARGETS)
    foreach(TEST_TARGET IN LISTS TEST_TARGETS)
        if(TEST_TARGET MATCHES "^ojp_odbc_.*_integration_test$")
            target_link_libraries(${TEST_TARGET} PRIVATE Threads::Threads)
        endif()
    endforeach()
endif()
