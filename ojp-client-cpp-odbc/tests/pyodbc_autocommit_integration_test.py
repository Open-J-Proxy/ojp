"""Real OJP/ODBC transaction tests; every CSV row is a backend configuration."""

import csv
import os
from pathlib import Path
import sys
import tempfile
import uuid

import pyodbc


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def brace(value):
    return "{" + value.replace("}", "}}") + "}"


def backend_id_sql(url):
    if url.startswith("jdbc:h2:"):
        return "SELECT SESSION_ID()"
    if url.startswith("jdbc:postgresql:"):
        return "SELECT pg_backend_pid()"
    if url.startswith("jdbc:sqlserver:"):
        return "SELECT @@SPID"
    return "SELECT CONNECTION_ID()"


def lifecycle_counts(log_path):
    log = log_path.read_text(encoding="utf-8")
    return tuple(
        sum(f"{api}: enter" in line for line in log.splitlines())
        for api in ("SQLDriverConnect", "SQLDriverConnectW", "SQLDisconnect")
    )


def run_connection(url, user, password, endpoints, log_path):
    connection_string = "".join(
        f"{key}={brace(value)};"
        for key, value in (
            ("DRIVER", "OJP"),
            ("SERVER", endpoints),
            ("DATABASE", url),
            ("UID", user),
            ("PWD", password),
            ("OJP.LOADAWARE.SELECTION.ENABLED", "false"),
        )
    )
    writer = pyodbc.connect(connection_string, autocommit=True)
    observer = None
    table = "ojp_py_ac_" + uuid.uuid4().hex[:16]
    created = False
    try:
        observer = pyodbc.connect(connection_string, autocommit=True)
        require(writer.autocommit is True, "Connected autocommit must default to ON")
        # Retain this exact cursor and its HSTMT through every mode transition.
        cursor = writer.cursor()
        reader = observer.cursor()
        if url.startswith("jdbc:sqlserver:"):
            require(
                reader.execute(
                    "SELECT is_read_committed_snapshot_on FROM sys.databases "
                    "WHERE name = DB_NAME()"
                ).fetchval() == 1,
                "SQL Server visibility tests require READ_COMMITTED_SNAPSHOT ON",
            )
        reader.execute(f"CREATE TABLE {table} (id INT PRIMARY KEY)")
        created = True
        initial_lifecycle = lifecycle_counts(log_path)

        def visible(record, expected):
            actual = reader.execute(
                f"SELECT COUNT(*) FROM {table} WHERE id = ?", record
            ).fetchval()
            require(actual == expected, f"id={record}: expected {expected}, got {actual}")

        def insert(record):
            cursor.execute(f"INSERT INTO {table} (id) VALUES (?)", record)

        for cycle in range(6):
            base = cycle * 10
            writer.autocommit = True
            writer.autocommit = False
            require(writer.autocommit is False, "pyodbc did not enter manual mode")
            backend = cursor.execute(backend_id_sql(url)).fetchval()
            insert(base + 1)
            visible(base + 1, 0)
            writer.autocommit = False
            visible(base + 1, 0)
            require(
                cursor.execute(backend_id_sql(url)).fetchval() == backend,
                "Manual transaction changed backend connection ownership",
            )
            if cycle % 2:
                writer.rollback()
                visible(base + 1, 0)
            else:
                writer.commit()
                visible(base + 1, 1)
            require(writer.autocommit is False, "Explicit completion changed the mode")
            insert(base + 2)
            visible(base + 2, 0)
            writer.autocommit = True
            require(writer.autocommit is True, "pyodbc did not restore autocommit")
            visible(base + 2, 1)
            writer.autocommit = True
            insert(base + 3)
            visible(base + 3, 1)
            require(
                lifecycle_counts(log_path) == initial_lifecycle,
                "Autocommit switching called SQLDriverConnect[W] or SQLDisconnect",
            )

        writer.autocommit = False
        insert(100)
        visible(100, 0)
        cursor.close()
        writer.close()
        writer = None
        visible(100, 0)
        reader.execute(f"DROP TABLE {table}")
        created = False
        reader.close()

        # pyodbc's default autocommit=False must still work when opening a connection.
        manual = pyodbc.connect(connection_string)
        try:
            require(manual.autocommit is False, "pyodbc default manual mode was lost")
            manual.autocommit = True
            require(manual.autocommit is True, "Initial manual connection cannot switch ON")
        finally:
            manual.close()
    finally:
        if writer is not None:
            try:
                writer.rollback()
            finally:
                writer.close()
        if observer is not None:
            try:
                if created:
                    observer.execute(f"DROP TABLE {table}")
            finally:
                observer.close()


def main():
    if len(sys.argv) != 5:
        raise ValueError("Expected CSV, enable variable, endpoints variable, minimum node count")
    csv_path, enable_variable, endpoint_variable, minimum_nodes = sys.argv[1:]
    if os.environ.get(enable_variable) != "true":
        print(f"Skipped: set {enable_variable}=true")
        return 77
    endpoints = os.environ[endpoint_variable]
    require(len(set(endpoints.split(","))) >= int(minimum_nodes),
            "Multinode autocommit tests require multiple distinct OJP endpoints")
    pyodbc.pooling = False
    with tempfile.TemporaryDirectory(prefix="ojp-pyodbc-autocommit-") as directory:
        log_path = Path(directory) / "driver.log"
        os.environ["OJP_ODBC_LOG_FILE"] = str(log_path)
        os.environ["OJP_ODBC_LOG_LEVEL"] = "debug"
        with open(csv_path, newline="", encoding="utf-8") as source:
            rows = list(csv.reader(source))
        require(bool(rows), "Database CSV is empty")
        for row_number, row in enumerate(rows, start=1):
            require(len(row) == 3 and row[0], f"Invalid database CSV row {row_number}")
            # Run every configured node as a transaction owner as well as a shared list.
            topologies = [endpoints]
            if int(minimum_nodes) > 1:
                topologies += endpoints.split(",")
            for topology in topologies:
                try:
                    run_connection(*row, topology, log_path)
                except Exception:
                    # Preserve diagnostics without exposing passwords or connection strings.
                    print(f"FAILED: row {row_number}, topology {topology}", file=sys.stderr)
                    raise
            print(f"pyodbc autocommit passed: row {row_number}, {row[0].split(':')[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
