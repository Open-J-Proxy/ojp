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
        return grpc::Status::OK;
    }

    // SQL containing "slow" blocks until the client cancels the call or its deadline expires.
    grpc::Status executeUpdate(grpc::ServerContext* context, const proto::StatementRequest* request,
                               proto::OpResult* response) override {
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
