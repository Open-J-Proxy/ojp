import { registerDatabaseL9Suite } from './mysqlMariaDbL9Suite';

registerDatabaseL9Suite({
  database: 'MariaDB',
  enabledEnvironmentVariable: 'OJP_ENABLE_MARIADB_L9_TESTS',
  fixture: 'mariadb_connections.csv',
  fixtureEnvironmentVariable: 'OJP_TEST_MARIADB_CSV',
  passwordEnvironmentVariable: 'OJP_TEST_MARIADB_PASSWORD',
});
