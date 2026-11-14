import { OjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_SQLSERVER_L5_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const connections = readConnectionFixtures('sqlserver_connections.csv', process.env.OJP_TEST_SQLSERVER_CSV);

describeIfEnabled.each(connections)('OjpClient - SQL Server L5: %s', (jdbcUrl, user, fixturePassword) => {
  const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';
  const url = ojpUrl(endpoint, jdbcUrl);
  const password = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? fixturePassword;
  const table = `ojp_node_l5_${Date.now()}_${Math.floor(Math.random() * 1_000_000)}`;
  let client: OjpClient;

  const newClient = async (): Promise<OjpClient> => {
    const connection = new OjpClient(url, { user, password });
    await connection.connect();
    return connection;
  };

  beforeAll(async () => {
    client = await newClient();
    await client.executeUpdate(
      `CREATE TABLE ${table} (id INT PRIMARY KEY, payload VARBINARY(MAX), body NVARCHAR(MAX))`,
    );
  }, 30_000);

  afterEach(async () => {
    await client.executeUpdate(`DELETE FROM ${table}`);
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

  it('round-trips Unicode BLOB content through a LOB reference and a hydrated query result', async () => {
    const content = Buffer.from('SQL Server BLOB: äöü ñ 中文 🚀', 'utf8');
    const ref = await client.createLob(content, 'BLOB');

    expect(await client.executeUpdate(`INSERT INTO ${table} (id, payload) VALUES (?, ?)`, [1, ref])).toBe(1);
    const result = await client.executeQuery(`SELECT payload FROM ${table} WHERE id = ?`, [1]);

    expect(result.rows).toHaveLength(1);
    expect(Buffer.isBuffer(result.rows[0].payload)).toBe(true);
    expect((result.rows[0].payload as Buffer).equals(content)).toBe(true);
  }, 30_000);

  it('round-trips a multi-chunk BLOB byte-for-byte', async () => {
    const content = Buffer.alloc(1_048_579);
    for (let index = 0; index < content.length; index++) {
      content[index] = (index * 31) % 256;
    }
    const ref = await client.createLob(content, 'BLOB');

    expect(await client.executeUpdate(`INSERT INTO ${table} (id, payload) VALUES (?, ?)`, [2, ref])).toBe(1);
    const result = await client.executeQuery(`SELECT payload FROM ${table} WHERE id = ?`, [2]);

    expect((result.rows[0].payload as Buffer).length).toBe(content.length);
    expect((result.rows[0].payload as Buffer).equals(content)).toBe(true);
  }, 60_000);

  it('updates a BLOB and returns only the new bytes', async () => {
    const original = Buffer.from('original binary payload');
    const updated = Buffer.from([0, 1, 2, 127, 128, 254, 255]);
    const originalRef = await client.createLob(original, 'BLOB');
    const updatedRef = await client.createLob(updated, 'BLOB');
    await client.executeUpdate(`INSERT INTO ${table} (id, payload) VALUES (?, ?)`, [3, originalRef]);

    expect(await client.executeUpdate(`UPDATE ${table} SET payload = ? WHERE id = ?`, [updatedRef, 3])).toBe(1);
    const result = await client.executeQuery(`SELECT payload FROM ${table} WHERE id = ?`, [3]);

    expect((result.rows[0].payload as Buffer).equals(updated)).toBe(true);
    expect((result.rows[0].payload as Buffer).equals(original)).toBe(false);
  }, 30_000);

  it('round-trips a multi-chunk Unicode CLOB as text', async () => {
    const content = 'SQL Server CLOB: äöü ñ 中文 🚀 — '.repeat(5_000);
    const ref = await client.createLob(content, 'CLOB');

    expect(await client.executeUpdate(`INSERT INTO ${table} (id, body) VALUES (?, ?)`, [4, ref])).toBe(1);
    const result = await client.executeQuery(`SELECT body FROM ${table} WHERE id = ?`, [4]);

    expect(result.rows).toEqual([{ body: content }]);
  }, 60_000);

  it('round-trips a NULL BLOB and NULL CLOB without hydrating them as empty values', async () => {
    expect(await client.executeUpdate(
      `INSERT INTO ${table} (id, payload, body) VALUES (?, ?, ?)`,
      [5, null, null],
    )).toBe(1);
    const result = await client.executeQuery(`SELECT payload, body FROM ${table} WHERE id = ?`, [5]);

    expect(result.rows).toEqual([{ payload: null, body: null }]);
  }, 30_000);

  it('preserves empty BLOB and CLOB values distinctly from NULL', async () => {
    const blob = await client.createLob(Buffer.alloc(0), 'BLOB');
    const clob = await client.createLob('', 'CLOB');
    await client.executeUpdate(
      `INSERT INTO ${table} (id, payload, body) VALUES (?, ?, ?)`,
      [6, blob, clob],
    );
    const result = await client.executeQuery(`SELECT payload, body FROM ${table} WHERE id = ?`, [6]);

    expect(result.rows).toHaveLength(1);
    expect(result.rows[0].payload).toEqual(Buffer.alloc(0));
    expect(result.rows[0].body).toBe('');
  }, 30_000);

  it('reuses one BLOB reference across multiple parameter bindings', async () => {
    const content = Buffer.from([0, 10, 13, 26, 127, 128, 255]);
    const ref = await client.createLob(content, 'BLOB');
    await client.executeUpdate(`INSERT INTO ${table} (id, payload) VALUES (?, ?)`, [7, ref]);
    await client.executeUpdate(`INSERT INTO ${table} (id, payload) VALUES (?, ?)`, [8, ref]);

    const result = await client.executeQuery(`SELECT id, payload FROM ${table} ORDER BY id`);

    expect(result.rows).toHaveLength(2);
    expect(result.rows.map((row) => row.id)).toEqual([7, 8]);
    expect(result.rows.map((row) => (row.payload as Buffer).equals(content))).toEqual([true, true]);
  }, 30_000);

  it('keeps the session usable after reading and binding a LOB reference', async () => {
    const content = Buffer.from('session remains usable after LOB work');
    const ref = await client.createLob(content, 'BLOB');
    await client.executeUpdate(`INSERT INTO ${table} (id, payload) VALUES (?, ?)`, [9, ref]);

    expect((await client.executeQuery(`SELECT payload FROM ${table} WHERE id = ?`, [9])).rows[0].payload)
      .toEqual(content);
    expect((await client.executeQuery('SELECT 1 AS value')).rows).toEqual([{ value: 1 }]);
  }, 30_000);
});
