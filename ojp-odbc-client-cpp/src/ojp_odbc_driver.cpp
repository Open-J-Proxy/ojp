#include <sql.h>
#include <sqlext.h>

#include "StatementService.grpc.pb.h"

#include <google/protobuf/descriptor.h>
#include <google/protobuf/message.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <random>
#include <string>
#include <variant>
#include <vector>

#include <grpcpp/grpcpp.h>

namespace {

using com::openjproxy::grpc::ConnectionDetails;
using com::openjproxy::grpc::OpQueryResultProto;
using com::openjproxy::grpc::OpResult;
using com::openjproxy::grpc::ParameterProto;
using com::openjproxy::grpc::ParameterTypeProto;
using com::openjproxy::grpc::ParameterValue;
using com::openjproxy::grpc::SessionInfo;
using com::openjproxy::grpc::StatementRequest;
using com::openjproxy::grpc::StatementService;

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

struct ConnectionHandle final : HandleBase {
    ConnectionHandle() : HandleBase(SQL_HANDLE_DBC) {}

    std::string endpoint;
    std::string url;
    std::string user;
    std::string password;
    std::string client_uuid;
    std::shared_ptr<grpc::Channel> channel;
    std::unique_ptr<StatementService::Stub> stub;
    SessionInfo session;
    std::mutex operation_mutex;
    bool connected = false;
};

struct BoundParameter {
    SQLSMALLINT direction = SQL_PARAM_INPUT;
    SQLSMALLINT value_type = SQL_C_DEFAULT;
    SQLSMALLINT parameter_type = SQL_UNKNOWN_TYPE;
    SQLPOINTER value = nullptr;
    SQLLEN buffer_length = 0;
    SQLLEN* indicator = nullptr;
};

struct BoundColumn {
    SQLSMALLINT value_type = SQL_C_DEFAULT;
    SQLPOINTER value = nullptr;
    SQLLEN buffer_length = 0;
    SQLLEN* indicator = nullptr;
};

using Cell = std::variant<std::monostate, bool, std::int32_t, std::int64_t, double,
                          std::string, std::vector<std::uint8_t>>;

struct StatementHandle final : HandleBase {
    explicit StatementHandle(ConnectionHandle* parent)
        : HandleBase(SQL_HANDLE_STMT), connection(parent) {}

