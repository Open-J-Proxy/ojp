// Verifies SQL Server failover, pool-exhaustion handling, and server recovery through ODBC.
#include <sql.h>
#include <sqlext.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <csignal>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include <sys/types.h>
#include <unistd.h>

namespace {

struct DatabaseConfig {
    std::string url;
    std::string user;
    std::string password;
};

DatabaseConfig read_connection_config(const std::string& path) {
    std::ifstream input(path);
    std::string line;
    if (!input || !std::getline(input, line)) {
        throw std::runtime_error("cannot read the SQL Server connection CSV");
    }
    std::vector<std::string> fields;
    std::string field;
    bool quoted = false;
    for (std::size_t index = 0; index < line.size(); ++index) {
        const char character = line[index];
        if (quoted) {
            if (character == '"' && index + 1 < line.size() && line[index + 1] == '"') {
                field.push_back('"');
                ++index;
            } else if (character == '"') {
                quoted = false;
            } else {
                field.push_back(character);
            }
        } else if (character == ',') {
            fields.push_back(field);
            field.clear();
        } else if (character == '"' && field.empty()) {
            quoted = true;
        } else {
            field.push_back(character);
        }
    }
    fields.push_back(field);
    if (quoted || fields.size() != 3 || fields[0].empty()) {
        throw std::runtime_error("expected JDBC URL, username, and password in SQL Server CSV");
    }
    return {fields[0], fields[1], fields[2]};
}

std::string brace_value(const std::string& value) {
    std::string escaped;
    for (const char character : value) {
        escaped.push_back(character);
        if (character == '}') {
            escaped.push_back('}');
        }
    }
    return "{" + escaped + "}";
}

void require_success(SQLRETURN result, const std::string& operation,
                     SQLSMALLINT handle_type = SQL_HANDLE_ENV,
                     SQLHANDLE handle = SQL_NULL_HANDLE) {
    if (SQL_SUCCEEDED(result)) {
        return;
    }
    std::ostringstream message;
    message << operation << " failed";
    if (handle != SQL_NULL_HANDLE) {
        SQLCHAR state[6] = {};
        SQLCHAR detail[1024] = {};
        SQLINTEGER native_error = 0;
        SQLSMALLINT detail_length = 0;
        if (SQLGetDiagRec(handle_type, handle, 1, state, &native_error, detail,
                          sizeof(detail), &detail_length) == SQL_SUCCESS) {
            message << " [" << state << ", " << native_error << "] "
                    << reinterpret_cast<const char*>(detail);
        }
    }
    throw std::runtime_error(message.str());
}

std::string environment_value(const char* name) {
    const char* value = std::getenv(name);
    return value == nullptr ? std::string{} : std::string(value);
}

bool enabled(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char character) {
                       return static_cast<char>(std::tolower(character));
                   });
    return value == "true" || value == "1" || value == "yes";
}

std::vector<std::string> split_endpoints(const std::string& value) {
    std::vector<std::string> endpoints;
    std::size_t start = 0;
    while (start <= value.size()) {
        const auto separator = value.find(',', start);
        std::string endpoint = value.substr(
            start, separator == std::string::npos ? separator : separator - start);
        const auto first = endpoint.find_first_not_of(" \t\r\n");
        const auto last = endpoint.find_last_not_of(" \t\r\n");
        endpoint = first == std::string::npos ? "" : endpoint.substr(first, last - first + 1);
        if (endpoint.empty() ||
            std::find(endpoints.begin(), endpoints.end(), endpoint) != endpoints.end()) {
            throw std::runtime_error(
                "SQL Server L8 endpoint list contains an empty or duplicate endpoint");
        }
        endpoints.push_back(std::move(endpoint));
        if (separator == std::string::npos) {
            break;
        }
        start = separator + 1;
    }
    if (endpoints.size() < 2) {
        throw std::runtime_error("OJP_TEST_SQLSERVER_L8_ADDRS must contain at least two servers");
    }
    return endpoints;
}

