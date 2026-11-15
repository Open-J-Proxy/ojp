#include "connection_suite.h"
#include "fake_server_process.h"

#include <algorithm>
#include <cstdint>

namespace {

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void success(SQLRETURN result) {
    require(SQL_SUCCEEDED(result), "ODBC call failed: " + std::to_string(result));
}

SQLRETURN set_mode(SQLHDBC connection, SQLULEN mode, bool wide) {
    const auto value = reinterpret_cast<SQLPOINTER>(static_cast<std::uintptr_t>(mode));
    return wide ? SQLSetConnectAttrW(connection, SQL_ATTR_AUTOCOMMIT, value, SQL_IS_UINTEGER)
                : SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT, value, SQL_IS_UINTEGER);
}

void check_mode(SQLHDBC connection, SQLUINTEGER expected) {
    for (const bool wide : {false, true}) {
        struct {
            SQLUINTEGER value = 99;
            SQLUINTEGER sentinel = 0x12345678;
        } output;
        SQLINTEGER length = -1;
        success(wide ? SQLGetConnectAttrW(connection, SQL_ATTR_AUTOCOMMIT, &output.value,
                                          sizeof(output.value), &length)
                     : SQLGetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT, &output.value,
                                         sizeof(output.value), &length));
        require(output.value == expected, "Unexpected autocommit state");
        require(output.sentinel == 0x12345678 && length == sizeof(SQLUINTEGER),
                "Autocommit getter must write exactly one SQLUINTEGER");
    }
}

void diagnostic(SQLRETURN result, SQLHDBC connection, const std::string& expected,
                SQLINTEGER expected_native = 0) {
    require(result == SQL_ERROR, "Expected SQL_ERROR");
    SQLCHAR state[6] = {};
    SQLCHAR message[512] = {};
    SQLINTEGER native = -1;
    success(SQLGetDiagRec(SQL_HANDLE_DBC, connection, 1, state, &native, message,
                          sizeof(message), nullptr));
    require(reinterpret_cast<const char*>(state) == expected, "Incorrect SQLSTATE");
    require(native == expected_native && message[0] != 0, "Missing server diagnostic detail");
}

