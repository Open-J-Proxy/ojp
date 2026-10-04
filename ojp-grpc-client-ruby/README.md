# OJP Ruby DBI Client

This module provides a Ruby DBI driver for OJP. It currently targets **L1: Basic Connectivity + CRUD** with one OJP server and H2. The client uses the Ruby gRPC runtime and the OJP `StatementService`; database connections remain owned by `ojp-server`.

## L1 coverage

The H2 integration test covers connect/ping, DDL, parameterized insert/select/update/delete, result values, empty results, SQL error propagation, session termination, and operations after close. The client supports basic scalar parameters (NULL, booleans, integers, floats, strings, and timestamps).

Higher levels are not implemented: broader parameter coverage, server result pagination/resource cleanup, transactions, LOBs, session affinity, multinode routing/failover, and XA. Active Record adapters are not included.

## Use with Ruby DBI

Add this module and its dependencies to the Ruby application's bundle. The DBI DSN contains two CSV fields: the OJP endpoint and the real JDBC URL. Username and password are passed through DBI's normal connection arguments.

```ruby
require "dbi"
require "dbd/Ojp"

dsn = CSV.generate_line([
  "localhost:1059",
  "jdbc:postgresql://db:5432/app"
]).strip

DBI.connect("DBI:Ojp:#{dsn}", "app_user", ENV.fetch("DB_PASSWORD")) do |db|
  db.do("CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(100))")
  db.do("INSERT INTO items (id, name) VALUES (?, ?)", 1, "example")
  db.execute("SELECT id, name FROM items WHERE id = ?", 1) do |statement|
    p statement.fetch
  end
end
```

The DSN JDBC URL is sent unchanged to the OJP server. Do not add an application-side connection pool; the OJP server owns the database pool.

## H2 L1 integration test

The test is skipped unless enabled explicitly. It reads the backend JDBC URL and credentials from `test/testdata/h2_l1_connection.csv`; set the OJP server endpoint separately:

```bash
cd ojp-grpc-client-ruby
bundle install
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 ruby -Ilib -Itest test/integration/h2_l1_test.rb
```

Start `ojp-server` with Java 25, `-Duser.timezone=UTC`, and the H2 JDBC driver available in `ojp-libs/`. When enabled, a missing endpoint or unavailable server fails the test rather than skipping it.

## Protocol bindings

The generated Ruby stubs are checked in under `lib/`. Regenerate them with:

```bash
bundle exec grpc_tools_ruby_protoc --version
./generate-proto.sh
./generate-proto.sh --check
```
