#pragma once

#include <cstdio>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#define OJP_TEST_POPEN _popen
#define OJP_TEST_PCLOSE _pclose
#else
#define OJP_TEST_POPEN popen
#define OJP_TEST_PCLOSE pclose
#endif

// Runs tests/fake_statement_server.cpp in a child process and collects the calls it reports.
class FakeServerProcess {
public:
    explicit FakeServerProcess(const std::string& executable) {
        const std::string command = "\"" + executable + "\"";
        pipe_ = OJP_TEST_POPEN(command.c_str(), "r");
        if (pipe_ == nullptr) {
            throw std::runtime_error("Could not start " + executable);
        }
        const std::string first = read_line();
        if (first.rfind("PORT ", 0) != 0) {
            throw std::runtime_error("Fake server did not report its port: " + first);
        }
        port_ = std::stoi(first.substr(5));
        // The server blocks once the pipe buffer is full, so its output is drained as it arrives.
        reader_ = std::thread([this]() {
            for (std::string line = read_line(); !line.empty(); line = read_line()) {
                lines_.push_back(line);
            }
        });
    }

    ~FakeServerProcess() {
        if (reader_.joinable()) {
            reader_.join();
        }
        if (pipe_ != nullptr) {
            OJP_TEST_PCLOSE(pipe_);
        }
    }

    FakeServerProcess(const FakeServerProcess&) = delete;
    FakeServerProcess& operator=(const FakeServerProcess&) = delete;

    int port() const { return port_; }

    std::string connection_string(const std::string& database) const {
        return "SERVER=127.0.0.1:" + std::to_string(port_) + ";DATABASE=" + database +
               ";OJP.LOADAWARE.SELECTION.ENABLED=false;";
    }

    // Waits for the server to exit (after SQLDisconnect) and returns the calls it reported.
    std::vector<std::string> finish() {
        reader_.join();
        OJP_TEST_PCLOSE(pipe_);
        pipe_ = nullptr;
        return lines_;
    }

private:
    std::string read_line() {
        std::string line;
        char buffer[512];
        while (std::fgets(buffer, sizeof(buffer), pipe_) != nullptr) {
            line += buffer;
            if (!line.empty() && line.back() == '\n') {
                break;
            }
        }
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) {
            line.pop_back();
        }
        return line;
    }

    std::FILE* pipe_ = nullptr;
    int port_ = 0;
    std::thread reader_;
    std::vector<std::string> lines_;
};
