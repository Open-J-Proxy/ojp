// Fake OJP server used by the driver unit tests. It runs in its own process because on Windows
// gRPC is linked statically: two gRPC copies (test executable and ojp_odbc.dll) cannot coexist
// in one process.
//
// Protocol on stdout: the first line is "PORT <port>"; every handled call is then reported as
// one line, so the test can verify the requests after the server exits. The server exits when
// a session is terminated (SQLDisconnect) or after kIdleLimit.
#include "StatementService.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>

namespace {

namespace proto = com::openjproxy::grpc;
using Clock = std::chrono::steady_clock;

constexpr auto kIdleLimit = std::chrono::seconds(60);

class FakeStatementService final : public proto::StatementService::Service {
public:
    grpc::Status connect(grpc::ServerContext*, const proto::ConnectionDetails* request,
                         proto::SessionInfo* response) override {
        report("connect " + request->url());
        response->set_connhash("fake-server");
        // A URL containing "legacy" simulates a server without parameter-set support.
        if (request->url().find("legacy") == std::string::npos) {
            response->add_servercapabilities("PARAMETER_SETS");
        }
        return grpc::Status::OK;
    }

    // SQL containing "slow" blocks until the client cancels the call or its deadline expires.
    grpc::Status executeUpdate(grpc::ServerContext* context, const proto::StatementRequest* request,
                               proto::OpResult* response) override {
        for (const auto& property : request->properties()) {
            if (property.key() == "PREPARED_STATEMENT_PARAMETER_SET_SIZE_KEY") {
                return execute_parameter_sets(context, *request, property.int_value(), response);
            }
        }
        // The update-result-set request returns the first result set or the update count; the
        // fake server never has a result set, so it answers with the update count.
        const bool update_result_set = std::any_of(
            request->properties().begin(), request->properties().end(), [](const auto& property) {
                return property.key() == "STATEMENT_EXECUTE_UPDATE_RESULT_SET_KEY" &&
                       property.bool_value();
            });
        report((update_result_set ? "executeUpdateResultSet " : "executeUpdate ") + request->sql());
        if (request->sql().find("slow") != std::string::npos) {
            const auto limit = Clock::now() + std::chrono::seconds(10);
            while (!context->IsCancelled() && Clock::now() < limit) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            if (context->IsCancelled()) {
                report("cancelled " + request->sql());
                return grpc::Status(grpc::StatusCode::CANCELLED, "cancelled by client");
            }
        }
        response->set_type(proto::INTEGER);
        response->set_int_value(1);
        return grpc::Status::OK;
    }

    // DatabaseMetaData calls (getMetaData().getX(...)) are reported as "metadata X p1|p2|...",
    // with NULL parameters as "<null>", and answered with a result set in the JDBC shape.
    grpc::Status callResource(grpc::ServerContext*, const proto::CallResourceRequest* request,
                              proto::CallResourceResponse* response) override {
        if (request->resourcetype() == proto::RES_RESULT_SET &&
            request->target().calltype() == proto::CALL_CLOSE) {
            report("closeResultSet " + request->resourceuuid());
            return grpc::Status::OK;
        }
        if (request->target().resourcename() != "MetaData" || !request->target().has_nextcall()) {
            return grpc::Status(grpc::StatusCode::UNIMPLEMENTED, "unsupported resource call");
        }
        const auto& call = request->target().nextcall();
        std::string parameters;
        for (const auto& parameter : call.params()) {
            if (!parameters.empty()) {
                parameters += "|";
            }
            parameters += parameter.value_case() == proto::ParameterValue::kStringValue
                ? parameter.string_value() : "<null>";
        }
        report("metadata " + call.resourcename() + " " + parameters);
        std::lock_guard<std::mutex> lock(mutex_);
        pending_metadata_ = call.resourcename();
        response->add_values()->set_string_value("rs-" + call.resourcename());
        return grpc::Status::OK;
    }

