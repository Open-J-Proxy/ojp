import { OjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_SQLSERVER_AG_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const connections = readConnectionFixtures('sqlserver_connections.csv', process.env.OJP_TEST_SQLSERVER_CSV);

describeIfEnabled.each(connections)('OjpClient - SQL Server L3: %s', (jdbcUrl, user, fixturePassword) => {
  const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';
  const url = ojpUrl(endpoint, jdbcUrl);
  const password = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? fixturePassword;
  const table = `ojp_node_l3_${Date.now()}_${Math.floor(Math.random() * 1_000_000)}`;
  const totalRows = 1_000;
  let client: OjpClient;

  beforeAll(async () => {
    client = new OjpClient(url, { user, password });
    await client.connect();
    await client.executeUpdate(`CREATE TABLE ${table} (id INT PRIMARY KEY, label NVARCHAR(100))`);

    const values = Array.from({ length: totalRows }, (_, id) => `(${id}, N'l3-${id}')`).join(', ');
    await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES ${values}`);
  }, 30_000);

  afterAll(async () => {
    if (client) {
      try {
        await client.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
      } finally {
        await client.close();
      }
    }
  }, 30_000);

  it('preserves selected-column metadata for an empty result and reaches end of results', async () => {
    const result = await client.executeQuery(`SELECT id, label FROM ${table} WHERE id < 0`);

    expect(result.columns.map((column) => column.toLowerCase())).toEqual(['id', 'label']);
    expect(result.rows).toEqual([]);
    expect(result.metadata).toEqual({
      columnCount: 2,
      columns: result.columns.map((label, index) => ({ index: index + 1, label })),
    });
  }, 15_000);

  it('streams every row across result blocks in order, without loss or duplication', async () => {
    let count = 0;
    for await (const row of client.executeQueryStream(`SELECT id, label FROM ${table} ORDER BY id`)) {
      expect(row).toEqual({ id: count, label: `l3-${count}` });
      count++;
    }

    expect(count).toBe(totalRows);
  }, 30_000);
});
