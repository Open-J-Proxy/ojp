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

## Experimental ODBC alternative: Ruby ODBC

The native driver has a single-endpoint L1 API and an H2 suite, not full DBI
or database coverage. An alternative is the separately installed `ruby-odbc`
binding (`require "odbc"`) → OJP C++ ODBC driver → OJP server.
It is a bridge candidate requiring conformance testing, not a verified
replacement. A DBI ODBC adapter is another possibility only if it uses full
driver connection strings; a DSN-only `SQLConnect` path will not work.

A successful Linux driver build/install does not verify this bridge.
Windows and macOS driver builds and these wrapper integrations remain untested.

Build and register the driver as `OJP`; see
[build requirements and registration](../ojp-client-cpp-odbc/README.md#build-requirements).
The Ruby binding and the platform's driver manager/driver must have matching
architecture and support ANSI calls. Start the OJP server with Java 25, UTC,
and its H2 JDBC driver available. Disable driver-manager pooling before
connections and do not add application/framework pooling.

The complete DSN-less connection string is:

```text
DRIVER={OJP};SERVER=localhost:1059;DATABASE={jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1};UID={environment-user};PWD=;
```

`SERVER` targets OJP; `DATABASE` is the backend JDBC URL; `UID`/`PWD` hold
database credentials. The template shows an empty password; the example below
reads both credentials from the environment.
The parser also accepts `ENDPOINT`, `URL`, and `USER`
as aliases for `SERVER`, `DATABASE`, and `UID`. The driver manager resolves
`DRIVER`. Braces preserve embedded JDBC semicolons; double a literal `}`.
`SQLConnect` and DSN-only connections are unsupported: use `drvconnect`
(`SQLDriverConnect`) with the endpoint and URL explicitly supplied.

OJP's driver is **ANSI-only**, with no `W` exports. Unicode-only Ruby ODBC
builds are not compatible without verified driver-manager translation.
`SQLGetInfo` is limited; `SQLGetStmtAttr`, `SQLMoreResults`,
`SQLColAttribute`, `SQLTables`, and `SQLColumns` are absent. The Ruby binding
may require these even for basic execution/fetching. Check the actual binding,
encoding, and driver-manager behavior; ODBC levels do not prove Ruby support.

Illustrative runnable Ruby API usage (not a passing OJP bridge test), using
environment credentials `DB_USER` and `DB_PASSWORD`:

```ruby
require "odbc"

def brace(value)
  "{" + value.gsub("}", "}}") + "}"
end

text = "DRIVER={OJP};SERVER=localhost:1059;" \
       "DATABASE={jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1};" \
       "UID=#{brace(ENV.fetch('DB_USER'))};" \
       'PWD=' + brace(ENV.fetch('DB_PASSWORD')) + ";"
connection = ODBC::Database.new
statement = nil
begin
  connection.drvconnect(text)
  statement = connection.run("SELECT 1")
  p statement.fetch
ensure
  statement.drop if statement
  connection.disconnect if connection.connected?
end
```

An empty password is valid for a suitably configured H2. Never log the connection
string. The ODBC route does not add gRPC encryption; use a trusted/private
network or externally secured deployment.

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
