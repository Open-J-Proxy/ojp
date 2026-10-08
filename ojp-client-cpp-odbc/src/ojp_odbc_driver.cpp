#include <sql.h>
#include <sqlext.h>

#include "StatementService.grpc.pb.h"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <iomanip>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <random>
#include <set>
#include <sstream>
#include <string>
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

constexpr char kRowByRowMode[] = "RESULT_SET_ROW_BY_ROW_MODE";

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
    std::set<std::string> savepoints;
    std::map<std::string, std::string> savepoint_names;
    SQLULEN transaction_isolation = 0;
    bool auto_commit = true;
    bool connected = false;
};

struct BoundParameter {
    SQLSMALLINT direction = SQL_PARAM_INPUT;
    SQLSMALLINT value_type = SQL_C_DEFAULT;
    SQLSMALLINT parameter_type = SQL_UNKNOWN_TYPE;
    SQLPOINTER value = nullptr;
    SQLLEN buffer_length = 0;
    SQLLEN* indicator = nullptr;
    bool data_at_execution = false;
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
    std::vector<SQLUSMALLINT> data_at_execution_parameters;
    std::size_t next_data_at_execution_parameter = 0;
    SQLUSMALLINT current_data_at_execution_parameter = 0;
    bool has_result_set = false;
};

struct ParsedConnectionString {
    std::map<std::string, std::string> values;
    std::string error;
};

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
                sql_state = response.sqlstate();
                message = response.reason();
                native_error = response.vendorcode();
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
            const auto& instant = value.timestamp_value().instant();
            const std::time_t timestamp = static_cast<std::time_t>(instant.seconds());
            std::tm utc_time{};
            if (gmtime_r(&timestamp, &utc_time) == nullptr) {
                return false;
            }
            std::ostringstream text;
            text << std::put_time(&utc_time, "%Y-%m-%d %H:%M:%S") << format_fraction(instant.nanos());
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
        return std::to_string(*value);
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
    return fail(handle, "ODBC C target type is not supported by the OJP client", "07006");
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
    auto set_decimal = [&](const std::string& decimal_text) {
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
                error->state = "22018";
                error->message = "Invalid decimal parameter";
                error->native_error = static_cast<SQLINTEGER>(parameter_index);
                return false;
            }
        }
        if (digits.empty()) {
            error->state = "22018";
            error->message = "Invalid decimal parameter";
            error->native_error = static_cast<SQLINTEGER>(parameter_index);
            return false;
        }
        const auto first_significant = digits.find_first_not_of('0');
        digits = first_significant == std::string::npos ? "0" : digits.substr(first_significant);
        std::string unscaled = negative && digits != "0" ? "-" + digits : digits;
        std::string wire;
        wire.push_back('\1');
        const auto length = static_cast<std::uint32_t>(unscaled.size());
        for (int shift = 24; shift >= 0; shift -= 8) {
            wire.push_back(static_cast<char>((length >> shift) & 0xff));
        }
        wire.append(unscaled);
        const auto wire_scale = static_cast<std::uint32_t>(scale);
        for (int shift = 24; shift >= 0; shift -= 8) {
            wire.push_back(static_cast<char>((wire_scale >> shift) & 0xff));
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
            auto* timestamp_value = value->mutable_timestamp_value();
            const std::int64_t year = timestamp->year - (timestamp->month <= 2 ? 1 : 0);
            const std::int64_t era = (year >= 0 ? year : year - 399) / 400;
            const auto year_of_era = static_cast<std::uint32_t>(year - era * 400);
            const auto adjusted_month = static_cast<std::int32_t>(timestamp->month) +
                (timestamp->month > 2 ? -3 : 9);
            const auto day_of_year = (153 * adjusted_month + 2) / 5 +
                static_cast<std::int32_t>(timestamp->day) - 1;
            const auto day_of_era = year_of_era * 365 + year_of_era / 4 -
                year_of_era / 100 + static_cast<std::uint32_t>(day_of_year);
            const std::int64_t days = era * 146097 + day_of_era - 719468;
            const std::int64_t seconds = days * 86400 +
                timestamp->hour * 3600 + timestamp->minute * 60 + timestamp->second;
            auto* instant = timestamp_value->mutable_instant();
            instant->set_seconds(seconds);
            instant->set_nanos(static_cast<std::int32_t>(timestamp->fraction));
            timestamp_value->set_timezone("UTC");
            timestamp_value->set_original_type(com::openjproxy::grpc::TEMPORAL_TYPE_TIMESTAMP);
            return true;
        }
        return false;
    };
    if (c_type == SQL_C_CHAR) {
        std::size_t text_length = length == SQL_NTS
            ? std::strlen(reinterpret_cast<const char*>(data))
            : static_cast<std::size_t>(std::max<SQLLEN>(0, length));
        if (bound.buffer_length > 0 && length != SQL_NTS) {
            text_length = std::min(text_length, static_cast<std::size_t>(bound.buffer_length));
        }
        if (bound.parameter_type == SQL_DECIMAL || bound.parameter_type == SQL_NUMERIC) {
            return set_decimal(std::string(reinterpret_cast<const char*>(data), text_length));
        }
        value->set_string_value(std::string(reinterpret_cast<const char*>(data), text_length));
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
        case SQL_C_STINYINT:
            value->set_int_value(*reinterpret_cast<const SQLSCHAR*>(data));
            return true;
        case SQL_C_SSHORT:
            value->set_int_value(*reinterpret_cast<const SQLSMALLINT*>(data));
            return true;
        case SQL_C_SLONG:
            value->set_int_value(*reinterpret_cast<const SQLINTEGER*>(data));
            return true;
        case SQL_C_SBIGINT:
            value->set_long_value(*reinterpret_cast<const SQLBIGINT*>(data));
            return true;
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
            error->message = "ODBC C data type is not supported by the OJP client";
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
        case SQL_LONGVARCHAR:
        case SQL_WLONGVARCHAR: return PT_CLOB;
        case SQL_BIT: return PT_BOOLEAN;
        case SQL_TYPE_DATE: return PT_DATE;
        case SQL_TYPE_TIME: return PT_TIME;
        case SQL_TYPE_TIMESTAMP: return PT_TIMESTAMP;
        default: return PT_STRING;
    }
}