    // Returns the rows of the last metadata call on the first fetch and no rows afterwards.
    grpc::Status fetchNextRows(grpc::ServerContext*, const proto::ResultSetFetchRequest*,
                               proto::OpResult* response) override {
        std::string method;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            method.swap(pending_metadata_);
        }
        auto* result = response->mutable_query_result();
        auto add_row = [result](std::initializer_list<proto::ParameterValue> values) {
            auto* row = result->add_rows();
            for (const auto& value : values) {
                *row->add_columns() = value;
            }
        };
        auto text = [](const char* value) {
            proto::ParameterValue parameter;
            if (value == nullptr) {
                parameter.set_is_null(true);
            } else {
                parameter.set_string_value(value);
            }
            return parameter;
        };
        auto number = [](int value) {
            proto::ParameterValue parameter;
            parameter.set_int_value(value);
            return parameter;
        };
        if (method == "Procedures") {
            for (const char* label : {"PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
                                      "RESERVED1", "RESERVED2", "RESERVED3", "REMARKS",
                                      "PROCEDURE_TYPE", "SPECIFIC_NAME"}) {
                result->add_labels(label);
            }
            add_row({text("db"), text("dbo"), text("add_values;1"), text(nullptr), text(nullptr),
                     text(nullptr), text(nullptr), number(2), text("add_values")});
        } else if (method == "ProcedureColumns") {
            for (const char* label : {"PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
                                      "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME",
                                      "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE",
                                      "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
                                      "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                                      "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME"}) {
                result->add_labels(label);
            }
            const struct {
                const char* name;
                int column_type;
                int ordinal;
            } parameters[] = {{"@RETURN_VALUE", 5, 0}, {"@a", 1, 1}, {"@r", 2, 2}};
            for (const auto& parameter : parameters) {
                add_row({text("db"), text("dbo"), text("add_values;1"), text(parameter.name),
                         number(parameter.column_type), number(4), text("int"), number(10),
                         number(4), number(0), number(10), number(1), text(nullptr),
                         text(nullptr), number(4), text(nullptr), text(nullptr),
                         number(parameter.ordinal), text("YES"), text("add_values")});
            }
        }
        return grpc::Status::OK;
    }

    grpc::Status terminateSession(grpc::ServerContext*, const proto::SessionInfo*,
                                  proto::SessionTerminationStatus* response) override {
        report("terminateSession");
        response->set_terminated(true);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            terminated_ = true;
        }
        terminated_cv_.notify_all();
        return grpc::Status::OK;
    }

    // Each set whose first parameter is 99 fails. SQL containing "partial" reports only the
    // counts before the failure (PostgreSQL); "every" reports all sets with EXECUTE_FAILED
    // (SQL Server).
    grpc::Status execute_parameter_sets(grpc::ServerContext* context,
                                        const proto::StatementRequest& request, int set_size,
                                        proto::OpResult* response) {
        const int sets = request.parameters_size() / set_size;
        report("executeParameterSets " + request.sql() + " " + std::to_string(set_size) + " " +
               std::to_string(sets));
        const bool partial = request.sql().find("partial") != std::string::npos;
        proto::SqlErrorResponse error;
        bool failed = false;
        int total = 0;
        for (int set = 0; set < sets; ++set) {
            const auto& first = request.parameters(set * set_size);
            if (first.index() != set * set_size + 1) {
                return grpc::Status(grpc::StatusCode::INVALID_ARGUMENT, "unexpected parameter index");
            }
            const bool set_fails = first.values_size() > 0 && first.values(0).int_value() == 99;
            if (set_fails) {
                failed = true;
                if (partial) {
                    break;
                }
                error.add_batchupdatecounts(-3);
                continue;
            }
            error.add_batchupdatecounts(1);
            response->add_batchupdatecounts(1);
            ++total;
        }
        if (failed) {
            error.set_sqlstate("23000");
            error.set_reason("duplicate key");
            error.set_vendorcode(2627);
            context->AddTrailingMetadata("sqlerrorresponse-bin", error.SerializeAsString());
            return grpc::Status(grpc::StatusCode::INTERNAL, "duplicate key");
        }
        response->set_type(proto::INTEGER);
        response->set_int_value(total);
        return grpc::Status::OK;
    }

    void wait_for_termination() {
        std::unique_lock<std::mutex> lock(mutex_);
        terminated_cv_.wait_for(lock, kIdleLimit, [this]() { return terminated_; });
    }

    void report(const std::string& line) {
        std::lock_guard<std::mutex> lock(mutex_);
        std::cout << line << std::endl;
    }

private:
    std::mutex mutex_;
    std::condition_variable terminated_cv_;
    bool terminated_ = false;
    std::string pending_metadata_;
};

}  // namespace

int main() {
    FakeStatementService service;
    grpc::ServerBuilder builder;
    int port = 0;
    builder.AddListeningPort("127.0.0.1:0", grpc::InsecureServerCredentials(), &port);
    builder.RegisterService(&service);
    auto server = builder.BuildAndStart();
    if (server == nullptr || port == 0) {
        std::cerr << "Could not start the fake OJP server\n";
        return 1;
    }
    service.report("PORT " + std::to_string(port));
    service.wait_for_termination();
    server->Shutdown(std::chrono::system_clock::now() + std::chrono::seconds(2));
    return 0;
}
