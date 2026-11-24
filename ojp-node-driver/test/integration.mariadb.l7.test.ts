import { registerDatabaseL7Suite } from './mysqlMariaDbL7Suite';

registerDatabaseL7Suite({
  database: 'MariaDB',
  enabledEnvironmentVariable: 'OJP_ENABLE_MARIADB_L7_TESTS',
  fixture: 'mariadb_connections.csv',
  fixtureEnvironmentVariable: 'OJP_TEST_MARIADB_CSV',
  passwordEnvironmentVariable: 'OJP_TEST_MARIADB_PASSWORD',
});
