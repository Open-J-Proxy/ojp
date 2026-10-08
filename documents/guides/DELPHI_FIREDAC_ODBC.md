# Delphi FireDAC / ODBC with OJP

> **Status: experimental, not validated.** This guide describes the intended DSN-less FireDAC configuration and a compatibility probe. It does **not** establish that current FireDAC can open or query through the current OJP driver. Do not use it as production integration guidance.

## Why an ODBC bridge?

```text
Delphi application → FireDAC generic ODBC → platform ODBC Driver Manager
                  → OJP C++ ODBC driver → gRPC → ojp-server → JDBC → Database
```

There is no native Delphi OJP client in this repository. FireDAC supplies the application API; the OJP C++ driver would translate supported ODBC operations into OJP RPCs. The backend JDBC driver runs on the server, not in Delphi.

See the [framework chapter](../ebook/part2-chapter7-framework-integration.md#79-non-java-apis-and-odbc-bridges) for other candidate ODBC bridges and the [client matrix](../ebook/part1-chapter3-quickstart.md#native-client-maturity) for native clients.

## Current compatibility blockers

The [C++ implementation](../../ojp-client-cpp-odbc/src/ojp_odbc_driver.cpp) currently:

- Exports ANSI ODBC entry points only; there are no `SQLDriverConnectW`, `SQLPrepareW`, `SQLExecDirectW`, or other wide-character exports.
- Has no `SQLGetStmtAttr`, `SQLMoreResults`, `SQLColAttribute`, `SQLTables`, or `SQLColumns` exports.
- Implements only limited `SQLGetInfo` capability discovery and statement attributes. Basic `SQLDescribeCol` metadata is not a complete catalog or framework metadata implementation.
- Rejects DSN-only `SQLConnect`; use `SQLDriverConnect` with explicit `SERVER` and `DATABASE`.

FireDAC commonly uses Unicode and capability/metadata calls. Depending on the Driver Manager's conversion behavior and the FireDAC release, it can fail during initialization, before your first query. ANSI-to-Unicode translation by a Driver Manager does not supply missing metadata functions. There is **no verified configuration switch** that makes FireDAC fully compatible with this driver; neither selecting generic ODBC nor changing string types guarantees success.

The C++ driver's H2 and SQL Server implementations extend through L9, but reported test-proven coverage is L8 and the new L9 XA suites await passing CI. PostgreSQL has L1 coverage. Those tests use C++ ODBC calls, **not FireDAC**. XA requires the OJP-specific C API in `ojp_odbc_xa.h`; ordinary FireDAC local transactions do not expose it.

**Confidence:** High in these source-level limitations; low in end-to-end FireDAC compatibility because no FireDAC validation results are available.

## Prerequisites for an isolated trial

1. Start `ojp-server` on Java 25 with `-Duser.timezone=UTC`. Supply the backend JDBC driver in `ojp-libs/` and leave the experimental SQL enhancer disabled.
2. Build the ODBC driver using the [C++ build requirements](../../ojp-client-cpp-odbc/README.md#build-requirements). No prebuilt OJP ODBC binaries are currently published. Register the built library as **OJP** with your platform's Driver Manager.
3. Match the Delphi application, Driver Manager, and OJP driver architecture (for example, all 64-bit). Confirm the driver's gRPC/Protobuf runtime libraries can be loaded. FireDAC platform support and a successful driver build are separate requirements.
4. Disable FireDAC/application pooling (`Pooled=False`) and any Driver Manager pooling separately. FireDAC's connection-definition pooling setting does not universally control the manager's process-level pool.
5. Use a trusted local/private environment or an externally secured transport. The C++ driver currently uses plaintext gRPC; this example does not configure TLS.
6. Set these environment values for the application process:

| Variable | Meaning | Local H2 trial |
|---|---|---|
| `OJP_SERVER` | OJP endpoint, not the database server | `localhost:1059` |
| `OJP_JDBC_URL` | Complete backend JDBC URL | `jdbc:h2:mem:firedac_probe;DB_CLOSE_DELAY=-1` |
| `DB_USER` | Backend database user | `sa` |
| `DB_PASSWORD` | Backend database password | Empty for default local H2, otherwise supplied securely |

Never store credentials in a form resource, source code, shared connection-definition file, or trace output.

The C++ README provides the portable CMake/Ninja `release` preset, optional installation to a user-chosen prefix, platform registration, and an [ODBC compatibility table](../../ojp-client-cpp-odbc/README.md#using-other-languages-through-odbc). Linux Release build/install has been verified; Windows/macOS builds and runtime remain untested. The preset disables integration tests. Windows automatic symbol export is build support, not evidence of FireDAC or Unicode compatibility.

## DSN-less connection definition

Use FireDAC's **generic ODBC** driver, not its MSSQL or another database-specific driver:

| FireDAC setting | Value / purpose |
|---|---|
| `DriverID` | `ODBC` |
| `ODBCDriver` | `OJP`, the registered ODBC driver name |
| `ODBCAdvanced` | Full OJP `SERVER`, `DATABASE`, `UID`, and `PWD` fields, built at runtime below |
| `Pooled` | `False` |
| `LoginPrompt` | `False` on `TFDConnection` |

Do not set `DataSource`/`DSN` as a substitute for the explicit OJP fields. `DATABASE` must retain all JDBC options; wrap each ODBC value in braces, including the complete backend URL, and escape an embedded closing brace by doubling it. Do not use FireDAC's SQL Server-specific URL conventions for an OJP connection.

The public FireDAC documentation establishes the generic ODBC driver, driver name, advanced parameters, and connection-definition pooling controls. It does not certify OJP compatibility. The example below keeps the OJP-specific fields together in `ODBCAdvanced` so that the URL's semicolons are not mistaken for separate connection attributes.

## Delphi configuration and parameterized local-transaction probe

This console example requires an existing disposable H2 table, created beforehand using a known-working OJP client:

```sql
CREATE TABLE ojp_firedac_probe (id INTEGER PRIMARY KEY, label VARCHAR(100));
```

The probe inserts and reads a row inside a local transaction, then **rolls it back even on success**, leaving no row behind. Do not run it against a production schema. DDL is intentionally outside the transaction because H2 DDL may implicitly commit.

```delphi
program OjpFireDACProbe;

{$APPTYPE CONSOLE}

uses
  System.SysUtils,
  Data.DB,
  FireDAC.Stan.Def,
  FireDAC.Stan.Param,
  FireDAC.Stan.Error,
  FireDAC.Stan.Async,
  FireDAC.Comp.Client,
  FireDAC.ConsoleUI.Wait,
  FireDAC.DApt,
  FireDAC.Phys.ODBC,
  FireDAC.Phys.ODBCDef,
  FireDAC.Phys.ODBCWrapper;

function RequiredEnv(const Name: string): string;
begin
  Result := GetEnvironmentVariable(Name);
  if Result = '' then
    raise Exception.Create('Missing environment variable: ' + Name);
end;

function OdbcValue(const Value: string): string;
begin
  Result := '{' + StringReplace(Value, '}', '}}', [rfReplaceAll]) + '}';
end;

procedure PrintSqlErrors(const Error: EFDDBEngineException);
var
  I: Integer;
  State: string;
begin
  for I := 0 to Error.ErrorCount - 1 do
  begin
    State := '';
    if Error.Errors[I] is TFDODBCNativeError then
      State := TFDODBCNativeError(Error.Errors[I]).SQLState;
    Writeln(Format('SQLSTATE=%s NativeError=%d Message=%s', [
      State,
      Error.Errors[I].ErrorCode,
      Error.Errors[I].Message]));
  end;
end;

procedure RunProbe;
var
  Connection: TFDConnection;
  Query: TFDQuery;
begin
  Connection := TFDConnection.Create(nil);
  try
    Connection.LoginPrompt := False;
    Connection.Params.Values['DriverID'] := 'ODBC';
    Connection.Params.Values['ODBCDriver'] := 'OJP';
    Connection.Params.Values['Pooled'] := 'False';
    Connection.Params.Values['ODBCAdvanced'] := Format(
      'SERVER=%s;DATABASE=%s;UID=%s;%s=%s;', [
        OdbcValue(RequiredEnv('OJP_SERVER')),
        OdbcValue(RequiredEnv('OJP_JDBC_URL')),
        OdbcValue(RequiredEnv('DB_USER')),
        'PWD', OdbcValue(GetEnvironmentVariable('DB_PASSWORD'))]);
    Connection.Connected := True;

    Query := TFDQuery.Create(nil);
    try
      Query.Connection := Connection;
      Connection.StartTransaction;
      try
        Query.SQL.Text :=
          'INSERT INTO ojp_firedac_probe (id, label) VALUES (:id, :label)';
        Query.ParamByName('id').DataType := ftInteger;
        Query.ParamByName('id').AsInteger := 1;
        Query.ParamByName('label').DataType := ftString;
        Query.ParamByName('label').AsString := 'probe';
        Query.ExecSQL;

        Query.SQL.Text := 'SELECT label FROM ojp_firedac_probe WHERE id = :id';
        Query.ParamByName('id').DataType := ftInteger;
        Query.ParamByName('id').AsInteger := 1;
        Query.Open;
        if Query.IsEmpty then
          raise Exception.Create('Inserted row was not visible');
        Writeln(Query.FieldByName('label').AsString);
        Query.Close;
        Connection.Rollback;
      except
        on E: Exception do
        begin
          try
            Query.Close;
          except
            on CleanupError: Exception do
              Writeln('Query cleanup also failed: ' + CleanupError.Message);
          end;
          try
            if Connection.InTransaction then
              Connection.Rollback;
          except
            on CleanupError: Exception do
              Writeln('Rollback also failed: ' + CleanupError.Message);
          end;
          raise;
        end;
      end;
    finally
      Query.Free;
    end;
  finally
    try
      try
        Connection.Close;
      except
        on CleanupError: Exception do
        begin
          Writeln('Connection cleanup failed: ' + CleanupError.Message);
          if CleanupError is EFDDBEngineException then
            PrintSqlErrors(EFDDBEngineException(CleanupError));
          ExitCode := 1;
        end;
      end;
    finally
      Connection.Free;
    end;
  end;
end;

begin
  try
    RunProbe;
  except
    on E: EFDDBEngineException do
    begin
      PrintSqlErrors(E);
      ExitCode := 1;
    end;
    on E: Exception do
    begin
      Writeln(E.Message);
      ExitCode := 1;
    end;
  end;
end.
```

FireDAC parameters (`:id`, `:label`) are application-side placeholders; the ODBC layer must bind input parameters rather than interpolate SQL. `ftString` in this example is **not** a promise that FireDAC will avoid all wide-character calls. If connection initialization fails, no parameter or transaction code will run.

For a separate commit test, use a unique key, replace the success-path rollback with `Connection.Commit`, and verify visibility using a second connection. Keep failure-path rollback and explicit cleanup. Remove committed test rows and drop the disposable table afterward using a validated client. Failed writes and uncertain commits must not be automatically replayed.

SQLSTATE, vendor error code, and message should be preserved from `EFDDBEngineException.Errors`. Inspect diagnostic output in a restricted development environment and redact credentials/data before sharing. A transport failure or missing ODBC function is not necessarily a backend SQL error. If close/disconnect fails during another error, retain both diagnostics rather than classifying the cleanup failure as the original cause.

## What must be validated before calling this an integration?

- Record Delphi/FireDAC version, OS, bitness, Driver Manager, OJP driver revision, server revision, and backend JDBC driver version.
- Verify DSN-less opening with no credential prompt; trace initialization to identify unsupported Unicode, capability, and metadata calls. Redact traces before sharing.
- Check parameterized insert/select/update/delete, SQL error diagnostics, NULL, Unicode/UTF-8, decimal, binary, and date/time values.
- Verify empty-result column metadata, complete multi-block results, partial-result closure, statement reuse, and session termination.
- Test local commit/rollback visibility and isolation separately; do not infer them from C++ L4 tests.
- Confirm no FireDAC/framework/Driver Manager idle pool retains OJP sessions.
- Do not claim XA, automatic failover of active transactions, full catalog browsing, or a tested backend merely because generic ODBC can connect.

If FireDAC needs missing functions, the next step is driver/API implementation and wrapper-specific tests, not an invented compatibility setting. Until that evidence exists, use a native supported client or the directly tested C++ ANSI ODBC path for evaluation.

## Sources

Public FireDAC references (configuration semantics, **not** OJP validation):

- [Connect to ODBC Data Source (FireDAC)](https://docwiki.embarcadero.com/RADStudio/en/Connect_to_ODBC_Data_Source_(FireDAC))
- [TFDPhysODBCDriverLink](https://docwiki.embarcadero.com/Libraries/Florence/en/FireDAC.Phys.ODBC.TFDPhysODBCDriverLink) — generic ODBC driver and driver/advanced settings
- [ODBCAdvanced](https://docwiki.embarcadero.com/Libraries/Florence/en/FireDAC.Phys.ODBCBase.TFDPhysODBCBaseDriverLink.ODBCAdvanced) — additional ODBC connection parameters
- [TFDODBCNativeError](https://docwiki.embarcadero.com/Libraries/Sydney/en/FireDAC.Phys.ODBCWrapper.TFDODBCNativeError) — ODBC-specific SQLSTATE diagnostics
- [Common Connection Parameters (FireDAC)](https://docwiki.embarcadero.com/RADStudio/Sydney/en/Common_Connection_Parameters_(FireDAC)) — connection definition and `Pooled`
- [Using FireDAC Connection Pooling with RAD Server](https://blogs.embarcadero.com/using-firedac-connection-pooling-with-rad-server/) — FireDAC connection-definition pooling

The driver-link, advanced-parameter, and pooling references were checked through public web-search results. Direct docwiki page retrieval was unavailable in the documentation environment; no FireDAC runtime was used.

Repository evidence:

- [C++ ODBC README](../../ojp-client-cpp-odbc/README.md) — build, capabilities, and per-database test boundaries
- [ODBC driver source](../../ojp-client-cpp-odbc/src/ojp_odbc_driver.cpp) — actual exported entry points and limitations
- [Client implementation levels](../multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md) — implementation targets versus test-proven levels
