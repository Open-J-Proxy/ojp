import 'dart:math';
import 'dart:typed_data';

import 'package:fixnum/fixnum.dart';
import 'package:grpc/grpc.dart';
import 'package:protobuf/well_known_types/google/protobuf/timestamp.pb.dart';

import 'generated/StatementService.pbgrpc.dart';

class OjpConnection {
  OjpConnection._(this._channel, this._service, this._session);

  final ClientChannel _channel;
  final StatementServiceClient _service;
  SessionInfo _session;
  bool _closed = false;

  static Future<OjpConnection> connect({
    required String endpoint,
    required String jdbcUrl,
    String username = '',
    String password = '',
    Duration timeout = const Duration(seconds: 30),
  }) async {
    final (host, port) = _parseEndpoint(endpoint);
    final channel = ClientChannel(
      host,
      port: port,
      options: const ChannelOptions(credentials: ChannelCredentials.insecure()),
    );
    final service = StatementServiceClient(channel);
    try {
      final session = await service
          .connect(
            ConnectionDetails(
              url: jdbcUrl,
              user: username,
              password: password,
              clientUUID: _newUuid(),
            ),
            options: CallOptions(timeout: timeout),
          )
          .timeout(timeout);
      if (session.connHash.isEmpty) {
        throw StateError('OJP server returned an empty connection session');
      }
      return OjpConnection._(channel, service, session);
    } catch (_) {
      await channel.shutdown();
      rethrow;
    }
  }

  Future<OjpQueryResult> executeQuery(
    String sql, {
    List<Object?> parameters = const [],
    Duration timeout = const Duration(seconds: 30),
  }) async {
    _checkOpen();
    final result = OjpQueryResult();
    final stream = _service.executeQuery(
      StatementRequest(
        session: _session,
        sql: sql,
        parameters: _encodeParameters(parameters),
      ),
      options: CallOptions(timeout: timeout),
    );
    await for (final response in stream) {
      if (response.hasSession()) {
        _session = response.session;
      }
      if (!response.hasQueryResult()) {
        continue;
      }
      final queryResult = response.queryResult;
      if (result.columns.isEmpty) {
        result.columns.addAll(queryResult.labels);
      }
      for (final row in queryResult.rows) {
        if (row.columns.length != queryResult.labels.length) {
          throw const FormatException(
            'OJP query result row does not match its labels',
          );
        }
        result.rows.add([for (final value in row.columns) _decodeValue(value)]);
      }
    }
    return result;
  }

  Future<int> executeUpdate(
    String sql, {
    List<Object?> parameters = const [],
    Duration timeout = const Duration(seconds: 30),
  }) async {
    _checkOpen();
    final response = await _service
        .executeUpdate(
          StatementRequest(
            session: _session,
            sql: sql,
            parameters: _encodeParameters(parameters),
          ),
          options: CallOptions(timeout: timeout),
        )
        .timeout(timeout);
    if (response.hasSession()) {
      _session = response.session;
    }
    if (response.type != ResultType.INTEGER || !response.hasIntValue()) {
      throw StateError('OJP server returned no affected-row count');
    }
    return response.intValue;
  }

  Future<void> close({Duration timeout = const Duration(seconds: 10)}) async {
    if (_closed) {
      return;
    }
    _closed = true;
    Object? closeError;
    try {
      final response = await _service
          .terminateSession(_session, options: CallOptions(timeout: timeout))
          .timeout(timeout);
      if (!response.terminated) {
        closeError = StateError('OJP server did not terminate the session');
      }
    } catch (error) {
      closeError = error;
    } finally {
      await _channel.shutdown();
    }
    if (closeError != null) {
      Error.throwWithStackTrace(closeError, StackTrace.current);
    }
  }

  void _checkOpen() {
    if (_closed) {
      throw StateError('OJP connection is closed');
    }
  }
}

class OjpQueryResult {
  final List<String> columns = [];
  final List<List<Object?>> rows = [];
}

List<ParameterProto> _encodeParameters(List<Object?> values) {
  return [
    for (var index = 0; index < values.length; index++)
      ParameterProto(
        index: index + 1,
        type: _parameterType(values[index]),
        values: [_encodeValue(values[index])],
      ),
  ];
}

ParameterTypeProto _parameterType(Object? value) {
  return switch (value) {
    null => ParameterTypeProto.PT_NULL,
    bool() => ParameterTypeProto.PT_BOOLEAN,
    int() => ParameterTypeProto.PT_LONG,
    double() => ParameterTypeProto.PT_DOUBLE,
    String() => ParameterTypeProto.PT_STRING,
    Uint8List() || List<int>() => ParameterTypeProto.PT_BYTES,
    DateTime() => ParameterTypeProto.PT_TIMESTAMP,
    _ => throw ArgumentError.value(value, 'value', 'Unsupported SQL value'),
  };
}

ParameterValue _encodeValue(Object? value) {
  return switch (value) {
    null => ParameterValue()..isNull = true,
    bool value => ParameterValue()..boolValue = value,
    int value => ParameterValue()..longValue = Int64(value),
    double value => ParameterValue()..doubleValue = value,
    String value => ParameterValue()..stringValue = value,
    Uint8List value => ParameterValue()..bytesValue = value,
    List<int> value => ParameterValue()..bytesValue = Uint8List.fromList(value),
    DateTime value =>
      ParameterValue()
        ..timestampValue = TimestampWithZone()
        ..timestampValue.instant = Timestamp.fromDateTime(value.toUtc()),
    _ => throw ArgumentError.value(value, 'value', 'Unsupported SQL value'),
  };
}

Object? _decodeValue(ParameterValue value) {
  if (value.hasIsNull() && value.isNull) {
    return null;
  }
  if (value.hasBoolValue()) {
    return value.boolValue;
  }
  if (value.hasIntValue()) {
    return value.intValue;
  }
  if (value.hasLongValue()) {
    return value.longValue.toInt();
  }
  if (value.hasFloatValue()) {
    return value.floatValue;
  }
  if (value.hasDoubleValue()) {
    return value.doubleValue;
  }
  if (value.hasStringValue()) {
    return value.stringValue;
  }
  if (value.hasBytesValue()) {
    return Uint8List.fromList(value.bytesValue);
  }
  if (value.hasTimestampValue()) {
    return value.timestampValue.instant.toDateTime().toUtc();
  }
  if (value.hasDateValue()) {
    final date = value.dateValue;
    return DateTime.utc(date.year, date.month, date.day);
  }
  if (value.hasTimeValue()) {
    final time = value.timeValue;
    return '${time.hours.toString().padLeft(2, '0')}:'
        '${time.minutes.toString().padLeft(2, '0')}:'
        '${time.seconds.toString().padLeft(2, '0')}';
  }
  return null;
}

(String, int) _parseEndpoint(String endpoint) {
  final uri = Uri.tryParse('http://$endpoint');
  if (uri == null ||
      !uri.hasAuthority ||
      uri.host.isEmpty ||
      !uri.hasPort ||
      uri.port < 1 ||
      uri.port > 65535 ||
      (uri.path.isNotEmpty && uri.path != '/') ||
      uri.hasQuery ||
      uri.hasFragment ||
      uri.userInfo.isNotEmpty) {
    throw ArgumentError.value(endpoint, 'endpoint', 'Expected host:port');
  }
  return (uri.host, uri.port);
}

String _newUuid() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes
      .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}
