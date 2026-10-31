"""PEP 249 errors and OJP SQL trailer decoding."""

import grpc
from google.protobuf.message import DecodeError

from ._proto import StatementService_pb2 as pb


class Warning(Exception):
    """Database warning."""


class Error(Exception):
    """Base DB-API error, retaining SQLSTATE and vendor error code when available."""

    def __init__(self, message: str, *, sqlstate: str | None = None,
                 vendor_code: int | None = None, grpc_status=None):
        super().__init__(message)
        self.sqlstate = sqlstate
        self.vendor_code = vendor_code
        self.grpc_status = grpc_status


class InterfaceError(Error):
    """Client interface misuse."""


class DatabaseError(Error):
    """Database error."""


class DataError(DatabaseError):
    """Invalid or out-of-range data."""


class OperationalError(DatabaseError):
    """Transport, timeout, connection, or operational failure."""


class IntegrityError(DatabaseError):
    """Constraint violation."""


class InternalError(DatabaseError):
    """Internal database failure."""


class ProgrammingError(DatabaseError):
    """Invalid SQL or API operation."""


class NotSupportedError(DatabaseError):
    """Feature outside this client's supported scope."""


def from_rpc(error: grpc.RpcError) -> Error:
    for key, value in error.trailing_metadata() or ():
        if key != "com.openjproxy.grpc.sqlerrorresponse-bin":
            continue
        try:
            detail = pb.SqlErrorResponse.FromString(value)
        except (DecodeError, TypeError):
            continue
        state = detail.sqlState
        prefix = state[:2]
        cls = DatabaseError
        if prefix == "23":
            cls = IntegrityError
        elif prefix == "22" or detail.sqlErrorType == pb.SQL_DATA_EXCEPTION:
            cls = DataError
        elif prefix in ("42", "37", "2A", "07"):
            cls = ProgrammingError
        elif prefix == "0A":
            cls = NotSupportedError
        elif prefix in ("08", "40", "53", "54", "57", "58") or detail.sqlErrorType == pb.SQL_TRANSIENT_CONNECTION_EXCEPTION:
            cls = OperationalError
        elif prefix == "XX":
            cls = InternalError
        return cls(detail.reason or error.details(), sqlstate=state or None,
                   vendor_code=detail.vendorCode, grpc_status=error.code())
    return OperationalError(error.details() or str(error), grpc_status=error.code())
