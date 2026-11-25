import { registerDatabaseL8Suite } from './mysqlMariaDbL8Suite';

registerDatabaseL8Suite({
  database: 'MariaDB',
  enabledEnvironmentVariable: 'OJP_ENABLE_MARIADB_L8_TESTS',
  fixture: 'mariadb_connections.csv',
  fixtureEnvironmentVariable: 'OJP_TEST_MARIADB_CSV',
  passwordEnvironmentVariable: 'OJP_TEST_MARIADB_PASSWORD',
});