long read_process_id(const std::string& path) {
    std::ifstream input(path);
    long process_id = 0;
    if (!(input >> process_id) || process_id <= 0) {
        return 0;
    }
    return process_id;
}

std::string random_suffix() {
    std::random_device random;
    std::ostringstream suffix;
    suffix << std::hex;
    for (int index = 0; index < 6; ++index) {
        suffix << static_cast<unsigned int>(random() & 0xff);
    }
    return suffix.str();
}

SQLCHAR* sql_text(const std::string& sql) {
    return reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str()));
}

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement, sql_text(sql), SQL_NTS),
                    "SQLExecDirect", SQL_HANDLE_STMT, statement);
}

SQLHDBC open_connection(SQLHENV environment, const DatabaseConfig& config,
                        const std::string& endpoints, bool load_aware = true) {
    SQLHDBC connection = SQL_NULL_HDBC;
    require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment,
                                   reinterpret_cast<SQLHANDLE*>(&connection)),
                    "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);
    const std::string connection_string =
        "DRIVER={OJP};SERVER=" + brace_value(endpoints) +
        ";DATABASE=" + brace_value(config.url) +
        ";UID=" + brace_value(config.user) +
        ";P" "WD=" + brace_value(config.password) +
        ";OJP.LOADAWARE.SELECTION.ENABLED=" + std::string(load_aware ? "true" : "false") +
        ";OJP.MULTINODE.RETRY.ATTEMPTS=3;OJP.MULTINODE.RETRY.DELAY=25;";
    require_success(SQLDriverConnect(connection, nullptr, sql_text(connection_string), SQL_NTS,
                                     nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                    "SQLDriverConnect", SQL_HANDLE_DBC, connection);
    return connection;
}

void verify_label(SQLHSTMT statement, const std::string& table, int id,
                  const std::string& expected) {
    execute_direct(statement, "SELECT label FROM " + table + " WHERE id = " +
                                  std::to_string(id));
    require_success(SQLFetch(statement), "SQLFetch", SQL_HANDLE_STMT, statement);
    SQLCHAR label[128] = {};
    SQLLEN label_length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_CHAR, label, sizeof(label), &label_length),
                    "SQLGetData(label)", SQL_HANDLE_STMT, statement);
    if (std::string(reinterpret_cast<const char*>(label)) != expected) {
        throw std::runtime_error("SQL Server L8 lookup returned an unexpected value");
    }
    if (SQLFetch(statement) != SQL_NO_DATA) {
        throw std::runtime_error("SQL Server L8 lookup returned more than one row");
    }
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(close)",
                    SQL_HANDLE_STMT, statement);
}

