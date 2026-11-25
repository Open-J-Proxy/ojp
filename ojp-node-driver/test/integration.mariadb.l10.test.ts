import { registerDatabaseL10Suite } from './mysqlMariaDbL10Suite';

registerDatabaseL10Suite({
  database: 'MariaDB',
  enabledEnvironmentVariable: 'OJP_ENABLE_MARIADB_L10_TESTS',
  fixture: 'mariadb_connections.csv',
  fixtureEnvironmentVariable: 'OJP_TEST_MARIADB_CSV',
  passwordEnvironmentVariable: 'OJP_TEST_MARIADB_PASSWORD',
});
