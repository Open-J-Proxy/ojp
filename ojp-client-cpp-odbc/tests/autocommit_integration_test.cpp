#include "connection_suite.h"

#include <algorithm>
#include <cstdint>
#include <random>
#include <set>
#include <sstream>

namespace {

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void success(SQLRETURN result, SQLSMALLINT type, SQLHANDLE handle) {
    if (SQL_SUCCEEDED(result)) {
        return;
    }
    SQLCHAR state[6] = {};
    SQLCHAR message[1024] = {};
    SQLGetDiagRec(type, handle, 1, state, nullptr, message, sizeof(message), nullptr);
    throw std::runtime_error(std::string(reinterpret_cast<char*>(state)) + ": " +
                             reinterpret_cast<char*>(message));
}

std::string brace(const std::string& value) {
    std::string output = "{";
    for (const char c : value) {
        output += c;
        if (c == '}') {
            output += c;
        }
    }
    return output + "}";
}

class Connection {
public:
    SQLHDBC handle = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;

    Connection(SQLHENV environment, const std::string& text) {
        success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &handle), SQL_HANDLE_ENV, environment);
        success(SQLDriverConnect(handle, nullptr,
                                  reinterpret_cast<SQLCHAR*>(const_cast<char*>(text.c_str())),
                                  SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                SQL_HANDLE_DBC, handle);
        success(SQLAllocHandle(SQL_HANDLE_STMT, handle, &statement), SQL_HANDLE_DBC, handle);
    }

    ~Connection() {
        if (statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, statement);
        }
        if (handle != SQL_NULL_HDBC) {
            SQLDisconnect(handle);
            SQLFreeHandle(SQL_HANDLE_DBC, handle);
        }
    }

    Connection(const Connection&) = delete;
    Connection& operator=(const Connection&) = delete;

    void execute(const std::string& sql) {
        success(SQLExecDirect(statement,
                              reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str())), SQL_NTS),
                SQL_HANDLE_STMT, statement);
    }

    SQLINTEGER scalar(const std::string& sql) {
        execute(sql);
        success(SQLFetch(statement), SQL_HANDLE_STMT, statement);
        SQLINTEGER result = -1;
        SQLLEN length = 0;
        success(SQLGetData(statement, 1, SQL_C_SLONG, &result, sizeof(result), &length),
                SQL_HANDLE_STMT, statement);
        require(length != SQL_NULL_DATA, "Unexpected NULL scalar");
        success(SQLFreeStmt(statement, SQL_CLOSE), SQL_HANDLE_STMT, statement);
        return result;
    }

    void mode(SQLUINTEGER value, bool wide) {
        const auto pointer = reinterpret_cast<SQLPOINTER>(static_cast<std::uintptr_t>(value));
        success(wide ? SQLSetConnectAttrW(handle, SQL_ATTR_AUTOCOMMIT, pointer, SQL_IS_UINTEGER)
                     : SQLSetConnectAttr(handle, SQL_ATTR_AUTOCOMMIT, pointer, SQL_IS_UINTEGER),
                SQL_HANDLE_DBC, handle);
        check_mode(value);
    }

    void check_mode(SQLUINTEGER expected) {
        for (const bool wide : {false, true}) {
            struct {
                SQLUINTEGER mode = 99;
                SQLUINTEGER guard = 0x12345678;
            } output;
            SQLINTEGER length = -1;
            success(wide ? SQLGetConnectAttrW(handle, SQL_ATTR_AUTOCOMMIT, &output.mode,
                                              sizeof(output.mode), &length)
                         : SQLGetConnectAttr(handle, SQL_ATTR_AUTOCOMMIT, &output.mode,
                                             sizeof(output.mode), &length),
                    SQL_HANDLE_DBC, handle);
            require(output.mode == expected, "Incorrect ANSI/Unicode autocommit mode");
            require(output.guard == 0x12345678 && length == sizeof(SQLUINTEGER),
                    "Incorrect autocommit output size");
        }
    }

    void end(SQLSMALLINT completion) {
        success(SQLEndTran(SQL_HANDLE_DBC, handle, completion), SQL_HANDLE_DBC, handle);
        check_mode(SQL_AUTOCOMMIT_OFF);
    }
};

