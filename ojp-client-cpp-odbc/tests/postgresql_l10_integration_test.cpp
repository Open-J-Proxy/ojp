// Verifies PostgreSQL XA affinity together with multinode failover and recovery.
#include <sql.h>
#include <sqlext.h>

#include "ojp_odbc_xa.h"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
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

struct XaApi {
    decltype(&OjpXAStart) start;
    decltype(&OjpXAEnd) end;
    decltype(&OjpXAPrepare) prepare;
    decltype(&OjpXACommit) commit;
    decltype(&OjpXARollback) rollback;
    decltype(&OjpXARecover) recover;
};

DatabaseConfig read_connection_config(const std::string& path) {
    std::ifstream input(path);
    std::string line;
    if (!input || !std::getline(input, line)) {
        throw std::runtime_error("cannot read the PostgreSQL connection CSV");
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
        throw std::runtime_error("expected JDBC URL, username, and password in PostgreSQL CSV");
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

template <typename Function>
Function load_symbol(void* module, const char* name) {
    auto* symbol = dlsym(module, name);
    if (symbol == nullptr) {
        throw std::runtime_error(std::string("missing OJP XA API symbol: ") + name);
    }
    return reinterpret_cast<Function>(symbol);
}

XaApi load_xa_api(void* module) {
    return {
        load_symbol<decltype(&OjpXAStart)>(module, "OjpXAStart"),
        load_symbol<decltype(&OjpXAEnd)>(module, "OjpXAEnd"),
        load_symbol<decltype(&OjpXAPrepare)>(module, "OjpXAPrepare"),
        load_symbol<decltype(&OjpXACommit)>(module, "OjpXACommit"),
        load_symbol<decltype(&OjpXARollback)>(module, "OjpXARollback"),
        load_symbol<decltype(&OjpXARecover)>(module, "OjpXARecover")};
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

bool disabled(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char character) {
                       return static_cast<char>(std::tolower(character));
                   });
    return value.empty() || value == "false" || value == "0" || value == "no";
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
                "PostgreSQL L10 endpoint list contains an empty or duplicate endpoint");
        }
        endpoints.push_back(std::move(endpoint));
        if (separator == std::string::npos) {
            break;
        }
        start = separator + 1;
    }
    if (endpoints.size() < 2) {
        throw std::runtime_error("OJP_TEST_POSTGRESQL_L10_ADDRS must contain at least two servers");
    }
    return endpoints;
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

SQLHDBC open_connection(SQLHENV environment, const DatabaseConfig& config,
                        const std::string& endpoints, bool xa = false) {
    SQLHDBC connection = SQL_NULL_HDBC;
    require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment,
                                   reinterpret_cast<SQLHANDLE*>(&connection)),
                    "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);
    const std::string connection_string =
        "DRIVER={OJP};SERVER=" + brace_value(endpoints) +
        ";DATABASE=" + brace_value(config.url) +
        ";UID=" + brace_value(config.user) +
        ";P" "WD=" + brace_value(config.password) +
        ";OJP.LOADAWARE.SELECTION.ENABLED=false;" +
        "OJP.MULTINODE.RETRY.ATTEMPTS=3;OJP.MULTINODE.RETRY.DELAY=25;" +
        (xa ? "OJP.XA=TRUE;" : "");
    const SQLRETURN result =
        SQLDriverConnect(connection, nullptr, sql_text(connection_string), SQL_NTS,
                         nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT);
    if (!SQL_SUCCEEDED(result)) {
        SQLCHAR state[6] = {};
        SQLCHAR detail[1024] = {};
        SQLINTEGER native_error = 0;
        SQLSMALLINT detail_length = 0;
        std::ostringstream message;
        message << "SQLDriverConnect failed";
        if (SQLGetDiagRec(SQL_HANDLE_DBC, connection, 1, state, &native_error, detail,
                          sizeof(detail), &detail_length) == SQL_SUCCESS) {
            message << " [" << state << ", " << native_error << "] "
                    << reinterpret_cast<const char*>(detail);
        }
        SQLFreeHandle(SQL_HANDLE_DBC, connection);
        throw std::runtime_error(message.str());
    }
    return connection;
}

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement, sql_text(sql), SQL_NTS),
                    "SQLExecDirect", SQL_HANDLE_STMT, statement);
}

int row_count(SQLHSTMT statement, const std::string& table, int id) {
    execute_direct(statement, "SELECT COUNT(*) FROM " + table + " WHERE id = " +
                              std::to_string(id));
    require_success(SQLFetch(statement), "SQLFetch(count)", SQL_HANDLE_STMT, statement);
    SQLINTEGER count = -1;
    SQLLEN count_length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_SLONG, &count, sizeof(count), &count_length),
                    "SQLGetData(count)", SQL_HANDLE_STMT, statement);
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(count)",
                    SQL_HANDLE_STMT, statement);
    return count;
}

