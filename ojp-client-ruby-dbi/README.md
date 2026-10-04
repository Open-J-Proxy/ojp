# OJP Ruby DBI Client (Application Layout)

This module provides a Ruby DBI client for OJP. It connects to `ojp-server` over gRPC and runs SQL through the server-owned database connection pools.

## Current Implementation Level Assessment

| Assessment | Value |
|---|---|
| Highest implemented level in this module | **L1** |
| Summary | A single-endpoint Ruby DBI driver and an H2 real-server integration suite are implemented. CI confirmation is pending. |

### Current Test-Proven Coverage by Database (`ojp-client-ruby-dbi`)

| Database | Highest achieved level (current tests) | Evidence highlights |
|---|---:|---|
| **H2** | **L1** | `H2L1IntegrationTest#test_h2_supports_l1_crud_and_lifecycle` exercises Ruby DBI → one OJP server → H2. |
| **PostgreSQL** | **Not established** | No database-specific integration suite in this module yet. |
| **MySQL** | **Not established** | No database-specific integration suite in this module yet. |
| **MariaDB** | **Not established** | No database-specific integration suite in this module yet. |
| **Oracle** | **Not established** | No database-specific integration suite in this module yet. |
| **SQL Server** | **Not established** | No database-specific integration suite in this module yet. |
| **DB2** | **Not established** | No database-specific integration suite in this module yet. |
| **CockroachDB** | **Not established** | No database-specific integration suite in this module yet. |

Level definitions: [`../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md`](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md)

## Folder Structure

```text
ojp-client-ruby-dbi/
  lib/dbd/Ojp.rb                 # Ruby DBI driver
  lib/ojp/client.rb              # OJP StatementService client
  lib/*_pb.rb                    # generated protobuf/gRPC bindings
  test/unit/                     # client and DBI unit tests
  test/integration/              # H2 L1 real-server integration tests
  test/testdata/                 # H2 connection CSV fixture
  generate-proto.sh              # regenerate or verify Ruby protocol bindings
  Gemfile
  ojp-client-ruby-dbi.gemspec
```

## Configuration

The DBI data source name contains a CSV record with the OJP server endpoint and backend JDBC URL. The database username and password are provided through DBI's normal connection arguments.

| Value | Example |
|---|---|
| OJP endpoint | `localhost:1059` |
| Backend JDBC URL | `jdbc:postgresql://db:5432/app` |
| Username / password | Supplied separately to `DBI.connect` |

The CSV encoding preserves commas in either field:

```ruby
require "csv"

dsn = CSV.generate_line([
  "localhost:1059",
  "jdbc:postgresql://db:5432/app"
]).strip
```

## Using as a Library

Add this module and its dependencies to the Ruby application's bundle. The supported database access API is Ruby DBI; generated protocol bindings are implementation details. Active Record integration is not included.

```ruby
require "csv"
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
    row = statement.fetch
    p row.to_a if row
  end
end
```

The JDBC URL is sent unchanged to `ojp-server`. Do not add an application-side connection pool; the OJP server owns the database pool. Positional parameters currently support NULL, booleans, integers, floats, strings, and timestamps. Transactions, LOBs, result pagination, multinode routing/failover, and XA are not implemented in this client.

## Unit Tests

Run the Ruby client unit and fixture tests:

```bash
cd ojp-client-ruby-dbi
bundle install
bundle exec ruby -Ilib -Itest -e 'Dir["test/**/*_test.rb"].sort.each { |file| require_relative file }'
```

## H2 L1 Integration Tests

The H2 suite runs Ruby DBI through a real OJP gRPC server to H2. The test reads the backend JDBC URL, username, and password from `test/testdata/h2_l1_connection.csv`; set the OJP server endpoint separately:

```bash
OJP_TEST_H2=true \
OJP_TEST_H2_ADDR=localhost:1059 \
bundle exec ruby -Ilib -Itest test/integration/h2_l1_test.rb
```

Start `ojp-server` using Java 25 and UTC, with the H2 JDBC driver available in `ojp-libs/`. When `OJP_TEST_H2=true`, a missing endpoint or unavailable server fails the test rather than skipping it. The integration test covers connection/ping, DDL, parameterized CRUD, result values, empty results, SQL error propagation, session termination, and behavior after disconnect.

## Protocol Bindings

Generated Ruby stubs are checked in under `lib/`. Regenerate or verify them with:

```bash
./generate-proto.sh
./generate-proto.sh --check
```
