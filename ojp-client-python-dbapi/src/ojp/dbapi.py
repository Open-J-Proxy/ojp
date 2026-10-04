"""Synchronous DB-API implementation using server-side JDBC execution dispatch."""

from collections.abc import Sequence
from decimal import Decimal
import math
import re
import uuid
import weakref

import grpc

from ._proto import StatementService_pb2 as pb
from ._proto import StatementService_pb2_grpc as rpc
from ._values import decode, decode_column, parameter
from .errors import InterfaceError, OperationalError, ProgrammingError, NotSupportedError, from_rpc

_CLIENT_UUID = str(uuid.uuid4())
_ENDPOINT = re.compile(r"(?:[A-Za-z0-9_.-]+|\[[0-9A-Fa-f:]+\]):([0-9]+)\Z")
_SCALAR_TYPES = {0, 1, 12, -1, -15, -9, -16, -2, -3, -4, -7, 16, -6, 5, 4, -5,
                 6, 7, 8, 2, 3, 91, 92, 93, 2013, 2014}
_UPDATE_WORDS = {"INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TRUNCATE"}


def _sql_route(sql):
    """Route only unambiguous H2 statement families; everything else uses JDBC execute."""
    position = 0
    while position < len(sql):
        if sql[position].isspace():
            position += 1
        elif sql.startswith("--", position):
            end = sql.find("\n", position + 2)
            position = len(sql) if end < 0 else end + 1
        elif sql.startswith("/*", position):
            depth = 1
            position += 2
            while position < len(sql) and depth:
                if sql.startswith("/*", position):
                    depth += 1
                    position += 2
                elif sql.startswith("*/", position):
                    depth -= 1
                    position += 2
                else:
                    position += 1
            if depth:
                return None
        else:
            break
    match = re.match(r"[A-Za-z][\w$]*", sql[position:])
    word = match.group().upper() if match else ""
    if word in ("SELECT", "VALUES"):
        return "query"
    if word in _UPDATE_WORDS:
        return "update"
    return None


def _configuration(dsn: str, endpoint: str | None) -> tuple[str, str]:
    if not isinstance(dsn, str) or not dsn:
        raise InterfaceError("dsn must be a nonempty JDBC URL")
    if dsn.startswith("jdbc:ojp["):
        start = len("jdbc:ojp[")
        separator = dsn.find("]_", start)
        if separator <= start or separator + 2 == len(dsn):
            raise InterfaceError("expected jdbc:ojp[host:port]_backend_url")
        embedded, backend = dsn[start:separator], dsn[separator + 2:]
        if "," in embedded:
            raise NotSupportedError("only one OJP endpoint is supported")
        endpoint = endpoint if endpoint is not None else embedded
        dsn = backend if backend.startswith("jdbc:") else "jdbc:" + backend
    if not dsn.startswith("jdbc:") or dsn == "jdbc:":
        raise InterfaceError("backend URL must start with jdbc:")
    endpoint = "localhost:1059" if endpoint is None else endpoint
    match = _ENDPOINT.fullmatch(endpoint) if isinstance(endpoint, str) else None
    if match is None or not 0 < int(match.group(1)) < 65536:
        raise InterfaceError("endpoint must be one host:port (IPv6 addresses require brackets)")
    return dsn, endpoint


def connect(dsn: str, user: str = "", password: str = "", *,
            endpoint: str | None = None, timeout: float = 30.0,
            autocommit: bool = False) -> "Connection":
    """Connect to one plaintext OJP endpoint; manual commit is the default."""
    dsn, endpoint = _configuration(dsn, endpoint)
    if not isinstance(user, str) or not isinstance(password, str):
        raise InterfaceError("user and password must be strings")
    if isinstance(timeout, bool) or not isinstance(timeout, (float, int)) or not math.isfinite(timeout) or timeout <= 0:
        raise InterfaceError("timeout must be a positive finite number of seconds")
    if not isinstance(autocommit, bool):
        raise InterfaceError("autocommit must be a bool")
    return Connection(dsn, user, password, endpoint, float(timeout), autocommit)