bool is_lob_parameter_type(SQLSMALLINT sql_type) {
    return sql_type == SQL_LONGVARBINARY || sql_type == SQL_LONGVARCHAR ||
           sql_type == SQL_WLONGVARCHAR;
}

bool get_lob_parameter_data(const BoundParameter& bound, std::string* data, Diagnostic* error) {
    if (bound.data_at_execution) {
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
        *data = bound.streamed_data;
        return true;
    }
    if (bound.value == nullptr) {
        error->state = "HY009";
        error->message = "LOB parameter buffer is required";
        return false;
    }
    if (bound.value_type != SQL_C_DEFAULT && bound.value_type != SQL_C_CHAR &&
        bound.value_type != SQL_C_BINARY) {
        error->state = "07006";
        error->message = "LOB parameters require SQL_C_CHAR or SQL_C_BINARY values";
        return false;
    }
    const auto* bytes = static_cast<const char*>(bound.value);
    SQLLEN length = bound.indicator == nullptr ? bound.buffer_length : *bound.indicator;
    if (length == SQL_NTS) {
        if (bound.value_type != SQL_C_CHAR) {
            error->state = "HY090";
            error->message = "SQL_NTS is only valid for character LOB parameters";
            return false;
        }
        length = static_cast<SQLLEN>(std::strlen(bytes));
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
    data->assign(bytes, static_cast<std::size_t>(length));
    return true;
}

std::size_t utf8_character_units(const std::string& text, std::size_t length) {
    std::size_t units = 0;
    for (std::size_t index = 0; index < length;) {
        const auto character = static_cast<unsigned char>(text[index]);
        const std::size_t width = character < 0x80 ? 1 : (character < 0xe0 ? 2 :
            (character < 0xf0 ? 3 : 4));
        units += width == 4 ? 2 : 1;
        index += std::min(width, length - index);
    }
    return units;
}

std::size_t lob_chunk_end(const std::string& data, std::size_t offset, LobType lob_type) {
    constexpr std::size_t kLobChunkSize = 64 * 1024;
    std::size_t end = std::min(data.size(), offset + kLobChunkSize);
    if (lob_type == com::openjproxy::grpc::LT_CLOB && end < data.size()) {
        while (end > offset &&
               (static_cast<unsigned char>(data[end]) & 0xc0) == 0x80) {
            --end;
        }
        if (end == offset) {
            end = std::min(data.size(), offset + kLobChunkSize);
        }
    }
    return end;
}

SQLRETURN create_lob(ConnectionHandle* connection, HandleBase* handle, LobType lob_type,
                     const std::string& data, std::string* uuid) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    if (data.size() > static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max())) {
        return fail(handle, "LOB input exceeds the supported size", "22001");
    }
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    auto writer = connection->stub->createLob(&context);
    std::size_t offset = 0;
    std::size_t character_position = 1;
    bool first_block = true;
    bool received_reference = false;
    LobReference reference;
    while (first_block || offset < data.size()) {
        const std::size_t end = lob_chunk_end(data, offset, lob_type);
        LobDataBlock block;
        block.mutable_session()->CopyFrom(connection->session);
        block.set_position(lob_type == com::openjproxy::grpc::LT_CLOB
                               ? static_cast<std::int64_t>(character_position)
                               : static_cast<std::int64_t>(offset + 1));
        block.set_lobtype(lob_type);
        block.set_data(data.data() + offset, end - offset);
        if (!writer->Write(block)) {
            const auto status = writer->Finish();
            return status.ok() ? fail(handle, "OJP closed the LOB upload stream", "08S01")
                               : fail_grpc(handle, status, context);
        }
        if (lob_type == com::openjproxy::grpc::LT_CLOB) {
            character_position += utf8_character_units(data, end - offset);
        }
        offset = end;
        first_block = false;
        if (!received_reference) {
            if (!writer->Read(&reference)) {
                const auto status = writer->Finish();
                return status.ok() ? fail(handle, "OJP did not return a LOB reference", "HY000")
                                   : fail_grpc(handle, status, context);
            }
            received_reference = true;
            connection->session.CopyFrom(reference.session());
        }
    }
    if (!writer->WritesDone()) {
        const auto status = writer->Finish();
        return status.ok() ? fail(handle, "Unable to finish the LOB upload stream", "08S01")
                           : fail_grpc(handle, status, context);
    }
    while (writer->Read(&reference)) {
        connection->session.CopyFrom(reference.session());
    }
    const auto status = writer->Finish();
    if (!status.ok()) {
        return fail_grpc(handle, status, context);
    }
    if (!received_reference || reference.uuid().empty()) {
        return fail(handle, "OJP returned an empty LOB reference", "HY000");
    }
    connection->session.CopyFrom(reference.session());
    *uuid = reference.uuid();
    return SQL_SUCCESS;
}