std::string backend_id_sql(const std::string& url) {
    if (url.rfind("jdbc:h2:", 0) == 0) {
        return "SELECT SESSION_ID()";
    }
    if (url.rfind("jdbc:postgresql:", 0) == 0) {
        return "SELECT pg_backend_pid()";
    }
    if (url.rfind("jdbc:sqlserver:", 0) == 0) {
        return "SELECT @@SPID";
    }
    return "SELECT CONNECTION_ID()";
}

int run(const ojp::test::DatabaseConfig& config, const std::string& endpoints) {
    SQLHENV environment = SQL_NULL_HENV;
    success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment),
            SQL_HANDLE_ENV, environment);
    success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                          reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), 0),
            SQL_HANDLE_ENV, environment);
    try {
        const std::string text = "DRIVER={OJP};SERVER=" + brace(endpoints) +
            ";DATABASE=" + brace(config.url) + ";UID=" + brace(config.user) +
            ";PWD=" + brace(config.password) + ";OJP.LOADAWARE.SELECTION.ENABLED=false;";
        Connection writer(environment, text);
        Connection observer(environment, text);
        writer.check_mode(SQL_AUTOCOMMIT_ON);
        observer.check_mode(SQL_AUTOCOMMIT_ON);
        if (config.url.rfind("jdbc:sqlserver:", 0) == 0) {
            require(observer.scalar("SELECT is_read_committed_snapshot_on FROM sys.databases "
                                    "WHERE name = DB_NAME()") == 1,
                    "SQL Server visibility tests require READ_COMMITTED_SNAPSHOT ON");
        }
        const auto original_handle = writer.handle;
        std::ostringstream name;
        name << "ojp_ac_" << std::hex << std::random_device{}();
        const auto table = name.str();
        observer.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY)");
        try {
            SQLHSTMT insert = SQL_NULL_HSTMT;
            success(SQLAllocHandle(SQL_HANDLE_STMT, writer.handle, &insert),
                    SQL_HANDLE_DBC, writer.handle);
            const auto sql = "INSERT INTO " + table + " (id) VALUES (?)";
            success(SQLPrepare(insert,
                                reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str())), SQL_NTS),
                    SQL_HANDLE_STMT, insert);
            SQLINTEGER id = 0;
            SQLLEN indicator = 0;
            success(SQLBindParameter(insert, 1, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER,
                                      0, 0, &id, sizeof(id), &indicator), SQL_HANDLE_STMT, insert);
            const auto add = [&](SQLINTEGER value) {
                id = value;
                success(SQLExecute(insert), SQL_HANDLE_STMT, insert);
            };
            const auto visible = [&](SQLINTEGER value, SQLINTEGER expected) {
                require(observer.scalar("SELECT COUNT(*) FROM " + table +
                    " WHERE id = " + std::to_string(value)) == expected,
                    "Unexpected cross-connection visibility for id " + std::to_string(value));
            };
            try {
                for (int cycle = 0; cycle < 6; ++cycle) {
                    const int base = 10 * cycle;
                    writer.mode(SQL_AUTOCOMMIT_ON, true);
                    writer.mode(SQL_AUTOCOMMIT_OFF, cycle % 2 == 0);
                    const auto backend = writer.scalar(backend_id_sql(config.url));
                    add(base + 1);
                    visible(base + 1, 0);
                    writer.mode(SQL_AUTOCOMMIT_OFF, cycle % 2 != 0);
                    visible(base + 1, 0);
                    require(writer.scalar(backend_id_sql(config.url)) == backend,
                            "Manual transaction changed backend connection ownership");
                    writer.end(cycle % 2 == 0 ? SQL_COMMIT : SQL_ROLLBACK);
                    visible(base + 1, cycle % 2 == 0 ? 1 : 0);
                    add(base + 2);
                    visible(base + 2, 0);
                    writer.mode(SQL_AUTOCOMMIT_ON, cycle % 2 != 0);
                    visible(base + 2, 1);
                    writer.mode(SQL_AUTOCOMMIT_ON, cycle % 2 == 0);
                    add(base + 3);
                    visible(base + 3, 1);
                    require(writer.handle == original_handle, "Autocommit replaced the HDBC");
                }
                writer.mode(SQL_AUTOCOMMIT_OFF, true);
                add(100);
                writer.execute(config.url.rfind("jdbc:sqlserver:", 0) == 0
                    ? "SAVE TRANSACTION ac_point" : "SAVEPOINT ac_point");
                writer.mode(SQL_AUTOCOMMIT_ON, false);
                visible(100, 1);
                writer.mode(SQL_AUTOCOMMIT_OFF, false);
                const std::string rollback = config.url.rfind("jdbc:sqlserver:", 0) == 0
                    ? "ROLLBACK TRANSACTION ac_point" : "ROLLBACK TO SAVEPOINT ac_point";
                require(SQLExecDirect(writer.statement,
                            reinterpret_cast<SQLCHAR*>(const_cast<char*>(rollback.c_str())),
                            SQL_NTS) == SQL_ERROR, "Implicit commit retained a stale savepoint");
                SQLCHAR state[6] = {};
                success(SQLGetDiagRec(SQL_HANDLE_STMT, writer.statement, 1, state,
                                      nullptr, nullptr, 0, nullptr), SQL_HANDLE_STMT, writer.statement);
                require(std::string(reinterpret_cast<char*>(state)) == "3B001",
                        "A stale savepoint must report 3B001");
                add(101);
                visible(101, 0);
                success(SQLFreeHandle(SQL_HANDLE_STMT, insert), SQL_HANDLE_DBC, writer.handle);
                insert = SQL_NULL_HSTMT;
                success(SQLFreeHandle(SQL_HANDLE_STMT, writer.statement),
                        SQL_HANDLE_DBC, writer.handle);
                writer.statement = SQL_NULL_HSTMT;
                success(SQLDisconnect(writer.handle), SQL_HANDLE_DBC, writer.handle);
                visible(101, 0);
                success(SQLFreeHandle(SQL_HANDLE_DBC, writer.handle), SQL_HANDLE_ENV, environment);
                writer.handle = SQL_NULL_HDBC;
            } catch (...) {
                if (insert != SQL_NULL_HSTMT) {
                    SQLFreeHandle(SQL_HANDLE_STMT, insert);
                }
                throw;
            }
        } catch (...) {
            if (writer.handle != SQL_NULL_HDBC) {
                SQLEndTran(SQL_HANDLE_DBC, writer.handle, SQL_ROLLBACK);
            }
            observer.execute("DROP TABLE " + table);
            throw;
        }
        observer.execute("DROP TABLE " + table);
    } catch (...) {
        SQLFreeHandle(SQL_HANDLE_ENV, environment);
        throw;
    }
    success(SQLFreeHandle(SQL_HANDLE_ENV, environment), SQL_HANDLE_ENV, environment);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 5, "Expected CSV, enable variable, endpoints variable, minimum node count");
        const char* enabled = std::getenv(argv[2]);
        if (enabled == nullptr || std::string(enabled) != "true") {
            std::cout << "Skipped: set " << argv[2] << "=true\n";
            return 77;
        }
        const char* addresses = std::getenv(argv[3]);
        require(addresses != nullptr && addresses[0] != 0, "OJP endpoints are required");
        const std::string endpoints(addresses);
        std::set<std::string> distinct_endpoints;
        std::istringstream input(endpoints);
        for (std::string endpoint; std::getline(input, endpoint, ',');) {
            require(!endpoint.empty(), "Empty OJP endpoint");
            distinct_endpoints.insert(endpoint);
        }
        require(distinct_endpoints.size() >= static_cast<std::size_t>(std::stoi(argv[4])),
                "Multinode autocommit tests require multiple distinct OJP endpoints");
        return ojp::test::run_connections(argv[1], [&](const ojp::test::DatabaseConfig& config) {
            run(config, endpoints);
            if (std::stoi(argv[4]) > 1) {
                std::istringstream input(endpoints);
                for (std::string endpoint; std::getline(input, endpoint, ',');) {
                    run(config, endpoint);
                }
            }
            return 0;
        });
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
