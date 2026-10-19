#include "ojp_odbc_dsn.h"

#include <iostream>
#include <stdexcept>
#include <string>

namespace {

namespace dsn = ojp::odbc::dsn;

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

dsn::Attributes valid_attributes() {
    return {{dsn::KEY_SERVER, "localhost:1059"},
            {dsn::KEY_DATABASE, "jdbc:h2:mem:example"}};
}

void should_parse_attribute_pairs_with_uppercase_keys() {
    const auto attributes = dsn::parse_attribute_pairs(
        {"dsn=Sales", "Server=a:1,b:2", "DATABASE=jdbc:x://h/db?a=b", "ignored"});
    require(attributes.size() == 3, "Unexpected attribute count");
    require(attributes.at("DSN") == "Sales", "DSN key must be upper-cased");
    require(attributes.at("SERVER") == "a:1,b:2", "Server value must be preserved");
    require(attributes.at("DATABASE") == "jdbc:x://h/db?a=b", "Only the first '=' splits");
}

void should_let_explicit_values_override_stored_values() {
    const auto merged = dsn::merge(
        {{dsn::KEY_SERVER, "stored:1059"}, {dsn::KEY_UID, "stored"}},
        {{dsn::KEY_DSN, "Sales"}, {dsn::KEY_UID, "explicit"}});
    require(merged.at(dsn::KEY_SERVER) == "stored:1059", "Stored value must be kept");
    require(merged.at(dsn::KEY_UID) == "explicit", "Explicit value must win");
    require(merged.at(dsn::KEY_DSN) == "Sales", "Explicit-only value must be kept");
}

void should_brace_and_escape_connection_string_values() {
    const auto text = dsn::to_connection_string({{"PWD", "a}b;c"}, {"UID", "sa"}});
    require(text == "PWD={a}}b;c};UID={sa};", "Unexpected connection string: " + text);
}

void should_accept_valid_attributes() {
    auto attributes = valid_attributes();
    attributes[dsn::KEY_SERVER] = "host1:1059, [::1]:1060";
    attributes[dsn::KEY_XA] = "yes";
    attributes[dsn::KEY_RETRY_ATTEMPTS] = "10";
    attributes[dsn::KEY_RETRY_DELAY] = "60000";
    attributes[dsn::KEY_TLS_ENABLED] = "TRUE";
    attributes[dsn::KEY_TLS_CERT] = "c.pem";
    attributes[dsn::KEY_TLS_KEY] = "k.pem";
    require(dsn::validate(attributes, true).empty(), dsn::validate(attributes, true));
    require(dsn::validate({}, false).empty(), "Empty DSN is valid in silent mode");
}

void should_reject_invalid_attributes() {
    require(!dsn::validate({}, true).empty(), "Missing server must fail");
    auto attributes = valid_attributes();
    attributes.erase(dsn::KEY_DATABASE);
    require(!dsn::validate(attributes, true).empty(), "Missing database must fail");

    const std::pair<const char*, const char*> invalid[] = {
        {dsn::KEY_SERVER, "localhost"},
        {dsn::KEY_SERVER, "localhost:0"},
        {dsn::KEY_SERVER, "a:1,,b:2"},
        {dsn::KEY_SERVER, "::1:1059"},
        {dsn::KEY_XA, "maybe"},
        {dsn::KEY_RETRY_ATTEMPTS, "11"},
        {dsn::KEY_RETRY_DELAY, "-1"},
        {dsn::KEY_TLS_ENABLED, "yes"},
        {dsn::KEY_TLS_ROOTCERT, "ca.pem"},
    };
    for (const auto& entry : invalid) {
        auto candidate = valid_attributes();
        candidate[entry.first] = entry.second;
        require(!dsn::validate(candidate, true).empty(),
                std::string("Expected failure for ") + entry.first + "=" + entry.second);
    }

    auto unpaired = valid_attributes();
    unpaired[dsn::KEY_TLS_ENABLED] = "true";
    unpaired[dsn::KEY_TLS_CERT] = "c.pem";
    require(!dsn::validate(unpaired, true).empty(), "Certificate without key must fail");
}

void should_not_store_password() {
    for (const auto& key : dsn::stored_keys()) {
        require(key != dsn::KEY_PWD, "PWD must never be persisted in a DSN");
    }
}

}  // namespace

int main() {
    try {
        should_parse_attribute_pairs_with_uppercase_keys();
        should_let_explicit_values_override_stored_values();
        should_brace_and_escape_connection_string_values();
        should_accept_valid_attributes();
        should_reject_invalid_attributes();
        should_not_store_password();
    } catch (const std::exception& error) {
        std::cerr << "DSN test failed: " << error.what() << '\n';
        return 1;
    }
    std::cout << "DSN tests passed\n";
    return 0;
}
