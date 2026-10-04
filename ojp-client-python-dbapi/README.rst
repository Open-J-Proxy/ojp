OJP Python client
=================

This is a synchronous Python DB-API 2.0 client targeting **L1 H2 connectivity
and CRUD**, as defined in ``documents/multi-language-client-spec/``. It is not
a full JDBC or higher-level OJP implementation. ``apilevel = "2.0"``,
``threadsafety = 1`` (share the module, not connections or cursors), and
``paramstyle = "qmark"``.

Installation
------------

Python 3.10 or later is required. Local validation uses Python 3.12.
From this directory::

    python -m pip install .

The installable distribution is ``ojp-client-python-dbapi``; the import is ``ojp``. Runtime
dependencies are pinned in ``pyproject.toml``. Generated protobuf/gRPC bindings
are package-private and included in the wheel. No Java runtime is needed on the
Python client machine.

Start an OJP server on Java 25, with ``-Duser.timezone=UTC`` and an H2 JDBC
driver in its ``ojp-libs`` directory. Application-side connection pools must
be disabled: do not wrap ``ojp.connect`` in an application/framework connection
pool. The OJP server owns database connection pooling; double-pooling causes
incorrect behavior and unnecessary resource use.

API
---

::

    import os
    import ojp

    with ojp.connect(
        "jdbc:h2:mem:example;DB_CLOSE_DELAY=-1",
        os.environ.get("DB_USER", "sa"),
        os.environ.get("DB_PASSWORD", ""),
        endpoint="localhost:1059",
        timeout=30,
    ) as connection:
        with connection.cursor() as cursor:
            cursor.execute("CREATE TABLE example (id INTEGER, name VARCHAR(80))")
            cursor.executemany(
                "INSERT INTO example VALUES (?, ?)",
                [(1, "Alice"), (2, "Bob")],
            )
            cursor.execute("SELECT id, name FROM example WHERE id > ?", (0,))
            print(cursor.fetchall())
        # The connection context commits on success and always closes.

``connect(dsn, user="", password="", *, endpoint=None, timeout=30.0,
autocommit=False)`` accepts a backend JDBC URL or
``jdbc:ojp[host:port]_h2:mem:example``. The default endpoint is
``localhost:1059``. An explicit endpoint overrides the embedded endpoint.
Only one endpoint is allowed; bracketed IPv6 endpoints are accepted.
The positive finite timeout is a deadline **per RPC**, not for an entire
multi-RPC ``execute``. Transport calls are not retried.

Manual commit is the default. A transaction starts lazily before the first
execution; ``commit()`` and ``rollback()`` finish it, and subsequent execution
starts another. ``close()`` rolls back pending work. Connection and cursor
close are idempotent; subsequent operations raise ``InterfaceError``.
``autocommit=True`` can be chosen when connecting and is read-only afterward.
H2 DDL can implicitly commit, as with its JDBC driver.

``execute`` and ``executemany`` return the cursor. ``executemany`` is repeated
execution, not an atomic batch; use a manual transaction for atomic DML.
Queries in ``executemany`` are unsupported. ``fetchone`` returns a tuple or
``None``; ``fetchmany`` uses ``arraysize`` (default 1); ``fetchall`` returns a
list. Iteration is supported. Fetching without a result raises
``ProgrammingError``. Empty SELECTs still have column metadata.

``description`` is a tuple of seven-item column tuples: name, JDBC integer
type code, display size, internal size (unknown: ``None``), precision, scale,
and nullability (``None`` if unknown). Compare type codes with ``STRING``,
``BINARY``, ``NUMBER``, ``DATETIME`` or ``ROWID``. ``rowcount`` is -1 before
execution/on failure, the exact update count for DML, and the buffered row
count for queries. ``lastrowid`` is always ``None``: generated keys are not
supported. ``setinputsizes`` and ``setoutputsize`` are harmless hints.
``callproc`` and ``nextset`` raise ``NotSupportedError``.

Scalar parameters: ``None``, bool, signed 64-bit int, float, str, bytes-like,
date, time, and datetime. Values are bound using JDBC setters, never SQL
interpolation. Naive datetimes are treated as UTC; aware datetimes are
normalized to UTC. JDBC TIMESTAMP results are naive UTC datetimes. JDBC TIME
may lose fractional precision through ``java.sql.Time``; timezone-aware time
parameters are unsupported. SQL decimal results use ``decimal.Decimal``;
Decimal parameters are not supported (bind a string for a DECIMAL column).
The DB-API date/time/timestamp constructors, ``*FromTicks`` and ``Binary`` are
available.

