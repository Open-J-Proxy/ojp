#include <sql.h>
#include <sqlext.h>

#include "StatementService.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <array>
#include <iostream>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

namespace proto = com::openjproxy::grpc;

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void success(SQLRETURN result, const std::string& operation) {
    require(SQL_SUCCEEDED(result), operation + " failed");
}

void diagnostic(SQLHSTMT statement, const char* expected) {
    SQLCHAR state[6] = {};
    SQLCHAR message[256] = {};
    success(SQLGetDiagRec(SQL_HANDLE_STMT, statement, 1, state, nullptr,
                          message, sizeof(message), nullptr), "SQLGetDiagRec");
    require(std::string(reinterpret_cast<char*>(state)) == expected,
            "Unexpected SQLSTATE: " + std::string(reinterpret_cast<char*>(state)));
}

void prepare(SQLHSTMT statement, const std::string& sql, bool wide, bool explicit_length) {
    if (wide) {
        std::vector<SQLWCHAR> text(sql.begin(), sql.end());
        text.push_back(0);
        success(SQLPrepareW(statement, text.data(),
                            explicit_length ? static_cast<SQLINTEGER>(sql.size()) : SQL_NTS),
                "SQLPrepareW");
    } else {
        success(SQLPrepare(statement, reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str())),
                           explicit_length ? static_cast<SQLINTEGER>(sql.size()) : SQL_NTS),
                "SQLPrepare");
    }
}

void test_counts(SQLHDBC connection, SQLHSTMT statement) {
    SQLSMALLINT count = -1;
    require(SQLNumParams(SQL_NULL_HSTMT, &count) == SQL_INVALID_HANDLE, "Null handle");
    require(SQLNumParams(connection, &count) == SQL_INVALID_HANDLE, "Wrong handle type");
    require(SQLNumParams(statement, &count) == SQL_ERROR, "Unprepared statement");
    diagnostic(statement, "HY010");
    require(count == -1, "Error must not change output");
    require(SQLNumParams(statement, nullptr) == SQL_ERROR, "Null count");
    diagnostic(statement, "HY009");

    const std::vector<std::pair<std::string, SQLSMALLINT>> cases = {
        {"SELECT 1", 0},
        {"SELECT ?", 1},
        {"INSERT INTO t VALUES (?, ?, ?)", 3},
        {"SELECT '?', 'it''s ?', \"?\", \"a\"\"?\", `?`, `a``?`, [?], [a]]?], ?", 1},
        {"SELECT ? -- ?\n, ? /* ? */ , '?' -- ?", 2},
        {"SELECT ? -- ?\r, ?", 2},
        {"SELECT /* ? /* nested ? */ ? */ ?, ? /* ?", 2},
        {"SELECT $$ ? ' \" -- ? $$, $tag$ ? /* ? */ $tag$, ?", 1},
        {"SELECT $tag$ ? $other$ ? $tag$, ?", 1},
        {"SELECT E'escaped\\' ? and \\\\ ?', ?", 1},
        {"SELECT 'backslash\\', ?", 1},
        {"SELECT identifier$tag$ + ?, $1 + ?", 2},
        {"SELECT ? /* ' \" ? */ + ?", 2},
        {"SELECT 'unterminated ?", 0},
        {"SELECT $$ unterminated ?", 0}};
    for (const bool wide : {false, true}) {
        for (const bool explicit_length : {false, true}) {
            for (const auto& entry : cases) {
                prepare(statement, entry.first, wide, explicit_length);
                count = -1;
                success(SQLNumParams(statement, &count), "SQLNumParams");
                require(count == entry.second, "Incorrect count for: " + entry.first);
            }
            const auto maximum = std::numeric_limits<SQLSMALLINT>::max();
            prepare(statement, std::string(static_cast<std::size_t>(maximum), '?'),
                    wide, explicit_length);
            success(SQLNumParams(statement, &count), "Maximum parameter count");
            require(count == maximum, "Maximum parameter count was truncated");
            prepare(statement, std::string(static_cast<std::size_t>(maximum) + 1, '?'),
                    wide, explicit_length);
            count = -1;
            require(SQLNumParams(statement, &count) == SQL_ERROR, "Count overflow");
            diagnostic(statement, "22003");
            require(count == -1, "Overflow must not change output");
        }
    }
    prepare(statement, "SELECT ?", false, false);
    require(SQLNumParams(statement, nullptr) == SQL_ERROR, "Prepared null count");
    diagnostic(statement, "HY009");
    success(SQLNumParams(statement, &count), "Count after error");
    SQLCHAR state[6] = {};
    require(SQLGetDiagRec(SQL_HANDLE_STMT, statement, 1, state, nullptr, nullptr, 0, nullptr) ==
                SQL_NO_DATA, "Successful SQLNumParams must clear diagnostics");

    SQLUSMALLINT supported = SQL_FALSE;
    success(SQLGetFunctions(connection, SQL_API_SQLNUMPARAMS, &supported), "Individual support");
    require(supported == SQL_TRUE, "SQLNumParams not advertised");
    std::array<SQLUSMALLINT, 100> legacy{};
    success(SQLGetFunctions(connection, SQL_API_ALL_FUNCTIONS, legacy.data()), "ODBC 2 support");
    require(legacy[SQL_API_SQLNUMPARAMS] == SQL_TRUE, "SQLNumParams not in ODBC 2 bitmap");
    std::array<SQLUSMALLINT, SQL_API_ODBC3_ALL_FUNCTIONS_SIZE> modern{};
    success(SQLGetFunctions(connection, SQL_API_ODBC3_ALL_FUNCTIONS, modern.data()),
            "ODBC 3 support");
    require(SQL_FUNC_EXISTS(modern.data(), SQL_API_SQLNUMPARAMS), "SQLNumParams not in ODBC 3 bitmap");
}

