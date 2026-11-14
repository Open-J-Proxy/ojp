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

  it('streams a single row and reports end-of-results without repeating it', async () => {
    const rows = client.executeQueryStream(`SELECT id, label FROM ${table} WHERE id = ?`, [617]);
    const first = await rows.next();

    expect(first).toEqual({ done: false, value: { id: 617, label: 'l3-617' } });
    await expect(rows.next()).resolves.toEqual({ done: true, value: undefined });
  }, 15_000);

  it('streams every row across result blocks in order, without loss or duplication', async () => {
    let count = 0;
    for await (const row of client.executeQueryStream(`SELECT id, label FROM ${table} ORDER BY id`)) {
      expect(row).toEqual({ id: count, label: `l3-${count}` });
      count++;
    }

    expect(count).toBe(totalRows);
  }, 30_000);

  it('reads SQL Server OFFSET/FETCH pages without gaps or duplicate rows', async () => {
    const pageSize = 100;
    let totalRead = 0;

    for (let offset = 0; offset < totalRows; offset += pageSize) {
      const page = await client.executeQuery(
        `SELECT id, label FROM ${table} ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`,
        [offset, pageSize],
      );
      expect(page.rows).toHaveLength(pageSize);
      page.rows.forEach((row, index) => {
        const id = offset + index;
        expect(row).toEqual({ id, label: `l3-${id}` });
      });
      totalRead += page.rows.length;
    }

    expect(totalRead).toBe(totalRows);
  }, 30_000);

  it('supports independent result cursors being consumed concurrently', async () => {
    const secondClient = new OjpClient(url, { user, password });
    await secondClient.connect();
    try {
      const [firstRows, secondRows] = await Promise.all([
        collectRows(client.executeQueryStream(`SELECT id, label FROM ${table} WHERE id < 20 ORDER BY id`)),
        collectRows(secondClient.executeQueryStream(
          `SELECT id, label FROM ${table} WHERE id >= 980 ORDER BY id`,
        )),
      ]);

      expect(firstRows).toHaveLength(20);
      expect(firstRows).toEqual(Array.from({ length: 20 }, (_, id) => ({ id, label: `l3-${id}` })));
      expect(secondRows).toHaveLength(20);
      expect(secondRows).toEqual(
        Array.from({ length: 20 }, (_, index) => {
          const id = 980 + index;
          return { id, label: `l3-${id}` };
        }),
      );
    } finally {
      await secondClient.close();
    }
  }, 30_000);

  it('closes a partially consumed cursor and leaves the client usable', async () => {
    let consumed = 0;
    for await (const row of client.executeQueryStream(`SELECT id, label FROM ${table} ORDER BY id`)) {
      expect(row).toEqual({ id: consumed, label: `l3-${consumed}` });
      consumed++;
      if (consumed === 10) {
        break;
      }
    }

    expect(consumed).toBe(10);
    await expect(client.executeQuery(`SELECT id, label FROM ${table} WHERE id = ?`, [999]))
      .resolves.toMatchObject({ rows: [{ id: 999, label: 'l3-999' }] });
  }, 15_000);
});

async function collectRows(rows: AsyncIterable<Record<string, unknown>>): Promise<Record<string, unknown>[]> {
  const result: Record<string, unknown>[] = [];
  for await (const row of rows) {
    result.push(row);
  }
  return result;
}