    ConnectionHandle* connection;
    std::string sql;
    std::map<SQLUSMALLINT, BoundParameter> parameters;
    std::map<SQLUSMALLINT, BoundColumn> bound_columns;
    std::vector<std::string> columns;
    std::vector<std::vector<Cell>> rows;
    SQLLEN row_count = -1;
    std::size_t row_index = 0;
    bool has_result_set = false;
};

struct ParsedConnectionString {
    std::map<std::string, std::string> values;
    std::string error;
};

void set_string_field(google::protobuf::Message* message, const std::string& name,
                      const std::string& value) {
    const auto* field = message->GetDescriptor()->FindFieldByName(name);
    if (field != nullptr && field->cpp_type() == google::protobuf::FieldDescriptor::CPPTYPE_STRING) {
        message->GetReflection()->SetString(message, field, value);
    }
}

std::string get_string_field(const google::protobuf::Message& message, const std::string& name) {
    const auto* field = message.GetDescriptor()->FindFieldByName(name);
    if (field == nullptr || field->cpp_type() != google::protobuf::FieldDescriptor::CPPTYPE_STRING) {
        return {};
    }
    return message.GetReflection()->GetString(message, field);
}

std::int32_t get_int32_field(const google::protobuf::Message& message, const std::string& name) {
    const auto* field = message.GetDescriptor()->FindFieldByName(name);
    if (field == nullptr || field->cpp_type() != google::protobuf::FieldDescriptor::CPPTYPE_INT32) {
        return 0;
    }
    return message.GetReflection()->GetInt32(message, field);
}

void set_bool_field(google::protobuf::Message* message, const std::string& name, bool value) {
    const auto* field = message->GetDescriptor()->FindFieldByName(name);
    if (field != nullptr && field->cpp_type() == google::protobuf::FieldDescriptor::CPPTYPE_BOOL) {
        message->GetReflection()->SetBool(message, field, value);
    }
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

void clear_diagnostics(HandleBase* handle) {
    if (handle != nullptr) {
        handle->diagnostics.clear();
    }
}

SQLRETURN fail(HandleBase* handle, std::string message, std::string state = "HY000",
               SQLINTEGER native_error = 0) {
    if (handle != nullptr) {
        handle->diagnostics.push_back({std::move(state), native_error, std::move(message)});
    }
    return SQL_ERROR;
}

SQLRETURN fail_grpc(HandleBase* handle, const grpc::Status& status,
                    const grpc::ClientContext& context) {
    std::string sql_state;
    std::string message;
    SQLINTEGER native_error = 0;
    const auto& metadata = context.GetServerTrailingMetadata();
    for (const auto& item : metadata) {
        if (item.first == "com.openjproxy.grpc.sqlerrorresponse-bin" ||
            item.first.find("sqlerrorresponse-bin") != std::string::npos) {
            com::openjproxy::grpc::SqlErrorResponse response;
            if (response.ParseFromString(std::string(item.second.data(), item.second.size()))) {
                sql_state = get_string_field(response, "sqlState");
                message = get_string_field(response, "reason");
                native_error = get_int32_field(response, "vendorCode");
                break;
            }
        }
    }
    if (message.empty()) {
        message = status.error_message();
    }
    if (sql_state.empty()) {
        sql_state = "08S01";
    }
    return fail(handle, std::move(message), std::move(sql_state), native_error);
}

bool decode_value(const ParameterValue& value, Cell* output) {
    const auto* field = value.GetReflection()->GetOneofFieldDescriptor(
        value, value.GetDescriptor()->oneof_decl(0));
    if (field == nullptr) {
        *output = std::monostate{};
        return true;
    }
    const auto* reflection = value.GetReflection();
    switch (field->cpp_type()) {
        case google::protobuf::FieldDescriptor::CPPTYPE_BOOL:
            if (field->name() == "is_null" && reflection->GetBool(value, field)) {
                *output = std::monostate{};
            } else {
                *output = reflection->GetBool(value, field);
            }
            return true;
        case google::protobuf::FieldDescriptor::CPPTYPE_INT32:
            *output = reflection->GetInt32(value, field);
            return true;
        case google::protobuf::FieldDescriptor::CPPTYPE_INT64:
            *output = reflection->GetInt64(value, field);
            return true;
        case google::protobuf::FieldDescriptor::CPPTYPE_FLOAT:
            *output = static_cast<double>(reflection->GetFloat(value, field));
            return true;
        case google::protobuf::FieldDescriptor::CPPTYPE_DOUBLE:
            *output = reflection->GetDouble(value, field);
            return true;
        case google::protobuf::FieldDescriptor::CPPTYPE_STRING: {
            const auto contents = reflection->GetString(value, field);
            if (field->type() == google::protobuf::FieldDescriptor::TYPE_BYTES) {
                *output = std::vector<std::uint8_t>(contents.begin(), contents.end());
            } else {
                *output = contents;
            }
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
        return std::to_string(*value);
    }
    if (const auto* value = std::get_if<bool>(&cell)) {
        return *value ? "1" : "0";
    }
    if (const auto* value = std::get_if<std::vector<std::uint8_t>>(&cell)) {
        return std::string(value->begin(), value->end());
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
    if (target_type == SQL_C_LONG || target_type == SQL_C_SLONG) {
        SQLINTEGER converted = 0;
        if (const auto* value = std::get_if<std::int64_t>(&cell)) {
            converted = static_cast<SQLINTEGER>(*value);
        } else if (const auto* value = std::get_if<std::int32_t>(&cell)) {
            converted = static_cast<SQLINTEGER>(*value);
        } else if (const auto* value = std::get_if<bool>(&cell)) {
            converted = *value ? 1 : 0;
        } else {
            return fail(handle, "Result value cannot be converted to SQL_C_LONG", "07006");
        }
        write_numeric(target_value, indicator, converted);
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_SBIGINT) {
        SQLBIGINT converted = 0;
        if (const auto* value = std::get_if<std::int64_t>(&cell)) {
            converted = static_cast<SQLBIGINT>(*value);
        } else if (const auto* value = std::get_if<std::int32_t>(&cell)) {
            converted = static_cast<SQLBIGINT>(*value);
        } else {
            return fail(handle, "Result value cannot be converted to SQL_C_SBIGINT", "07006");
        }
        write_numeric(target_value, indicator, converted);
        return SQL_SUCCESS;
    }
    if (target_type == SQL_C_DOUBLE || target_type == SQL_C_FLOAT) {
        double number = 0;
        if (const auto* value = std::get_if<double>(&cell)) {
            number = *value;
        } else if (const auto* value = std::get_if<std::int64_t>(&cell)) {
            number = static_cast<double>(*value);
        } else if (const auto* value = std::get_if<std::int32_t>(&cell)) {
            number = static_cast<double>(*value);
        } else {
            return fail(handle, "Result value cannot be converted to a floating-point type", "07006");
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
        const auto* boolean = std::get_if<bool>(&cell);
        const auto* integer = std::get_if<std::int64_t>(&cell);
        const auto* small_integer = std::get_if<std::int32_t>(&cell);
        const SQLCHAR converted = boolean != nullptr
            ? static_cast<SQLCHAR>(*boolean)
            : static_cast<SQLCHAR>((integer != nullptr && *integer != 0) ||
                                   (small_integer != nullptr && *small_integer != 0));
        write_numeric(target_value, indicator, converted);
        return SQL_SUCCESS;
    }
    return fail(handle, "ODBC C target type is not supported by the OJP L1 client", "07006");
}

bool is_query_sql(const std::string& sql) {
    std::size_t position = 0;
    while (position < sql.size() && std::isspace(static_cast<unsigned char>(sql[position]))) {
        ++position;
    }
    std::string keyword;
    while (position < sql.size() && std::isalpha(static_cast<unsigned char>(sql[position]))) {
        keyword.push_back(static_cast<char>(std::toupper(static_cast<unsigned char>(sql[position]))));
        ++position;
    }
    return keyword == "SELECT" || keyword == "WITH" || keyword == "VALUES" ||
           keyword == "TABLE" || keyword == "SHOW" || keyword == "EXPLAIN";
}

bool set_parameter_value(ParameterValue* value, const BoundParameter& bound,
                         SQLUSMALLINT parameter_index, Diagnostic* error) {
    const auto* data = static_cast<const std::uint8_t*>(bound.value);
    SQLLEN length = bound.buffer_length;
    if (bound.indicator != nullptr) {
        length = *bound.indicator;
        if (length == SQL_NULL_DATA) {
            value->GetReflection()->SetBool(value,
                value->GetDescriptor()->FindFieldByName("is_null"), true);
            return true;
        }
    }
    if (data == nullptr) {
        value->GetReflection()->SetBool(value,
            value->GetDescriptor()->FindFieldByName("is_null"), true);
        return true;
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
            default: c_type = SQL_C_CHAR; break;
        }
    }
    const auto* descriptor = value->GetDescriptor();
    const auto* reflection = value->GetReflection();
    if (c_type == SQL_C_CHAR) {
        std::size_t text_length = length == SQL_NTS
            ? std::strlen(reinterpret_cast<const char*>(data))
            : static_cast<std::size_t>(std::max<SQLLEN>(0, length));
        if (bound.buffer_length > 0 && length != SQL_NTS) {
            text_length = std::min(text_length, static_cast<std::size_t>(bound.buffer_length));
        }
        const auto* field = descriptor->FindFieldByName("string_value");
        reflection->SetString(value, field,
            std::string(reinterpret_cast<const char*>(data), text_length));
        return true;
    }
    auto set_integer = [&](const std::string& field_name, std::int64_t number) {
        const auto* field = descriptor->FindFieldByName(field_name);
        if (field->cpp_type() == google::protobuf::FieldDescriptor::CPPTYPE_INT32) {
            reflection->SetInt32(value, field, static_cast<std::int32_t>(number));
        } else {
            reflection->SetInt64(value, field, number);
        }
    };
    switch (c_type) {
        case SQL_C_STINYINT:
            set_integer("int_value", *reinterpret_cast<const SQLSCHAR*>(data));
            return true;
        case SQL_C_SSHORT:
            set_integer("int_value", *reinterpret_cast<const SQLSMALLINT*>(data));
            return true;
        case SQL_C_SLONG:
            set_integer("int_value", *reinterpret_cast<const SQLINTEGER*>(data));
            return true;
        case SQL_C_SBIGINT:
            set_integer("long_value", *reinterpret_cast<const SQLBIGINT*>(data));
            return true;
        case SQL_C_FLOAT:
            reflection->SetFloat(value, descriptor->FindFieldByName("float_value"),
                                 *reinterpret_cast<const float*>(data));
            return true;
        case SQL_C_DOUBLE:
            reflection->SetDouble(value, descriptor->FindFieldByName("double_value"),
                                  *reinterpret_cast<const double*>(data));
            return true;
        case SQL_C_BIT:
            reflection->SetBool(value, descriptor->FindFieldByName("bool_value"),
                                *reinterpret_cast<const SQLCHAR*>(data) != 0);
            return true;
        case SQL_C_BINARY: {
            const auto* field = descriptor->FindFieldByName("bytes_value");
            const auto byte_count = static_cast<std::size_t>(std::max<SQLLEN>(0, length));
            reflection->SetString(value, field,
                std::string(reinterpret_cast<const char*>(data), byte_count));
            return true;
        }
        default:
            error->state = "07006";
            error->message = "ODBC C data type is not supported by the OJP L1 client";
            error->native_error = static_cast<SQLINTEGER>(parameter_index);
            return false;
    }
}

ParameterTypeProto parameter_type(const BoundParameter& bound) {
    if (bound.value == nullptr ||
        (bound.indicator != nullptr && *bound.indicator == SQL_NULL_DATA)) {
        return static_cast<ParameterTypeProto>(0); // PT_NULL
    }
    switch (bound.parameter_type) {
        case SQL_TINYINT: return static_cast<ParameterTypeProto>(2);   // PT_BYTE
        case SQL_SMALLINT: return static_cast<ParameterTypeProto>(3);  // PT_SHORT
        case SQL_INTEGER: return static_cast<ParameterTypeProto>(4);   // PT_INT
        case SQL_BIGINT: return static_cast<ParameterTypeProto>(5);    // PT_LONG
        case SQL_REAL: return static_cast<ParameterTypeProto>(6);      // PT_FLOAT
        case SQL_FLOAT:
        case SQL_DOUBLE: return static_cast<ParameterTypeProto>(7);   // PT_DOUBLE
        case SQL_BINARY:
        case SQL_VARBINARY:
        case SQL_LONGVARBINARY: return static_cast<ParameterTypeProto>(10); // PT_BYTES
        case SQL_BIT: return static_cast<ParameterTypeProto>(1);       // PT_BOOLEAN
        default: return static_cast<ParameterTypeProto>(9);            // PT_STRING
    }
}

SQLRETURN execute_statement(StatementHandle* statement) {
    auto* connection = statement->connection;
    clear_diagnostics(statement);
    statement->columns.clear();
    statement->rows.clear();
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

    StatementRequest request;
    request.mutable_session()->CopyFrom(connection->session);
    request.set_sql(statement->sql);
    for (const auto& entry : statement->parameters) {
        const auto& bound = entry.second;
        if (bound.direction != SQL_PARAM_INPUT) {
            return fail(statement, "Only input parameters are supported", "HYC00");
        }
        auto* parameter = request.add_parameters();
        parameter->set_index(static_cast<std::int32_t>(entry.first));
        parameter->set_type(parameter_type(bound));
        auto* value = parameter->add_values();
        Diagnostic parameter_error;
        if (!set_parameter_value(value, bound, entry.first, &parameter_error)) {
            return fail(statement, parameter_error.message, parameter_error.state,
                        parameter_error.native_error);
        }
    }

    if (is_query_sql(statement->sql)) {
        grpc::ClientContext context;
        context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
        auto reader = connection->stub->ExecuteQuery(&context, request);
        OpResult result;
        while (reader->Read(&result)) {
            if (result.has_session()) {
                connection->session.CopyFrom(result.session());
            }
            if (!result.has_query_result()) {
                continue;
            }
            const OpQueryResultProto& query = result.query_result();
            if (statement->columns.empty()) {
                statement->columns.assign(query.labels().begin(), query.labels().end());
            }
            for (const auto& row : query.rows()) {
                std::vector<Cell> decoded;
                decoded.reserve(static_cast<std::size_t>(row.columns_size()));
                for (const auto& column : row.columns()) {
                    Cell cell;
                    if (!decode_value(column, &cell)) {
                        return fail(statement, "OJP returned a result type unsupported by L1", "HY000");
                    }
                    decoded.push_back(std::move(cell));
                }
                statement->rows.push_back(std::move(decoded));
            }
        }
        const auto status = reader->Finish();
        if (!status.ok()) {
            return fail_grpc(statement, status, context);
        }
        statement->has_result_set = true;
        statement->row_count = static_cast<SQLLEN>(statement->rows.size());
        return SQL_SUCCESS;
    }

    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    OpResult result;
    const auto status = connection->stub->ExecuteUpdate(&context, request, &result);
    if (!status.ok()) {
        return fail_grpc(statement, status, context);
    }
    if (result.has_session()) {
        connection->session.CopyFrom(result.session());
    }
    if (result.type() != com::openjproxy::grpc::INTEGER || !result.has_int_value()) {
        return fail(statement, "OJP returned an invalid update result", "HY000");
    }
    statement->row_count = result.int_value();
    return SQL_SUCCESS;
}

SQLRETURN connect(ConnectionHandle* connection, const std::string& connection_string) {
    clear_diagnostics(connection);
    const auto parsed = parse_connection_string(connection_string);
    if (!parsed.error.empty()) {
        return fail(connection, parsed.error, "IM012");
    }
    std::lock_guard<std::mutex> connection_lock(connection->operation_mutex);
    auto find = [&parsed](const std::string& key) -> std::string {
        const auto entry = parsed.values.find(key);
        return entry == parsed.values.end() ? std::string{} : entry->second;
    };
    connection->endpoint = find("SERVER");
    if (connection->endpoint.empty()) {
        connection->endpoint = find("ENDPOINT");
    }
    connection->url = find("DATABASE");
    if (connection->url.empty()) {
        connection->url = find("URL");
    }
    connection->user = find("UID");
    if (connection->user.empty()) {
        connection->user = find("USER");
    }
    connection->password = find("PWD");
    if (connection->endpoint.empty() || connection->url.empty()) {
        return fail(connection, "Connection string requires SERVER and DATABASE fields", "IM002");
    }
    if (connection->connected) {
        return fail(connection, "ODBC connection is already open", "08002");
    }
    connection->client_uuid = make_client_uuid();
    connection->channel = grpc::CreateChannel(connection->endpoint, grpc::InsecureChannelCredentials());
    connection->stub = StatementService::NewStub(connection->channel);

    ConnectionDetails details;
    details.set_url(connection->url);
    details.set_user(connection->user);
    details.set_password(connection->password);
    set_string_field(&details, "clientUUID", connection->client_uuid);
    set_bool_field(&details, "isXA", false);

    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    SessionInfo session;
    const auto status = connection->stub->Connect(&context, details, &session);
    if (!status.ok()) {
        connection->stub.reset();
        connection->channel.reset();
        return fail_grpc(connection, status, context);
    }
    connection->session.CopyFrom(session);
    connection->connected = true;
    return SQL_SUCCESS;
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
    const auto status = connection->stub->TerminateSession(&context, connection->session, &response);
    if (!status.ok()) {
        return fail_grpc(connection, status, context);
    }
    if (!response.terminated()) {
        return fail(connection, "OJP server did not terminate the session", "HY000");
    }
    connection->connected = false;
    connection->stub.reset();
    connection->channel.reset();
    return SQL_SUCCESS;
}

}  // namespace

extern "C" {

SQLRETURN SQL_API SQLAllocHandle(SQLSMALLINT handle_type, SQLHANDLE input_handle,
                                 SQLHANDLE* output_handle) {
    if (output_handle == nullptr) {
        return SQL_ERROR;
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
    return SQL_ERROR;
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
}

SQLRETURN SQL_API SQLDriverConnect(SQLHDBC connection, SQLHWND, SQLCHAR* input_string,
                                   SQLSMALLINT input_length, SQLCHAR* output_string,
                                   SQLSMALLINT output_buffer_length,
                                   SQLSMALLINT* output_length, SQLUSMALLINT) {
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
    if (output_length != nullptr) {
        *output_length = static_cast<SQLSMALLINT>(length);
    }
    if (output_string == nullptr || output_buffer_length <= 0) {
        return SQL_SUCCESS;
    }
    const auto copy_count = std::min<std::size_t>(
        length, static_cast<std::size_t>(output_buffer_length - 1));
    std::memcpy(output_string, input, copy_count);
    output_string[copy_count] = '\0';
    if (copy_count < length) {
        static_cast<HandleBase*>(connection)->diagnostics.push_back(
            {"01004", 0, "Connection string output was truncated"});
        return SQL_SUCCESS_WITH_INFO;
    }
    return SQL_SUCCESS;
}

SQLRETURN SQL_API SQLConnect(SQLHDBC connection, SQLCHAR*, SQLSMALLINT,
                             SQLCHAR*, SQLSMALLINT, SQLCHAR*, SQLSMALLINT) {
    if (connection == SQL_NULL_HDBC ||
        static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
        return SQL_INVALID_HANDLE;
    }
    return fail(static_cast<HandleBase*>(connection),
                "Use SQLDriverConnect with SERVER and DATABASE fields", "IM002");
}

SQLRETURN SQL_API SQLDisconnect(SQLHDBC connection) {
    if (connection == SQL_NULL_HDBC ||
        static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
        return SQL_INVALID_HANDLE;
    }
    return disconnect(static_cast<ConnectionHandle*>(connection));
}

SQLRETURN SQL_API SQLExecDirect(SQLHSTMT statement, SQLCHAR* sql, SQLINTEGER length) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    if (sql == nullptr) {
        return fail(static_cast<HandleBase*>(statement), "SQL text is required", "HY009");
    }
    auto* target = static_cast<StatementHandle*>(statement);
    target->sql.assign(reinterpret_cast<const char*>(sql),
        length == SQL_NTS ? std::strlen(reinterpret_cast<const char*>(sql))
                          : static_cast<std::size_t>(std::max<SQLINTEGER>(0, length)));
    target->parameters.clear();
    return execute_statement(target);
}

SQLRETURN SQL_API SQLPrepare(SQLHSTMT statement, SQLCHAR* sql, SQLINTEGER length) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    if (sql == nullptr) {
        return fail(static_cast<HandleBase*>(statement), "SQL text is required", "HY009");
    }
    auto* target = static_cast<StatementHandle*>(statement);
    target->sql.assign(reinterpret_cast<const char*>(sql),
        length == SQL_NTS ? std::strlen(reinterpret_cast<const char*>(sql))
                          : static_cast<std::size_t>(std::max<SQLINTEGER>(0, length)));
    target->parameters.clear();
    return SQL_SUCCESS;
}

SQLRETURN SQL_API SQLBindParameter(SQLHSTMT statement, SQLUSMALLINT parameter_number,
                                   SQLSMALLINT input_output_type, SQLSMALLINT value_type,
                                   SQLSMALLINT parameter_type, SQLULEN, SQLSMALLINT,
                                   SQLPOINTER value, SQLLEN buffer_length, SQLLEN* indicator) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    clear_diagnostics(target);
    if (parameter_number == 0) {
        return fail(target, "ODBC parameter numbers start at 1", "07009");
    }
    if (input_output_type != SQL_PARAM_INPUT) {
        return fail(target, "Only input parameters are supported", "HYC00");
    }
    target->parameters[parameter_number] = {
        input_output_type, value_type, parameter_type, value, buffer_length, indicator};
    return SQL_SUCCESS;
}

SQLRETURN SQL_API SQLBindCol(SQLHSTMT statement, SQLUSMALLINT column_number,
                             SQLSMALLINT target_type, SQLPOINTER target_value,
                             SQLLEN buffer_length, SQLLEN* indicator) {
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
}

SQLRETURN SQL_API SQLExecute(SQLHSTMT statement) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    return execute_statement(static_cast<StatementHandle*>(statement));
}

SQLRETURN SQL_API SQLFetch(SQLHSTMT statement) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    clear_diagnostics(target);
    if (!target->has_result_set) {
        return fail(target, "Statement does not have a result set", "24000");
    }
    if (target->row_index >= target->rows.size()) {
        return SQL_NO_DATA;
    }
    ++target->row_index;
    SQLRETURN result = SQL_SUCCESS;
    const auto& row = target->rows[target->row_index - 1];
    for (const auto& binding : target->bound_columns) {
        if (binding.first == 0 || binding.first > row.size()) {
            return fail(target, "Bound column number exceeds the result column count", "07009");
        }
        const auto& column = binding.second;
        const auto column_result = write_cell(target, row[binding.first - 1],
            column.value_type, column.value, column.buffer_length, column.indicator);
        if (!SQL_SUCCEEDED(column_result)) {
            return column_result;
        }
        if (column_result == SQL_SUCCESS_WITH_INFO) {
            result = SQL_SUCCESS_WITH_INFO;
        }
    }
    return result;
}

