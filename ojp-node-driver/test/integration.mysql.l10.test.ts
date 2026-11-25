import { registerDatabaseL10Suite } from './mysqlMariaDbL10Suite';

registerDatabaseL10Suite({
  database: 'MySQL',
  enabledEnvironmentVariable: 'OJP_ENABLE_MYSQL_L10_TESTS',
  fixture: 'mysql_connections.csv',
  fixtureEnvironmentVariable: 'OJP_TEST_MYSQL_CSV',
  passwordEnvironmentVariable: 'OJP_TEST_MYSQL_PASSWORD',
});
