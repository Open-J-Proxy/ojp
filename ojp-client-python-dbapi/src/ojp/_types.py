"""DB-API constructors and JDBC type-code comparison objects."""

from datetime import date as Date, time as Time, datetime as Timestamp
import time


class DBAPITypeObject:
    def __init__(self, *values: int):
        self.values = frozenset(values)

    def __eq__(self, other):
        if isinstance(other, DBAPITypeObject):
            return self.values == other.values
        return other in self.values

    def __ne__(self, other):
        return not self == other

    __hash__ = None


STRING = DBAPITypeObject(1, 12, -1, -15, -9, -16)
BINARY = DBAPITypeObject(-2, -3, -4)
NUMBER = DBAPITypeObject(-7, 16, -6, 5, 4, -5, 6, 7, 8, 2, 3)
DATETIME = DBAPITypeObject(91, 92, 93, 2013, 2014)
ROWID = DBAPITypeObject(-8)


def Binary(value) -> bytes:
    if not isinstance(value, (bytes, bytearray, memoryview)):
        raise TypeError("Binary requires a bytes-like object")
    return bytes(value)


def DateFromTicks(ticks: float) -> Date:
    return Date(*time.localtime(ticks)[:3])


def TimeFromTicks(ticks: float) -> Time:
    return Time(*time.localtime(ticks)[3:6])


def TimestampFromTicks(ticks: float) -> Timestamp:
    return Timestamp.fromtimestamp(ticks)