SQLRETURN read_lob(ConnectionHandle* connection, HandleBase* handle, const std::string& uuid,
                   LobType lob_type, std::string* data) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    com::openjproxy::grpc::ReadLobRequest request;
    request.mutable_lobreference()->mutable_session()->CopyFrom(connection->session);
    request.mutable_lobreference()->set_uuid(uuid);
    request.mutable_lobreference()->set_lobtype(lob_type);
    request.set_position(1);
    request.set_length(std::numeric_limits<std::int32_t>::max());
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    auto reader = connection->stub->readLob(&context, request);
    LobDataBlock block;
    while (reader->Read(&block)) {
        if (block.has_session()) {
            connection->session.CopyFrom(block.session());
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
    request.mutable_session()->CopyFrom(connection->session);
    request.set_resourcetype(com::openjproxy::grpc::RES_RESULT_SET);
    request.set_resourceuuid(result_set_uuid);
    request.mutable_target()->set_calltype(com::openjproxy::grpc::CALL_CLOSE);
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    com::openjproxy::grpc::CallResourceResponse response;
    const auto status = connection->stub->callResource(&context, request, &response);
    if (!status.ok()) {
        return fail_grpc(statement, status, context);
    }
    if (response.has_session()) {
        connection->session.CopyFrom(response.session());
    }
    return SQL_SUCCESS;
}

template <typename Invoke>
SQLRETURN invoke_session_rpc(ConnectionHandle* connection, HandleBase* handle, Invoke invoke) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    SessionInfo response;
    const auto status = invoke(&context, connection->session, &response);
    if (!status.ok()) {
        return fail_grpc(handle, status, context);
    }
    connection->session.CopyFrom(response);
    return SQL_SUCCESS;
}

