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
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
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
    decltype(&OjpXAForget) forget;
    decltype(&OjpXASetTransactionTimeout) set_timeout;
    decltype(&OjpXAGetTransactionTimeout) get_timeout;
    decltype(&OjpXAIsSameRM) is_same_rm;
};

DatabaseConfig read_connection_config(const std::string& path) {
    std::ifstream input(path);
    std::string line;
    if (!input || !std::getline(input, line)) {
        throw std::runtime_error("cannot read the H2 L9 connection CSV");
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
        throw std::runtime_error("expected JDBC URL, username, and password in H2 L9 CSV");
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
                     SQLSMALLINT handle_type = SQL_HANDLE_ENV, SQLHANDLE handle = SQL_NULL_HANDLE) {
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
        const SQLRETURN diagnostic_result =
            SQLGetDiagRec(handle_type, handle, 1, state, &native_error, detail, sizeof(detail),
                          &detail_length);
        if (SQL_SUCCEEDED(diagnostic_result)) {
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
        load_symbol<decltype(&OjpXARecover)>(module, "OjpXARecover"),
        load_symbol<decltype(&OjpXAForget)>(module, "OjpXAForget"),
        load_symbol<decltype(&OjpXASetTransactionTimeout)>(module, "OjpXASetTransactionTimeout"),
        load_symbol<decltype(&OjpXAGetTransactionTimeout)>(module, "OjpXAGetTransactionTimeout"),
        load_symbol<decltype(&OjpXAIsSameRM)>(module, "OjpXAIsSameRM")};
}

std::string environment_value(const char* name) {
    const char* value = std::getenv(name);
    return value == nullptr ? std::string{} : std::string(value);
}

bool enabled(const std::string& value) {
    std::string normalized = value;
    std::transform(normalized.begin(), normalized.end(), normalized.begin(),
                   [](unsigned char character) {
                       return static_cast<char>(std::tolower(character));
                   });
    return normalized == "true" || normalized == "1" || normalized == "yes";
}

bool disabled(const std::string& value) {
    std::string normalized = value;
    std::transform(normalized.begin(), normalized.end(), normalized.begin(),
                   [](unsigned char character) {
                       return static_cast<char>(std::tolower(character));
                   });
    return normalized.empty() || normalized == "false" || normalized == "0" ||
        normalized == "no";
}

SQLHDBC open_connection(SQLHENV environment, const DatabaseConfig& config,
                        const std::string& endpoint, bool xa) {
    SQLHDBC connection = SQL_NULL_HDBC;
    require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment,
                                   reinterpret_cast<SQLHANDLE*>(&connection)),
                    "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);
    const std::string connection_string =
        "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
        ";DATABASE=" + brace_value(config.url) +
        ";UID=" + brace_value(config.user) +
        ";P"
        "WD=" + brace_value(config.password) + ";" +
        "OJP.LOADAWARE.SELECTION.ENABLED=false;" +
        (xa ? "OJP.XA=TRUE;" : "");
    require_success(SQLDriverConnect(connection, nullptr,
                                     reinterpret_cast<SQLCHAR*>(
                                         const_cast<char*>(connection_string.c_str())),
                                     SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                    "SQLDriverConnect", SQL_HANDLE_DBC, connection);
    return connection;
}

SQLCHAR* sql_text(const std::string& sql) {
    return reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str()));
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
            "expected H2 CSV path, enable variable, endpoint variable, target PID variable, and driver path");
    }
    const std::string enable_variable = argv[2];
    const std::string enabled_value = environment_value(enable_variable.c_str());
    if (!enabled(enabled_value)) {
        if (!disabled(enabled_value)) {
            throw std::runtime_error(enable_variable + " must be true or false");
        }
        std::cout << "Skipped: set " << enable_variable << "=true to run the H2 L9 suite\n";
        return 77;
    }
    const std::string endpoints = environment_value(argv[3]);
    if (endpoints.empty()) {
        throw std::runtime_error(std::string(argv[3]) + " is required when " +
                                 enable_variable + "=true");
    }
    std::vector<std::string> endpoint_list;
    std::istringstream endpoint_stream(endpoints);
    std::string endpoint;
    while (std::getline(endpoint_stream, endpoint, ',')) {
        if (endpoint.empty()) {
            throw std::runtime_error(std::string(argv[3]) + " must contain non-empty endpoints");
        }
        endpoint_list.push_back(endpoint);
    }
    if (endpoint_list.size() < 2) {
        throw std::runtime_error(std::string(argv[3]) + " must contain at least two OJP servers");
    }
    const std::string pid_file = environment_value(argv[4]);
    if (pid_file.empty()) {
        throw std::runtime_error(std::string(argv[4]) + " is required when " +
                                 enable_variable + "=true");
    }
    const DatabaseConfig config = read_connection_config(argv[1]);
    const auto timestamp = std::chrono::steady_clock::now().time_since_epoch().count();
    const std::string table = "ojp_cpp_l9_" + std::to_string(timestamp);
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC regular_connection = SQL_NULL_HDBC;
    SQLHDBC xa_connection = SQL_NULL_HDBC;
    SQLHDBC second_xa_connection = SQL_NULL_HDBC;
    SQLHSTMT regular_statement = SQL_NULL_HSTMT;
    SQLHSTMT xa_statement = SQL_NULL_HSTMT;
    void* driver_module = nullptr;
    bool table_created = false;
    try {
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE,
                                       reinterpret_cast<SQLHANDLE*>(&environment)),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);
        regular_connection = open_connection(environment, config, endpoints, false);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, regular_connection,
                                       reinterpret_cast<SQLHANDLE*>(&regular_statement)),
                        "SQLAllocHandle(regular statement)", SQL_HANDLE_DBC, regular_connection);
        execute_direct(regular_statement, "CREATE TABLE " + table +
                                          " (id INT PRIMARY KEY, label VARCHAR(100))");
        table_created = true;

        xa_connection = open_connection(environment, config, endpoints, true);
        second_xa_connection = open_connection(environment, config, endpoints, true);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, xa_connection,
                                       reinterpret_cast<SQLHANDLE*>(&xa_statement)),
                        "SQLAllocHandle(XA statement)", SQL_HANDLE_DBC, xa_connection);
        SQLULEN auto_commit = SQL_AUTOCOMMIT_ON;
        require_success(SQLGetConnectAttr(xa_connection, SQL_ATTR_AUTOCOMMIT, &auto_commit,
                                          sizeof(auto_commit), nullptr),
                        "SQLGetConnectAttr(autocommit)", SQL_HANDLE_DBC, xa_connection);
        if (auto_commit != SQL_AUTOCOMMIT_OFF) {
            throw std::runtime_error("OJP XA connections must have autocommit disabled");
        }

        driver_module = dlopen(argv[5], RTLD_NOW | RTLD_LOCAL);
        if (driver_module == nullptr) {
            throw std::runtime_error(std::string("cannot load OJP driver XA API: ") + dlerror());
        }
        const XaApi xa = load_xa_api(driver_module);
        SQLSMALLINT same_resource_manager = SQL_FALSE;
        require_success(xa.is_same_rm(xa_connection, second_xa_connection,
                                      &same_resource_manager),
                        "OjpXAIsSameRM", SQL_HANDLE_DBC, xa_connection);
        if (same_resource_manager != SQL_TRUE) {
            throw std::runtime_error("connections to the same H2 database must share an RM");
        }

        const OjpXid two_phase_xid = xid_for(table + "_two_phase", "branch-1");
        require_success(xa.start(xa_connection, &two_phase_xid, OJP_XA_TMNOFLAGS),
                        "OjpXAStart(two-phase)", SQL_HANDLE_DBC, xa_connection);
        execute_direct(xa_statement, "INSERT INTO " + table + " VALUES (1, 'two-phase')");
        require_success(xa.end(xa_connection, &two_phase_xid, OJP_XA_TMSUCCESS),
                        "OjpXAEnd(two-phase)", SQL_HANDLE_DBC, xa_connection);
        SQLINTEGER prepare_result = -1;
        require_success(xa.prepare(xa_connection, &two_phase_xid, &prepare_result),
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
                         [&two_phase_xid](const OjpXid& recovered) {
                             return same_xid(two_phase_xid, recovered);
                         })) {
            throw std::runtime_error("XA recovery scan did not return the prepared XID");
        }
        require_success(xa.recover(xa_connection, OJP_XA_TMENDRSCAN,
                                   recovered_xids, 16, &recovered_count),
                        "OjpXARecover(end scan)", SQL_HANDLE_DBC, xa_connection);
        if (prepare_result == OJP_XA_XA_OK) {
            require_success(xa.commit(xa_connection, &two_phase_xid, SQL_FALSE),
                            "OjpXACommit(two-phase)", SQL_HANDLE_DBC, xa_connection);
        }
        if (row_count(regular_statement, table, 1) != 1) {
            throw std::runtime_error("two-phase XA commit did not persist the inserted row");
        }

        const OjpXid rollback_xid = xid_for(table + "_rollback", "branch-2");
        require_success(xa.start(xa_connection, &rollback_xid, OJP_XA_TMNOFLAGS),
                        "OjpXAStart(rollback)", SQL_HANDLE_DBC, xa_connection);
        execute_direct(xa_statement, "INSERT INTO " + table + " VALUES (2, 'rollback')");
        require_success(xa.end(xa_connection, &rollback_xid, OJP_XA_TMSUCCESS),
                        "OjpXAEnd(rollback)", SQL_HANDLE_DBC, xa_connection);
        require_success(xa.rollback(xa_connection, &rollback_xid),
                        "OjpXARollback", SQL_HANDLE_DBC, xa_connection);
        if (row_count(regular_statement, table, 2) != 0) {
            throw std::runtime_error("XA rollback left the inserted row visible");
        }

        const OjpXid one_phase_xid = xid_for(table + "_one_phase", "branch-3");
        require_success(xa.start(xa_connection, &one_phase_xid, OJP_XA_TMNOFLAGS),
                        "OjpXAStart(one-phase)", SQL_HANDLE_DBC, xa_connection);
        execute_direct(xa_statement, "INSERT INTO " + table + " VALUES (3, 'one-phase')");
        require_success(xa.end(xa_connection, &one_phase_xid, OJP_XA_TMSUCCESS),
                        "OjpXAEnd(one-phase)", SQL_HANDLE_DBC, xa_connection);
        require_success(xa.commit(xa_connection, &one_phase_xid, SQL_TRUE),
                        "OjpXACommit(one-phase)", SQL_HANDLE_DBC, xa_connection);
        if (row_count(regular_statement, table, 3) != 1) {
            throw std::runtime_error("one-phase XA commit did not persist the inserted row");
        }

        SQLSMALLINT timeout_set = SQL_FALSE;
        require_success(xa.set_timeout(xa_connection, 30, &timeout_set),
                        "OjpXASetTransactionTimeout", SQL_HANDLE_DBC, xa_connection);
        SQLINTEGER timeout = -1;
        require_success(xa.get_timeout(xa_connection, &timeout),
                        "OjpXAGetTransactionTimeout", SQL_HANDLE_DBC, xa_connection);
        if ((timeout_set == SQL_TRUE && timeout != 30) ||
            (timeout_set == SQL_FALSE && timeout != 0)) {
            throw std::runtime_error("H2 XA timeout did not match the set/get result");
        }
        require_success(xa.set_timeout(xa_connection, 0, &timeout_set),
                        "OjpXASetTransactionTimeout(reset)", SQL_HANDLE_DBC, xa_connection);

        const SQLRETURN forget_result = xa.forget(xa_connection, &two_phase_xid);
        if (!SQL_SUCCEEDED(forget_result) && forget_result != SQL_ERROR) {
            throw std::runtime_error("OjpXAForget returned an unexpected status");
        }

        const OjpXid unavailable_branch = xid_for(table + "_unavailable", "branch-4");
        require_success(xa.start(xa_connection, &unavailable_branch, OJP_XA_TMNOFLAGS),
                        "OjpXAStart(unavailable server)", SQL_HANDLE_DBC, xa_connection);
        execute_direct(xa_statement, "INSERT INTO " + table + " VALUES (4, 'unavailable')");
        std::ifstream pid_input(pid_file);
        long target_pid = 0;
        if (!(pid_input >> target_pid) || target_pid <= 0) {
            throw std::runtime_error("H2 L9 target PID file does not contain a valid process ID");
        }
        if (kill(static_cast<pid_t>(target_pid), SIGTERM) != 0) {
            throw std::runtime_error("unable to stop the OJP server hosting the XA branch");
        }
        bool target_stopped = false;
        for (int attempt = 0; attempt < 100; ++attempt) {
            if (kill(static_cast<pid_t>(target_pid), 0) != 0) {
                target_stopped = true;
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        if (!target_stopped) {
            throw std::runtime_error("OJP server hosting the XA branch did not stop");
        }
        const SQLRETURN xa_end_result =
            xa.end(xa_connection, &unavailable_branch, OJP_XA_TMSUCCESS);
        if (xa_end_result != SQL_ERROR) {
            throw std::runtime_error("XA end should fail when its pinned OJP server is unavailable");
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
            throw std::runtime_error("XA affinity failure must report XAER_RMFAIL without rerouting");
        }
        if (row_count(regular_statement, table, 1) != 1) {
            throw std::runtime_error("the other OJP server became unavailable with the XA target");
        }

        execute_direct(regular_statement, "DROP TABLE " + table);
        table_created = false;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, xa_statement),
                        "SQLFreeHandle(XA statement)", SQL_HANDLE_DBC, xa_connection);
        xa_statement = SQL_NULL_HSTMT;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, regular_statement),
                        "SQLFreeHandle(regular statement)", SQL_HANDLE_DBC, regular_connection);
        regular_statement = SQL_NULL_HSTMT;
        close_connection(&second_xa_connection);
        close_connection(&xa_connection);
        close_connection(&regular_connection);
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment),
                        "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
        dlclose(driver_module);
        driver_module = nullptr;
    } catch (...) {
        if (table_created && regular_statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(regular_statement, sql_text(drop), SQL_NTS);
        }
        if (xa_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, xa_statement);
        }
        if (regular_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, regular_statement);
        }
        close_connection(&second_xa_connection);
        close_connection(&xa_connection);
        close_connection(&regular_connection);
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        if (driver_module != nullptr) {
            dlclose(driver_module);
        }
        throw;
    }
    std::cout << "C++ ODBC H2 L9 XA integration test passed\n";
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
