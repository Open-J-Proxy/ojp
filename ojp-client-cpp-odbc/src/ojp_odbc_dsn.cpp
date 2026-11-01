#include "ojp_odbc_dsn.h"

#ifdef OJP_ODBC_HAVE_ODBCINST
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif
#include <odbcinst.h>
#endif

#include <algorithm>
#include <cctype>
#include <charconv>
#include <cstddef>

namespace ojp::odbc::dsn {

namespace {

std::string trim(const std::string& value) {
    std::size_t begin = 0;
    std::size_t end = value.size();
    while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) {
        ++begin;
    }
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return value.substr(begin, end - begin);
}

std::string lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

std::string value_of(const Attributes& attributes, const char* key) {
    const auto entry = attributes.find(key);
    return entry == attributes.end() ? std::string{} : trim(entry->second);
}

bool parse_int(const std::string& text, int min, int max) {
    int parsed = 0;
    const auto* end = text.data() + text.size();
    const auto result = std::from_chars(text.data(), end, parsed);
    return !text.empty() && result.ec == std::errc{} && result.ptr == end &&
        parsed >= min && parsed <= max;
}

bool is_boolean(const std::string& text) {
    const auto normalized = lower_ascii(text);
    return normalized == "true" || normalized == "false" || normalized == "1" ||
        normalized == "0" || normalized == "yes" || normalized == "no";
}

bool is_valid_endpoint(const std::string& endpoint) {
    const auto colon = endpoint.rfind(':');
    if (colon == std::string::npos || colon == 0) {
        return false;
    }
    std::string host = endpoint.substr(0, colon);
    if (host.front() == '[') {
        if (host.size() < 3 || host.back() != ']') {
            return false;
        }
    } else if (host.find(':') != std::string::npos) {
        return false;
    }
    return parse_int(endpoint.substr(colon + 1), 1, 65535);
}

bool is_valid_server_list(std::string value) {
    // Same optional list brackets the driver accepts; a valid endpoint never ends with ']'.
    if (value.size() >= 2 && value.front() == '[' && value.back() == ']') {
        value = value.substr(1, value.size() - 2);
    }
    std::size_t start = 0;
    while (true) {
        const auto separator = value.find(',', start);
        const auto endpoint = trim(value.substr(
            start, separator == std::string::npos ? separator : separator - start));
        if (!is_valid_endpoint(endpoint)) {
            return false;
        }
        if (separator == std::string::npos) {
            return true;
        }
        start = separator + 1;
    }
}

#ifdef OJP_ODBC_HAVE_ODBCINST
constexpr int PROFILE_BUFFER_SIZE = 32768;

#ifdef _WIN32
std::wstring widen(const std::string& value) {
    if (value.empty()) {
        return {};
    }
    const int size = MultiByteToWideChar(CP_UTF8, 0, value.data(),
                                         static_cast<int>(value.size()), nullptr, 0);
    std::wstring result(static_cast<std::size_t>(size), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                        result.data(), size);
    return result;
}

std::string narrow(const wchar_t* value, std::size_t length) {
    if (length == 0) {
        return {};
    }
    const int size = WideCharToMultiByte(CP_UTF8, 0, value, static_cast<int>(length),
                                         nullptr, 0, nullptr, nullptr);
    std::string result(static_cast<std::size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value, static_cast<int>(length), result.data(), size,
                        nullptr, nullptr);
    return result;
}

bool read_profile(const std::string& dsn, Attributes* attributes) {
    const auto section = widen(dsn);
    std::wstring keys(PROFILE_BUFFER_SIZE, L'\0');
    const int keys_length = SQLGetPrivateProfileStringW(
        section.c_str(), nullptr, L"", keys.data(), PROFILE_BUFFER_SIZE, L"ODBC.INI");
    if (keys_length <= 0) {
        return false;
    }
    std::wstring value(PROFILE_BUFFER_SIZE, L'\0');
    for (const wchar_t* key = keys.c_str(); *key != L'\0'; key += wcslen(key) + 1) {
        const int length = SQLGetPrivateProfileStringW(
            section.c_str(), key, L"", value.data(), PROFILE_BUFFER_SIZE, L"ODBC.INI");
        (*attributes)[upper_ascii(narrow(key, wcslen(key)))] =
            narrow(value.c_str(), static_cast<std::size_t>(std::max(0, length)));
    }
    return true;
}
#else
bool read_profile(const std::string& dsn, Attributes* attributes) {
    std::string keys(PROFILE_BUFFER_SIZE, '\0');
    const int keys_length = SQLGetPrivateProfileString(
        dsn.c_str(), nullptr, "", keys.data(), PROFILE_BUFFER_SIZE, "ODBC.INI");
    if (keys_length <= 0) {
        return false;
    }
    std::string value(PROFILE_BUFFER_SIZE, '\0');
    for (const char* key = keys.c_str(); *key != '\0';
         key += std::char_traits<char>::length(key) + 1) {
        const int length = SQLGetPrivateProfileString(
            dsn.c_str(), key, "", value.data(), PROFILE_BUFFER_SIZE, "ODBC.INI");
        (*attributes)[upper_ascii(key)] =
            value.substr(0, static_cast<std::size_t>(std::max(0, length)));
    }
    return true;
}
#endif
#endif

}  // namespace