SQLRETURN SQL_API SQLGetData(SQLHSTMT statement, SQLUSMALLINT column_number,
                             SQLSMALLINT target_type, SQLPOINTER target_value,
                             SQLLEN buffer_length, SQLLEN* indicator) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    clear_diagnostics(target);
    if (target->row_index == 0 || target->row_index > target->rows.size() ||
        column_number == 0 || column_number > target->rows[target->row_index - 1].size()) {
        return fail(target, "No current row or invalid column number", "07009");
    }
    const Cell& cell = target->rows[target->row_index - 1][column_number - 1];
    return write_cell(target, cell, target_type, target_value, buffer_length, indicator);
}

SQLRETURN SQL_API SQLNumResultCols(SQLHSTMT statement, SQLSMALLINT* column_count) {
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
}

SQLRETURN SQL_API SQLDescribeCol(SQLHSTMT statement, SQLUSMALLINT column_number,
                                 SQLCHAR* column_name, SQLSMALLINT name_buffer_length,
                                 SQLSMALLINT* name_length, SQLSMALLINT* data_type,
                                 SQLULEN* column_size, SQLSMALLINT* decimal_digits,
                                 SQLSMALLINT* nullable) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    if (column_number == 0 || column_number > target->columns.size()) {
        return fail(target, "Invalid column number", "07009");
    }
    const std::string& name = target->columns[column_number - 1];
    if (name_length != nullptr) {
        *name_length = static_cast<SQLSMALLINT>(name.size());
    }
    if (column_name != nullptr && name_buffer_length > 0) {
        const auto copy_count = std::min<std::size_t>(
            name.size(), static_cast<std::size_t>(name_buffer_length - 1));
        std::memcpy(column_name, name.data(), copy_count);
        column_name[copy_count] = '\0';
    }
    SQLSMALLINT inferred_type = SQL_VARCHAR;
    if (!target->rows.empty() && column_number <= target->rows.front().size()) {
        const Cell& cell = target->rows.front()[column_number - 1];
        if (std::holds_alternative<std::int64_t>(cell)) {
            inferred_type = SQL_BIGINT;
        } else if (std::holds_alternative<std::int32_t>(cell)) {
            inferred_type = SQL_INTEGER;
        } else if (std::holds_alternative<double>(cell)) {
            inferred_type = SQL_DOUBLE;
        } else if (std::holds_alternative<bool>(cell)) {
            inferred_type = SQL_BIT;
        } else if (std::holds_alternative<std::vector<std::uint8_t>>(cell)) {
            inferred_type = SQL_VARBINARY;
        }
    }
    if (data_type != nullptr) {
        *data_type = inferred_type;
    }
    if (column_size != nullptr) {
        *column_size = 255;
    }
    if (decimal_digits != nullptr) {
        *decimal_digits = 0;
    }
    if (nullable != nullptr) {
        *nullable = SQL_NULLABLE;
    }
    return column_name != nullptr && name.size() >= static_cast<std::size_t>(name_buffer_length)
        ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
}

