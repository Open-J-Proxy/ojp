import { registerDatabaseL3Suite } from './mysqlMariaDbL3Suite';

registerDatabaseL3Suite({
  database: 'MySQL',
  enabledEnvironmentVariable: 'OJP_ENABLE_MYSQL_TESTS',
  fixture: 'mysql_connections.csv',
  fixtureEnvironmentVariable: 'OJP_TEST_MYSQL_CSV',
  passwordEnvironmentVariable: 'OJP_TEST_MYSQL_PASSWORD',
});
