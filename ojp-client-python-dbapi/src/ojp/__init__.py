"""Single-endpoint OJP DB-API 2.0 client (H2 L1)."""

from .errors import (
    Warning, Error, InterfaceError, DatabaseError, DataError, OperationalError,
    IntegrityError, InternalError, ProgrammingError, NotSupportedError,
)
from ._types import (
    Date, Time, Timestamp, Binary, DateFromTicks, TimeFromTicks,
    TimestampFromTicks, STRING, BINARY, NUMBER, DATETIME, ROWID,
)
from .dbapi import Connection, Cursor, connect

apilevel = "2.0"
threadsafety = 1
paramstyle = "qmark"

__all__ = [
    "connect", "Connection", "Cursor", "apilevel", "threadsafety", "paramstyle",
    "Warning", "Error", "InterfaceError", "DatabaseError", "DataError",
    "OperationalError", "IntegrityError", "InternalError", "ProgrammingError",
    "NotSupportedError", "Date", "Time", "Timestamp", "Binary", "DateFromTicks",
    "TimeFromTicks", "TimestampFromTicks", "STRING", "BINARY", "NUMBER",
    "DATETIME", "ROWID",
]