SQLRETURN SQL_API SQLRowCount(SQLHSTMT statement, SQLLEN* row_count) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    if (row_count == nullptr) {
        return fail(static_cast<HandleBase*>(statement), "Row count output is required", "HY009");
    }
    *row_count = static_cast<StatementHandle*>(statement)->row_count;
    return SQL_SUCCESS;
}

SQLRETURN SQL_API SQLFreeStmt(SQLHSTMT statement, SQLUSMALLINT option) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    clear_diagnostics(target);
    if (option == SQL_CLOSE || option == SQL_UNBIND || option == SQL_RESET_PARAMS) {
        if (option == SQL_CLOSE) {
            target->columns.clear();
            target->rows.clear();
            target->row_count = -1;
            target->row_index = 0;
            target->has_result_set = false;
        } else if (option == SQL_UNBIND) {
            target->bound_columns.clear();
        } else {
            target->parameters.clear();
        }
        return SQL_SUCCESS;
    }
    return fail(target, "Invalid SQLFreeStmt option", "HY092");
}

SQLRETURN SQL_API SQLSetConnectAttr(SQLHDBC connection, SQLINTEGER attribute,
                                    SQLPOINTER value, SQLINTEGER) {
    if (connection == SQL_NULL_HDBC ||
        static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<ConnectionHandle*>(connection);
    clear_diagnostics(target);
    if (attribute == SQL_ATTR_AUTOCOMMIT &&
        reinterpret_cast<std::uintptr_t>(value) == SQL_AUTOCOMMIT_ON) {
        return SQL_SUCCESS;
    }
    return fail(target, "Only autocommit mode is supported at L1", "HYC00");
}

SQLRETURN SQL_API SQLGetConnectAttr(SQLHDBC connection, SQLINTEGER attribute,
                                    SQLPOINTER value, SQLINTEGER, SQLINTEGER* length) {
    if (connection == SQL_NULL_HDBC ||
        static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
        return SQL_INVALID_HANDLE;
    }
    if (attribute != SQL_ATTR_AUTOCOMMIT || value == nullptr) {
        return fail(static_cast<HandleBase*>(connection),
                    "Connection attribute is not supported", "HYC00");
    }
    *static_cast<SQLULEN*>(value) = SQL_AUTOCOMMIT_ON;
    if (length != nullptr) {
        *length = static_cast<SQLINTEGER>(sizeof(SQLULEN));
    }
    return SQL_SUCCESS;
}

SQLRETURN SQL_API SQLSetStmtAttr(SQLHSTMT statement, SQLINTEGER attribute,
                                 SQLPOINTER value, SQLINTEGER) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    if (attribute == SQL_ATTR_ROW_ARRAY_SIZE &&
        reinterpret_cast<std::uintptr_t>(value) == 1) {
        return SQL_SUCCESS;
    }
    return fail(target, "Only a single-row fetch array is supported", "HYC00");
}