void require_pool_exhaustion_not_retried(SQLHENV environment, const DatabaseConfig& config,
                                         const std::string& endpoint,
                                         const std::string& alternate_endpoint) {
    SQLHDBC busy_connection = SQL_NULL_HDBC;
    SQLHDBC overloaded_connection = SQL_NULL_HDBC;
    SQLHSTMT busy_statement = SQL_NULL_HSTMT;
    SQLHSTMT overloaded_statement = SQL_NULL_HSTMT;
    std::atomic<bool> query_started{false};
    SQLRETURN long_query_result = SQL_ERROR;
    std::thread long_query_thread;
    try {
        busy_connection = open_connection(environment, config, endpoint, false);
        overloaded_connection = open_connection(
            environment, config, endpoint + "," + alternate_endpoint, false);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, busy_connection,
                                       reinterpret_cast<SQLHANDLE*>(&busy_statement)),
                        "SQLAllocHandle(busy statement)", SQL_HANDLE_DBC, busy_connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, overloaded_connection,
                                       reinterpret_cast<SQLHANDLE*>(&overloaded_statement)),
                        "SQLAllocHandle(overloaded statement)", SQL_HANDLE_DBC,
                        overloaded_connection);

        const std::string long_query =
            "SELECT SUM(CONVERT(BIGINT, row_number)) FROM "
            "(SELECT TOP (50000000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS row_number "
            "FROM sys.all_objects AS a CROSS JOIN sys.all_objects AS b "
            "CROSS JOIN sys.all_objects AS c) AS numbers";
        long_query_thread = std::thread([&] {
            query_started.store(true);
            long_query_result = SQLExecDirect(busy_statement, sql_text(long_query), SQL_NTS);
        });
        while (!query_started.load()) {
            std::this_thread::yield();
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));

        const std::string short_query = "SELECT 1";
        const SQLRETURN overloaded_result =
            SQLExecDirect(overloaded_statement, sql_text(short_query), SQL_NTS);
        SQLCHAR sql_state[6] = {};
        SQLCHAR detail[1024] = {};
        SQLINTEGER native_error = 0;
        SQLSMALLINT detail_length = 0;
        std::string error_detail;
        if (!SQL_SUCCEEDED(overloaded_result) &&
            SQLGetDiagRec(SQL_HANDLE_STMT, overloaded_statement, 1, sql_state, &native_error,
                          detail, sizeof(detail), &detail_length) == SQL_SUCCESS) {
            error_detail = reinterpret_cast<const char*>(detail);
        }
        long_query_thread.join();
        if (SQL_SUCCEEDED(overloaded_result) ||
            error_detail.find("Server overloaded") == std::string::npos ||
            !SQL_SUCCEEDED(long_query_result)) {
            throw std::runtime_error(
                "SQL Server L8 pool exhaustion must be surfaced without retrying the request");
        }

        require_success(SQLFreeHandle(SQL_HANDLE_STMT, overloaded_statement),
                        "SQLFreeHandle(overloaded statement)", SQL_HANDLE_DBC,
                        overloaded_connection);
        overloaded_statement = SQL_NULL_HSTMT;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, busy_statement),
                        "SQLFreeHandle(busy statement)", SQL_HANDLE_DBC, busy_connection);
        busy_statement = SQL_NULL_HSTMT;
        require_success(SQLDisconnect(overloaded_connection), "SQLDisconnect(overloaded)",
                        SQL_HANDLE_DBC, overloaded_connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, overloaded_connection),
                        "SQLFreeHandle(overloaded connection)", SQL_HANDLE_ENV, environment);
        overloaded_connection = SQL_NULL_HDBC;
        require_success(SQLDisconnect(busy_connection), "SQLDisconnect(busy)",
                        SQL_HANDLE_DBC, busy_connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, busy_connection),
                        "SQLFreeHandle(busy connection)", SQL_HANDLE_ENV, environment);
        busy_connection = SQL_NULL_HDBC;
    } catch (...) {
        if (long_query_thread.joinable()) {
            long_query_thread.join();
        }
        if (busy_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, busy_statement);
        }
        if (overloaded_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, overloaded_statement);
        }
        if (overloaded_connection != SQL_NULL_HDBC) {
            SQLDisconnect(overloaded_connection);
            SQLFreeHandle(SQL_HANDLE_DBC, overloaded_connection);
        }
        if (busy_connection != SQL_NULL_HDBC) {
            SQLDisconnect(busy_connection);
            SQLFreeHandle(SQL_HANDLE_DBC, busy_connection);
        }
        throw;
    }
}

long stop_server(const std::string& pid_file, const std::string& endpoint) {
    const long process_id = read_process_id(pid_file);
    std::ifstream command_line_file(
        "/proc/" + std::to_string(process_id) + "/cmdline", std::ios::binary);
    const std::string command_line{
        std::istreambuf_iterator<char>(command_line_file), std::istreambuf_iterator<char>()};
    const auto port_separator = endpoint.rfind(':');
    const std::string server_port = port_separator == std::string::npos
        ? std::string{} : endpoint.substr(port_separator + 1);
    if (process_id <= 0 || server_port.empty() ||
        command_line.find("ojp-server-") == std::string::npos ||
        command_line.find("-Dojp.server.port=" + server_port) == std::string::npos ||
        ::kill(static_cast<pid_t>(process_id), SIGKILL) != 0) {
        throw std::runtime_error(
            "cannot stop the first SQL Server L8 OJP server using its PID file");
    }
    return process_id;
}

