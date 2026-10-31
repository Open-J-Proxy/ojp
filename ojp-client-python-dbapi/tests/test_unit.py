import datetime
from decimal import Decimal
import pickle
import unittest

import grpc

import ojp
from ojp._proto import StatementService_pb2 as pb
from ojp._values import parameter, decode, decode_column
from ojp.dbapi import _configuration, _sql_route, Connection
from ojp.errors import from_rpc


class RPCError(grpc.RpcError):
    def __init__(self, detail=None, metadata=None):
        self.detail = detail
        self.metadata = metadata

    def trailing_metadata(self):
        if self.metadata is not None:
            return self.metadata
        if self.detail is None:
            return ()
        return (("com.openjproxy.grpc.sqlerrorresponse-bin", self.detail.SerializeToString()),)

    def code(self):
        return grpc.StatusCode.INTERNAL

    def details(self):
        return "RPC failed"


class UnitTests(unittest.TestCase):
    def test_dbapi_contract_and_types(self):
        self.assertEqual((ojp.apilevel, ojp.threadsafety, ojp.paramstyle), ("2.0", 1, "qmark"))
        self.assertTrue(issubclass(ojp.IntegrityError, ojp.DatabaseError))
        self.assertTrue(issubclass(ojp.DatabaseError, ojp.Error))
        self.assertEqual(ojp.STRING, 12)
        self.assertEqual(ojp.BINARY, -3)
        self.assertEqual(ojp.NUMBER, 4)
        self.assertEqual(ojp.DATETIME, 93)
        self.assertEqual(ojp.ROWID, -8)
        self.assertNotEqual(ojp.NUMBER, 12)
        self.assertEqual(ojp.Date(2026, 1, 2), datetime.date(2026, 1, 2))
        self.assertEqual(ojp.Time(1, 2, 3), datetime.time(1, 2, 3))
        self.assertEqual(ojp.Timestamp(2026, 1, 2, 3, 4, 5), datetime.datetime(2026, 1, 2, 3, 4, 5))
        self.assertEqual(ojp.Binary(memoryview(b"abc")), b"abc")
        self.assertEqual(ojp.DateFromTicks(0), ojp.TimestampFromTicks(0).date())
        self.assertEqual(ojp.TimeFromTicks(0), ojp.TimestampFromTicks(0).time())

    def test_url_parsing(self):
        self.assertEqual(_configuration("jdbc:ojp[localhost:1059]_h2:mem:x", None),
                         ("jdbc:h2:mem:x", "localhost:1059"))
        self.assertEqual(_configuration("jdbc:ojp[[::1]:1059]_jdbc:h2:mem:x", "other:1060"),
                         ("jdbc:h2:mem:x", "other:1060"))
        self.assertEqual(_configuration("jdbc:ojp[localhost:1059]_h2:mem:x]_y", None),
                         ("jdbc:h2:mem:x]_y", "localhost:1059"))
        for url, endpoint in [("", None), ("h2:mem:x", None), ("jdbc:", None),
                              ("jdbc:h2:mem:x", ""), ("jdbc:h2:mem:x", "host:0"),
                              ("jdbc:h2:mem:x", "https://host:1059")]:
            with self.subTest(url=url, endpoint=endpoint), self.assertRaises(ojp.InterfaceError):
                _configuration(url, endpoint)
        with self.assertRaises(ojp.NotSupportedError):
            _configuration("jdbc:ojp[a:1,b:2]_h2:mem:x", None)
        for timeout in (0, -1, float("nan"), float("inf"), True, "1"):
            with self.subTest(timeout=timeout), self.assertRaises(ojp.InterfaceError):
                ojp.connect("jdbc:h2:mem:x", timeout=timeout)

    def test_errors_preserve_sql_details(self):
        for state, expected in [("23505", ojp.IntegrityError), ("22003", ojp.DataError),
                                ("42000", ojp.ProgrammingError), ("08001", ojp.OperationalError),
                                ("0A000", ojp.NotSupportedError), ("XX000", ojp.InternalError),
                                ("HY000", ojp.DatabaseError)]:
            error = from_rpc(RPCError(pb.SqlErrorResponse(reason="bad SQL", sqlState=state, vendorCode=123)))
            self.assertIsInstance(error, expected)
            self.assertEqual((str(error), error.sqlstate, error.vendor_code), ("bad SQL", state, 123))
        self.assertIsInstance(from_rpc(RPCError()), ojp.OperationalError)
        self.assertIsInstance(from_rpc(RPCError(metadata=[
            ("com.openjproxy.grpc.sqlerrorresponse-bin", b"\xff"),
        ])), ojp.OperationalError)

    def test_scalar_encoding_and_null(self):
        for value in (None, False, True, 0, 2**40, -(2**63), 2**63 - 1, 1.25, "", "λ", b"", b"\x00\xff",
                      datetime.date(2026, 1, 2), datetime.time(3, 4, 5, 123000),
                      datetime.datetime(2026, 1, 2, 3, 4, 5, 123456)):
            setter, values = parameter(value)
            decoded = decode(values[0])
            self.assertTrue(setter)
            self.assertEqual(decoded, 0 if value is None else value)
        self.assertIsNone(decode(pb.ParameterValue(is_null=True)))
        aware = datetime.datetime(2026, 1, 2, 3, tzinfo=datetime.timezone(datetime.timedelta(hours=2)))
        self.assertEqual(decode(parameter(aware)[1][0]), datetime.datetime(2026, 1, 2, 1))
        with self.assertRaises(ojp.DataError):
            parameter(2**63)
        with self.assertRaises(ojp.DataError):
            parameter(-(2**63) - 1)
        with self.assertRaises(ojp.NotSupportedError):
            parameter(object())
        with self.assertRaises(ojp.NotSupportedError):
            parameter(datetime.time(1, tzinfo=datetime.timezone.utc))

    def test_session_response_is_replaced_not_merged(self):
        connection = Connection.__new__(Connection)
        connection._closed = False
        connection._timeout = 1
        connection._session = pb.SessionInfo(connHash="old", sessionUUID="old", targetServer="old")
        response = pb.CallResourceResponse(session=pb.SessionInfo(
            connHash="new", clientUUID="client", sessionUUID="new",
            transactionInfo=pb.TransactionInfo(transactionUUID="txn"),
            clusterHealth="server:1(UP)", maxAdmission=5,
        ))
        connection._invoke(lambda request, timeout: response, object())
        self.assertEqual(connection._session, response.session)
        session = pb.SessionInfo(connHash="new", clientUUID="client")
        connection._invoke(lambda request, timeout: session, object())
        self.assertEqual(connection._session, session)

    def test_generated_bindings_use_private_module_names(self):
        session = pb.SessionInfo(connHash="pool", clientUUID="client")
        self.assertEqual(pickle.loads(pickle.dumps(session)), session)
        self.assertTrue(type(session).__module__.startswith("ojp._proto."))

    def test_only_unambiguous_h2_families_use_direct_rpcs(self):
        for sql in ("SELECT 1", " values(1)", "-- line\n /* block */ SELECT 1",
                    "/* nested /* block */ comment */ SELECT '?'"):
            self.assertEqual(_sql_route(sql), "query")
        for sql in ("INSERT INTO t VALUES(1)", "/* leading */ UPDATE t SET x=1",
                    "DELETE FROM t", "CREATE TABLE t(x INT)"):
            self.assertEqual(_sql_route(sql), "update")
        for sql in ("WITH t AS (SELECT 1) UPDATE x SET y=2", "WITH t AS (SELECT 1) SELECT * FROM t",
                    "CALL p()", "(SELECT 1)", "SELECTED", "SELECTé", "/* unfinished", "-- only a comment"):
            self.assertIsNone(_sql_route(sql))

    def test_decimal_stream_decode_preserves_precision(self):
        digits = b"123456789012345678901234567890123456789"
        wire = b"\x01" + len(digits).to_bytes(4, "big", signed=True) + digits + (5).to_bytes(4, "big", signed=True)
        self.assertEqual(decode_column(pb.ParameterValue(bytes_value=wire), 3),
                         Decimal("1234567890123456789012345678901234.56789"))
        self.assertEqual(decode_column(pb.ParameterValue(bytes_value=wire), -3), wire)
        with self.assertRaises(ojp.OperationalError):
            decode_column(pb.ParameterValue(bytes_value=b"\x01"), 3)


if __name__ == "__main__":
    unittest.main()
