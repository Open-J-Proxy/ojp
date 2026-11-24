import { randomBytes } from 'crypto';
import { Readable } from 'stream';
import { OjpClient, OjpConnectionError } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

interface DatabaseL5Options {
  database: 'MariaDB' | 'MySQL';
  enabledEnvironmentVariable: string;
  fixture: string;
  fixtureEnvironmentVariable: string;
  passwordEnvironmentVariable: string;
}

export function registerDatabaseL5Suite(options: DatabaseL5Options): void {
  const enabled = process.env[options.enabledEnvironmentVariable] === 'true';
  const connections = readConnectionFixtures(
    options.fixture,
    process.env[options.fixtureEnvironmentVariable],
  );
  const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

  (enabled ? describe : describe.skip).each(connections)(
    `${options.database} L5 for %s`,
    (jdbcUrl: string, user: string, fixturePassword: string) => {
      const url = ojpUrl(endpoint, jdbcUrl);
      const credentials = {
        user,
        password: process.env[options.passwordEnvironmentVariable] ?? fixturePassword,
      };
      const table = `ojp_node_l5_${randomBytes(6).toString('hex')}`;
      let client: OjpClient;

      const readStream = async (reference: Parameters<OjpClient['readLobStream']>[0]): Promise<Buffer> => {
        const chunks: Buffer[] = [];
        for await (const chunk of client.readLobStream(reference)) {
          chunks.push(Buffer.from(chunk));
        }
        return Buffer.concat(chunks);
      };

      beforeAll(async () => {
        client = new OjpClient(url, credentials);
        await client.connect();
        await client.executeUpdate(`
          CREATE TABLE ${table} (
            id INT PRIMARY KEY,
            payload LONGBLOB,
            body LONGTEXT
          )
        `);
      }, 60_000);

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
      }, 60_000);

      it('round-trips a BLOB reference through parameter binding and hydrated query results', async () => {
        const content = Buffer.from([0, 0x41, 0x7f, 0x80, 0xfe, 0xff]);
        const reference = await client.createLob(content, 'BLOB');

        expect(await client.executeUpdate(
          `INSERT INTO ${table} (id, payload) VALUES (?, ?)`,
          [1, reference],
        )).toBe(1);
        const result = await client.executeQuery(`SELECT payload FROM ${table} WHERE id = ?`, [1]);

        expect(result.rows).toHaveLength(1);
        expect(Buffer.isBuffer(result.rows[0].payload)).toBe(true);
        expect(result.rows[0].payload).toEqual(content);
      }, 30_000);

      it('uploads a multi-chunk BLOB stream and returns every byte unchanged', async () => {
        const content = Buffer.alloc(1_048_579);
        for (let index = 0; index < content.length; index++) {
          content[index] = (index * 31) % 256;
        }
        const source = Readable.from([
          content.subarray(0, 17_000),
          content.subarray(17_000, 98_000),
          content.subarray(98_000),
        ]);
        const reference = await client.createLob(source, 'BLOB');

        expect(await client.executeUpdate(
          `INSERT INTO ${table} (id, payload) VALUES (?, ?)`,
          [2, reference],
        )).toBe(1);
        const result = await client.executeQuery(`SELECT payload FROM ${table} WHERE id = ?`, [2]);
        const hydrated = result.rows[0].payload as Buffer;

        expect(hydrated.length).toBe(content.length);
        expect(hydrated.equals(content)).toBe(true);
      }, 60_000);

      it('uploads and hydrates a multi-chunk Unicode CLOB stream without changing text', async () => {
        const content = 'MariaDB/MySQL CLOB: äöü ñ 中文 🚀 — '.repeat(5_000);
        const source = Readable.from([
          content.slice(0, 137),
          content.slice(137, 3_141),
          content.slice(3_141),
        ]);
        const reference = await client.createLob(source, 'CLOB');

        expect(await client.executeUpdate(
          `INSERT INTO ${table} (id, body) VALUES (?, ?)`,
          [3, reference],
        )).toBe(1);
        const result = await client.executeQuery(`SELECT body FROM ${table} WHERE id = ?`, [3]);

        expect(result.rows).toEqual([{ body: content }]);
      }, 60_000);

      it('reads BLOB and CLOB references as streams with exact bytes and UTF-8 content', async () => {
        const binary = Buffer.alloc(90_123);
        for (let index = 0; index < binary.length; index++) {
          binary[index] = (index * 17) % 256;
        }
        const text = 'streamed CLOB: äöü ñ 中文 🚀 — '.repeat(1_000);
        const blobReference = await client.createLob(Readable.from([binary]), 'BLOB');
        const clobReference = await client.createLob(Readable.from([text]), 'CLOB');

        const blobResult = await readStream(blobReference);
        const clobResult = await readStream(clobReference);

        expect(blobResult.length).toBe(binary.length);
        expect(blobResult.equals(binary)).toBe(true);
        expect(clobResult.length).toBe(Buffer.byteLength(text, 'utf8'));
        expect(clobResult.toString('utf8')).toBe(text);
      }, 60_000);

      it('preserves empty BLOB/CLOB values separately from SQL NULL', async () => {
        const emptyBlob = await client.createLob(Buffer.alloc(0), 'BLOB');
        const emptyClob = await client.createLob('', 'CLOB');
        expect(await client.executeUpdate(
          `INSERT INTO ${table} (id, payload, body) VALUES (?, ?, ?)`,
          [4, emptyBlob, emptyClob],
        )).toBe(1);
        expect(await client.executeUpdate(
          `INSERT INTO ${table} (id, payload, body) VALUES (?, ?, ?)`,
          [5, null, null],
        )).toBe(1);

        const result = await client.executeQuery(
          `SELECT id, payload, body FROM ${table} ORDER BY id`,
        );

        expect(result.rows).toEqual([
          { id: 4, payload: Buffer.alloc(0), body: '' },
          { id: 5, payload: null, body: null },
        ]);
      }, 30_000);

      it('updates BLOB and CLOB columns and returns only the replacement content', async () => {
        const originalBytes = Buffer.from('original binary content');
        const updatedBytes = Buffer.from([0, 1, 0x7f, 0x80, 0xff]);
        const originalText = 'original text';
        const updatedText = 'updated Unicode text — 中文 🚀';
        const originalBlob = await client.createLob(originalBytes, 'BLOB');
        const updatedBlob = await client.createLob(updatedBytes, 'BLOB');
        const originalClob = await client.createLob(originalText, 'CLOB');
        const updatedClob = await client.createLob(updatedText, 'CLOB');
        await client.executeUpdate(
          `INSERT INTO ${table} (id, payload, body) VALUES (?, ?, ?)`,
          [6, originalBlob, originalClob],
        );

        expect(await client.executeUpdate(
          `UPDATE ${table} SET payload = ?, body = ? WHERE id = ?`,
          [updatedBlob, updatedClob, 6],
        )).toBe(1);
        const result = await client.executeQuery(
          `SELECT payload, body FROM ${table} WHERE id = ?`,
          [6],
        );

        expect(result.rows).toEqual([{ payload: updatedBytes, body: updatedText }]);
        expect(result.rows[0].payload).not.toEqual(originalBytes);
        expect(result.rows[0].body).not.toBe(originalText);
      }, 30_000);

      it('reuses one BLOB reference across multiple parameter bindings', async () => {
        const content = Buffer.from([0, 10, 13, 26, 127, 128, 255]);
        const reference = await client.createLob(content, 'BLOB');
        expect(await client.executeUpdate(
          `INSERT INTO ${table} (id, payload) VALUES (?, ?), (?, ?)`,
          [7, reference, 8, reference],
        )).toBe(2);
        const result = await client.executeQuery(
          `SELECT id, payload FROM ${table} ORDER BY id`,
        );

        expect(result.rows).toHaveLength(2);
        expect(result.rows.map((row) => row.id)).toEqual([7, 8]);
        expect(result.rows.map((row) => (row.payload as Buffer).equals(content))).toEqual([true, true]);
      }, 30_000);

      it('keeps the session usable after LOB upload, read, and binding', async () => {
        const content = Buffer.from('session remains usable after LOB work');
        const reference = await client.createLob(content, 'BLOB');
        await client.executeUpdate(
          `INSERT INTO ${table} (id, payload) VALUES (?, ?)`,
          [9, reference],
        );

        expect((await readStream(reference)).equals(content)).toBe(true);
        expect((await client.executeQuery(`SELECT payload FROM ${table} WHERE id = ?`, [9])).rows[0].payload)
          .toEqual(content);
        expect((await client.executeQuery('SELECT 1 AS value')).rows).toEqual([{ value: 1 }]);
      }, 30_000);

      it('does not allow a LOB reference to be read after its session closes', async () => {
        const scoped = new OjpClient(url, credentials);
        await scoped.connect();
        const reference = await scoped.createLob(Buffer.from('session-bound LOB'), 'BLOB');
        await scoped.close();

        expect(() => scoped.readLobStream(reference)).toThrow(OjpConnectionError);
      }, 30_000);
    },
  );
}