class Connection:
    """A logical connection. Connections and their cursors must not be shared between threads."""

    def __init__(self, dsn, user, password, endpoint, timeout, autocommit):
        self._closed = False
        self._autocommit = autocommit
        self._transaction = False
        self._timeout = timeout
        self._cursors = weakref.WeakSet()
        self._channel = grpc.insecure_channel(endpoint, options=[
            ("grpc.max_receive_message_length", 16 * 1024 * 1024),
        ])
        self._stub = rpc.StatementServiceStub(self._channel)
        self._session = pb.SessionInfo()
        try:
            details = pb.ConnectionDetails(
                url=dsn, user=user, clientUUID=_CLIENT_UUID,
                serverEndpoints=[endpoint], isXA=False,
            )
            setattr(details, "password", password)
            self._invoke(self._stub.connect, details)
            if not self._session.connHash:
                raise OperationalError("server returned an empty connection identifier")
        except BaseException:
            self._channel.close()
            self._closed = True
            raise

    @property
    def closed(self) -> bool:
        return self._closed

    @property
    def autocommit(self) -> bool:
        """Read-only; choose transaction mode at connect time."""
        return self._autocommit

    def _check(self):
        if self._closed:
            raise InterfaceError("connection is closed")

    def _invoke(self, method, request):
        self._check()
        try:
            response = method(request, timeout=self._timeout)
        except grpc.RpcError as error:
            raise from_rpc(error) from error
        self._apply_response(response)
        return response

    def _apply_response(self, response):
        if isinstance(response, pb.SessionInfo):
            self._session.CopyFrom(response)
        elif hasattr(response, "session") and response.HasField("session"):
            self._session.CopyFrom(response.session)

    def _call(self, resource, resource_uuid, call_type, name="", params=(), *,
              next_call=None, properties=()):
        target = pb.TargetCall(callType=call_type, resourceName=name, params=params)
        if next_call is not None:
            target.nextCall.CopyFrom(next_call)
        return self._invoke(self._stub.callResource, pb.CallResourceRequest(
            session=self._session, resourceType=resource, resourceUUID=resource_uuid,
            target=target, properties=properties,
        ))

    def _scalar(self, resource, resource_uuid, call_type, name="", params=(), **kwargs):
        response = self._call(resource, resource_uuid, call_type, name, params, **kwargs)
        if len(response.values) != 1:
            raise OperationalError("server returned an invalid scalar response")
        return decode(response.values[0])

    def _begin(self):
        if not self._autocommit and not self._transaction:
            self._invoke(self._stub.startTransaction, self._session)
            self._transaction = True
        elif self._autocommit and not self._session.sessionUUID:
            # Acquire the session before prepare can fail, so even a first invalid
            # SQL statement leaves a known session that close() can release.
            self._call(pb.RES_CONNECTION, "", pb.CALL_SET, "AutoCommit",
                       [pb.ParameterValue(bool_value=True)])

    def cursor(self) -> "Cursor":
        self._check()
        cursor = Cursor(self)
        self._cursors.add(cursor)
        return cursor

    def commit(self) -> None:
        self._check()
        if self._transaction:
            self._invoke(self._stub.commitTransaction, self._session)
            self._transaction = False

    def rollback(self) -> None:
        self._check()
        if self._transaction:
            self._invoke(self._stub.rollbackTransaction, self._session)
            self._transaction = False

    def close(self) -> None:
        """Idempotent; roll back outstanding work, terminate the session, close the channel."""
        if self._closed:
            return
        failure = None
        try:
            try:
                self.rollback()
            except Exception as error:
                failure = error
            try:
                self._invoke(self._stub.terminateSession, self._session)
            except Exception as error:
                if failure is None:
                    failure = error
        finally:
            self._closed = True
            for cursor in list(self._cursors):
                cursor._invalidate()
            self._channel.close()
        if failure is not None:
            raise failure

    def __enter__(self):
        self._check()
        return self

    def __exit__(self, exc_type, exc, traceback):
        try:
            if exc_type is None:
                self.commit()
            else:
                self.rollback()
        finally:
            self.close()
        return False


