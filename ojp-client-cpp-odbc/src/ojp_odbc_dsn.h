#pragma once

#include <map>
#include <string>
#include <vector>

// Shared DSN logic used by the ODBC driver and the Windows setup DLL. Keys are
// always stored upper-cased, matching the driver's connection-string parser.
namespace ojp::odbc::dsn {

using Attributes = std::map<std::string, std::string>;

inline constexpr const char* KEY_DSN = "DSN";
inline constexpr const char* KEY_DRIVER = "DRIVER";
inline constexpr const char* KEY_DESCRIPTION = "DESCRIPTION";
inline constexpr const char* KEY_SERVER = "SERVER";
inline constexpr const char* KEY_DATABASE = "DATABASE";
inline constexpr const char* KEY_UID = "UID";
inline constexpr const char* KEY_PWD = "PWD";
inline constexpr const char* KEY_DATASOURCE_NAME = "OJP.DATASOURCE.NAME";
inline constexpr const char* KEY_XA = "OJP.XA";
inline constexpr const char* KEY_LOAD_AWARE = "OJP.LOADAWARE.SELECTION.ENABLED";
inline constexpr const char* KEY_RETRY_ATTEMPTS = "OJP.MULTINODE.RETRY.ATTEMPTS";
inline constexpr const char* KEY_RETRY_DELAY = "OJP.MULTINODE.RETRY.DELAY";
inline constexpr const char* KEY_TLS_ENABLED = "OJP.CLIENT.TLS.ENABLED";
inline constexpr const char* KEY_TLS_ROOTCERT = "OJP.CLIENT.TLS.ROOTCERT";
inline constexpr const char* KEY_TLS_CERT = "OJP.CLIENT.TLS.CERT";
inline constexpr const char* KEY_TLS_KEY = "OJP.CLIENT.TLS.KEY";

// Keys persisted by the setup DLL. PWD is deliberately absent: DSN storage is plain text.
const std::vector<std::string>& stored_keys();

std::string upper_ascii(std::string value);

// Parses "KEY=value" pairs (e.g. ConfigDSN attribute list entries).
Attributes parse_attribute_pairs(const std::vector<std::string>& pairs);

// Explicit connection-string values override values stored in the DSN.
Attributes merge(const Attributes& stored, const Attributes& explicit_values);

// Serializes attributes as an ODBC connection string with braced, escaped values.
std::string to_connection_string(const Attributes& attributes);

// Returns an empty string when valid, otherwise a user-facing error message.
std::string validate(const Attributes& attributes, bool require_connection_fields);

// Reads every key of a DSN from the ODBC installer configuration. Returns false
// with an error message when the DSN does not exist or DSN support is unavailable.
bool read_stored_dsn(const std::string& dsn, Attributes* attributes, std::string* error);

}  // namespace ojp::odbc::dsn
