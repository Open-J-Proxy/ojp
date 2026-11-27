/**
 * Real OJP server and H2 LOB integration, enabled by OJP_ENABLE_LOB_H2_TESTS=true.
 * Every CSV connection row runs the full suite.
 */
import { Readable } from 'stream';
import { OjpClient, OjpTypedParam, ParameterType } from '../src';
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

  it('should distinguish empty LOB values from SQL NULL', async () => {
    const emptyBlob = await client.createLob(Buffer.alloc(0), 'BLOB');
    const emptyClob = await client.createLob('', 'CLOB');
    await client.executeUpdate(
      `INSERT INTO ${tableName} (id, payload, big_text) VALUES (?, ?, ?)`,
      [5, emptyBlob, emptyClob],
    );
    await client.executeUpdate(
      `INSERT INTO ${tableName} (id, payload, big_text) VALUES (?, ?, ?)`,
      [6, null, null],
    );

    const { rows } = await client.executeQuery(
      `SELECT payload, big_text FROM ${tableName} WHERE id IN (?, ?) ORDER BY id`,
      [5, 6],
    );
    expect(rows[0].PAYLOAD).toEqual(Buffer.alloc(0));
    expect(rows[0].BIG_TEXT).toBe('');
    expect(rows[1].PAYLOAD).toBeNull();
    expect(rows[1].BIG_TEXT).toBeNull();
  }, 15_000);

  it('should reuse an uploaded LOB reference for inserts and updates', async () => {
    const content = Buffer.from('reusable H2 BLOB reference');
    const ref = await client.createLob(content, 'BLOB');
    await client.executeUpdate(`INSERT INTO ${tableName} (id, payload) VALUES (?, ?)`, [7, ref]);
    await client.executeUpdate(`INSERT INTO ${tableName} (id, payload) VALUES (?, ?)`, [8, ref]);
    await client.executeUpdate(`UPDATE ${tableName} SET payload = ? WHERE id = ?`, [ref, 7]);

    const { rows } = await client.executeQuery(
      `SELECT payload FROM ${tableName} WHERE id IN (?, ?) ORDER BY id`,
      [7, 8],
    );
    expect(rows).toHaveLength(2);
    for (const row of rows) {
      expect((row.PAYLOAD as Buffer).equals(content)).toBe(true);
    }
  }, 15_000);

  it('should upload a BLOB from a Node readable stream', async () => {
    const content = Buffer.from('readable stream upload through OJP');
    const ref = await client.createLob(
      Readable.from([content.subarray(0, 9), content.subarray(9)]),
      'BLOB',
    );
    await client.executeUpdate(`INSERT INTO ${tableName} (id, payload) VALUES (?, ?)`, [9, ref]);

    const { rows } = await client.executeQuery(`SELECT payload FROM ${tableName} WHERE id = ?`, [9]);
    expect((rows[0].PAYLOAD as Buffer).equals(content)).toBe(true);
  }, 15_000);

  it('should round-trip large binary and Unicode stream parameters through H2 LOB columns', async () => {
    const binary = Buffer.alloc(200_000);
    for (let index = 0; index < binary.length; index++) {
      binary[index] = (index * 31) % 256;
    }
    const text = 'H2 Unicode stream: äöü ñ 中文 🚀 — '.repeat(5_000);
    await client.executeUpdate(
      `INSERT INTO ${tableName} (id, payload, big_text) VALUES (?, ?, ?)`,
      [
        10,
        new OjpTypedParam(binary, ParameterType.BINARY_STREAM),
        new OjpTypedParam(text, ParameterType.UNICODE_STREAM),
      ],
    );

    const { rows } = await client.executeQuery(
      `SELECT payload, big_text FROM ${tableName} WHERE id = ?`,
      [10],
    );
    expect((rows[0].PAYLOAD as Buffer).length).toBe(binary.length);
    expect((rows[0].PAYLOAD as Buffer).equals(binary)).toBe(true);
    expect(rows[0].BIG_TEXT).toBe(text);
  }, 30_000);

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