class Cursor:
    """Forward-only, fully buffered scalar result cursor."""

    def __init__(self, connection: Connection):
        self.connection = connection
        self.arraysize = 1
        self.description = None
        self.rowcount = -1
        self.lastrowid = None
        self._closed = False
        self._rows = []
        self._position = 0

    @property
    def closed(self) -> bool:
        return self._closed

    def _check(self):
        self.connection._check()
        if self._closed:
            raise InterfaceError("cursor is closed")

    def _reset(self):
        self.description = None
        self.rowcount = -1
        self._rows = []
        self._position = 0

    def _invalidate(self):
        self._closed = True
        self._reset()

    def close(self) -> None:
        self._invalidate()
        self.connection._cursors.discard(self)

    def execute(self, operation: str, parameters=None) -> "Cursor":
        self._check()
        self._reset()
        if not isinstance(operation, str) or not operation.strip():
            raise ProgrammingError("operation must be nonempty SQL")
        if parameters is None:
            parameters = ()
        if not isinstance(parameters, Sequence) or isinstance(parameters, (str, bytes, bytearray, memoryview)):
            raise ProgrammingError("qmark parameters must be a sequence of scalar values")
        bindings = [parameter(value) for value in parameters]
        connection = self.connection
        connection._begin()
        statement_uuid = ""
        result_uuid = ""
        streamed_query = False
        failed = False
        try:
            # Creation plus an innocuous getter avoids executing SQL merely to classify it.
            response = connection._call(
                pb.RES_PREPARED_STATEMENT, "", pb.CALL_GET, "ParameterMetaData",
                next_call=pb.TargetCall(callType=pb.CALL_GET, resourceName="ParameterCount"),
                properties=[pb.PropertyEntry(key="PREPARED_STATEMENT_SQL_KEY", string_value=operation)],
            )
            statement_uuid = response.resourceUUID
            if not statement_uuid or len(response.values) != 1:
                raise OperationalError("server returned an invalid prepared statement")
            if decode(response.values[0]) != len(bindings):
                raise ProgrammingError("parameter count does not match SQL placeholders")
            typed_parameters = [
                pb.ParameterProto(index=index, type=getattr(pb, "PT_" + setter.upper()), values=values)
                for index, (setter, values) in enumerate(bindings, 1)
            ]
            request = pb.StatementRequest(
                session=connection._session, sql=operation, statementUUID=statement_uuid,
                parameters=typed_parameters,
            )
            route = _sql_route(operation)
            if route == "query":
                streamed_query = True
                request.ClearField("statementUUID")
                buffered = []
                stream = None
                try:
                    stream = connection._stub.executeQuery(request, timeout=connection._timeout)
                    for block in stream:
                        connection._apply_response(block)
                        if block.WhichOneof("result") != "query_result":
                            raise OperationalError("server returned a non-query stream block")
                        identifier = block.query_result.resultSetUUID
                        if not identifier or (result_uuid and result_uuid != identifier):
                            raise OperationalError("server returned inconsistent result set identifiers")
                        result_uuid = identifier
                        if block.flag:
                            raise NotSupportedError(f"unsupported query stream mode: {block.flag}")
                        buffered.extend(block.query_result.rows)
                except grpc.RpcError as error:
                    raise from_rpc(error) from error
                finally:
                    if stream is not None:
                        stream.cancel()
                if not result_uuid:
                    raise OperationalError("server returned an empty query stream")
                codes = self._read_description(result_uuid)
                rows = []
                for row in buffered:
                    if len(row.columns) != len(codes):
                        raise OperationalError("server returned an invalid row width")
                    rows.append(tuple(decode_column(value, code) for value, code in zip(row.columns, codes)))
                self._rows = rows
                self.rowcount = len(rows)
                return self
            if route == "update":
                response = connection._invoke(connection._stub.executeUpdate, request)
                if response.WhichOneof("result") != "int_value":
                    raise OperationalError("server returned an invalid update count")
                self.rowcount = response.int_value
                return self
            if bindings:
                # Generic callResource setters cannot distinguish raw bytes from serialized
                # containers. The typed addBatch operation binds without executing SQL;
                # clearBatch removes the staged batch, retaining JDBC parameter values.
                response = connection._invoke(connection._stub.executeUpdate, pb.StatementRequest(
                    session=connection._session, sql=operation, statementUUID=statement_uuid,
                    parameters=typed_parameters,
                    properties=[pb.PropertyEntry(key="PREPARED_STATEMENT_ADD_BATCH_FLAG", bool_value=True)],
                ))
                if response.type != pb.UUID_STRING or response.uuid_value != statement_uuid:
                    raise OperationalError("server did not acknowledge parameter staging")
                connection._call(pb.RES_PREPARED_STATEMENT, statement_uuid, pb.CALL_CLEAR, "Batch")
            has_result = connection._scalar(pb.RES_PREPARED_STATEMENT, statement_uuid, pb.CALL_EXECUTE)
            if has_result:
                result_uuid = connection._scalar(pb.RES_PREPARED_STATEMENT, statement_uuid, pb.CALL_GET, "ResultSet")
                if not isinstance(result_uuid, str) or not result_uuid:
                    raise OperationalError("server returned an invalid result set identifier")
                self._read_result(result_uuid)
            else:
                self.rowcount = connection._scalar(pb.RES_PREPARED_STATEMENT, statement_uuid, pb.CALL_GET, "UpdateCount")
        except BaseException:
            failed = True
            self._reset()
            raise
        finally:
            cleanup_error = None
            if result_uuid and streamed_query:
                try:
                    connection._call(
                        pb.RES_RESULT_SET, result_uuid, pb.CALL_GET, "Statement",
                        next_call=pb.TargetCall(callType=pb.CALL_CLOSE),
                    )
                except Exception as error:
                    cleanup_error = error
            for resource, identifier in ((pb.RES_RESULT_SET, result_uuid),
                                         (pb.RES_PREPARED_STATEMENT, statement_uuid)):
                if identifier:
                    try:
                        connection._call(resource, identifier, pb.CALL_CLOSE)
                    except Exception as error:
                        if cleanup_error is None:
                            cleanup_error = error
            if cleanup_error is not None and not failed:
                self._reset()
                raise cleanup_error
        return self

    def _metadata(self, identifier, name, index=None, *, call_type=pb.CALL_GET):
        params = [] if index is None else [pb.ParameterValue(int_value=index)]
        return self.connection._scalar(
            pb.RES_RESULT_SET, identifier, pb.CALL_GET, "MetaData",
            next_call=pb.TargetCall(callType=call_type, resourceName=name, params=params),
        )

    def _read_description(self, identifier):
        count = self._metadata(identifier, "ColumnCount")
        columns = []
        codes = []
        for index in range(1, count + 1):
            code = self._metadata(identifier, "ColumnType", index)
            if code not in _SCALAR_TYPES:
                raise NotSupportedError(f"unsupported JDBC result type: {code}")
            nullable = self._metadata(identifier, "Nullable", index, call_type=pb.CALL_IS)
            columns.append((
                self._metadata(identifier, "ColumnLabel", index), code,
                self._metadata(identifier, "ColumnDisplaySize", index), None,
                self._metadata(identifier, "Precision", index),
                self._metadata(identifier, "Scale", index),
                None if nullable == 2 else bool(nullable),
            ))
            codes.append(code)
        self.description = tuple(columns)
        return codes

    def _read_result(self, identifier):
        codes = self._read_description(identifier)
        rows = []
        while self.connection._scalar(pb.RES_RESULT_SET, identifier, pb.CALL_NEXT):
            row = []
            for index, code in enumerate(codes, 1):
                # Decimal's getObject wire value uses a Java-specific binary encoding.
                getter = "String" if code in (2, 3) else "Object"
                value = self.connection._scalar(pb.RES_RESULT_SET, identifier, pb.CALL_GET, getter,
                                                [pb.ParameterValue(int_value=index)])
                if code in (2, 3) and value is not None:
                    value = Decimal(value)
                row.append(value)
            rows.append(tuple(row))
        self._rows = rows
        self.rowcount = len(rows)

    def executemany(self, operation: str, seq_of_parameters) -> "Cursor":
        self._check()
        self._reset()
        total = 0
        for parameters in seq_of_parameters:
            self.execute(operation, parameters)
            if self.description is not None:
                self._reset()
                raise NotSupportedError("executemany does not support statements returning rows")
            if self.rowcount < 0:
                total = -1
            elif total >= 0:
                total += self.rowcount
        self.rowcount = total
        return self

    def _check_result(self):
        self._check()
        if self.description is None:
            raise ProgrammingError("no query result is available")

    def fetchone(self):
        self._check_result()
        if self._position >= len(self._rows):
            return None
        row = self._rows[self._position]
        self._position += 1
        return row

    def fetchmany(self, size=None):
        self._check_result()
        size = self.arraysize if size is None else size
        if isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise ProgrammingError("fetch size must be a nonnegative integer")
        end = min(len(self._rows), self._position + size)
        rows = self._rows[self._position:end]
        self._position = end
        return rows

    def fetchall(self):
        self._check_result()
        rows = self._rows[self._position:]
        self._position = len(self._rows)
        return rows

    def callproc(self, procname, parameters=()):
        self._check()
        raise NotSupportedError("stored procedures are not supported")

    def nextset(self):
        self._check()
        raise NotSupportedError("multiple result sets are not supported")

    def setinputsizes(self, sizes):
        self._check()

    def setoutputsize(self, size, column=None):
        self._check()

    def __iter__(self):
        self._check_result()
        return self

    def __next__(self):
        row = self.fetchone()
        if row is None:
            raise StopIteration
        return row

    def __enter__(self):
        self._check()
        return self

    def __exit__(self, exc_type, exc, traceback):
        self.close()
        return False
