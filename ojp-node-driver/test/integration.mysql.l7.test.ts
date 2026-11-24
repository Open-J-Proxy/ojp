import { registerDatabaseL7Suite } from './mysqlMariaDbL7Suite';

registerDatabaseL7Suite({
  database: 'MySQL',
  enabledEnvironmentVariable: 'OJP_ENABLE_MYSQL_L7_TESTS',
  fixture: 'mysql_connections.csv',
  fixtureEnvironmentVariable: 'OJP_TEST_MYSQL_CSV',
  passwordEnvironmentVariable: 'OJP_TEST_MYSQL_PASSWORD',
});
