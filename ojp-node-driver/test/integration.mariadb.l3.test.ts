import { registerDatabaseL3Suite } from './mysqlMariaDbL3Suite';

registerDatabaseL3Suite({
  database: 'MariaDB',
  enabledEnvironmentVariable: 'OJP_ENABLE_MARIADB_TESTS',
  fixture: 'mariadb_connections.csv',
  fixtureEnvironmentVariable: 'OJP_TEST_MARIADB_CSV',
  passwordEnvironmentVariable: 'OJP_TEST_MARIADB_PASSWORD',
});
