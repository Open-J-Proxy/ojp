"""Opt-in real-server H2 CRUD tests. Enabled setup failures are errors, not skips."""

from contextlib import contextmanager
import csv
from datetime import date, time, datetime, timezone
from decimal import Decimal
import os
from pathlib import Path
import unittest
import uuid

import grpc
import ojp


@unittest.skipUnless(os.environ.get("OJP_TEST_H2", "").lower() == "true",
                     "set OJP_TEST_H2=true to run real H2 integration tests")
class H2IntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(os.environ.get("OJP_TEST_H2_CSV", Path(__file__).parent / "resources/h2.csv"))
        with path.open(newline="") as fixture:
            cls.cases = list(csv.reader(fixture, strict=True))
        if not cls.cases or any(len(row) != 3 or not row[0] for row in cls.cases):
            raise ValueError("H2 CSV must contain nonempty rows of URL,user,password without a header")
        cls.endpoint = os.environ.get("OJP_TEST_H2_ADDR")
        for url, user, password in cls.cases:
            with cls.open((url, user, password), autocommit=True) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT 1")
                    if cursor.fetchone() != (1,):
                        raise AssertionError("H2 connectivity probe returned incorrect data")

    @classmethod
    def open(cls, case, **kwargs):
        return ojp.connect(*case, endpoint=cls.endpoint, **kwargs)

    @contextmanager
    def table(self, case, columns="id INTEGER PRIMARY KEY, item_value VARCHAR(100)"):
        name = "PY_L1_" + uuid.uuid4().hex.upper()
        with self.open(case, autocommit=True) as connection:
            with connection.cursor() as cursor:
                cursor.execute(f"CREATE TABLE {name} ({columns})")
                try:
                    yield name, connection
                finally:
                    cursor.execute(f"DROP TABLE {name}")

    def test_crud_exact_counts_and_fetch_methods(self):
        for index, case in enumerate(self.cases):
            with self.subTest(case=index), self.table(case) as (table, connection):
                with connection.cursor() as cursor:
                    self.assertIs(cursor.execute(f"INSERT INTO {table} VALUES (?, ?)", (1, "λ ' ?")), cursor)
                    self.assertEqual(cursor.rowcount, 1)
                    self.assertIsNone(cursor.description)
                    with self.assertRaises(ojp.ProgrammingError):
                        cursor.fetchone()
                    self.assertIs(cursor.executemany(f"INSERT INTO {table} VALUES (?, ?)",
                                                     [(2, ""), (3, None), (4, "four")]), cursor)
                    self.assertEqual(cursor.rowcount, 3)
                    cursor.execute(f"UPDATE {table} SET item_value=? WHERE id=?", ("updated", 4))
                    self.assertEqual(cursor.rowcount, 1)
                    cursor.execute(f"DELETE FROM {table} WHERE id=?", (99,))
                    self.assertEqual(cursor.rowcount, 0)
                    cursor.execute(f"/* SELECT? no classifier */ SELECT id AS ident, item_value FROM {table} ORDER BY id")
                    self.assertEqual(cursor.rowcount, 4)
                    self.assertEqual([entry[0] for entry in cursor.description], ["IDENT", "ITEM_VALUE"])
                    self.assertTrue(all(len(entry) == 7 for entry in cursor.description))
                    self.assertEqual(cursor.description[0][1], ojp.NUMBER)
                    self.assertEqual(cursor.description[1][1], ojp.STRING)
                    self.assertEqual(cursor.fetchone(), (1, "λ ' ?"))
                    cursor.arraysize = 2
                    self.assertEqual(cursor.fetchmany(), [(2, ""), (3, None)])
                    self.assertEqual(cursor.fetchmany(0), [])
                    self.assertEqual(cursor.fetchall(), [(4, "updated")])
                    self.assertIsNone(cursor.fetchone())
                    self.assertEqual(cursor.fetchmany(), [])
                    cursor.execute(f"DELETE FROM {table} WHERE id=?", (4,))
                    self.assertEqual(cursor.rowcount, 1)
                    cursor.execute(f"SELECT id FROM {table} ORDER BY id")
                    self.assertEqual(list(cursor), [(1,), (2,), (3,)])
                    cursor.execute(f"SELECT id FROM FINAL TABLE (INSERT INTO {table} VALUES (?,?))",
                                   (5, "data-changing query"))
                    self.assertEqual(cursor.fetchall(), [(5,)])
                    cursor.execute(f"DELETE FROM {table} WHERE id=5")
                    self.assertEqual(cursor.rowcount, 1)
                    cursor.execute(f"SELECT id, item_value FROM {table} WHERE 1=0")
                    self.assertEqual(cursor.rowcount, 0)
                    self.assertEqual(len(cursor.description), 2)
                    self.assertEqual(cursor.fetchall(), [])
                    cursor.execute("VALUES (42)")
                    self.assertEqual(cursor.fetchall(), [(42,)])
                    cursor.execute("SELECT '?' AS MARK")
                    self.assertEqual(cursor.fetchall(), [("?",)])
                    cursor.execute("WITH X(N) AS (SELECT 7) SELECT N FROM X")
                    self.assertEqual(cursor.fetchall(), [(7,)])
                    cursor.executemany(f"INSERT INTO {table} VALUES (?, ?)", [])
                    self.assertEqual(cursor.rowcount, 0)
                    self.assertIsNone(cursor.description)

    def test_scalar_types(self):
        columns = ("id INTEGER PRIMARY KEY, b BOOLEAN, n BIGINT, f DOUBLE, s VARCHAR(100), "
                   "raw VARBINARY(1000), d DATE, t TIME, ts TIMESTAMP, dec_value DECIMAL(10,2)")
        for index, case in enumerate(self.cases):
            with self.subTest(case=index), self.table(case, columns) as (table, connection):
                with connection.cursor() as cursor:
                    values = (1, False, 2**40, 1.25, "λ", b"\x00\xff", date(2026, 1, 2),
                              time(3, 4, 5), datetime(2026, 1, 2, 3, 4, 5, 123456), "12.34")
                    cursor.execute(f"INSERT INTO {table} VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", values)
                    self.assertEqual(cursor.rowcount, 1)
                    cursor.execute(f"SELECT * FROM {table}")
                    row = cursor.fetchone()
                    self.assertEqual(row, (*values[:-1], Decimal("12.34")))
                    self.assertEqual(tuple(type(value) for value in row),
                                     (int, bool, int, float, str, bytes, date, time, datetime, Decimal))
                    cursor.execute(f"INSERT INTO {table} VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                   (2, None, None, None, "", b"", None, None, None, None))
                    cursor.execute(f"SELECT * FROM {table} WHERE id=2")
                    self.assertEqual(cursor.fetchone(), (2, None, None, None, "", b"", None, None, None, None))
                    cursor.execute(f"INSERT INTO {table} (id,ts) VALUES (?,?)",
                                   (3, datetime(2026, 1, 2, 3, 4, 5, tzinfo=timezone.utc)))
                    cursor.execute(f"SELECT ts FROM {table} WHERE id=3")
                    self.assertEqual(cursor.fetchone(), (datetime(2026, 1, 2, 3, 4, 5),))
                    for item, payload in enumerate((b"\x0a\x02\x30\x00",
                                                     b"\x01\x00\x00\x00\x01\x31\x00\x00\x00\x00",
                                                     bytes(range(256))), 10):
                        cursor.execute(f"INSERT INTO {table} (id,raw) VALUES (?,?)", (item, payload))
                        cursor.execute(f"SELECT raw FROM {table} WHERE id=?", (item,))
                        self.assertEqual(cursor.fetchone(), (payload,))

    def test_errors_and_lifecycle(self):
        for index, case in enumerate(self.cases):
            with self.subTest(case=index), self.table(case) as (table, connection):
                cursor = connection.cursor()
                self.assertEqual(cursor.rowcount, -1)
                self.assertIsNone(cursor.description)
                for method in (cursor.fetchone, cursor.fetchall, cursor.fetchmany):
                    with self.assertRaises(ojp.ProgrammingError):
                        method()
                cursor.execute(f"INSERT INTO {table} VALUES (1, 'a')")
                with self.assertRaises(ojp.IntegrityError) as caught:
                    cursor.execute(f"INSERT INTO {table} VALUES (?, ?)", (1, "duplicate"))
                self.assertEqual(caught.exception.sqlstate, "23505")
                self.assertIsInstance(caught.exception.vendor_code, int)
                self.assertNotEqual(caught.exception.vendor_code, 0)
                with self.assertRaises(ojp.ProgrammingError):
                    cursor.execute("SELEKT invalid")
                with self.assertRaises(ojp.DataError) as caught:
                    cursor.execute(f"INSERT INTO {table} VALUES (CAST(1000 AS TINYINT), 'range')")
                self.assertTrue(caught.exception.sqlstate.startswith("22"))
                with self.assertRaises(ojp.ProgrammingError):
                    cursor.execute("SELECT ?", ())
                with self.assertRaises(ojp.ProgrammingError):
                    cursor.execute("SELECT ?", {"value": 1})
                with self.assertRaises(ojp.NotSupportedError):
                    cursor.execute("SELECT ?", (object(),))
                cursor.execute("SELECT 1")
                self.assertEqual(cursor.fetchone(), (1,))
                with self.assertRaises(ojp.ProgrammingError):
                    cursor.fetchmany(-1)
                with self.assertRaises(ojp.NotSupportedError):
                    cursor.callproc("not_supported")
                with self.assertRaises(ojp.NotSupportedError):
                    cursor.nextset()
                with self.assertRaises(ojp.NotSupportedError):
                    cursor.executemany("SELECT ?", [(1,)])
                cursor.setinputsizes([None])
                cursor.setoutputsize(100)
                cursor.close()
                cursor.close()
                with self.assertRaises(ojp.InterfaceError):
                    cursor.execute("SELECT 1")
                extra = self.open(case)
                child = extra.cursor()
                extra.close()
                extra.close()
                self.assertTrue(child.closed)
                for method in (extra.cursor, extra.commit, extra.rollback, child.fetchone):
                    with self.assertRaises(ojp.InterfaceError):
                        method()

    def test_manual_commit_rollback_close_and_context(self):
        for index, case in enumerate(self.cases):
            with self.subTest(case=index), self.table(case) as (table, observer):
                def count():
                    with observer.cursor() as cursor:
                        cursor.execute(f"SELECT COUNT(*) FROM {table}")
                        return cursor.fetchone()[0]
                writer = self.open(case)
                try:
                    self.assertFalse(writer.autocommit)
                    with writer.cursor() as cursor:
                        cursor.execute(f"INSERT INTO {table} VALUES (1,'first')")
                        self.assertEqual(count(), 0)
                        writer.commit()
                        self.assertEqual(count(), 1)
                        cursor.execute(f"INSERT INTO {table} VALUES (2,'rolled back')")
                        writer.rollback()
                        self.assertEqual(count(), 1)
                        cursor.execute(f"INSERT INTO {table} VALUES (3,'closed')")
                finally:
                    writer.close()
                self.assertEqual(count(), 1)
                with self.open(case) as writer:
                    with writer.cursor() as cursor:
                        cursor.execute(f"INSERT INTO {table} VALUES (4,'context commit')")
                self.assertTrue(writer.closed)
                self.assertEqual(count(), 2)
                with self.assertRaisesRegex(ValueError, "rollback"):
                    with self.open(case) as writer:
                        with writer.cursor() as cursor:
                            cursor.execute(f"INSERT INTO {table} VALUES (5,'context rollback')")
                        raise ValueError("rollback")
                self.assertEqual(count(), 2)

    def test_deadline_and_unavailable_endpoint(self):
        for index, case in enumerate(self.cases):
            with self.subTest(case=index):
                with self.assertRaises(ojp.OperationalError) as caught:
                    self.open(case, timeout=1e-9)
                self.assertEqual(caught.exception.grpc_status, grpc.StatusCode.DEADLINE_EXCEEDED)
        with self.assertRaises(ojp.OperationalError):
            ojp.connect("jdbc:h2:mem:unreachable", "sa", "", endpoint="127.0.0.1:1", timeout=0.1)


if __name__ == "__main__":
    unittest.main()
