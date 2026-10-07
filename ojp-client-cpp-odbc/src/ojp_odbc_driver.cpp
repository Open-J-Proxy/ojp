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
#include <map>
#include <memory>
#include <mutex>
#include <random>
#include <sstream>
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
            *output = value.uuid_value().value();
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
        case SQL_LONGVARBINARY:
        case SQL_TYPE_DATE:
        case SQL_TYPE_TIME:
        case SQL_TYPE_TIMESTAMP:
            return sql_type;  // ODBC and java.sql.Types share these codes.
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
        case SQL_VARBINARY:
        case SQL_LONGVARBINARY: return PT_BYTES;
        case SQL_BIT: return PT_BOOLEAN;
        case SQL_TYPE_DATE: return PT_DATE;
        case SQL_TYPE_TIME: return PT_TIME;
        case SQL_TYPE_TIMESTAMP: return PT_TIMESTAMP;
        default: return PT_STRING;
    }
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
    return fail(target, "Only autocommit mode is supported", "HYC00");
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