const std::vector<std::string>& stored_keys() {
    static const std::vector<std::string> keys = {
        KEY_DESCRIPTION, KEY_SERVER, KEY_DATABASE, KEY_UID, KEY_DATASOURCE_NAME, KEY_XA,
        KEY_LOAD_AWARE, KEY_RETRY_ATTEMPTS, KEY_RETRY_DELAY, KEY_TLS_ENABLED,
        KEY_TLS_ROOTCERT, KEY_TLS_CERT, KEY_TLS_KEY};
    return keys;
}

std::string upper_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::toupper(ch));
    });
    return value;
}

Attributes parse_attribute_pairs(const std::vector<std::string>& pairs) {
    Attributes attributes;
    for (const auto& pair : pairs) {
        const auto equals = pair.find('=');
        if (equals == std::string::npos) {
            continue;
        }
        const auto key = upper_ascii(trim(pair.substr(0, equals)));
        if (!key.empty()) {
            attributes[key] = pair.substr(equals + 1);
        }
    }
    return attributes;
}

Attributes merge(const Attributes& stored, const Attributes& explicit_values) {
    Attributes merged = stored;
    for (const auto& entry : explicit_values) {
        merged[entry.first] = entry.second;
    }
    return merged;
}

std::string to_connection_string(const Attributes& attributes) {
    std::string output;
    for (const auto& entry : attributes) {
        output += entry.first + "={";
        for (const char character : entry.second) {
            output.push_back(character);
            if (character == '}') {
                output.push_back('}');
            }
        }
        output += "};";
    }
    return output;
}

std::string validate(const Attributes& attributes, bool require_connection_fields) {
    const auto server = value_of(attributes, KEY_SERVER);
    const auto database = value_of(attributes, KEY_DATABASE);
    if (require_connection_fields && server.empty()) {
        return "Server is required (host:port, comma-separated for multinode).";
    }
    if (!server.empty() && !is_valid_server_list(server)) {
        return "Server must be a comma-separated list of host:port entries "
               "with ports between 1 and 65535.";
    }
    if (require_connection_fields && database.empty()) {
        return "Database URL is required (for example jdbc:postgresql://host:5432/db).";
    }
    const auto xa = value_of(attributes, KEY_XA);
    if (!xa.empty() && !is_boolean(xa)) {
        return "XA must be true or false.";
    }
    const auto load_aware = value_of(attributes, KEY_LOAD_AWARE);
    if (!load_aware.empty() && !is_boolean(load_aware)) {
        return "Load-aware selection must be true or false.";
    }
    const auto attempts = value_of(attributes, KEY_RETRY_ATTEMPTS);
    if (!attempts.empty() && !parse_int(attempts, 0, 10)) {
        return "Retry attempts must be a whole number between 0 and 10.";
    }
    const auto delay = value_of(attributes, KEY_RETRY_DELAY);
    if (!delay.empty() && !parse_int(delay, 0, 60000)) {
        return "Retry delay must be a whole number of milliseconds between 0 and 60000.";
    }
    const auto tls_enabled = lower_ascii(value_of(attributes, KEY_TLS_ENABLED));
    if (!tls_enabled.empty() && tls_enabled != "true" && tls_enabled != "false") {
        return "TLS enabled must be true or false.";
    }
    const auto root_cert = value_of(attributes, KEY_TLS_ROOTCERT);
    const auto cert = value_of(attributes, KEY_TLS_CERT);
    const auto key = value_of(attributes, KEY_TLS_KEY);
    if (tls_enabled != "true" && (!root_cert.empty() || !cert.empty() || !key.empty())) {
        return "TLS certificate files require TLS to be enabled.";
    }
    if (cert.empty() != key.empty()) {
        return "Client certificate and client key must be provided together.";
    }
    return {};
}

bool read_stored_dsn(const std::string& dsn, Attributes* attributes, std::string* error) {
#ifdef OJP_ODBC_HAVE_ODBCINST
    Attributes stored;
    if (dsn.empty() || !read_profile(dsn, &stored)) {
        *error = "Data source name not found: " + dsn;
        return false;
    }
    *attributes = std::move(stored);
    return true;
#else
    (void)attributes;
    *error = "DSN connections are not supported by this build (ODBC installer library "
             "unavailable); use a DSN-less connection string. Requested DSN: " + dsn;
    return false;
#endif
}

}  // namespace ojp::odbc::dsn