void verify_endpoint_available(SQLHSTMT statement) {
    execute_direct(statement, "SELECT 1");
    require_success(SQLFetch(statement), "SQLFetch(endpoint availability)",
                    SQL_HANDLE_STMT, statement);
    SQLINTEGER value = 0;
    SQLLEN value_length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_SLONG, &value, sizeof(value), &value_length),
                    "SQLGetData(endpoint availability)", SQL_HANDLE_STMT, statement);
    if (value != 1) {
        throw std::runtime_error("the surviving PostgreSQL OJP endpoint returned an unexpected result");
    }
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(endpoint availability)",
                    SQL_HANDLE_STMT, statement);
}

OjpXid xid_for(const std::string& global_id, const std::string& branch_id) {
    if (global_id.size() > OJP_XA_MAX_GTRID_SIZE || branch_id.size() > OJP_XA_MAX_BQUAL_SIZE) {
        throw std::runtime_error("test XID exceeds XA limits");
    }
    OjpXid xid{};
    xid.format_id = 0x4f4a50;
    xid.global_transaction_id_length = static_cast<std::uint32_t>(global_id.size());
    std::copy(global_id.begin(), global_id.end(), xid.global_transaction_id);
    xid.branch_qualifier_length = static_cast<std::uint32_t>(branch_id.size());
    std::copy(branch_id.begin(), branch_id.end(), xid.branch_qualifier);
    return xid;
}

bool same_xid(const OjpXid& left, const OjpXid& right) {
    return left.format_id == right.format_id &&
        left.global_transaction_id_length == right.global_transaction_id_length &&
        left.branch_qualifier_length == right.branch_qualifier_length &&
        std::equal(left.global_transaction_id,
                   left.global_transaction_id + left.global_transaction_id_length,
                   right.global_transaction_id) &&
        std::equal(left.branch_qualifier,
                   left.branch_qualifier + left.branch_qualifier_length,
                   right.branch_qualifier);
}

long read_process_id(const std::string& path) {
    std::ifstream input(path);
    long process_id = 0;
    if (!(input >> process_id) || process_id <= 0) {
        return 0;
    }
    return process_id;
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
        throw std::runtime_error("cannot stop the first PostgreSQL L10 OJP server");
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
    throw std::runtime_error("the PostgreSQL L10 OJP server did not restart within 30 seconds");
}

void close_connection(SQLHDBC* connection) {
    if (*connection != SQL_NULL_HDBC) {
        SQLDisconnect(*connection);
        SQLFreeHandle(SQL_HANDLE_DBC, *connection);
        *connection = SQL_NULL_HDBC;
    }
}

