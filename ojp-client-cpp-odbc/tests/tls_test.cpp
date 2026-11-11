#include <sql.h>
#include <sqlext.h>

#include "StatementService.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <atomic>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
namespace proto = com::openjproxy::grpc;

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void environment(const std::string& suffix, const std::string* value = nullptr) {
    const std::string name = "OJP_CLIENT_TLS_" + suffix;
#ifdef _WIN32
    _putenv_s(name.c_str(), value ? value->c_str() : "");
#else
    if (value) {
        setenv(name.c_str(), value->c_str(), 1);
    } else {
        unsetenv(name.c_str());
    }
#endif
}

struct Environment {
    std::vector<std::pair<std::string, std::string>> saved;
    Environment() {
        for (const auto* suffix : {"ENABLED", "ROOTCERT", "CERT", "KEY"}) {
            if (const auto* value = std::getenv((std::string("OJP_CLIENT_TLS_") + suffix).c_str())) {
                saved.emplace_back(suffix, value);
            }
            environment(suffix);
        }
    }
    ~Environment() {
        for (const auto* suffix : {"ENABLED", "ROOTCERT", "CERT", "KEY"}) {
            environment(suffix);
        }
        for (const auto& entry : saved) {
            environment(entry.first, &entry.second);
        }
    }
};

struct Connection {
    SQLHENV env = SQL_NULL_HENV;
    SQLHDBC dbc = SQL_NULL_HDBC;
    bool connected = false;
    Connection() {
        require(SQL_SUCCEEDED(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &env)), "Allocate env");
        require(SQL_SUCCEEDED(SQLSetEnvAttr(env, SQL_ATTR_ODBC_VERSION,
                    reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), 0)), "Set ODBC version");
        require(SQL_SUCCEEDED(SQLAllocHandle(SQL_HANDLE_DBC, env, &dbc)), "Allocate connection");
    }
    ~Connection() {
        if (connected) {
            SQLDisconnect(dbc);
        }
        SQLFreeHandle(SQL_HANDLE_DBC, dbc);
        SQLFreeHandle(SQL_HANDLE_ENV, env);
    }
    void connect(std::string input, const char* expected = nullptr, bool wide = false) {
        SQLCHAR output[4096] = {};
        SQLSMALLINT length = 0;
        SQLRETURN result;
        if (wide) {
            std::vector<SQLWCHAR> text(input.begin(), input.end());
            text.push_back(0);
            SQLWCHAR wide_output[4096] = {};
            result = SQLDriverConnectW(dbc, nullptr, text.data(), SQL_NTS,
                                       wide_output, 4096, &length, SQL_DRIVER_NOPROMPT);
            std::string returned;
            for (int i = 0; i < length && i < 4095; ++i) {
                returned.push_back(static_cast<char>(wide_output[i]));
            }
            require(returned.find("TLS.") == std::string::npos, "Wide TLS options leaked");
            require(returned.find(".pem") == std::string::npos, "Wide credential path leaked");
            require(returned.find(".key") == std::string::npos, "Wide key path leaked");
        } else {
            result = SQLDriverConnect(dbc, nullptr, reinterpret_cast<SQLCHAR*>(input.data()),
                SQL_NTS, output, sizeof(output), &length, SQL_DRIVER_NOPROMPT);
            const std::string returned(reinterpret_cast<char*>(output));
            require(returned.find("TLS.") == std::string::npos, "TLS options leaked");
            require(returned.find(".pem") == std::string::npos, "Credential path leaked");
            require(returned.find(".key") == std::string::npos, "Key path leaked");
        }
        if (expected) {
            require(result == SQL_ERROR, "Expected connection failure");
            SQLCHAR state[6] = {}, message[2048] = {};
            require(SQL_SUCCEEDED(SQLGetDiagRec(SQL_HANDLE_DBC, dbc, 1, state, nullptr,
                        message, sizeof(message), nullptr)), "Read diagnostic");
            require(std::string(reinterpret_cast<char*>(state)) == expected,
                    "Unexpected connection SQLSTATE: " + std::string(reinterpret_cast<char*>(state)));
            const std::string diagnostic(reinterpret_cast<char*>(message));
            require(diagnostic.find(".pem") == std::string::npos, "Diagnostic leaked path");
            require(diagnostic.find(".key") == std::string::npos, "Diagnostic leaked key path");
            require(diagnostic.find("PRIVATE KEY") == std::string::npos, "Diagnostic leaked key");
        } else {
            require(SQL_SUCCEEDED(result), "Expected successful connection");
            connected = true;
        }
    }
};

class Service final : public proto::StatementService::Service {
public:
    std::atomic<int> connects{0};
    std::atomic<bool> leaked{false};
    grpc::Status connect(grpc::ServerContext*, const proto::ConnectionDetails* request,
                         proto::SessionInfo* response) override {
        ++connects;
        const std::string serialized = request->DebugString();
        if (serialized.find(".pem") != std::string::npos ||
            serialized.find(".key") != std::string::npos ||
            serialized.find("TLS.") != std::string::npos ||
            serialized.find("PRIVATE KEY") != std::string::npos) {
            leaked = true;
        }
        response->set_connhash("same-server-pool");
        return grpc::Status::OK;
    }
    grpc::Status terminateSession(grpc::ServerContext*, const proto::SessionInfo*,
                                  proto::SessionTerminationStatus* response) override {
        response->set_terminated(true);
        return grpc::Status::OK;
    }
};

