"""Scalar values for reflective JDBC setters and result getters."""

from datetime import date, datetime, time, timezone
from decimal import Decimal
import struct

from ._proto import StatementService_pb2 as pb
from .errors import DataError, NotSupportedError, OperationalError


def parameter(value):
    """Return a JDBC setter suffix and its wire arguments, excluding the index."""
    if value is None:
        return "Null", [pb.ParameterValue(int_value=0)]
    if isinstance(value, bool):
        return "Boolean", [pb.ParameterValue(bool_value=value)]
    if isinstance(value, int):
        if not -(2**63) <= value < 2**63:
            raise DataError("integer outside signed 64-bit range")
        field = "int_value" if -(2**31) <= value < 2**31 else "long_value"
        return ("Int" if field == "int_value" else "Long"), [pb.ParameterValue(**{field: value})]
    if isinstance(value, float):
        return "Double", [pb.ParameterValue(double_value=value)]
    if isinstance(value, str):
        return "String", [pb.ParameterValue(string_value=value)]
    if isinstance(value, (bytes, bytearray, memoryview)):
        return "Bytes", [pb.ParameterValue(bytes_value=bytes(value))]
    if isinstance(value, datetime):
        result = pb.ParameterValue()
        result.timestamp_value.instant.FromDatetime(value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value)
        result.timestamp_value.timezone = "UTC"
        result.timestamp_value.original_type = pb.TEMPORAL_TYPE_TIMESTAMP
        return "Timestamp", [result]
    if isinstance(value, date):
        result = pb.ParameterValue()
        result.date_value.year, result.date_value.month, result.date_value.day = value.year, value.month, value.day
        return "Date", [result]
    if isinstance(value, time):
        if value.tzinfo is not None:
            raise NotSupportedError("timezone-aware time parameters are not supported")
        result = pb.ParameterValue()
        result.time_value.hours, result.time_value.minutes, result.time_value.seconds = value.hour, value.minute, value.second
        result.time_value.nanos = value.microsecond * 1000
        return "Time", [result]
    raise NotSupportedError(f"unsupported scalar parameter type: {type(value).__name__}")


def decode(value):
    field = value.WhichOneof("value")
    if field is None or field == "is_null":
        return None
    if field == "date_value":
        item = value.date_value
        return date(item.year, item.month, item.day)
    if field == "time_value":
        item = value.time_value
        return time(item.hours, item.minutes, item.seconds, item.nanos // 1000)
    if field == "timestamp_value":
        item = value.timestamp_value
        instant = item.instant.ToDatetime(tzinfo=timezone.utc)
        # JDBC TIMESTAMP is a wall-clock value; the server is required to run in UTC.
        if item.original_type in (pb.TEMPORAL_TYPE_TIMESTAMP, pb.TEMPORAL_TYPE_LOCAL_DATE_TIME):
            return instant.replace(tzinfo=None)
        return instant
    if field in ("bool_value", "int_value", "long_value", "float_value", "double_value", "string_value", "bytes_value"):
        return getattr(value, field)
    raise NotSupportedError(f"unsupported result wire type: {field}")


def decode_column(value, jdbc_type):
    """JDBC metadata distinguishes decimal wire bytes from binary column values."""
    if jdbc_type not in (2, 3) or value.WhichOneof("value") != "bytes_value":
        return decode(value)
    data = value.bytes_value
    if len(data) < 9 or data[0] != 1:
        raise OperationalError("invalid decimal wire value")
    length = struct.unpack_from(">i", data, 1)[0]
    if length < 1 or len(data) != length + 9:
        raise OperationalError("invalid decimal wire length")
    try:
        digits = data[5:5 + length].decode("ascii")
        negative = digits.startswith("-")
        magnitude = digits[1:] if negative else digits
        if not magnitude or not magnitude.isdecimal():
            raise ValueError("invalid unscaled decimal")
        scale = struct.unpack_from(">i", data, 5 + length)[0]
        return Decimal((int(negative), tuple(int(digit) for digit in magnitude), -scale))
    except (ValueError, UnicodeError, struct.error) as error:
        raise OperationalError("invalid decimal wire value") from error