int run_integration_test(int argc, char** argv) {
    if (argc != 6) {
        throw std::runtime_error(
            "expected PostgreSQL CSV path, enable variable, endpoints variable, target PID file, and driver path");
    }
    const std::string enable_variable = argv[2];
    const std::string enabled_value = environment_value(enable_variable.c_str());
    if (!enabled(enabled_value)) {
        if (!disabled(enabled_value)) {
            throw std::runtime_error(enable_variable + " must be true or false");
        }
        std::cout << "Skipped: set " << enable_variable
                  << "=true to run the PostgreSQL L10 suite\n";
        return 77;
    }
    const std::vector<std::string> endpoints =
        split_endpoints(environment_value(argv[3]));
    const std::string pid_file = environment_value(argv[4]);
    if (pid_file.empty()) {
        throw std::runtime_error(std::string(argv[4]) + " is required when " +
                                 enable_variable + "=true");
    }
    const DatabaseConfig config = read_connection_config(argv[1]);
    const std::string table = "ojp_cpp_pg_l10_" + random_suffix();
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC failover_connection = SQL_NULL_HDBC;
    SQLHDBC xa_connection = SQL_NULL_HDBC;
    SQLHDBC surviving_connection = SQL_NULL_HDBC;
    SQLHDBC recovered_connection = SQL_NULL_HDBC;
    SQLHSTMT failover_statement = SQL_NULL_HSTMT;
    SQLHSTMT xa_statement = SQL_NULL_HSTMT;
    SQLHSTMT surviving_statement = SQL_NULL_HSTMT;
    SQLHSTMT recovered_statement = SQL_NULL_HSTMT;
    void* driver_module = nullptr;
    bool table_created = false;
    try {
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE,
                                       reinterpret_cast<SQLHANDLE*>(&environment)),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);

        failover_connection = open_connection(environment, config,
                                              endpoints[0] + "," + endpoints[1]);
        xa_connection = open_connection(environment, config, endpoints[0], true);
        surviving_connection = open_connection(environment, config, endpoints[1]);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, failover_connection,
                                       reinterpret_cast<SQLHANDLE*>(&failover_statement)),
                        "SQLAllocHandle(failover statement)", SQL_HANDLE_DBC, failover_connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, xa_connection,
                                       reinterpret_cast<SQLHANDLE*>(&xa_statement)),
                        "SQLAllocHandle(XA statement)", SQL_HANDLE_DBC, xa_connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, surviving_connection,
                                       reinterpret_cast<SQLHANDLE*>(&surviving_statement)),
                        "SQLAllocHandle(surviving statement)", SQL_HANDLE_DBC,
                        surviving_connection);

        execute_direct(surviving_statement, "CREATE TABLE " + table +
                       " (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)");
        table_created = true;
        execute_direct(failover_statement, "INSERT INTO " + table +
                       " VALUES (10, 'multinode before failure')");

        driver_module = dlopen(argv[5], RTLD_NOW | RTLD_LOCAL);
        if (driver_module == nullptr) {
            throw std::runtime_error(std::string("cannot load OJP driver XA API: ") + dlerror());
        }
        const XaApi xa = load_xa_api(driver_module);

        const OjpXid commit_xid = xid_for(table + "_commit", "branch-1");
        require_success(xa.start(xa_connection, &commit_xid, OJP_XA_TMNOFLAGS),
                        "OjpXAStart(two-phase)", SQL_HANDLE_DBC, xa_connection);
        execute_direct(xa_statement, "INSERT INTO " + table +
                       " VALUES (1, 'two-phase commit')");
        require_success(xa.end(xa_connection, &commit_xid, OJP_XA_TMSUCCESS),
                        "OjpXAEnd(two-phase)", SQL_HANDLE_DBC, xa_connection);
        SQLINTEGER prepare_result = -1;
        require_success(xa.prepare(xa_connection, &commit_xid, &prepare_result),
                        "OjpXAPrepare", SQL_HANDLE_DBC, xa_connection);
        if (prepare_result != OJP_XA_XA_OK && prepare_result != OJP_XA_XA_RDONLY) {
            throw std::runtime_error("XA prepare must return XA_OK or XA_RDONLY");
        }
        OjpXid recovered_xids[16]{};
        SQLSMALLINT recovered_count = 0;
        require_success(xa.recover(xa_connection, OJP_XA_TMSTARTRSCAN,
                                   recovered_xids, 16, &recovered_count),
                        "OjpXARecover(start scan)", SQL_HANDLE_DBC, xa_connection);
        if (prepare_result == OJP_XA_XA_OK &&
            std::none_of(recovered_xids, recovered_xids + recovered_count,
                         [&commit_xid](const OjpXid& recovered) {
                             return same_xid(commit_xid, recovered);
                         })) {
            throw std::runtime_error("XA recovery scan did not return the prepared XID");
        }
        require_success(xa.recover(xa_connection, OJP_XA_TMENDRSCAN,
                                   recovered_xids, 16, &recovered_count),
                        "OjpXARecover(end scan)", SQL_HANDLE_DBC, xa_connection);
        if (prepare_result == OJP_XA_XA_OK) {
            require_success(xa.commit(xa_connection, &commit_xid, SQL_FALSE),
                            "OjpXACommit(two-phase)", SQL_HANDLE_DBC, xa_connection);
        }
        if (row_count(surviving_statement, table, 1) !=
            (prepare_result == OJP_XA_XA_OK ? 1 : 0)) {
            throw std::runtime_error("PostgreSQL XA two-phase commit returned unexpected data");
        }

        const OjpXid rollback_xid = xid_for(table + "_rollback", "branch-2");
        require_success(xa.start(xa_connection, &rollback_xid, OJP_XA_TMNOFLAGS),
                        "OjpXAStart(rollback)", SQL_HANDLE_DBC, xa_connection);
        execute_direct(xa_statement, "INSERT INTO " + table + " VALUES (2, 'rollback')");
        require_success(xa.end(xa_connection, &rollback_xid, OJP_XA_TMSUCCESS),
                        "OjpXAEnd(rollback)", SQL_HANDLE_DBC, xa_connection);
        require_success(xa.rollback(xa_connection, &rollback_xid),
                        "OjpXARollback", SQL_HANDLE_DBC, xa_connection);
        if (row_count(surviving_statement, table, 2) != 0) {
            throw std::runtime_error("PostgreSQL XA rollback left the inserted row visible");
        }

        const OjpXid unavailable_xid = xid_for(table + "_unavailable", "branch-3");
        require_success(xa.start(xa_connection, &unavailable_xid, OJP_XA_TMNOFLAGS),
                        "OjpXAStart(affinity)", SQL_HANDLE_DBC, xa_connection);
        const long stopped_process_id = stop_server(pid_file, endpoints[0]);

        execute_direct(failover_statement, "UPDATE " + table +
                       " SET label = 'multinode after failure' WHERE id = 10");
        if (xa.end(xa_connection, &unavailable_xid, OJP_XA_TMSUCCESS) != SQL_ERROR) {
            throw std::runtime_error("an active PostgreSQL XA branch must not reroute after node loss");
        }
        SQLCHAR error_state[6] = {};
        SQLCHAR error_message[512] = {};
        SQLINTEGER xa_error = 0;
        SQLSMALLINT error_length = 0;
        require_success(SQLGetDiagRec(SQL_HANDLE_DBC, xa_connection, 1, error_state, &xa_error,
                                      error_message, sizeof(error_message), &error_length),
                        "SQLGetDiagRec(XA affinity failure)", SQL_HANDLE_DBC, xa_connection);
        if (std::string(reinterpret_cast<const char*>(error_state)) != "08S01" ||
            xa_error != OJP_XA_XAER_RMFAIL) {
            throw std::runtime_error("PostgreSQL XA node loss must report XAER_RMFAIL without rerouting");
        }

        verify_endpoint_available(surviving_statement);
        if (row_count(surviving_statement, table, 10) != 1 ||
            row_count(surviving_statement, table, 1) !=
                (prepare_result == OJP_XA_XA_OK ? 1 : 0)) {
            throw std::runtime_error("PostgreSQL data was not available through the surviving node");
        }
        const std::string duplicate_insert =
            "INSERT INTO " + table + " VALUES (10, 'duplicate')";
        if (SQL_SUCCEEDED(SQLExecDirect(surviving_statement, sql_text(duplicate_insert), SQL_NTS))) {
            throw std::runtime_error("PostgreSQL duplicate-key error unexpectedly succeeded");
        }
        if (SQLGetDiagRec(SQL_HANDLE_STMT, surviving_statement, 1, error_state, &xa_error,
                          error_message, sizeof(error_message), &error_length) != SQL_SUCCESS ||
            std::string(reinterpret_cast<const char*>(error_state)) == "08S01") {
            throw std::runtime_error("PostgreSQL constraint errors must not be transport failures");
        }
        require_success(SQLFreeStmt(surviving_statement, SQL_CLOSE), "SQLFreeStmt(error close)",
                        SQL_HANDLE_STMT, surviving_statement);

        wait_for_server_restart(pid_file, stopped_process_id);
        recovered_connection = open_connection(environment, config, endpoints[0]);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, recovered_connection,
                                       reinterpret_cast<SQLHANDLE*>(&recovered_statement)),
                        "SQLAllocHandle(recovered statement)", SQL_HANDLE_DBC, recovered_connection);
        verify_endpoint_available(recovered_statement);
        if (row_count(recovered_statement, table, 10) != 1) {
            throw std::runtime_error("recovered PostgreSQL OJP node did not reuse the database");
        }
        execute_direct(recovered_statement, "INSERT INTO " + table +
                       " VALUES (20, 'recovered-node reuse')");
        if (row_count(surviving_statement, table, 20) != 1) {
            throw std::runtime_error("recovered PostgreSQL node data was not visible on the cluster");
        }
        execute_direct(surviving_statement, "DROP TABLE " + table);
        table_created = false;

        require_success(SQLFreeHandle(SQL_HANDLE_STMT, recovered_statement),
                        "SQLFreeHandle(recovered statement)", SQL_HANDLE_DBC, recovered_connection);
        recovered_statement = SQL_NULL_HSTMT;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, failover_statement),
                        "SQLFreeHandle(failover statement)", SQL_HANDLE_DBC, failover_connection);
        failover_statement = SQL_NULL_HSTMT;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, xa_statement),
                        "SQLFreeHandle(XA statement)", SQL_HANDLE_DBC, xa_connection);
        xa_statement = SQL_NULL_HSTMT;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, surviving_statement),
                        "SQLFreeHandle(surviving statement)", SQL_HANDLE_DBC, surviving_connection);
        surviving_statement = SQL_NULL_HSTMT;
        close_connection(&recovered_connection);
        close_connection(&failover_connection);
        close_connection(&xa_connection);
        close_connection(&surviving_connection);
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
        dlclose(driver_module);
        driver_module = nullptr;
    } catch (...) {
        if (table_created && surviving_statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(surviving_statement, sql_text(drop), SQL_NTS);
        }
        if (recovered_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, recovered_statement);
        }
        if (failover_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, failover_statement);
        }
        if (xa_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, xa_statement);
        }
        if (surviving_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, surviving_statement);
        }
        close_connection(&recovered_connection);
        close_connection(&failover_connection);
        close_connection(&xa_connection);
        close_connection(&surviving_connection);
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        if (driver_module != nullptr) {
            dlclose(driver_module);
        }
        throw;
    }
    std::cout << "C++ ODBC PostgreSQL L10 multinode and XA integration test passed\n";
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