void run(const std::string& executable, const std::string& database, bool preconnect) {
    FakeServerProcess server(executable);
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment));
    success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                          reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), 0));
    success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection));
    check_mode(connection, SQL_AUTOCOMMIT_ON);
    require(set_mode(SQL_NULL_HDBC, SQL_AUTOCOMMIT_OFF, true) == SQL_INVALID_HANDLE,
            "Unicode setter must reject null connection handles");
    require(SQLGetConnectAttr(SQL_NULL_HDBC, SQL_ATTR_AUTOCOMMIT, nullptr, 0, nullptr) ==
                SQL_INVALID_HANDLE, "ANSI getter must reject null connection handles");
    for (const bool wide : {false, true}) {
        diagnostic(set_mode(connection, 42, wide), connection, "HY024");
        check_mode(connection, SQL_AUTOCOMMIT_ON);
        diagnostic(wide ? SQLGetConnectAttrW(connection, SQL_ATTR_AUTOCOMMIT, nullptr, 0, nullptr)
                        : SQLGetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT, nullptr, 0, nullptr),
                   connection, "HY009");
    }
    if (preconnect) {
        success(set_mode(connection, SQL_AUTOCOMMIT_OFF, true));
        check_mode(connection, SQL_AUTOCOMMIT_OFF);
    }
    std::string text = server.connection_string(database);
    success(SQLDriverConnect(connection, nullptr, reinterpret_cast<SQLCHAR*>(text.data()),
                              SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT));
    check_mode(connection, preconnect ? SQL_AUTOCOMMIT_OFF : SQL_AUTOCOMMIT_ON);

    const bool failures = database.find("autocommit_failures") != std::string::npos;
    const bool transport = database.find("autocommit_transport") != std::string::npos;
    if (failures) {
        diagnostic(set_mode(connection, SQL_AUTOCOMMIT_OFF, true), connection, "40001", 701);
        check_mode(connection, SQL_AUTOCOMMIT_ON);
        success(set_mode(connection, SQL_AUTOCOMMIT_OFF, false));
        diagnostic(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT), connection, "40001", 702);
        check_mode(connection, SQL_AUTOCOMMIT_OFF);
        diagnostic(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_ROLLBACK), connection, "40001", 703);
        check_mode(connection, SQL_AUTOCOMMIT_OFF);
        diagnostic(set_mode(connection, SQL_AUTOCOMMIT_ON, false), connection, "40001", 705);
        check_mode(connection, SQL_AUTOCOMMIT_OFF);
        success(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_ROLLBACK));
        success(set_mode(connection, SQL_AUTOCOMMIT_ON, true));
    } else if (transport) {
        success(set_mode(connection, SQL_AUTOCOMMIT_OFF, true));
        diagnostic(set_mode(connection, SQL_AUTOCOMMIT_ON, true), connection, "08S01");
        check_mode(connection, SQL_AUTOCOMMIT_OFF);
    } else {
        for (int cycle = 0; cycle < 4; ++cycle) {
            success(set_mode(connection, SQL_AUTOCOMMIT_OFF, cycle % 2 == 0));
            success(set_mode(connection, SQL_AUTOCOMMIT_OFF, cycle % 2 != 0));
            check_mode(connection, SQL_AUTOCOMMIT_OFF);
            diagnostic(set_mode(connection, 42, true), connection, "HY024");
            check_mode(connection, SQL_AUTOCOMMIT_OFF);
            success(SQLEndTran(SQL_HANDLE_DBC, connection,
                                cycle % 2 == 0 ? SQL_COMMIT : SQL_ROLLBACK));
            check_mode(connection, SQL_AUTOCOMMIT_OFF);
            success(set_mode(connection, SQL_AUTOCOMMIT_ON, cycle % 2 != 0));
            success(set_mode(connection, SQL_AUTOCOMMIT_ON, cycle % 2 == 0));
            check_mode(connection, SQL_AUTOCOMMIT_ON);
            success(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT));
        }
    }
    success(SQLDisconnect(connection));
    check_mode(connection, SQL_AUTOCOMMIT_ON);
    diagnostic(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT), connection, "08003");
    success(SQLFreeHandle(SQL_HANDLE_DBC, connection));
    success(SQLFreeHandle(SQL_HANDLE_ENV, environment));
    const auto calls = server.finish();
    const auto count = [&](const std::string& prefix) {
        return std::count_if(calls.begin(), calls.end(), [&](const std::string& line) {
            return line.rfind(prefix, 0) == 0;
        });
    };
    require(count("connect ") == 1 && count("terminateSession") == 1,
            "Mode changes must not reconnect or terminate the session");
    require(count("startTransaction ") == (failures ? 2 : transport ? 1 : 4),
            "Repeated OFF must not restart transactions");
    require(count("setAutoCommit autocommit-session") == (failures ? 2 : transport ? 1 : 4),
            "Enabling autocommit must use the existing server session without replay");
    require(count("commitTransaction autocommit-session") == (failures ? 1 : transport ? 0 : 2),
            "Unexpected commit: switching OFF or reapplying a mode must not commit");
    require(count("rollbackTransaction autocommit-session") == (failures ? 2 : transport ? 0 : 2),
            "Rollback must use the original transaction owner");
}

}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 2, "Usage: ojp_odbc_autocommit_test <fake server executable>");
        run(argv[1], "jdbc:h2:mem:autocommit_normal", false);
        run(argv[1], "jdbc:h2:mem:autocommit_preconnect", true);
        run(argv[1], "jdbc:h2:mem:autocommit_failures", false);
        run(argv[1], "jdbc:h2:mem:autocommit_transport", false);
        std::cout << "Autocommit unit tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