struct Server {
    Service service;
    int port = 0;
    std::unique_ptr<grpc::Server> server;
    explicit Server(std::shared_ptr<grpc::ServerCredentials> credentials) {
        grpc::ServerBuilder builder;
        builder.AddListeningPort("127.0.0.1:0", credentials, &port);
        builder.RegisterService(&service);
        server = builder.BuildAndStart();
        require(server && port, "Start local server");
    }
    ~Server() {
        server->Shutdown();
        server->Wait();
    }
    std::string options(const std::string& host = "localhost") const {
        return "SERVER=" + host + ":" + std::to_string(port) +
            ";DATABASE=jdbc:h2:mem:tls;OJP.LOADAWARE.SELECTION.ENABLED=false;";
    }
};

struct Fixtures {
    std::filesystem::path directory;
    explicit Fixtures(const std::string& openssl) {
        directory = std::filesystem::current_path() /
            ("tls-fixtures-" + std::to_string(std::random_device{}()));
        std::filesystem::create_directory(directory);
        std::filesystem::permissions(directory, std::filesystem::perms::owner_all);
        auto run = [&](const std::string& args) {
            const std::string command = "cd \"" + directory.string() + "\" && \"" + openssl +
                "\" " + args + " > /dev/null 2>&1";
            require(std::system(command.c_str()) == 0, "Generate ephemeral TLS fixture");
        };
        run("req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 1 -subj /CN=OJP-Test-CA");
        for (const auto* name : {"server", "client"}) {
            const std::string prefix(name);
            run("req -newkey rsa:2048 -nodes -keyout " + prefix + ".key -out " +
                prefix + ".csr -subj /CN=" + (prefix == "server" ? "localhost" : "client"));
            std::ofstream(directory / (prefix + ".ext")) <<
                (prefix == "server" ? "subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n"
                                    : "extendedKeyUsage=clientAuth\n");
            run("x509 -req -in " + prefix + ".csr -CA ca.pem -CAkey ca.key -CAcreateserial -out " +
                prefix + ".pem -days 1 -extfile " + prefix + ".ext");
        }
        run("req -x509 -newkey rsa:2048 -nodes -keyout untrusted.key -out untrusted.pem -days 1 -subj /CN=Untrusted");
        std::ofstream(directory / "empty.pem");
        std::ofstream(directory / "blank.pem") << " \r\n\t";
    }
    ~Fixtures() { std::filesystem::remove_all(directory); }
    std::string read(const std::string& name) const {
        std::ifstream input(directory / name);
        std::ostringstream output;
        output << input.rdbuf();
        return output.str();
    }
    std::string option(const std::string& key, const std::string& file) const {
        return "OJP.CLIENT.TLS." + key + "={" + (directory / file).string() + "};";
    }
    std::string trusted() const {
        return "OJP.CLIENT.TLS.ENABLED=true;" + option("ROOTCERT", "ca.pem") +
            option("CERT", "client.pem") + option("KEY", "client.key");
    }
};

void configuration_tests() {
    const std::string base = "SERVER=localhost:1;DATABASE=jdbc:h2:mem:tls;";
    for (const auto* value : {"", "yes", "1", "0", "no", "tru", "falsee"}) {
        Connection connection;
        connection.connect(base + "OJP.CLIENT.TLS.ENABLED=" + value + ";", "HY024");
    }
    const std::string invalid = "invalid";
    environment("ENABLED", &invalid);
    Connection connection;
    connection.connect(base, "HY024");
    environment("ENABLED");
    for (const auto* suffix : {"ROOTCERT", "CERT", "KEY"}) {
        Connection candidate;
        candidate.connect(base + "OJP.CLIENT.TLS.ENABLED=false;OJP.CLIENT.TLS." +
                          suffix + "=secret-path.pem;", "HY024");
    }
}