SQLRETURN SQL_API SQLGetInfo(SQLHDBC connection, SQLUSMALLINT info_type, SQLPOINTER value,
                             SQLSMALLINT buffer_length, SQLSMALLINT* output_length) {
    if (connection == SQL_NULL_HDBC ||
        static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
        return SQL_INVALID_HANDLE;
    }
    std::string text;
    switch (info_type) {
        case SQL_DRIVER_NAME: text = "libojp_odbc"; break;
        case SQL_DRIVER_VER: text = "00.01.0000"; break;
        case SQL_DRIVER_ODBC_VER: text = "03.80"; break;
        case SQL_DBMS_NAME: text = "OJP"; break;
        case SQL_DBMS_VER: text = "00.01.0000"; break;
        case SQL_ODBC_VER: text = "03.80"; break;
        case SQL_IDENTIFIER_QUOTE_CHAR: text = "\""; break;
        default:
            return fail(static_cast<HandleBase*>(connection),
                        "Requested SQLGetInfo value is not supported", "HYC00");
    }
    if (output_length != nullptr) {
        *output_length = static_cast<SQLSMALLINT>(text.size());
    }
    if (value == nullptr || buffer_length <= 0) {
        return SQL_SUCCESS;
    }
    const auto copy_count = std::min<std::size_t>(
        text.size(), static_cast<std::size_t>(buffer_length - 1));
    std::memcpy(value, text.data(), copy_count);
    static_cast<char*>(value)[copy_count] = '\0';
    return copy_count < text.size() ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
}

