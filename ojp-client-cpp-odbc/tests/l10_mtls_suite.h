#ifndef OJP_TEST_L10_MTLS_SUITE_H
#define OJP_TEST_L10_MTLS_SUITE_H

#include "connection_suite.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace ojp::test {

inline std::string mtls_brace_value(const std::string& value) {
    std::string escaped;
    for (const char character : value) {
        escaped.push_back(character);
        if (character == '}') {
            escaped.push_back('}');
        }
    }
    return "{" + escaped + "}";
}

struct L10MtlsConfig {
    std::string rootcert;
    std::string cert;
    std::string key;
    std::string untrusted_cert;
    std::string untrusted_key;

    std::string options(const std::string& client_cert, const std::string& client_key) const {
        return "OJP.CLIENT.TLS.ENABLED=true;OJP.CLIENT.TLS.ROOTCERT=" +
               mtls_brace_value(rootcert) + ";OJP.CLIENT.TLS.CERT=" +
               mtls_brace_value(client_cert) + ";OJP.CLIENT.TLS.KEY=" +
               mtls_brace_value(client_key) + ";";
    }

    std::string trusted_options() const {
        return options(cert, key);
    }
};

inline const L10MtlsConfig& l10_mtls_config() {
    static const L10MtlsConfig config = [] {
        const char* enabled_env = std::getenv("OJP_CLIENT_TLS_ENABLED");
        std::string enabled = enabled_env == nullptr ? "" : enabled_env;
        std::transform(enabled.begin(), enabled.end(), enabled.begin(),
                       [](unsigned char character) {
                           return static_cast<char>(std::tolower(character));
                       });
        if (enabled != "true" && enabled != "1" && enabled != "yes") {
            throw std::runtime_error("L10 requires OJP_CLIENT_TLS_ENABLED=true");
        }
        const auto required_file = [](const char* name) {
            const char* value = std::getenv(name);
            if (value == nullptr || *value == '\0') {
                throw std::runtime_error(std::string("L10 requires ") + name);
            }
            std::ifstream input(value, std::ios::binary);
            if (!input || input.peek() == std::ifstream::traits_type::eof()) {
                throw std::runtime_error(std::string("L10 requires a readable, nonempty file: ") +
                                         name);
            }
            return std::string(value);
        };
        return L10MtlsConfig{
            required_file("OJP_CLIENT_TLS_ROOTCERT"),
            required_file("OJP_CLIENT_TLS_CERT"),
            required_file("OJP_CLIENT_TLS_KEY"),
            required_file("OJP_TEST_TLS_UNTRUSTED_CERT"),
            required_file("OJP_TEST_TLS_UNTRUSTED_KEY")};
    }();
    return config;
}

inline void require_mtls_success(SQLRETURN result, const std::string& operation,
                                 SQLSMALLINT handle_type, SQLHANDLE handle) {
    if (SQL_SUCCEEDED(result)) {
        return;
    }
    SQLCHAR state[6] = {};
    SQLCHAR detail[1024] = {};
    SQLINTEGER native_error = 0;
    SQLSMALLINT length = 0;
    SQLGetDiagRec(handle_type, handle, 1, state, &native_error, detail, sizeof(detail), &length);
    throw std::runtime_error(operation + " [" + reinterpret_cast<const char*>(state) + "] " +
                             reinterpret_cast<const char*>(detail));
}