SQLRETURN call_resource(ConnectionHandle* connection, HandleBase* handle,
                        com::openjproxy::grpc::ResourceType resource_type,
                        const std::string& resource_uuid,
                        com::openjproxy::grpc::CallType call_type,
                        const std::string& resource_name,
                        const std::vector<ParameterValue>& parameters,
                        com::openjproxy::grpc::CallResourceResponse* output) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    com::openjproxy::grpc::CallResourceRequest request;
    request.mutable_session()->CopyFrom(connection->session);
    request.set_resourcetype(resource_type);
    request.set_resourceuuid(resource_uuid);
    auto* target = request.mutable_target();
    target->set_calltype(call_type);
    target->set_resourcename(resource_name);
    for (const auto& parameter : parameters) {
        target->add_params()->CopyFrom(parameter);
    }
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    com::openjproxy::grpc::CallResourceResponse response;
    const auto status = connection->stub->callResource(&context, request, &response);
    if (!status.ok()) {
        return fail_grpc(handle, status, context);
    }
    if (response.has_session()) {
        connection->session.CopyFrom(response.session());
    }
    if (output != nullptr) {
        output->CopyFrom(response);
    }
    return SQL_SUCCESS;
}

SQLRETURN start_transaction(ConnectionHandle* connection, HandleBase* handle) {
    return invoke_session_rpc(connection, handle,
        [connection](grpc::ClientContext* context, const SessionInfo& request,
                     SessionInfo* response) {
            return connection->stub->startTransaction(context, request, response);
        });
}

