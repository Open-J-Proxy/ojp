#ifndef OJP_TEST_CONNECTION_SUITE_H
#define OJP_TEST_CONNECTION_SUITE_H

#include <sql.h>
#include <sqlext.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace ojp::test {

struct DatabaseConfig {
    std::string url;
    std::string user;
    std::string password;
    std::size_t row;
};

inline std::vector<DatabaseConfig> read_connections(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("cannot open the database connection CSV");
    }
    enum class State { start, unquoted, quoted, closed };
    State state = State::start;
    std::vector<DatabaseConfig> connections;
    std::vector<std::string> fields;
    std::string field;
    std::size_t line = 1;
    std::size_t row = 1;
    bool nonempty = false;
    const auto invalid = [&] {
        return std::runtime_error("invalid database connection CSV at row " +
                                  std::to_string(row) +
                                  ": expected JDBC URL, username, and password");
    };
    const auto finish_record = [&] {
        if (nonempty) {
            fields.push_back(field);
            if (fields.size() != 3 || fields[0].empty()) {
                throw invalid();
            }
            connections.push_back({fields[0], fields[1], fields[2], row});
        }
        fields.clear();
        field.clear();
        state = State::start;
        nonempty = false;
    };
    char character;
    while (input.get(character)) {
        if (state == State::quoted) {
            if (character == '"') {
                state = State::closed;
            } else {
                field.push_back(character);
                if (character == '\n') {
                    ++line;
                }
            }
            continue;
        }
        if (character == '\n' || character == '\r') {
            if (character == '\r' && input.peek() == '\n') {
                input.get();
            }
            finish_record();
            row = ++line;
            continue;
        }
        nonempty = true;
        if (character == ',') {
            fields.push_back(field);
            field.clear();
            state = State::start;
        } else if (character == '"') {
            if (state == State::start) {
                state = State::quoted;
            } else if (state == State::closed) {
                field.push_back('"');
                state = State::quoted;
            } else {
                throw invalid();
            }
        } else {
            if (state == State::closed) {
                throw invalid();
            }
            field.push_back(character);
            state = State::unquoted;
        }
    }
    if (!input.eof()) {
        throw std::runtime_error("cannot read the database connection CSV");
    }
    if (state == State::quoted) {
        throw invalid();
    }
    finish_record();
    if (connections.empty()) {
        throw std::runtime_error("database connection CSV is empty");
    }
    return connections;
}

inline std::string redact(std::string message, const DatabaseConfig& config) {
    std::vector<std::string> values{config.url, config.user, config.password};
    std::sort(values.begin(), values.end(), [](const auto& left, const auto& right) {
        return left.size() > right.size();
    });
    for (const auto& value : values) {
        if (value.empty()) {
            continue;
        }
        std::size_t offset = 0;
        while ((offset = message.find(value, offset)) != std::string::npos) {
            message.replace(offset, value.size(), "[redacted]");
            offset += 10;
        }
    }
    return message;
}

inline void wait_for_restart(const std::string& pid_file, long stopped_pid,
                             const std::string& connection_string) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    while (std::chrono::steady_clock::now() < deadline) {
        std::ifstream input(pid_file);
        long current_pid = 0;
        if (input >> current_pid && current_pid > 0 && current_pid != stopped_pid) {
            SQLHENV environment = SQL_NULL_HENV;
            SQLHDBC connection = SQL_NULL_HDBC;
            SQLHSTMT statement = SQL_NULL_HSTMT;
            bool ready = false;
            bool connected = false;
            bool cleaned = true;
            if (SQL_SUCCEEDED(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment)) &&
                SQL_SUCCEEDED(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                    reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER)) &&
                SQL_SUCCEEDED(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection))) {
                connected = SQL_SUCCEEDED(SQLDriverConnect(connection, nullptr,
                    reinterpret_cast<SQLCHAR*>(const_cast<char*>(connection_string.c_str())),
                    SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT));
                if (connected &&
                    SQL_SUCCEEDED(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement))) {
                    SQLCHAR query[] = "SELECT 1";
                    SQLINTEGER value = 0;
                    SQLLEN length = 0;
                    ready = SQL_SUCCEEDED(SQLExecDirect(statement, query, SQL_NTS)) &&
                            SQL_SUCCEEDED(SQLFetch(statement)) &&
                            SQL_SUCCEEDED(SQLGetData(statement, 1, SQL_C_SLONG, &value,
                                                     sizeof(value), &length)) &&
                            length != SQL_NULL_DATA && value == 1;
                }
            }
            if (statement != SQL_NULL_HSTMT) {
                const auto result = SQLFreeHandle(SQL_HANDLE_STMT, statement);
                cleaned = SQL_SUCCEEDED(result) && cleaned;
            }
            if (connection != SQL_NULL_HDBC) {
                if (connected) {
                    const auto disconnect = SQLDisconnect(connection);
                    cleaned = SQL_SUCCEEDED(disconnect) && cleaned;
                }
                const auto release = SQLFreeHandle(SQL_HANDLE_DBC, connection);
                cleaned = SQL_SUCCEEDED(release) && cleaned;
            }
            if (environment != SQL_NULL_HENV) {
                const auto result = SQLFreeHandle(SQL_HANDLE_ENV, environment);
                cleaned = SQL_SUCCEEDED(result) && cleaned;
            }
            if (!cleaned) {
                throw std::runtime_error("failed to clean up OJP restart readiness handles");
            }
            if (ready) {
                return;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    throw std::runtime_error("the stopped OJP server did not restart and become ready within 30 seconds");
}

class RowDeadline {
public:
    RowDeadline(std::size_t row, std::chrono::milliseconds timeout)
        : watcher([this, row, timeout] {
            std::unique_lock<std::mutex> lock(mutex);
            if (!finished.wait_for(lock, timeout, [this] { return complete; })) {
                std::cerr << "CSV row " << row << " exceeded its execution deadline\n"
                          << std::flush;
                std::_Exit(EXIT_FAILURE);
            }
        }) {}

    ~RowDeadline() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            complete = true;
        }
        finished.notify_one();
        watcher.join();
    }

private:
    std::mutex mutex;
    std::condition_variable finished;
    bool complete = false;
    std::thread watcher;
};

template <typename Suite>
int run_connections(const std::string& path, Suite suite,
                    std::chrono::milliseconds row_timeout = std::chrono::seconds(180)) {
    // Validate the entire fixture before allocating handles or contacting any server.
    const auto connections = read_connections(path);
    for (const auto& config : connections) {
        std::cout << "Running CSV row " << config.row << '\n' << std::flush;
        try {
            RowDeadline deadline(config.row, row_timeout);
            const int result = suite(config);
            if (result != 0) {
                throw std::runtime_error("suite returned " + std::to_string(result));
            }
        } catch (const std::exception& error) {
            throw std::runtime_error("CSV row " + std::to_string(config.row) +
                                     " failed: " + redact(error.what(), config));
        } catch (...) {
            throw std::runtime_error("CSV row " + std::to_string(config.row) +
                                     " failed with an unknown exception");
        }
    }
    return 0;
}

}  // namespace ojp::test

#endif