void wait_for_server_restart(const std::string& pid_file, long stopped_process_id) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    while (std::chrono::steady_clock::now() < deadline) {
        const long restarted_process_id = read_process_id(pid_file);
        if (restarted_process_id > 0 && restarted_process_id != stopped_process_id) {
            std::this_thread::sleep_for(std::chrono::seconds(8));
            return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    throw std::runtime_error("the SQL Server L8 OJP server did not restart within 30 seconds");
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error(
            "expected SQL Server L8 CSV path, enable variable, and endpoint variable");
    }
    const std::string enable_variable = argv[2];
    if (!enabled(environment_value(enable_variable.c_str()))) {
        std::cout << "Skipped: set " << enable_variable
                  << "=true to run the SQL Server L8 suite\n";
        return 77;
    }
    const std::vector<std::string> endpoints = split_endpoints(environment_value(argv[3]));
    const std::string pid_file =
        environment_value("OJP_TEST_SQLSERVER_L8_FIRST_SERVER_PID_FILE");
    if (pid_file.empty()) {
        throw std::runtime_error(
            "OJP_TEST_SQLSERVER_L8_FIRST_SERVER_PID_FILE is required when SQL Server L8 is enabled");
    }
    const DatabaseConfig config = read_connection_config(argv[1]);
    const std::string table = "dbo.ojp_cpp_mssql_l8_" + random_suffix();
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC first_connection = SQL_NULL_HDBC;
    SQLHDBC recovered_connection = SQL_NULL_HDBC;
    SQLHSTMT first_statement = SQL_NULL_HSTMT;
    SQLHSTMT recovered_statement = SQL_NULL_HSTMT;
    bool table_created = false;
    try {
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE,
                                       reinterpret_cast<SQLHANDLE*>(&environment)),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);
        first_connection = open_connection(
            environment, config, endpoints[0] + "," + endpoints[1]);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, first_connection,
                                       reinterpret_cast<SQLHANDLE*>(&first_statement)),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, first_connection);
        execute_direct(first_statement, "CREATE TABLE " + table +
                                       " (id INT PRIMARY KEY, label NVARCHAR(100) NOT NULL)");
        table_created = true;
        execute_direct(first_statement, "INSERT INTO " + table +
                                        " VALUES (1, N'before-failover')");
        require_pool_exhaustion_not_retried(environment, config, endpoints[0], endpoints[1]);

        const long stopped_process_id = stop_server(pid_file, endpoints[0]);
        execute_direct(first_statement, "UPDATE " + table +
                                        " SET label = N'after-failover' WHERE id = 1");
        verify_label(first_statement, table, 1, "after-failover");

        const std::string duplicate_insert =
            "INSERT INTO " + table + " VALUES (1, N'duplicate')";
        if (SQL_SUCCEEDED(SQLExecDirect(first_statement, sql_text(duplicate_insert), SQL_NTS))) {
            throw std::runtime_error("SQL Server L8 duplicate-key error unexpectedly succeeded");
        }
        SQLCHAR sql_state[6] = {};
        SQLCHAR detail[1024] = {};
        SQLINTEGER native_error = 0;
        SQLSMALLINT detail_length = 0;
        if (SQLGetDiagRec(SQL_HANDLE_STMT, first_statement, 1, sql_state, &native_error,
                          detail, sizeof(detail), &detail_length) != SQL_SUCCESS ||
            std::string(reinterpret_cast<const char*>(sql_state)) == "08S01") {
            throw std::runtime_error(
                "SQL Server L8 SQL errors must not be reported as transport failures");
        }
        verify_label(first_statement, table, 1, "after-failover");

        wait_for_server_restart(pid_file, stopped_process_id);
        recovered_connection = open_connection(environment, config, endpoints[0]);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, recovered_connection,
                                       reinterpret_cast<SQLHANDLE*>(&recovered_statement)),
                        "SQLAllocHandle(recovered statement)", SQL_HANDLE_DBC,
                        recovered_connection);
        verify_label(recovered_statement, table, 1, "after-failover");

        execute_direct(recovered_statement, "INSERT INTO " + table +
                                            " VALUES (2, N'recovered-node reuse')");
        SQLHDBC redistributed_connection =
            open_connection(environment, config, endpoints[0] + "," + endpoints[1]);
        SQLHSTMT redistributed_statement = SQL_NULL_HSTMT;
        try {
            require_success(SQLAllocHandle(SQL_HANDLE_STMT, redistributed_connection,
                                           reinterpret_cast<SQLHANDLE*>(&redistributed_statement)),
                            "SQLAllocHandle(redistributed statement)", SQL_HANDLE_DBC,
                            redistributed_connection);
            verify_label(redistributed_statement, table, 2, "recovered-node reuse");
            execute_direct(redistributed_statement, "UPDATE " + table +
                                                     " SET label = N'cluster reuse' WHERE id = 2");
            verify_label(recovered_statement, table, 2, "cluster reuse");
            require_success(SQLFreeHandle(SQL_HANDLE_STMT, redistributed_statement),
                            "SQLFreeHandle(redistributed statement)", SQL_HANDLE_DBC,
                            redistributed_connection);
            redistributed_statement = SQL_NULL_HSTMT;
            require_success(SQLDisconnect(redistributed_connection),
                            "SQLDisconnect(redistributed)", SQL_HANDLE_DBC,
                            redistributed_connection);
            require_success(SQLFreeHandle(SQL_HANDLE_DBC, redistributed_connection),
                            "SQLFreeHandle(redistributed connection)", SQL_HANDLE_ENV, environment);
            redistributed_connection = SQL_NULL_HDBC;
        } catch (...) {
            if (redistributed_statement != SQL_NULL_HSTMT) {
                SQLFreeHandle(SQL_HANDLE_STMT, redistributed_statement);
            }
            if (redistributed_connection != SQL_NULL_HDBC) {
                SQLDisconnect(redistributed_connection);
                SQLFreeHandle(SQL_HANDLE_DBC, redistributed_connection);
            }
            throw;
        }

        execute_direct(first_statement, "DROP TABLE " + table);
        table_created = false;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, recovered_statement),
                        "SQLFreeHandle(recovered statement)", SQL_HANDLE_DBC,
                        recovered_connection);
        recovered_statement = SQL_NULL_HSTMT;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, first_statement),
                        "SQLFreeHandle(statement)", SQL_HANDLE_DBC, first_connection);
        first_statement = SQL_NULL_HSTMT;
        require_success(SQLDisconnect(recovered_connection), "SQLDisconnect(recovered)",
                        SQL_HANDLE_DBC, recovered_connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, recovered_connection),
                        "SQLFreeHandle(recovered connection)", SQL_HANDLE_ENV, environment);
        recovered_connection = SQL_NULL_HDBC;
        require_success(SQLDisconnect(first_connection), "SQLDisconnect(first)",
                        SQL_HANDLE_DBC, first_connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, first_connection),
                        "SQLFreeHandle(first connection)", SQL_HANDLE_ENV, environment);
        first_connection = SQL_NULL_HDBC;
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
    } catch (...) {
        if (table_created && first_statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(first_statement, sql_text(drop), SQL_NTS);
        }
        if (recovered_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, recovered_statement);
        }
        if (first_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, first_statement);
        }
        if (recovered_connection != SQL_NULL_HDBC) {
            SQLDisconnect(recovered_connection);
            SQLFreeHandle(SQL_HANDLE_DBC, recovered_connection);
        }
        if (first_connection != SQL_NULL_HDBC) {
            SQLDisconnect(first_connection);
            SQLFreeHandle(SQL_HANDLE_DBC, first_connection);
        }
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        throw;
    }
    std::cout << "C++ ODBC SQL Server L8 failover and recovery integration test passed\n";
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        return run_integration_test(argc, argv);
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
