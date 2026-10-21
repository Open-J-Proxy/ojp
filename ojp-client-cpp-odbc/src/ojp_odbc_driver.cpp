#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <share.h>
#else
#include <unistd.h>
#endif
#include <sql.h>
#include <sqlext.h>

#include "StatementService.grpc.pb.h"
#include "ojp_odbc_dsn.h"
#include "ojp_odbc_xa.h"

#include <algorithm>
#include <atomic>
#include <charconv>
#include <cctype>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <random>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <type_traits>
#include <variant>
#include <vector>

#include <grpcpp/grpcpp.h>

namespace {

using com::openjproxy::grpc::ConnectionDetails;
using com::openjproxy::grpc::LobDataBlock;
using com::openjproxy::grpc::LobReference;
using com::openjproxy::grpc::LobType;
using com::openjproxy::grpc::OpQueryResultProto;
using com::openjproxy::grpc::OpResult;
using com::openjproxy::grpc::ParameterProto;
using com::openjproxy::grpc::ParameterTypeProto;
using com::openjproxy::grpc::ParameterValue;
using com::openjproxy::grpc::SessionInfo;
using com::openjproxy::grpc::StatementRequest;
using com::openjproxy::grpc::StatementService;
using com::openjproxy::grpc::XaCommitRequest;
using com::openjproxy::grpc::XaEndRequest;
using com::openjproxy::grpc::XaForgetRequest;
using com::openjproxy::grpc::XaGetTransactionTimeoutRequest;
using com::openjproxy::grpc::XaGetTransactionTimeoutResponse;
using com::openjproxy::grpc::XaIsSameRMRequest;
using com::openjproxy::grpc::XaIsSameRMResponse;
using com::openjproxy::grpc::XaPrepareRequest;
using com::openjproxy::grpc::XaPrepareResponse;
using com::openjproxy::grpc::XaRecoverRequest;
using com::openjproxy::grpc::XaRecoverResponse;
using com::openjproxy::grpc::XaResponse;
using com::openjproxy::grpc::XaRollbackRequest;
using com::openjproxy::grpc::XaSetTransactionTimeoutRequest;
using com::openjproxy::grpc::XaSetTransactionTimeoutResponse;
using com::openjproxy::grpc::XaStartRequest;
using com::openjproxy::grpc::XidProto;

constexpr char kRowByRowMode[] = "RESULT_SET_ROW_BY_ROW_MODE";
constexpr char kResultSetLastBlockFlag[] = "RESULT_SET_LAST_BLOCK";
constexpr char kUpdateResultSetFlag[] = "UPDATE_RESULT_SET";
constexpr char kUpdateResultSetRequestKey[] = "STATEMENT_EXECUTE_UPDATE_RESULT_SET_KEY";
using StatementStub = StatementService::Stub;

struct Diagnostic {
    std::string state = "HY000";
    SQLINTEGER native_error = 0;
    std::string message;
};

struct HandleBase {
    explicit HandleBase(SQLSMALLINT kind) : type(kind) {}
    virtual ~HandleBase() = default;

    SQLSMALLINT type;
    std::vector<Diagnostic> diagnostics;
};

struct EnvironmentHandle final : HandleBase {
    EnvironmentHandle() : HandleBase(SQL_HANDLE_ENV) {}
};

struct TlsConfig {
    bool enabled = false;
    std::string root_cert;
    std::string cert;
    std::string key;
    std::string identity;
    std::shared_ptr<grpc::ChannelCredentials> credentials;
};

struct StoredConnectionDetails {
    ConnectionDetails details;
    std::shared_ptr<TlsConfig> tls;
};

struct ConnectionHandle final : HandleBase {
    ConnectionHandle() : HandleBase(SQL_HANDLE_DBC) {}

    std::string endpoint;
    std::shared_ptr<TlsConfig> tls;
    std::vector<std::string> endpoints;
    std::string url;
    std::string user;
    std::string password;
    std::string client_uuid;
    std::string datasource_name = "default";
    bool xa_mode = false;
    std::shared_ptr<grpc::Channel> channel;
    std::shared_ptr<StatementStub> stub;
    std::map<std::string, std::shared_ptr<grpc::Channel>> affinity_channels;
    std::map<std::string, std::shared_ptr<StatementStub>> affinity_stubs;
    std::map<std::string, std::string> session_to_server;
    std::mutex routing_mutex;
    std::string last_rpc_endpoint;
    SessionInfo session;
    ConnectionDetails connection_details;
    int retry_attempts = 3;
    int retry_delay_ms = 100;
    std::size_t lob_chunk_size = 0;
    bool load_aware = true;
    std::mutex operation_mutex;
    std::set<std::string> savepoints;
    std::map<std::string, std::string> savepoint_names;
    SQLULEN transaction_isolation = 0;
    bool auto_commit = true;
    bool connected = false;
    // Attributes accepted only to be read back later (no effect on the gRPC transport).
    std::map<SQLINTEGER, SQLULEN> stored_attributes;
};

struct EndpointRuntime {
    explicit EndpointRuntime(std::string address_value) : address(std::move(address_value)) {}

    std::string address;
    std::shared_ptr<TlsConfig> tls;
    std::shared_ptr<grpc::Channel> channel;
    std::shared_ptr<StatementStub> stub;
    std::atomic<bool> healthy{true};
    std::atomic<int> active_connections{0};
    std::atomic<std::int64_t> last_failure_ms{0};
};

struct ClientRuntime {
    std::mutex mutex;
    std::map<std::string, std::shared_ptr<EndpointRuntime>> endpoints;
    std::map<std::string, std::string> conn_hash_by_key;
    std::map<std::string, StoredConnectionDetails> details_by_conn_hash;
    std::map<std::string, StoredConnectionDetails> xa_details_by_conn_hash;
    std::map<std::string, std::shared_ptr<TlsConfig>> tls_configs;
    std::map<std::string, std::string> cluster_health_by_endpoints;
    std::atomic<std::uint64_t> round_robin_counter{0};
    std::atomic<bool> stop_health_checks{false};
    std::once_flag health_check_once;
    std::mutex health_check_wait_mutex;
    std::condition_variable health_check_cv;
    std::thread health_check_thread;

    ~ClientRuntime() {
        stop_health_checks.store(true);
        health_check_cv.notify_all();
        if (health_check_thread.joinable()) {
            health_check_thread.join();
        }
    }
};

struct BoundParameter {
    SQLSMALLINT direction = SQL_PARAM_INPUT;
    SQLSMALLINT value_type = SQL_C_DEFAULT;
    SQLSMALLINT parameter_type = SQL_UNKNOWN_TYPE;
    SQLPOINTER value = nullptr;
    SQLLEN buffer_length = 0;
    SQLLEN* indicator = nullptr;
    std::string streamed_data;
};

struct BoundColumn {
    SQLSMALLINT value_type = SQL_C_DEFAULT;
    SQLPOINTER value = nullptr;
    SQLLEN buffer_length = 0;
    SQLLEN* indicator = nullptr;
};

struct UuidCell {
    std::string value;
};

using Cell = std::variant<std::monostate, bool, std::int32_t, std::int64_t, double, std::string,
                          std::vector<std::uint8_t>, UuidCell>;

// Actual column metadata (java.sql.ResultSetMetaData) retrieved from the OJP server.
struct ColumnMetadata {
    int jdbc_type = 0;
    int precision = 0;
    int scale = 0;
    int nullable = SQL_NULLABLE_UNKNOWN;
};

struct StatementHandle final : HandleBase {
    explicit StatementHandle(ConnectionHandle* parent)
        : HandleBase(SQL_HANDLE_STMT), connection(parent) {}

    // Implicit descriptors exposed to the Driver Manager through SQLGetStmtAttr.
    HandleBase descriptors[4] = {HandleBase(SQL_HANDLE_DESC), HandleBase(SQL_HANDLE_DESC),
                                 HandleBase(SQL_HANDLE_DESC), HandleBase(SQL_HANDLE_DESC)};

    ConnectionHandle* connection;
    std::mutex operation_mutex;
    std::string sql;
    std::map<SQLUSMALLINT, BoundParameter> parameters;
    std::map<SQLUSMALLINT, BoundColumn> bound_columns;
    std::vector<std::string> columns;
    // Empty when the server provided no metadata; the type is then inferred from the values.
    std::vector<ColumnMetadata> column_meta;
    std::vector<std::vector<Cell>> rows;
    std::map<SQLUSMALLINT, std::size_t> get_data_offsets;
    SQLLEN row_count = -1;
    std::size_t row_index = 0;
    bool has_result_set = false;
    std::vector<SQLUSMALLINT> data_at_execution_parameters;
    std::size_t data_at_execution_index = 0;
    SQLUSMALLINT current_data_at_execution_parameter = 0;
    bool waiting_for_data = false;
    // Stored statement attributes; the pointers are filled on fetch/execute.
    std::map<SQLINTEGER, SQLULEN> stored_attributes;
    SQLULEN* rows_fetched_ptr = nullptr;
    SQLUSMALLINT* row_status_ptr = nullptr;
    SQLULEN* params_processed_ptr = nullptr;
    SQLUSMALLINT* param_status_ptr = nullptr;
};

struct ParsedConnectionString {
    std::map<std::string, std::string> values;
    std::string error;
};

std::size_t count_parameter_markers(const std::string& sql) {
    std::size_t count = 0;
    for (std::size_t index = 0; index < sql.size();) {
        const char character = sql[index];
        if (character == '-' && index + 1 < sql.size() && sql[index + 1] == '-') {
            index = sql.find_first_of("\r\n", index + 2);
            if (index == std::string::npos) {
                break;
            }
        } else if (character == '/' && index + 1 < sql.size() && sql[index + 1] == '*') {
            index += 2;
            std::size_t depth = 1;
            while (index < sql.size() && depth != 0) {
                if (index + 1 < sql.size() && sql[index] == '/' && sql[index + 1] == '*') {
                    ++depth;
                    index += 2;
                } else if (index + 1 < sql.size() && sql[index] == '*' && sql[index + 1] == '/') {
                    --depth;
                    index += 2;
                } else {
                    ++index;
                }
            }
        } else if (character == '\'' || character == '"' || character == '`' || character == '[') {
            const char closing = character == '[' ? ']' : character;
            const bool escape_string = character == '\'' && index > 0 &&
                (sql[index - 1] == 'E' || sql[index - 1] == 'e') &&
                (index == 1 || (!std::isalnum(static_cast<unsigned char>(sql[index - 2])) &&
                               sql[index - 2] != '_' && sql[index - 2] != '$'));
            ++index;
            while (index < sql.size()) {
                if (escape_string && sql[index] == '\\' && index + 1 < sql.size()) {
                    index += 2;
                } else if (sql[index++] == closing) {
                    if (index < sql.size() && sql[index] == closing) {
                        ++index;
                    } else {
                        break;
                    }
                }
            }
        } else if (character == '$' &&
                   (index == 0 || (!std::isalnum(static_cast<unsigned char>(sql[index - 1])) &&
                                   sql[index - 1] != '_' && sql[index - 1] != '$'))) {
            std::size_t end = index + 1;
            if (end < sql.size() &&
                (std::isalpha(static_cast<unsigned char>(sql[end])) || sql[end] == '_')) {
                do {
                    ++end;
                } while (end < sql.size() &&
                         (std::isalnum(static_cast<unsigned char>(sql[end])) || sql[end] == '_'));
            }
            if (end < sql.size() && sql[end] == '$') {
                const auto delimiter = sql.substr(index, end - index + 1);
                const auto closing = sql.find(delimiter, end + 1);
                if (closing == std::string::npos) {
                    break;
                }
                index = closing + delimiter.size();
            } else {
                ++index;
            }
        } else {
            if (character == '?') {
                ++count;
            }
            ++index;
        }
    }
    return count;
}

ParsedConnectionString parse_connection_string(const std::string& input) {
    ParsedConnectionString parsed;
    std::size_t position = 0;
    while (position < input.size()) {
        while (position < input.size() &&
               (input[position] == ';' ||
                std::isspace(static_cast<unsigned char>(input[position])))) {
            ++position;
        }
        if (position == input.size()) {
            break;
        }
        const auto equals = input.find('=', position);
        if (equals == std::string::npos) {
            parsed.error = "ODBC connection string field is missing '='";
            return parsed;
        }
        std::string key = input.substr(position, equals - position);
        std::transform(key.begin(), key.end(), key.begin(), [](unsigned char ch) {
            return static_cast<char>(std::toupper(ch));
        });
        position = equals + 1;
        std::string value;
        if (position < input.size() && input[position] == '{') {
            ++position;
            bool closed = false;
            while (position < input.size()) {
                if (input[position] == '}' && position + 1 < input.size() &&
                    input[position + 1] == '}') {
                    value.push_back('}');
                    position += 2;
                } else if (input[position] == '}') {
                    ++position;
                    closed = true;
                    break;
                } else {
                    value.push_back(input[position++]);
                }
            }
            if (!closed) {
                parsed.error = "ODBC connection string contains an unterminated braced value";
                return parsed;
            }
            while (position < input.size() &&
                   std::isspace(static_cast<unsigned char>(input[position]))) {
                ++position;
            }
            if (position < input.size() && input[position] != ';') {
                parsed.error = "ODBC connection string has text after a braced value";
                return parsed;
            }
        } else {
            const auto end = input.find(';', position);
            value = input.substr(position, end == std::string::npos ? end : end - position);
            while (!value.empty() &&
                   std::isspace(static_cast<unsigned char>(value.back()))) {
                value.pop_back();
            }
            position = end == std::string::npos ? input.size() : end;
        }
        if (key.empty()) {
            parsed.error = "ODBC connection string contains an empty field name";
            return parsed;
        }
        parsed.values[key] = value;
        if (position < input.size() && input[position] == ';') {
            ++position;
        }
    }
    return parsed;
}

std::string make_client_uuid() {
    static std::mutex mutex;
    static std::string uuid;
    std::lock_guard<std::mutex> lock(mutex);
    if (!uuid.empty()) {
        return uuid;
    }
    std::random_device random;
    std::uint8_t bytes[16];
    for (auto& byte : bytes) {
        byte = static_cast<std::uint8_t>(random());
    }
    bytes[6] = static_cast<std::uint8_t>((bytes[6] & 0x0f) | 0x40);
    bytes[8] = static_cast<std::uint8_t>((bytes[8] & 0x3f) | 0x80);
    static constexpr char hex[] = "0123456789abcdef";
    uuid.reserve(36);
    for (std::size_t index = 0; index < 16; ++index) {
        if (index == 4 || index == 6 || index == 8 || index == 10) {
            uuid.push_back('-');
        }
        uuid.push_back(hex[bytes[index] >> 4]);
        uuid.push_back(hex[bytes[index] & 0x0f]);
    }
    return uuid;
}

ClientRuntime& client_runtime() {
    static ClientRuntime runtime;
    return runtime;
}

std::shared_ptr<EndpointRuntime> endpoint_runtime(
        const std::string& address, const std::shared_ptr<TlsConfig>& tls) {
    ClientRuntime& runtime = client_runtime();
    std::lock_guard<std::mutex> lock(runtime.mutex);
    const std::string cache_key = tls->identity + "|" + address;
    auto endpoint = runtime.endpoints.find(cache_key);
    if (endpoint != runtime.endpoints.end()) {
        return endpoint->second;
    }
    auto state = std::make_shared<EndpointRuntime>(address);
    state->tls = tls;
    // The server returns VARBINARY(MAX)/long text inline in the result; the gRPC default (4 MiB)
    // would cancel the call. No client-side limit, like ojp.grpc.maxInboundMessageSize in JDBC.
    grpc::ChannelArguments channel_args;
    channel_args.SetMaxReceiveMessageSize(-1);
    state->channel = grpc::CreateCustomChannel(address, tls->credentials, channel_args);
    state->stub = std::shared_ptr<StatementStub>(StatementService::NewStub(state->channel).release());
    runtime.endpoints.emplace(cache_key, state);
    return state;
}

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) {
        return {};
    }
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

bool parse_endpoint_list(std::string value, std::vector<std::string>* endpoints) {
    value = trim(std::move(value));
    if (value.size() >= 2 && value.front() == '[' && value.back() == ']') {
        value = value.substr(1, value.size() - 2);
    }
    std::size_t start = 0;
    while (start <= value.size()) {
        const auto separator = value.find(',', start);
        std::string address = trim(value.substr(
            start, separator == std::string::npos ? separator : separator - start));
        if (address.empty()) {
            return false;
        }
        if (std::find(endpoints->begin(), endpoints->end(), address) == endpoints->end()) {
            endpoints->push_back(std::move(address));
        }
        if (separator == std::string::npos) {
            break;
        }
        start = separator + 1;
    }
    return !endpoints->empty();
}

template <typename Endpoints>
std::string cluster_health(const Endpoints& endpoints, const std::shared_ptr<TlsConfig>& tls) {
    std::ostringstream health;
    bool first = true;
    for (const auto& address : endpoints) {
        if (!first) {
            health << ';';
        }
        first = false;
        health << address << (endpoint_runtime(address, tls)->healthy.load() ? "(UP)" : "(DOWN)");
    }
    return health.str();
}

std::string connection_cache_key(const ConnectionHandle* connection) {
    std::string key = connection->tls->identity + "|";
    for (const auto& value : {connection->url, connection->user, connection->password,
                              connection->datasource_name}) {
        key += std::to_string(value.size()) + ":" + value;
    }
    for (const auto& address : connection->endpoints) {
        key += std::to_string(address.size()) + ":" + address;
    }
    return key;
}

std::string session_cache_key(const ConnectionHandle* connection, const std::string& hash) {
    return connection->tls->identity + "|" + hash;
}

bool configure_tls(const ParsedConnectionString& parsed, std::shared_ptr<TlsConfig>* result) {
    auto option = [&parsed](const char* suffix, std::string* value) {
        const auto entry = parsed.values.find(std::string("OJP.CLIENT.TLS.") + suffix);
        if (entry != parsed.values.end()) {
            *value = entry->second;
            return true;
        }
        const auto* environment = std::getenv((std::string("OJP_CLIENT_TLS_") + suffix).c_str());
        if (environment != nullptr) {
            *value = environment;
            return true;
        }
        return false;
    };
    auto config = std::make_shared<TlsConfig>();
    std::string enabled;
    const bool explicit_enabled = option("ENABLED", &enabled);
    if (explicit_enabled) {
        std::transform(enabled.begin(), enabled.end(), enabled.begin(), [](unsigned char ch) {
            return static_cast<char>(std::tolower(ch));
        });
        enabled = trim(enabled);
        if (enabled != "true" && enabled != "false") {
            return false;
        }
        config->enabled = enabled == "true";
    }
    std::string root_path, cert_path, key_path;
    const bool root_set = option("ROOTCERT", &root_path);
    const bool cert_set = option("CERT", &cert_path);
    const bool key_set = option("KEY", &key_path);
    // Credentials never implicitly enable TLS or cause a plaintext fallback.
    if ((!config->enabled && (root_set || cert_set || key_set)) ||
        cert_path.empty() != key_path.empty()) {
        return false;
    }
    auto read = [](bool supplied, const std::string& path, std::string* material) {
        if (!supplied) {
            return true;
        }
        std::error_code error;
        if (path.empty() || !std::filesystem::is_regular_file(path, error)) {
            return false;
        }
        std::ifstream file(path, std::ios::binary);
        std::ostringstream contents;
        contents << file.rdbuf();
        if (!file || !contents ||
            contents.str().find_first_not_of(" \t\r\n") == std::string::npos) {
            return false;
        }
        *material = contents.str();
        return true;
    };
    if (!read(!root_path.empty(), root_path, &config->root_cert) ||
        !read(!cert_path.empty(), cert_path, &config->cert) ||
        !read(!key_path.empty(), key_path, &config->key)) {
        return false;
    }
    std::string material_key = config->enabled ? "tls" : "plaintext";
    for (const auto& value : {root_path, cert_path, key_path,
                              config->root_cert, config->cert, config->key}) {
        material_key += std::to_string(value.size()) + ":" + value;
    }
    auto& runtime = client_runtime();
    std::lock_guard<std::mutex> lock(runtime.mutex);
    const auto existing = runtime.tls_configs.find(material_key);
    if (existing != runtime.tls_configs.end()) {
        *result = existing->second;
        return true;
    }
    config->identity = std::to_string(runtime.tls_configs.size());
    if (config->enabled) {
        grpc::SslCredentialsOptions ssl;
        ssl.pem_root_certs = config->root_cert;
        ssl.pem_cert_chain = config->cert;
        ssl.pem_private_key = config->key;
        config->credentials = grpc::SslCredentials(ssl);
    } else {
        config->credentials = grpc::InsecureChannelCredentials();
    }
    if (!config->credentials) {
        return false;
    }
    runtime.tls_configs.emplace(std::move(material_key), config);
    *result = std::move(config);
    return true;
}

bool environment_boolean(const char* name, bool fallback) {
    const char* value = std::getenv(name);
    if (value == nullptr) {
        return fallback;
    }
    std::string normalized(value);
    std::transform(normalized.begin(), normalized.end(), normalized.begin(),
                   [](unsigned char character) {
                       return static_cast<char>(std::tolower(character));
                   });
    normalized = trim(std::move(normalized));
    return normalized.empty() ? fallback
        : (normalized == "true" || normalized == "1" || normalized == "yes");
}

bool parse_boolean_option(const std::string& value, bool* parsed) {
    std::string normalized = value;
    std::transform(normalized.begin(), normalized.end(), normalized.begin(),
                   [](unsigned char character) {
                       return static_cast<char>(std::tolower(character));
                   });
    normalized = trim(std::move(normalized));
    if (normalized == "true" || normalized == "1" || normalized == "yes") {
        *parsed = true;
        return true;
    }
    if (normalized == "false" || normalized == "0" || normalized == "no") {
        *parsed = false;
        return true;
    }
    return false;
}

bool parse_nonnegative_int_option(const std::string& value, int maximum, int* parsed) {
    const std::string normalized = trim(value);
    if (normalized.empty()) {
        return false;
    }
    int number = 0;
    const auto result = std::from_chars(
        normalized.data(), normalized.data() + normalized.size(), number);
    if (result.ec != std::errc{} || result.ptr != normalized.data() + normalized.size() ||
        number < 0 || number > maximum) {
        return false;
    }
    *parsed = number;
    return true;
}

int health_check_interval_ms() {
    const char* value = std::getenv("OJP_HEALTH_CHECK_INTERVAL_MS");
    if (value == nullptr) {
        return 5000;
    }
    try {
        const int parsed = std::stoi(value);
        return parsed > 0 ? parsed : 5000;
    } catch (...) {
        return 5000;
    }
}

std::string choose_endpoint(const std::vector<std::string>& endpoints, bool load_aware,
                            const std::shared_ptr<TlsConfig>& tls,
                            const std::set<std::string>& excluded = {}) {
    std::vector<std::shared_ptr<EndpointRuntime>> healthy;
    for (const auto& address : endpoints) {
        if (excluded.find(address) != excluded.end()) {
            continue;
        }
        auto state = endpoint_runtime(address, tls);
        if (state->healthy.load()) {
            healthy.push_back(std::move(state));
        }
    }
    if (healthy.empty()) {
        return {};
    }
    ClientRuntime& runtime = client_runtime();
    if (!load_aware) {
        const auto index = runtime.round_robin_counter.fetch_add(1) % healthy.size();
        return healthy[static_cast<std::size_t>(index)]->address;
    }
    int minimum = healthy.front()->active_connections.load();
    for (const auto& state : healthy) {
        minimum = std::min(minimum, state->active_connections.load());
    }
    std::vector<std::shared_ptr<EndpointRuntime>> least_loaded;
    for (const auto& state : healthy) {
        if (state->active_connections.load() == minimum) {
            least_loaded.push_back(state);
        }
    }
    const auto index = runtime.round_robin_counter.fetch_add(1) % least_loaded.size();
    return least_loaded[static_cast<std::size_t>(index)]->address;
}

void mark_endpoint_unhealthy(const std::string& address, const std::shared_ptr<TlsConfig>& tls) {
    auto state = endpoint_runtime(address, tls);
    state->healthy.store(false);
    const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    state->last_failure_ms.store(now);
    client_runtime().health_check_cv.notify_all();
}

bool is_transport_failure(const grpc::Status& status, const grpc::ClientContext& context) {
    if (!status.error_details().empty()) {
        return false;
    }
    if (status.error_code() == grpc::StatusCode::UNAVAILABLE ||
        status.error_code() == grpc::StatusCode::DEADLINE_EXCEEDED) {
        return true;
    }
    if (status.error_code() == grpc::StatusCode::UNKNOWN) {
        return status.error_message().find("connection") != std::string::npos;
    }
    if (status.error_code() == grpc::StatusCode::INTERNAL) {
        const auto& metadata = context.GetServerTrailingMetadata();
        return std::none_of(metadata.begin(), metadata.end(), [](const auto& item) {
            return item.first.find("sqlerrorresponse-bin") != std::string::npos;
        });
    }
    return false;
}

bool probe_endpoint(const std::shared_ptr<EndpointRuntime>& endpoint) {
    ConnectionDetails empty_details;
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(2));
    SessionInfo response;
    const auto status = endpoint->stub->connect(&context, empty_details, &response);
    return status.ok() || status.error_code() == grpc::StatusCode::RESOURCE_EXHAUSTED;
}

bool details_include_endpoint(const ConnectionDetails& details, const std::string& address) {
    return std::find(details.serverendpoints().begin(), details.serverendpoints().end(),
                     address) != details.serverendpoints().end();
}

template <typename Endpoints>
std::string endpoint_set_key(const Endpoints& endpoints) {
    std::ostringstream key;
    for (const auto& endpoint : endpoints) {
        key << endpoint << ';';
    }
    return key.str();
}

void ensure_health_checker() {
    ClientRuntime& runtime = client_runtime();
    std::call_once(runtime.health_check_once, [&runtime] {
        runtime.health_check_thread = std::thread([&runtime] {
            const auto interval = std::chrono::milliseconds(health_check_interval_ms());
            while (!runtime.stop_health_checks.load()) {
            std::unique_lock<std::mutex> wait_lock(runtime.health_check_wait_mutex);
            runtime.health_check_cv.wait_for(wait_lock, interval);
                wait_lock.unlock();
                if (runtime.stop_health_checks.load()) {
                    break;
                }
                std::vector<std::shared_ptr<EndpointRuntime>> endpoints;
                std::vector<StoredConnectionDetails> details;
                {
                    std::lock_guard<std::mutex> lock(runtime.mutex);
                    for (const auto& entry : runtime.endpoints) {
                        endpoints.push_back(entry.second);
                    }
                    for (const auto& entry : runtime.details_by_conn_hash) {
                        details.push_back(entry.second);
                    }
                    for (const auto& entry : runtime.xa_details_by_conn_hash) {
                        details.push_back(entry.second);
                    }
                }
                if (details.empty()) {
                    continue;
                }
                const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
                for (const auto& endpoint : endpoints) {
                    if (endpoint->healthy.load()) {
                        if (!probe_endpoint(endpoint)) {
                            mark_endpoint_unhealthy(endpoint->address, endpoint->tls);
                        }
                        continue;
                    }
                    if (now - endpoint->last_failure_ms.load() < interval.count()) {
                        continue;
                    }
                    if (!probe_endpoint(endpoint)) {
                        endpoint->last_failure_ms.store(now);
                        continue;
                    }
                    bool pools_ready = true;
                    for (const auto& stored : details) {
                        const auto& stored_details = stored.details;
                        if (stored.tls != endpoint->tls ||
                            !details_include_endpoint(stored_details, endpoint->address)) {
                            continue;
                        }
                        ConnectionDetails recovery_details(stored_details);
                        recovery_details.set_clusterhealth(
                            cluster_health(stored_details.serverendpoints(), stored.tls));
                        grpc::ClientContext recovery_context;
                        recovery_context.set_deadline(
                            std::chrono::system_clock::now() + std::chrono::seconds(30));
                        SessionInfo response;
                        if (!endpoint->stub->connect(&recovery_context, recovery_details,
                                                     &response).ok()) {
                            pools_ready = false;
                            break;
                        }
                    }
                    if (pools_ready) {
                        endpoint->healthy.store(true);
                        endpoint->last_failure_ms.store(0);
                    } else {
                        endpoint->last_failure_ms.store(now);
                    }
                }
                for (const auto& stored : details) {
                    const auto& stored_details = stored.details;
                    const std::string health = cluster_health(
                        stored_details.serverendpoints(), stored.tls);
                    const std::string endpoints_key =
                        stored.tls->identity + "|" +
                        endpoint_set_key(stored_details.serverendpoints());
                    const auto previous = runtime.cluster_health_by_endpoints.find(endpoints_key);
                    const bool health_changed =
                        previous == runtime.cluster_health_by_endpoints.end() ||
                        previous->second != health;
                    if (!health_changed) {
                        continue;
                    }
                    runtime.cluster_health_by_endpoints[endpoints_key] = health;
                    ConnectionDetails updated(stored_details);
                    updated.set_clusterhealth(health);
                    for (const auto& endpoint : endpoints) {
                        if (stored.tls != endpoint->tls || !endpoint->healthy.load() ||
                            !details_include_endpoint(stored_details, endpoint->address)) {
                            continue;
                        }
                        grpc::ClientContext context;
                        context.set_deadline(
                            std::chrono::system_clock::now() + std::chrono::seconds(30));
                        SessionInfo response;
                        endpoint->stub->connect(&context, updated, &response);
                    }
                }
            }
        });
    });
}

// Optional driver log: OJP_ODBC_LOG_FILE (path; no log without it) and
// OJP_ODBC_LOG_LEVEL (error|warn|info|debug, default info).
enum class LogLevel { error = 0, warn = 1, info = 2, debug = 3 };

class DriverLog {
public:
    static DriverLog& instance() {
        // Never freed: the DLL stays pinned in memory and gRPC threads may log until the very end.
        static DriverLog* log = new DriverLog();
        return *log;
    }

    bool enabled(LogLevel level) const {
        return file_ != nullptr && static_cast<int>(level) <= static_cast<int>(level_);
    }

    void write(LogLevel level, const std::string& message) {
        if (!enabled(level)) {
            return;
        }
        static constexpr const char* kNames[] = {"ERROR", "WARN", "INFO", "DEBUG"};
        const auto now = std::chrono::system_clock::now();
        const auto seconds = std::chrono::system_clock::to_time_t(now);
        const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(
                                now.time_since_epoch()).count() % 1000;
        std::tm local{};
#ifdef _WIN32
        localtime_s(&local, &seconds);
        const unsigned long pid = GetCurrentProcessId();
        const unsigned long tid = GetCurrentThreadId();
#else
        localtime_r(&seconds, &local);
        const unsigned long pid = static_cast<unsigned long>(getpid());
        const unsigned long tid = static_cast<unsigned long>(
            std::hash<std::thread::id>{}(std::this_thread::get_id()) & 0xffffffffu);
#endif
        char stamp[32];
        std::strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &local);
        std::lock_guard<std::mutex> lock(mutex_);
        std::fprintf(file_, "%s.%03d [%lu:%lu] %-5s %s%s%s\n", stamp, static_cast<int>(millis),
                     pid, tid, kNames[static_cast<int>(level)],
                     current_api() == nullptr ? "" : current_api(),
                     current_api() == nullptr ? "" : ": ", message.c_str());
        std::fflush(file_);
    }

    static const char*& current_api() {
        thread_local const char* api = nullptr;
        return api;
    }

private:
    DriverLog() {
        const char* path = std::getenv("OJP_ODBC_LOG_FILE");
        if (path == nullptr || *path == '\0') {
            return;
        }
#ifdef _WIN32
        file_ = _fsopen(path, "a", _SH_DENYNO);
#else
        file_ = std::fopen(path, "a");
#endif
        std::string level;
        if (const char* raw = std::getenv("OJP_ODBC_LOG_LEVEL"); raw != nullptr) {
            level = raw;
            std::transform(level.begin(), level.end(), level.begin(),
                           [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        }
        if (level == "error") {
            level_ = LogLevel::error;
        } else if (level == "warn" || level == "warning") {
            level_ = LogLevel::warn;
        } else if (level == "debug" || level == "trace") {
            level_ = LogLevel::debug;
        }
    }

    std::FILE* file_ = nullptr;
    LogLevel level_ = LogLevel::info;
    std::mutex mutex_;
};

bool log_enabled(LogLevel level) {
    return DriverLog::instance().enabled(level);
}

void log_message(LogLevel level, const std::string& message) {
    DriverLog::instance().write(level, message);
}

// Masks the value of "password=" in JDBC URLs before logging.
std::string mask_password(std::string text) {
    std::string lower = text;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    std::size_t position = 0;
    while ((position = lower.find("password=", position)) != std::string::npos) {
        const std::size_t start = position + 9;
        std::size_t end = text.find_first_of(";&", start);
        if (end == std::string::npos) {
            end = text.size();
        }
        text.replace(start, end - start, "***");
        lower.replace(start, end - start, "***");
        position = start + 3;
    }
    return text;
}

void clear_diagnostics(HandleBase* handle) {
    if (handle != nullptr) {
        handle->diagnostics.clear();
    }
}

SQLRETURN fail(HandleBase* handle, std::string message, std::string state = "HY000",
               SQLINTEGER native_error = 0) {
    if (message.empty()) {
        message = "OJP ODBC driver error without details (SQLSTATE " + state + ")";
    }
    const LogLevel level = state == "HYC00" || state == "IM001" ? LogLevel::warn : LogLevel::error;
    if (log_enabled(level)) {
        log_message(level, "[" + state + "] native=" + std::to_string(native_error) + " " +
                               message);
    }
    if (handle != nullptr) {
        handle->diagnostics.push_back({std::move(state), native_error, std::move(message)});
    }
    return SQL_ERROR;
}

const char* sql_return_name(SQLRETURN result) {
    switch (result) {
        case SQL_SUCCESS: return "SQL_SUCCESS";
        case SQL_SUCCESS_WITH_INFO: return "SQL_SUCCESS_WITH_INFO";
        case SQL_ERROR: return "SQL_ERROR";
        case SQL_INVALID_HANDLE: return "SQL_INVALID_HANDLE";
        case SQL_NO_DATA: return "SQL_NO_DATA";
        case SQL_NEED_DATA: return "SQL_NEED_DATA";
        case SQL_STILL_EXECUTING: return "SQL_STILL_EXECUTING";
        default: return "SQL_RETURN_UNKNOWN";
    }
}

// Sets the current API function name (used as the log prefix) and restores it on exit.
class ApiScope {
public:
    explicit ApiScope(const char* api) : previous_(DriverLog::current_api()) {
        DriverLog::current_api() = api;
    }
    ~ApiScope() { DriverLog::current_api() = previous_; }
    ApiScope(const ApiScope&) = delete;
    ApiScope& operator=(const ApiScope&) = delete;

private:
    const char* previous_;
};

// Wraps every exported function: no exception crosses the C boundary (it would crash the
// Driver Manager) and every SQL_ERROR leaves with at least one diagnostic record.
template <typename Body>
SQLRETURN guarded(SQLHANDLE raw_handle, const char* api, Body&& body) noexcept {
    ApiScope scope(api);
    auto* handle = static_cast<HandleBase*>(raw_handle);
    const bool debug = log_enabled(LogLevel::debug);
    const auto started = std::chrono::steady_clock::now();
    if (debug) {
        log_message(LogLevel::debug, "enter");
    }
    SQLRETURN result = SQL_ERROR;
    try {
        result = body();
    } catch (const std::bad_alloc&) {
        result = fail(handle, std::string("Out of memory in ") + api, "HY001");
    } catch (const std::exception& error) {
        result = fail(handle, std::string("Unexpected internal error in ") + api + ": " +
                                  error.what());
    } catch (...) {
        result = fail(handle, std::string("Unexpected unknown internal error in ") + api);
    }
    try {
        if (result == SQL_ERROR && handle != nullptr && handle->diagnostics.empty()) {
            fail(handle, std::string(api) + " failed without a specific reason (driver bug); "
                         "enable OJP_ODBC_LOG_FILE with OJP_ODBC_LOG_LEVEL=debug for details");
        }
        if (debug) {
            const auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - started).count();
            log_message(LogLevel::debug, std::string("exit ") + sql_return_name(result) + " in " +
                                             std::to_string(elapsed) + "us");
        }
    } catch (...) {
    }
    return result;
}

const char* grpc_code_name(grpc::StatusCode code) {
    switch (code) {
        case grpc::StatusCode::OK: return "OK";
        case grpc::StatusCode::CANCELLED: return "CANCELLED";
        case grpc::StatusCode::UNKNOWN: return "UNKNOWN";
        case grpc::StatusCode::INVALID_ARGUMENT: return "INVALID_ARGUMENT";
        case grpc::StatusCode::DEADLINE_EXCEEDED: return "DEADLINE_EXCEEDED";
        case grpc::StatusCode::NOT_FOUND: return "NOT_FOUND";
        case grpc::StatusCode::ALREADY_EXISTS: return "ALREADY_EXISTS";
        case grpc::StatusCode::PERMISSION_DENIED: return "PERMISSION_DENIED";
        case grpc::StatusCode::RESOURCE_EXHAUSTED: return "RESOURCE_EXHAUSTED";
        case grpc::StatusCode::FAILED_PRECONDITION: return "FAILED_PRECONDITION";
        case grpc::StatusCode::ABORTED: return "ABORTED";
        case grpc::StatusCode::OUT_OF_RANGE: return "OUT_OF_RANGE";
        case grpc::StatusCode::UNIMPLEMENTED: return "UNIMPLEMENTED";
        case grpc::StatusCode::INTERNAL: return "INTERNAL";
        case grpc::StatusCode::UNAVAILABLE: return "UNAVAILABLE";
        case grpc::StatusCode::DATA_LOSS: return "DATA_LOSS";
        case grpc::StatusCode::UNAUTHENTICATED: return "UNAUTHENTICATED";
        default: return "UNRECOGNIZED";
    }
}

std::string describe_grpc_status(const grpc::Status& status) {
    std::string text = std::string("gRPC ") + grpc_code_name(status.error_code());
    if (!status.error_message().empty()) {
        text += ": " + status.error_message();
    }
    return text;
}

void append_utf8(std::uint32_t code_point, std::string* output) {
    if (code_point <= 0x7f) {
        output->push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ff) {
        output->push_back(static_cast<char>(0xc0 | (code_point >> 6)));
        output->push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else if (code_point <= 0xffff) {
        output->push_back(static_cast<char>(0xe0 | (code_point >> 12)));
        output->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else {
        output->push_back(static_cast<char>(0xf0 | (code_point >> 18)));
        output->push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    }
}

bool next_utf8_code_point(const std::string& input, std::size_t* offset,
                          std::uint32_t* code_point) {
    if (*offset >= input.size()) {
        return false;
    }
    const auto first = static_cast<unsigned char>(input[*offset]);
    std::size_t width = 0;
    std::uint32_t value = 0;
    if (first <= 0x7f) {
        width = 1;
        value = first;
    } else if (first >= 0xc2 && first <= 0xdf) {
        width = 2;
        value = first & 0x1f;
    } else if (first >= 0xe0 && first <= 0xef) {
        width = 3;
        value = first & 0x0f;
    } else if (first >= 0xf0 && first <= 0xf4) {
        width = 4;
        value = first & 0x07;
    } else {
        return false;
    }
    if (width > input.size() - *offset) {
        return false;
    }
    for (std::size_t index = 1; index < width; ++index) {
        const auto continuation = static_cast<unsigned char>(input[*offset + index]);
        if ((continuation & 0xc0) != 0x80) {
            return false;
        }
        value = (value << 6) | (continuation & 0x3f);
    }
    if ((width == 2 && value < 0x80) || (width == 3 && value < 0x800) ||
        (width == 4 && value < 0x10000) || value > 0x10ffff ||
        (value >= 0xd800 && value <= 0xdfff)) {
        return false;
    }
    *offset += width;
    *code_point = value;
    return true;
}

bool wide_to_utf8(const SQLWCHAR* input, std::size_t units, std::string* output) {
    output->clear();
    output->reserve(units * 2);
    for (std::size_t index = 0; index < units; ++index) {
        std::uint32_t code_point = static_cast<std::uint32_t>(input[index]);
        if (sizeof(SQLWCHAR) == 2) {
            if (code_point >= 0xd800 && code_point <= 0xdbff) {
                if (++index >= units) {
                    return false;
                }
                const auto low = static_cast<std::uint32_t>(input[index]);
                if (low < 0xdc00 || low > 0xdfff) {
                    return false;
                }
                code_point = 0x10000 + ((code_point - 0xd800) << 10) + (low - 0xdc00);
            } else if (code_point >= 0xdc00 && code_point <= 0xdfff) {
                return false;
            }
        } else if (code_point > 0x10ffff ||
                   (code_point >= 0xd800 && code_point <= 0xdfff)) {
            return false;
        }
        append_utf8(code_point, output);
    }
    return true;
}

bool utf8_to_wide(const std::string& input, std::vector<SQLWCHAR>* output) {
    output->clear();
    output->reserve(input.size());
    for (std::size_t offset = 0; offset < input.size();) {
        std::uint32_t code_point = 0;
        if (!next_utf8_code_point(input, &offset, &code_point)) {
            return false;
        }
        if (sizeof(SQLWCHAR) == 2 && code_point > 0xffff) {
            code_point -= 0x10000;
            output->push_back(static_cast<SQLWCHAR>(0xd800 + (code_point >> 10)));
            output->push_back(static_cast<SQLWCHAR>(0xdc00 + (code_point & 0x3ff)));
        } else {
            output->push_back(static_cast<SQLWCHAR>(code_point));
        }
    }
    return true;
}

bool read_wide_string(const SQLWCHAR* input, SQLINTEGER length, std::string* output) {
    if (input == nullptr || (length < 0 && length != SQL_NTS)) {
        return false;
    }
    std::size_t units = 0;
    if (length == SQL_NTS) {
        while (input[units] != 0) {
            ++units;
        }
    } else {
        units = static_cast<std::size_t>(length);
    }
    return wide_to_utf8(input, units, output);
}

SQLRETURN write_wide_string(HandleBase* handle, const std::string& text,
                            SQLWCHAR* output, SQLSMALLINT buffer_length,
                            SQLSMALLINT* output_length) {
    std::vector<SQLWCHAR> wide;
    if (!utf8_to_wide(text, &wide)) {
        return fail(handle, "Server returned invalid UTF-8 text", "22018");
    }
    if (wide.size() > static_cast<std::size_t>(std::numeric_limits<SQLSMALLINT>::max())) {
        return fail(handle, "Wide-character output is too long", "22001");
    }
    if (output_length != nullptr) {
        *output_length = static_cast<SQLSMALLINT>(wide.size());
    }
    if (buffer_length < 0) {
        return fail(handle, "Invalid wide-character output buffer length", "HY090");
    }
    if (output == nullptr || buffer_length <= 0) {
        return SQL_SUCCESS;
    }
    const auto copy_count = std::min<std::size_t>(
        wide.size(), static_cast<std::size_t>(buffer_length - 1));
    std::copy_n(wide.begin(), copy_count, output);
    output[copy_count] = 0;
    if (copy_count < wide.size()) {
        handle->diagnostics.push_back({"01004", 0, "Wide-character output was truncated"});
        return SQL_SUCCESS_WITH_INFO;
    }
    return SQL_SUCCESS;
}

// Variant for W APIs whose BufferLength/StringLength are in bytes (e.g. SQLColAttributeW).
SQLRETURN write_wide_string_bytes(HandleBase* handle, const std::string& text, SQLPOINTER output,
                                  SQLINTEGER buffer_length_bytes, SQLINTEGER* output_length_bytes) {
    if (buffer_length_bytes < 0) {
        return fail(handle, "Invalid wide-character output buffer length (" +
                    std::to_string(buffer_length_bytes) + " bytes)", "HY090");
    }
    SQLSMALLINT characters = 0;
    const auto capacity = std::min<SQLINTEGER>(buffer_length_bytes / static_cast<SQLINTEGER>(sizeof(SQLWCHAR)),
                                               std::numeric_limits<SQLSMALLINT>::max());
    const auto result = write_wide_string(handle, text, static_cast<SQLWCHAR*>(output),
                                          static_cast<SQLSMALLINT>(capacity), &characters);
    if (output_length_bytes != nullptr) {
        *output_length_bytes = static_cast<SQLINTEGER>(characters) * static_cast<SQLINTEGER>(sizeof(SQLWCHAR));
    }
    return result;
}

// Extracts the SqlErrorResponse (SQLSTATE, message and vendor code) sent by the OJP server.
bool extract_sql_error(const grpc::ClientContext& context, std::string* sql_state,
                       std::string* message, SQLINTEGER* native_error) {
    for (const auto& item : context.GetServerTrailingMetadata()) {
        if (item.first.find("sqlerrorresponse-bin") != std::string::npos) {
            com::openjproxy::grpc::SqlErrorResponse response;
            if (response.ParseFromString(std::string(item.second.data(), item.second.size()))) {
                *sql_state = response.sqlstate();
                *message = response.reason();
                *native_error = response.vendorcode();
                return true;
            }
        }
    }
    return false;
}

SQLRETURN fail_grpc(HandleBase* handle, const grpc::Status& status,
                    const grpc::ClientContext& context) {
    std::string sql_state;
    std::string message;
    SQLINTEGER native_error = 0;
    extract_sql_error(context, &sql_state, &message, &native_error);
    if (message.empty()) {
        message = "OJP server call failed (" + describe_grpc_status(status) + ")";
    } else if (sql_state.empty()) {
        message += " (" + describe_grpc_status(status) + ")";
    }
    if (sql_state.empty()) {
        sql_state = "08S01";
    }
    return fail(handle, std::move(message), std::move(sql_state), native_error);
}

StatementService::Stub* route_session_rpc(ConnectionHandle* connection) {
    std::lock_guard<std::mutex> lock(connection->routing_mutex);
    std::string target = connection->endpoint;
    if (!connection->session.sessionuuid().empty()) {
        const std::string& session_uuid = connection->session.sessionuuid();
        const auto binding = connection->session_to_server.find(session_uuid);
        if (binding != connection->session_to_server.end()) {
            target = binding->second;
        } else {
            if (!connection->session.targetserver().empty()) {
                target = connection->session.targetserver();
            }
            connection->session_to_server.emplace(session_uuid, target);
        }
        connection->session.set_targetserver(target);
    } else if (connection->session.targetserver().empty()) {
        connection->session.set_targetserver(target);
    }
    connection->last_rpc_endpoint = target;

    if (target == connection->endpoint) {
        return connection->stub.get();
    }
    auto stub = connection->affinity_stubs.find(target);
    if (stub == connection->affinity_stubs.end()) {
        auto endpoint = endpoint_runtime(target, connection->tls);
        connection->affinity_channels.emplace(target, endpoint->channel);
        stub = connection->affinity_stubs.emplace(target, endpoint->stub).first;
    }
    return stub->second.get();
}

void note_transport_failure(ConnectionHandle* connection, const grpc::Status& status,
                            const grpc::ClientContext& context) {
    if (is_transport_failure(status, context)) {
        mark_endpoint_unhealthy(connection->last_rpc_endpoint.empty()
                                    ? connection->endpoint : connection->last_rpc_endpoint,
                                connection->tls);
    }
}

bool failover_stateless_connection(ConnectionHandle* connection,
                                   const std::set<std::string>& attempted_endpoints) {
    if (!connection->session.sessionuuid().empty()) {
        return false;
    }
    const std::string endpoint =
        choose_endpoint(connection->endpoints, connection->load_aware, connection->tls,
                        attempted_endpoints);
    if (endpoint.empty()) {
        return false;
    }
    if (connection->endpoint != endpoint) {
        endpoint_runtime(connection->endpoint, connection->tls)->active_connections.fetch_sub(1);
        endpoint_runtime(endpoint, connection->tls)->active_connections.fetch_add(1);
    }
    connection->endpoint = endpoint;
    const auto selected_endpoint = endpoint_runtime(endpoint, connection->tls);
    connection->channel = selected_endpoint->channel;
    connection->stub = selected_endpoint->stub;
    const std::string health = cluster_health(connection->endpoints, connection->tls);
    connection->session.set_targetserver(endpoint);
    connection->session.set_clusterhealth(health);
    connection->connection_details.set_clusterhealth(health);
    return true;
}

bool recover_not_found(ConnectionHandle* connection) {
    if (!connection->session.sessionuuid().empty() || connection->session.connhash().empty()) {
        return false;
    }
    ClientRuntime& runtime = client_runtime();
    const std::string old_conn_hash = connection->session.connhash();
    ConnectionDetails details;
    {
        std::lock_guard<std::mutex> lock(runtime.mutex);
        const auto stored = runtime.details_by_conn_hash.find(
            session_cache_key(connection, old_conn_hash));
        if (stored == runtime.details_by_conn_hash.end()) {
            return false;
        }
        details.CopyFrom(stored->second.details);
        runtime.conn_hash_by_key.erase(connection_cache_key(connection));
    }
    details.set_clusterhealth(cluster_health(connection->endpoints, connection->tls));
    std::vector<std::string> candidates{connection->endpoint};
    for (const auto& address : connection->endpoints) {
        if (address != connection->endpoint) {
            candidates.push_back(address);
        }
    }
    for (const auto& address : candidates) {
        auto endpoint = endpoint_runtime(address, connection->tls);
        if (!endpoint->healthy.load()) {
            continue;
        }
        grpc::ClientContext context;
        context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
        SessionInfo response;
        const auto status = endpoint->stub->connect(&context, details, &response);
        if (!status.ok()) {
            if (is_transport_failure(status, context)) {
                mark_endpoint_unhealthy(address, connection->tls);
            }
            continue;
        }
        if (connection->endpoint != address) {
            endpoint_runtime(connection->endpoint, connection->tls)->active_connections.fetch_sub(1);
            endpoint->active_connections.fetch_add(1);
        }
        connection->endpoint = address;
        connection->channel = endpoint->channel;
        connection->stub = endpoint->stub;
        connection->connection_details.CopyFrom(details);
        response.set_targetserver(address);
        connection->session.CopyFrom(response);
        {
            std::lock_guard<std::mutex> lock(runtime.mutex);
            if (!response.connhash().empty()) {
                runtime.conn_hash_by_key[connection_cache_key(connection)] = response.connhash();
                runtime.details_by_conn_hash[session_cache_key(connection, response.connhash())] =
                    {details, connection->tls};
                if (response.connhash() != old_conn_hash) {
                    runtime.details_by_conn_hash.erase(session_cache_key(connection, old_conn_hash));
                }
            }
        }
        return true;
    }
    return false;
}

bool prepare_stateless_retry(ConnectionHandle* connection, const grpc::Status& status,
                             const grpc::ClientContext& context,
                             const SessionInfo& initial_request_session,
                             std::set<std::string>* attempted_endpoints,
                             bool* not_found_retried) {
    note_transport_failure(connection, status, context);
    if (connection->xa_mode || !initial_request_session.sessionuuid().empty()) {
        return false;
    }
    if (status.error_code() == grpc::StatusCode::NOT_FOUND) {
        if (*not_found_retried) {
            return false;
        }
        connection->session.CopyFrom(initial_request_session);
        if (!recover_not_found(connection)) {
            return false;
        }
        *not_found_retried = true;
    } else if (is_transport_failure(status, context)) {
        const std::string failed_endpoint = connection->last_rpc_endpoint.empty()
            ? connection->endpoint : connection->last_rpc_endpoint;
        attempted_endpoints->insert(failed_endpoint);
        connection->session.CopyFrom(initial_request_session);
        if (!failover_stateless_connection(connection, *attempted_endpoints)) {
            return false;
        }
    } else {
        return false;
    }
    if (connection->retry_delay_ms > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(connection->retry_delay_ms));
    }
    return true;
}

void update_session(ConnectionHandle* connection, const SessionInfo& response,
                    const SessionInfo& request) {
    std::string target;
    if (!response.sessionuuid().empty()) {
        std::lock_guard<std::mutex> lock(connection->routing_mutex);
        const std::string& session_uuid = response.sessionuuid();
        if (!response.targetserver().empty()) {
            target = response.targetserver();
        } else {
            const auto binding = connection->session_to_server.find(session_uuid);
            if (binding != connection->session_to_server.end()) {
                target = binding->second;
            } else if (!request.targetserver().empty()) {
                target = request.targetserver();
            } else {
                target = connection->endpoint;
            }
        }
        connection->session_to_server[session_uuid] = target;
    }
    connection->session.CopyFrom(response);
    if (!response.sessionuuid().empty()) {
        connection->session.set_targetserver(target);
    } else if (connection->session.targetserver().empty()) {
        connection->session.set_targetserver(
            request.targetserver().empty() ? connection->endpoint : request.targetserver());
    }
}

std::string format_fraction(std::int32_t nanos) {
    if (nanos == 0) {
        return {};
    }
    std::ostringstream fraction;
    fraction << std::setfill('0') << std::setw(9) << nanos;
    std::string digits = fraction.str();
    while (!digits.empty() && digits.back() == '0') {
        digits.pop_back();
    }
    return "." + digits;
}

std::uint32_t read_big_endian_32(const std::string& bytes, std::size_t offset) {
    std::uint32_t number = 0;
    for (std::size_t index = 0; index < 4; ++index) {
        number = (number << 8) | static_cast<unsigned char>(bytes[offset + index]);
    }
    return number;
}

// The server sends BigDecimal results in the BigDecimalWire format (documents/protocol/
// BIGDECIMAL_WIRE_FORMAT.md): 0x01, int32 length, unscaled UTF-8 digits, int32 scale.
// Like the JDBC driver, which decodes untyped result bytes with BigDecimalWire, the
// value is only treated as a decimal when the bytes match that layout exactly.
bool decode_big_decimal_wire(const std::string& bytes, std::string* decimal) {
    if (bytes.size() < 10 || bytes[0] != '\1') {
        return false;
    }
    const auto length = read_big_endian_32(bytes, 1);
    if (length == 0 || bytes.size() != 9 + static_cast<std::size_t>(length)) {
        return false;
    }
    std::string digits = bytes.substr(5, length);
    const bool negative = digits[0] == '-';
    if (negative) {
        digits.erase(0, 1);
    }
    if (digits.empty() || digits.find_first_not_of("0123456789") != std::string::npos) {
        return false;
    }
    const auto scale = static_cast<std::int32_t>(read_big_endian_32(bytes, 5 + length));
    if (scale > 1000 || scale < -1000) {
        return false;
    }
    if (scale < 0) {
        digits.append(static_cast<std::size_t>(-scale), '0');
    } else if (scale > 0) {
        if (digits.size() <= static_cast<std::size_t>(scale)) {
            digits.insert(0, static_cast<std::size_t>(scale) + 1 - digits.size(), '0');
        }
        digits.insert(digits.size() - static_cast<std::size_t>(scale), 1, '.');
    }
    *decimal = negative ? "-" + digits : digits;
    return true;
}

// Parses fixed-offset zones: "UTC", "GMT", "Z", "+HH:MM", "-HHMM", "+HH", "UTC-03:00".
bool parse_zone_offset(const std::string& raw, int* offset_seconds) {
    *offset_seconds = 0;
    std::string zone = raw;
    for (const char* prefix : {"UTC", "GMT", "UT"}) {
        if (zone.rfind(prefix, 0) == 0) {
            zone.erase(0, std::strlen(prefix));
            break;
        }
    }
    if (zone.rfind("Etc/", 0) == 0) {
        // POSIX convention: "Etc/GMT+3" means UTC-03:00.
        if (!parse_zone_offset(zone.substr(4), offset_seconds)) {
            return false;
        }
        *offset_seconds = -*offset_seconds;
        return true;
    }
    if (zone.empty() || zone == "Z") {
        return true;
    }
    if (zone[0] != '+' && zone[0] != '-') {
        return false;
    }
    const int sign = zone[0] == '-' ? -1 : 1;
    std::string digits;
    for (std::size_t index = 1; index < zone.size(); ++index) {
        if (std::isdigit(static_cast<unsigned char>(zone[index]))) {
            digits.push_back(zone[index]);
        } else if (zone[index] != ':') {
            return false;
        }
    }
    int hours = 0;
    int minutes = 0;
    int seconds = 0;
    if (digits.size() == 1 || digits.size() == 2) {
        hours = std::stoi(digits);
    } else if (digits.size() == 4 || digits.size() == 6) {
        hours = std::stoi(digits.substr(0, 2));
        minutes = std::stoi(digits.substr(2, 2));
        seconds = digits.size() == 6 ? std::stoi(digits.substr(4, 2)) : 0;
    } else {
        return false;
    }
    if (hours > 18 || minutes > 59 || seconds > 59) {
        return false;
    }
    *offset_seconds = sign * (hours * 3600 + minutes * 60 + seconds);
    return true;
}

struct CivilDate {
    std::int64_t year;
    std::uint32_t month;
    std::uint32_t day;
};

std::int64_t floor_div(std::int64_t value, std::int64_t divisor) {
    const std::int64_t quotient = value / divisor;
    return (value % divisor != 0 && (value < 0) != (divisor < 0)) ? quotient - 1 : quotient;
}

// java.sql.Timestamp (used by the server) follows the hybrid Julian/Gregorian calendar: days
// before the Gregorian cutover (1582-10-15) are Julian dates. Applying the same rule keeps old
// dates unchanged across the round trip. Day numbers are counted from 1970-01-01.
constexpr std::int64_t kGregorianCutoverDay = -141427;

// days_from_civil / civil_from_days by Howard Hinnant (proleptic Gregorian calendar).
std::int64_t gregorian_days_from_civil(std::int64_t year, std::uint32_t month, std::uint32_t day) {
    year -= month <= 2 ? 1 : 0;
    const std::int64_t era = floor_div(year, 400);
    const auto year_of_era = static_cast<std::uint32_t>(year - era * 400);
    const std::uint32_t day_of_year = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
    const std::uint32_t day_of_era =
        year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    return era * 146097 + static_cast<std::int64_t>(day_of_era) - 719468;
}

CivilDate gregorian_civil_from_days(std::int64_t days) {
    days += 719468;
    const std::int64_t era = floor_div(days, 146097);
    const auto day_of_era = static_cast<std::uint32_t>(days - era * 146097);
    const std::uint32_t year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36524 - day_of_era / 146096) / 365;
    const std::uint32_t day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    const std::uint32_t shifted_month = (5 * day_of_year + 2) / 153;
    const std::uint32_t month = shifted_month < 10 ? shifted_month + 3 : shifted_month - 9;
    return {static_cast<std::int64_t>(year_of_era) + era * 400 + (month <= 2 ? 1 : 0), month,
            day_of_year - (153 * shifted_month + 2) / 5 + 1};
}

// Julian calendar through the Julian Day Number (2440588 is 1970-01-01).
std::int64_t julian_days_from_civil(std::int64_t year, std::uint32_t month, std::uint32_t day) {
    const std::int64_t shift = (14 - static_cast<std::int64_t>(month)) / 12;
    const std::int64_t shifted_year = year + 4800 - shift;
    const std::int64_t shifted_month = month + 12 * shift - 3;
    const std::int64_t day_number = day + (153 * shifted_month + 2) / 5 + 365 * shifted_year +
        floor_div(shifted_year, 4) - 32083;
    return day_number - 2440588;
}

CivilDate julian_civil_from_days(std::int64_t days) {
    const std::int64_t c = days + 2440588 + 32082;
    const std::int64_t d = floor_div(4 * c + 3, 1461);
    const std::int64_t e = c - floor_div(1461 * d, 4);
    const std::int64_t m = (5 * e + 2) / 153;
    return {d - 4800 + m / 10, static_cast<std::uint32_t>(m + 3 - 12 * (m / 10)),
            static_cast<std::uint32_t>(e - (153 * m + 2) / 5 + 1)};
}

std::int64_t days_from_civil(std::int64_t year, std::uint32_t month, std::uint32_t day) {
    const std::int64_t gregorian = gregorian_days_from_civil(year, month, day);
    return gregorian >= kGregorianCutoverDay ? gregorian : julian_days_from_civil(year, month, day);
}

CivilDate civil_from_days(std::int64_t days) {
    return days >= kGregorianCutoverDay ? gregorian_civil_from_days(days)
                                        : julian_civil_from_days(days);
}

bool decode_value(const ParameterValue& value, Cell* output) {
    switch (value.value_case()) {
        case ParameterValue::VALUE_NOT_SET:
        case ParameterValue::kIsNull:
            *output = std::monostate{};
            return true;
        case ParameterValue::kBoolValue:
            *output = value.bool_value();
            return true;
        case ParameterValue::kIntValue:
            *output = value.int_value();
            return true;
        case ParameterValue::kLongValue:
            *output = value.long_value();
            return true;
        case ParameterValue::kFloatValue:
            *output = static_cast<double>(value.float_value());
            return true;
        case ParameterValue::kDoubleValue:
            *output = value.double_value();
            return true;
        case ParameterValue::kStringValue:
            *output = value.string_value();
            return true;
        case ParameterValue::kBytesValue: {
            const auto& bytes = value.bytes_value();
            std::string decimal;
            if (decode_big_decimal_wire(bytes, &decimal)) {
                *output = decimal;
            } else {
                *output = std::vector<std::uint8_t>(bytes.begin(), bytes.end());
            }
            return true;
        }
        case ParameterValue::kUrlValue:
            *output = value.url_value().value();
            return true;
        case ParameterValue::kRowidValue:
            *output = value.rowid_value().value();
            return true;
        case ParameterValue::kUuidValue:
            *output = UuidCell{value.uuid_value().value()};
            return true;
        case ParameterValue::kBigintegerValue:
            *output = value.biginteger_value().value();
            return true;
        case ParameterValue::kRowidlifetimeValue:
            *output = value.rowidlifetime_value().value();
            return true;
        case ParameterValue::kDateValue: {
            const auto& date = value.date_value();
            std::ostringstream text;
            text << std::setfill('0') << std::setw(4) << date.year() << "-"
                 << std::setw(2) << date.month() << "-" << std::setw(2) << date.day();
            *output = text.str();
            return true;
        }
        case ParameterValue::kTimeValue: {
            const auto& time = value.time_value();
            std::ostringstream text;
            text << std::setfill('0') << std::setw(2) << time.hours() << ":"
                 << std::setw(2) << time.minutes() << ":" << std::setw(2) << time.seconds()
                 << format_fraction(time.nanos());
            *output = text.str();
            return true;
        }
        case ParameterValue::kTimestampValue: {
            const auto& timestamp_value = value.timestamp_value();
            const auto& instant = timestamp_value.instant();
            // Applies the zone sent by the server ("UTC", "Z", "-03:00", "GMT+2"...). Named zones
            // (e.g. "America/Sao_Paulo") are not resolved here and stay in UTC.
            int offset_seconds = 0;
            const bool has_offset = parse_zone_offset(timestamp_value.timezone(), &offset_seconds);
            // Computed by hand: gmtime_s on Windows rejects dates before 1970 and after 3000.
            const std::int64_t seconds = instant.seconds() + (has_offset ? offset_seconds : 0);
            const std::int64_t days = floor_div(seconds, 86400);
            const std::int64_t second_of_day = seconds - days * 86400;
            const CivilDate date = civil_from_days(days);
            if (date.year < 0 || date.year > 9999) {
                return false;
            }
            std::ostringstream text;
            text << std::setfill('0') << std::setw(4) << date.year << '-' << std::setw(2)
                 << date.month << '-' << std::setw(2) << date.day << ' ' << std::setw(2)
                 << second_of_day / 3600 << ':' << std::setw(2) << second_of_day / 60 % 60 << ':'
                 << std::setw(2) << second_of_day % 60 << format_fraction(instant.nanos());
            if (timestamp_value.original_type() ==
                com::openjproxy::grpc::TEMPORAL_TYPE_OFFSET_DATE_TIME) {
                // SQL Server format for datetimeoffset: "YYYY-MM-DD HH:MM:SS[.f] +HH:MM".
                const int absolute = std::abs(offset_seconds);
                text << ' ' << (offset_seconds < 0 ? '-' : '+') << std::setfill('0')
                     << std::setw(2) << absolute / 3600 << ':' << std::setw(2) << absolute / 60 % 60;
            }
            *output = text.str();
            return true;
        }
        default:
            return false;
    }
}

std::string cell_as_string(const Cell& cell) {
    if (const auto* value = std::get_if<std::string>(&cell)) {
        return *value;
    }
    if (const auto* value = std::get_if<std::int64_t>(&cell)) {
        return std::to_string(*value);
    }
    if (const auto* value = std::get_if<std::int32_t>(&cell)) {
        return std::to_string(*value);
    }
    if (const auto* value = std::get_if<double>(&cell)) {
        // Shortest representation that preserves the value (std::to_string always uses 6 decimals).
        char buffer[64];
        const auto result = std::to_chars(buffer, buffer + sizeof(buffer), *value);
        return std::string(buffer, result.ptr);
    }
    if (const auto* value = std::get_if<bool>(&cell)) {
        return *value ? "1" : "0";
    }
    if (const auto* value = std::get_if<std::vector<std::uint8_t>>(&cell)) {
        return std::string(value->begin(), value->end());
    }
    if (const auto* value = std::get_if<UuidCell>(&cell)) {
        return value->value;
    }
    return {};
}

template <typename T>
void write_numeric(SQLPOINTER output, SQLLEN* indicator, const T& value) {
    if (indicator != nullptr) {
        *indicator = static_cast<SQLLEN>(sizeof(T));
    }
    if (output != nullptr) {
        std::memcpy(output, &value, sizeof(T));
    }
}

std::string trim_copy(const std::string& text) {
    const auto first = text.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) {
        return {};
    }
    return text.substr(first, text.find_last_not_of(" \t\r\n") - first + 1);
}

bool cell_as_double(const Cell& cell, double* output) {
    if (const auto* value = std::get_if<double>(&cell)) {
        *output = *value;
    } else if (const auto* value = std::get_if<std::int64_t>(&cell)) {
        *output = static_cast<double>(*value);
    } else if (const auto* value = std::get_if<std::int32_t>(&cell)) {
        *output = static_cast<double>(*value);
    } else if (const auto* value = std::get_if<bool>(&cell)) {
        *output = *value ? 1.0 : 0.0;
    } else if (const auto* value = std::get_if<std::string>(&cell)) {
        const std::string text = trim_copy(*value);
        const auto result = std::from_chars(text.data(), text.data() + text.size(), *output);
        return result.ec == std::errc{} && result.ptr == text.data() + text.size() && !text.empty();
    } else {
        return false;
    }
    return true;
}

// Integer from the cell; decimal texts (e.g. DECIMAL "12.50") are truncated.
bool cell_as_int64(const Cell& cell, std::int64_t* output, bool* fraction_lost) {
    *fraction_lost = false;
    if (const auto* value = std::get_if<std::int64_t>(&cell)) {
        *output = *value;
    } else if (const auto* value = std::get_if<std::int32_t>(&cell)) {
        *output = *value;
    } else if (const auto* value = std::get_if<bool>(&cell)) {
        *output = *value ? 1 : 0;
    } else if (const auto* value = std::get_if<double>(&cell)) {
        if (!(*value >= -9.2233720368547758e18 && *value < 9.2233720368547758e18)) {
            return false;
        }
        *output = static_cast<std::int64_t>(*value);
        *fraction_lost = static_cast<double>(*output) != *value;
    } else if (const auto* value = std::get_if<std::string>(&cell)) {
        const std::string text = trim_copy(*value);
        const char* begin = text.data();
        const char* end = begin + text.size();
        if (begin != end && *begin == '+') {
            ++begin;
        }
        const auto result = std::from_chars(begin, end, *output);
        if (result.ec != std::errc{} || result.ptr == begin) {
            return false;
        }
        if (result.ptr != end) {
            if (*result.ptr != '.' ||
                std::any_of(result.ptr + 1, end, [](char c) { return !std::isdigit(static_cast<unsigned char>(c)); })) {
                return false;
            }
            *fraction_lost = std::any_of(result.ptr + 1, end, [](char c) { return c != '0'; });
        }
    } else {
        return false;
    }
    return true;
}

// Parses "YYYY-MM-DD", "HH:MM:SS[.f]" or "YYYY-MM-DD HH:MM:SS[.f]" (format produced by decode_value).
bool parse_date_time(const std::string& raw, SQL_TIMESTAMP_STRUCT* output, bool* has_date,
                     bool* has_time) {
    const std::string text = trim_copy(raw);
    *output = SQL_TIMESTAMP_STRUCT{};
    *has_date = false;
    *has_time = false;
    std::size_t position = 0;
    auto read_number = [&](std::size_t digits, int* value) -> bool {
        if (position + digits > text.size()) {
            return false;
        }
        const auto result = std::from_chars(text.data() + position, text.data() + position + digits, *value);
        if (result.ec != std::errc{} || result.ptr != text.data() + position + digits) {
            return false;
        }
        position += digits;
        return true;
    };
    int year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0;
    if (text.size() >= 10 && text[4] == '-' && text[7] == '-') {
        if (!read_number(4, &year) || text[position++] != '-' || !read_number(2, &month) ||
            text[position++] != '-' || !read_number(2, &day)) {
            return false;
        }
        *has_date = true;
        if (position < text.size() && (text[position] == ' ' || text[position] == 'T')) {
            ++position;
        }
    }
    if (position < text.size()) {
        if (!read_number(2, &hour) || position >= text.size() || text[position++] != ':' ||
            !read_number(2, &minute)) {
            return false;
        }
        if (position < text.size() && text[position] == ':') {
            ++position;
            if (!read_number(2, &second)) {
                return false;
            }
        }
        SQLUINTEGER fraction = 0;
        if (position < text.size() && text[position] == '.') {
            ++position;
            int digits = 0;
            while (position < text.size() && std::isdigit(static_cast<unsigned char>(text[position]))) {
                if (digits < 9) {
                    fraction = fraction * 10 + static_cast<SQLUINTEGER>(text[position] - '0');
                    ++digits;
                }
                ++position;
            }
            while (digits++ < 9) {
                fraction *= 10;
            }
        }
        output->fraction = fraction;
        *has_time = true;
    }
    if (!*has_date && !*has_time) {
        return false;
    }
    output->year = static_cast<SQLSMALLINT>(year);
    output->month = static_cast<SQLUSMALLINT>(month);
    output->day = static_cast<SQLUSMALLINT>(day);
    output->hour = static_cast<SQLUSMALLINT>(hour);
    output->minute = static_cast<SQLUSMALLINT>(minute);
    output->second = static_cast<SQLUSMALLINT>(second);
    return true;
}

// Converts decimal text ("-123.4500") into SQL_NUMERIC_STRUCT, keeping the value's own scale.
bool parse_numeric(const std::string& raw, SQL_NUMERIC_STRUCT* output) {
    const std::string text = trim_copy(raw);
    *output = SQL_NUMERIC_STRUCT{};
    std::size_t position = 0;
    output->sign = 1;
    if (position < text.size() && (text[position] == '-' || text[position] == '+')) {
        output->sign = text[position] == '-' ? 0 : 1;
        ++position;
    }
    std::uint8_t digits_seen = 0;
    std::uint8_t scale = 0;
    bool after_point = false;
    bool any_digit = false;
    for (; position < text.size(); ++position) {
        const char character = text[position];
        if (character == '.' && !after_point) {
            after_point = true;
            continue;
        }
        if (!std::isdigit(static_cast<unsigned char>(character))) {
            return false;
        }
        any_digit = true;
        // val = val * 10 + digit, in 128-bit little-endian.
        unsigned carry = static_cast<unsigned>(character - '0');
        for (auto& byte : output->val) {
            const unsigned product = static_cast<unsigned>(byte) * 10 + carry;
            byte = static_cast<SQLCHAR>(product & 0xFF);
            carry = product >> 8;
        }
        if (carry != 0) {
            return false;
        }
        if (digits_seen > 0 || character != '0') {
            ++digits_seen;
        }
        if (after_point) {
            ++scale;
        }
    }
    if (!any_digit) {
        return false;
    }
    output->precision = static_cast<SQLCHAR>(std::max<std::uint8_t>(digits_seen, 1));
    output->scale = static_cast<SQLSCHAR>(scale);
    return true;
}

bool parse_guid(const std::string& raw, SQLGUID* output) {
    std::string hex;
    for (const char character : raw) {
        if (std::isxdigit(static_cast<unsigned char>(character))) {
            hex.push_back(character);
        } else if (character != '-' && character != '{' && character != '}' && character != ' ') {
            return false;
        }
    }
    if (hex.size() != 32) {
        return false;
    }
    auto read = [&](std::size_t offset, std::size_t digits, auto* value) {
        unsigned long long parsed = 0;
        std::from_chars(hex.data() + offset, hex.data() + offset + digits, parsed, 16);
        *value = static_cast<std::remove_pointer_t<decltype(value)>>(parsed);
    };
    read(0, 8, &output->Data1);
    read(8, 4, &output->Data2);
    read(12, 4, &output->Data3);
    for (std::size_t index = 0; index < 8; ++index) {
        read(16 + index * 2, 2, &output->Data4[index]);
    }
    return true;
}

SQLRETURN write_cell(HandleBase* handle, const Cell& cell, SQLSMALLINT target_type,
                     SQLPOINTER target_value, SQLLEN buffer_length, SQLLEN* indicator) {
    if (std::holds_alternative<std::monostate>(cell)) {
        if (indicator != nullptr) {
            *indicator = SQL_NULL_DATA;
        }
        return SQL_SUCCESS;
    }
    if (target_value == nullptr) {
        return fail(handle, "Output buffer is required", "HY009");
    }
    if (target_type == SQL_C_DEFAULT) {
        if (std::holds_alternative<bool>(cell)) {
            target_type = SQL_C_BIT;
        } else if (std::holds_alternative<std::int32_t>(cell)) {
            target_type = SQL_C_SLONG;
        } else if (std::holds_alternative<std::int64_t>(cell)) {
            target_type = SQL_C_SBIGINT;
        } else if (std::holds_alternative<double>(cell)) {
            target_type = SQL_C_DOUBLE;
        } else if (std::holds_alternative<std::vector<std::uint8_t>>(cell)) {
            target_type = SQL_C_BINARY;
        } else {
            target_type = SQL_C_CHAR;
        }
    }
    if (target_type == SQL_C_CHAR) {
        const std::string text = cell_as_string(cell);
        if (indicator != nullptr) {
            *indicator = static_cast<SQLLEN>(text.size());
        }
        if (buffer_length <= 0) {
            return text.empty() ? SQL_SUCCESS : SQL_SUCCESS_WITH_INFO;
        }
        const auto copy_count = std::min<std::size_t>(text.size(),
            static_cast<std::size_t>(buffer_length - 1));
        std::memcpy(target_value, text.data(), copy_count);
        static_cast<char*>(target_value)[copy_count] = '\0';
        if (copy_count < text.size()) {
            handle->diagnostics.push_back({"01004", 0, "Character result was truncated"});
            return SQL_SUCCESS_WITH_INFO;
        }
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_WCHAR) {
        const std::string text = cell_as_string(cell);
        std::vector<SQLWCHAR> wide;
        if (!utf8_to_wide(text, &wide)) {
            return fail(handle, "Result text is not valid UTF-8", "22018");
        }
        if (indicator != nullptr) {
            *indicator = static_cast<SQLLEN>(wide.size() * sizeof(SQLWCHAR));
        }
        if (buffer_length < 0) {
            return fail(handle, "Invalid wide-character output buffer length", "HY090");
        }
        const auto capacity = static_cast<std::size_t>(buffer_length) / sizeof(SQLWCHAR);
        const auto copy_count = std::min(wide.size(), capacity > 0 ? capacity - 1 : 0);
        std::copy_n(wide.begin(), copy_count, static_cast<SQLWCHAR*>(target_value));
        if (capacity > 0) {
            static_cast<SQLWCHAR*>(target_value)[copy_count] = 0;
        }
        if (copy_count < wide.size()) {
            handle->diagnostics.push_back({"01004", 0, "Wide-character result was truncated"});
            return SQL_SUCCESS_WITH_INFO;
        }
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_BINARY) {
        const auto* bytes = std::get_if<std::vector<std::uint8_t>>(&cell);
        const std::string text = bytes == nullptr ? cell_as_string(cell) :
            std::string(bytes->begin(), bytes->end());
        if (indicator != nullptr) {
            *indicator = static_cast<SQLLEN>(text.size());
        }
        const auto copy_count = std::min<std::size_t>(text.size(),
            static_cast<std::size_t>(std::max<SQLLEN>(0, buffer_length)));
        std::memcpy(target_value, text.data(), copy_count);
        if (copy_count < text.size()) {
            handle->diagnostics.push_back({"01004", 0, "Binary result was truncated"});
            return SQL_SUCCESS_WITH_INFO;
        }
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_LONG || target_type == SQL_C_SLONG || target_type == SQL_C_ULONG ||
        target_type == SQL_C_SHORT || target_type == SQL_C_SSHORT || target_type == SQL_C_USHORT ||
        target_type == SQL_C_TINYINT || target_type == SQL_C_STINYINT || target_type == SQL_C_UTINYINT ||
        target_type == SQL_C_SBIGINT || target_type == SQL_C_UBIGINT) {
        std::int64_t number = 0;
        bool fraction_lost = false;
        if (!cell_as_int64(cell, &number, &fraction_lost)) {
            return fail(handle, "Result value '" + cell_as_string(cell) +
                        "' cannot be converted to integer C type " + std::to_string(target_type), "22018");
        }
        auto store = [&](auto typed_min, auto typed_max) -> bool {
            using Target = decltype(typed_min);
            if (number < static_cast<std::int64_t>(typed_min) ||
                (number > 0 && static_cast<std::uint64_t>(number) > static_cast<std::uint64_t>(typed_max))) {
                return false;
            }
            write_numeric(target_value, indicator, static_cast<Target>(number));
            return true;
        };
        bool stored = false;
        switch (target_type) {
            case SQL_C_LONG: case SQL_C_SLONG:
                stored = store(std::numeric_limits<SQLINTEGER>::min(), std::numeric_limits<SQLINTEGER>::max());
                break;
            case SQL_C_ULONG:
                stored = store(SQLUINTEGER{0}, std::numeric_limits<SQLUINTEGER>::max());
                break;
            case SQL_C_SHORT: case SQL_C_SSHORT:
                stored = store(std::numeric_limits<SQLSMALLINT>::min(), std::numeric_limits<SQLSMALLINT>::max());
                break;
            case SQL_C_USHORT:
                stored = store(SQLUSMALLINT{0}, std::numeric_limits<SQLUSMALLINT>::max());
                break;
            case SQL_C_TINYINT: case SQL_C_STINYINT:
                stored = store(std::numeric_limits<SQLSCHAR>::min(), std::numeric_limits<SQLSCHAR>::max());
                break;
            case SQL_C_UTINYINT:
                stored = store(SQLCHAR{0}, std::numeric_limits<SQLCHAR>::max());
                break;
            case SQL_C_UBIGINT:
                stored = store(SQLUBIGINT{0}, std::numeric_limits<SQLUBIGINT>::max());
                break;
            default:
                stored = store(std::numeric_limits<SQLBIGINT>::min(), std::numeric_limits<SQLBIGINT>::max());
                break;
        }
        if (!stored) {
            return fail(handle, "Result value " + std::to_string(number) +
                        " is out of range for integer C type " + std::to_string(target_type), "22003");
        }
        if (fraction_lost) {
            handle->diagnostics.push_back({"01S07", 0, "Fractional part of a numeric result was truncated"});
            return SQL_SUCCESS_WITH_INFO;
        }
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_DOUBLE || target_type == SQL_C_FLOAT) {
        double number = 0;
        if (!cell_as_double(cell, &number)) {
            return fail(handle, "Result value '" + cell_as_string(cell) +
                        "' cannot be converted to a floating-point type", "22018");
        }
        if (target_type == SQL_C_FLOAT) {
            const float converted = static_cast<float>(number);
            write_numeric(target_value, indicator, converted);
        } else {
            write_numeric(target_value, indicator, number);
        }
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_BIT) {
        double number = 0;
        if (!cell_as_double(cell, &number)) {
            std::string text = trim_copy(cell_as_string(cell));
            std::transform(text.begin(), text.end(), text.begin(),
                           [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
            if (text == "true") {
                number = 1;
            } else if (text == "false") {
                number = 0;
            } else {
                return fail(handle, "Result value '" + text + "' cannot be converted to SQL_C_BIT", "22018");
            }
        }
        const SQLCHAR converted = number != 0 ? 1 : 0;
        write_numeric(target_value, indicator, converted);
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_NUMERIC) {
        SQL_NUMERIC_STRUCT numeric{};
        if (!parse_numeric(cell_as_string(cell), &numeric)) {
            return fail(handle, "Result value '" + cell_as_string(cell) +
                        "' cannot be converted to SQL_C_NUMERIC", "22018");
        }
        write_numeric(target_value, indicator, numeric);
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_TYPE_DATE || target_type == SQL_C_DATE ||
        target_type == SQL_C_TYPE_TIME || target_type == SQL_C_TIME ||
        target_type == SQL_C_TYPE_TIMESTAMP || target_type == SQL_C_TIMESTAMP) {
        SQL_TIMESTAMP_STRUCT timestamp{};
        bool has_date = false;
        bool has_time = false;
        if (!parse_date_time(cell_as_string(cell), &timestamp, &has_date, &has_time)) {
            return fail(handle, "Result value '" + cell_as_string(cell) +
                        "' cannot be converted to date/time C type " + std::to_string(target_type), "22018");
        }
        if (target_type == SQL_C_TYPE_DATE || target_type == SQL_C_DATE) {
            if (!has_date) {
                return fail(handle, "Time value '" + cell_as_string(cell) +
                            "' cannot be converted to a date", "07006");
            }
            const SQL_DATE_STRUCT date{timestamp.year, timestamp.month, timestamp.day};
            write_numeric(target_value, indicator, date);
            if (has_time && (timestamp.hour || timestamp.minute || timestamp.second || timestamp.fraction)) {
                handle->diagnostics.push_back({"01S07", 0, "Time part of a timestamp was truncated"});
                return SQL_SUCCESS_WITH_INFO;
            }
            return SQL_SUCCESS;
        }
        if (target_type == SQL_C_TYPE_TIME || target_type == SQL_C_TIME) {
            const SQL_TIME_STRUCT time{timestamp.hour, timestamp.minute, timestamp.second};
            write_numeric(target_value, indicator, time);
            if (timestamp.fraction != 0) {
                handle->diagnostics.push_back({"01S07", 0, "Fractional seconds were truncated"});
                return SQL_SUCCESS_WITH_INFO;
            }
            return SQL_SUCCESS;
        }
        if (!has_date) {
            // TIME converted to TIMESTAMP gets the current date, as defined by ODBC.
            const std::time_t now = std::time(nullptr);
            std::tm local{};
#if defined(_WIN32)
            localtime_s(&local, &now);
#else
            localtime_r(&now, &local);
#endif
            timestamp.year = static_cast<SQLSMALLINT>(local.tm_year + 1900);
            timestamp.month = static_cast<SQLUSMALLINT>(local.tm_mon + 1);
            timestamp.day = static_cast<SQLUSMALLINT>(local.tm_mday);
        }
        write_numeric(target_value, indicator, timestamp);
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_GUID) {
        SQLGUID guid{};
        if (!parse_guid(cell_as_string(cell), &guid)) {
            return fail(handle, "Result value '" + cell_as_string(cell) +
                        "' cannot be converted to SQL_C_GUID", "22018");
        }
        write_numeric(target_value, indicator, guid);
        return SQL_SUCCESS;
    }
    return fail(handle, "ODBC C target type " + std::to_string(target_type) +
                " is not supported by the OJP client", "07006");
}

std::string first_sql_keyword(const std::string& sql) {
    std::size_t position = 0;
    while (position < sql.size()) {
        while (position < sql.size() && std::isspace(static_cast<unsigned char>(sql[position]))) {
            ++position;
        }
        if (position + 1 < sql.size() && sql[position] == '-' && sql[position + 1] == '-') {
            const auto end = sql.find_first_of("\r\n", position + 2);
            position = end == std::string::npos ? sql.size() : end + 1;
        } else if (position + 1 < sql.size() && sql[position] == '/' && sql[position + 1] == '*') {
            position += 2;
            std::size_t depth = 1;
            while (position < sql.size() && depth != 0) {
                if (position + 1 < sql.size() && sql[position] == '/' && sql[position + 1] == '*') {
                    ++depth;
                    position += 2;
                } else if (position + 1 < sql.size() && sql[position] == '*' && sql[position + 1] == '/') {
                    --depth;
                    position += 2;
                } else {
                    ++position;
                }
            }
        } else {
            break;
        }
    }
    std::string keyword;
    while (position < sql.size() &&
           (std::isalpha(static_cast<unsigned char>(sql[position])) || sql[position] == '_')) {
        keyword.push_back(static_cast<char>(std::toupper(static_cast<unsigned char>(sql[position]))));
        ++position;
    }
    return keyword;
}

bool is_query_sql(const std::string& sql) {
    const std::string keyword = first_sql_keyword(sql);
    return keyword == "SELECT" || keyword == "WITH" || keyword == "VALUES" ||
           keyword == "TABLE" || keyword == "SHOW" || keyword == "EXPLAIN";
}

std::vector<std::string> sql_keywords(const std::string& sql) {
    std::vector<std::string> keywords;
    for (std::size_t index = 0; index < sql.size();) {
        const char character = sql[index];
        if (std::isspace(static_cast<unsigned char>(character))) {
            ++index;
        } else if (character == '-' && index + 1 < sql.size() && sql[index + 1] == '-') {
            const auto end = sql.find_first_of("\r\n", index + 2);
            index = end == std::string::npos ? sql.size() : end + 1;
        } else if (character == '/' && index + 1 < sql.size() && sql[index + 1] == '*') {
            index += 2;
            std::size_t depth = 1;
            while (index < sql.size() && depth != 0) {
                if (index + 1 < sql.size() && sql[index] == '/' && sql[index + 1] == '*') {
                    ++depth;
                    index += 2;
                } else if (index + 1 < sql.size() && sql[index] == '*' && sql[index + 1] == '/') {
                    --depth;
                    index += 2;
                } else {
                    ++index;
                }
            }
        } else if (character == '\'' || character == '"' || character == '`' || character == '[') {
            const char closing = character == '[' ? ']' : character;
            ++index;
            while (index < sql.size()) {
                if (sql[index++] == closing) {
                    if (index < sql.size() && sql[index] == closing) {
                        ++index;
                    } else {
                        break;
                    }
                }
            }
        } else if (character == '$' &&
                   (index == 0 || (!std::isalnum(static_cast<unsigned char>(sql[index - 1])) &&
                                   sql[index - 1] != '_' && sql[index - 1] != '$'))) {
            std::size_t tag_end = index + 1;
            while (tag_end < sql.size() &&
                   (std::isalnum(static_cast<unsigned char>(sql[tag_end])) || sql[tag_end] == '_')) {
                ++tag_end;
            }
            if (tag_end < sql.size() && sql[tag_end] == '$') {
                const auto delimiter = sql.substr(index, tag_end - index + 1);
                const auto end = sql.find(delimiter, tag_end + 1);
                index = end == std::string::npos ? sql.size() : end + delimiter.size();
            } else {
                ++index;
            }
        } else if (std::isalpha(static_cast<unsigned char>(character)) || character == '_') {
            const auto start = index++;
            while (index < sql.size() &&
                   (std::isalnum(static_cast<unsigned char>(sql[index])) ||
                    sql[index] == '_' || sql[index] == '$' || sql[index] == '#')) {
                ++index;
            }
            std::string keyword;
            keyword.reserve(index - start);
            for (std::size_t token_index = start; token_index < index; ++token_index) {
                keyword.push_back(static_cast<char>(
                    std::toupper(static_cast<unsigned char>(sql[token_index]))));
            }
            keywords.push_back(std::move(keyword));
        } else {
            ++index;
        }
    }
    return keywords;
}

bool is_dml_result_set_sql(const std::string& sql) {
    const std::string first_keyword = first_sql_keyword(sql);
    const bool is_dml = first_keyword == "INSERT" || first_keyword == "UPDATE" ||
        first_keyword == "DELETE" || first_keyword == "MERGE";
    const bool is_query_with_dml = first_keyword == "SELECT" || first_keyword == "WITH";
    // Oracle RETURNING INTO is an OUT-parameter flow, not a JDBC ResultSet.
    if (!is_dml && !is_query_with_dml) {
        return false;
    }

    const auto keywords = sql_keywords(sql);
    const auto contains = [&keywords](const std::string& keyword) {
        return std::find(keywords.begin(), keywords.end(), keyword) != keywords.end();
    };
    const bool has_dml = contains("INSERT") || contains("UPDATE") ||
        contains("DELETE") || contains("MERGE");
    if (!has_dml) {
        return false;
    }

    if (contains("OUTPUT") || contains("RETURNING")) {
        return true;
    }

    if (!is_query_sql(sql)) {
        return false;
    }
    for (std::size_t index = 1; index < keywords.size(); ++index) {
        if (keywords[index - 1] == "FINAL" && keywords[index] == "TABLE") {
            return true;
        }
    }
    return false;
}

// Long text up to this size (UTF-8) is sent inline in the request; above it a LOB is used (the
// server's default gRPC message limit is 4 MiB).
constexpr std::size_t kInlineTextLobLimit = 2 * 1024 * 1024;

// Encodes decimal text ("-123.45") as BigDecimalWire: 0x01, int32 length, digits, int32 scale.
bool encode_decimal_wire(const std::string& raw, std::string* wire) {
    const std::string decimal_text = trim_copy(raw);
    std::string digits;
    int scale = 0;
    bool after_decimal = false;
    bool negative = false;
    std::size_t index = 0;
    if (!decimal_text.empty() && (decimal_text[0] == '-' || decimal_text[0] == '+')) {
        negative = decimal_text[0] == '-';
        index = 1;
    }
    for (; index < decimal_text.size(); ++index) {
        const char character = decimal_text[index];
        if (character == '.' && !after_decimal) {
            after_decimal = true;
        } else if (character >= '0' && character <= '9') {
            digits.push_back(character);
            if (after_decimal) {
                ++scale;
            }
        } else {
            return false;
        }
    }
    if (digits.empty()) {
        return false;
    }
    const auto first_significant = digits.find_first_not_of('0');
    digits = first_significant == std::string::npos ? "0" : digits.substr(first_significant);
    const std::string unscaled = negative && digits != "0" ? "-" + digits : digits;
    wire->clear();
    wire->push_back('\1');
    const auto length = static_cast<std::uint32_t>(unscaled.size());
    for (int shift = 24; shift >= 0; shift -= 8) {
        wire->push_back(static_cast<char>((length >> shift) & 0xff));
    }
    wire->append(unscaled);
    const auto wire_scale = static_cast<std::uint32_t>(scale);
    for (int shift = 24; shift >= 0; shift -= 8) {
        wire->push_back(static_cast<char>((wire_scale >> shift) & 0xff));
    }
    return true;
}

// Wall-clock date/time sent as a UTC instant (same contract used by SQL_C_TYPE_TIMESTAMP).
void set_timestamp_value(ParameterValue* value, const SQL_TIMESTAMP_STRUCT& timestamp) {
    auto* timestamp_value = value->mutable_timestamp_value();
    const std::int64_t days = days_from_civil(timestamp.year, timestamp.month, timestamp.day);
    const std::int64_t seconds = days * 86400 +
        timestamp.hour * 3600 + timestamp.minute * 60 + timestamp.second;
    auto* instant = timestamp_value->mutable_instant();
    instant->set_seconds(seconds);
    instant->set_nanos(static_cast<std::int32_t>(timestamp.fraction));
    timestamp_value->set_timezone("UTC");
    timestamp_value->set_original_type(com::openjproxy::grpc::TEMPORAL_TYPE_TIMESTAMP);
}

// OJP binds NULL parameters with PreparedStatement.setNull, which needs a java.sql.Types code.
std::int32_t jdbc_null_type(SQLSMALLINT sql_type) {
    switch (sql_type) {
        case SQL_CHAR:
        case SQL_VARCHAR:
        case SQL_LONGVARCHAR:
        case SQL_WVARCHAR:
        case SQL_DECIMAL:
        case SQL_NUMERIC:
        case SQL_SMALLINT:
        case SQL_INTEGER:
        case SQL_REAL:
        case SQL_FLOAT:
        case SQL_DOUBLE:
        case SQL_BIT:
        case SQL_TINYINT:
        case SQL_BIGINT:
        case SQL_BINARY:
        case SQL_VARBINARY:
        case SQL_TYPE_DATE:
        case SQL_TYPE_TIME:
        case SQL_TYPE_TIMESTAMP:
            return sql_type;  // ODBC and java.sql.Types share these codes.
        case SQL_LONGVARBINARY: return 2004;  // Types.BLOB
        case SQL_WCHAR: return -15;         // Types.NCHAR
        case SQL_WLONGVARCHAR: return -16;  // Types.LONGNVARCHAR
        case SQL_GUID: return 1;            // Types.CHAR
        default: return 0;                  // Types.NULL
    }
}

bool set_parameter_value(ParameterValue* value, const BoundParameter& bound,
                         SQLUSMALLINT parameter_index, Diagnostic* error) {
    const auto* data = static_cast<const std::uint8_t*>(bound.value);
    SQLLEN length = bound.buffer_length;
    if ((bound.indicator != nullptr && *bound.indicator == SQL_NULL_DATA) || data == nullptr) {
        value->set_int_value(jdbc_null_type(bound.parameter_type));
        return true;
    }
    if (bound.indicator != nullptr) {
        length = *bound.indicator;
    }
    SQLSMALLINT c_type = bound.value_type;
    if (c_type == SQL_C_DEFAULT) {
        switch (bound.parameter_type) {
            case SQL_TINYINT: c_type = SQL_C_STINYINT; break;
            case SQL_SMALLINT: c_type = SQL_C_SSHORT; break;
            case SQL_INTEGER: c_type = SQL_C_SLONG; break;
            case SQL_BIGINT: c_type = SQL_C_SBIGINT; break;
            case SQL_REAL: c_type = SQL_C_FLOAT; break;
            case SQL_FLOAT:
            case SQL_DOUBLE: c_type = SQL_C_DOUBLE; break;
            case SQL_DECIMAL:
            case SQL_NUMERIC: c_type = SQL_C_NUMERIC; break;
            case SQL_TYPE_DATE: c_type = SQL_C_TYPE_DATE; break;
            case SQL_TYPE_TIME: c_type = SQL_C_TYPE_TIME; break;
            case SQL_TYPE_TIMESTAMP: c_type = SQL_C_TYPE_TIMESTAMP; break;
            default: c_type = SQL_C_CHAR; break;
        }
    }
    // ODBC 2.x aliases used by older applications/driver managers.
    if (c_type == SQL_C_DATE) {
        c_type = SQL_C_TYPE_DATE;
    } else if (c_type == SQL_C_TIME) {
        c_type = SQL_C_TYPE_TIME;
    } else if (c_type == SQL_C_TIMESTAMP) {
        c_type = SQL_C_TYPE_TIMESTAMP;
    }
    auto set_decimal = [&](const std::string& decimal_text) {
        std::string wire;
        if (!encode_decimal_wire(decimal_text, &wire)) {
            error->state = "22018";
            error->message = "Parameter " + std::to_string(parameter_index) +
                ": value '" + decimal_text.substr(0, 64) + "' is not a valid decimal number";
            error->native_error = static_cast<SQLINTEGER>(parameter_index);
            return false;
        }
        value->set_bytes_value(wire);
        return true;
    };
    auto set_temporal_type = [&](SQLSMALLINT temporal_type) {
        if (temporal_type == SQL_C_TYPE_DATE) {
            const auto* date = reinterpret_cast<const SQL_DATE_STRUCT*>(data);
            auto* date_value = value->mutable_date_value();
            date_value->set_year(date->year);
            date_value->set_month(date->month);
            date_value->set_day(date->day);
            return true;
        }
        if (temporal_type == SQL_C_TYPE_TIME) {
            const auto* time = reinterpret_cast<const SQL_TIME_STRUCT*>(data);
            auto* time_value = value->mutable_time_value();
            time_value->set_hours(time->hour);
            time_value->set_minutes(time->minute);
            time_value->set_seconds(time->second);
            return true;
        }
        if (temporal_type == SQL_C_TYPE_TIMESTAMP) {
            const auto* timestamp = reinterpret_cast<const SQL_TIMESTAMP_STRUCT*>(data);
            set_timestamp_value(value, *timestamp);
            return true;
        }
        return false;
    };
    if (c_type == SQL_C_CHAR || c_type == SQL_C_WCHAR) {
        const bool wide = c_type == SQL_C_WCHAR;
        std::size_t text_length = length == SQL_NTS
            ? (wide
                ? [&]() {
                    const auto* characters = reinterpret_cast<const SQLWCHAR*>(data);
                    std::size_t count = 0;
                    while (characters[count] != 0) {
                        ++count;
                    }
                    return count * sizeof(SQLWCHAR);
                }()
                : std::strlen(reinterpret_cast<const char*>(data)))
            : static_cast<std::size_t>(std::max<SQLLEN>(0, length));
        if (bound.buffer_length > 0) {
            text_length = std::min(text_length, static_cast<std::size_t>(bound.buffer_length));
        }
        if (wide && text_length % sizeof(SQLWCHAR) != 0) {
            error->state = "HY090";
            error->message = "Wide-character parameter length is not a whole number of SQLWCHAR units";
            error->native_error = static_cast<SQLINTEGER>(parameter_index);
            return false;
        }
        std::string text;
        if (wide) {
            if (!wide_to_utf8(reinterpret_cast<const SQLWCHAR*>(data),
                              text_length / sizeof(SQLWCHAR), &text)) {
                error->state = "22018";
                error->message = "Wide-character parameter contains invalid Unicode";
                error->native_error = static_cast<SQLINTEGER>(parameter_index);
                return false;
            }
        } else {
            text.assign(reinterpret_cast<const char*>(data), text_length);
        }
        if (bound.parameter_type == SQL_DECIMAL || bound.parameter_type == SQL_NUMERIC) {
            return set_decimal(text);
        }
        value->set_string_value(text);
        return true;
    }
    if (c_type == SQL_C_NUMERIC) {
        const auto* numeric = reinterpret_cast<const SQL_NUMERIC_STRUCT*>(data);
        std::vector<unsigned int> decimal_digits(1, 0);
        for (int byte_index = static_cast<int>(sizeof(numeric->val)) - 1; byte_index >= 0;
             --byte_index) {
            unsigned int carry = numeric->val[byte_index];
            for (auto& digit : decimal_digits) {
                const unsigned int value_in_base = digit * 256 + carry;
                digit = value_in_base % 10;
                carry = value_in_base / 10;
            }
            while (carry != 0) {
                decimal_digits.push_back(carry % 10);
                carry /= 10;
            }
        }
        std::string digits;
        for (auto digit = decimal_digits.rbegin(); digit != decimal_digits.rend(); ++digit) {
            digits.push_back(static_cast<char>('0' + *digit));
        }
        std::string decimal = numeric->sign == 0 ? "-" : "";
        const auto scale = static_cast<int>(numeric->scale);
        if (scale > 0 && digits.size() <= static_cast<std::size_t>(scale)) {
            decimal.append(static_cast<std::size_t>(scale) + 1 - digits.size(), '0');
        }
        decimal += digits;
        if (scale > 0) {
            decimal.insert(decimal.size() - static_cast<std::size_t>(scale), 1, '.');
        }
        return set_decimal(decimal);
    }
    if (c_type == SQL_C_TYPE_DATE || c_type == SQL_C_TYPE_TIME ||
        c_type == SQL_C_TYPE_TIMESTAMP) {
        return set_temporal_type(c_type);
    }
    switch (c_type) {
        case SQL_C_TINYINT:
        case SQL_C_STINYINT:
            value->set_int_value(*reinterpret_cast<const SQLSCHAR*>(data));
            return true;
        case SQL_C_SHORT:
        case SQL_C_SSHORT:
            value->set_int_value(*reinterpret_cast<const SQLSMALLINT*>(data));
            return true;
        case SQL_C_LONG:
        case SQL_C_SLONG:
            value->set_int_value(*reinterpret_cast<const SQLINTEGER*>(data));
            return true;
        case SQL_C_SBIGINT:
            value->set_long_value(*reinterpret_cast<const SQLBIGINT*>(data));
            return true;
        case SQL_C_UTINYINT:
            value->set_int_value(*reinterpret_cast<const SQLCHAR*>(data));
            return true;
        case SQL_C_USHORT:
            value->set_int_value(*reinterpret_cast<const SQLUSMALLINT*>(data));
            return true;
        case SQL_C_ULONG:
            value->set_long_value(*reinterpret_cast<const SQLUINTEGER*>(data));
            return true;
        case SQL_C_UBIGINT: {
            const auto unsigned_value = *reinterpret_cast<const SQLUBIGINT*>(data);
            if (unsigned_value > static_cast<SQLUBIGINT>(std::numeric_limits<std::int64_t>::max())) {
                error->state = "22003";
                error->message = "Parameter " + std::to_string(parameter_index) +
                    ": unsigned BIGINT value " + std::to_string(unsigned_value) +
                    " exceeds the signed 64-bit range supported by OJP";
                error->native_error = static_cast<SQLINTEGER>(parameter_index);
                return false;
            }
            value->set_long_value(static_cast<std::int64_t>(unsigned_value));
            return true;
        }
        case SQL_C_GUID: {
            const auto* guid = reinterpret_cast<const SQLGUID*>(data);
            char text[37];
            std::snprintf(text, sizeof(text),
                          "%08lX-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X",
                          static_cast<unsigned long>(guid->Data1), guid->Data2, guid->Data3,
                          guid->Data4[0], guid->Data4[1], guid->Data4[2], guid->Data4[3],
                          guid->Data4[4], guid->Data4[5], guid->Data4[6], guid->Data4[7]);
            value->set_string_value(text);
            return true;
        }
        case SQL_C_FLOAT:
            value->set_float_value(*reinterpret_cast<const float*>(data));
            return true;
        case SQL_C_DOUBLE:
            value->set_double_value(*reinterpret_cast<const double*>(data));
            return true;
        case SQL_C_BIT:
            value->set_bool_value(*reinterpret_cast<const SQLCHAR*>(data) != 0);
            return true;
        case SQL_C_BINARY: {
            const auto byte_count = static_cast<std::size_t>(std::max<SQLLEN>(0, length));
            value->set_bytes_value(std::string(reinterpret_cast<const char*>(data), byte_count));
            return true;
        }
        default:
            error->state = "07006";
            error->message = "Parameter " + std::to_string(parameter_index) + ": ODBC C data type " +
                std::to_string(c_type) + " (SQL type " + std::to_string(bound.parameter_type) +
                ") is not supported by the OJP client";
            error->native_error = static_cast<SQLINTEGER>(parameter_index);
            return false;
    }
}

ParameterTypeProto parameter_type(const BoundParameter& bound) {
    using namespace com::openjproxy::grpc;
    if (bound.value == nullptr ||
        (bound.indicator != nullptr && *bound.indicator == SQL_NULL_DATA)) {
        return PT_NULL;
    }
    switch (bound.parameter_type) {
        case SQL_TINYINT: return PT_BYTE;
        case SQL_SMALLINT: return PT_SHORT;
        case SQL_INTEGER: return PT_INT;
        case SQL_BIGINT: return PT_LONG;
        case SQL_REAL: return PT_FLOAT;
        case SQL_FLOAT:
        case SQL_DOUBLE: return PT_DOUBLE;
        case SQL_DECIMAL:
        case SQL_NUMERIC: return PT_BIG_DECIMAL;
        case SQL_BINARY:
        case SQL_VARBINARY: return PT_BYTES;
        case SQL_LONGVARBINARY: return PT_BLOB;
        case SQL_LONGVARCHAR: return PT_CLOB;
        case SQL_WLONGVARCHAR: return PT_N_CLOB;
        case SQL_WCHAR:
        case SQL_WVARCHAR: return PT_N_STRING;
        case SQL_BIT: return PT_BOOLEAN;
        case SQL_TYPE_DATE: return PT_DATE;
        case SQL_TYPE_TIME: return PT_TIME;
        case SQL_TYPE_TIMESTAMP: return PT_TIMESTAMP;
        default: return PT_STRING;
    }
}

// The server strictly casts the value according to the ParameterType (e.g. FLOAT requires Float, LONG
// requires Long). Since the value field comes from the C type and the ParameterType from the SQL type,
// align both here (e.g. SQL_C_DOUBLE with SQL_REAL, SQL_C_SLONG with SQL_BIGINT, text with SQL_INTEGER).
bool coerce_parameter_value(ParameterValue* value, ParameterTypeProto type,
                            SQLUSMALLINT parameter_index, Diagnostic* error) {
    using namespace com::openjproxy::grpc;
    const auto value_case = value->value_case();
    auto fail_with = [&](const char* state, const std::string& detail) {
        error->state = state;
        error->message = "Parameter " + std::to_string(parameter_index) + " (" +
            ParameterTypeProto_Name(type) + "): " + detail;
        error->native_error = static_cast<SQLINTEGER>(parameter_index);
        return false;
    };
    auto value_text = [&]() -> std::string {
        switch (value_case) {
            case ParameterValue::kBoolValue: return value->bool_value() ? "1" : "0";
            case ParameterValue::kIntValue: return std::to_string(value->int_value());
            case ParameterValue::kLongValue: return std::to_string(value->long_value());
            case ParameterValue::kFloatValue:
            case ParameterValue::kDoubleValue: {
                const double number = value_case == ParameterValue::kFloatValue
                    ? static_cast<double>(value->float_value()) : value->double_value();
                char buffer[64];
                const auto result = std::to_chars(buffer, buffer + sizeof(buffer), number);
                return std::string(buffer, result.ptr);
            }
            case ParameterValue::kStringValue: return value->string_value();
            default: return {};
        }
    };
    const bool numeric_or_text = value_case == ParameterValue::kBoolValue ||
        value_case == ParameterValue::kIntValue || value_case == ParameterValue::kLongValue ||
        value_case == ParameterValue::kFloatValue || value_case == ParameterValue::kDoubleValue ||
        value_case == ParameterValue::kStringValue;
    // Reads the current value as double (for FLOAT/DOUBLE) or integer (for INT/LONG).
    auto as_double = [&](double* number) -> bool {
        switch (value_case) {
            case ParameterValue::kBoolValue: *number = value->bool_value() ? 1 : 0; return true;
            case ParameterValue::kIntValue: *number = value->int_value(); return true;
            case ParameterValue::kLongValue: *number = static_cast<double>(value->long_value()); return true;
            case ParameterValue::kFloatValue: *number = value->float_value(); return true;
            case ParameterValue::kDoubleValue: *number = value->double_value(); return true;
            case ParameterValue::kStringValue: {
                const std::string text = trim_copy(value->string_value());
                const auto result = std::from_chars(text.data(), text.data() + text.size(), *number);
                return !text.empty() && result.ec == std::errc{} && result.ptr == text.data() + text.size();
            }
            default: return false;
        }
    };
    auto as_integer = [&](std::int64_t* number) -> bool {
        switch (value_case) {
            case ParameterValue::kBoolValue: *number = value->bool_value() ? 1 : 0; return true;
            case ParameterValue::kIntValue: *number = value->int_value(); return true;
            case ParameterValue::kLongValue: *number = value->long_value(); return true;
            case ParameterValue::kStringValue: {
                std::string text = trim_copy(value->string_value());
                if (!text.empty() && text[0] == '+') {
                    text.erase(0, 1);
                }
                const auto result = std::from_chars(text.data(), text.data() + text.size(), *number);
                if (!text.empty() && result.ec == std::errc{} && result.ptr == text.data() + text.size()) {
                    return true;
                }
                [[fallthrough]];
            }
            default: {
                double real = 0;
                if (!as_double(&real) || !std::isfinite(real) ||
                    real < -9223372036854775808.0 || real >= 9223372036854775808.0) {
                    return false;
                }
                // Truncation toward zero, as SQLBindParameter does for integer types.
                *number = static_cast<std::int64_t>(real);
                return true;
            }
        }
    };
    switch (type) {
        case PT_INT:
        case PT_SHORT:
        case PT_BYTE: {
            if (value_case == ParameterValue::kIntValue) {
                return true;
            }
            std::int64_t number = 0;
            if (!numeric_or_text || !as_integer(&number)) {
                return fail_with("22018", "value '" + value_text().substr(0, 64) +
                                 "' cannot be converted to an integer");
            }
            const std::int64_t minimum = type == PT_SHORT ? -32768 : std::numeric_limits<std::int32_t>::min();
            const std::int64_t maximum = type == PT_SHORT ? 32767 : std::numeric_limits<std::int32_t>::max();
            if (number < minimum || number > maximum) {
                return fail_with("22003", "value " + std::to_string(number) + " is out of range");
            }
            value->set_int_value(static_cast<std::int32_t>(number));
            return true;
        }
        case PT_LONG: {
            if (value_case == ParameterValue::kLongValue) {
                return true;
            }
            std::int64_t number = 0;
            if (!numeric_or_text || !as_integer(&number)) {
                return fail_with("22018", "value '" + value_text().substr(0, 64) +
                                 "' cannot be converted to a BIGINT");
            }
            value->set_long_value(number);
            return true;
        }
        case PT_FLOAT:
        case PT_DOUBLE: {
            const bool already = type == PT_FLOAT ? value_case == ParameterValue::kFloatValue
                                                  : value_case == ParameterValue::kDoubleValue;
            if (already) {
                return true;
            }
            double number = 0;
            if (!numeric_or_text || !as_double(&number)) {
                return fail_with("22018", "value '" + value_text().substr(0, 64) +
                                 "' cannot be converted to a floating-point number");
            }
            if (type == PT_FLOAT) {
                if (std::isfinite(number) && std::fabs(number) > std::numeric_limits<float>::max()) {
                    return fail_with("22003", "value " + value_text() + " is out of REAL range");
                }
                value->set_float_value(static_cast<float>(number));
            } else {
                value->set_double_value(number);
            }
            return true;
        }
        case PT_BOOLEAN: {
            if (value_case == ParameterValue::kBoolValue) {
                return true;
            }
            if (value_case == ParameterValue::kStringValue) {
                std::string text = trim_copy(value->string_value());
                std::transform(text.begin(), text.end(), text.begin(),
                               [](unsigned char character) { return static_cast<char>(std::tolower(character)); });
                if (text == "true" || text == "t" || text == "y" || text == "s") {
                    value->set_bool_value(true);
                    return true;
                }
                if (text == "false" || text == "f" || text == "n") {
                    value->set_bool_value(false);
                    return true;
                }
            }
            double number = 0;
            if (!numeric_or_text || !as_double(&number)) {
                return fail_with("22018", "value '" + value_text().substr(0, 64) +
                                 "' cannot be converted to BIT");
            }
            value->set_bool_value(number != 0);
            return true;
        }
        case PT_STRING:
        case PT_N_STRING:
            if (value_case != ParameterValue::kStringValue && numeric_or_text) {
                value->set_string_value(value_text());
            }
            return true;
        case PT_BIG_DECIMAL: {
            if (value_case == ParameterValue::kBytesValue || !numeric_or_text) {
                return true;
            }
            std::string text = value_text();
            if (value_case == ParameterValue::kFloatValue || value_case == ParameterValue::kDoubleValue) {
                // to_chars may produce scientific notation; BigDecimalWire needs fixed digits.
                const double number = value_case == ParameterValue::kFloatValue
                    ? static_cast<double>(value->float_value()) : value->double_value();
                char buffer[512];
                const auto result = std::to_chars(buffer, buffer + sizeof(buffer), number,
                                                  std::chars_format::fixed);
                if (result.ec != std::errc{} || !std::isfinite(number)) {
                    return fail_with("22003", "value " + text + " cannot be represented as DECIMAL");
                }
                text.assign(buffer, result.ptr);
            } else if (value_case == ParameterValue::kBoolValue) {
                text = value->bool_value() ? "1" : "0";
            }
            std::string wire;
            if (!encode_decimal_wire(text, &wire)) {
                return fail_with("22018", "value '" + text.substr(0, 64) + "' is not a valid decimal number");
            }
            value->set_bytes_value(wire);
            return true;
        }
        case PT_DATE:
        case PT_TIME:
        case PT_TIMESTAMP: {
            SQL_TIMESTAMP_STRUCT parsed{};
            bool has_date = false;
            bool has_time = false;
            if (value_case == ParameterValue::kStringValue) {
                if (!parse_date_time(value->string_value(), &parsed, &has_date, &has_time)) {
                    return fail_with("22007", "value '" + value->string_value().substr(0, 64) +
                                     "' is not a valid date/time");
                }
            } else if (value_case == ParameterValue::kTimestampValue && type != PT_TIMESTAMP) {
                // E.g. the application sends SQL_C_TYPE_TIMESTAMP for a DATE/TIME column (keeps the fraction for TIME).
                const auto& instant = value->timestamp_value().instant();
                const std::int64_t days = floor_div(instant.seconds(), 86400);
                const std::int64_t second_of_day = instant.seconds() - days * 86400;
                if (type == PT_TIME) {
                    auto* time = value->mutable_time_value();
                    const auto nanos = instant.nanos();
                    time->set_hours(static_cast<std::int32_t>(second_of_day / 3600));
                    time->set_minutes(static_cast<std::int32_t>(second_of_day / 60 % 60));
                    time->set_seconds(static_cast<std::int32_t>(second_of_day % 60));
                    time->set_nanos(nanos);
                    return true;
                }
                const CivilDate civil = civil_from_days(days);
                auto* date = value->mutable_date_value();
                date->set_year(static_cast<std::int32_t>(civil.year));
                date->set_month(static_cast<std::int32_t>(civil.month));
                date->set_day(static_cast<std::int32_t>(civil.day));
                return true;
            } else if (value_case == ParameterValue::kDateValue && type == PT_TIMESTAMP) {
                const auto date = value->date_value();
                parsed.year = static_cast<SQLSMALLINT>(date.year());
                parsed.month = static_cast<SQLUSMALLINT>(date.month());
                parsed.day = static_cast<SQLUSMALLINT>(date.day());
                set_timestamp_value(value, parsed);
                return true;
            } else {
                return true;
            }
            if (type == PT_DATE) {
                auto* date = value->mutable_date_value();
                date->set_year(parsed.year);
                date->set_month(parsed.month);
                date->set_day(parsed.day);
            } else if (type == PT_TIME) {
                auto* time = value->mutable_time_value();
                time->set_hours(parsed.hour);
                time->set_minutes(parsed.minute);
                time->set_seconds(parsed.second);
                time->set_nanos(static_cast<std::int32_t>(parsed.fraction));
            } else {
                if (!has_date) {
                    parsed.year = 1900;
                    parsed.month = 1;
                    parsed.day = 1;
                }
                set_timestamp_value(value, parsed);
            }
            return true;
        }
        default:
            return true;
    }
}

bool is_lob_parameter_type(SQLSMALLINT sql_type) {
    return sql_type == SQL_LONGVARBINARY || sql_type == SQL_LONGVARCHAR ||
           sql_type == SQL_WLONGVARCHAR;
}

bool is_data_at_execution(const BoundParameter& bound) {
    return bound.indicator != nullptr &&
        (*bound.indicator == SQL_DATA_AT_EXEC ||
         *bound.indicator <= SQL_LEN_DATA_AT_EXEC_OFFSET);
}

bool get_lob_parameter_data(const BoundParameter& bound, std::string* data, Diagnostic* error) {
    if (is_data_at_execution(bound)) {
        if (bound.indicator != nullptr && *bound.indicator != SQL_DATA_AT_EXEC &&
            *bound.indicator <= SQL_LEN_DATA_AT_EXEC_OFFSET) {
            const auto expected_length =
                static_cast<SQLLEN>(SQL_LEN_DATA_AT_EXEC_OFFSET) - *bound.indicator;
            if (expected_length < 0 ||
                static_cast<std::uint64_t>(expected_length) != bound.streamed_data.size()) {
                error->state = "22001";
                error->message = "LOB stream length does not match its declared length";
                return false;
            }
        }
        if (bound.value_type == SQL_C_WCHAR) {
            if (bound.streamed_data.size() % sizeof(SQLWCHAR) != 0 ||
                !wide_to_utf8(reinterpret_cast<const SQLWCHAR*>(bound.streamed_data.data()),
                              bound.streamed_data.size() / sizeof(SQLWCHAR), data)) {
                error->state = "22018";
                error->message = "Wide-character LOB stream contains invalid Unicode";
                return false;
            }
        } else {
            *data = bound.streamed_data;
        }
        return true;
    }
    if (bound.value == nullptr) {
        error->state = "HY009";
        error->message = "LOB parameter buffer is required";
        return false;
    }
    if (bound.value_type != SQL_C_DEFAULT && bound.value_type != SQL_C_CHAR &&
        bound.value_type != SQL_C_WCHAR &&
        bound.value_type != SQL_C_BINARY) {
        error->state = "07006";
        error->message = "LOB parameters require SQL_C_CHAR or SQL_C_BINARY values";
        return false;
    }
    const auto* bytes = static_cast<const char*>(bound.value);
    SQLLEN length = bound.indicator == nullptr ? bound.buffer_length : *bound.indicator;
    if (length == SQL_NTS) {
        if (bound.value_type != SQL_C_CHAR && bound.value_type != SQL_C_WCHAR) {
            error->state = "HY090";
            error->message = "SQL_NTS is only valid for character LOB parameters";
            return false;
        }
        if (bound.value_type == SQL_C_WCHAR) {
            const auto* characters = reinterpret_cast<const SQLWCHAR*>(bytes);
            std::size_t units = 0;
            while (characters[units] != 0) {
                ++units;
            }
            length = static_cast<SQLLEN>(units * sizeof(SQLWCHAR));
        } else {
            length = static_cast<SQLLEN>(std::strlen(bytes));
        }
    }
    if (length < 0) {
        error->state = "HY090";
        error->message = "LOB parameter length is invalid";
        return false;
    }
    if (bound.buffer_length >= 0 && length > bound.buffer_length) {
        error->state = "HY090";
        error->message = "LOB parameter length exceeds its bound buffer";
        return false;
    }
    if (bound.value_type == SQL_C_WCHAR) {
        if (length % sizeof(SQLWCHAR) != 0) {
            error->state = "HY090";
            error->message = "Wide-character LOB length is invalid";
            return false;
        }
        if (!wide_to_utf8(reinterpret_cast<const SQLWCHAR*>(bytes),
                          static_cast<std::size_t>(length) / sizeof(SQLWCHAR), data)) {
            error->state = "22018";
            error->message = "Wide-character LOB contains invalid Unicode";
            return false;
        }
    } else {
        data->assign(bytes, static_cast<std::size_t>(length));
    }
    return true;
}

std::size_t utf8_character_units(const std::string& text, std::size_t offset, std::size_t length) {
    std::size_t units = 0;
    for (std::size_t index = 0; index < length;) {
        const auto character = static_cast<unsigned char>(text[offset + index]);
        const std::size_t width = character < 0x80 ? 1 : (character < 0xe0 ? 2 :
            (character < 0xf0 ? 3 : 4));
        units += width == 4 ? 2 : 1;
        index += std::min(width, length - index);
    }
    return units;
}

// The server writes each chunk with Blob.setBytes and SQLServerBlob copies the whole value on every
// call (O(n^2) overall), so larger chunks speed up the upload. A chunk must fit in
// ojp.server.maxRequestSize (default 4 MiB); the 64 KiB headroom covers the session and other fields.
constexpr std::size_t kDefaultLobChunkSize = 4 * 1024 * 1024 - 64 * 1024;
constexpr int kMinLobChunkSize = 64 * 1024;
constexpr int kMaxLobChunkSize = 256 * 1024 * 1024;

std::size_t lob_chunk_end(const std::string& data, std::size_t offset, LobType lob_type,
                          std::size_t chunk_size) {
    std::size_t end = std::min(data.size(), offset + chunk_size);
    if (lob_type == com::openjproxy::grpc::LT_CLOB && end < data.size()) {
        while (end > offset &&
               (static_cast<unsigned char>(data[end]) & 0xc0) == 0x80) {
            --end;
        }
        if (end == offset) {
            end = std::min(data.size(), offset + chunk_size);
        }
    }
    return end;
}

// Execution, fetch and LOB streams follow SQL_ATTR_QUERY_TIMEOUT (seconds). The default 0 means
// "no limit", as in ODBC and in the OJP JDBC client: a fixed deadline would cancel
// long queries and large LOBs (e.g. 100 MiB) in the middle of the transfer.
void apply_query_timeout(grpc::ClientContext* context, const HandleBase* handle) {
    const auto* statement = dynamic_cast<const StatementHandle*>(handle);
    if (statement == nullptr) {
        return;
    }
    const auto timeout = statement->stored_attributes.find(SQL_ATTR_QUERY_TIMEOUT);
    if (timeout != statement->stored_attributes.end() && timeout->second > 0) {
        context->set_deadline(std::chrono::system_clock::now() +
                              std::chrono::seconds(static_cast<std::int64_t>(timeout->second)));
    }
}

// RESOURCE_EXHAUSTED during upload usually means a chunk larger than ojp.server.maxRequestSize.
SQLRETURN fail_lob_upload(HandleBase* handle, const grpc::Status& status,
                          const grpc::ClientContext& context, std::size_t chunk_size) {
    if (status.error_code() == grpc::StatusCode::RESOURCE_EXHAUSTED) {
        return fail(handle, "OJP rejected a LOB block of " + std::to_string(chunk_size) +
                                " bytes (" + describe_grpc_status(status) +
                                "); set OJP.LOB.CHUNK.SIZE below the server's "
                                "ojp.server.maxRequestSize", "08S01");
    }
    return fail_grpc(handle, status, context);
}

SQLRETURN create_lob(ConnectionHandle* connection, HandleBase* handle, LobType lob_type,
                     const std::string& data, std::string* uuid) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    if (data.size() > static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max())) {
        return fail(handle, "LOB input exceeds the supported size", "22001");
    }
    const std::size_t chunk_size =
        connection->lob_chunk_size > 0 ? connection->lob_chunk_size : kDefaultLobChunkSize;
    StatementService::Stub* stub = route_session_rpc(connection);
    SessionInfo request_session = connection->session;
    grpc::ClientContext context;
    apply_query_timeout(&context, handle);
    auto writer = stub->createLob(&context);
    std::size_t offset = 0;
    std::size_t character_position = 1;
    bool first_block = true;
    bool received_reference = false;
    LobReference reference;
    while (first_block || offset < data.size()) {
        const std::size_t end = lob_chunk_end(data, offset, lob_type, chunk_size);
        LobDataBlock block;
        block.mutable_session()->CopyFrom(connection->session);
        block.set_position(lob_type == com::openjproxy::grpc::LT_CLOB
                               ? static_cast<std::int64_t>(character_position)
                               : static_cast<std::int64_t>(offset + 1));
        block.set_lobtype(lob_type);
        block.set_data(data.data() + offset, end - offset);
        if (!writer->Write(block)) {
            const auto status = writer->Finish();
            note_transport_failure(connection, status, context);
            return status.ok() ? fail(handle, "OJP closed the LOB upload stream", "08S01")
                               : fail_lob_upload(handle, status, context, chunk_size);
        }
        if (lob_type == com::openjproxy::grpc::LT_CLOB) {
            character_position += utf8_character_units(data, offset, end - offset);
        }
        offset = end;
        first_block = false;
        if (!received_reference) {
            if (!writer->Read(&reference)) {
                const auto status = writer->Finish();
                note_transport_failure(connection, status, context);
                return status.ok() ? fail(handle, "OJP did not return a LOB reference", "HY000")
                                   : fail_lob_upload(handle, status, context, chunk_size);
            }
            received_reference = true;
            update_session(connection, reference.session(), request_session);
        }
    }
    if (!writer->WritesDone()) {
        const auto status = writer->Finish();
        note_transport_failure(connection, status, context);
        return status.ok() ? fail(handle, "Unable to finish the LOB upload stream", "08S01")
                           : fail_lob_upload(handle, status, context, chunk_size);
    }
    while (writer->Read(&reference)) {
        update_session(connection, reference.session(), request_session);
    }
    const auto status = writer->Finish();
    if (!status.ok()) {
        note_transport_failure(connection, status, context);
        return fail_lob_upload(handle, status, context, chunk_size);
    }
    if (!received_reference || reference.uuid().empty()) {
        return fail(handle, "OJP returned an empty LOB reference", "HY000");
    }
    update_session(connection, reference.session(), request_session);
    *uuid = reference.uuid();
    return SQL_SUCCESS;
}

SQLRETURN read_lob(ConnectionHandle* connection, HandleBase* handle, const std::string& uuid,
                   LobType lob_type, std::string* data) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    StatementService::Stub* stub = route_session_rpc(connection);
    const SessionInfo request_session = connection->session;
    com::openjproxy::grpc::ReadLobRequest request;
    request.mutable_lobreference()->mutable_session()->CopyFrom(connection->session);
    request.mutable_lobreference()->set_uuid(uuid);
    request.mutable_lobreference()->set_lobtype(lob_type);
    request.set_position(1);
    request.set_length(std::numeric_limits<std::int32_t>::max());
    grpc::ClientContext context;
    apply_query_timeout(&context, handle);
    auto reader = stub->readLob(&context, request);
    LobDataBlock block;
    while (reader->Read(&block)) {
        if (block.has_session()) {
            update_session(connection, block.session(), request_session);
        }
        const auto max_lob_size =
            static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max());
        if (data->size() > max_lob_size || block.data().size() > max_lob_size - data->size()) {
            return fail(handle, "LOB result exceeds the supported ODBC buffer size", "22001");
        }
        data->append(block.data());
    }
    const auto status = reader->Finish();
    if (!status.ok()) {
        note_transport_failure(connection, status, context);
        return fail_grpc(handle, status, context);
    }
    return SQL_SUCCESS;
}

bool is_uuid(const std::string& value) {
    if (value.size() != 36) {
        return false;
    }
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            if (value[index] != '-') {
                return false;
            }
        } else if (!std::isxdigit(static_cast<unsigned char>(value[index]))) {
            return false;
        }
    }
    return true;
}

SQLRETURN materialize_lob_cell(StatementHandle* statement, const Cell& cell,
                               SQLSMALLINT target_type, Cell* output) {
    const auto* text = std::get_if<std::string>(&cell);
    if (text == nullptr) {
        *output = cell;
        return SQL_SUCCESS;
    }
    constexpr char kClobPrefix[] = "OJP_CLOB_PREFIX:";
    const std::string prefix(kClobPrefix);
    const bool is_clob = text->compare(0, prefix.size(), prefix) == 0;
    const bool is_blob = target_type == SQL_C_BINARY && is_uuid(*text);
    if (!is_clob && !is_blob) {
        *output = cell;
        return SQL_SUCCESS;
    }
    const std::string uuid = is_clob ? text->substr(prefix.size()) : *text;
    std::string bytes;
    const auto result = read_lob(statement->connection, statement, uuid,
        is_clob ? com::openjproxy::grpc::LT_CLOB : com::openjproxy::grpc::LT_BLOB, &bytes);
    if (!SQL_SUCCEEDED(result)) {
        return result;
    }
    if (is_clob && target_type != SQL_C_BINARY) {
        *output = std::move(bytes);
    } else {
        *output = std::vector<std::uint8_t>(bytes.begin(), bytes.end());
    }
    return SQL_SUCCESS;
}

// All rows are read eagerly, so the server-side result set is closed straight away
// (CLIENT_SPEC_AI.md section 4.5 rule 2).
SQLRETURN close_result_set(ConnectionHandle* connection, const std::string& result_set_uuid,
                           StatementHandle* statement) {
    com::openjproxy::grpc::CallResourceRequest request;
    request.set_resourcetype(com::openjproxy::grpc::RES_RESULT_SET);
    request.set_resourceuuid(result_set_uuid);
    request.mutable_target()->set_calltype(com::openjproxy::grpc::CALL_CLOSE);
    const SessionInfo initial_request_session = connection->session;
    std::set<std::string> attempted_endpoints;
    bool not_found_retried = false;
    for (int attempt = 0; attempt <= connection->retry_attempts; ++attempt) {
        StatementService::Stub* stub = route_session_rpc(connection);
        const SessionInfo request_session = connection->session;
        request.mutable_session()->CopyFrom(request_session);
        grpc::ClientContext context;
        context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
        com::openjproxy::grpc::CallResourceResponse response;
        const auto status = stub->callResource(&context, request, &response);
        if (!status.ok()) {
            if (attempt == connection->retry_attempts) {
                note_transport_failure(connection, status, context);
                return fail_grpc(statement, status, context);
            }
            if (!prepare_stateless_retry(connection, status, context, initial_request_session,
                                         &attempted_endpoints, &not_found_retried)) {
                return fail_grpc(statement, status, context);
            }
            continue;
        }
        if (response.has_session()) {
            update_session(connection, response.session(), request_session);
        }
        return SQL_SUCCESS;
    }
    return fail(statement, "OJP resource close retries were exhausted", "08S01");
}

SQLRETURN fetch_update_result_set(ConnectionHandle* connection, StatementHandle* statement,
                                  const std::string& result_set_uuid) {
    bool decoded_all = true;
    for (;;) {
        StatementService::Stub* stub = route_session_rpc(connection);
        com::openjproxy::grpc::ResultSetFetchRequest fetch;
        fetch.mutable_session()->CopyFrom(connection->session);
        fetch.set_resultsetuuid(result_set_uuid);
        fetch.set_size(1);
        grpc::ClientContext context;
        context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
        OpResult result;
        const auto status = stub->fetchNextRows(&context, fetch, &result);
        if (!status.ok()) {
            note_transport_failure(connection, status, context);
            close_result_set(connection, result_set_uuid, statement);
            return fail_grpc(statement, status, context);
        }
        if (result.has_session()) {
            update_session(connection, result.session(), fetch.session());
        }
        if (!result.has_query_result()) {
            close_result_set(connection, result_set_uuid, statement);
            return fail(statement, "OJP returned an invalid update result set", "HY000");
        }
        const auto& query = result.query_result();
        if (statement->columns.empty()) {
            statement->columns.assign(query.labels().begin(), query.labels().end());
        }
        for (const auto& row : query.rows()) {
            std::vector<Cell> decoded;
            decoded.reserve(static_cast<std::size_t>(row.columns_size()));
            for (const auto& column : row.columns()) {
                Cell cell;
                if (!decode_value(column, &cell)) {
                    decoded_all = false;
                }
                decoded.push_back(std::move(cell));
            }
            statement->rows.push_back(std::move(decoded));
        }
        if (query.rows_size() == 0 ||
            result.flag().find(kResultSetLastBlockFlag) != std::string::npos) {
            break;
        }
    }

    const auto close_result = close_result_set(connection, result_set_uuid, statement);
    if (!SQL_SUCCEEDED(close_result)) {
        return close_result;
    }
    if (!decoded_all) {
        return fail(statement, "OJP returned a result type unsupported by the ODBC client", "HY000");
    }
    statement->has_result_set = true;
    statement->row_count = static_cast<SQLLEN>(statement->rows.size());
    return SQL_SUCCESS;
}

template <typename Invoke>
SQLRETURN invoke_session_rpc(ConnectionHandle* connection, HandleBase* handle, Invoke invoke,
                             bool retry_when_stateless = false) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    const SessionInfo initial_request_session = connection->session;
    std::set<std::string> attempted_endpoints;
    bool not_found_retried = false;
    const int retry_limit = retry_when_stateless ? connection->retry_attempts : 0;
    for (int attempt = 0; attempt <= retry_limit; ++attempt) {
        StatementService::Stub* stub = route_session_rpc(connection);
        const SessionInfo request_session = connection->session;
        grpc::ClientContext context;
        context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
        SessionInfo response;
        const auto status = invoke(stub, &context, request_session, &response);
        if (!status.ok()) {
            if (attempt == retry_limit) {
                note_transport_failure(connection, status, context);
                return fail_grpc(handle, status, context);
            }
            if (!prepare_stateless_retry(connection, status, context, initial_request_session,
                                         &attempted_endpoints, &not_found_retried)) {
                return fail_grpc(handle, status, context);
            }
            continue;
        }
        update_session(connection, response, request_session);
        return SQL_SUCCESS;
    }
    return fail(handle, "OJP RPC retries were exhausted", "08S01");
}

SQLRETURN call_resource(ConnectionHandle* connection, HandleBase* handle,
                        com::openjproxy::grpc::ResourceType resource_type,
                        const std::string& resource_uuid,
                        com::openjproxy::grpc::CallType call_type,
                        const std::string& resource_name,
                        const std::vector<ParameterValue>& parameters,
                        com::openjproxy::grpc::CallResourceResponse* output,
                        const com::openjproxy::grpc::TargetCall* next_call = nullptr) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    const SessionInfo initial_request_session = connection->session;
    com::openjproxy::grpc::CallResourceRequest request;
    request.set_resourcetype(resource_type);
    request.set_resourceuuid(resource_uuid);
    auto* target = request.mutable_target();
    target->set_calltype(call_type);
    target->set_resourcename(resource_name);
    for (const auto& parameter : parameters) {
        target->add_params()->CopyFrom(parameter);
    }
    if (next_call != nullptr) {
        target->mutable_nextcall()->CopyFrom(*next_call);
    }
    std::set<std::string> attempted_endpoints;
    bool not_found_retried = false;
    for (int attempt = 0; attempt <= connection->retry_attempts; ++attempt) {
        StatementService::Stub* stub = route_session_rpc(connection);
        const SessionInfo request_session = connection->session;
        request.mutable_session()->CopyFrom(request_session);
        grpc::ClientContext context;
        context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
        com::openjproxy::grpc::CallResourceResponse response;
        const auto status = stub->callResource(&context, request, &response);
        if (!status.ok()) {
            if (attempt == connection->retry_attempts) {
                note_transport_failure(connection, status, context);
                return fail_grpc(handle, status, context);
            }
            if (!prepare_stateless_retry(connection, status, context, initial_request_session,
                                         &attempted_endpoints, &not_found_retried)) {
                return fail_grpc(handle, status, context);
            }
            continue;
        }
        if (response.has_session()) {
            update_session(connection, response.session(), request_session);
        }
        if (output != nullptr) {
            output->CopyFrom(response);
        }
        return SQL_SUCCESS;
    }
    return fail(handle, "OJP resource call retries were exhausted", "08S01");
}

SQLRETURN start_transaction(ConnectionHandle* connection, HandleBase* handle) {
    return invoke_session_rpc(connection, handle,
        [](StatementService::Stub* stub, grpc::ClientContext* context, const SessionInfo& request,
                     SessionInfo* response) {
            return stub->startTransaction(context, request, response);
        }, true);
}

SQLRETURN end_transaction(ConnectionHandle* connection, HandleBase* handle,
                          SQLSMALLINT completion_type) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    if (connection->xa_mode) {
        return fail(handle, "Use the OJP XA API to complete XA transactions", "HYC00");
    }
    if (connection->auto_commit) {
        return SQL_SUCCESS;
    }
    const auto result = completion_type == SQL_COMMIT
        ? invoke_session_rpc(connection, handle,
            [](StatementService::Stub* stub, grpc::ClientContext* context, const SessionInfo& request,
                         SessionInfo* response) {
                return stub->commitTransaction(context, request, response);
            })
        : invoke_session_rpc(connection, handle,
            [](StatementService::Stub* stub, grpc::ClientContext* context, const SessionInfo& request,
                         SessionInfo* response) {
                return stub->rollbackTransaction(context, request, response);
            });
    if (SQL_SUCCEEDED(result)) {
        connection->savepoints.clear();
        connection->savepoint_names.clear();
    }
    return result;
}

bool jdbc_transaction_isolation(SQLULEN isolation, std::int32_t* jdbc_isolation) {
    switch (isolation) {
        case SQL_TXN_READ_UNCOMMITTED:
            *jdbc_isolation = 1;
            return true;
        case SQL_TXN_READ_COMMITTED:
            *jdbc_isolation = 2;
            return true;
        case SQL_TXN_REPEATABLE_READ:
            *jdbc_isolation = 4;
            return true;
        case SQL_TXN_SERIALIZABLE:
            *jdbc_isolation = 8;
            return true;
        default:
            return false;
    }
}

SQLRETURN set_transaction_isolation(ConnectionHandle* connection, HandleBase* handle,
                                    SQLULEN isolation) {
    std::int32_t jdbc_isolation = 0;
    if (!jdbc_transaction_isolation(isolation, &jdbc_isolation)) {
        return fail(handle, "Unsupported transaction isolation level", "HY024");
    }
    ParameterValue parameter;
    parameter.set_int_value(jdbc_isolation);
    com::openjproxy::grpc::CallResourceResponse response;
    const auto result = call_resource(connection, handle, com::openjproxy::grpc::RES_CONNECTION,
        "", com::openjproxy::grpc::CALL_SET, "TransactionIsolation", {parameter}, &response);
    if (SQL_SUCCEEDED(result)) {
        connection->transaction_isolation = isolation;
    }
    return result;
}

SQLRETURN get_transaction_isolation(ConnectionHandle* connection, HandleBase* handle,
                                    SQLULEN* isolation) {
    com::openjproxy::grpc::CallResourceResponse response;
    const auto result = call_resource(connection, handle, com::openjproxy::grpc::RES_CONNECTION,
        "", com::openjproxy::grpc::CALL_GET, "TransactionIsolation", {}, &response);
    if (!SQL_SUCCEEDED(result)) {
        return result;
    }
    if (response.values_size() == 0 ||
        response.values(0).value_case() != ParameterValue::kIntValue) {
        return fail(handle, "OJP returned an invalid transaction isolation level", "HY000");
    }
    const auto jdbc_isolation = response.values(0).int_value();
    switch (jdbc_isolation) {
        case 1: *isolation = SQL_TXN_READ_UNCOMMITTED; break;
        case 2: *isolation = SQL_TXN_READ_COMMITTED; break;
        case 4: *isolation = SQL_TXN_REPEATABLE_READ; break;
        case 8: *isolation = SQL_TXN_SERIALIZABLE; break;
        default:
            return fail(handle, "OJP returned an unsupported transaction isolation level", "HY000");
    }
    connection->transaction_isolation = *isolation;
    return SQL_SUCCESS;
}

SQLRETURN set_auto_commit(ConnectionHandle* connection, HandleBase* handle, bool enabled) {
    if (connection->xa_mode) {
        if (enabled) {
            return fail(handle, "Autocommit cannot be enabled for an OJP XA connection", "HY024");
        }
        connection->auto_commit = false;
        return SQL_SUCCESS;
    }
    if (connection->auto_commit == enabled) {
        return SQL_SUCCESS;
    }
    if (!connection->connected) {
        connection->auto_commit = enabled;
        return SQL_SUCCESS;
    }
    SQLRETURN result = SQL_SUCCESS;
    if (enabled) {
        ParameterValue parameter;
        parameter.set_bool_value(true);
        result = call_resource(connection, handle, com::openjproxy::grpc::RES_CONNECTION,
            "", com::openjproxy::grpc::CALL_SET, "AutoCommit", {parameter}, nullptr);
    } else {
        result = start_transaction(connection, handle);
    }
    if (SQL_SUCCEEDED(result)) {
        connection->auto_commit = enabled;
        if (enabled) {
            connection->savepoints.clear();
            connection->savepoint_names.clear();
        }
    }
    return result;
}

enum class SavepointAction {
    NONE,
    SET,
    ROLLBACK,
    RELEASE,
    INVALID
};

std::string uppercase_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char character) {
        return static_cast<char>(std::toupper(character));
    });
    return value;
}

SavepointAction parse_savepoint_statement(const std::string& sql, std::string* name) {
    std::string normalized = sql;
    const auto first = normalized.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) {
        return SavepointAction::NONE;
    }
    normalized.erase(0, first);
    while (!normalized.empty() &&
           std::isspace(static_cast<unsigned char>(normalized.back()))) {
        normalized.pop_back();
    }
    if (!normalized.empty() && normalized.back() == ';') {
        normalized.pop_back();
        while (!normalized.empty() &&
               std::isspace(static_cast<unsigned char>(normalized.back()))) {
            normalized.pop_back();
        }
    }

    std::istringstream tokens_stream(normalized);
    std::vector<std::string> tokens;
    std::string token;
    while (tokens_stream >> token) {
        tokens.push_back(token);
    }
    if (tokens.empty()) {
        return SavepointAction::NONE;
    }

    const std::string command = uppercase_ascii(tokens[0]);
    SavepointAction action = SavepointAction::NONE;
    std::size_t name_index = 0;
    if (command == "SAVEPOINT") {
        action = SavepointAction::SET;
        name_index = 1;
    } else if (command == "SAVE" && tokens.size() > 1 &&
               uppercase_ascii(tokens[1]) == "TRANSACTION") {
        action = SavepointAction::SET;
        name_index = 2;
    } else if (command == "ROLLBACK" && tokens.size() > 1 &&
               uppercase_ascii(tokens[1]) == "TO") {
        action = SavepointAction::ROLLBACK;
        name_index = tokens.size() > 2 && uppercase_ascii(tokens[2]) == "SAVEPOINT" ? 3 : 2;
    } else if (command == "ROLLBACK" && tokens.size() > 1 &&
               uppercase_ascii(tokens[1]) == "TRANSACTION") {
        action = SavepointAction::ROLLBACK;
        name_index = 2;
    } else if (command == "RELEASE") {
        action = SavepointAction::RELEASE;
        name_index = tokens.size() > 1 && uppercase_ascii(tokens[1]) == "SAVEPOINT" ? 2 : 1;
    } else {
        return SavepointAction::NONE;
    }
    if (name_index + 1 != tokens.size()) {
        return SavepointAction::INVALID;
    }

    const std::string& identifier = tokens[name_index];
    if (identifier.empty() ||
        !(std::isalpha(static_cast<unsigned char>(identifier[0])) || identifier[0] == '_') ||
        !std::all_of(identifier.begin() + 1, identifier.end(), [](unsigned char character) {
            return std::isalnum(character) || character == '_' || character == '$';
        })) {
        return SavepointAction::INVALID;
    }
    *name = uppercase_ascii(identifier);
    return action;
}

SQLRETURN execute_savepoint_statement(StatementHandle* statement, SavepointAction action,
                                      const std::string& name) {
    auto* connection = statement->connection;
    if (action == SavepointAction::INVALID) {
        return fail(statement, "Invalid OJP savepoint statement", "42000");
    }
    if (connection->auto_commit) {
        return fail(statement, "Savepoints require autocommit to be disabled", "25000");
    }

    if (action == SavepointAction::SET) {
        std::vector<ParameterValue> parameters(1);
        parameters[0].set_string_value(name);
        com::openjproxy::grpc::CallResourceResponse response;
        const auto result = call_resource(connection, statement,
            com::openjproxy::grpc::RES_CONNECTION, "", com::openjproxy::grpc::CALL_SET,
            "Savepoint", parameters, &response);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        if (response.values_size() == 0 ||
            response.values(0).value_case() != ParameterValue::kStringValue ||
            response.values(0).string_value().empty()) {
            return fail(statement, "OJP did not return a savepoint handle", "HY000");
        }
        const auto& savepoint_uuid = response.values(0).string_value();
        const auto previous = connection->savepoint_names.find(name);
        if (previous != connection->savepoint_names.end()) {
            connection->savepoints.erase(previous->second);
        }
        connection->savepoints.insert(savepoint_uuid);
        connection->savepoint_names[name] = savepoint_uuid;
        statement->row_count = 0;
        return SQL_SUCCESS;
    }

    const auto found = connection->savepoint_names.find(name);
    if (found == connection->savepoint_names.end() ||
        connection->savepoints.count(found->second) == 0) {
        return fail(statement, "Savepoint does not exist or is no longer valid", "3B001");
    }
    ParameterValue parameter;
    parameter.set_string_value(found->second);
    const auto call_type = action == SavepointAction::ROLLBACK
        ? com::openjproxy::grpc::CALL_ROLLBACK : com::openjproxy::grpc::CALL_RELEASE;
    const std::string resource_name = action == SavepointAction::RELEASE ? "Savepoint" : "";
    const auto result = call_resource(connection, statement,
        com::openjproxy::grpc::RES_CONNECTION, "", call_type, resource_name, {parameter}, nullptr);
    if (SQL_SUCCEEDED(result) && action == SavepointAction::RELEASE) {
        connection->savepoints.erase(found->second);
        connection->savepoint_names.erase(found);
    }
    if (SQL_SUCCEEDED(result)) {
        statement->row_count = 0;
    }
    return result;
}

void reset_data_at_execution(StatementHandle* statement) {
    statement->data_at_execution_parameters.clear();
    statement->data_at_execution_index = 0;
    statement->current_data_at_execution_parameter = 0;
    statement->waiting_for_data = false;
    for (auto& entry : statement->parameters) {
        entry.second.streamed_data.clear();
    }
}

// Reads getColumnType/getPrecision/getScale/isNullable for each column while the ResultSet is still
// open on the server. Failure is not fatal: the driver falls back to inferring the type from the values.
void load_column_metadata(ConnectionHandle* connection, StatementHandle* statement,
                          const std::string& result_set_uuid) {
    using com::openjproxy::grpc::CallResourceRequest;
    using com::openjproxy::grpc::CallResourceResponse;
    using com::openjproxy::grpc::CallType;
    const auto started = std::chrono::steady_clock::now();
    struct Attribute {
        CallType call_type;
        const char* name;
        int ColumnMetadata::*field;
    };
    static const Attribute kAttributes[] = {
        {com::openjproxy::grpc::CALL_GET, "ColumnType", &ColumnMetadata::jdbc_type},
        {com::openjproxy::grpc::CALL_GET, "Precision", &ColumnMetadata::precision},
        {com::openjproxy::grpc::CALL_GET, "Scale", &ColumnMetadata::scale},
        {com::openjproxy::grpc::CALL_IS, "Nullable", &ColumnMetadata::nullable},
    };
    constexpr std::size_t kAttributeCount = sizeof(kAttributes) / sizeof(kAttributes[0]);
    // Each attribute is a callResource call; they are issued in parallel (async gRPC)
    // in batches, so the cost does not grow linearly with the number of columns.
    constexpr std::size_t kMaxInFlight = 64;
    struct Pending {
        grpc::ClientContext context;
        CallResourceResponse response;
        grpc::Status status;
        std::unique_ptr<grpc::ClientAsyncResponseReader<CallResourceResponse>> reader;
    };
    std::vector<ColumnMetadata> metadata(statement->columns.size());
    const std::size_t total = metadata.size() * kAttributeCount;
    std::string failure;
    for (std::size_t batch_start = 0; batch_start < total && failure.empty(); batch_start += kMaxInFlight) {
        const std::size_t batch_end = std::min(total, batch_start + kMaxInFlight);
        StatementService::Stub* stub = route_session_rpc(connection);
        grpc::CompletionQueue queue;
        std::vector<std::unique_ptr<Pending>> pending;
        pending.reserve(batch_end - batch_start);
        for (std::size_t call = batch_start; call < batch_end; ++call) {
            const Attribute& attribute = kAttributes[call % kAttributeCount];
            CallResourceRequest request;
            request.mutable_session()->CopyFrom(connection->session);
            request.set_resourcetype(com::openjproxy::grpc::RES_RESULT_SET);
            request.set_resourceuuid(result_set_uuid);
            auto* target = request.mutable_target();
            target->set_calltype(com::openjproxy::grpc::CALL_GET);
            target->set_resourcename("MetaData");
            auto* next = target->mutable_nextcall();
            next->set_calltype(attribute.call_type);
            next->set_resourcename(attribute.name);
            next->add_params()->set_int_value(static_cast<int>(call / kAttributeCount + 1));
            auto item = std::make_unique<Pending>();
            item->context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
            item->reader = stub->AsynccallResource(&item->context, request, &queue);
            item->reader->Finish(&item->response, &item->status, item.get());
            pending.push_back(std::move(item));
        }
        for (std::size_t received = 0; received < pending.size(); ++received) {
            void* tag = nullptr;
            bool ok = false;
            if (!queue.Next(&tag, &ok)) {
                failure = "gRPC completion queue was shut down";
                break;
            }
            auto* item = static_cast<Pending*>(tag);
            const std::size_t call = batch_start + static_cast<std::size_t>(
                std::find_if(pending.begin(), pending.end(),
                             [item](const auto& entry) { return entry.get() == item; }) - pending.begin());
            const Attribute& attribute = kAttributes[call % kAttributeCount];
            const std::size_t column = call / kAttributeCount;
            if (!ok || !item->status.ok()) {
                if (failure.empty()) {
                    failure = std::string(attribute.name) + " of column " + std::to_string(column + 1) +
                              " (" + statement->columns[column] + "): " +
                              (ok ? item->status.error_message() : "RPC was not completed");
                }
                continue;
            }
            if (item->response.values_size() == 0) {
                if (failure.empty()) {
                    failure = std::string(attribute.name) + " of column " + std::to_string(column + 1) +
                              " returned no value";
                }
                continue;
            }
            const auto& value = item->response.values(0);
            if (value.value_case() == ParameterValue::kIntValue) {
                metadata[column].*attribute.field = value.int_value();
            } else if (value.value_case() == ParameterValue::kLongValue) {
                metadata[column].*attribute.field = static_cast<int>(std::clamp<std::int64_t>(
                    value.long_value(), 0, std::numeric_limits<int>::max()));
            } else if (failure.empty()) {
                failure = std::string(attribute.name) + " of column " + std::to_string(column + 1) +
                          " returned an unexpected value type " + std::to_string(value.value_case());
            }
        }
        queue.Shutdown();
        void* tag = nullptr;
        bool ok = false;
        while (queue.Next(&tag, &ok)) {
        }
    }
    if (!failure.empty()) {
        log_message(LogLevel::warn, "result-set metadata unavailable (" + failure +
                    "); column types will be inferred from the data");
        return;
    }
    statement->column_meta = std::move(metadata);
    if (log_enabled(LogLevel::debug)) {
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count();
        std::ostringstream text;
        text << "result-set metadata loaded in " << elapsed << " ms (" << total << " calls):";
        for (std::size_t index = 0; index < statement->column_meta.size(); ++index) {
            const auto& item = statement->column_meta[index];
            text << " [" << statement->columns[index] << " jdbcType=" << item.jdbc_type
                 << " precision=" << item.precision << " scale=" << item.scale
                 << " nullable=" << item.nullable << "]";
        }
        log_message(LogLevel::debug, text.str());
    }
}

SQLRETURN execute_statement(StatementHandle* statement) {
    auto* connection = statement->connection;
    clear_diagnostics(statement);
    statement->columns.clear();
    statement->column_meta.clear();
    statement->rows.clear();
    statement->get_data_offsets.clear();
    statement->row_index = 0;
    statement->row_count = -1;
    statement->has_result_set = false;
    if (connection == nullptr) {
        return fail(statement, "ODBC connection is not open", "08003");
    }
    std::lock_guard<std::mutex> connection_lock(connection->operation_mutex);
    if (!connection->connected || !connection->stub) {
        return fail(statement, "ODBC connection is not open", "08003");
    }

    std::string savepoint_name;
    const SavepointAction savepoint_action =
        parse_savepoint_statement(statement->sql, &savepoint_name);
    if (savepoint_action != SavepointAction::NONE) {
        return execute_savepoint_statement(statement, savepoint_action, savepoint_name);
    }

    StatementRequest request;
    request.set_sql(statement->sql);
    const bool postgres = connection->url.find("jdbc:postgresql:") != std::string::npos;
    // Bindings persist across statements; only those with a matching '?' marker are sent.
    const auto marker_count = count_parameter_markers(statement->sql);
    log_message(LogLevel::debug, "executing SQL with " + std::to_string(marker_count) +
                " parameter marker(s) and " + std::to_string(statement->parameters.size()) +
                " bound parameter(s)");
    for (const auto& entry : statement->parameters) {
        if (entry.first > marker_count) {
            continue;
        }
        const auto& bound = entry.second;
        if (bound.direction != SQL_PARAM_INPUT) {
            return fail(statement, "Only input parameters are supported", "HYC00");
        }
        auto* parameter = request.add_parameters();
        parameter->set_index(static_cast<std::int32_t>(entry.first));
        auto* value = parameter->add_values();
        const bool is_null = bound.value == nullptr ||
            (bound.indicator != nullptr && *bound.indicator == SQL_NULL_DATA);
        if (is_lob_parameter_type(bound.parameter_type) && !is_null) {
            std::string lob_data;
            Diagnostic parameter_error;
            if (!get_lob_parameter_data(bound, &lob_data, &parameter_error)) {
                return fail(statement, parameter_error.message, parameter_error.state);
            }
            if (postgres) {
                // Match Java JDBC: PostgreSQL BYTEA/TEXT uses hydrated values, not LOB handles.
                if (bound.parameter_type == SQL_LONGVARBINARY) {
                    parameter->set_type(com::openjproxy::grpc::PT_BINARY_STREAM);
                    value->set_bytes_value(lob_data);
                    parameter->add_values()->set_long_value(static_cast<std::int64_t>(lob_data.size()));
                } else {
                    parameter->set_type(com::openjproxy::grpc::PT_STRING);
                    value->set_string_value(lob_data);
                }
                continue;
            }
            if (bound.parameter_type != SQL_LONGVARBINARY &&
                lob_data.size() <= kInlineTextLobLimit) {
                // Long text goes inline (setString/setNString). OJP server 1.0.x does not handle
                // PT_N_CLOB and would call setObject(uuid), storing the LOB UUID instead of the text.
                parameter->set_type(bound.parameter_type == SQL_WLONGVARCHAR
                    ? com::openjproxy::grpc::PT_N_STRING : com::openjproxy::grpc::PT_STRING);
                value->set_string_value(lob_data);
                continue;
            }
            const LobType lob_type = bound.parameter_type == SQL_LONGVARBINARY
                ? com::openjproxy::grpc::LT_BLOB : com::openjproxy::grpc::LT_CLOB;
            std::string lob_uuid;
            const auto lob_result = create_lob(connection, statement, lob_type, lob_data, &lob_uuid);
            if (!SQL_SUCCEEDED(lob_result)) {
                return lob_result;
            }
            // PT_CLOB (setClob) also for Unicode text: it is the only text LOB type
            // supported by every server version.
            parameter->set_type(bound.parameter_type == SQL_LONGVARBINARY
                ? com::openjproxy::grpc::PT_BLOB : com::openjproxy::grpc::PT_CLOB);
            log_message(LogLevel::debug, "parameter " + std::to_string(entry.first) + " sent as LOB " +
                        lob_uuid + " (" + std::to_string(lob_data.size()) + " bytes)");
            value->set_string_value(lob_uuid);
            continue;
        }
        if (is_data_at_execution(bound)) {
            return fail(statement, "Data-at-execution is only supported for LOB parameters", "HYC00");
        }
        parameter->set_type(parameter_type(bound));
        Diagnostic parameter_error;
        if (!set_parameter_value(value, bound, entry.first, &parameter_error) ||
            (parameter->type() != com::openjproxy::grpc::PT_NULL &&
             !coerce_parameter_value(value, parameter->type(), entry.first, &parameter_error))) {
            log_message(LogLevel::warn, "parameter " + std::to_string(entry.first) +
                        " rejected (C type " + std::to_string(bound.value_type) + ", SQL type " +
                        std::to_string(bound.parameter_type) + "): " + parameter_error.message);
            return fail(statement, parameter_error.message, parameter_error.state,
                        parameter_error.native_error);
        }
        if (postgres && is_null && bound.parameter_type == SQL_LONGVARBINARY) {
            value->set_int_value(SQL_VARBINARY);
        }
    }
    request.mutable_session()->CopyFrom(connection->session);
    const bool dml_result_set = is_dml_result_set_sql(statement->sql);
    if (dml_result_set) {
        auto* property = request.add_properties();
        property->set_key(kUpdateResultSetRequestKey);
        property->set_bool_value(true);
    }

    StatementService::Stub* stub = route_session_rpc(connection);
    SessionInfo request_session = connection->session;
    const SessionInfo initial_request_session = request_session;
    std::set<std::string> attempted_endpoints;
    bool not_found_retried = false;
    request.mutable_session()->CopyFrom(request_session);

    if (is_query_sql(statement->sql) && !dml_result_set) {
        std::string result_set_uuid;
        bool row_by_row = false;
        bool decoded_all = true;
        auto append_result = [&](const OpResult& result) {
            if (result.has_session()) {
                update_session(connection, result.session(), request_session);
            }
            if (result.flag().find(kRowByRowMode) != std::string::npos) {
                row_by_row = true;
            }
            if (!result.has_query_result()) {
                return std::size_t{0};
            }
            const OpQueryResultProto& query = result.query_result();
            if (result_set_uuid.empty()) {
                result_set_uuid = query.resultsetuuid();
            }
            if (statement->columns.empty()) {
                statement->columns.assign(query.labels().begin(), query.labels().end());
            }
            for (const auto& row : query.rows()) {
                std::vector<Cell> decoded;
                decoded.reserve(static_cast<std::size_t>(row.columns_size()));
                for (const auto& column : row.columns()) {
                    Cell cell;
                    if (!decode_value(column, &cell)) {
                        decoded_all = false;
                    }
                    decoded.push_back(std::move(cell));
                }
                statement->rows.push_back(std::move(decoded));
            }
            return static_cast<std::size_t>(query.rows_size());
        };

        for (int attempt = 0; attempt <= connection->retry_attempts; ++attempt) {
            stub = route_session_rpc(connection);
            grpc::ClientContext context;
            apply_query_timeout(&context, statement);
            auto reader = stub->executeQuery(&context, request);
            OpResult result;
            while (reader->Read(&result)) {
                append_result(result);
            }
            const auto status = reader->Finish();
            if (status.ok()) {
                break;
            }
            if (attempt == connection->retry_attempts ||
                !prepare_stateless_retry(connection, status, context, initial_request_session,
                                         &attempted_endpoints, &not_found_retried)) {
                if (attempt == connection->retry_attempts) {
                    note_transport_failure(connection, status, context);
                }
                return fail_grpc(statement, status, context);
            }
            request_session.CopyFrom(connection->session);
            request.mutable_session()->CopyFrom(request_session);
            statement->rows.clear();
            statement->columns.clear();
            statement->column_meta.clear();
            result_set_uuid.clear();
            row_by_row = false;
            decoded_all = true;
        }
        // SQL Server and DB2 send one row at a time when the result has binary or LOB
        // columns; the remaining rows must be pulled with fetchNextRows.
        while (row_by_row && !result_set_uuid.empty()) {
            StatementService::Stub* fetch_stub = route_session_rpc(connection);
            com::openjproxy::grpc::ResultSetFetchRequest fetch;
            fetch.mutable_session()->CopyFrom(connection->session);
            fetch.set_resultsetuuid(result_set_uuid);
            fetch.set_size(1);
            grpc::ClientContext fetch_context;
            apply_query_timeout(&fetch_context, statement);
            OpResult next;
            const auto fetch_status = fetch_stub->fetchNextRows(&fetch_context, fetch, &next);
            if (!fetch_status.ok()) {
                note_transport_failure(connection, fetch_status, fetch_context);
                return fail_grpc(statement, fetch_status, fetch_context);
            }
            if (next.has_session()) {
                update_session(connection, next.session(), fetch.session());
            }
            if (append_result(next) == 0) {
                break;
            }
        }
        if (!result_set_uuid.empty()) {
            load_column_metadata(connection, statement, result_set_uuid);
            const auto close_result = close_result_set(connection, result_set_uuid, statement);
            if (!SQL_SUCCEEDED(close_result)) {
                return close_result;
            }
        }
        if (!decoded_all) {
            return fail(statement, "OJP returned a result type unsupported by the ODBC client", "HY000");
        }
        statement->has_result_set = true;
        statement->row_count = static_cast<SQLLEN>(statement->rows.size());
        return SQL_SUCCESS;
    }

    grpc::Status status;
    OpResult result;
    for (int attempt = 0; attempt <= connection->retry_attempts; ++attempt) {
        stub = route_session_rpc(connection);
        result.Clear();
        grpc::ClientContext context;
        apply_query_timeout(&context, statement);
        status = stub->executeUpdate(&context, request, &result);
        if (status.ok()) {
            break;
        }
        if (attempt == connection->retry_attempts ||
            !prepare_stateless_retry(connection, status, context, initial_request_session,
                                     &attempted_endpoints, &not_found_retried)) {
            if (attempt == connection->retry_attempts) {
                note_transport_failure(connection, status, context);
            }
            return fail_grpc(statement, status, context);
        }
        request_session.CopyFrom(connection->session);
        request.mutable_session()->CopyFrom(request_session);
    }
    if (result.has_session()) {
        update_session(connection, result.session(), request_session);
    }
    if (result.flag() == kUpdateResultSetFlag) {
        if (result.uuid().empty()) {
            return fail(statement, "OJP returned an empty update result-set reference", "HY000");
        }
        return fetch_update_result_set(connection, statement, result.uuid());
    }
    if (result.type() != com::openjproxy::grpc::INTEGER || !result.has_int_value()) {
        return fail(statement, "OJP returned an invalid update result", "HY000");
    }
    statement->row_count = result.int_value();
    return SQL_SUCCESS;
}

SQLRETURN execute_metadata_statement(StatementHandle* statement, const std::string& method,
                                    const std::vector<ParameterValue>& parameters) {
    clear_diagnostics(statement);
    statement->columns.clear();
    statement->column_meta.clear();
    statement->rows.clear();
    statement->get_data_offsets.clear();
    statement->row_index = 0;
    statement->row_count = -1;
    statement->has_result_set = false;
    auto* connection = statement->connection;

    com::openjproxy::grpc::TargetCall metadata_call;
    metadata_call.set_calltype(com::openjproxy::grpc::CALL_GET);
    metadata_call.set_resourcename(method);
    for (const auto& parameter : parameters) {
        metadata_call.add_params()->CopyFrom(parameter);
    }
    com::openjproxy::grpc::CallResourceResponse metadata_response;
    SQLRETURN result = call_resource(connection, statement,
        com::openjproxy::grpc::RES_CONNECTION, "", com::openjproxy::grpc::CALL_GET,
        "MetaData", {}, &metadata_response, &metadata_call);
    if (!SQL_SUCCEEDED(result)) {
        return result;
    }
    if (metadata_response.values_size() == 0 ||
        metadata_response.values(0).value_case() != ParameterValue::kStringValue ||
        metadata_response.values(0).string_value().empty()) {
        return fail(statement, "JDBC metadata call did not return a result set", "HY000");
    }
    const std::string result_set_uuid = metadata_response.values(0).string_value();
    bool decoded_all = true;
    auto append_result = [&](const OpResult& fetched) {
        if (fetched.has_session()) {
            update_session(connection, fetched.session(), connection->session);
        }
        if (!fetched.has_query_result()) {
            return std::size_t{0};
        }
        const auto& query = fetched.query_result();
        if (statement->columns.empty()) {
            statement->columns.assign(query.labels().begin(), query.labels().end());
        }
        for (const auto& row : query.rows()) {
            std::vector<Cell> decoded;
            decoded.reserve(static_cast<std::size_t>(row.columns_size()));
            for (const auto& column : row.columns()) {
                Cell cell;
                if (!decode_value(column, &cell)) {
                    decoded_all = false;
                }
                decoded.push_back(std::move(cell));
            }
            statement->rows.push_back(std::move(decoded));
        }
        return static_cast<std::size_t>(query.rows_size());
    };
    for (;;) {
        StatementService::Stub* stub = route_session_rpc(connection);
        com::openjproxy::grpc::ResultSetFetchRequest fetch;
        fetch.mutable_session()->CopyFrom(connection->session);
        fetch.set_resultsetuuid(result_set_uuid);
        fetch.set_size(1);
        grpc::ClientContext context;
        context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
        OpResult fetched;
        const auto status = stub->fetchNextRows(&context, fetch, &fetched);
        if (!status.ok()) {
            note_transport_failure(connection, status, context);
            close_result_set(connection, result_set_uuid, statement);
            return fail_grpc(statement, status, context);
        }
        const auto rows_fetched = append_result(fetched);
        if (rows_fetched == 0) {
            break;
        }
    }
    result = close_result_set(connection, result_set_uuid, statement);
    if (!SQL_SUCCEEDED(result)) {
        return result;
    }
    if (!decoded_all) {
        return fail(statement, "JDBC metadata contains a result type unsupported by the ODBC client",
                    "HY000");
    }
    if (method == "Tables") {
        static constexpr std::size_t ODBC_TABLES_COLUMN_COUNT = 5;
        if (statement->columns.size() < ODBC_TABLES_COLUMN_COUNT ||
            std::any_of(statement->rows.begin(), statement->rows.end(),
                        [](const std::vector<Cell>& row) {
                            return row.size() < ODBC_TABLES_COLUMN_COUNT;
                        })) {
            return fail(statement, "JDBC table metadata does not contain the required columns",
                        "HY000");
        }
        statement->columns.resize(ODBC_TABLES_COLUMN_COUNT);
        for (auto& row : statement->rows) {
            row.resize(ODBC_TABLES_COLUMN_COUNT);
        }
    } else if (method == "Catalogs" || method == "Schemas" || method == "TableTypes") {
        // SQLTables enumerations (SQL_ALL_CATALOGS/SCHEMAS/TABLE_TYPES) keep the SQLTables
        // five-column shape: only the enumerated column is filled, the others are NULL.
        const std::size_t target = method == "Catalogs" ? 0 : (method == "Schemas" ? 1 : 3);
        for (auto& row : statement->rows) {
            std::vector<Cell> reshaped(5);
            if (!row.empty()) {
                reshaped[target] = std::move(row[0]);
            }
            row = std::move(reshaped);
        }
        statement->columns = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"};
    } else if (method == "Columns") {
        static constexpr std::size_t ODBC_COLUMNS_COLUMN_COUNT = 24;
        if (statement->columns.size() < ODBC_COLUMNS_COLUMN_COUNT ||
            std::any_of(statement->rows.begin(), statement->rows.end(),
                        [](const std::vector<Cell>& row) {
                            return row.size() < ODBC_COLUMNS_COLUMN_COUNT;
                        })) {
            return fail(statement, "JDBC column metadata does not contain the required columns",
                        "HY000");
        }
        statement->columns.resize(ODBC_COLUMNS_COLUMN_COUNT);
        for (auto& row : statement->rows) {
            row.resize(ODBC_COLUMNS_COLUMN_COUNT);
        }
    }
    statement->has_result_set = true;
    statement->row_count = static_cast<SQLLEN>(statement->rows.size());
    return SQL_SUCCESS;
}

ParameterValue metadata_string(const std::string* value) {
    ParameterValue parameter;
    if (value == nullptr) {
        parameter.set_is_null(true);
    } else {
        parameter.set_string_value(*value);
    }
    return parameter;
}

bool is_empty_string_parameter(const ParameterValue& parameter) {
    return parameter.value_case() == ParameterValue::kStringValue &&
        parameter.string_value().empty();
}

bool read_ansi_string(const SQLCHAR* input, SQLSMALLINT length, std::string* output) {
    if (input == nullptr || (length < 0 && length != SQL_NTS)) {
        return false;
    }
    const auto* text = reinterpret_cast<const char*>(input);
    const auto size = length == SQL_NTS
        ? std::strlen(text) : static_cast<std::size_t>(length);
    output->assign(text, size);
    return true;
}

SQLRETURN append_ansi_metadata_parameter(HandleBase* handle, const SQLCHAR* input,
                                         SQLSMALLINT length,
                                         std::vector<ParameterValue>* parameters) {
    if (input == nullptr) {
        parameters->push_back(metadata_string(nullptr));
        return SQL_SUCCESS;
    }
    std::string value;
    if (!read_ansi_string(input, length, &value)) {
        return fail(handle, "Metadata string length is invalid", "HY090");
    }
    parameters->push_back(metadata_string(&value));
    return SQL_SUCCESS;
}

SQLRETURN append_wide_metadata_parameter(HandleBase* handle, const SQLWCHAR* input,
                                         SQLSMALLINT length,
                                         std::vector<ParameterValue>* parameters) {
    if (input == nullptr) {
        parameters->push_back(metadata_string(nullptr));
        return SQL_SUCCESS;
    }
    if (length < 0 && length != SQL_NTS) {
        return fail(handle, "Metadata string length is invalid", "HY090");
    }
    std::string value;
    if (!read_wide_string(input, length, &value)) {
        return fail(handle, "Metadata string contains invalid Unicode or length", "22018");
    }
    parameters->push_back(metadata_string(&value));
    return SQL_SUCCESS;
}

std::vector<std::string> parse_table_types(const std::string& table_types) {
    std::vector<std::string> types;
    std::size_t position = 0;
    while (position <= table_types.size()) {
        const auto separator = table_types.find(',', position);
        const auto end = separator == std::string::npos ? table_types.size() : separator;
        std::string type = trim(table_types.substr(position, end - position));
        // ODBC allows quoted values ('TABLE','VIEW'); JDBC expects the bare type names.
        if (type.size() >= 2 && type.front() == '\'' && type.back() == '\'') {
            type = trim(type.substr(1, type.size() - 2));
        }
        if (!type.empty()) {
            types.push_back(std::move(type));
        }
        if (separator == std::string::npos) {
            break;
        }
        position = separator + 1;
    }
    return types;
}

SQLRETURN append_table_types(HandleBase* handle, const SQLCHAR* input, SQLSMALLINT length,
                             std::vector<ParameterValue>* parameters) {
    if (input == nullptr) {
        parameters->push_back(metadata_string(nullptr));
        return SQL_SUCCESS;
    }
    std::string value;
    if (!read_ansi_string(input, length, &value)) {
        return fail(handle, "Table type string length is invalid", "HY090");
    }
    ParameterValue parameter;
    const auto types = parse_table_types(value);
    if (types.empty()) {
        parameter.set_is_null(true);
    }
    for (const auto& type : types) {
        parameter.mutable_string_array_value()->add_values(type);
    }
    parameters->push_back(std::move(parameter));
    return SQL_SUCCESS;
}

SQLRETURN append_wide_table_types(HandleBase* handle, const SQLWCHAR* input, SQLSMALLINT length,
                                  std::vector<ParameterValue>* parameters) {
    if (input == nullptr) {
        parameters->push_back(metadata_string(nullptr));
        return SQL_SUCCESS;
    }
    if (length < 0 && length != SQL_NTS) {
        return fail(handle, "Table type string length is invalid", "HY090");
    }
    std::string value;
    if (!read_wide_string(input, length, &value)) {
        return fail(handle, "Table type string contains invalid Unicode or length", "22018");
    }
    ParameterValue parameter;
    const auto types = parse_table_types(value);
    if (types.empty()) {
        parameter.set_is_null(true);
    }
    for (const auto& type : types) {
        parameter.mutable_string_array_value()->add_values(type);
    }
    parameters->push_back(std::move(parameter));
    return SQL_SUCCESS;
}

SQLRETURN execute_catalog_api(SQLHSTMT statement, const std::string& method,
                              const std::vector<ParameterValue>& parameters) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    std::lock_guard<std::mutex> statement_lock(target->operation_mutex);
    std::lock_guard<std::mutex> connection_lock(target->connection->operation_mutex);
    // An empty catalog has no meaning for JDBC drivers that switch databases by catalog
    // (mssql-jdbc issues "USE []"), so it is sent as NULL, i.e. the current catalog.
    std::vector<ParameterValue> normalized = parameters;
    auto null_empty_catalog = [&normalized](std::size_t index) {
        if (index < normalized.size() && is_empty_string_parameter(normalized[index])) {
            normalized[index] = metadata_string(nullptr);
        }
    };
    if (method == "Tables" || method == "Columns" || method == "PrimaryKeys" ||
        method == "ImportedKeys" || method == "ExportedKeys" || method == "CrossReference") {
        null_empty_catalog(0);
    }
    if (method == "CrossReference") {
        null_empty_catalog(3);
    }
    return execute_metadata_statement(target, method, normalized);
}

bool is_string_parameter(const ParameterValue& parameter, const char* value) {
    return parameter.value_case() == ParameterValue::kStringValue &&
        parameter.string_value() == value;
}

bool is_all_table_types(const ParameterValue& parameter) {
    return parameter.value_case() == ParameterValue::kStringArrayValue &&
        parameter.string_array_value().values_size() == 1 &&
        parameter.string_array_value().values(0) == "%";
}

// Parameters: catalog, schema, table, table types (as built by SQLTables/SQLTablesW).
SQLRETURN execute_tables_api(SQLHSTMT statement, std::vector<ParameterValue> parameters) {
    const bool empty_catalog = is_empty_string_parameter(parameters[0]);
    const bool empty_schema = is_empty_string_parameter(parameters[1]);
    const bool empty_table = is_empty_string_parameter(parameters[2]);
    if (is_string_parameter(parameters[0], "%") && empty_schema && empty_table) {
        return execute_catalog_api(statement, "Catalogs", {});
    }
    if (empty_catalog && is_string_parameter(parameters[1], "%") && empty_table) {
        return execute_catalog_api(statement, "Schemas", {});
    }
    if (is_all_table_types(parameters[3])) {
        if (empty_catalog && empty_schema && empty_table) {
            return execute_catalog_api(statement, "TableTypes", {});
        }
        parameters[3] = metadata_string(nullptr);
    }
    return execute_catalog_api(statement, "Tables", parameters);
}

SQLRETURN get_database_metadata(ConnectionHandle* connection, HandleBase* handle,
                                const std::string& method,
                                const std::vector<ParameterValue>& parameters,
                                ParameterValue* value,
                                com::openjproxy::grpc::CallType call_type =
                                    com::openjproxy::grpc::CALL_GET) {
    com::openjproxy::grpc::TargetCall metadata_call;
    metadata_call.set_calltype(call_type);
    metadata_call.set_resourcename(method);
    for (const auto& parameter : parameters) {
        metadata_call.add_params()->CopyFrom(parameter);
    }
    com::openjproxy::grpc::CallResourceResponse response;
    const SQLRETURN result = call_resource(connection, handle,
        com::openjproxy::grpc::RES_CONNECTION, "", com::openjproxy::grpc::CALL_GET,
        "MetaData", {}, &response, &metadata_call);
    if (!SQL_SUCCEEDED(result)) {
        return result;
    }
    if (response.values_size() == 0) {
        return fail(handle, "JDBC metadata call returned no value", "HY000");
    }
    value->CopyFrom(response.values(0));
    return SQL_SUCCESS;
}

struct ColumnDescription {
    std::string name;
    std::string type_name = "VARCHAR";
    SQLSMALLINT type = SQL_VARCHAR;
    SQLULEN size = 255;
    SQLSMALLINT decimal_digits = 0;
    SQLSMALLINT nullable = SQL_NULLABLE;
    SQLLEN display_size = 255;
    SQLLEN octet_length = 255;
    bool is_unsigned = false;
};

// Size above which text/binary is treated as a LOB (e.g. nvarchar(max) = 1073741823).
constexpr int kMaxInlineLength = 8000;

// Maps java.sql.Types to the equivalent ODBC SQL type; false for unknown types.
bool map_jdbc_type(const ColumnMetadata& meta, ColumnDescription* description) {
    const bool unbounded = meta.precision <= 0 || meta.precision > kMaxInlineLength;
    const SQLULEN precision = static_cast<SQLULEN>(std::max(meta.precision, 0));
    auto set = [&](SQLSMALLINT type, const char* name, SQLULEN size) {
        description->type = type;
        description->type_name = name;
        description->size = size;
    };
    switch (meta.jdbc_type) {
        case -7:   // BIT
        case 16:   // BOOLEAN
            set(SQL_BIT, "BIT", 1);
            break;
        case -6:   // TINYINT
            set(SQL_TINYINT, "TINYINT", 3);
            break;
        case 5:    // SMALLINT
            set(SQL_SMALLINT, "SMALLINT", 5);
            break;
        case 4:    // INTEGER
            set(SQL_INTEGER, "INTEGER", 10);
            break;
        case -5:   // BIGINT
            set(SQL_BIGINT, "BIGINT", 19);
            break;
        case 7:    // REAL
            set(SQL_REAL, "REAL", 7);
            break;
        case 6:    // FLOAT
        case 8:    // DOUBLE
            set(SQL_DOUBLE, "DOUBLE", 15);
            break;
        case 2:    // NUMERIC
        case 3:    // DECIMAL
            set(meta.jdbc_type == 2 ? SQL_NUMERIC : SQL_DECIMAL,
                meta.jdbc_type == 2 ? "NUMERIC" : "DECIMAL", precision > 0 ? precision : 38);
            description->decimal_digits = static_cast<SQLSMALLINT>(std::max(meta.scale, 0));
            break;
        case 1:    // CHAR
            unbounded ? set(SQL_LONGVARCHAR, "LONGVARCHAR", precision) : set(SQL_CHAR, "CHAR", precision);
            break;
        case 12:   // VARCHAR
            unbounded ? set(SQL_LONGVARCHAR, "LONGVARCHAR", precision) : set(SQL_VARCHAR, "VARCHAR", precision);
            break;
        case -1:   // LONGVARCHAR
        case 2005: // CLOB
            set(SQL_LONGVARCHAR, "LONGVARCHAR", precision);
            break;
        case -15:  // NCHAR
            unbounded ? set(SQL_WLONGVARCHAR, "WLONGVARCHAR", precision) : set(SQL_WCHAR, "WCHAR", precision);
            break;
        case -9:   // NVARCHAR
            unbounded ? set(SQL_WLONGVARCHAR, "WLONGVARCHAR", precision) : set(SQL_WVARCHAR, "WVARCHAR", precision);
            break;
        case -16:  // LONGNVARCHAR
        case 2011: // NCLOB
        case 2009: // SQLXML
            set(SQL_WLONGVARCHAR, "WLONGVARCHAR", precision);
            break;
        case -2:   // BINARY
            unbounded ? set(SQL_LONGVARBINARY, "LONGVARBINARY", precision) : set(SQL_BINARY, "BINARY", precision);
            break;
        case -3:   // VARBINARY
            unbounded ? set(SQL_LONGVARBINARY, "LONGVARBINARY", precision) : set(SQL_VARBINARY, "VARBINARY", precision);
            break;
        case -4:   // LONGVARBINARY
        case 2004: // BLOB
            set(SQL_LONGVARBINARY, "LONGVARBINARY", precision);
            break;
        case 91:   // DATE
            set(SQL_TYPE_DATE, "DATE", 10);
            break;
        case 92:   // TIME
        case 2013: // TIME_WITH_TIMEZONE
            description->decimal_digits = static_cast<SQLSMALLINT>(std::clamp(meta.scale, 0, 9));
            set(SQL_TYPE_TIME, "TIME", 8 + (description->decimal_digits > 0 ? description->decimal_digits + 1 : 0));
            break;
        case 93:   // TIMESTAMP
        case 2014: // TIMESTAMP_WITH_TIMEZONE
            description->decimal_digits = static_cast<SQLSMALLINT>(std::clamp(meta.scale, 0, 9));
            set(SQL_TYPE_TIMESTAMP, "TIMESTAMP",
                19 + (description->decimal_digits > 0 ? description->decimal_digits + 1 : 0));
            break;
        case -155: { // microsoft.sql.Types.DATETIMEOFFSET: text "YYYY-MM-DD HH:MM:SS[.f] +HH:MM"
            const int scale = std::clamp(meta.scale, 0, 7);
            set(SQL_WVARCHAR, "datetimeoffset", 26 + (scale > 0 ? scale + 1 : 0));
            break;
        }
        default:
            return false;
    }
    return true;
}

// Fills DISPLAY_SIZE and OCTET_LENGTH according to the ODBC size table.
void finish_column_description(ColumnDescription* description) {
    const auto size = static_cast<SQLLEN>(std::min<SQLULEN>(
        description->size, static_cast<SQLULEN>(std::numeric_limits<SQLINTEGER>::max())));
    switch (description->type) {
        case SQL_WCHAR: case SQL_WVARCHAR: case SQL_WLONGVARCHAR:
            description->display_size = size;
            description->octet_length = size > std::numeric_limits<SQLINTEGER>::max() / 2
                ? std::numeric_limits<SQLINTEGER>::max() : size * 2;
            break;
        case SQL_BINARY: case SQL_VARBINARY: case SQL_LONGVARBINARY:
            description->display_size = size > std::numeric_limits<SQLINTEGER>::max() / 2
                ? std::numeric_limits<SQLINTEGER>::max() : size * 2;
            description->octet_length = size;
            break;
        case SQL_DECIMAL: case SQL_NUMERIC:
            description->display_size = size + 2;
            description->octet_length = size + 2;
            break;
        case SQL_BIT: description->display_size = 1; description->octet_length = 1; break;
        case SQL_TINYINT: description->display_size = 4; description->octet_length = 1; break;
        case SQL_SMALLINT: description->display_size = 6; description->octet_length = 2; break;
        case SQL_INTEGER: description->display_size = 11; description->octet_length = 4; break;
        case SQL_BIGINT: description->display_size = 20; description->octet_length = 8; break;
        case SQL_REAL: description->display_size = 14; description->octet_length = 4; break;
        case SQL_FLOAT: case SQL_DOUBLE:
            description->display_size = 24; description->octet_length = 8; break;
        case SQL_TYPE_DATE:
            description->display_size = 10; description->octet_length = sizeof(SQL_DATE_STRUCT); break;
        case SQL_TYPE_TIME:
            description->display_size = size; description->octet_length = sizeof(SQL_TIME_STRUCT); break;
        case SQL_TYPE_TIMESTAMP:
            description->display_size = size; description->octet_length = sizeof(SQL_TIMESTAMP_STRUCT); break;
        case SQL_GUID:
            description->display_size = 36; description->octet_length = 16; break;
        default:
            description->display_size = size;
            description->octet_length = size;
            break;
    }
}

bool describe_column(const StatementHandle* statement, SQLUSMALLINT column_number,
                     ColumnDescription* description) {
    if (column_number == 0 || column_number > statement->columns.size()) {
        return false;
    }
    const std::size_t index = column_number - 1;
    description->name = statement->columns[index];
    if (index < statement->column_meta.size()) {
        const ColumnMetadata& meta = statement->column_meta[index];
        description->nullable = static_cast<SQLSMALLINT>(
            meta.nullable == 0 ? SQL_NO_NULLS : meta.nullable == 1 ? SQL_NULLABLE : SQL_NULLABLE_UNKNOWN);
        if (map_jdbc_type(meta, description)) {
            description->is_unsigned = description->type == SQL_TINYINT;
            finish_column_description(description);
            return true;
        }
    }
    // No metadata (or unknown JDBC type): infer from the first non-null value and use the
    // longest length across all rows, so values of later rows are not truncated.
    const Cell* sample = nullptr;
    std::size_t longest = 0;
    for (const auto& row : statement->rows) {
        if (index >= row.size() || std::holds_alternative<std::monostate>(row[index])) {
            continue;
        }
        if (sample == nullptr) {
            sample = &row[index];
        }
        if (const auto* text = std::get_if<std::string>(&row[index])) {
            longest = std::max(longest, text->size());
        } else if (const auto* bytes = std::get_if<std::vector<std::uint8_t>>(&row[index])) {
            longest = std::max(longest, bytes->size());
        }
    }
    if (sample == nullptr) {
        finish_column_description(description);
        return true;
    }
    const Cell& cell = *sample;
    if (std::holds_alternative<std::int64_t>(cell)) {
        description->type = SQL_BIGINT;
        description->type_name = "BIGINT";
        description->size = 19;
    } else if (std::holds_alternative<std::int32_t>(cell)) {
        description->type = SQL_INTEGER;
        description->type_name = "INTEGER";
        description->size = 10;
    } else if (std::holds_alternative<double>(cell)) {
        description->type = SQL_DOUBLE;
        description->type_name = "DOUBLE";
        description->size = 15;
    } else if (std::holds_alternative<bool>(cell)) {
        description->type = SQL_BIT;
        description->type_name = "BOOLEAN";
        description->size = 1;
    } else if (std::holds_alternative<std::vector<std::uint8_t>>(cell)) {
        description->type = SQL_VARBINARY;
        description->type_name = "VARBINARY";
        description->size = std::max<SQLULEN>(1, static_cast<SQLULEN>(longest));
    } else if (std::holds_alternative<UuidCell>(cell)) {
        description->type = SQL_GUID;
        description->type_name = "GUID";
        description->size = 36;
    } else if (const auto* text = std::get_if<std::string>(&cell)) {
        description->size = std::max<SQLULEN>({255, static_cast<SQLULEN>(longest)});
        if (text->compare(0, 16, "OJP_CLOB_PREFIX:") == 0) {
            description->type = SQL_LONGVARCHAR;
            description->type_name = "LONGVARCHAR";
        }
    }
    finish_column_description(description);
    return true;
}

SQLRETURN metadata_boolean(ConnectionHandle* connection, HandleBase* handle,
                           const std::string& method, bool* value,
                           com::openjproxy::grpc::CallType call_type =
                               com::openjproxy::grpc::CALL_SUPPORTS) {
    ParameterValue result;
    const SQLRETURN status = get_database_metadata(
        connection, handle, method, {}, &result, call_type);
    if (!SQL_SUCCEEDED(status)) {
        return status;
    }
    if (result.value_case() == ParameterValue::kBoolValue) {
        *value = result.bool_value();
    } else if (result.value_case() == ParameterValue::kIsNull) {
        *value = false;
    } else {
        return fail(handle, "JDBC metadata returned a non-boolean value", "HY000");
    }
    return SQL_SUCCESS;
}

SQLRETURN metadata_integer(ConnectionHandle* connection, HandleBase* handle,
                           const std::string& method, SQLUINTEGER* value) {
    ParameterValue result;
    const SQLRETURN status = get_database_metadata(connection, handle, method, {}, &result);
    if (!SQL_SUCCEEDED(status)) {
        return status;
    }
    if (result.value_case() == ParameterValue::kIntValue) {
        *value = static_cast<SQLUINTEGER>(std::max(0, result.int_value()));
    } else {
        return fail(handle, "JDBC metadata returned a non-integer value", "HY000");
    }
    return SQL_SUCCESS;
}

SQLRETURN metadata_text(ConnectionHandle* connection, HandleBase* handle,
                        const std::string& method, std::string* text) {
    ParameterValue result;
    const SQLRETURN status = get_database_metadata(connection, handle, method, {}, &result);
    if (!SQL_SUCCEEDED(status)) {
        return status;
    }
    if (result.value_case() == ParameterValue::kStringValue) {
        *text = result.string_value();
    } else if (result.value_case() == ParameterValue::kIsNull) {
        text->clear();
    } else {
        return fail(handle, "JDBC metadata returned a non-string value", "HY000");
    }
    return SQL_SUCCESS;
}

// Fixed SQLGetInfo answers for the driver's own characteristics (forward-only cursor,
// read-only, no function escapes), requested by FireDAC/ADO/MSDASQL when connecting.
enum class InfoKind { small, integer, text };

struct StaticInfo {
    SQLUSMALLINT id;
    const char* name;
    InfoKind kind;
    SQLUINTEGER number;
    const char* text;
};

#define OJP_INFO_SMALL(id, value) {id, #id, InfoKind::small, static_cast<SQLUINTEGER>(value), ""}
#define OJP_INFO_INT(id, value) {id, #id, InfoKind::integer, static_cast<SQLUINTEGER>(value), ""}
#define OJP_INFO_TEXT(id, value) {id, #id, InfoKind::text, 0, value}

const StaticInfo kStaticInfo[] = {
    OJP_INFO_SMALL(SQL_FILE_USAGE, SQL_FILE_NOT_SUPPORTED),
    OJP_INFO_SMALL(SQL_MAX_DRIVER_CONNECTIONS, 0),
    OJP_INFO_SMALL(SQL_MAX_CONCURRENT_ACTIVITIES, 0),
    OJP_INFO_SMALL(SQL_ACTIVE_ENVIRONMENTS, 0),
    OJP_INFO_SMALL(SQL_CURSOR_COMMIT_BEHAVIOR, SQL_CB_CLOSE),
    OJP_INFO_SMALL(SQL_CURSOR_ROLLBACK_BEHAVIOR, SQL_CB_CLOSE),
    OJP_INFO_SMALL(SQL_CONCAT_NULL_BEHAVIOR, SQL_CB_NULL),
    OJP_INFO_SMALL(SQL_CORRELATION_NAME, SQL_CN_ANY),
    OJP_INFO_SMALL(SQL_NON_NULLABLE_COLUMNS, SQL_NNC_NON_NULL),
    OJP_INFO_SMALL(SQL_NULL_COLLATION, SQL_NC_LOW),
    OJP_INFO_SMALL(SQL_QUOTED_IDENTIFIER_CASE, SQL_IC_MIXED),
    OJP_INFO_SMALL(SQL_GROUP_BY, SQL_GB_GROUP_BY_CONTAINS_SELECT),
    OJP_INFO_SMALL(SQL_CATALOG_LOCATION, SQL_CL_START),
    OJP_INFO_SMALL(SQL_MAX_IDENTIFIER_LEN, 128),
    OJP_INFO_SMALL(SQL_MAX_CURSOR_NAME_LEN, 0),
    OJP_INFO_SMALL(SQL_MAX_PROCEDURE_NAME_LEN, 128),
    OJP_INFO_SMALL(SQL_MAX_USER_NAME_LEN, 0),
    OJP_INFO_SMALL(SQL_MAX_COLUMNS_IN_GROUP_BY, 0),
    OJP_INFO_SMALL(SQL_MAX_COLUMNS_IN_INDEX, 0),
    OJP_INFO_SMALL(SQL_MAX_COLUMNS_IN_ORDER_BY, 0),
    OJP_INFO_SMALL(SQL_ODBC_API_CONFORMANCE, SQL_OAC_LEVEL1),
    OJP_INFO_SMALL(SQL_ODBC_SQL_CONFORMANCE, SQL_OSC_CORE),
    OJP_INFO_SMALL(SQL_ODBC_SAG_CLI_CONFORMANCE, SQL_OSCC_COMPLIANT),
    OJP_INFO_INT(SQL_MAX_ROW_SIZE, 0),
    OJP_INFO_INT(SQL_MAX_STATEMENT_LEN, 0),
    OJP_INFO_INT(SQL_MAX_CHAR_LITERAL_LEN, 0),
    OJP_INFO_INT(SQL_MAX_BINARY_LITERAL_LEN, 0),
    OJP_INFO_INT(SQL_MAX_INDEX_SIZE, 0),
    OJP_INFO_INT(SQL_MAX_ASYNC_CONCURRENT_STATEMENTS, 0),
    OJP_INFO_INT(SQL_ASYNC_MODE, SQL_AM_NONE),
    OJP_INFO_INT(SQL_CURSOR_SENSITIVITY, SQL_INSENSITIVE),
    OJP_INFO_INT(SQL_SCROLL_CONCURRENCY, SQL_SCCO_READ_ONLY),
    OJP_INFO_INT(SQL_SCROLL_OPTIONS, SQL_SO_FORWARD_ONLY),
    OJP_INFO_INT(SQL_STATIC_SENSITIVITY, 0),
    OJP_INFO_INT(SQL_POS_OPERATIONS, 0),
    OJP_INFO_INT(SQL_POSITIONED_STATEMENTS, 0),
    OJP_INFO_INT(SQL_LOCK_TYPES, 0),
    OJP_INFO_INT(SQL_BOOKMARK_PERSISTENCE, 0),
    OJP_INFO_INT(SQL_FORWARD_ONLY_CURSOR_ATTRIBUTES1, SQL_CA1_NEXT),
    OJP_INFO_INT(SQL_FORWARD_ONLY_CURSOR_ATTRIBUTES2, SQL_CA2_READ_ONLY_CONCURRENCY),
    OJP_INFO_INT(SQL_STATIC_CURSOR_ATTRIBUTES1, 0),
    OJP_INFO_INT(SQL_STATIC_CURSOR_ATTRIBUTES2, 0),
    OJP_INFO_INT(SQL_DYNAMIC_CURSOR_ATTRIBUTES1, 0),
    OJP_INFO_INT(SQL_DYNAMIC_CURSOR_ATTRIBUTES2, 0),
    OJP_INFO_INT(SQL_KEYSET_CURSOR_ATTRIBUTES1, 0),
    OJP_INFO_INT(SQL_KEYSET_CURSOR_ATTRIBUTES2, 0),
    OJP_INFO_INT(SQL_BATCH_SUPPORT, 0),
    OJP_INFO_INT(SQL_BATCH_ROW_COUNT, 0),
    OJP_INFO_INT(SQL_PARAM_ARRAY_ROW_COUNTS, SQL_PARC_NO_BATCH),
    OJP_INFO_INT(SQL_PARAM_ARRAY_SELECTS, SQL_PAS_NO_SELECT),
    OJP_INFO_INT(SQL_ALTER_TABLE, 0),
    OJP_INFO_INT(SQL_CONVERT_FUNCTIONS, 0),
    OJP_INFO_INT(SQL_NUMERIC_FUNCTIONS, 0),
    OJP_INFO_INT(SQL_STRING_FUNCTIONS, 0),
    OJP_INFO_INT(SQL_SYSTEM_FUNCTIONS, 0),
    OJP_INFO_INT(SQL_TIMEDATE_FUNCTIONS, 0),
    OJP_INFO_INT(SQL_TIMEDATE_ADD_INTERVALS, 0),
    OJP_INFO_INT(SQL_TIMEDATE_DIFF_INTERVALS, 0),
    OJP_INFO_INT(SQL_CONVERT_BIGINT, 0),
    OJP_INFO_INT(SQL_CONVERT_BINARY, 0),
    OJP_INFO_INT(SQL_CONVERT_BIT, 0),
    OJP_INFO_INT(SQL_CONVERT_CHAR, 0),
    OJP_INFO_INT(SQL_CONVERT_DATE, 0),
    OJP_INFO_INT(SQL_CONVERT_DECIMAL, 0),
    OJP_INFO_INT(SQL_CONVERT_DOUBLE, 0),
    OJP_INFO_INT(SQL_CONVERT_FLOAT, 0),
    OJP_INFO_INT(SQL_CONVERT_INTEGER, 0),
    OJP_INFO_INT(SQL_CONVERT_LONGVARBINARY, 0),
    OJP_INFO_INT(SQL_CONVERT_LONGVARCHAR, 0),
    OJP_INFO_INT(SQL_CONVERT_NUMERIC, 0),
    OJP_INFO_INT(SQL_CONVERT_REAL, 0),
    OJP_INFO_INT(SQL_CONVERT_SMALLINT, 0),
    OJP_INFO_INT(SQL_CONVERT_TIME, 0),
    OJP_INFO_INT(SQL_CONVERT_TIMESTAMP, 0),
    OJP_INFO_INT(SQL_CONVERT_TINYINT, 0),
    OJP_INFO_INT(SQL_CONVERT_VARBINARY, 0),
    OJP_INFO_INT(SQL_CONVERT_VARCHAR, 0),
    OJP_INFO_INT(SQL_CONVERT_WCHAR, 0),
    OJP_INFO_INT(SQL_CONVERT_WLONGVARCHAR, 0),
    OJP_INFO_INT(SQL_CONVERT_WVARCHAR, 0),
    OJP_INFO_INT(SQL_CONVERT_GUID, 0),
    OJP_INFO_INT(SQL_OJ_CAPABILITIES, SQL_OJ_LEFT | SQL_OJ_RIGHT | SQL_OJ_FULL | SQL_OJ_NESTED |
                                          SQL_OJ_NOT_ORDERED | SQL_OJ_INNER |
                                          SQL_OJ_ALL_COMPARISON_OPS),
    OJP_INFO_INT(SQL_SUBQUERIES, SQL_SQ_CORRELATED_SUBQUERIES | SQL_SQ_COMPARISON |
                                     SQL_SQ_EXISTS | SQL_SQ_IN | SQL_SQ_QUANTIFIED),
    OJP_INFO_INT(SQL_UNION, SQL_U_UNION | SQL_U_UNION_ALL),
    OJP_INFO_INT(SQL_SCHEMA_USAGE, SQL_SU_DML_STATEMENTS | SQL_SU_PROCEDURE_INVOCATION |
                                       SQL_SU_TABLE_DEFINITION | SQL_SU_INDEX_DEFINITION |
                                       SQL_SU_PRIVILEGE_DEFINITION),
    OJP_INFO_INT(SQL_CATALOG_USAGE, SQL_CU_DML_STATEMENTS | SQL_CU_PROCEDURE_INVOCATION |
                                        SQL_CU_TABLE_DEFINITION),
    OJP_INFO_INT(SQL_SQL_CONFORMANCE, SQL_SC_SQL92_ENTRY),
    OJP_INFO_INT(SQL_ODBC_INTERFACE_CONFORMANCE, SQL_OIC_CORE),
    OJP_INFO_INT(SQL_STANDARD_CLI_CONFORMANCE, 0),
    OJP_INFO_INT(SQL_DTC_TRANSITION_COST, 0),
    OJP_INFO_INT(SQL_AGGREGATE_FUNCTIONS, SQL_AF_ALL),
    OJP_INFO_INT(SQL_DATETIME_LITERALS, 0),
    OJP_INFO_INT(SQL_INDEX_KEYWORDS, SQL_IK_ASC | SQL_IK_DESC),
    OJP_INFO_INT(SQL_INFO_SCHEMA_VIEWS, 0),
    OJP_INFO_INT(SQL_INSERT_STATEMENT, SQL_IS_INSERT_LITERALS | SQL_IS_INSERT_SEARCHED |
                                           SQL_IS_SELECT_INTO),
    OJP_INFO_INT(SQL_DDL_INDEX, SQL_DI_CREATE_INDEX | SQL_DI_DROP_INDEX),
    OJP_INFO_INT(SQL_CREATE_TABLE, 0),
    OJP_INFO_INT(SQL_CREATE_VIEW, 0),
    OJP_INFO_INT(SQL_CREATE_SCHEMA, 0),
    OJP_INFO_INT(SQL_DROP_TABLE, 0),
    OJP_INFO_INT(SQL_DROP_VIEW, 0),
    OJP_INFO_INT(SQL_DROP_SCHEMA, 0),
    OJP_INFO_INT(SQL_SQL92_DATETIME_FUNCTIONS, 0),
    OJP_INFO_INT(SQL_SQL92_FOREIGN_KEY_DELETE_RULE, 0),
    OJP_INFO_INT(SQL_SQL92_FOREIGN_KEY_UPDATE_RULE, 0),
    OJP_INFO_INT(SQL_SQL92_GRANT, 0),
    OJP_INFO_INT(SQL_SQL92_NUMERIC_VALUE_FUNCTIONS, 0),
    OJP_INFO_INT(SQL_SQL92_PREDICATES, 0),
    OJP_INFO_INT(SQL_SQL92_RELATIONAL_JOIN_OPERATORS, 0),
    OJP_INFO_INT(SQL_SQL92_REVOKE, 0),
    OJP_INFO_INT(SQL_SQL92_ROW_VALUE_CONSTRUCTOR, 0),
    OJP_INFO_INT(SQL_SQL92_STRING_FUNCTIONS, 0),
    OJP_INFO_INT(SQL_SQL92_VALUE_EXPRESSIONS, 0),
    OJP_INFO_INT(SQL_FETCH_DIRECTION, SQL_FD_FETCH_NEXT),
    OJP_INFO_INT(SQL_ALTER_DOMAIN, 0),
    OJP_INFO_INT(SQL_CREATE_ASSERTION, 0),
    OJP_INFO_INT(SQL_CREATE_CHARACTER_SET, 0),
    OJP_INFO_INT(SQL_CREATE_COLLATION, 0),
    OJP_INFO_INT(SQL_CREATE_DOMAIN, 0),
    OJP_INFO_INT(SQL_CREATE_TRANSLATION, 0),
    OJP_INFO_INT(SQL_DROP_ASSERTION, 0),
    OJP_INFO_INT(SQL_DROP_CHARACTER_SET, 0),
    OJP_INFO_INT(SQL_DROP_COLLATION, 0),
    OJP_INFO_INT(SQL_DROP_DOMAIN, 0),
    OJP_INFO_INT(SQL_DROP_TRANSLATION, 0),
#ifdef SQL_ASYNC_DBC_FUNCTIONS
    OJP_INFO_INT(SQL_ASYNC_DBC_FUNCTIONS, SQL_ASYNC_DBC_NOT_CAPABLE),
#endif
#ifdef SQL_ASYNC_NOTIFICATION
    OJP_INFO_INT(SQL_ASYNC_NOTIFICATION, SQL_ASYNC_NOTIFICATION_NOT_CAPABLE),
#endif
#ifdef SQL_DRIVER_AWARE_POOLING_SUPPORTED
    OJP_INFO_INT(SQL_DRIVER_AWARE_POOLING_SUPPORTED, SQL_DRIVER_AWARE_POOLING_NOT_CAPABLE),
#endif
    OJP_INFO_TEXT(SQL_ACCESSIBLE_TABLES, "N"),
    OJP_INFO_TEXT(SQL_ACCESSIBLE_PROCEDURES, "N"),
    OJP_INFO_TEXT(SQL_PROCEDURES, "Y"),
    OJP_INFO_TEXT(SQL_MULT_RESULT_SETS, "N"),
    OJP_INFO_TEXT(SQL_MULTIPLE_ACTIVE_TXN, "Y"),
    OJP_INFO_TEXT(SQL_NEED_LONG_DATA_LEN, "N"),
    OJP_INFO_TEXT(SQL_ROW_UPDATES, "N"),
    OJP_INFO_TEXT(SQL_EXPRESSIONS_IN_ORDERBY, "Y"),
    OJP_INFO_TEXT(SQL_ORDER_BY_COLUMNS_IN_SELECT, "N"),
    OJP_INFO_TEXT(SQL_LIKE_ESCAPE_CLAUSE, "Y"),
    OJP_INFO_TEXT(SQL_COLUMN_ALIAS, "Y"),
    OJP_INFO_TEXT(SQL_DATA_SOURCE_READ_ONLY, "N"),
    OJP_INFO_TEXT(SQL_INTEGRITY, "N"),
    OJP_INFO_TEXT(SQL_MAX_ROW_SIZE_INCLUDES_LONG, "N"),
    OJP_INFO_TEXT(SQL_DESCRIBE_PARAMETER, "N"),
    OJP_INFO_TEXT(SQL_CATALOG_NAME, "Y"),
    OJP_INFO_TEXT(SQL_OUTER_JOINS, "Y"),
    OJP_INFO_TEXT(SQL_COLLATION_SEQ, ""),
    OJP_INFO_TEXT(SQL_TABLE_TERM, "table"),
    OJP_INFO_TEXT(SQL_XOPEN_CLI_YEAR, "1995"),
};

#undef OJP_INFO_SMALL
#undef OJP_INFO_INT
#undef OJP_INFO_TEXT

const StaticInfo* find_static_info(SQLUSMALLINT info_type) {
    for (const auto& entry : kStaticInfo) {
        if (entry.id == info_type) {
            return &entry;
        }
    }
    return nullptr;
}

std::string unsupported_info_message(SQLUSMALLINT info_type) {
    return "SQLGetInfo InfoType " + std::to_string(info_type) +
           " is not supported by the OJP ODBC driver; report it with the application name so it "
           "can be added";
}

SQLRETURN get_sql_info_text(ConnectionHandle* connection, HandleBase* handle,
                            SQLUSMALLINT info_type, std::string* text) {
    if (const auto* entry = find_static_info(info_type);
        entry != nullptr && entry->kind == InfoKind::text) {
        *text = entry->text;
        return SQL_SUCCESS;
    }
    switch (info_type) {
        case SQL_SEARCH_PATTERN_ESCAPE:
            return metadata_text(connection, handle, "SearchStringEscape", text);
        case SQL_SPECIAL_CHARACTERS:
            return metadata_text(connection, handle, "ExtraNameCharacters", text);
        case SQL_PROCEDURE_TERM:
            return metadata_text(connection, handle, "ProcedureTerm", text);
        case SQL_SERVER_NAME:
            *text = connection->endpoint;
            return SQL_SUCCESS;
        case SQL_DATA_SOURCE_NAME:
        case SQL_DATABASE_NAME:
            text->clear();
            return SQL_SUCCESS;
        case SQL_DRIVER_NAME: *text = "libojp_odbc"; return SQL_SUCCESS;
        case SQL_DRIVER_VER: *text = "00.01.0000"; return SQL_SUCCESS;
        case SQL_DRIVER_ODBC_VER:
        case SQL_ODBC_VER: *text = "03.80"; return SQL_SUCCESS;
        case SQL_DBMS_NAME:
            return metadata_text(connection, handle, "DatabaseProductName", text);
        case SQL_DBMS_VER:
            return metadata_text(connection, handle, "DatabaseProductVersion", text);
        case SQL_IDENTIFIER_QUOTE_CHAR:
            return metadata_text(connection, handle, "IdentifierQuoteString", text);
        case SQL_KEYWORDS:
            return metadata_text(connection, handle, "SQLKeywords", text);
        case SQL_CATALOG_NAME_SEPARATOR:
            return metadata_text(connection, handle, "CatalogSeparator", text);
        case SQL_CATALOG_TERM:
            return metadata_text(connection, handle, "CatalogTerm", text);
        case SQL_SCHEMA_TERM:
            return metadata_text(connection, handle, "SchemaTerm", text);
        case SQL_USER_NAME:
            *text = connection->user;
            return SQL_SUCCESS;
        default:
            return fail(handle, unsupported_info_message(info_type), "HYC00");
    }
}

SQLRETURN get_sql_info_number(ConnectionHandle* connection, HandleBase* handle,
                              SQLUSMALLINT info_type, SQLUINTEGER* value) {
    switch (info_type) {
        case SQL_GETDATA_EXTENSIONS:
            *value = SQL_GD_ANY_COLUMN | SQL_GD_ANY_ORDER;
            return SQL_SUCCESS;
        case SQL_MAX_COLUMN_NAME_LEN:
            return metadata_integer(connection, handle, "MaxColumnNameLength", value);
        case SQL_MAX_TABLE_NAME_LEN:
            return metadata_integer(connection, handle, "MaxTableNameLength", value);
        case SQL_MAX_SCHEMA_NAME_LEN:
            return metadata_integer(connection, handle, "MaxSchemaNameLength", value);
        case SQL_MAX_CATALOG_NAME_LEN:
            return metadata_integer(connection, handle, "MaxCatalogNameLength", value);
        case SQL_MAX_COLUMNS_IN_SELECT:
            return metadata_integer(connection, handle, "MaxColumnsInSelect", value);
        case SQL_MAX_COLUMNS_IN_TABLE:
            return metadata_integer(connection, handle, "MaxColumnsInTable", value);
        case SQL_MAX_TABLES_IN_SELECT:
            return metadata_integer(connection, handle, "MaxTablesInSelect", value);
        case SQL_IDENTIFIER_CASE: {
            bool upper = false;
            bool lower = false;
            bool mixed = false;
            SQLRETURN status = metadata_boolean(connection, handle, "UpperCaseIdentifiers", &upper,
                com::openjproxy::grpc::CALL_STORES);
            if (!SQL_SUCCEEDED(status)) {
                return status;
            }
            status = metadata_boolean(connection, handle, "LowerCaseIdentifiers", &lower,
                com::openjproxy::grpc::CALL_STORES);
            if (!SQL_SUCCEEDED(status)) {
                return status;
            }
            status = metadata_boolean(connection, handle, "MixedCaseIdentifiers", &mixed,
                com::openjproxy::grpc::CALL_STORES);
            if (!SQL_SUCCEEDED(status)) {
                return status;
            }
            *value = upper ? SQL_IC_UPPER : (lower ? SQL_IC_LOWER :
                     (mixed ? SQL_IC_MIXED : SQL_IC_SENSITIVE));
            return SQL_SUCCESS;
        }
        case SQL_TXN_CAPABLE: {
            bool full_transactions = false;
            bool dml_transactions = false;
            SQLRETURN status = metadata_boolean(connection, handle,
                "DataDefinitionAndDataManipulationTransactions", &full_transactions);
            if (!SQL_SUCCEEDED(status)) {
                return status;
            }
            status = metadata_boolean(connection, handle,
                "DataManipulationTransactionsOnly", &dml_transactions);
            if (!SQL_SUCCEEDED(status)) {
                return status;
            }
            *value = full_transactions ? SQL_TC_ALL :
                (dml_transactions ? SQL_TC_DML : SQL_TC_NONE);
            return SQL_SUCCESS;
        }
        default:
            return fail(handle, unsupported_info_message(info_type), "HYC00");
    }
}

SQLRETURN connect(ConnectionHandle* connection, const std::string& connection_string) {
    clear_diagnostics(connection);
    auto parsed = parse_connection_string(connection_string);
    if (!parsed.error.empty()) {
        return fail(connection, parsed.error, "IM012");
    }
    const auto dsn_entry = parsed.values.find(ojp::odbc::dsn::KEY_DSN);
    if (dsn_entry != parsed.values.end() && !dsn_entry->second.empty()) {
        ojp::odbc::dsn::Attributes stored;
        std::string dsn_error;
        if (!ojp::odbc::dsn::read_stored_dsn(dsn_entry->second, &stored, &dsn_error)) {
            return fail(connection, dsn_error, "IM002");
        }
        parsed.values = ojp::odbc::dsn::merge(stored, parsed.values);
    }
    std::lock_guard<std::mutex> connection_lock(connection->operation_mutex);
    if (connection->connected) {
        return fail(connection, "ODBC connection is already open", "08002");
    }
    if (!configure_tls(parsed, &connection->tls)) {
        return fail(connection, "Invalid TLS configuration: use true/false, readable nonempty "
                    "PEM files and paired client certificate/key with TLS enabled", "HY024");
    }
    connection->endpoints.clear();
    connection->affinity_channels.clear();
    connection->affinity_stubs.clear();
    auto find = [&parsed](const std::string& key) -> std::string {
        const auto entry = parsed.values.find(key);
        return entry == parsed.values.end() ? std::string{} : entry->second;
    };
    const std::string configured_endpoints = find("SERVER").empty()
        ? find("ENDPOINT") : find("SERVER");
    connection->url = find("DATABASE");
    if (connection->url.empty()) {
        connection->url = find("URL");
    }
    connection->user = find("UID");
    if (connection->user.empty()) {
        connection->user = find("USER");
    }
    connection->password = find("PWD");
    const std::string xa_option = find("OJP.XA");
    connection->xa_mode = false;
    if (!xa_option.empty() && !parse_boolean_option(xa_option, &connection->xa_mode)) {
        return fail(connection, "OJP.XA must be true or false", "IM012");
    }
    connection->datasource_name = find("OJP.DATASOURCE.NAME");
    if (connection->datasource_name.empty()) {
        connection->datasource_name = "default";
    }
    if (!parse_endpoint_list(configured_endpoints, &connection->endpoints) ||
        connection->url.empty()) {
        std::string received;
        for (const auto& entry : parsed.values) {
            received += (received.empty() ? "" : ", ") + entry.first;
        }
        std::string missing;
        if (configured_endpoints.empty() && connection->url.empty()) {
            missing = "SERVER (OJP host:port list) and DATABASE (JDBC URL)";
        } else if (configured_endpoints.empty()) {
            missing = "SERVER (OJP host:port list)";
        } else if (connection->url.empty()) {
            missing = "DATABASE (JDBC URL)";
        } else {
            missing = "a valid SERVER value (expected host:port[,host:port...], got '" +
                      configured_endpoints + "')";
        }
        std::string hint;
        if (parsed.values.count(ojp::odbc::dsn::KEY_DSN) == 0 &&
            parsed.values.count("DRIVER") != 0) {
            hint = ". If you meant to use a DSN, do not combine DRIVER= with DSN= (the Driver "
                   "Manager drops DSN when DRIVER is present)";
        }
        return fail(connection, "Connection string is missing " + missing +
                                    "; keys received: [" + received + "]" + hint, "IM002");
    }
    if (connection->connected) {
        return fail(connection, "ODBC connection is already open", "08002");
    }
    connection->retry_attempts = 3;
    connection->retry_delay_ms = 100;
    bool load_aware = environment_boolean("OJP_LOADAWARE_SELECTION_ENABLED", true);
    const std::string load_aware_option = find("OJP.LOADAWARE.SELECTION.ENABLED");
    if (!load_aware_option.empty() &&
        !parse_boolean_option(load_aware_option, &load_aware)) {
        return fail(connection,
                    "OJP.LOADAWARE.SELECTION.ENABLED must be true or false", "IM012");
    }
    connection->load_aware = load_aware;
    if (connection->xa_mode) {
        connection->auto_commit = false;
    }
    const std::string retry_attempts_option = find("OJP.MULTINODE.RETRY.ATTEMPTS");
    if (!retry_attempts_option.empty() &&
        !parse_nonnegative_int_option(retry_attempts_option, 10,
                                      &connection->retry_attempts)) {
        return fail(connection,
                    "OJP.MULTINODE.RETRY.ATTEMPTS must be between 0 and 10", "IM012");
    }
    const std::string retry_delay_option = find("OJP.MULTINODE.RETRY.DELAY");
    if (!retry_delay_option.empty() &&
        !parse_nonnegative_int_option(retry_delay_option, 60000,
                                      &connection->retry_delay_ms)) {
        return fail(connection, "OJP.MULTINODE.RETRY.DELAY must be between 0 and 60000 ms",
                    "IM012");
    }
    std::string lob_chunk_option = find("OJP.LOB.CHUNK.SIZE");
    if (lob_chunk_option.empty()) {
        const char* environment_value = std::getenv("OJP_LOB_CHUNK_SIZE");
        lob_chunk_option = environment_value != nullptr ? environment_value : "";
    }
    connection->lob_chunk_size = kDefaultLobChunkSize;
    if (!lob_chunk_option.empty()) {
        int chunk_size = 0;
        if (!parse_nonnegative_int_option(lob_chunk_option, kMaxLobChunkSize, &chunk_size) ||
            chunk_size < kMinLobChunkSize) {
            return fail(connection, "OJP.LOB.CHUNK.SIZE must be between 65536 and 268435456 bytes",
                        "IM012");
        }
        connection->lob_chunk_size = static_cast<std::size_t>(chunk_size);
    }
    connection->client_uuid = make_client_uuid();
    connection->endpoint = choose_endpoint(connection->endpoints, load_aware, connection->tls);
    if (connection->endpoint.empty()) {
        return fail(connection, "No healthy OJP servers are available",
                    connection->tls->enabled ? "08001" : "08S01");
    }

    auto selected_endpoint = endpoint_runtime(connection->endpoint, connection->tls);
    connection->channel = selected_endpoint->channel;
    connection->stub = selected_endpoint->stub;

    ConnectionDetails details;
    details.set_url(connection->url);
    details.set_user(connection->user);
    details.set_password(connection->password);
    details.set_clientuuid(connection->client_uuid);
    details.set_isxa(connection->xa_mode);
    for (const auto& address : connection->endpoints) {
        details.add_serverendpoints(address);
    }
    details.set_clusterhealth(cluster_health(connection->endpoints, connection->tls));
    auto* datasource_property = details.add_properties();
    datasource_property->set_key("ojp.datasource.name");
    datasource_property->set_string_value(connection->datasource_name);
    connection->connection_details.CopyFrom(details);

    ClientRuntime& runtime = client_runtime();
    SessionInfo session;
    std::string cached_conn_hash;
    {
        std::lock_guard<std::mutex> lock(runtime.mutex);
        if (!connection->xa_mode) {
            const auto cached = runtime.conn_hash_by_key.find(connection_cache_key(connection));
            if (cached != runtime.conn_hash_by_key.end()) {
                cached_conn_hash = cached->second;
            }
        }
    }
    if (!cached_conn_hash.empty()) {
        session.set_connhash(cached_conn_hash);
        session.set_clientuuid(connection->client_uuid);
        session.set_isxa(false);
    } else {
        std::vector<std::string> ordered_endpoints{connection->endpoint};
        if (!connection->xa_mode) {
            for (const auto& address : connection->endpoints) {
                if (address != connection->endpoint) {
                    ordered_endpoints.push_back(address);
                }
            }
        }
        bool connected_to_any = false;
        std::string failure_message;
        std::string failure_state;
        SQLINTEGER failure_native = 0;
        for (const auto& address : ordered_endpoints) {
            auto endpoint = endpoint_runtime(address, connection->tls);
            if (!endpoint->healthy.load() &&
                std::find(connection->endpoints.begin(), connection->endpoints.end(), address) !=
                    connection->endpoints.end()) {
                if (!failure_message.empty()) {
                    failure_message += "; ";
                }
                failure_message += address + ": skipped, marked unhealthy by a previous failure";
                continue;
            }
            ConnectionDetails endpoint_details(details);
            endpoint_details.set_clusterhealth(cluster_health(connection->endpoints, connection->tls));
            grpc::ClientContext context;
            context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
            SessionInfo response;
            log_message(LogLevel::info, "connecting to " + address + " url=" +
                                            mask_password(connection->url) + " user=" +
                                            connection->user + " datasource=" +
                                            connection->datasource_name +
                                            (connection->xa_mode ? " xa=true" : ""));
            const auto status = endpoint->stub->connect(&context, endpoint_details, &response);
            if (!status.ok()) {
                const bool transport_failure = is_transport_failure(status, context);
                if (transport_failure) {
                    mark_endpoint_unhealthy(address, connection->tls);
                }
                std::string server_state;
                std::string server_message;
                SQLINTEGER server_native = 0;
                const bool from_database =
                    extract_sql_error(context, &server_state, &server_message, &server_native) &&
                    !server_message.empty();
                std::string detail = from_database ? server_message
                                                   : describe_grpc_status(status);
                if (transport_failure && connection->tls->enabled) {
                    detail += " (TLS is enabled: check certificates and OJP_CLIENT_TLS_* settings)";
                }
                if (!failure_message.empty()) {
                    failure_message += "; ";
                }
                failure_message += address + ": " + detail;
                log_message(LogLevel::warn, "connect to " + address + " failed: " + detail);
                if (!server_state.empty()) {
                    failure_state = server_state;
                    failure_native = server_native;
                } else if (!transport_failure) {
                    // OJP answered, but the database refused the connection.
                    failure_state = detail.find("Login failed") != std::string::npos ? "28000"
                                                                                     : "08001";
                }
                // The server answered and the database refused (URL, user, password): another OJP node
                // would give the same result, so there is no point in trying the others.
                if (!transport_failure) {
                    break;
                }
                continue;
            }
            endpoint->healthy.store(true);
            endpoint->last_failure_ms.store(0);
            if (!connected_to_any || address == connection->endpoint) {
                session.CopyFrom(response);
                connection->endpoint = address;
                connection->channel = endpoint->channel;
                connection->stub = endpoint->stub;
                connected_to_any = true;
            }
        }
        if (!connected_to_any) {
            connection->stub.reset();
            connection->channel.reset();
            if (failure_message.empty()) {
                failure_message = "no endpoint was attempted";
            }
            if (failure_state.empty() || failure_state == "08S01") {
                failure_state = connection->tls->enabled ? "08001" : "08S01";
            }
            return fail(connection, "Failed to connect through OJP (" + configured_endpoints +
                                        "): " + failure_message,
                        failure_state, failure_native);
        }
        if (connection->xa_mode && !session.isxa()) {
            return fail(connection, "OJP server did not establish an XA connection", "HY000",
                        OJP_XA_XAER_RMERR);
        }
        if (!session.connhash().empty()) {
            std::lock_guard<std::mutex> lock(runtime.mutex);
            if (!connection->xa_mode) {
                runtime.conn_hash_by_key[connection_cache_key(connection)] = session.connhash();
                runtime.details_by_conn_hash[session_cache_key(connection, session.connhash())] =
                    {details, connection->tls};
            } else {
                runtime.xa_details_by_conn_hash[session_cache_key(connection, session.connhash())] =
                    {details, connection->tls};
            }
        }
    }
    session.set_targetserver(connection->endpoint);
    connection->session.CopyFrom(session);
    if (!session.sessionuuid().empty()) {
        std::lock_guard<std::mutex> routing_lock(connection->routing_mutex);
        connection->session_to_server[session.sessionuuid()] = connection->endpoint;
    }
    selected_endpoint = endpoint_runtime(connection->endpoint, connection->tls);
    selected_endpoint->active_connections.fetch_add(1);
    connection->connected = true;
    log_message(LogLevel::info, "connected via " + connection->endpoint + " session=" +
                                    session.sessionuuid() + " connhash=" + session.connhash());
    ensure_health_checker();
    if (connection->transaction_isolation != 0) {
        const auto isolation_result = set_transaction_isolation(
            connection, connection, connection->transaction_isolation);
        if (!SQL_SUCCEEDED(isolation_result)) {
            return isolation_result;
        }
    }
    if (!connection->auto_commit && !connection->xa_mode) {
        const auto transaction_result = start_transaction(connection, connection);
        if (!SQL_SUCCEEDED(transaction_result)) {
            return transaction_result;
        }
    }
    return SQL_SUCCESS;
}

std::string brace_connection_value(const std::string& value) {
    std::string escaped;
    escaped.reserve(value.size());
    for (const char character : value) {
        escaped.push_back(character);
        if (character == '}') {
            escaped.push_back('}');
        }
    }
    return "{" + escaped + "}";
}

std::string public_connection_string(const std::string& input) {
    const auto parsed = parse_connection_string(input);
    if (std::none_of(parsed.values.begin(), parsed.values.end(), [](const auto& entry) {
            return entry.first.compare(0, 15, "OJP.CLIENT.TLS.") == 0;
        })) {
        return input;
    }
    std::string output;
    for (const auto& entry : parsed.values) {
        if (entry.first.compare(0, 15, "OJP.CLIENT.TLS.") != 0) {
            output += entry.first + "=" + brace_connection_value(entry.second) + ";";
        }
    }
    return output;
}

SQLRETURN connect_with_options(ConnectionHandle* connection, const std::string& source,
                               const std::string& user, const std::string& password) {
    // Standard SQLConnect form: the server name is a DSN name, not a connection string.
    if (source.find('=') == std::string::npos) {
        std::string dsn_connection = "DSN=" + brace_connection_value(source);
        if (!user.empty()) {
            dsn_connection += ";UID=" + brace_connection_value(user);
        }
        if (!password.empty()) {
            dsn_connection += ";PWD=" + brace_connection_value(password);
        }
        return connect(connection, dsn_connection);
    }
    const auto parsed = parse_connection_string(source);
    if (!parsed.error.empty()) {
        return fail(connection, parsed.error, "IM012");
    }
    if (parsed.values.find(ojp::odbc::dsn::KEY_DSN) == parsed.values.end() &&
        (parsed.values.find("SERVER") == parsed.values.end() ||
         parsed.values.find("DATABASE") == parsed.values.end())) {
        return fail(connection,
                    "SQLConnect requires a DSN name or SERVER and DATABASE options in its "
                    "server name; use SQLDriverConnect for standard DSN-less connections",
                    "IM002");
    }
    return connect(connection, source + ";UID=" + brace_connection_value(user) +
                                   ";PWD=" + brace_connection_value(password));
}

SQLRETURN disconnect(ConnectionHandle* connection) {
    clear_diagnostics(connection);
    if (connection == nullptr) {
        return SQL_INVALID_HANDLE;
    }
    std::lock_guard<std::mutex> connection_lock(connection->operation_mutex);
    if (!connection->connected || !connection->stub) {
        return fail(connection, "ODBC connection is not open", "08003");
    }
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    com::openjproxy::grpc::SessionTerminationStatus response;
    StatementService::Stub* stub = route_session_rpc(connection);
    const SessionInfo request_session = connection->session;
    const auto status = stub->terminateSession(&context, request_session, &response);
    // terminateSession is sent exactly once; the connection is unusable even if it fails.
    connection->connected = false;
    endpoint_runtime(connection->endpoint, connection->tls)->active_connections.fetch_sub(1);
    connection->stub.reset();
    connection->channel.reset();
    connection->savepoints.clear();
    connection->savepoint_names.clear();
    {
        std::lock_guard<std::mutex> routing_lock(connection->routing_mutex);
        connection->session_to_server.clear();
        connection->affinity_stubs.clear();
        connection->affinity_channels.clear();
    }
    connection->auto_commit = true;
    connection->xa_mode = false;
    connection->transaction_isolation = 0;
    if (!status.ok()) {
        return fail_grpc(connection, status, context);
    }
    if (!response.terminated()) {
        return fail(connection, "OJP server did not terminate the session", "HY000");
    }
    return SQL_SUCCESS;
}

SQLRETURN validate_xa_connection(ConnectionHandle* connection) {
    if (!connection->connected || !connection->stub) {
        return fail(connection, "ODBC connection is not open", "08003");
    }
    if (!connection->xa_mode || !connection->session.isxa()) {
        return fail(connection, "Connection was not opened in OJP XA mode", "HYC00");
    }
    return SQL_SUCCESS;
}

SQLRETURN encode_xid(const OjpXid* xid, HandleBase* handle, XidProto* proto) {
    if (xid == nullptr) {
        return fail(handle, "XA transaction identifier is required", "HY009");
    }
    if (xid->global_transaction_id_length > OJP_XA_MAX_GTRID_SIZE ||
        xid->branch_qualifier_length > OJP_XA_MAX_BQUAL_SIZE) {
        return fail(handle, "XA transaction identifier exceeds the 64-byte limit", "HY090");
    }
    proto->set_formatid(xid->format_id);
    proto->set_globaltransactionid(xid->global_transaction_id,
                                  xid->global_transaction_id_length);
    proto->set_branchqualifier(xid->branch_qualifier, xid->branch_qualifier_length);
    return SQL_SUCCESS;
}

bool decode_xid(const XidProto& proto, OjpXid* xid) {
    if (proto.globaltransactionid().size() > OJP_XA_MAX_GTRID_SIZE ||
        proto.branchqualifier().size() > OJP_XA_MAX_BQUAL_SIZE) {
        return false;
    }
    *xid = {};
    xid->format_id = proto.formatid();
    xid->global_transaction_id_length =
        static_cast<std::uint32_t>(proto.globaltransactionid().size());
    std::copy(proto.globaltransactionid().begin(), proto.globaltransactionid().end(),
              xid->global_transaction_id);
    xid->branch_qualifier_length =
        static_cast<std::uint32_t>(proto.branchqualifier().size());
    std::copy(proto.branchqualifier().begin(), proto.branchqualifier().end(),
              xid->branch_qualifier);
    return true;
}

template <typename Request, typename Response, typename Invoke>
SQLRETURN perform_xa_rpc(ConnectionHandle* connection, HandleBase* handle,
                         const SessionInfo& request_session, const Request& request,
                         Response* response, Invoke invoke, bool* transport_failure = nullptr) {
    if (transport_failure != nullptr) {
        *transport_failure = false;
    }
    const SQLRETURN valid = validate_xa_connection(connection);
    if (!SQL_SUCCEEDED(valid)) {
        return valid;
    }
    StatementService::Stub* stub = route_session_rpc(connection);
    if (stub == nullptr) {
        return fail(handle, "No OJP XA endpoint is available", "08S01", OJP_XA_XAER_RMFAIL);
    }
    const std::string target = connection->last_rpc_endpoint;
    if (!endpoint_runtime(target, connection->tls)->healthy.load()) {
        return fail(handle, "XA resource manager is unavailable; XA requests are not rerouted",
                    "08S01", OJP_XA_XAER_RMFAIL);
    }
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    const grpc::Status status = invoke(stub, &context, request, response);
    if (!status.ok()) {
        const bool is_transport = is_transport_failure(status, context);
        if (transport_failure != nullptr) {
            *transport_failure = is_transport;
        }
        note_transport_failure(connection, status, context);
        const SQLRETURN failure = fail_grpc(handle, status, context);
        if (is_transport && !handle->diagnostics.empty()) {
            handle->diagnostics.back().native_error = OJP_XA_XAER_RMFAIL;
        }
        return failure;
    }
    if constexpr (!std::is_same_v<Response, XaIsSameRMResponse>) {
        if (response->has_session()) {
            update_session(connection, response->session(), request_session);
        }
    }
    return SQL_SUCCESS;
}

bool reconnect_xa_for_start(ConnectionHandle* connection, HandleBase* handle,
                            const std::string& failed_endpoint) {
    const std::set<std::string> excluded{failed_endpoint};
    const std::string next_endpoint =
        choose_endpoint(connection->endpoints, connection->load_aware, connection->tls, excluded);
    if (next_endpoint.empty()) {
        fail(handle, "XA start failed on " + failed_endpoint +
                         " and no other healthy OJP server is available for failover", "08S01",
             OJP_XA_XAER_RMFAIL);
        return false;
    }
    log_message(LogLevel::warn, "XA failover from " + failed_endpoint + " to " + next_endpoint);
    auto endpoint = endpoint_runtime(next_endpoint, connection->tls);
    ConnectionDetails details(connection->connection_details);
    details.set_clusterhealth(cluster_health(connection->endpoints, connection->tls));
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    SessionInfo response;
    const grpc::Status status = endpoint->stub->connect(&context, details, &response);
    if (!status.ok()) {
        if (is_transport_failure(status, context)) {
            mark_endpoint_unhealthy(next_endpoint, connection->tls);
        }
        fail_grpc(handle, status, context);
        if (!handle->diagnostics.empty()) {
            handle->diagnostics.back().native_error = OJP_XA_XAER_RMFAIL;
        }
        return false;
    }
    if (!response.isxa()) {
        fail(handle, "OJP server did not establish an XA connection", "HY000",
             OJP_XA_XAER_RMERR);
        return false;
    }
    endpoint_runtime(connection->endpoint, connection->tls)->active_connections.fetch_sub(1);
    endpoint->active_connections.fetch_add(1);
    connection->endpoint = next_endpoint;
    connection->channel = endpoint->channel;
    connection->stub = endpoint->stub;
    connection->connection_details.CopyFrom(details);
    response.set_targetserver(next_endpoint);
    connection->session.CopyFrom(response);
    if (!response.sessionuuid().empty()) {
        std::lock_guard<std::mutex> routing_lock(connection->routing_mutex);
        connection->session_to_server[response.sessionuuid()] = next_endpoint;
    }
    return true;
}

}  // namespace

extern "C" {

SQLRETURN SQL_API OjpXAStart(SQLHDBC connection, const OjpXid* xid, SQLINTEGER flags) {
    return guarded(connection, "OjpXAStart", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        XidProto xid_proto;
        SQLRETURN result = encode_xid(xid, target, &xid_proto);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        for (int attempt = 0; attempt <= target->retry_attempts; ++attempt) {
            XaStartRequest request;
            request.mutable_session()->CopyFrom(target->session);
            request.mutable_xid()->CopyFrom(xid_proto);
            request.set_flags(flags);
            const SessionInfo request_session = request.session();
            XaResponse response;
            bool transport_failure = false;
            result = perform_xa_rpc(target, target, request_session, request, &response,
                [](StatementService::Stub* stub, grpc::ClientContext* context,
                   const XaStartRequest& xa_request, XaResponse* xa_response) {
                    return stub->xaStart(context, xa_request, xa_response);
                }, &transport_failure);
            if (SQL_SUCCEEDED(result)) {
                if (!response.success()) {
                    return fail(target, response.message().empty() ? "XA start failed" : response.message(),
                                "HY000", OJP_XA_XAER_RMERR);
                }
                return SQL_SUCCESS;
            }
            if (!transport_failure || !request_session.sessionuuid().empty() ||
                attempt == target->retry_attempts) {
                return result;
            }
            const std::string failed_endpoint = target->last_rpc_endpoint.empty()
                ? target->endpoint : target->last_rpc_endpoint;
            clear_diagnostics(target);
            if (!reconnect_xa_for_start(target, target, failed_endpoint)) {
                return SQL_ERROR;
            }
        }
        return result;
    });
}

SQLRETURN SQL_API OjpXAEnd(SQLHDBC connection, const OjpXid* xid, SQLINTEGER flags) {
    return guarded(connection, "OjpXAEnd", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        XidProto xid_proto;
        SQLRETURN result = encode_xid(xid, target, &xid_proto);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        XaEndRequest request;
        request.mutable_session()->CopyFrom(target->session);
        request.mutable_xid()->CopyFrom(xid_proto);
        request.set_flags(flags);
        XaResponse response;
        result = perform_xa_rpc(target, target, request.session(), request, &response,
            [](StatementService::Stub* stub, grpc::ClientContext* context,
               const XaEndRequest& xa_request, XaResponse* xa_response) {
                return stub->xaEnd(context, xa_request, xa_response);
            });
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        return response.success()
            ? SQL_SUCCESS
            : fail(target, response.message().empty() ? "XA end failed" : response.message(),
                   "HY000", OJP_XA_XAER_RMERR);
    });
}

SQLRETURN SQL_API OjpXAPrepare(SQLHDBC connection, const OjpXid* xid, SQLINTEGER* result_value) {
    return guarded(connection, "OjpXAPrepare", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        if (result_value == nullptr) {
            return fail(target, "XA prepare result output is required", "HY009");
        }
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        XidProto xid_proto;
        SQLRETURN result = encode_xid(xid, target, &xid_proto);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        XaPrepareRequest request;
        request.mutable_session()->CopyFrom(target->session);
        request.mutable_xid()->CopyFrom(xid_proto);
        XaPrepareResponse response;
        result = perform_xa_rpc(target, target, request.session(), request, &response,
            [](StatementService::Stub* stub, grpc::ClientContext* context,
               const XaPrepareRequest& xa_request, XaPrepareResponse* xa_response) {
                return stub->xaPrepare(context, xa_request, xa_response);
            });
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        if (response.result() != OJP_XA_XA_OK && response.result() != OJP_XA_XA_RDONLY) {
            return fail(target, "OJP returned an invalid XA prepare result", "HY000",
                        OJP_XA_XAER_RMERR);
        }
        *result_value = response.result();
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API OjpXACommit(SQLHDBC connection, const OjpXid* xid, SQLSMALLINT one_phase) {
    return guarded(connection, "OjpXACommit", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        if (one_phase != SQL_FALSE && one_phase != SQL_TRUE) {
            return fail(target, "one_phase must be SQL_TRUE or SQL_FALSE", "HY024");
        }
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        XidProto xid_proto;
        SQLRETURN result = encode_xid(xid, target, &xid_proto);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        XaCommitRequest request;
        request.mutable_session()->CopyFrom(target->session);
        request.mutable_xid()->CopyFrom(xid_proto);
        request.set_onephase(one_phase == SQL_TRUE);
        XaResponse response;
        result = perform_xa_rpc(target, target, request.session(), request, &response,
            [](StatementService::Stub* stub, grpc::ClientContext* context,
               const XaCommitRequest& xa_request, XaResponse* xa_response) {
                return stub->xaCommit(context, xa_request, xa_response);
            });
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        return response.success()
            ? SQL_SUCCESS
            : fail(target, response.message().empty() ? "XA commit failed" : response.message(),
                   "HY000", OJP_XA_XAER_RMERR);
    });
}

SQLRETURN SQL_API OjpXARollback(SQLHDBC connection, const OjpXid* xid) {
    return guarded(connection, "OjpXARollback", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        XidProto xid_proto;
        SQLRETURN result = encode_xid(xid, target, &xid_proto);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        XaRollbackRequest request;
        request.mutable_session()->CopyFrom(target->session);
        request.mutable_xid()->CopyFrom(xid_proto);
        XaResponse response;
        result = perform_xa_rpc(target, target, request.session(), request, &response,
            [](StatementService::Stub* stub, grpc::ClientContext* context,
               const XaRollbackRequest& xa_request, XaResponse* xa_response) {
                return stub->xaRollback(context, xa_request, xa_response);
            });
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        return response.success()
            ? SQL_SUCCESS
            : fail(target, response.message().empty() ? "XA rollback failed" : response.message(),
                   "HY000", OJP_XA_XAER_RMERR);
    });
}

SQLRETURN SQL_API OjpXARecover(SQLHDBC connection, SQLINTEGER flags, OjpXid* xids,
                               SQLSMALLINT capacity, SQLSMALLINT* count) {
    return guarded(connection, "OjpXARecover", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        if (count == nullptr || capacity < 0 || (capacity > 0 && xids == nullptr)) {
            return fail(target, "XA recovery output buffer and capacity are invalid", "HY009");
        }
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        XaRecoverRequest request;
        request.mutable_session()->CopyFrom(target->session);
        request.set_flag(flags);
        XaRecoverResponse response;
        SQLRETURN result = perform_xa_rpc(target, target, request.session(), request, &response,
            [](StatementService::Stub* stub, grpc::ClientContext* context,
               const XaRecoverRequest& xa_request, XaRecoverResponse* xa_response) {
                return stub->xaRecover(context, xa_request, xa_response);
            });
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        if (response.xids_size() > std::numeric_limits<SQLSMALLINT>::max()) {
            return fail(target, "OJP returned too many XA recovery identifiers", "HY000");
        }
        *count = static_cast<SQLSMALLINT>(response.xids_size());
        if (response.xids_size() > capacity) {
            return fail(target, "XA recovery output buffer is too small", "HY090");
        }
        for (int index = 0; index < response.xids_size(); ++index) {
            if (!decode_xid(response.xids(index), &xids[index])) {
                return fail(target, "OJP returned an oversized XA transaction identifier", "HY000");
            }
        }
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API OjpXAForget(SQLHDBC connection, const OjpXid* xid) {
    return guarded(connection, "OjpXAForget", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        XidProto xid_proto;
        SQLRETURN result = encode_xid(xid, target, &xid_proto);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        XaForgetRequest request;
        request.mutable_session()->CopyFrom(target->session);
        request.mutable_xid()->CopyFrom(xid_proto);
        XaResponse response;
        result = perform_xa_rpc(target, target, request.session(), request, &response,
            [](StatementService::Stub* stub, grpc::ClientContext* context,
               const XaForgetRequest& xa_request, XaResponse* xa_response) {
                return stub->xaForget(context, xa_request, xa_response);
            });
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        return response.success()
            ? SQL_SUCCESS
            : fail(target, response.message().empty() ? "XA forget failed" : response.message(),
                   "HY000", OJP_XA_XAER_RMERR);
    });
}

SQLRETURN SQL_API OjpXASetTransactionTimeout(SQLHDBC connection, SQLINTEGER seconds,
                                             SQLSMALLINT* success) {
    return guarded(connection, "OjpXASetTransactionTimeout", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        if (seconds < 0 || success == nullptr) {
            return fail(target, "XA timeout must be non-negative and have an output", "HY024");
        }
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        XaSetTransactionTimeoutRequest request;
        request.mutable_session()->CopyFrom(target->session);
        request.set_seconds(seconds);
        XaSetTransactionTimeoutResponse response;
        const SQLRETURN result = perform_xa_rpc(target, target, request.session(), request, &response,
            [](StatementService::Stub* stub, grpc::ClientContext* context,
               const XaSetTransactionTimeoutRequest& xa_request,
               XaSetTransactionTimeoutResponse* xa_response) {
                return stub->xaSetTransactionTimeout(context, xa_request, xa_response);
            });
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        *success = response.success() ? SQL_TRUE : SQL_FALSE;
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API OjpXAGetTransactionTimeout(SQLHDBC connection, SQLINTEGER* seconds) {
    return guarded(connection, "OjpXAGetTransactionTimeout", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        if (seconds == nullptr) {
            return fail(target, "XA timeout output is required", "HY009");
        }
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        XaGetTransactionTimeoutRequest request;
        request.mutable_session()->CopyFrom(target->session);
        XaGetTransactionTimeoutResponse response;
        const SQLRETURN result = perform_xa_rpc(target, target, request.session(), request, &response,
            [](StatementService::Stub* stub, grpc::ClientContext* context,
               const XaGetTransactionTimeoutRequest& xa_request,
               XaGetTransactionTimeoutResponse* xa_response) {
                return stub->xaGetTransactionTimeout(context, xa_request, xa_response);
            });
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        *seconds = response.seconds();
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API OjpXAIsSameRM(SQLHDBC connection, SQLHDBC other_connection,
                                SQLSMALLINT* same_resource_manager) {
    return guarded(connection, "OjpXAIsSameRM", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC || other_connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC ||
            static_cast<HandleBase*>(other_connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        auto* other = static_cast<ConnectionHandle*>(other_connection);
        clear_diagnostics(target);
        if (same_resource_manager == nullptr) {
            return fail(target, "XA resource-manager comparison output is required", "HY009");
        }
        const auto operation = [&]() -> SQLRETURN {
            SQLRETURN result = validate_xa_connection(target);
            if (!SQL_SUCCEEDED(result)) {
                return result;
            }
            if (!other->connected || !other->stub || !other->xa_mode || !other->session.isxa()) {
                return fail(target, "Both connections must be open OJP XA connections", "HYC00");
            }
            XaIsSameRMRequest request;
            request.mutable_session1()->CopyFrom(target->session);
            request.mutable_session2()->CopyFrom(other->session);
            XaIsSameRMResponse response;
            result = perform_xa_rpc(target, target, request.session1(), request, &response,
                [](StatementService::Stub* stub, grpc::ClientContext* context,
                   const XaIsSameRMRequest& xa_request, XaIsSameRMResponse* xa_response) {
                    return stub->xaIsSameRM(context, xa_request, xa_response);
                });
            if (SQL_SUCCEEDED(result)) {
                *same_resource_manager = response.issame() ? SQL_TRUE : SQL_FALSE;
            }
            return result;
        };
        if (target == other) {
            std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
            return operation();
        }
        std::scoped_lock connection_locks(target->operation_mutex, other->operation_mutex);
        return operation();
    });
}

SQLRETURN SQL_API SQLAllocHandle(SQLSMALLINT handle_type, SQLHANDLE input_handle,
                                 SQLHANDLE* output_handle) {
    return guarded(input_handle, "SQLAllocHandle", [&]() -> SQLRETURN {
        if (output_handle == nullptr) {
            return fail(static_cast<HandleBase*>(input_handle),
                        "SQLAllocHandle output handle pointer is required", "HY009");
        }
        *output_handle = SQL_NULL_HANDLE;
        if (handle_type == SQL_HANDLE_ENV) {
            *output_handle = new EnvironmentHandle();
            return SQL_SUCCESS;
        }
        if (handle_type == SQL_HANDLE_DBC && input_handle != SQL_NULL_HANDLE &&
            static_cast<HandleBase*>(input_handle)->type == SQL_HANDLE_ENV) {
            *output_handle = new ConnectionHandle();
            return SQL_SUCCESS;
        }
        if (handle_type == SQL_HANDLE_STMT && input_handle != SQL_NULL_HANDLE &&
            static_cast<HandleBase*>(input_handle)->type == SQL_HANDLE_DBC) {
            auto* connection = static_cast<ConnectionHandle*>(input_handle);
            *output_handle = new StatementHandle(connection);
            return SQL_SUCCESS;
        }
        if (handle_type != SQL_HANDLE_DBC && handle_type != SQL_HANDLE_STMT) {
            return fail(static_cast<HandleBase*>(input_handle),
                        "SQLAllocHandle handle type " + std::to_string(handle_type) +
                            " is not supported (descriptors cannot be allocated explicitly)",
                        "HY092");
        }
        return SQL_INVALID_HANDLE;
    });
}

SQLRETURN SQL_API SQLFreeHandle(SQLSMALLINT handle_type, SQLHANDLE handle) {
    if (handle == SQL_NULL_HANDLE || static_cast<HandleBase*>(handle)->type != handle_type) {
        return SQL_INVALID_HANDLE;
    }
    if (handle_type == SQL_HANDLE_ENV) {
        delete static_cast<EnvironmentHandle*>(handle);
    } else if (handle_type == SQL_HANDLE_DBC) {
        auto* connection = static_cast<ConnectionHandle*>(handle);
        if (connection->connected) {
            const auto result = disconnect(connection);
            if (!SQL_SUCCEEDED(result)) {
                return result;
            }
        }
        delete connection;
    } else if (handle_type == SQL_HANDLE_STMT) {
        delete static_cast<StatementHandle*>(handle);
    } else {
        return SQL_INVALID_HANDLE;
    }
    return SQL_SUCCESS;
}

SQLRETURN SQL_API SQLSetEnvAttr(SQLHENV environment, SQLINTEGER attribute,
                                SQLPOINTER value, SQLINTEGER) {
    return guarded(environment, "SQLSetEnvAttr", [&]() -> SQLRETURN {
        if (environment == SQL_NULL_HENV ||
            static_cast<HandleBase*>(environment)->type != SQL_HANDLE_ENV) {
            return SQL_INVALID_HANDLE;
        }
        clear_diagnostics(static_cast<HandleBase*>(environment));
        if (attribute == SQL_ATTR_ODBC_VERSION &&
            (reinterpret_cast<std::uintptr_t>(value) == SQL_OV_ODBC3 ||
             reinterpret_cast<std::uintptr_t>(value) == SQL_OV_ODBC3_80)) {
            return SQL_SUCCESS;
        }
        return fail(static_cast<HandleBase*>(environment),
                    "Only ODBC 3.x environments are supported", "HY024");
    });
}

SQLRETURN SQL_API SQLDriverConnect(SQLHDBC connection, SQLHWND, SQLCHAR* input_string,
                                   SQLSMALLINT input_length, SQLCHAR* output_string,
                                   SQLSMALLINT output_buffer_length,
                                   SQLSMALLINT* output_length, SQLUSMALLINT) {
    return guarded(connection, "SQLDriverConnect", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        const auto* input = reinterpret_cast<const char*>(input_string);
        if (input == nullptr) {
            return fail(static_cast<HandleBase*>(connection), "Connection string is required", "HY009");
        }
        const auto length = input_length == SQL_NTS
            ? std::strlen(input)
            : static_cast<std::size_t>(std::max<SQLSMALLINT>(0, input_length));
        const auto status = connect(static_cast<ConnectionHandle*>(connection),
                                    std::string(input, length));
        if (!SQL_SUCCEEDED(status)) {
            return status;
        }
        const auto output = public_connection_string(std::string(input, length));
        if (output_length != nullptr) {
            *output_length = static_cast<SQLSMALLINT>(output.size());
        }
        if (output_string == nullptr || output_buffer_length <= 0) {
            return SQL_SUCCESS;
        }
        const auto copy_count = std::min<std::size_t>(
            output.size(), static_cast<std::size_t>(output_buffer_length - 1));
        std::memcpy(output_string, output.data(), copy_count);
        output_string[copy_count] = '\0';
        if (copy_count < output.size()) {
            static_cast<HandleBase*>(connection)->diagnostics.push_back(
                {"01004", 0, "Connection string output was truncated"});
            return SQL_SUCCESS_WITH_INFO;
        }
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLDriverConnectW(SQLHDBC connection, SQLHWND, SQLWCHAR* input_string,
                                    SQLSMALLINT input_length, SQLWCHAR* output_string,
                                    SQLSMALLINT output_buffer_length,
                                    SQLSMALLINT* output_length, SQLUSMALLINT) {
    return guarded(connection, "SQLDriverConnectW", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        if (input_string == nullptr) {
            return fail(target, "Connection string is required", "HY009");
        }
        if (input_length < 0 && input_length != SQL_NTS) {
            return fail(target, "Connection string length is invalid", "HY090");
        }
        std::string input;
        if (!read_wide_string(input_string, input_length, &input)) {
            return fail(target, "Connection string contains invalid wide-character Unicode", "22018");
        }
        const auto status = connect(target, input);
        if (!SQL_SUCCEEDED(status)) {
            return status;
        }
        return write_wide_string(target, public_connection_string(input),
                                 output_string, output_buffer_length, output_length);
    });
}

SQLRETURN SQL_API SQLConnect(SQLHDBC connection, SQLCHAR* server_name,
                             SQLSMALLINT server_name_length, SQLCHAR* user_name,
                             SQLSMALLINT user_name_length, SQLCHAR* authentication,
                             SQLSMALLINT authentication_length) {
    return guarded(connection, "SQLConnect", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        if (server_name == nullptr) {
            return fail(target, "SQLConnect server name is required", "HY009");
        }
        const auto ansi_value = [](SQLCHAR* value, SQLSMALLINT length) {
            if (value == nullptr) {
                return std::string{};
            }
            const auto* text = reinterpret_cast<const char*>(value);
            const auto size = length == SQL_NTS
                ? std::strlen(text)
                : static_cast<std::size_t>(std::max<SQLSMALLINT>(0, length));
            return std::string(text, size);
        };
        if ((server_name_length < 0 && server_name_length != SQL_NTS) ||
            (user_name_length < 0 && user_name_length != SQL_NTS) ||
            (authentication_length < 0 && authentication_length != SQL_NTS)) {
            return fail(target, "SQLConnect string length is invalid", "HY090");
        }
        return connect_with_options(target, ansi_value(server_name, server_name_length),
                                    ansi_value(user_name, user_name_length),
                                    ansi_value(authentication, authentication_length));
    });
}

SQLRETURN SQL_API SQLConnectW(SQLHDBC connection, SQLWCHAR* server_name,
                              SQLSMALLINT server_name_length, SQLWCHAR* user_name,
                              SQLSMALLINT user_name_length, SQLWCHAR* authentication,
                              SQLSMALLINT authentication_length) {
    return guarded(connection, "SQLConnectW", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        std::string endpoint;
        std::string user;
        std::string password;
        if (server_name == nullptr) {
            return fail(target, "SQLConnect server name is required", "HY009");
        }
        if ((server_name_length < 0 && server_name_length != SQL_NTS) ||
            (user_name_length < 0 && user_name_length != SQL_NTS) ||
            (authentication_length < 0 && authentication_length != SQL_NTS)) {
            return fail(target, "SQLConnect string length is invalid", "HY090");
        }
        if (!read_wide_string(server_name, server_name_length, &endpoint) ||
            (user_name != nullptr && !read_wide_string(user_name, user_name_length, &user)) ||
            (authentication != nullptr &&
             !read_wide_string(authentication, authentication_length, &password))) {
            return fail(target, "SQLConnect contains invalid wide-character Unicode", "22018");
        }
        return connect_with_options(target, endpoint, user, password);
    });
}

SQLRETURN SQL_API SQLDisconnect(SQLHDBC connection) {
    return guarded(connection, "SQLDisconnect", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        return disconnect(static_cast<ConnectionHandle*>(connection));
    });
}

// Element size of a column-wise bound parameter array (fixed-size types
// ignore buffer_length, as per the ODBC specification).
static SQLLEN parameter_element_size(const BoundParameter& bound) {
    switch (bound.value_type) {
        case SQL_C_TINYINT: case SQL_C_STINYINT: case SQL_C_UTINYINT: case SQL_C_BIT:
            return 1;
        case SQL_C_SHORT: case SQL_C_SSHORT: case SQL_C_USHORT:
            return sizeof(SQLSMALLINT);
        case SQL_C_LONG: case SQL_C_SLONG: case SQL_C_ULONG:
            return sizeof(SQLINTEGER);
        case SQL_C_SBIGINT: case SQL_C_UBIGINT:
            return sizeof(SQLBIGINT);
        case SQL_C_FLOAT: return sizeof(float);
        case SQL_C_DOUBLE: return sizeof(double);
        case SQL_C_NUMERIC: return sizeof(SQL_NUMERIC_STRUCT);
        case SQL_C_TYPE_DATE: return sizeof(SQL_DATE_STRUCT);
        case SQL_C_TYPE_TIME: return sizeof(SQL_TIME_STRUCT);
        case SQL_C_TYPE_TIMESTAMP: return sizeof(SQL_TIMESTAMP_STRUCT);
        default: return bound.buffer_length;
    }
}

static void report_parameter_row(StatementHandle* target, SQLULEN row, SQLRETURN result) {
    if (target->param_status_ptr != nullptr) {
        target->param_status_ptr[row] = static_cast<SQLUSMALLINT>(
            result == SQL_SUCCESS ? SQL_PARAM_SUCCESS
            : result == SQL_SUCCESS_WITH_INFO ? SQL_PARAM_SUCCESS_WITH_INFO : SQL_PARAM_ERROR);
    }
}

// Parameter bindings persist across SQLPrepare/SQLExecDirect (only SQL_RESET_PARAMS clears them).
// Also handles SQL_ATTR_PARAMSET_SIZE > 1 by executing once per array row and fills
// SQL_ATTR_PARAMS_PROCESSED_PTR / SQL_ATTR_PARAM_STATUS_PTR.
static SQLRETURN execute_and_report(StatementHandle* target) {
    const auto paramset = target->stored_attributes.find(SQL_ATTR_PARAMSET_SIZE);
    const SQLULEN rows = paramset == target->stored_attributes.end() ? 1 : paramset->second;
    if (rows <= 1 || target->parameters.empty()) {
        const SQLRETURN result = execute_statement(target);
        if (result == SQL_NEED_DATA) {
            return result;
        }
        if (target->params_processed_ptr != nullptr) {
            *target->params_processed_ptr = 1;
        }
        report_parameter_row(target, 0, result);
        return result;
    }
    const auto original = target->parameters;
    for (const auto& entry : original) {
        if (is_data_at_execution(entry.second)) {
            return fail(target, "Data-at-execution parameters cannot be combined with "
                        "SQL_ATTR_PARAMSET_SIZE = " + std::to_string(rows), "HYC00");
        }
        if (entry.second.value != nullptr && parameter_element_size(entry.second) <= 0) {
            return fail(target, "Parameter " + std::to_string(entry.first) +
                        " needs a positive BufferLength to be used in a parameter array "
                        "(SQL_ATTR_PARAMSET_SIZE = " + std::to_string(rows) + ")", "HY090");
        }
    }
    log_message(LogLevel::debug, "executing parameter array with " + std::to_string(rows) +
                " row(s), one round-trip per row");
    SQLLEN total_rows = 0;
    bool any_count = false;
    SQLULEN failed = 0;
    bool any_info = false;
    std::vector<Diagnostic> errors;
    SQLULEN processed = 0;
    for (SQLULEN row = 0; row < rows; ++row) {
        target->parameters = original;
        for (auto& entry : target->parameters) {
            auto& bound = entry.second;
            if (bound.value != nullptr) {
                bound.value = static_cast<std::uint8_t*>(bound.value) +
                    static_cast<std::size_t>(row) * static_cast<std::size_t>(parameter_element_size(bound));
            }
            if (bound.indicator != nullptr) {
                bound.indicator += row;
            }
        }
        const SQLRETURN result = execute_statement(target);
        ++processed;
        report_parameter_row(target, row, result);
        if (!SQL_SUCCEEDED(result)) {
            ++failed;
            for (auto diagnostic : target->diagnostics) {
                diagnostic.message = "[parameter row " + std::to_string(row + 1) + "] " +
                    diagnostic.message;
                errors.push_back(std::move(diagnostic));
            }
            continue;
        }
        any_info = any_info || result == SQL_SUCCESS_WITH_INFO;
        if (target->row_count >= 0) {
            total_rows += target->row_count;
            any_count = true;
        }
    }
    target->parameters = original;
    target->row_count = any_count ? total_rows : -1;
    if (target->params_processed_ptr != nullptr) {
        *target->params_processed_ptr = processed;
    }
    target->diagnostics = std::move(errors);
    if (failed > 0) {
        // With a status array the application can identify the failed rows; without it, execution fails.
        return target->param_status_ptr != nullptr && failed < processed
            ? SQL_SUCCESS_WITH_INFO : SQL_ERROR;
    }
    return any_info ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
}

SQLRETURN SQL_API SQLExecDirect(SQLHSTMT statement, SQLCHAR* sql, SQLINTEGER length) {
    return guarded(statement, "SQLExecDirect", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        if (sql == nullptr) {
            return fail(static_cast<HandleBase*>(statement), "SQL text is required", "HY009");
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        reset_data_at_execution(target);
        target->sql.assign(reinterpret_cast<const char*>(sql),
            length == SQL_NTS ? std::strlen(reinterpret_cast<const char*>(sql))
                              : static_cast<std::size_t>(std::max<SQLINTEGER>(0, length)));
        return execute_and_report(target);
    });
}

SQLRETURN SQL_API SQLExecDirectW(SQLHSTMT statement, SQLWCHAR* sql, SQLINTEGER length) {
    return guarded(statement, "SQLExecDirectW", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        reset_data_at_execution(target);
        if (sql == nullptr) {
            return fail(target, "SQL text is required", "HY009");
        }
        if (length < 0 && length != SQL_NTS) {
            return fail(target, "SQL text length is invalid", "HY090");
        }
        if (!read_wide_string(sql, length, &target->sql)) {
            return fail(target, "SQL text contains invalid wide-character Unicode", "22018");
        }
        return execute_and_report(target);
    });
}

SQLRETURN SQL_API SQLPrepare(SQLHSTMT statement, SQLCHAR* sql, SQLINTEGER length) {
    return guarded(statement, "SQLPrepare", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        if (sql == nullptr) {
            return fail(static_cast<HandleBase*>(statement), "SQL text is required", "HY009");
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        reset_data_at_execution(target);
        target->sql.assign(reinterpret_cast<const char*>(sql),
            length == SQL_NTS ? std::strlen(reinterpret_cast<const char*>(sql))
                              : static_cast<std::size_t>(std::max<SQLINTEGER>(0, length)));
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLPrepareW(SQLHSTMT statement, SQLWCHAR* sql, SQLINTEGER length) {
    return guarded(statement, "SQLPrepareW", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        reset_data_at_execution(target);
        if (sql == nullptr) {
            return fail(target, "SQL text is required", "HY009");
        }
        if (length < 0 && length != SQL_NTS) {
            return fail(target, "SQL text length is invalid", "HY090");
        }
        if (!read_wide_string(sql, length, &target->sql)) {
            return fail(target, "SQL text contains invalid wide-character Unicode", "22018");
        }
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLBindParameter(SQLHSTMT statement, SQLUSMALLINT parameter_number,
                                   SQLSMALLINT input_output_type, SQLSMALLINT value_type,
                                   SQLSMALLINT parameter_type, SQLULEN column_size,
                                   SQLSMALLINT decimal_digits, SQLPOINTER value,
                                   SQLLEN buffer_length, SQLLEN* indicator) {
    return guarded(statement, "SQLBindParameter", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        log_message(LogLevel::debug, "#" + std::to_string(parameter_number) +
                    " io=" + std::to_string(input_output_type) + " C type=" + std::to_string(value_type) +
                    " SQL type=" + std::to_string(parameter_type) + " size=" + std::to_string(column_size) +
                    " digits=" + std::to_string(decimal_digits) + " buffer=" + std::to_string(buffer_length));
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        if (!target->data_at_execution_parameters.empty()) {
            return fail(target, "Cannot bind a parameter while data-at-execution is active", "HY010");
        }
        if (parameter_number == 0) {
            return fail(target, "ODBC parameter numbers start at 1", "07009");
        }
        if (input_output_type != SQL_PARAM_INPUT) {
            return fail(target, "Only input parameters are supported", "HYC00");
        }
        target->parameters[parameter_number] = {
            input_output_type, value_type, parameter_type, value, buffer_length, indicator};
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLBindCol(SQLHSTMT statement, SQLUSMALLINT column_number,
                             SQLSMALLINT target_type, SQLPOINTER target_value,
                             SQLLEN buffer_length, SQLLEN* indicator) {
    return guarded(statement, "SQLBindCol", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        clear_diagnostics(target);
        if (column_number == 0) {
            return fail(target, "ODBC column numbers start at 1", "07009");
        }
        if (target_value == nullptr) {
            target->bound_columns.erase(column_number);
            return SQL_SUCCESS;
        }
        target->bound_columns[column_number] = {
            target_type, target_value, buffer_length, indicator};
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLExecute(SQLHSTMT statement) {
    return guarded(statement, "SQLExecute", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        if (target->waiting_for_data) {
            return fail(target, "Data-at-execution is already active", "HY010");
        }
        reset_data_at_execution(target);
        for (const auto& entry : target->parameters) {
            if (is_data_at_execution(entry.second)) {
                if (!is_lob_parameter_type(entry.second.parameter_type)) {
                    return fail(target, "Data-at-execution is supported only for LOB parameters",
                                "HYC00");
                }
                target->data_at_execution_parameters.push_back(entry.first);
            }
        }
        if (!target->data_at_execution_parameters.empty()) {
            target->waiting_for_data = true;
            return SQL_NEED_DATA;
        }
        return execute_and_report(target);
    });
}

SQLRETURN SQL_API SQLCancel(SQLHSTMT statement) {
    return guarded(statement, "SQLCancel", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        if (!target->waiting_for_data) {
            return fail(target, "Only buffered data-at-execution cancellation is supported", "HYC00");
        }
        // Data-at-execution is buffered locally; cancellation must discard it before any RPC.
        reset_data_at_execution(target);
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLParamData(SQLHSTMT statement, SQLPOINTER* value) {
    return guarded(statement, "SQLParamData", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        if (!target->waiting_for_data) {
            return fail(target, "No data-at-execution operation is active", "HY010");
        }
        if (value == nullptr) {
            return fail(target, "Data-at-execution token output is required", "HY009");
        }
        if (target->current_data_at_execution_parameter != 0) {
            const auto previous = target->parameters.find(
                target->current_data_at_execution_parameter);
            if (previous == target->parameters.end()) {
                return fail(target, "Data-at-execution parameter is no longer bound", "HY010");
            }
            target->current_data_at_execution_parameter = 0;
        }
        if (target->data_at_execution_index < target->data_at_execution_parameters.size()) {
            const auto parameter_number =
                target->data_at_execution_parameters[target->data_at_execution_index++];
            const auto parameter = target->parameters.find(parameter_number);
            if (parameter == target->parameters.end()) {
                return fail(target, "Data-at-execution parameter is no longer bound", "HY010");
            }
            target->current_data_at_execution_parameter = parameter_number;
            *value = parameter->second.value;
            return SQL_NEED_DATA;
        }

        target->waiting_for_data = false;
        target->data_at_execution_parameters.clear();
        target->data_at_execution_index = 0;
        return execute_and_report(target);
    });
}

SQLRETURN SQL_API SQLPutData(SQLHSTMT statement, SQLPOINTER data, SQLLEN length) {
    return guarded(statement, "SQLPutData", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        if (!target->waiting_for_data || target->current_data_at_execution_parameter == 0) {
            return fail(target, "SQLParamData must request a parameter before SQLPutData", "HY010");
        }
        auto parameter = target->parameters.find(target->current_data_at_execution_parameter);
        if (parameter == target->parameters.end()) {
            return fail(target, "Data-at-execution parameter is no longer bound", "HY010");
        }
        auto& bound = parameter->second;
        if (length == SQL_NTS) {
            if ((bound.value_type != SQL_C_CHAR && bound.value_type != SQL_C_WCHAR) ||
                data == nullptr) {
                return fail(target, "SQL_NTS is valid only for character LOB data", "HY090");
            }
            if (bound.value_type == SQL_C_WCHAR) {
                const auto* characters = static_cast<const SQLWCHAR*>(data);
                std::size_t units = 0;
                while (characters[units] != 0) {
                    ++units;
                }
                length = static_cast<SQLLEN>(units * sizeof(SQLWCHAR));
            } else {
                length = static_cast<SQLLEN>(std::strlen(static_cast<const char*>(data)));
            }
        }
        if (length < 0) {
            return fail(target, "LOB stream chunk length is invalid", "HY090");
        }
        if (length > 0 && data == nullptr) {
            return fail(target, "LOB stream chunk buffer is required", "HY009");
        }
        if (bound.value_type == SQL_C_WCHAR && length % sizeof(SQLWCHAR) != 0) {
            return fail(target, "Wide-character LOB chunk length must align to SQLWCHAR units", "HY090");
        }
        if (length > 0) {
            auto& parameter = bound;
            constexpr std::size_t kMaxLobSize =
                static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max());
            if (static_cast<std::size_t>(length) > kMaxLobSize - parameter.streamed_data.size()) {
                return fail(target, "LOB stream exceeds the supported size", "22001");
            }
            parameter.streamed_data.append(static_cast<const char*>(data),
                                           static_cast<std::size_t>(length));
        }
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLFetch(SQLHSTMT statement) {
    return guarded(statement, "SQLFetch", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        if (!target->has_result_set) {
            return fail(target, "Statement does not have a result set", "24000");
        }
        if (target->row_index >= target->rows.size()) {
            if (target->rows_fetched_ptr != nullptr) {
                *target->rows_fetched_ptr = 0;
            }
            return SQL_NO_DATA;
        }
        ++target->row_index;
        target->get_data_offsets.clear();
        if (target->rows_fetched_ptr != nullptr) {
            *target->rows_fetched_ptr = 1;
        }
        if (target->row_status_ptr != nullptr) {
            target->row_status_ptr[0] = SQL_ROW_SUCCESS;
        }
        SQLRETURN result = SQL_SUCCESS;
        const auto& row = target->rows[target->row_index - 1];
        for (const auto& binding : target->bound_columns) {
            if (binding.first == 0 || binding.first > row.size()) {
                return fail(target, "Bound column number exceeds the result column count", "07009");
            }
            const auto& column = binding.second;
            Cell materialized_cell;
            const auto lob_result = materialize_lob_cell(target, row[binding.first - 1],
                                                         column.value_type, &materialized_cell);
            if (!SQL_SUCCEEDED(lob_result)) {
                return lob_result;
            }
            const auto column_result = write_cell(target, materialized_cell,
                column.value_type, column.value, column.buffer_length, column.indicator);
            if (!SQL_SUCCEEDED(column_result)) {
                return column_result;
            }
            if (column_result == SQL_SUCCESS_WITH_INFO) {
                result = SQL_SUCCESS_WITH_INFO;
            }
        }
        return result;
    });
}

SQLRETURN SQL_API SQLGetData(SQLHSTMT statement, SQLUSMALLINT column_number,
                             SQLSMALLINT target_type, SQLPOINTER target_value,
                             SQLLEN buffer_length, SQLLEN* indicator) {
    return guarded(statement, "SQLGetData", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        if (target->row_index == 0 || target->row_index > target->rows.size() ||
            column_number == 0 || column_number > target->rows[target->row_index - 1].size()) {
            return fail(target, "No current row or invalid column number", "07009");
        }
        const Cell& cell = target->rows[target->row_index - 1][column_number - 1];
        Cell materialized_cell;
        const Cell* result_cell = &cell;
        const auto* original_text = std::get_if<std::string>(&cell);
        if (original_text != nullptr &&
            (original_text->compare(0, std::strlen("OJP_CLOB_PREFIX:"), "OJP_CLOB_PREFIX:") == 0 ||
             (target_type == SQL_C_BINARY && is_uuid(*original_text)))) {
            const auto lob_result = materialize_lob_cell(target, cell, target_type, &materialized_cell);
            if (!SQL_SUCCEEDED(lob_result)) {
                return lob_result;
            }
            result_cell = &materialized_cell;
        }
        if (target_type == SQL_C_BINARY || target_type == SQL_C_CHAR ||
            target_type == SQL_C_WCHAR) {
            const auto* bytes = std::get_if<std::vector<std::uint8_t>>(result_cell);
            const auto* text = std::get_if<std::string>(result_cell);
            if (bytes != nullptr && target_type == SQL_C_WCHAR) {
                return fail(target, "Binary result cannot be converted to SQL_C_WCHAR", "07006");
            }
            if ((bytes != nullptr && target_type != SQL_C_WCHAR) ||
                ((target_type == SQL_C_CHAR || target_type == SQL_C_WCHAR) && text != nullptr)) {
                std::vector<SQLWCHAR> wide_text;
                if (target_type == SQL_C_WCHAR && text != nullptr &&
                    !utf8_to_wide(*text, &wide_text)) {
                    return fail(target, "Result text is not valid UTF-8", "22018");
                }
                const std::size_t size = target_type == SQL_C_WCHAR
                    ? wide_text.size() : (bytes != nullptr ? bytes->size() : text->size());
                const void* data = bytes != nullptr
                    ? static_cast<const void*>(bytes->data())
                    : target_type == SQL_C_WCHAR
                        ? static_cast<const void*>(wide_text.data())
                        : static_cast<const void*>(text->data());
                const auto offset = target->get_data_offsets[column_number];
                if (offset > size) {
                    return SQL_NO_DATA;
                }
                if (target_value == nullptr) {
                    return fail(target, "Output buffer is required", "HY009");
                }
                if (buffer_length < 0) {
                    return fail(target, "Invalid binary output buffer length", "HY090");
                }
                const auto unit_size = target_type == SQL_C_WCHAR ? sizeof(SQLWCHAR) : 1;
                const auto capacity = target_type == SQL_C_CHAR
                    ? (buffer_length > 0 ? static_cast<std::size_t>(buffer_length - 1) : 0)
                    : target_type == SQL_C_WCHAR
                        ? (static_cast<std::size_t>(buffer_length) / unit_size > 0
                            ? static_cast<std::size_t>(buffer_length) / unit_size - 1 : 0)
                        : static_cast<std::size_t>(buffer_length);
                const auto available = size - offset;
                auto copy_count = std::min(capacity, available);
                if (target_type == SQL_C_WCHAR && copy_count > 0 &&
                    sizeof(SQLWCHAR) == 2 && offset + copy_count < size) {
                    const auto last = static_cast<std::uint32_t>(
                        wide_text[offset + copy_count - 1]);
                    if (last >= 0xd800 && last <= 0xdbff) {
                        --copy_count;
                    }
                }
                if (copy_count > 0) {
                    std::memcpy(target_value, static_cast<const char*>(data) + offset * unit_size,
                                copy_count * unit_size);
                }
                if (target_type == SQL_C_CHAR && buffer_length > 0) {
                    static_cast<char*>(target_value)[copy_count] = '\0';
                } else if (target_type == SQL_C_WCHAR &&
                           static_cast<std::size_t>(buffer_length) / unit_size > 0) {
                    static_cast<SQLWCHAR*>(target_value)[copy_count] = 0;
                }
                if (indicator != nullptr) {
                    *indicator = static_cast<SQLLEN>(available * unit_size);
                }
                target->get_data_offsets[column_number] =
                    copy_count < available ? offset + copy_count : size + 1;
                if (copy_count < available) {
                    target->diagnostics.push_back(
                        {"01004", 0, "Result value was truncated"});
                    return SQL_SUCCESS_WITH_INFO;
                }
                return SQL_SUCCESS;
            }
        }
        return write_cell(target, *result_cell, target_type, target_value,
                          buffer_length, indicator);
    });
}

SQLRETURN SQL_API SQLNumResultCols(SQLHSTMT statement, SQLSMALLINT* column_count) {
    return guarded(statement, "SQLNumResultCols", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        if (column_count == nullptr) {
            return fail(static_cast<HandleBase*>(statement), "Column count output is required", "HY009");
        }
        *column_count = static_cast<SQLSMALLINT>(
            static_cast<StatementHandle*>(statement)->columns.size());
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLNumParams(SQLHSTMT statement, SQLSMALLINT* parameter_count) {
    return guarded(statement, "SQLNumParams", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        if (parameter_count == nullptr) {
            return fail(target, "Parameter count output is required", "HY009");
        }
        if (target->sql.empty()) {
            return fail(target, "Statement has not been prepared", "HY010");
        }
        const auto count = count_parameter_markers(target->sql);
        if (count > static_cast<std::size_t>(std::numeric_limits<SQLSMALLINT>::max())) {
            return fail(target, "Parameter count exceeds SQLSMALLINT range", "22003");
        }
        *parameter_count = static_cast<SQLSMALLINT>(count);
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLTables(SQLHSTMT statement, SQLCHAR* catalog, SQLSMALLINT catalog_length,
                            SQLCHAR* schema, SQLSMALLINT schema_length, SQLCHAR* table,
                            SQLSMALLINT table_length, SQLCHAR* table_types,
                            SQLSMALLINT table_types_length) {
    return guarded(statement, "SQLTables", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* handle = static_cast<HandleBase*>(statement);
        std::vector<ParameterValue> parameters;
        SQLRETURN result = append_ansi_metadata_parameter(handle, catalog, catalog_length, &parameters);
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, schema, schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, table, table_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_table_types(handle, table_types, table_types_length, &parameters);
        }
        return SQL_SUCCEEDED(result)
            ? execute_tables_api(statement, std::move(parameters)) : result;
    });
}

SQLRETURN SQL_API SQLTablesW(SQLHSTMT statement, SQLWCHAR* catalog, SQLSMALLINT catalog_length,
                             SQLWCHAR* schema, SQLSMALLINT schema_length, SQLWCHAR* table,
                             SQLSMALLINT table_length, SQLWCHAR* table_types,
                             SQLSMALLINT table_types_length) {
    return guarded(statement, "SQLTablesW", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* handle = static_cast<HandleBase*>(statement);
        std::vector<ParameterValue> parameters;
        SQLRETURN result = append_wide_metadata_parameter(handle, catalog, catalog_length, &parameters);
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, schema, schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, table, table_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_table_types(handle, table_types, table_types_length, &parameters);
        }
        return SQL_SUCCEEDED(result)
            ? execute_tables_api(statement, std::move(parameters)) : result;
    });
}

SQLRETURN SQL_API SQLColumns(SQLHSTMT statement, SQLCHAR* catalog, SQLSMALLINT catalog_length,
                             SQLCHAR* schema, SQLSMALLINT schema_length, SQLCHAR* table,
                             SQLSMALLINT table_length, SQLCHAR* column,
                             SQLSMALLINT column_length) {
    return guarded(statement, "SQLColumns", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* handle = static_cast<HandleBase*>(statement);
        std::vector<ParameterValue> parameters;
        SQLRETURN result = append_ansi_metadata_parameter(handle, catalog, catalog_length, &parameters);
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, schema, schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, table, table_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, column, column_length, &parameters);
        }
        return SQL_SUCCEEDED(result)
            ? execute_catalog_api(statement, "Columns", parameters) : result;
    });
}

SQLRETURN SQL_API SQLColumnsW(SQLHSTMT statement, SQLWCHAR* catalog, SQLSMALLINT catalog_length,
                              SQLWCHAR* schema, SQLSMALLINT schema_length, SQLWCHAR* table,
                              SQLSMALLINT table_length, SQLWCHAR* column,
                              SQLSMALLINT column_length) {
    return guarded(statement, "SQLColumnsW", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* handle = static_cast<HandleBase*>(statement);
        std::vector<ParameterValue> parameters;
        SQLRETURN result = append_wide_metadata_parameter(handle, catalog, catalog_length, &parameters);
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, schema, schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, table, table_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, column, column_length, &parameters);
        }
        return SQL_SUCCEEDED(result)
            ? execute_catalog_api(statement, "Columns", parameters) : result;
    });
}

SQLRETURN SQL_API SQLPrimaryKeys(SQLHSTMT statement, SQLCHAR* catalog,
                                 SQLSMALLINT catalog_length, SQLCHAR* schema,
                                 SQLSMALLINT schema_length, SQLCHAR* table,
                                 SQLSMALLINT table_length) {
    return guarded(statement, "SQLPrimaryKeys", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* handle = static_cast<HandleBase*>(statement);
        std::vector<ParameterValue> parameters;
        SQLRETURN result = append_ansi_metadata_parameter(handle, catalog, catalog_length, &parameters);
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, schema, schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, table, table_length, &parameters);
        }
        return SQL_SUCCEEDED(result)
            ? execute_catalog_api(statement, "PrimaryKeys", parameters) : result;
    });
}

SQLRETURN SQL_API SQLPrimaryKeysW(SQLHSTMT statement, SQLWCHAR* catalog,
                                  SQLSMALLINT catalog_length, SQLWCHAR* schema,
                                  SQLSMALLINT schema_length, SQLWCHAR* table,
                                  SQLSMALLINT table_length) {
    return guarded(statement, "SQLPrimaryKeysW", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* handle = static_cast<HandleBase*>(statement);
        std::vector<ParameterValue> parameters;
        SQLRETURN result = append_wide_metadata_parameter(handle, catalog, catalog_length, &parameters);
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, schema, schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, table, table_length, &parameters);
        }
        return SQL_SUCCEEDED(result)
            ? execute_catalog_api(statement, "PrimaryKeys", parameters) : result;
    });
}

SQLRETURN SQL_API SQLForeignKeys(
    SQLHSTMT statement, SQLCHAR* primary_catalog, SQLSMALLINT primary_catalog_length,
    SQLCHAR* primary_schema, SQLSMALLINT primary_schema_length, SQLCHAR* primary_table,
    SQLSMALLINT primary_table_length, SQLCHAR* foreign_catalog,
    SQLSMALLINT foreign_catalog_length, SQLCHAR* foreign_schema,
    SQLSMALLINT foreign_schema_length, SQLCHAR* foreign_table,
    SQLSMALLINT foreign_table_length) {
    return guarded(statement, "SQLForeignKeys", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* handle = static_cast<HandleBase*>(statement);
        std::vector<ParameterValue> parameters;
        SQLRETURN result = append_ansi_metadata_parameter(handle, primary_catalog,
            primary_catalog_length, &parameters);
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, primary_schema,
                primary_schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, primary_table,
                primary_table_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, foreign_catalog,
                foreign_catalog_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, foreign_schema,
                foreign_schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_ansi_metadata_parameter(handle, foreign_table,
                foreign_table_length, &parameters);
        }
        const std::string method = primary_table != nullptr && foreign_table != nullptr
            ? "CrossReference" : (primary_table != nullptr ? "ExportedKeys" : "ImportedKeys");
        if (SQL_SUCCEEDED(result)) {
            if (primary_table == nullptr) {
                parameters.erase(parameters.begin(), parameters.begin() + 3);
            } else if (foreign_table == nullptr) {
                parameters.resize(3);
            }
        }
        return SQL_SUCCEEDED(result)
            ? execute_catalog_api(statement, method, parameters) : result;
    });
}

SQLRETURN SQL_API SQLForeignKeysW(
    SQLHSTMT statement, SQLWCHAR* primary_catalog, SQLSMALLINT primary_catalog_length,
    SQLWCHAR* primary_schema, SQLSMALLINT primary_schema_length, SQLWCHAR* primary_table,
    SQLSMALLINT primary_table_length, SQLWCHAR* foreign_catalog,
    SQLSMALLINT foreign_catalog_length, SQLWCHAR* foreign_schema,
    SQLSMALLINT foreign_schema_length, SQLWCHAR* foreign_table,
    SQLSMALLINT foreign_table_length) {
    return guarded(statement, "SQLForeignKeysW", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* handle = static_cast<HandleBase*>(statement);
        std::vector<ParameterValue> parameters;
        SQLRETURN result = append_wide_metadata_parameter(handle, primary_catalog,
            primary_catalog_length, &parameters);
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, primary_schema,
                primary_schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, primary_table,
                primary_table_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, foreign_catalog,
                foreign_catalog_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, foreign_schema,
                foreign_schema_length, &parameters);
        }
        if (SQL_SUCCEEDED(result)) {
            result = append_wide_metadata_parameter(handle, foreign_table,
                foreign_table_length, &parameters);
        }
        const std::string method = primary_table != nullptr && foreign_table != nullptr
            ? "CrossReference" : (primary_table != nullptr ? "ExportedKeys" : "ImportedKeys");
        if (SQL_SUCCEEDED(result)) {
            if (primary_table == nullptr) {
                parameters.erase(parameters.begin(), parameters.begin() + 3);
            } else if (foreign_table == nullptr) {
                parameters.resize(3);
            }
        }
        return SQL_SUCCEEDED(result)
            ? execute_catalog_api(statement, method, parameters) : result;
    });
}

SQLRETURN SQL_API SQLGetTypeInfo(SQLHSTMT statement, SQLSMALLINT data_type) {
    return guarded(statement, "SQLGetTypeInfo", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> statement_lock(target->operation_mutex);
        std::lock_guard<std::mutex> connection_lock(target->connection->operation_mutex);
        const SQLRETURN result = execute_metadata_statement(target, "TypeInfo", {});
        if (!SQL_SUCCEEDED(result) || data_type == SQL_ALL_TYPES) {
            return result;
        }

        const auto type_column = std::find_if(target->columns.begin(), target->columns.end(),
            [](const std::string& column) {
                if (column.size() != sizeof("DATA_TYPE") - 1) {
                    return false;
                }
                return std::equal(column.begin(), column.end(), "DATA_TYPE",
                    [](unsigned char left, unsigned char right) {
                        return std::toupper(left) == std::toupper(right);
                    });
            });
        if (type_column == target->columns.end()) {
            return fail(target, "JDBC type metadata is missing the DATA_TYPE column", "HY000");
        }
        const auto type_column_index = static_cast<std::size_t>(
            std::distance(target->columns.begin(), type_column));
        for (const auto& row : target->rows) {
            if (type_column_index >= row.size() ||
                (!std::holds_alternative<std::int32_t>(row[type_column_index]) &&
                 !std::holds_alternative<std::int64_t>(row[type_column_index]))) {
                return fail(target, "JDBC type metadata contains an invalid DATA_TYPE value", "HY000");
            }
        }
        target->rows.erase(std::remove_if(target->rows.begin(), target->rows.end(),
            [type_column_index, data_type](const std::vector<Cell>& row) {
                const auto* type_code = std::get_if<std::int32_t>(&row[type_column_index]);
                const auto* long_type_code = std::get_if<std::int64_t>(&row[type_column_index]);
                return type_code != nullptr ? *type_code != data_type : *long_type_code != data_type;
            }), target->rows.end());
        target->row_count = static_cast<SQLLEN>(target->rows.size());
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLDescribeCol(SQLHSTMT statement, SQLUSMALLINT column_number,
                                 SQLCHAR* column_name, SQLSMALLINT name_buffer_length,
                                 SQLSMALLINT* name_length, SQLSMALLINT* data_type,
                                 SQLULEN* column_size, SQLSMALLINT* decimal_digits,
                                 SQLSMALLINT* nullable) {
    return guarded(statement, "SQLDescribeCol", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        ColumnDescription description;
        if (!describe_column(target, column_number, &description)) {
            return fail(target, "Invalid column number", "07009");
        }
        if (name_length != nullptr) {
            *name_length = static_cast<SQLSMALLINT>(std::min<std::size_t>(
                description.name.size(), std::numeric_limits<SQLSMALLINT>::max()));
        }
        if (name_buffer_length < 0) {
            return fail(target, "Invalid column name buffer length", "HY090");
        }
        if (column_name != nullptr && name_buffer_length > 0) {
            const auto copy_count = std::min<std::size_t>(
                description.name.size(), static_cast<std::size_t>(name_buffer_length - 1));
            std::memcpy(column_name, description.name.data(), copy_count);
            column_name[copy_count] = '\0';
            if (copy_count < description.name.size()) {
                target->diagnostics.push_back({"01004", 0, "Column name was truncated"});
            }
        }
        if (data_type != nullptr) {
            *data_type = description.type;
        }
        if (column_size != nullptr) {
            *column_size = description.size;
        }
        if (decimal_digits != nullptr) {
            *decimal_digits = description.decimal_digits;
        }
        if (nullable != nullptr) {
            *nullable = description.nullable;
        }
        return column_name != nullptr && name_buffer_length > 0 &&
               description.name.size() >= static_cast<std::size_t>(name_buffer_length)
            ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLDescribeColW(SQLHSTMT statement, SQLUSMALLINT column_number,
                                  SQLWCHAR* column_name, SQLSMALLINT name_buffer_length,
                                  SQLSMALLINT* name_length, SQLSMALLINT* data_type,
                                  SQLULEN* column_size, SQLSMALLINT* decimal_digits,
                                  SQLSMALLINT* nullable) {
    return guarded(statement, "SQLDescribeColW", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        ColumnDescription description;
        if (!describe_column(target, column_number, &description)) {
            return fail(target, "Invalid column number", "07009");
        }
        const SQLRETURN name_result = write_wide_string(
            target, description.name, column_name, name_buffer_length, name_length);
        if (!SQL_SUCCEEDED(name_result)) {
            return name_result;
        }
        if (data_type != nullptr) {
            *data_type = description.type;
        }
        if (column_size != nullptr) {
            *column_size = description.size;
        }
        if (decimal_digits != nullptr) {
            *decimal_digits = description.decimal_digits;
        }
        if (nullable != nullptr) {
            *nullable = description.nullable;
        }
        return name_result;
    });
}

#if defined(_WIN32) && !defined(_WIN64)
using NumericAttributeArg = SQLPOINTER;
#else
using NumericAttributeArg = SQLLEN*;
#endif

// Resolves an SQLColAttribute(W) field: returns text (is_text) or a numeric value.
static SQLRETURN column_attribute(StatementHandle* target, SQLUSMALLINT column_number,
                                 SQLUSMALLINT field_identifier, std::string* text,
                                 SQLLEN* number, bool* is_text) {
    *is_text = false;
    if (field_identifier == SQL_DESC_COUNT || field_identifier == SQL_COLUMN_COUNT) {
        *number = static_cast<SQLLEN>(target->columns.size());
        return SQL_SUCCESS;
    }
    ColumnDescription description;
    if (!describe_column(target, column_number, &description)) {
        return fail(target, "Invalid column number " + std::to_string(column_number) +
                    " (result set has " + std::to_string(target->columns.size()) + " column(s))",
                    "07009");
    }
    switch (field_identifier) {
        case SQL_DESC_NAME:
        case SQL_DESC_LABEL:
        case SQL_COLUMN_NAME:
        case SQL_DESC_BASE_COLUMN_NAME:
            *is_text = true;
            *text = description.name;
            return SQL_SUCCESS;
        case SQL_DESC_TYPE_NAME:
        case SQL_DESC_LOCAL_TYPE_NAME:
            *is_text = true;
            *text = description.type_name;
            return SQL_SUCCESS;
        // OJP does not expose the column origin or per-type literals: empty text means "unknown".
        case SQL_DESC_BASE_TABLE_NAME:
        case SQL_DESC_TABLE_NAME:
        case SQL_DESC_SCHEMA_NAME:
        case SQL_DESC_CATALOG_NAME:
        case SQL_DESC_LITERAL_PREFIX:
        case SQL_DESC_LITERAL_SUFFIX:
            *is_text = true;
            text->clear();
            return SQL_SUCCESS;
        case SQL_DESC_TYPE:
        case SQL_DESC_CONCISE_TYPE:
            *number = description.type;
            return SQL_SUCCESS;
        case SQL_DESC_LENGTH:
        case SQL_COLUMN_PRECISION:
            *number = static_cast<SQLLEN>(std::min<SQLULEN>(
                description.size, static_cast<SQLULEN>(std::numeric_limits<SQLINTEGER>::max())));
            return SQL_SUCCESS;
        case SQL_DESC_PRECISION:
            // For date/time, ODBC defines PRECISION as the number of fractional-second digits.
            *number = description.type == SQL_TYPE_TIME || description.type == SQL_TYPE_TIMESTAMP
                ? description.decimal_digits
                : static_cast<SQLLEN>(std::min<SQLULEN>(
                      description.size, static_cast<SQLULEN>(std::numeric_limits<SQLINTEGER>::max())));
            return SQL_SUCCESS;
        case SQL_DESC_DISPLAY_SIZE:
            *number = description.display_size;
            return SQL_SUCCESS;
        case SQL_DESC_OCTET_LENGTH:
        case SQL_COLUMN_LENGTH:
            *number = description.octet_length;
            return SQL_SUCCESS;
        case SQL_DESC_SCALE:
        case SQL_COLUMN_SCALE:
            *number = description.decimal_digits;
            return SQL_SUCCESS;
        case SQL_DESC_NULLABLE:
        case SQL_COLUMN_NULLABLE:
            *number = description.nullable;
            return SQL_SUCCESS;
        case SQL_DESC_UNSIGNED:
            switch (description.type) {
                case SQL_SMALLINT: case SQL_INTEGER: case SQL_BIGINT: case SQL_DECIMAL:
                case SQL_NUMERIC: case SQL_REAL: case SQL_FLOAT: case SQL_DOUBLE:
                    *number = SQL_FALSE;
                    break;
                default:
                    // Per the specification, non-numeric types (and SQL Server TINYINT) are "unsigned".
                    *number = SQL_TRUE;
                    break;
            }
            return SQL_SUCCESS;
        case SQL_DESC_AUTO_UNIQUE_VALUE:
        case SQL_DESC_CASE_SENSITIVE:
        case SQL_DESC_FIXED_PREC_SCALE:
            *number = SQL_FALSE;
            return SQL_SUCCESS;
        case SQL_DESC_UPDATABLE:
            *number = SQL_ATTR_READWRITE_UNKNOWN;
            return SQL_SUCCESS;
        case SQL_DESC_SEARCHABLE:
            *number = SQL_PRED_SEARCHABLE;
            return SQL_SUCCESS;
        case SQL_DESC_UNNAMED:
            *number = description.name.empty() ? SQL_UNNAMED : SQL_NAMED;
            return SQL_SUCCESS;
        case SQL_DESC_NUM_PREC_RADIX:
            switch (description.type) {
                case SQL_TINYINT: case SQL_SMALLINT: case SQL_INTEGER: case SQL_BIGINT:
                case SQL_DECIMAL: case SQL_NUMERIC:
                    *number = 10;
                    break;
                case SQL_REAL: case SQL_FLOAT: case SQL_DOUBLE:
                    *number = 2;
                    break;
                default:
                    *number = 0;
                    break;
            }
            return SQL_SUCCESS;
        default:
            return fail(target, "SQLColAttribute field " + std::to_string(field_identifier) +
                        " (column " + std::to_string(column_number) +
                        ") is not supported by the OJP ODBC driver", "HYC00");
    }
}

SQLRETURN SQL_API SQLColAttribute(SQLHSTMT statement, SQLUSMALLINT column_number,
                                  SQLUSMALLINT field_identifier, SQLPOINTER character_attribute,
                                  SQLSMALLINT buffer_length, SQLSMALLINT* string_length,
                                  NumericAttributeArg numeric_attribute_arg) {
    return guarded(statement, "SQLColAttribute", [&]() -> SQLRETURN {
        auto* numeric_attribute = static_cast<SQLLEN*>(numeric_attribute_arg);
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        clear_diagnostics(target);
        std::string text;
        SQLLEN number = 0;
        bool is_text = false;
        const auto result = column_attribute(target, column_number, field_identifier,
                                             &text, &number, &is_text);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        if (!is_text) {
            if (numeric_attribute == nullptr) {
                return fail(target, "Numeric column attribute output is required (field " +
                            std::to_string(field_identifier) + ")", "HY009");
            }
            *numeric_attribute = number;
            return SQL_SUCCESS;
        }
        if (buffer_length < 0) {
            return fail(target, "Invalid column attribute buffer length", "HY090");
        }
        if (string_length != nullptr) {
            *string_length = static_cast<SQLSMALLINT>(std::min<std::size_t>(
                text.size(), std::numeric_limits<SQLSMALLINT>::max()));
        }
        if (character_attribute == nullptr || buffer_length == 0) {
            return SQL_SUCCESS;
        }
        const auto copy_count = std::min<std::size_t>(
            text.size(), static_cast<std::size_t>(buffer_length - 1));
        std::memcpy(character_attribute, text.data(), copy_count);
        static_cast<char*>(character_attribute)[copy_count] = '\0';
        return copy_count < text.size() ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLColAttributeW(SQLHSTMT statement, SQLUSMALLINT column_number,
                                   SQLUSMALLINT field_identifier,
                                   SQLPOINTER character_attribute, SQLSMALLINT buffer_length,
                                   SQLSMALLINT* string_length,
                                   NumericAttributeArg numeric_attribute_arg) {
    return guarded(statement, "SQLColAttributeW", [&]() -> SQLRETURN {
        auto* numeric_attribute = static_cast<SQLLEN*>(numeric_attribute_arg);
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        clear_diagnostics(target);
        std::string text;
        SQLLEN number = 0;
        bool is_text = false;
        const auto result = column_attribute(target, column_number, field_identifier,
                                             &text, &number, &is_text);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        if (!is_text) {
            if (numeric_attribute == nullptr) {
                return fail(target, "Numeric column attribute output is required (field " +
                            std::to_string(field_identifier) + ")", "HY009");
            }
            *numeric_attribute = number;
            return SQL_SUCCESS;
        }
        SQLINTEGER bytes = 0;
        const auto write_result = write_wide_string_bytes(target, text, character_attribute,
                                                          buffer_length, &bytes);
        if (string_length != nullptr) {
            *string_length = static_cast<SQLSMALLINT>(std::min<SQLINTEGER>(
                bytes, std::numeric_limits<SQLSMALLINT>::max()));
        }
        return write_result;
    });
}

SQLRETURN SQL_API SQLRowCount(SQLHSTMT statement, SQLLEN* row_count) {
    return guarded(statement, "SQLRowCount", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        if (row_count == nullptr) {
            return fail(static_cast<HandleBase*>(statement), "Row count output is required", "HY009");
        }
        *row_count = static_cast<StatementHandle*>(statement)->row_count;
        return SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLFreeStmt(SQLHSTMT statement, SQLUSMALLINT option) {
    return guarded(statement, "SQLFreeStmt", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<StatementHandle*>(statement);
        std::lock_guard<std::mutex> lock(target->operation_mutex);
        clear_diagnostics(target);
        if (option == SQL_CLOSE || option == SQL_UNBIND || option == SQL_RESET_PARAMS) {
            if (option == SQL_CLOSE) {
                reset_data_at_execution(target);
                target->columns.clear();
                target->column_meta.clear();
                target->rows.clear();
                target->get_data_offsets.clear();
                target->row_count = -1;
                target->row_index = 0;
                target->has_result_set = false;
            } else if (option == SQL_UNBIND) {
                target->bound_columns.clear();
            } else {
                target->parameters.clear();
                reset_data_at_execution(target);
            }
            return SQL_SUCCESS;
        }
        return fail(target, "Invalid SQLFreeStmt option", "HY092");
    });
}

extern "C++" {
// Current catalog extracted from the JDBC URL (databaseName=, database= or /db path).
static std::string current_catalog_from_url(const std::string& url) {
    for (const char* key : {"databaseName=", "database="}) {
        auto position = url.find(key);
        if (position != std::string::npos) {
            position += std::strlen(key);
            return url.substr(position, url.find_first_of(";&?", position) - position);
        }
    }
    const auto scheme = url.find("://");
    if (scheme != std::string::npos) {
        const auto slash = url.find('/', scheme + 3);
        if (slash != std::string::npos) {
            const auto stop = url.find_first_of(";?&", slash + 1);
            return url.substr(slash + 1, stop == std::string::npos ? std::string::npos : stop - slash - 1);
        }
    }
    return {};
}

static std::string connection_attribute_name(SQLINTEGER attribute) {
    switch (attribute) {
        case SQL_ATTR_AUTOCOMMIT: return "SQL_ATTR_AUTOCOMMIT";
        case SQL_ATTR_TXN_ISOLATION: return "SQL_ATTR_TXN_ISOLATION";
        case SQL_ATTR_ACCESS_MODE: return "SQL_ATTR_ACCESS_MODE";
        case SQL_ATTR_ASYNC_ENABLE: return "SQL_ATTR_ASYNC_ENABLE";
        case SQL_ATTR_CURRENT_CATALOG: return "SQL_ATTR_CURRENT_CATALOG";
        case SQL_ATTR_METADATA_ID: return "SQL_ATTR_METADATA_ID";
        default: return "attribute " + std::to_string(attribute);
    }
}

}  // extern "C++"

static SQLRETURN set_connection_attribute(SQLHDBC connection, SQLINTEGER attribute,
                                          SQLPOINTER value) {
    if (connection == SQL_NULL_HDBC ||
        static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<ConnectionHandle*>(connection);
    clear_diagnostics(target);
    std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
    const auto option = static_cast<SQLULEN>(reinterpret_cast<std::uintptr_t>(value));
    switch (attribute) {
        case SQL_ATTR_AUTOCOMMIT:
            if (option != SQL_AUTOCOMMIT_ON && option != SQL_AUTOCOMMIT_OFF) {
                return fail(target, "Invalid SQL_ATTR_AUTOCOMMIT value " + std::to_string(option) +
                            " (expected SQL_AUTOCOMMIT_ON=1 or SQL_AUTOCOMMIT_OFF=0)", "HY024");
            }
            if (!target->connected) {
                // Before connecting it is only recorded; OJP starts sessions in auto-commit.
                target->auto_commit = option == SQL_AUTOCOMMIT_ON;
                return SQL_SUCCESS;
            }
            return set_auto_commit(target, target, option == SQL_AUTOCOMMIT_ON);
        case SQL_ATTR_TXN_ISOLATION: {
            std::int32_t jdbc_isolation = 0;
            if (!jdbc_transaction_isolation(option, &jdbc_isolation)) {
                return fail(target, "Unsupported SQL_ATTR_TXN_ISOLATION value " +
                            std::to_string(option), "HY024");
            }
            if (!target->connected) {
                target->transaction_isolation = option;
                return SQL_SUCCESS;
            }
            return set_transaction_isolation(target, target, option);
        }
        case SQL_ATTR_ASYNC_ENABLE:
            if (option != SQL_ASYNC_ENABLE_OFF) {
                return fail(target, "Asynchronous execution (SQL_ATTR_ASYNC_ENABLE = " +
                            std::to_string(option) + ") is not supported by the OJP ODBC driver",
                            "HYC00");
            }
            target->stored_attributes[attribute] = option;
            return SQL_SUCCESS;
        case SQL_ATTR_METADATA_ID:
            if (option != SQL_FALSE) {
                return fail(target, "SQL_ATTR_METADATA_ID = SQL_TRUE is not supported by the OJP "
                            "ODBC driver", "HYC00");
            }
            target->stored_attributes[attribute] = option;
            return SQL_SUCCESS;
        case SQL_ATTR_LOGIN_TIMEOUT:
        case SQL_ATTR_CONNECTION_TIMEOUT:
        case SQL_ATTR_ACCESS_MODE:
        case SQL_ATTR_QUIET_MODE:
        case SQL_ATTR_PACKET_SIZE:
        case SQL_ATTR_ODBC_CURSORS:
            // Stored for SQLGetConnectAttr; timeouts and networking are controlled by OJP.
            target->stored_attributes[attribute] = option;
            log_message(LogLevel::debug, connection_attribute_name(attribute) + " stored as " +
                        std::to_string(option) + " (informational only)");
            return SQL_SUCCESS;
        case SQL_ATTR_CURRENT_CATALOG:
            return fail(target, "Changing SQL_ATTR_CURRENT_CATALOG is not supported by the OJP "
                        "ODBC driver; set the database in the JDBC URL of the DSN", "HYC00");
        default:
            return fail(target, "Connection attribute " + std::to_string(attribute) + " (value " +
                        std::to_string(option) + ") is not supported by the OJP ODBC driver",
                        "HYC00");
    }
}

SQLRETURN SQL_API SQLSetConnectAttr(SQLHDBC connection, SQLINTEGER attribute,
                                    SQLPOINTER value, SQLINTEGER) {
    return guarded(connection, "SQLSetConnectAttr", [&]() -> SQLRETURN {
        return set_connection_attribute(connection, attribute, value);
    });
}

// No supported writable connection attribute is a string, so the W variant reuses the ANSI one.
SQLRETURN SQL_API SQLSetConnectAttrW(SQLHDBC connection, SQLINTEGER attribute,
                                     SQLPOINTER value, SQLINTEGER) {
    return guarded(connection, "SQLSetConnectAttrW", [&]() -> SQLRETURN {
        return set_connection_attribute(connection, attribute, value);
    });
}

static SQLRETURN get_connection_attribute(SQLHDBC connection, SQLINTEGER attribute,
                                          SQLPOINTER value, SQLINTEGER buffer_length,
                                          SQLINTEGER* length, bool wide) {
    if (connection == SQL_NULL_HDBC ||
        static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<ConnectionHandle*>(connection);
    clear_diagnostics(target);
    std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
    if (attribute == SQL_ATTR_CURRENT_CATALOG) {
        const std::string catalog = current_catalog_from_url(target->url);
        if (wide) {
            return write_wide_string_bytes(target, catalog, value, buffer_length, length);
        }
        if (length != nullptr) {
            *length = static_cast<SQLINTEGER>(catalog.size());
        }
        if (value == nullptr || buffer_length <= 0) {
            return SQL_SUCCESS;
        }
        const auto copy_count = std::min<std::size_t>(
            catalog.size(), static_cast<std::size_t>(buffer_length - 1));
        std::memcpy(value, catalog.data(), copy_count);
        static_cast<char*>(value)[copy_count] = '\0';
        return copy_count < catalog.size() ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
    }
    if (value == nullptr) {
        return fail(target, "Output buffer for " + connection_attribute_name(attribute) +
                    " is required", "HY009");
    }
    SQLULEN result_value = 0;
    switch (attribute) {
        case SQL_ATTR_AUTOCOMMIT:
            result_value = target->auto_commit ? SQL_AUTOCOMMIT_ON : SQL_AUTOCOMMIT_OFF;
            break;
        case SQL_ATTR_TXN_ISOLATION:
            if (target->connected) {
                const auto result = get_transaction_isolation(target, target, &result_value);
                if (!SQL_SUCCEEDED(result)) {
                    return result;
                }
            } else {
                result_value = target->transaction_isolation;
            }
            break;
        case SQL_ATTR_CONNECTION_DEAD:
            result_value = target->connected ? SQL_CD_FALSE : SQL_CD_TRUE;
            break;
        case SQL_ATTR_AUTO_IPD:
            result_value = SQL_FALSE;
            break;
        case SQL_ATTR_ACCESS_MODE:
        case SQL_ATTR_ASYNC_ENABLE:
        case SQL_ATTR_METADATA_ID:
        case SQL_ATTR_LOGIN_TIMEOUT:
        case SQL_ATTR_CONNECTION_TIMEOUT:
        case SQL_ATTR_QUIET_MODE:
        case SQL_ATTR_PACKET_SIZE:
        case SQL_ATTR_ODBC_CURSORS: {
            const auto stored = target->stored_attributes.find(attribute);
            result_value = stored != target->stored_attributes.end() ? stored->second : 0;
            break;
        }
        default:
            return fail(target, "Connection attribute " + std::to_string(attribute) +
                        " is not supported by the OJP ODBC driver for reading", "HYC00");
    }
    if (attribute == SQL_ATTR_QUIET_MODE) {
        *static_cast<SQLPOINTER*>(value) = reinterpret_cast<SQLPOINTER>(result_value);
    } else if (attribute == SQL_ATTR_AUTOCOMMIT || attribute == SQL_ATTR_TXN_ISOLATION ||
               attribute == SQL_ATTR_ODBC_CURSORS) {
        *static_cast<SQLULEN*>(value) = result_value;
    } else {
        *static_cast<SQLUINTEGER*>(value) = static_cast<SQLUINTEGER>(result_value);
    }
    if (length != nullptr) {
        *length = attribute == SQL_ATTR_QUIET_MODE ? static_cast<SQLINTEGER>(sizeof(SQLPOINTER))
            : (attribute == SQL_ATTR_AUTOCOMMIT || attribute == SQL_ATTR_TXN_ISOLATION ||
               attribute == SQL_ATTR_ODBC_CURSORS)
                ? static_cast<SQLINTEGER>(sizeof(SQLULEN))
                : static_cast<SQLINTEGER>(sizeof(SQLUINTEGER));
    }
    return SQL_SUCCESS;
}

SQLRETURN SQL_API SQLGetConnectAttr(SQLHDBC connection, SQLINTEGER attribute,
                                    SQLPOINTER value, SQLINTEGER buffer_length,
                                    SQLINTEGER* length) {
    return guarded(connection, "SQLGetConnectAttr", [&]() -> SQLRETURN {
        return get_connection_attribute(connection, attribute, value, buffer_length, length, false);
    });
}

SQLRETURN SQL_API SQLGetConnectAttrW(SQLHDBC connection, SQLINTEGER attribute,
                                     SQLPOINTER value, SQLINTEGER buffer_length,
                                     SQLINTEGER* length) {
    return guarded(connection, "SQLGetConnectAttrW", [&]() -> SQLRETURN {
        return get_connection_attribute(connection, attribute, value, buffer_length, length, true);
    });
}

SQLRETURN SQL_API SQLEndTran(SQLSMALLINT handle_type, SQLHANDLE handle,
                             SQLSMALLINT completion_type) {
    return guarded(handle, "SQLEndTran", [&]() -> SQLRETURN {
        if (handle == SQL_NULL_HANDLE || static_cast<HandleBase*>(handle)->type != handle_type) {
            return SQL_INVALID_HANDLE;
        }
        if (handle_type != SQL_HANDLE_DBC) {
            return fail(static_cast<HandleBase*>(handle),
                        "Transactions can only be ended on a connection handle", "HY092");
        }
        auto* target = static_cast<ConnectionHandle*>(handle);
        clear_diagnostics(target);
        if (completion_type != SQL_COMMIT && completion_type != SQL_ROLLBACK) {
            return fail(target, "Invalid transaction completion type", "HY012");
        }
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        return end_transaction(target, target, completion_type);
    });
}

SQLRETURN SQL_API SQLTransact(SQLHENV environment, SQLHDBC connection,
                              SQLUSMALLINT completion_type) {
    return guarded(connection != SQL_NULL_HDBC ? static_cast<SQLHANDLE>(connection) : static_cast<SQLHANDLE>(environment), "SQLTransact", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC) {
            if (environment == SQL_NULL_HENV ||
                static_cast<HandleBase*>(environment)->type != SQL_HANDLE_ENV) {
                return SQL_INVALID_HANDLE;
            }
            return fail(static_cast<HandleBase*>(environment),
                        "Environment-wide transactions are not supported", "HYC00");
        }
        if (static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        if (completion_type != SQL_COMMIT && completion_type != SQL_ROLLBACK) {
            return fail(target, "Invalid transaction completion type", "HY012");
        }
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        // Calling the exported SQLEndTran here can resolve to the Driver Manager's entry point.
        return end_transaction(target, target, static_cast<SQLSMALLINT>(completion_type));
    });
}

extern "C++" {
// Default values reported by SQLGetStmtAttr for attributes not changed yet.
static SQLULEN default_statement_attribute(SQLINTEGER attribute) {
    switch (attribute) {
        case SQL_ATTR_CURSOR_TYPE: return SQL_CURSOR_FORWARD_ONLY;
        case SQL_ATTR_CONCURRENCY: return SQL_CONCUR_READ_ONLY;
        case SQL_ATTR_CURSOR_SCROLLABLE: return SQL_NONSCROLLABLE;
        case SQL_ATTR_CURSOR_SENSITIVITY: return SQL_INSENSITIVE;
        case SQL_ATTR_ROW_ARRAY_SIZE:
        case SQL_ROWSET_SIZE:
        case SQL_ATTR_PARAMSET_SIZE: return 1;
        case SQL_ATTR_ROW_BIND_TYPE:
        case SQL_ATTR_PARAM_BIND_TYPE: return SQL_BIND_BY_COLUMN;
        case SQL_ATTR_NOSCAN: return SQL_NOSCAN_OFF;
        case SQL_ATTR_RETRIEVE_DATA: return SQL_RD_ON;
        case SQL_ATTR_USE_BOOKMARKS: return SQL_UB_OFF;
        case SQL_ATTR_ASYNC_ENABLE: return SQL_ASYNC_ENABLE_OFF;
        case SQL_ATTR_ENABLE_AUTO_IPD: return SQL_FALSE;
        case SQL_ATTR_SIMULATE_CURSOR: return SQL_SC_UNIQUE;
        default: return 0;
    }
}

static std::string statement_attribute_name(SQLINTEGER attribute) {
    switch (attribute) {
        case SQL_ATTR_CURSOR_TYPE: return "SQL_ATTR_CURSOR_TYPE";
        case SQL_ATTR_CONCURRENCY: return "SQL_ATTR_CONCURRENCY";
        case SQL_ATTR_CURSOR_SCROLLABLE: return "SQL_ATTR_CURSOR_SCROLLABLE";
        case SQL_ATTR_CURSOR_SENSITIVITY: return "SQL_ATTR_CURSOR_SENSITIVITY";
        case SQL_ATTR_ROW_ARRAY_SIZE: return "SQL_ATTR_ROW_ARRAY_SIZE";
        case SQL_ROWSET_SIZE: return "SQL_ROWSET_SIZE";
        case SQL_ATTR_PARAMSET_SIZE: return "SQL_ATTR_PARAMSET_SIZE";
        case SQL_ATTR_ROW_BIND_TYPE: return "SQL_ATTR_ROW_BIND_TYPE";
        case SQL_ATTR_PARAM_BIND_TYPE: return "SQL_ATTR_PARAM_BIND_TYPE";
        case SQL_ATTR_ROW_BIND_OFFSET_PTR: return "SQL_ATTR_ROW_BIND_OFFSET_PTR";
        case SQL_ATTR_PARAM_BIND_OFFSET_PTR: return "SQL_ATTR_PARAM_BIND_OFFSET_PTR";
        case SQL_ATTR_USE_BOOKMARKS: return "SQL_ATTR_USE_BOOKMARKS";
        case SQL_ATTR_ASYNC_ENABLE: return "SQL_ATTR_ASYNC_ENABLE";
        case SQL_ATTR_SIMULATE_CURSOR: return "SQL_ATTR_SIMULATE_CURSOR";
        default: return "attribute " + std::to_string(attribute);
    }
}

}  // extern "C++"

static SQLRETURN set_statement_attribute(SQLHSTMT statement, SQLINTEGER attribute,
                                         SQLPOINTER value) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    std::lock_guard<std::mutex> lock(target->operation_mutex);
    clear_diagnostics(target);
    const auto option = static_cast<SQLULEN>(reinterpret_cast<std::uintptr_t>(value));
    // Accepts only the supported value; any other value raises an explicit error with the attribute and value.
    auto only = [&](SQLULEN supported, const char* supported_name, const char* state) -> SQLRETURN {
        if (option == supported) {
            target->stored_attributes[attribute] = option;
            return SQL_SUCCESS;
        }
        return fail(target, statement_attribute_name(attribute) + " = " + std::to_string(option) +
                    " is not supported by the OJP ODBC driver (only " + supported_name + ")", state);
    };
    switch (attribute) {
        case SQL_ATTR_CURSOR_TYPE:
            return only(SQL_CURSOR_FORWARD_ONLY, "SQL_CURSOR_FORWARD_ONLY", "HYC00");
        case SQL_ATTR_CONCURRENCY:
            return only(SQL_CONCUR_READ_ONLY, "SQL_CONCUR_READ_ONLY", "HYC00");
        case SQL_ATTR_CURSOR_SCROLLABLE:
            return only(SQL_NONSCROLLABLE, "SQL_NONSCROLLABLE", "HYC00");
        case SQL_ATTR_CURSOR_SENSITIVITY:
            if (option == SQL_UNSPECIFIED || option == SQL_INSENSITIVE) {
                target->stored_attributes[attribute] = option;
                return SQL_SUCCESS;
            }
            return fail(target, "SQL_ATTR_CURSOR_SENSITIVITY = " + std::to_string(option) +
                        " is not supported by the OJP ODBC driver (only SQL_UNSPECIFIED or "
                        "SQL_INSENSITIVE)", "HYC00");
        case SQL_ATTR_ROW_ARRAY_SIZE:
        case SQL_ROWSET_SIZE:
            return only(1, "1: block cursors are not supported", "HYC00");
        case SQL_ATTR_PARAMSET_SIZE:
            if (option == 0) {
                return fail(target, "SQL_ATTR_PARAMSET_SIZE must be at least 1", "HY024");
            }
            // Parameter arrays are executed row by row (see execute_and_report).
            target->stored_attributes[attribute] = option;
            return SQL_SUCCESS;
        case SQL_ATTR_ROW_BIND_TYPE:
        case SQL_ATTR_PARAM_BIND_TYPE:
            return only(SQL_BIND_BY_COLUMN, "SQL_BIND_BY_COLUMN", "HYC00");
        case SQL_ATTR_ROW_BIND_OFFSET_PTR:
        case SQL_ATTR_PARAM_BIND_OFFSET_PTR:
            if (value == nullptr || *static_cast<SQLULEN*>(value) == 0) {
                return SQL_SUCCESS;
            }
            return fail(target, statement_attribute_name(attribute) +
                        " with a non-zero offset is not supported by the OJP ODBC driver", "HYC00");
        case SQL_ATTR_USE_BOOKMARKS:
            return only(SQL_UB_OFF, "SQL_UB_OFF", "HYC00");
        case SQL_ATTR_ASYNC_ENABLE:
            return only(SQL_ASYNC_ENABLE_OFF, "SQL_ASYNC_ENABLE_OFF", "HYC00");
        case SQL_ATTR_ROWS_FETCHED_PTR:
            target->rows_fetched_ptr = static_cast<SQLULEN*>(value);
            return SQL_SUCCESS;
        case SQL_ATTR_ROW_STATUS_PTR:
            target->row_status_ptr = static_cast<SQLUSMALLINT*>(value);
            return SQL_SUCCESS;
        case SQL_ATTR_PARAMS_PROCESSED_PTR:
            target->params_processed_ptr = static_cast<SQLULEN*>(value);
            return SQL_SUCCESS;
        case SQL_ATTR_PARAM_STATUS_PTR:
            target->param_status_ptr = static_cast<SQLUSMALLINT*>(value);
            return SQL_SUCCESS;
        case SQL_ATTR_QUERY_TIMEOUT:
        case SQL_ATTR_MAX_ROWS:
        case SQL_ATTR_MAX_LENGTH:
        case SQL_ATTR_NOSCAN:
        case SQL_ATTR_RETRIEVE_DATA:
        case SQL_ATTR_SIMULATE_CURSOR:
        case SQL_ATTR_ENABLE_AUTO_IPD:
        case SQL_ATTR_KEYSET_SIZE:
            // Stored for SQLGetStmtAttr; OJP applies its own limits/timeouts.
            target->stored_attributes[attribute] = option;
            log_message(LogLevel::debug, statement_attribute_name(attribute) + " stored as " +
                        std::to_string(option) + " (informational only)");
            return SQL_SUCCESS;
        default:
            return fail(target, "Statement attribute " + std::to_string(attribute) + " (value " +
                        std::to_string(option) + ") is not supported by the OJP ODBC driver",
                        "HYC00");
    }
}

SQLRETURN SQL_API SQLSetStmtAttr(SQLHSTMT statement, SQLINTEGER attribute,
                                 SQLPOINTER value, SQLINTEGER) {
    return guarded(statement, "SQLSetStmtAttr", [&]() -> SQLRETURN {
        return set_statement_attribute(statement, attribute, value);
    });
}

// No supported statement attribute is a string, so the W variant is identical to the ANSI one.
SQLRETURN SQL_API SQLSetStmtAttrW(SQLHSTMT statement, SQLINTEGER attribute,
                                  SQLPOINTER value, SQLINTEGER) {
    return guarded(statement, "SQLSetStmtAttrW", [&]() -> SQLRETURN {
        return set_statement_attribute(statement, attribute, value);
    });
}

static SQLRETURN get_statement_attribute(SQLHSTMT statement, SQLINTEGER attribute,
                                         SQLPOINTER value, SQLINTEGER* string_length) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* stmt = static_cast<StatementHandle*>(statement);
    clear_diagnostics(stmt);
    auto write_pointer = [&](SQLPOINTER pointer) {
        if (value != nullptr) {
            *static_cast<SQLPOINTER*>(value) = pointer;
        }
        if (string_length != nullptr) {
            *string_length = static_cast<SQLINTEGER>(sizeof(SQLPOINTER));
        }
        return SQL_SUCCESS;
    };
    if (attribute >= SQL_ATTR_APP_ROW_DESC && attribute <= SQL_ATTR_IMP_PARAM_DESC) {
        return write_pointer(&stmt->descriptors[attribute - SQL_ATTR_APP_ROW_DESC]);
    }
    switch (attribute) {
        case SQL_ATTR_ROWS_FETCHED_PTR: return write_pointer(stmt->rows_fetched_ptr);
        case SQL_ATTR_ROW_STATUS_PTR: return write_pointer(stmt->row_status_ptr);
        case SQL_ATTR_PARAMS_PROCESSED_PTR: return write_pointer(stmt->params_processed_ptr);
        case SQL_ATTR_PARAM_STATUS_PTR: return write_pointer(stmt->param_status_ptr);
        case SQL_ATTR_ROW_BIND_OFFSET_PTR:
        case SQL_ATTR_PARAM_BIND_OFFSET_PTR:
        case SQL_ATTR_PARAM_OPERATION_PTR:
        case SQL_ATTR_ROW_OPERATION_PTR:
        case SQL_ATTR_FETCH_BOOKMARK_PTR:
            return write_pointer(nullptr);
        case SQL_ATTR_ROW_NUMBER: {
            if (value != nullptr) {
                *static_cast<SQLULEN*>(value) = static_cast<SQLULEN>(stmt->row_index);
            }
            if (string_length != nullptr) {
                *string_length = static_cast<SQLINTEGER>(sizeof(SQLULEN));
            }
            return SQL_SUCCESS;
        }
        case SQL_ATTR_CURSOR_TYPE:
        case SQL_ATTR_CONCURRENCY:
        case SQL_ATTR_CURSOR_SCROLLABLE:
        case SQL_ATTR_CURSOR_SENSITIVITY:
        case SQL_ATTR_ROW_ARRAY_SIZE:
        case SQL_ROWSET_SIZE:
        case SQL_ATTR_PARAMSET_SIZE:
        case SQL_ATTR_ROW_BIND_TYPE:
        case SQL_ATTR_PARAM_BIND_TYPE:
        case SQL_ATTR_QUERY_TIMEOUT:
        case SQL_ATTR_MAX_ROWS:
        case SQL_ATTR_MAX_LENGTH:
        case SQL_ATTR_NOSCAN:
        case SQL_ATTR_RETRIEVE_DATA:
        case SQL_ATTR_USE_BOOKMARKS:
        case SQL_ATTR_ASYNC_ENABLE:
        case SQL_ATTR_SIMULATE_CURSOR:
        case SQL_ATTR_ENABLE_AUTO_IPD:
        case SQL_ATTR_KEYSET_SIZE: {
            const auto stored = stmt->stored_attributes.find(attribute);
            if (value != nullptr) {
                *static_cast<SQLULEN*>(value) = stored != stmt->stored_attributes.end()
                    ? stored->second : default_statement_attribute(attribute);
            }
            if (string_length != nullptr) {
                *string_length = static_cast<SQLINTEGER>(sizeof(SQLULEN));
            }
            return SQL_SUCCESS;
        }
        default:
            return fail(stmt, "Statement attribute " + std::to_string(attribute) +
                        " is not supported by the OJP ODBC driver for reading", "HYC00");
    }
}

SQLRETURN SQL_API SQLGetStmtAttr(SQLHSTMT statement, SQLINTEGER attribute, SQLPOINTER value,
                                 SQLINTEGER, SQLINTEGER* string_length) {
    return guarded(statement, "SQLGetStmtAttr", [&]() -> SQLRETURN {
        return get_statement_attribute(statement, attribute, value, string_length);
    });
}

SQLRETURN SQL_API SQLGetStmtAttrW(SQLHSTMT statement, SQLINTEGER attribute, SQLPOINTER value,
                                  SQLINTEGER, SQLINTEGER* string_length) {
    return guarded(statement, "SQLGetStmtAttrW", [&]() -> SQLRETURN {
        return get_statement_attribute(statement, attribute, value, string_length);
    });
}

SQLRETURN SQL_API SQLCloseCursor(SQLHSTMT statement) {
    return guarded(statement, "SQLCloseCursor", [&]() -> SQLRETURN {
        return SQLFreeStmt(statement, SQL_CLOSE);
    });
}

SQLRETURN SQL_API SQLFetchScroll(SQLHSTMT statement, SQLSMALLINT orientation, SQLLEN) {
    return guarded(statement, "SQLFetchScroll", [&]() -> SQLRETURN {
        if (orientation != SQL_FETCH_NEXT) {
            if (statement == SQL_NULL_HSTMT ||
                static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
                return SQL_INVALID_HANDLE;
            }
            return fail(static_cast<StatementHandle*>(statement),
                        "SQLFetchScroll orientation " + std::to_string(orientation) +
                        " is not supported: OJP cursors are forward-only (use SQL_FETCH_NEXT)",
                        "HYC00");
        }
        return SQLFetch(statement);
    });
}

SQLRETURN SQL_API SQLMoreResults(SQLHSTMT statement) {
    return guarded(statement, "SQLMoreResults", [&]() -> SQLRETURN {
        if (statement == SQL_NULL_HSTMT ||
            static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
            return SQL_INVALID_HANDLE;
        }
        return SQL_NO_DATA;
    });
}

SQLRETURN SQL_API SQLGetInfo(SQLHDBC connection, SQLUSMALLINT info_type, SQLPOINTER value,
                             SQLSMALLINT buffer_length, SQLSMALLINT* output_length) {
    return guarded(connection, "SQLGetInfo", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        if (info_type == SQL_DEFAULT_TXN_ISOLATION) {
            SQLULEN isolation = 0;
            {
                std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
                const auto result = get_transaction_isolation(target, target, &isolation);
                if (!SQL_SUCCEEDED(result)) {
                    return result;
                }
            }
            if (value == nullptr) {
                if (output_length != nullptr) {
                    *output_length = static_cast<SQLSMALLINT>(sizeof(SQLUINTEGER));
                }
                return SQL_SUCCESS;
            }
            const auto isolation_value = static_cast<SQLUINTEGER>(isolation);
            std::memcpy(value, &isolation_value, sizeof(isolation_value));
            if (output_length != nullptr) {
                *output_length = static_cast<SQLSMALLINT>(sizeof(isolation_value));
            }
            return SQL_SUCCESS;
        }
        if (info_type == SQL_TXN_ISOLATION_OPTION) {
            SQLUINTEGER isolation_options = 0;
            const SQLULEN levels[] = {SQL_TXN_READ_UNCOMMITTED, SQL_TXN_READ_COMMITTED,
                                      SQL_TXN_REPEATABLE_READ, SQL_TXN_SERIALIZABLE};
            const std::int32_t jdbc_levels[] = {1, 2, 4, 8};
            std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
            for (std::size_t index = 0; index < std::size(levels); ++index) {
                ParameterValue parameter;
                parameter.set_int_value(jdbc_levels[index]);
                bool supported = false;
                ParameterValue result_value;
                const SQLRETURN result = get_database_metadata(target, target,
                    "TransactionIsolationLevel", {parameter}, &result_value,
                    com::openjproxy::grpc::CALL_SUPPORTS);
                if (!SQL_SUCCEEDED(result)) {
                    return result;
                }
                if (result_value.value_case() != ParameterValue::kBoolValue) {
                    return fail(target, "JDBC metadata returned an invalid isolation capability", "HY000");
                }
                supported = result_value.bool_value();
                if (supported) {
                    isolation_options |= static_cast<SQLUINTEGER>(levels[index]);
                }
            }
            if (output_length != nullptr) {
                *output_length = static_cast<SQLSMALLINT>(sizeof(isolation_options));
            }
            if (value != nullptr) {
                std::memcpy(value, &isolation_options, sizeof(isolation_options));
            }
            return SQL_SUCCESS;
        }
        if (const auto* entry = find_static_info(info_type);
            entry != nullptr && entry->kind != InfoKind::text) {
            const bool small_value = entry->kind == InfoKind::small;
            if (output_length != nullptr) {
                *output_length = static_cast<SQLSMALLINT>(
                    small_value ? sizeof(SQLUSMALLINT) : sizeof(SQLUINTEGER));
            }
            if (value != nullptr) {
                if (small_value) {
                    const auto converted = static_cast<SQLUSMALLINT>(entry->number);
                    std::memcpy(value, &converted, sizeof(converted));
                } else {
                    std::memcpy(value, &entry->number, sizeof(entry->number));
                }
            }
            return SQL_SUCCESS;
        }
        if (info_type == SQL_TXN_CAPABLE || info_type == SQL_GETDATA_EXTENSIONS ||
            info_type == SQL_MAX_COLUMN_NAME_LEN || info_type == SQL_MAX_TABLE_NAME_LEN ||
            info_type == SQL_MAX_SCHEMA_NAME_LEN || info_type == SQL_MAX_CATALOG_NAME_LEN ||
            info_type == SQL_MAX_COLUMNS_IN_SELECT || info_type == SQL_MAX_COLUMNS_IN_TABLE ||
            info_type == SQL_MAX_TABLES_IN_SELECT || info_type == SQL_IDENTIFIER_CASE) {
            SQLUINTEGER numeric_value = 0;
            SQLRETURN result;
            {
                std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
                result = get_sql_info_number(target, target, info_type, &numeric_value);
            }
            if (!SQL_SUCCEEDED(result)) {
                return result;
            }
            const bool small_value = info_type != SQL_GETDATA_EXTENSIONS;
            const SQLSMALLINT length = small_value
                ? static_cast<SQLSMALLINT>(sizeof(SQLUSMALLINT))
                : static_cast<SQLSMALLINT>(sizeof(numeric_value));
            if (output_length != nullptr) {
                *output_length = length;
            }
            if (value != nullptr) {
                if (small_value) {
                    const auto converted = static_cast<SQLUSMALLINT>(numeric_value);
                    std::memcpy(value, &converted, sizeof(converted));
                } else {
                    std::memcpy(value, &numeric_value, sizeof(numeric_value));
                }
            }
            return SQL_SUCCESS;
        }
        std::string text;
        SQLRETURN result;
        {
            std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
            result = get_sql_info_text(target, target, info_type, &text);
        }
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        if (output_length != nullptr) {
            *output_length = static_cast<SQLSMALLINT>(text.size());
        }
        if (buffer_length < 0) {
            return fail(target, "Invalid SQLGetInfo output buffer length", "HY090");
        }
        if (value == nullptr) {
            return SQL_SUCCESS;
        }
        if (buffer_length == 0) {
            return text.empty() ? SQL_SUCCESS : SQL_SUCCESS_WITH_INFO;
        }
        const auto copy_count = std::min<std::size_t>(
            text.size(), static_cast<std::size_t>(buffer_length - 1));
        std::memcpy(value, text.data(), copy_count);
        static_cast<char*>(value)[copy_count] = '\0';
        if (copy_count < text.size()) {
            target->diagnostics.push_back({"01004", 0, "SQLGetInfo text was truncated"});
        }
        return copy_count < text.size() ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLGetInfoW(SQLHDBC connection, SQLUSMALLINT info_type, SQLPOINTER value,
                              SQLSMALLINT buffer_length, SQLSMALLINT* output_length) {
    return guarded(connection, "SQLGetInfoW", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        auto* target = static_cast<ConnectionHandle*>(connection);
        clear_diagnostics(target);
        if (info_type == SQL_DEFAULT_TXN_ISOLATION) {
            SQLULEN isolation = 0;
            {
                std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
                const auto result = get_transaction_isolation(target, target, &isolation);
                if (!SQL_SUCCEEDED(result)) {
                    return result;
                }
            }
            if (output_length != nullptr) {
                *output_length = static_cast<SQLSMALLINT>(sizeof(SQLUINTEGER));
            }
            if (value != nullptr) {
                const auto converted = static_cast<SQLUINTEGER>(isolation);
                std::memcpy(value, &converted, sizeof(converted));
            }
            return SQL_SUCCESS;
        }
        if (info_type == SQL_TXN_ISOLATION_OPTION) {
            SQLUINTEGER isolation_options = 0;
            const SQLULEN levels[] = {SQL_TXN_READ_UNCOMMITTED, SQL_TXN_READ_COMMITTED,
                                      SQL_TXN_REPEATABLE_READ, SQL_TXN_SERIALIZABLE};
            const std::int32_t jdbc_levels[] = {1, 2, 4, 8};
            std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
            for (std::size_t index = 0; index < std::size(levels); ++index) {
                ParameterValue parameter;
                parameter.set_int_value(jdbc_levels[index]);
                ParameterValue result_value;
                const SQLRETURN result = get_database_metadata(target, target,
                    "TransactionIsolationLevel", {parameter}, &result_value,
                    com::openjproxy::grpc::CALL_SUPPORTS);
                if (!SQL_SUCCEEDED(result)) {
                    return result;
                }
                if (result_value.value_case() != ParameterValue::kBoolValue) {
                    return fail(target, "JDBC metadata returned an invalid isolation capability", "HY000");
                }
                if (result_value.bool_value()) {
                    isolation_options |= static_cast<SQLUINTEGER>(levels[index]);
                }
            }
            if (output_length != nullptr) {
                *output_length = static_cast<SQLSMALLINT>(sizeof(isolation_options));
            }
            if (value != nullptr) {
                std::memcpy(value, &isolation_options, sizeof(isolation_options));
            }
            return SQL_SUCCESS;
        }
        if (const auto* entry = find_static_info(info_type);
            entry != nullptr && entry->kind != InfoKind::text) {
            const bool small_value = entry->kind == InfoKind::small;
            if (output_length != nullptr) {
                *output_length = static_cast<SQLSMALLINT>(
                    small_value ? sizeof(SQLUSMALLINT) : sizeof(SQLUINTEGER));
            }
            if (value != nullptr) {
                if (small_value) {
                    const auto converted = static_cast<SQLUSMALLINT>(entry->number);
                    std::memcpy(value, &converted, sizeof(converted));
                } else {
                    std::memcpy(value, &entry->number, sizeof(entry->number));
                }
            }
            return SQL_SUCCESS;
        }
        if (info_type == SQL_TXN_CAPABLE || info_type == SQL_GETDATA_EXTENSIONS ||
            info_type == SQL_MAX_COLUMN_NAME_LEN || info_type == SQL_MAX_TABLE_NAME_LEN ||
            info_type == SQL_MAX_SCHEMA_NAME_LEN || info_type == SQL_MAX_CATALOG_NAME_LEN ||
            info_type == SQL_MAX_COLUMNS_IN_SELECT || info_type == SQL_MAX_COLUMNS_IN_TABLE ||
            info_type == SQL_MAX_TABLES_IN_SELECT || info_type == SQL_IDENTIFIER_CASE) {
            SQLUINTEGER numeric_value = 0;
            SQLRETURN result;
            {
                std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
                result = get_sql_info_number(target, target, info_type, &numeric_value);
            }
            if (!SQL_SUCCEEDED(result)) {
                return result;
            }
            const bool small_value = info_type != SQL_GETDATA_EXTENSIONS;
            const SQLSMALLINT length = small_value
                ? static_cast<SQLSMALLINT>(sizeof(SQLUSMALLINT))
                : static_cast<SQLSMALLINT>(sizeof(numeric_value));
            if (output_length != nullptr) {
                *output_length = length;
            }
            if (value != nullptr) {
                if (small_value) {
                    const auto converted = static_cast<SQLUSMALLINT>(numeric_value);
                    std::memcpy(value, &converted, sizeof(converted));
                } else {
                    std::memcpy(value, &numeric_value, sizeof(numeric_value));
                }
            }
            return SQL_SUCCESS;
        }
        std::string text;
        SQLRETURN result;
        {
            std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
            result = get_sql_info_text(target, target, info_type, &text);
        }
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        // In SQLGetInfoW, buffer_length and output_length are measured in bytes, not characters.
        SQLSMALLINT wide_length = 0;
        const auto wide_result = write_wide_string(
            target, text, static_cast<SQLWCHAR*>(value),
            static_cast<SQLSMALLINT>(buffer_length / static_cast<SQLSMALLINT>(sizeof(SQLWCHAR))),
            &wide_length);
        if (output_length != nullptr) {
            *output_length = static_cast<SQLSMALLINT>(wide_length * sizeof(SQLWCHAR));
        }
        return wide_result;
    });
}

SQLRETURN SQL_API SQLGetDiagRec(SQLSMALLINT handle_type, SQLHANDLE handle,
                                SQLSMALLINT record_number, SQLCHAR* sql_state,
                                SQLINTEGER* native_error, SQLCHAR* message_text,
                                SQLSMALLINT buffer_length, SQLSMALLINT* text_length) {
    return guarded(nullptr, "SQLGetDiagRec", [&]() -> SQLRETURN {
        if (handle == SQL_NULL_HANDLE ||
            static_cast<HandleBase*>(handle)->type != handle_type) {
            return SQL_INVALID_HANDLE;
        }
        const auto& diagnostics = static_cast<HandleBase*>(handle)->diagnostics;
        if (record_number < 1 || static_cast<std::size_t>(record_number) > diagnostics.size()) {
            return SQL_NO_DATA;
        }
        const auto& diagnostic = diagnostics[static_cast<std::size_t>(record_number - 1)];
        if (sql_state != nullptr) {
            std::memcpy(sql_state, diagnostic.state.data(),
                        std::min<std::size_t>(5, diagnostic.state.size()));
            sql_state[5] = '\0';
        }
        if (native_error != nullptr) {
            *native_error = diagnostic.native_error;
        }
        if (text_length != nullptr) {
            *text_length = static_cast<SQLSMALLINT>(diagnostic.message.size());
        }
        if (message_text == nullptr || buffer_length <= 0) {
            return SQL_SUCCESS;
        }
        const auto copy_count = std::min<std::size_t>(
            diagnostic.message.size(), static_cast<std::size_t>(buffer_length - 1));
        std::memcpy(message_text, diagnostic.message.data(), copy_count);
        message_text[copy_count] = '\0';
        return copy_count < diagnostic.message.size() ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLGetDiagRecA(SQLSMALLINT handle_type, SQLHANDLE handle,
                                 SQLSMALLINT record_number, SQLCHAR* sql_state,
                                 SQLINTEGER* native_error, SQLCHAR* message_text,
                                 SQLSMALLINT buffer_length, SQLSMALLINT* text_length) {
    return guarded(nullptr, "SQLGetDiagRecA", [&]() -> SQLRETURN {
        if (handle == SQL_NULL_HANDLE ||
            static_cast<HandleBase*>(handle)->type != handle_type) {
            return SQL_INVALID_HANDLE;
        }
        const auto* base = static_cast<HandleBase*>(handle);
        if (record_number < 1 ||
            static_cast<std::size_t>(record_number) > base->diagnostics.size()) {
            return SQL_NO_DATA;
        }
        const auto& diagnostic = base->diagnostics[static_cast<std::size_t>(record_number - 1)];
        if (sql_state != nullptr) {
            std::memcpy(sql_state, diagnostic.state.data(),
                        std::min<std::size_t>(5, diagnostic.state.size()));
            sql_state[5] = '\0';
        }
        if (native_error != nullptr) {
            *native_error = diagnostic.native_error;
        }
        if (diagnostic.message.size() > static_cast<std::size_t>(
                std::numeric_limits<SQLSMALLINT>::max())) {
            return fail(const_cast<HandleBase*>(base), "Diagnostic message is too long", "HY000");
        }
        if (text_length != nullptr) {
            *text_length = static_cast<SQLSMALLINT>(diagnostic.message.size());
        }
        if (buffer_length < 0) {
            return fail(const_cast<HandleBase*>(base), "Invalid diagnostic buffer length", "HY090");
        }
        if (message_text == nullptr || buffer_length == 0) {
            return SQL_SUCCESS;
        }
        const auto copy_count = std::min<std::size_t>(
            diagnostic.message.size(), static_cast<std::size_t>(buffer_length - 1));
        std::memcpy(message_text, diagnostic.message.data(), copy_count);
        message_text[copy_count] = '\0';
        return copy_count < diagnostic.message.size() ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLGetDiagRecW(SQLSMALLINT handle_type, SQLHANDLE handle,
                                 SQLSMALLINT record_number, SQLWCHAR* sql_state,
                                 SQLINTEGER* native_error, SQLWCHAR* message_text,
                                 SQLSMALLINT buffer_length, SQLSMALLINT* text_length) {
    return guarded(nullptr, "SQLGetDiagRecW", [&]() -> SQLRETURN {
        if (handle == SQL_NULL_HANDLE ||
            static_cast<HandleBase*>(handle)->type != handle_type) {
            return SQL_INVALID_HANDLE;
        }
        const auto* base = static_cast<HandleBase*>(handle);
        if (record_number < 1 ||
            static_cast<std::size_t>(record_number) > base->diagnostics.size()) {
            return SQL_NO_DATA;
        }
        const auto& diagnostic = base->diagnostics[static_cast<std::size_t>(record_number - 1)];
        if (sql_state != nullptr) {
            for (std::size_t index = 0; index < 5; ++index) {
                sql_state[index] = static_cast<SQLWCHAR>(index < diagnostic.state.size()
                    ? diagnostic.state[index] : '\0');
            }
            sql_state[5] = 0;
        }
        if (native_error != nullptr) {
            *native_error = diagnostic.native_error;
        }
        return write_wide_string(const_cast<HandleBase*>(base), diagnostic.message,
                                 message_text, buffer_length, text_length);
    });
}

// Value of a diagnostic field (SQLGetDiagField), independent of ANSI/Unicode.
struct DiagFieldValue {
    enum class Kind { text, integer, length, return_code } kind = Kind::integer;
    std::string text;
    SQLLEN number = 0;
};

static bool odbc_specific_state(const std::string& state) {
    return state.rfind("IM", 0) == 0 || state.rfind("HY", 0) == 0 ||
           state.rfind("01S", 0) == 0 || state.rfind("07S", 0) == 0 ||
           state.rfind("08S", 0) == 0 || state.rfind("21S", 0) == 0 ||
           state.rfind("42S", 0) == 0;
}

static SQLRETURN diag_field_value(const HandleBase* base, SQLSMALLINT record_number,
                                  SQLSMALLINT diagnostic_id, DiagFieldValue* out) {
    using Kind = DiagFieldValue::Kind;
    switch (diagnostic_id) {
        case SQL_DIAG_NUMBER:
            out->kind = Kind::integer;
            out->number = static_cast<SQLLEN>(base->diagnostics.size());
            return SQL_SUCCESS;
        case SQL_DIAG_RETURNCODE:
            out->kind = Kind::return_code;
            out->number = base->diagnostics.empty() ? SQL_SUCCESS : SQL_ERROR;
            return SQL_SUCCESS;
        case SQL_DIAG_ROW_COUNT:
        case SQL_DIAG_CURSOR_ROW_COUNT:
            out->kind = Kind::length;
            out->number = 0;
            return SQL_SUCCESS;
        case SQL_DIAG_DYNAMIC_FUNCTION:
            out->kind = Kind::text;
            return SQL_SUCCESS;
        case SQL_DIAG_DYNAMIC_FUNCTION_CODE:
            out->kind = Kind::integer;
            out->number = SQL_DIAG_UNKNOWN_STATEMENT;
            return SQL_SUCCESS;
        default:
            break;
    }
    if (record_number < 1 ||
        static_cast<std::size_t>(record_number) > base->diagnostics.size()) {
        return SQL_NO_DATA;
    }
    const auto& diagnostic = base->diagnostics[static_cast<std::size_t>(record_number - 1)];
    switch (diagnostic_id) {
        case SQL_DIAG_SQLSTATE:
            out->kind = Kind::text;
            out->text = diagnostic.state;
            return SQL_SUCCESS;
        case SQL_DIAG_MESSAGE_TEXT:
            out->kind = Kind::text;
            out->text = diagnostic.message;
            return SQL_SUCCESS;
        case SQL_DIAG_NATIVE:
            out->kind = Kind::integer;
            out->number = diagnostic.native_error;
            return SQL_SUCCESS;
        case SQL_DIAG_CLASS_ORIGIN:
            out->kind = Kind::text;
            out->text = diagnostic.state.rfind("IM", 0) == 0 ? "ODBC 3.0" : "ISO 9075";
            return SQL_SUCCESS;
        case SQL_DIAG_SUBCLASS_ORIGIN:
            out->kind = Kind::text;
            out->text = odbc_specific_state(diagnostic.state) ? "ODBC 3.0" : "ISO 9075";
            return SQL_SUCCESS;
        case SQL_DIAG_CONNECTION_NAME:
        case SQL_DIAG_SERVER_NAME:
            out->kind = Kind::text;
            return SQL_SUCCESS;
        case SQL_DIAG_ROW_NUMBER:
            out->kind = Kind::length;
            out->number = SQL_NO_ROW_NUMBER;
            return SQL_SUCCESS;
        case SQL_DIAG_COLUMN_NUMBER:
            out->kind = Kind::integer;
            out->number = SQL_NO_COLUMN_NUMBER;
            return SQL_SUCCESS;
        default:
            log_message(LogLevel::warn, "unknown diagnostic field " + std::to_string(diagnostic_id));
            return SQL_ERROR;
    }
}

static SQLRETURN write_diag_number(const DiagFieldValue& value, SQLPOINTER diagnostic_info) {
    if (diagnostic_info == nullptr) {
        return SQL_ERROR;
    }
    switch (value.kind) {
        case DiagFieldValue::Kind::length:
            *static_cast<SQLLEN*>(diagnostic_info) = value.number;
            break;
        case DiagFieldValue::Kind::return_code:
            *static_cast<SQLRETURN*>(diagnostic_info) = static_cast<SQLRETURN>(value.number);
            break;
        default:
            *static_cast<SQLINTEGER*>(diagnostic_info) = static_cast<SQLINTEGER>(value.number);
            break;
    }
    return SQL_SUCCESS;
}

SQLRETURN SQL_API SQLGetDiagField(SQLSMALLINT handle_type, SQLHANDLE handle,
                                  SQLSMALLINT record_number, SQLSMALLINT diagnostic_id,
                                  SQLPOINTER diagnostic_info, SQLSMALLINT buffer_length,
                                  SQLSMALLINT* string_length) {
    return guarded(nullptr, "SQLGetDiagField", [&]() -> SQLRETURN {
        if (handle == SQL_NULL_HANDLE ||
            static_cast<HandleBase*>(handle)->type != handle_type) {
            return SQL_INVALID_HANDLE;
        }
        DiagFieldValue value;
        const SQLRETURN status = diag_field_value(static_cast<HandleBase*>(handle),
                                                  record_number, diagnostic_id, &value);
        if (status != SQL_SUCCESS) {
            return status;
        }
        if (value.kind != DiagFieldValue::Kind::text) {
            return write_diag_number(value, diagnostic_info);
        }
        const std::string& text = value.text;
        if (string_length != nullptr) {
            *string_length = static_cast<SQLSMALLINT>(text.size());
        }
        if (diagnostic_info == nullptr || buffer_length <= 0) {
            return text.empty() || diagnostic_info == nullptr ? SQL_SUCCESS : SQL_SUCCESS_WITH_INFO;
        }
        const auto copy_count = std::min<std::size_t>(
            text.size(), static_cast<std::size_t>(buffer_length - 1));
        std::memcpy(diagnostic_info, text.data(), copy_count);
        static_cast<char*>(diagnostic_info)[copy_count] = '\0';
        return copy_count < text.size() ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLGetDiagFieldW(SQLSMALLINT handle_type, SQLHANDLE handle,
                                   SQLSMALLINT record_number, SQLSMALLINT diagnostic_id,
                                   SQLPOINTER diagnostic_info, SQLSMALLINT buffer_length,
                                   SQLSMALLINT* string_length) {
    return guarded(nullptr, "SQLGetDiagFieldW", [&]() -> SQLRETURN {
        if (handle == SQL_NULL_HANDLE ||
            static_cast<HandleBase*>(handle)->type != handle_type) {
            return SQL_INVALID_HANDLE;
        }
        DiagFieldValue value;
        const SQLRETURN status = diag_field_value(static_cast<HandleBase*>(handle),
                                                  record_number, diagnostic_id, &value);
        if (status != SQL_SUCCESS) {
            return status;
        }
        if (value.kind != DiagFieldValue::Kind::text) {
            return write_diag_number(value, diagnostic_info);
        }
        std::vector<SQLWCHAR> wide;
        if (!utf8_to_wide(value.text, &wide)) {
            wide.clear();
            for (const unsigned char c : value.text) {
                wide.push_back(static_cast<SQLWCHAR>(c < 0x80 ? c : '?'));
            }
        }
        // In SQLGetDiagFieldW, buffer_length and string_length are measured in bytes.
        if (string_length != nullptr) {
            *string_length = static_cast<SQLSMALLINT>(wide.size() * sizeof(SQLWCHAR));
        }
        const auto capacity = buffer_length > 0
            ? static_cast<std::size_t>(buffer_length) / sizeof(SQLWCHAR) : 0;
        if (diagnostic_info == nullptr || capacity == 0) {
            return wide.empty() || diagnostic_info == nullptr ? SQL_SUCCESS : SQL_SUCCESS_WITH_INFO;
        }
        const auto copy_count = std::min(wide.size(), capacity - 1);
        auto* output = static_cast<SQLWCHAR*>(diagnostic_info);
        std::copy_n(wide.begin(), copy_count, output);
        output[copy_count] = 0;
        return copy_count < wide.size() ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
    });
}

SQLRETURN SQL_API SQLGetFunctions(SQLHDBC connection, SQLUSMALLINT function_id,
                                  SQLUSMALLINT* supported) {
    return guarded(connection, "SQLGetFunctions", [&]() -> SQLRETURN {
        if (connection == SQL_NULL_HDBC ||
            static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
            return SQL_INVALID_HANDLE;
        }
        if (supported == nullptr) {
            return fail(static_cast<HandleBase*>(connection), "Function support output is required", "HY009");
        }
        const std::vector<SQLUSMALLINT> functions = {
            SQL_API_SQLALLOCHANDLE, SQL_API_SQLFREEHANDLE, SQL_API_SQLSETENVATTR,
            SQL_API_SQLDRIVERCONNECT, SQL_API_SQLCONNECT, SQL_API_SQLDISCONNECT,
            SQL_API_SQLSETCONNECTATTR, SQL_API_SQLGETCONNECTATTR, SQL_API_SQLENDTRAN,
            SQL_API_SQLTRANSACT,
            SQL_API_SQLEXECDIRECT, SQL_API_SQLPREPARE, SQL_API_SQLBINDPARAMETER,
            SQL_API_SQLBINDCOL, SQL_API_SQLEXECUTE, SQL_API_SQLCANCEL,
            SQL_API_SQLPARAMDATA, SQL_API_SQLPUTDATA,
            SQL_API_SQLFETCH, SQL_API_SQLGETDATA,
            SQL_API_SQLNUMRESULTCOLS, SQL_API_SQLNUMPARAMS, SQL_API_SQLDESCRIBECOL,
            SQL_API_SQLROWCOUNT, SQL_API_SQLFREESTMT,
            SQL_API_SQLTABLES, SQL_API_SQLCOLUMNS, SQL_API_SQLPRIMARYKEYS,
            SQL_API_SQLFOREIGNKEYS, SQL_API_SQLGETTYPEINFO, SQL_API_SQLCOLATTRIBUTE,
            SQL_API_SQLSETSTMTATTR, SQL_API_SQLGETSTMTATTR, SQL_API_SQLCLOSECURSOR,
            SQL_API_SQLFETCHSCROLL, SQL_API_SQLMORERESULTS,
            SQL_API_SQLGETDIAGREC, SQL_API_SQLGETDIAGFIELD, SQL_API_SQLGETINFO,
            SQL_API_SQLGETFUNCTIONS};
        if (function_id == SQL_API_ALL_FUNCTIONS) {
            constexpr SQLUSMALLINT function_count = 100;
            std::fill(supported, supported + function_count, SQL_FALSE);
            for (const auto function : functions) {
                if (function < function_count) {
                    supported[function] = SQL_TRUE;
                }
            }
            return SQL_SUCCESS;
        }
        if (function_id == SQL_API_ODBC3_ALL_FUNCTIONS) {
            std::fill(supported, supported + SQL_API_ODBC3_ALL_FUNCTIONS_SIZE, SQL_FALSE);
            for (const auto function : functions) {
                supported[function >> 4] = static_cast<SQLUSMALLINT>(
                    supported[function >> 4] | (1U << (function & 0x0f)));
            }
            return SQL_SUCCESS;
        }
        const bool implemented = std::find(functions.begin(), functions.end(), function_id) != functions.end();
        *supported = implemented ? SQL_TRUE : SQL_FALSE;
        return SQL_SUCCESS;
    });
}

}  // extern "C"

#ifdef _WIN32
// Pins the driver in memory: gRPC/Abseil threads may outlive the FreeLibrary
// issued by the Driver Manager and would run code from already unloaded DLLs.
BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        HMODULE pinned = nullptr;
        GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_PIN | GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                           reinterpret_cast<LPCWSTR>(module), &pinned);
    }
    return TRUE;
}
#endif