The complete DB-API exception hierarchy is exported. SQL errors preserve
``sqlstate``, ``vendor_code`` and ``grpc_status``; SQLSTATE classes select
``IntegrityError``, ``DataError``, ``ProgrammingError`` and related subclasses.
Transport errors and deadlines become ``OperationalError`` with the original
gRPC exception chained as the cause.
Deadline-triggered server cancellation may report gRPC ``CANCELLED`` rather
than ``DEADLINE_EXCEEDED``. A deadline does not guarantee cancellation of the
underlying JDBC statement; never retry a timed-out write automatically.

Scope and safety
----------------

SQL executes exactly once. A bounded H2 lexical routing rule skips leading
whitespace and SQL comments (including nested block comments). ``SELECT``
and ``VALUES`` use ``executeQuery``; ``INSERT``, ``UPDATE``, ``DELETE``,
``MERGE``, ``CREATE``, ``ALTER``, ``DROP`` and ``TRUNCATE`` use
``executeUpdate``. These H2 families have unambiguous result kinds.
Other statements, notably **all WITH statements**, ``CALL`` and parenthesized
queries, use server-side JDBC ``PreparedStatement.execute`` through
``callResource``; JDBC decides their result kind. There is no speculative
execution or guessing whether a WITH statement contains a SELECT or DML.

Parameters use typed ``ParameterProto`` binding. On the JDBC fallback path,
the protocol's add-batch mode binds without executing, then ``clearBatch``
removes the staged batch before the single execution. This avoids the generic
reflective setter's ambiguity between bytes and serialized objects. The
staging RPC follows the server's update path (including its cache/write
routing bookkeeping); query caching and read/write splitting are outside this
client's supported scope. Metadata always comes from reflective JDBC metadata
calls, even for empty results. The client replaces its session from every RPC response that
carries one. Server result sets and statements are closed after buffering.
The server-side connection session remains allocated until connection close;
prefer short-lived connection contexts rather than leaving connections idle.

All query rows are buffered in memory during ``execute``. The normal query
path consumes the complete ``executeQuery`` stream, including every block.
The JDBC fallback reads rows via multiple RPCs per row/cell. Both are intended
for small L1 datasets, not large results or high throughput. Unsupported
row-by-row/LOB stream modes fail explicitly rather than returning partial rows.
There is no ``fetchNextRows`` pagination, scrollable cursor, LOB hydration, stored
procedure, savepoint, XA, connection cache, admission controller, health
checker, failover, or multi-node implementation. Basic manual transactions
and scalar metadata do not constitute an L4 or broader maturity claim.
Only H2 L1 is targeted and integration-tested; no compatibility claim is made
for other databases.

Transport is **plaintext**. Database credentials, SQL and data travel without
encryption. Use only a trusted local/private environment or an externally
secured transport. TLS and authentication configuration are not implemented.
Do not enable the experimental SQL enhancer.

Development and tests
---------------------

Tests use only standard-library ``unittest``::

    python -m pip install -e ".[dev]"
    python -m unittest discover -s tests -v
    ojp-generate-proto --check

To regenerate bindings after a canonical schema change::

    ojp-generate-proto

Generation uses ``grpcio-tools==1.78.0`` and installed
``googleapis-common-protos`` includes, and rewrites the generated sibling
import and message module identity to stay package-private. ``--check`` reproduces and compares both
bindings without modifying them. Outside the checkout, supply
``--repo-root /path/to/ojp``. Generation/check scratch output is under the
module's ignored ``build/`` directory.
The entrypoint always updates/checks the checkout's bindings, not an installed
site-packages copy. It discovers the checkout from the current directory or
the editable package location.

Real H2 integration tests are explicitly enabled::

    OJP_TEST_H2=true python -m unittest discover -s tests -v

The default fixture is ``tests/resources/h2.csv`` with three independent H2
in-memory database cases, including a CSV-quoted backend URL containing a
comma. Both backend JDBC URLs and wrapped OJP URLs are accepted. Every record
is tested. Each CSV record is exactly ``URL,user,password``,
without a header; CSV quoting supports commas in URLs or credentials.
``OJP_TEST_H2_CSV`` selects another CSV path and ``OJP_TEST_H2_ADDR`` optionally
overrides the endpoint for every record. Example environment overrides::

    OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 \
      OJP_TEST_H2_CSV=tests/resources/h2.csv \
      python -m unittest discover -s tests -v

When enabled, missing/malformed fixtures and unavailable servers are failures,
never skips. Tests create unique tables and drop them in cleanup; they verify
exact CRUD counts, rows and scalar types, empty-result metadata, server SQL
errors, lifecycle, commit/rollback visibility, deadlines, and complete results
beyond the server's default 100-row block size. Real gRPC interceptors verify
the required ``connect``, ``executeQuery``, ``executeUpdate`` and
``terminateSession`` calls; WITH SELECT and a data-changing WITH query exercise
JDBC fallback. H2 2.3.232 rejects top-level WITH UPDATE syntax; such server SQL
errors are surfaced, not reclassified or retried.
