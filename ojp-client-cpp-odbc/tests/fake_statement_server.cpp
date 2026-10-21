// Fake OJP server used by the driver unit tests. It runs in its own process because on Windows
// gRPC is linked statically: two gRPC copies (test executable and ojp_odbc.dll) cannot coexist
// in one process.
//
// Protocol on stdout: the first line is "PORT <port>"; every handled call is then reported as
// one line, so the test can verify the requests after the server exits. The server exits when
// a session is terminated (SQLDisconnect) or after kIdleLimit.
#include "StatementService.grpc.pb.h"

#include <grpcpp/grpcpp.h>

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
        report("executeUpdate " + request->sql());
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
