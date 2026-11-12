/**
 * Real OJP server and H2 LOB integration, enabled by OJP_ENABLE_LOB_H2_TESTS=true.
 * Every CSV connection row runs the full suite.
 */
import { OjpClient } from '../src/client/OjpClient';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_LOB_H2_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;

const connections = readConnectionFixtures('h2_connections.csv', process.env.OJP_TEST_H2_CSV);

describeIfEnabled.each(connections)('OjpClient - H2 LOBs: %s', (jdbcUrl, OJP_USER, OJP_PASSWORD) => {
  const OJP_URL = ojpUrl(process.env.OJP_TEST_H2_ADDR ?? 'localhost:1059', jdbcUrl);
  const tableName = `ojp_lob_h2_test_${Date.now()}`;

  let client: OjpClient;

  beforeAll(async () => {
    client = new OjpClient(OJP_URL, { user: OJP_USER, password: OJP_PASSWORD });
    await client.connect();
    await client.executeUpdate(
      `CREATE TABLE ${tableName} (id INT PRIMARY KEY, payload BLOB, big_text CLOB)`,
    );
  }, 30_000);

  afterAll(async () => {
    if (client) {
      await client.executeUpdate(`DROP TABLE IF EXISTS ${tableName}`);
      await client.close();
    }
  }, 30_000);

  it('should read a native CLOB column transparently (automatic dereferencing)', async () => {
    await client.executeUpdate(
      `INSERT INTO ${tableName} (id, big_text) VALUES (?, ?)`,
      [1, 'hello native H2 clob'],
    );
    // H2 returns unquoted column names in uppercase by default.
    const { rows } = await client.executeQuery(`SELECT big_text FROM ${tableName} WHERE id = ?`, [1]);
    expect(rows[0].BIG_TEXT).toBe('hello native H2 clob');
  }, 15_000);

  it('should upload a small BLOB (single chunk) via createLob and read it back', async () => {
    const content = Buffer.from('test binary content', 'utf8');
    const ref = await client.createLob(content, 'BLOB');
    await client.executeUpdate(`INSERT INTO ${tableName} (id, payload) VALUES (?, ?)`, [2, ref]);

    const { rows } = await client.executeQuery(`SELECT payload FROM ${tableName} WHERE id = ?`, [2]);
    expect((rows[0].PAYLOAD as Buffer).equals(content)).toBe(true);
  }, 15_000);

  it('should upload a small CLOB (single chunk) via createLob and read it back', async () => {
    const content = 'large text via createLob';
    const ref = await client.createLob(content, 'CLOB');
    await client.executeUpdate(`INSERT INTO ${tableName} (id, big_text) VALUES (?, ?)`, [3, ref]);

    const { rows } = await client.executeQuery(`SELECT big_text FROM ${tableName} WHERE id = ?`, [3]);
    expect(rows[0].BIG_TEXT).toBe(content);
  }, 15_000);

  it('should upload an empty LOB (0 bytes) without failing', async () => {
    const ref = await client.createLob(Buffer.alloc(0), 'BLOB');
    await client.executeUpdate(`INSERT INTO ${tableName} (id, payload) VALUES (?, ?)`, [4, ref]);

    const { rows } = await client.executeQuery(`SELECT payload FROM ${tableName} WHERE id = ?`, [4]);
    expect((rows[0].PAYLOAD as Buffer).length).toBe(0);
  }, 15_000);

  /**
   * Known H2 limitation (not a driver bug): a "free-standing" Blob/Clob (created via
   * `Connection.createBlob()/createClob()`, not bound to a table column) only accepts
   * ONE write call via `setBytes`/`setCharacterStream` — a second call at a subsequent
   * position fails with `SQLFeatureNotSupportedException:
   * "Allocate a new object to set its value."` (SQLState HYC00). This limits
   * `createLob` on H2 to LOBs that fit in a single chunk (`LOB_CHUNK_SIZE`, 64KB).
   * Also validated that SQL Server (via `test/integration.sqlServerAg.test.ts`) supports
   * multiple chunks normally — the Node driver's logic is correct; the limitation is
   * exclusive to H2's JDBC driver. This test documents the current behavior to
   * automatically detect if a future H2 version starts supporting it.
   */
  it('createLob with multiple chunks (>64KB) should fail on H2 with SQLFeatureNotSupportedException (known limitation)', async () => {
    const bigContent = 'A'.repeat(200_000) + 'END';
    await expect(client.createLob(bigContent, 'CLOB')).rejects.toThrow(/Feature not supported/i);
  }, 15_000);
});
