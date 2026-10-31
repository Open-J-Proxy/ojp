import 'dart:io';
import 'dart:math';

import 'package:csv/csv.dart';
import 'package:grpc/grpc.dart';
import 'package:ojp_client_dart_drift/ojp_client_dart_drift.dart';
import 'package:test/test.dart';

void main() {
  final skipReason = _integrationSkipReason();

  test('H2 connection CSV contains one backend configuration', () async {
    expect(await _readH2ConnectionConfig(), (
      'jdbc:h2:mem:ojp_dart_h2_l1;DB_CLOSE_DELAY=-1',
      'sa',
      '',
    ));
  });

  test('OJP endpoint parser rejects malformed addresses', () async {
    await expectLater(
      OjpConnection.connect(endpoint: 'localhost', jdbcUrl: 'jdbc:h2:mem:test'),
      throwsArgumentError,
    );
    await expectLater(
      OjpConnection.connect(
        endpoint: 'localhost:1059/path',
        jdbcUrl: 'jdbc:h2:mem:test',
      ),
      throwsArgumentError,
    );
  });

  test(
    'H2 supports Dart L1 connectivity, CRUD, and session lifecycle',
    () async {
      final endpoint = Platform.environment['OJP_TEST_H2_ADDR']?.trim();
      if (endpoint == null || endpoint.isEmpty) {
        fail('OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true');
      }
      final connectionConfig = await _readH2ConnectionConfig();
      final connection = await OjpConnection.connect(
        endpoint: endpoint,
        jdbcUrl: connectionConfig.$1,
        username: connectionConfig.$2,
        password: connectionConfig.$3,
      );
      final executor = OjpDriftExecutor(connection);
      final table = 'ojp_dart_l1_${_randomSuffix()}';

      try {
        final readiness = await connection.executeQuery('SELECT 1');
        expect(readiness.rows, [
          [1],
        ]);

        await executor.runCustom(
          'CREATE TABLE $table (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)',
        );
        expect(
          await executor.runInsert(
            'INSERT INTO $table (id, name) VALUES (?, ?)',
            [1, 'before'],
          ),
          0,
        );
        expect(
          await executor.runSelect('SELECT id, name FROM $table WHERE id = ?', [
            1,
          ]),
          [
            {'ID': 1, 'NAME': 'before'},
          ],
        );

        expect(
          await executor.runUpdate('UPDATE $table SET name = ? WHERE id = ?', [
            'after',
            1,
          ]),
          1,
        );
        expect(
          await executor.runSelect('SELECT id, name FROM $table WHERE id = ?', [
            1,
          ]),
          [
            {'ID': 1, 'NAME': 'after'},
          ],
        );

        expect(
          await executor.runDelete('DELETE FROM $table WHERE id = ?', [1]),
          1,
        );
        final emptyResult = await executor.runSelect(
          'SELECT id, name FROM $table WHERE id = ?',
          [1],
        );
        expect(emptyResult, isEmpty);

        await expectLater(
          executor.runSelect('THIS IS NOT VALID SQL', const []),
          throwsA(isA<GrpcError>()),
        );

        await executor.runCustom('DROP TABLE IF EXISTS $table');
      } finally {
        await executor.close();
      }

      await expectLater(
        connection.executeQuery('SELECT 1'),
        throwsA(isA<StateError>()),
      );
    },
    skip: skipReason,
  );
}

Future<(String, String, String)> _readH2ConnectionConfig() async {
  final text = await File('testdata/h2_l1_connection.csv').readAsString();
  final records = csv.decode(text);
  if (records.length != 1 || records.single.length != 3) {
    throw const FormatException(
      'Expected one CSV record with JDBC URL, username, and password',
    );
  }
  final jdbcUrl = records.single[0].toString().trim();
  if (jdbcUrl.isEmpty) {
    throw const FormatException('H2 JDBC URL must not be empty');
  }
  return (
    jdbcUrl,
    records.single[1].toString().trim(),
    records.single[2].toString(),
  );
}

String? _integrationSkipReason() {
  final value = Platform.environment['OJP_TEST_H2']?.trim().toLowerCase() ?? '';
  switch (value) {
    case '':
    case 'false':
    case '0':
    case 'no':
      return 'set OJP_TEST_H2=true to run against an OJP H2 server';
    case 'true':
    case '1':
    case 'yes':
      return null;
    default:
      throw FormatException('OJP_TEST_H2 must be true or false, got "$value"');
  }
}

String _randomSuffix() {
  final random = Random.secure();
  return List<int>.generate(
    6,
    (_) => random.nextInt(256),
  ).map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();
}