inline void verify_l10_mtls(const DatabaseConfig& config,
                            const std::vector<std::string>& endpoints) {
    const auto& tls = l10_mtls_config();
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    const auto cleanup = [&] {
        if (statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, statement);
            statement = SQL_NULL_HSTMT;
        }
        if (connection != SQL_NULL_HDBC) {
            SQLDisconnect(connection);
            SQLFreeHandle(SQL_HANDLE_DBC, connection);
            connection = SQL_NULL_HDBC;
        }
    };
    try {
        require_mtls_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment),
                             "SQLAllocHandle(mTLS environment)", SQL_HANDLE_ENV, environment);
        require_mtls_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                             reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                             "SQLSetEnvAttr(mTLS)", SQL_HANDLE_ENV, environment);
        for (const auto& endpoint : endpoints) {
            const std::string base =
                "DRIVER={OJP};SERVER=" + mtls_brace_value(endpoint) +
                ";DATABASE=" + mtls_brace_value(config.url) +
                ";UID=" + mtls_brace_value(config.user) +
                ";PWD=" + mtls_brace_value(config.password) +
                ";OJP.MULTINODE.RETRY.ATTEMPTS=1;";
            const auto connect = [&](const std::string& options) {
                require_mtls_success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection),
                                     "SQLAllocHandle(mTLS connection)", SQL_HANDLE_ENV, environment);
                const std::string text = base + options;
                return SQLDriverConnect(connection, nullptr,
                    reinterpret_cast<SQLCHAR*>(const_cast<char*>(text.c_str())), SQL_NTS,
                    nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT);
            };
            const SQLRETURN trusted_result = connect(tls.trusted_options());
            require_mtls_success(trusted_result,
                                 "trusted mTLS SQLDriverConnect at " + endpoint,
                                 SQL_HANDLE_DBC, connection);
            require_mtls_success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement),
                                 "SQLAllocHandle(mTLS statement)", SQL_HANDLE_DBC, connection);
            SQLCHAR query[] = "SELECT 1";
            require_mtls_success(SQLExecDirect(statement, query, SQL_NTS),
                                 "mTLS SELECT 1 at " + endpoint, SQL_HANDLE_STMT, statement);
            require_mtls_success(SQLFetch(statement), "mTLS SQLFetch at " + endpoint,
                                 SQL_HANDLE_STMT, statement);
            SQLINTEGER value = 0;
            SQLLEN length = 0;
            require_mtls_success(SQLGetData(statement, 1, SQL_C_SLONG, &value, sizeof(value), &length),
                                 "mTLS SQLGetData at " + endpoint, SQL_HANDLE_STMT, statement);
            if (value != 1 || length == SQL_NULL_DATA) {
                throw std::runtime_error("trusted mTLS query returned an unexpected result at " +
                                         endpoint);
            }
            cleanup();
            const auto reject = [&](const std::string& options, const std::string& scenario) {
                const SQLRETURN result = connect(options);
                SQLCHAR state[6] = {};
                SQLINTEGER native_error = 0;
                SQLCHAR detail[1024] = {};
                SQLSMALLINT length = 0;
                const SQLRETURN diagnostic = SQLGetDiagRec(SQL_HANDLE_DBC, connection, 1, state,
                    &native_error, detail, sizeof(detail), &length);
                const std::string actual_state = reinterpret_cast<const char*>(state);
                cleanup();
                if (result != SQL_ERROR || !SQL_SUCCEEDED(diagnostic) || actual_state != "08001") {
                    throw std::runtime_error(scenario + " must fail SQLDriverConnect with SQLSTATE "
                                             "08001 at " + endpoint + "; received " + actual_state);
                }
            };
            // Empty explicit options must suppress the trusted credentials in the environment.
            reject(tls.options("", ""), "missing mTLS client certificate");
            reject(tls.options(tls.untrusted_cert, tls.untrusted_key),
                   "untrusted mTLS client certificate");
            std::cout << "mTLS query and missing/untrusted client rejection passed at "
                      << endpoint << '\n' << std::flush;
        }
        require_mtls_success(SQLFreeHandle(SQL_HANDLE_ENV, environment),
                             "SQLFreeHandle(mTLS environment)", SQL_HANDLE_ENV, environment);
        environment = SQL_NULL_HENV;
    } catch (...) {
        cleanup();
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        throw;
    }
}

}  // namespace ojp::test

#endif
