import { randomBytes, randomUUID } from 'crypto';
import { OjpClient, OjpLobRef } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L5 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword };
    const table = `ojp_node_l5_${randomBytes(6).toString('hex')}`;
    let client: OjpClient;

    beforeAll(async () => {
      client = new OjpClient(url, options);
      await client.connect();
      await client.executeUpdate(`
        CREATE TABLE ${table} (
          id NUMBER(10) PRIMARY KEY,
          binary_value BLOB,
          text_value CLOB
        )
      `);
    }, 60_000);

    afterEach(async () => {
      await client.executeUpdate(`DELETE FROM ${table}`);
    }, 30_000);

    afterAll(async () => {
      if (client) {
        try {
          await client.executeUpdate(`DROP TABLE ${table}`);
        } finally {
          await client.close();
        }
      }
    }, 60_000);

    it('uploads and hydrates a multi-chunk Oracle BLOB and Unicode CLOB', async () => {
      const binary = Buffer.alloc(1_048_579);
      for (let index = 0; index < binary.length; index++) {
        binary[index] = (index * 31) % 256;
      }
      const text = 'Oracle CLOB: äöü ñ 中文 🚀 — '.repeat(2_000);
      const blob = await client.createLob((async function* chunks() {
        for (let offset = 0; offset < binary.length; offset += 32_771) {
          yield binary.subarray(offset, Math.min(offset + 32_771, binary.length));
        }
      }()), 'BLOB');
      const clob = await client.createLob((async function* chunks() {
        for (let offset = 0; offset < text.length; offset += 257) {
          yield text.slice(offset, offset + 257);
        }
      }()), 'CLOB');
      const insert = client.prepareStatement(
        `INSERT INTO ${table} (id, binary_value, text_value) VALUES (?, ?, ?)`,
      );
      try {
        insert.setParameter(1, 1);
        insert.setParameter(2, blob);
        insert.setParameter(3, clob);
        expect(await insert.executeUpdate()).toBe(1);
      } finally {
        await insert.close();
      }

      for (let read = 0; read < 2; read++) {
        const row = (await client.executeQuery(
          `SELECT binary_value AS "BINARY_VALUE", text_value AS "TEXT_VALUE" FROM ${table} WHERE id = ?`,
          [1],
        )).rows[0];
        expect(row.BINARY_VALUE).toEqual(binary);
        expect(row.TEXT_VALUE).toBe(text);
      }
      expect(Buffer.byteLength(text, 'utf8')).toBeGreaterThan(32_000);
    }, 90_000);

    it('preserves a UTF-8 code point split at the 64 KiB CLOB input boundary', async () => {
      const text = `${'x'.repeat(65_535)}🚀 text after the split`;
      const encoded = Buffer.from(text, 'utf8');
      expect(encoded.length).toBeGreaterThan(65_536);
      expect(encoded[65_535]).toBe(0xf0);
      const clob = await client.createLob((async function* chunks() {
        yield encoded.subarray(0, 65_536);
        yield encoded.subarray(65_536);
      }()), 'CLOB');
      await client.executeUpdate(
        `INSERT INTO ${table} (id, text_value) VALUES (?, ?)`,
        [2, clob],
      );
      await expect(client.executeQuery(
        `SELECT text_value AS "TEXT_VALUE" FROM ${table} WHERE id = ?`, [2],
      )).resolves.toMatchObject({ rows: [{ TEXT_VALUE: text }] });
    }, 60_000);

    it('round-trips streamed BLOBs at empty, single-byte, and 64 KiB boundary sizes', async () => {
      const sizes = [0, 1, 65_535, 65_536, 65_537, 131_113];
      const payloads = sizes.map((size, index) => {
        const payload = Buffer.alloc(size);
        for (let offset = 0; offset < size; offset++) {
          payload[offset] = (offset * 37 + index) % 256;
        }
        return payload;
      });

      for (let index = 0; index < payloads.length; index++) {
        const payload = payloads[index];
        const lob = await client.createLob((async function* chunks() {
          for (let offset = 0; offset < payload.length; offset += 16_383) {
            yield payload.subarray(offset, Math.min(offset + 16_383, payload.length));
          }
        }()), 'BLOB');
        await client.executeUpdate(
          `INSERT INTO ${table} (id, binary_value) VALUES (?, ?)`,
          [10 + index, lob],
        );
      }

      const result = await client.executeQuery(
        `SELECT id AS "ID", binary_value AS "BINARY_VALUE" FROM ${table} `
        + 'WHERE id BETWEEN 10 AND 15 ORDER BY id',
      );
      expect(result.rows).toHaveLength(sizes.length);
      result.rows.forEach((row, index) => {
        expect(row.ID).toBe(String(10 + index));
        expect(row.BINARY_VALUE).toEqual(payloads[index]);
      });
    }, 90_000);

    it('preserves empty BLOB separately from NULL and observes Oracle empty-string semantics', async () => {
      await client.executeUpdate(
        `INSERT INTO ${table} (id, binary_value, text_value) VALUES (1, EMPTY_BLOB(), '')`,
      );
      const nullInsert = client.prepareStatement(
        `INSERT INTO ${table} (id, binary_value, text_value) VALUES (?, ?, ?)`,
      );
      try {
        nullInsert.setParameter(1, 2);
        nullInsert.setNull(2, 2004);
        nullInsert.setNull(3, 2005);
        expect(await nullInsert.executeUpdate()).toBe(1);
      } finally {
        await nullInsert.close();
      }

      const result = await client.executeQuery(
        `SELECT id AS "ID", binary_value AS "BINARY_VALUE", text_value AS "TEXT_VALUE" `
        + `FROM ${table} ORDER BY id`,
      );
      expect(result.rows).toHaveLength(2);
      expect(result.rows[0].BINARY_VALUE).toEqual(Buffer.alloc(0));
      // Oracle treats a zero-length VARCHAR2/CLOB value as NULL.
      expect(result.rows[0].TEXT_VALUE).toBeNull();
      expect(result.rows[1]).toEqual({ ID: '2', BINARY_VALUE: null, TEXT_VALUE: null });
    }, 30_000);

    it('round-trips an Oracle BLOB via a readable stream and updates hydrated values', async () => {
      const initial = Buffer.from([0, 0x41, 0x7f, 0x80, 0xfe, 0xff]);
      const replacement = Buffer.from('replacement binary payload');
      const initialLob = await client.createLob(initial, 'BLOB');
      await client.executeUpdate(
        `INSERT INTO ${table} (id, binary_value, text_value) VALUES (?, ?, ?)`,
        [3, initialLob, 'original Unicode text'],
      );
      const update = client.prepareStatement(`UPDATE ${table} SET binary_value = ? WHERE id = ?`);
      try {
        update.setParameter(1, await client.createLob(replacement, 'BLOB'));
        update.setParameter(2, 3);
        expect(await update.executeUpdate()).toBe(1);
      } finally {
        await update.close();
      }

      const hydrated = (await client.executeQuery(
        `SELECT binary_value AS "BINARY_VALUE" FROM ${table} WHERE id = ?`, [3],
      )).rows[0].BINARY_VALUE as Buffer;
      expect(Buffer.isBuffer(hydrated)).toBe(true);
      expect(hydrated).toEqual(replacement);
      const streamed = await new Promise<Buffer>((resolve, reject) => {
        const chunks: Buffer[] = [];
        client.readLobStream(initialLob).on('data', (chunk: Buffer) => chunks.push(chunk))
          .on('end', () => resolve(Buffer.concat(chunks)))
          .on('error', reject);
      });
      expect(streamed).toEqual(initial);
    }, 60_000);

    it('fails a read for a missing Oracle LOB reference instead of treating it as empty', async () => {
      const missing = new OjpLobRef(randomUUID(), 'BLOB');
      const read = new Promise<Buffer>((resolve, reject) => {
        const chunks: Buffer[] = [];
        client.readLobStream(missing)
          .on('data', (chunk: Buffer) => chunks.push(chunk))
          .on('end', () => resolve(Buffer.concat(chunks)))
          .on('error', reject);
      });
      await expect(read).rejects.toBeDefined();
    }, 30_000);

    it.skip(
      'JDBC Blob locator methods are unavailable: Node uses OjpLobRef upload plus readLobStream, not mutable Blob handles',
      () => {},
    );

    it.skip(
      'unbounded JDBC InputStream binding to RAW is unavailable: Node BINARY_STREAM requires a Buffer; streamed BLOB/CLOB uses createLob',
      () => {},
    );
  },
);