SQLRETURN SQL_API SQLGetDiagRec(SQLSMALLINT handle_type, SQLHANDLE handle,
                                SQLSMALLINT record_number, SQLCHAR* sql_state,
                                SQLINTEGER* native_error, SQLCHAR* message_text,
                                SQLSMALLINT buffer_length, SQLSMALLINT* text_length) {
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
}

SQLRETURN SQL_API SQLGetFunctions(SQLHDBC connection, SQLUSMALLINT function_id,
                                  SQLUSMALLINT* supported) {
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
        SQL_API_SQLEXECDIRECT, SQL_API_SQLPREPARE, SQL_API_SQLBINDPARAMETER,
        SQL_API_SQLBINDCOL, SQL_API_SQLEXECUTE, SQL_API_SQLFETCH, SQL_API_SQLGETDATA,
        SQL_API_SQLNUMRESULTCOLS, SQL_API_SQLDESCRIBECOL, SQL_API_SQLROWCOUNT,
        SQL_API_SQLFREESTMT, SQL_API_SQLGETDIAGREC, SQL_API_SQLGETINFO,
        SQL_API_SQLGETFUNCTIONS};
    if (function_id == SQL_API_ALL_FUNCTIONS) {
        std::fill(supported, supported + SQL_API_ALL_FUNCTIONS_SIZE, SQL_FALSE);
        for (const auto function : functions) {
            if (function < SQL_API_ALL_FUNCTIONS_SIZE) {
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
}

}  // extern "C"
