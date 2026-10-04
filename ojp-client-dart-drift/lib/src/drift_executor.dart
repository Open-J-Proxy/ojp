import 'package:drift/drift.dart';

import 'connection.dart';

class OjpDriftExecutor implements QueryExecutor {
  OjpDriftExecutor(
    this.connection, {
    this.dialect = SqlDialect.sqlite,
    this.closeConnectionOnClose = true,
  });

  final OjpConnection connection;
  final SqlDialect dialect;
  final bool closeConnectionOnClose;
  bool _opened = false;
  bool _closed = false;

  @override
  Future<bool> ensureOpen(QueryExecutorUser user) async {
    _checkOpen();
    if (_opened) {
      return false;
    }
    await user.beforeOpen(this, OpeningDetails(null, user.schemaVersion));
    _opened = true;
    return true;
  }

  @override
  Future<List<Map<String, Object?>>> runSelect(
    String statement,
    List<Object?> args,
  ) async {
    _checkOpen();
    final result = await connection.executeQuery(
      statement,
      parameters: _normalizeArguments(args),
    );
    return [
      for (final row in result.rows)
        Map<String, Object?>.fromIterables(result.columns, row),
    ];
  }

  @override
  Future<int> runInsert(String statement, List<Object?> args) async {
    _checkOpen();
    await connection.executeUpdate(
      statement,
      parameters: _normalizeArguments(args),
    );
    return 0;
  }

  @override
  Future<int> runUpdate(String statement, List<Object?> args) {
    _checkOpen();
    return connection.executeUpdate(
      statement,
      parameters: _normalizeArguments(args),
    );
  }

  @override
  Future<int> runDelete(String statement, List<Object?> args) {
    _checkOpen();
    return connection.executeUpdate(
      statement,
      parameters: _normalizeArguments(args),
    );
  }

  @override
  Future<void> runCustom(String statement, [List<Object?>? args]) async {
    _checkOpen();
    await connection.executeUpdate(
      statement,
      parameters: _normalizeArguments(args ?? const []),
    );
  }

  @override
  Future<void> runBatched(BatchedStatements statements) async {
    for (final batch in statements.arguments) {
      await runCustom(
        statements.statements[batch.statementIndex],
        batch.arguments,
      );
    }
  }

  @override
  TransactionExecutor beginTransaction() {
    throw UnsupportedError('OJP Dart L1 does not support transactions');
  }

  @override
  QueryExecutor beginExclusive() {
    throw UnsupportedError('OJP Dart L1 does not support transactions');
  }

  @override
  Future<void> close() async {
    if (!_closed) {
      _closed = true;
    } else {
      return;
    }
    if (closeConnectionOnClose) {
      await connection.close();
    }
  }

  void _checkOpen() {
    if (_closed) {
      throw StateError('OJP Drift executor is closed');
    }
  }
}

List<Object?> _normalizeArguments(List<Object?> values) {
  return values.map((value) {
    return value is DateTime ? value.toUtc() : value;
  }).toList();
}