void handshake_tests(const std::string& openssl) {
    Fixtures fixtures(openssl);
    Server plaintext(grpc::InsecureServerCredentials());
    {
        Connection connection;
        connection.connect(plaintext.options());
    }
    {
        const std::string bad = "bad";
        environment("ENABLED", &bad);
        Connection connection;
        connection.connect(plaintext.options() + "OJP.CLIENT.TLS.ENABLED=false;");
        environment("ENABLED");
    }
    {
        Connection connection;
        connection.connect(plaintext.options() + fixtures.trusted(), "08001");
    }
    grpc::SslServerCredentialsOptions ssl;
    ssl.pem_root_certs = fixtures.read("ca.pem");
    ssl.pem_key_cert_pairs.push_back({fixtures.read("server.key"), fixtures.read("server.pem")});
    ssl.client_certificate_request = GRPC_SSL_REQUEST_AND_REQUIRE_CLIENT_CERTIFICATE_AND_VERIFY;
    Server secure(grpc::SslServerCredentials(ssl));
    const auto base = secure.options();
    {
        Connection connection;
        connection.connect(base + fixtures.trusted());
    }
    {
        Connection connection;
        connection.connect(base + fixtures.trusted(), nullptr, true);
    }
    {
        const std::string enabled = "true";
        const std::string root = (fixtures.directory / "ca.pem").string();
        const std::string cert = (fixtures.directory / "client.pem").string();
        const std::string key = (fixtures.directory / "client.key").string();
        environment("ENABLED", &enabled);
        environment("ROOTCERT", &root);
        environment("CERT", &cert);
        environment("KEY", &key);
        Connection connection;
        connection.connect(base);
        Connection missing_credentials;
        missing_credentials.connect(base + "OJP.CLIENT.TLS.CERT={};OJP.CLIENT.TLS.KEY={};",
                                    "08001");
        Connection default_roots;
        default_roots.connect(base + "OJP.CLIENT.TLS.ROOTCERT={};", "08001");
        const std::string invalid = "not-a-boolean";
        const std::string missing = "missing-secret.pem";
        environment("ENABLED", &invalid);
        environment("ROOTCERT", &missing);
        environment("CERT", &missing);
        environment("KEY", &missing);
        Connection override_connection;
        override_connection.connect(base + fixtures.trusted());
        Connection empty_override;
        empty_override.connect(base + fixtures.trusted() + "OJP.CLIENT.TLS.CERT=;", "HY024");
        for (const auto* suffix : {"ENABLED", "ROOTCERT", "CERT", "KEY"}) {
            environment(suffix);
        }
    }
    {
        Connection connection;
        connection.connect(base, "08S01");
    }
    {
        grpc::SslServerCredentialsOptions server_only;
        server_only.pem_key_cert_pairs = ssl.pem_key_cert_pairs;
        Server tls_server(grpc::SslServerCredentials(server_only));
        Connection connection;
        connection.connect(tls_server.options() + "OJP.CLIENT.TLS.ENABLED=true;" +
                           fixtures.option("ROOTCERT", "ca.pem"));
    }
    {
        Server second(grpc::SslServerCredentials(ssl));
        const std::string endpoints = "SERVER=localhost:" + std::to_string(secure.port) +
            ",localhost:" + std::to_string(second.port) +
            ";DATABASE=jdbc:h2:mem:tls;OJP.LOADAWARE.SELECTION.ENABLED=false;";
        Connection connection;
        connection.connect(endpoints + fixtures.trusted());
        require(second.service.connects > 0, "Second endpoint did not use TLS");
        require(!second.service.leaked, "Second endpoint received TLS credentials");
    }
    {
        Connection connection;
        connection.connect(base + "OJP.CLIENT.TLS.ENABLED=true;" +
                           fixtures.option("ROOTCERT", "ca.pem"), "08001");
    }
    {
        Connection connection;
        connection.connect(base + "OJP.CLIENT.TLS.ENABLED=true;" +
            fixtures.option("ROOTCERT", "ca.pem") + fixtures.option("CERT", "untrusted.pem") +
            fixtures.option("KEY", "untrusted.key"), "08001");
    }
    {
        Connection connection;
        connection.connect(base + fixtures.trusted() +
                           fixtures.option("KEY", "untrusted.key"), "08001");
    }
    {
        Connection connection;
        connection.connect(base + fixtures.trusted() +
                           fixtures.option("ROOTCERT", "untrusted.pem"), "08001");
    }
    {
        Connection connection;
        connection.connect(secure.options("127.0.0.1") + fixtures.trusted(), "08001");
    }
    for (const auto* name : {"missing.pem", "empty.pem", "blank.pem"}) {
        Connection connection;
        connection.connect(base + fixtures.trusted() + fixtures.option("ROOTCERT", name), "HY024");
    }
    for (const auto* option : {"CERT", "KEY"}) {
        Connection connection;
        connection.connect(base + "OJP.CLIENT.TLS.ENABLED=true;" +
                           fixtures.option(option, "client.pem"), "HY024");
    }
    {
        std::ofstream(fixtures.directory / "ca.pem") << fixtures.read("untrusted.pem");
        Connection connection;
        connection.connect(base + fixtures.trusted(), "08001");
    }
    {
        std::ofstream(fixtures.directory / "client.pem");
        Connection connection;
        connection.connect(base + fixtures.trusted(), "HY024");
    }
    require(!secure.service.leaked && !plaintext.service.leaked, "TLS credentials sent to server");
}
}  // namespace

int main(int argc, char** argv) {
    try {
        Environment environment_guard;
        configuration_tests();
        require(argc == 2, "OpenSSL executable required for local TLS tests");
        handshake_tests(argv[1]);
        std::cout << "TLS configuration and localhost mTLS tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