SQLRETURN end_transaction(ConnectionHandle* connection, HandleBase* handle,
                          SQLSMALLINT completion_type) {
    if (!connection->connected || !connection->stub) {
        return fail(handle, "ODBC connection is not open", "08003");
    }
    if (connection->auto_commit) {
        return SQL_SUCCESS;
    }
    const auto result = completion_type == SQL_COMMIT
        ? invoke_session_rpc(connection, handle,
            [connection](grpc::ClientContext* context, const SessionInfo& request,
                         SessionInfo* response) {
                return connection->stub->commitTransaction(context, request, response);
            })
        : invoke_session_rpc(connection, handle,
            [connection](grpc::ClientContext* context, const SessionInfo& request,
                         SessionInfo* response) {
                return connection->stub->rollbackTransaction(context, request, response);
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

    std::string savepoint_name;
    const SavepointAction savepoint_action =
        parse_savepoint_statement(statement->sql, &savepoint_name);
    if (savepoint_action != SavepointAction::NONE) {
        return execute_savepoint_statement(statement, savepoint_action, savepoint_name);
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
        auto* value = parameter->add_values();
        const bool is_null = bound.value == nullptr ||
            (bound.indicator != nullptr && *bound.indicator == SQL_NULL_DATA);
        if (is_lob_parameter_type(bound.parameter_type) && !is_null) {
            std::string lob_data;
            Diagnostic parameter_error;
            if (!get_lob_parameter_data(bound, &lob_data, &parameter_error)) {
                return fail(statement, parameter_error.message, parameter_error.state);
            }
            const LobType lob_type = bound.parameter_type == SQL_LONGVARBINARY
                ? com::openjproxy::grpc::LT_BLOB : com::openjproxy::grpc::LT_CLOB;
            std::string lob_uuid;
            const auto lob_result = create_lob(connection, statement, lob_type, lob_data, &lob_uuid);
            if (!SQL_SUCCEEDED(lob_result)) {
                return lob_result;
            }
            parameter->set_type(bound.parameter_type == SQL_LONGVARBINARY
                ? com::openjproxy::grpc::PT_BLOB : com::openjproxy::grpc::PT_CLOB);
            value->set_string_value(lob_uuid);
            continue;
        }
        if (bound.data_at_execution) {
            return fail(statement, "Data-at-execution is only supported for LOB parameters", "HYC00");
        }
        parameter->set_type(parameter_type(bound));
        Diagnostic parameter_error;
        if (!set_parameter_value(value, bound, entry.first, &parameter_error)) {
            return fail(statement, parameter_error.message, parameter_error.state,
                        parameter_error.native_error);
        }
    }

    if (is_query_sql(statement->sql)) {
        std::string result_set_uuid;
        bool row_by_row = false;
        bool decoded_all = true;
        auto append_result = [&](const OpResult& result) {
            if (result.has_session()) {
                connection->session.CopyFrom(result.session());
            }
            if (result.flag() == kRowByRowMode) {
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

        grpc::ClientContext context;
        context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
        auto reader = connection->stub->executeQuery(&context, request);
        OpResult result;
        while (reader->Read(&result)) {
            append_result(result);
        }
        const auto status = reader->Finish();
        if (!status.ok()) {
            return fail_grpc(statement, status, context);
        }
        // SQL Server and DB2 send one row at a time when the result has binary or LOB
        // columns; the remaining rows must be pulled with fetchNextRows.
        while (row_by_row && !result_set_uuid.empty()) {
            com::openjproxy::grpc::ResultSetFetchRequest fetch;
            fetch.mutable_session()->CopyFrom(connection->session);
            fetch.set_resultsetuuid(result_set_uuid);
            fetch.set_size(1);
            grpc::ClientContext fetch_context;
            fetch_context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
            OpResult next;
            const auto fetch_status = connection->stub->fetchNextRows(&fetch_context, fetch, &next);
            if (!fetch_status.ok()) {
                return fail_grpc(statement, fetch_status, fetch_context);
            }
            if (append_result(next) == 0) {
                break;
            }
        }
        if (!result_set_uuid.empty()) {
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

    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    OpResult result;
    const auto status = connection->stub->executeUpdate(&context, request, &result);
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
    details.set_clientuuid(connection->client_uuid);
    details.set_isxa(false);

    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(30));
    SessionInfo session;
    const auto status = connection->stub->connect(&context, details, &session);
    if (!status.ok()) {
        connection->stub.reset();
        connection->channel.reset();
        return fail_grpc(connection, status, context);
    }
    connection->session.CopyFrom(session);
    connection->connected = true;
    if (connection->transaction_isolation != 0) {
        const auto isolation_result = set_transaction_isolation(
            connection, connection, connection->transaction_isolation);
        if (!SQL_SUCCEEDED(isolation_result)) {
            return isolation_result;
        }
    }
    if (!connection->auto_commit) {
        const auto transaction_result = start_transaction(connection, connection);
        if (!SQL_SUCCEEDED(transaction_result)) {
            return transaction_result;
        }
    }
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
    const auto status = connection->stub->terminateSession(&context, connection->session, &response);
    // terminateSession is sent exactly once; the connection is unusable even if it fails.
    connection->connected = false;
    connection->stub.reset();
    connection->channel.reset();
    connection->savepoints.clear();
    connection->savepoint_names.clear();
    connection->auto_commit = true;
    connection->transaction_isolation = 0;
    if (!status.ok()) {
        return fail_grpc(connection, status, context);
    }
    if (!response.terminated()) {
        return fail(connection, "OJP server did not terminate the session", "HY000");
    }
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
    target->parameters[parameter_number].data_at_execution =
        indicator != nullptr &&
        (*indicator == SQL_DATA_AT_EXEC || *indicator <= SQL_LEN_DATA_AT_EXEC_OFFSET);
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
    auto* target = static_cast<StatementHandle*>(statement);
    clear_diagnostics(target);
    target->data_at_execution_parameters.clear();
    target->next_data_at_execution_parameter = 0;
    target->current_data_at_execution_parameter = 0;
    for (auto& entry : target->parameters) {
        auto& parameter = entry.second;
        parameter.streamed_data.clear();
        if (parameter.data_at_execution) {
            if (!is_lob_parameter_type(parameter.parameter_type)) {
                return fail(target, "Data-at-execution is only supported for LOB parameters", "HYC00");
            }
            target->data_at_execution_parameters.push_back(entry.first);
        }
    }
    if (!target->data_at_execution_parameters.empty()) {
        return SQL_NEED_DATA;
    }
    return execute_statement(target);
}

SQLRETURN SQL_API SQLParamData(SQLHSTMT statement, SQLPOINTER* value) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    clear_diagnostics(target);
    if (target->data_at_execution_parameters.empty()) {
        return fail(target, "No data-at-execution parameter is pending", "HY010");
    }
    if (value == nullptr) {
        return fail(target, "Parameter token output is required", "HY009");
    }
    if (target->current_data_at_execution_parameter != 0) {
        target->current_data_at_execution_parameter = 0;
        ++target->next_data_at_execution_parameter;
    }
    if (target->next_data_at_execution_parameter < target->data_at_execution_parameters.size()) {
        const auto parameter_number =
            target->data_at_execution_parameters[target->next_data_at_execution_parameter];
        target->current_data_at_execution_parameter = parameter_number;
        *value = target->parameters.at(parameter_number).value;
        return SQL_NEED_DATA;
    }
    target->data_at_execution_parameters.clear();
    target->next_data_at_execution_parameter = 0;
    return execute_statement(target);
}

SQLRETURN SQL_API SQLPutData(SQLHSTMT statement, SQLPOINTER data, SQLLEN length) {
    if (statement == SQL_NULL_HSTMT ||
        static_cast<HandleBase*>(statement)->type != SQL_HANDLE_STMT) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<StatementHandle*>(statement);
    clear_diagnostics(target);
    if (target->current_data_at_execution_parameter == 0) {
        return fail(target, "SQLParamData must request a parameter before SQLPutData", "HY010");
    }
    if (length == SQL_NULL_DATA ||
        (length < 0 && length != SQL_NTS)) {
        return fail(target, "LOB stream chunk length is invalid", "HY090");
    }
    if (length == SQL_NTS) {
        const auto parameter_type = target->parameters.at(
            target->current_data_at_execution_parameter).value_type;
        if (parameter_type != SQL_C_CHAR || data == nullptr) {
            return fail(target, "SQL_NTS requires a character LOB chunk", "HY090");
        }
        length = static_cast<SQLLEN>(std::strlen(static_cast<const char*>(data)));
    }
    if (length > 0 && data == nullptr) {
        return fail(target, "LOB stream chunk buffer is required", "HY009");
    }
    if (length > 0) {
        auto& parameter = target->parameters.at(target->current_data_at_execution_parameter);
        constexpr std::size_t kMaxLobSize =
            static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max());
        if (static_cast<std::size_t>(length) > kMaxLobSize - parameter.streamed_data.size()) {
            return fail(target, "LOB stream exceeds the supported size", "22001");
        }
        parameter.streamed_data.append(static_cast<const char*>(data),
                                       static_cast<std::size_t>(length));
    }
    return SQL_SUCCESS;
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
    Cell materialized_cell;
    const auto lob_result = materialize_lob_cell(target, cell, target_type, &materialized_cell);
    if (!SQL_SUCCEEDED(lob_result)) {
        return lob_result;
    }
    return write_cell(target, materialized_cell, target_type, target_value,
                      buffer_length, indicator);
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
        } else if (std::holds_alternative<UuidCell>(cell)) {
            inferred_type = SQL_GUID;
        } else if (const auto* text = std::get_if<std::string>(&cell)) {
            if (text->compare(0, 16, "OJP_CLOB_PREFIX:") == 0) {
                inferred_type = SQL_LONGVARCHAR;
            } else if (is_uuid(*text)) {
                inferred_type = SQL_LONGVARBINARY;
            }
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
            target->data_at_execution_parameters.clear();
            target->next_data_at_execution_parameter = 0;
            target->current_data_at_execution_parameter = 0;
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
    if (value == nullptr && attribute != SQL_ATTR_AUTOCOMMIT) {
        return fail(target, "Connection attribute value is required", "HY009");
    }
    std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
    const auto option = static_cast<SQLULEN>(reinterpret_cast<std::uintptr_t>(value));
    if (attribute == SQL_ATTR_AUTOCOMMIT) {
        if (option != SQL_AUTOCOMMIT_ON && option != SQL_AUTOCOMMIT_OFF) {
            return fail(target, "Invalid autocommit option", "HY024");
        }
        return set_auto_commit(target, target, option == SQL_AUTOCOMMIT_ON);
    }
    if (attribute == SQL_ATTR_TXN_ISOLATION) {
        std::int32_t jdbc_isolation = 0;
        if (!jdbc_transaction_isolation(option, &jdbc_isolation)) {
            return fail(target, "Unsupported transaction isolation level", "HY024");
        }
        if (!target->connected) {
            target->transaction_isolation = option;
            return SQL_SUCCESS;
        }
        return set_transaction_isolation(target, target, option);
    }
    return fail(target, "Connection attribute is not supported", "HYC00");
}

SQLRETURN SQL_API SQLGetConnectAttr(SQLHDBC connection, SQLINTEGER attribute,
                                    SQLPOINTER value, SQLINTEGER, SQLINTEGER* length) {
    if (connection == SQL_NULL_HDBC ||
        static_cast<HandleBase*>(connection)->type != SQL_HANDLE_DBC) {
        return SQL_INVALID_HANDLE;
    }
    auto* target = static_cast<ConnectionHandle*>(connection);
    clear_diagnostics(target);
    if (value == nullptr) {
        return fail(target, "Connection attribute output is required", "HY009");
    }
    std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
    if (attribute == SQL_ATTR_AUTOCOMMIT) {
        *static_cast<SQLULEN*>(value) =
            target->auto_commit ? SQL_AUTOCOMMIT_ON : SQL_AUTOCOMMIT_OFF;
    } else if (attribute == SQL_ATTR_TXN_ISOLATION) {
        if (target->connected) {
            const auto result = get_transaction_isolation(
                target, target, static_cast<SQLULEN*>(value));
            if (!SQL_SUCCEEDED(result)) {
                return result;
            }
        } else {
            *static_cast<SQLULEN*>(value) = target->transaction_isolation;
        }
    } else {
        return fail(target, "Connection attribute is not supported", "HYC00");
    }
    if (length != nullptr) {
        *length = static_cast<SQLINTEGER>(sizeof(SQLULEN));
    }
    return SQL_SUCCESS;
}

SQLRETURN SQL_API SQLEndTran(SQLSMALLINT handle_type, SQLHANDLE handle,
                             SQLSMALLINT completion_type) {
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
}

SQLRETURN SQL_API SQLTransact(SQLHENV environment, SQLHDBC connection,
                              SQLUSMALLINT completion_type) {
    if (connection == SQL_NULL_HDBC) {
        if (environment == SQL_NULL_HENV ||
            static_cast<HandleBase*>(environment)->type != SQL_HANDLE_ENV) {
            return SQL_INVALID_HANDLE;
        }
        return fail(static_cast<HandleBase*>(environment),
                    "Environment-wide transactions are not supported", "HYC00");
    }
    return SQLEndTran(SQL_HANDLE_DBC, connection,
                      static_cast<SQLSMALLINT>(completion_type));
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
    auto* target = static_cast<ConnectionHandle*>(connection);
    clear_diagnostics(target);
    if (info_type == SQL_TXN_CAPABLE) {
        const SQLUSMALLINT numeric_value =
            uppercase_ascii(target->url).find("SQLSERVER") != std::string::npos
                ? SQL_TC_ALL : SQL_TC_DML;
        if (output_length != nullptr) {
            *output_length = static_cast<SQLSMALLINT>(sizeof(numeric_value));
        }
        if (value == nullptr) {
            return SQL_SUCCESS;
        }
        std::memcpy(value, &numeric_value, sizeof(numeric_value));
        return SQL_SUCCESS;
    }
    if (info_type == SQL_DEFAULT_TXN_ISOLATION) {
        if (value == nullptr) {
            return SQL_SUCCESS;
        }
        std::lock_guard<std::mutex> connection_lock(target->operation_mutex);
        SQLULEN isolation = 0;
        const auto result = get_transaction_isolation(target, target, &isolation);
        if (!SQL_SUCCEEDED(result)) {
            return result;
        }
        const auto isolation_value = static_cast<SQLUINTEGER>(isolation);
        std::memcpy(value, &isolation_value, sizeof(isolation_value));
        if (output_length != nullptr) {
            *output_length = static_cast<SQLSMALLINT>(sizeof(isolation_value));
        }
        return SQL_SUCCESS;
    }
    if (info_type == SQL_TXN_ISOLATION_OPTION) {
        const SQLUINTEGER isolation_options =
            SQL_TXN_READ_UNCOMMITTED | SQL_TXN_READ_COMMITTED |
            SQL_TXN_REPEATABLE_READ | SQL_TXN_SERIALIZABLE;
        if (output_length != nullptr) {
            *output_length = static_cast<SQLSMALLINT>(sizeof(isolation_options));
        }
        if (value != nullptr) {
            std::memcpy(value, &isolation_options, sizeof(isolation_options));
        }
        return SQL_SUCCESS;
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
            return fail(target, "Requested SQLGetInfo value is not supported", "HYC00");
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

SQLRETURN SQL_API SQLGetDiagRecA(SQLSMALLINT handle_type, SQLHANDLE handle,
                                 SQLSMALLINT record_number, SQLCHAR* sql_state,
                                 SQLINTEGER* native_error, SQLCHAR* message_text,
                                 SQLSMALLINT buffer_length, SQLSMALLINT* text_length) {
    return SQLGetDiagRec(handle_type, handle, record_number, sql_state, native_error,
                         message_text, buffer_length, text_length);
}

SQLRETURN SQL_API SQLGetDiagField(SQLSMALLINT handle_type, SQLHANDLE handle,
                                  SQLSMALLINT record_number, SQLSMALLINT diagnostic_id,
                                  SQLPOINTER diagnostic_info, SQLSMALLINT buffer_length,
                                  SQLSMALLINT* string_length) {
    if (handle == SQL_NULL_HANDLE ||
        static_cast<HandleBase*>(handle)->type != handle_type) {
        return SQL_INVALID_HANDLE;
    }
    const auto* base = static_cast<HandleBase*>(handle);
    if (diagnostic_id == SQL_DIAG_NUMBER) {
        if (diagnostic_info == nullptr) {
            return SQL_ERROR;
        }
        *static_cast<SQLINTEGER*>(diagnostic_info) =
            static_cast<SQLINTEGER>(base->diagnostics.size());
        return SQL_SUCCESS;
    }
    if (record_number < 1 ||
        static_cast<std::size_t>(record_number) > base->diagnostics.size()) {
        return SQL_NO_DATA;
    }
    const auto& diagnostic = base->diagnostics[static_cast<std::size_t>(record_number - 1)];
    if (diagnostic_id == SQL_DIAG_NATIVE) {
        if (diagnostic_info == nullptr) {
            return SQL_ERROR;
        }
        *static_cast<SQLINTEGER*>(diagnostic_info) = diagnostic.native_error;
        return SQL_SUCCESS;
    }
    std::string text;
    if (diagnostic_id == SQL_DIAG_SQLSTATE) {
        text = diagnostic.state;
    } else if (diagnostic_id == SQL_DIAG_MESSAGE_TEXT) {
        text = diagnostic.message;
    } else {
        return SQL_ERROR;
    }
    if (string_length != nullptr) {
        *string_length = static_cast<SQLSMALLINT>(text.size());
    }
    if (diagnostic_info == nullptr || buffer_length <= 0) {
        return SQL_SUCCESS;
    }
    const auto copy_count = std::min<std::size_t>(
        text.size(), static_cast<std::size_t>(buffer_length - 1));
    std::memcpy(diagnostic_info, text.data(), copy_count);
    static_cast<char*>(diagnostic_info)[copy_count] = '\0';
    return copy_count < text.size() ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
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
        SQL_API_SQLSETCONNECTATTR, SQL_API_SQLGETCONNECTATTR, SQL_API_SQLENDTRAN,
        SQL_API_SQLTRANSACT,
        SQL_API_SQLEXECDIRECT, SQL_API_SQLPREPARE, SQL_API_SQLBINDPARAMETER,
        SQL_API_SQLBINDCOL, SQL_API_SQLEXECUTE, SQL_API_SQLFETCH, SQL_API_SQLGETDATA,
        SQL_API_SQLNUMRESULTCOLS, SQL_API_SQLDESCRIBECOL, SQL_API_SQLROWCOUNT,
        SQL_API_SQLFREESTMT, SQL_API_SQLGETDIAGREC, SQL_API_SQLGETDIAGFIELD, SQL_API_SQLGETINFO,
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
}

}  // extern "C"