class ParameterService final : public proto::StatementService::Service {
public:
    grpc::Status connect(grpc::ServerContext*, const proto::ConnectionDetails*,
                         proto::SessionInfo* response) override {
        response->set_connhash("parameter-test");
        return grpc::Status::OK;
    }

    grpc::Status executeUpdate(grpc::ServerContext*, const proto::StatementRequest* request,
                               proto::OpResult* response) override {
        std::lock_guard<std::mutex> lock(mutex_);
        last_request_ = *request;
        response->set_type(proto::INTEGER);
        response->set_int_value(1);
        return grpc::Status::OK;
    }

    grpc::Status terminateSession(grpc::ServerContext*, const proto::SessionInfo*,
                                  proto::SessionTerminationStatus* response) override {
        response->set_terminated(true);
        return grpc::Status::OK;
    }

    proto::StatementRequest last_request() {
        std::lock_guard<std::mutex> lock(mutex_);
        return last_request_;
    }

private:
    std::mutex mutex_;
    proto::StatementRequest last_request_;
};

void test_bindings(SQLHDBC connection, SQLHSTMT statement, ParameterService& service, int port) {
    std::string connection_string = "SERVER=127.0.0.1:" + std::to_string(port) +
        ";DATABASE=jdbc:h2:mem:parameters;OJP.LOADAWARE.SELECTION.ENABLED=false;";
    success(SQLDriverConnect(connection, nullptr,
                              reinterpret_cast<SQLCHAR*>(connection_string.data()), SQL_NTS,
                              nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT), "SQLDriverConnect");
    prepare(statement, "INSERT INTO t VALUES (?, ?, ?, ?, ?, ?)", false, false);
    for (const int sample : {-1, 0, 1}) {
        SQLINTEGER integer = sample < 0 ? std::numeric_limits<SQLINTEGER>::min() :
                             sample > 0 ? std::numeric_limits<SQLINTEGER>::max() : 0;
        SQLSMALLINT small = sample < 0 ? std::numeric_limits<SQLSMALLINT>::min() :
                           sample > 0 ? std::numeric_limits<SQLSMALLINT>::max() : 0;
        SQLSCHAR tiny = sample < 0 ? std::numeric_limits<SQLSCHAR>::min() :
                       sample > 0 ? std::numeric_limits<SQLSCHAR>::max() : 0;
        const std::array<SQLSMALLINT, 6> types = {
            SQL_C_LONG, SQL_C_SLONG, SQL_C_SHORT, SQL_C_SSHORT, SQL_C_TINYINT, SQL_C_STINYINT};
        const std::array<SQLSMALLINT, 6> sql_types = {
            SQL_INTEGER, SQL_INTEGER, SQL_SMALLINT, SQL_SMALLINT, SQL_TINYINT, SQL_TINYINT};
        const std::array<SQLPOINTER, 6> values = {
            &integer, &integer, &small, &small, &tiny, &tiny};
        const std::array<SQLLEN, 6> sizes = {
            sizeof(integer), sizeof(integer), sizeof(small), sizeof(small), sizeof(tiny), sizeof(tiny)};
        for (SQLUSMALLINT index = 0; index < types.size(); ++index) {
            success(SQLBindParameter(statement, index + 1, SQL_PARAM_INPUT, types[index],
                                      sql_types[index], 0, 0, values[index], sizes[index], nullptr),
                    "SQLBindParameter");
        }
        success(SQLExecute(statement), "SQLExecute integer aliases");
        const auto request = service.last_request();
        require(request.parameters_size() == 6, "Missing bound parameters");
        const std::array<proto::ParameterTypeProto, 6> expected_types = {
            proto::PT_INT, proto::PT_INT, proto::PT_SHORT, proto::PT_SHORT, proto::PT_BYTE, proto::PT_BYTE};
        const std::array<SQLINTEGER, 6> expected = {integer, integer, small, small, tiny, tiny};
        for (int index = 0; index < request.parameters_size(); ++index) {
            const auto& parameter = request.parameters(index);
            require(parameter.index() == index + 1 && parameter.type() == expected_types[index] &&
                    parameter.values_size() == 1 && parameter.values(0).has_int_value() &&
                    parameter.values(0).int_value() == expected[index],
                    "Incorrect signed integer wire value at parameter " + std::to_string(index + 1));
        }
    }
    success(SQLDisconnect(connection), "SQLDisconnect");
}

}  // namespace

int main() {
    try {
        ParameterService service;
        grpc::ServerBuilder builder;
        int port = 0;
        builder.AddListeningPort("127.0.0.1:0", grpc::InsecureServerCredentials(), &port);
        builder.RegisterService(&service);
        auto server = builder.BuildAndStart();
        require(server != nullptr && port != 0, "Could not start test gRPC service");

        SQLHENV environment = SQL_NULL_HENV;
        SQLHDBC connection = SQL_NULL_HDBC;
        SQLHSTMT statement = SQL_NULL_HSTMT;
        success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment), "Allocate environment");
        success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection), "Allocate connection");
        success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement), "Allocate statement");
        test_counts(connection, statement);
        test_bindings(connection, statement, service, port);
        success(SQLFreeHandle(SQL_HANDLE_STMT, statement), "Free statement");
        success(SQLFreeHandle(SQL_HANDLE_DBC, connection), "Free connection");
        success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "Free environment");
        server->Shutdown();
        std::cout << "Parameter counting and signed integer binding tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
