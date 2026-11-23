import { randomBytes } from 'crypto';
import { OjpClient, OjpTypedParam, ParameterType } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_INTEGRATION_TESTS === 'true';
const connections = readConnectionFixtures('postgresql_connections.csv', process.env.OJP_TEST_POSTGRES_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

(enabled ? describe : describe.skip).each(connections)(
  'PostgreSQL L5 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_PASSWORD ?? fixturePassword };
    const table = `ojp_node_l5_${randomBytes(6).toString('hex')}`;
    let client: OjpClient;

    beforeAll(async () => {
      client = new OjpClient(url, options);
      await client.connect();
      await client.executeUpdate(`
        CREATE TABLE ${table} (
          id INTEGER PRIMARY KEY,
          binary_value BYTEA,
          text_value TEXT
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

    it('round-trips binary stream parameters and returns hydrated bytes', async () => {
      const content = Buffer.from([0, 0x41, 0x7f, 0x80, 0xfe, 0xff]);
      const insert = client.prepareStatement(`INSERT INTO ${table} (id, binary_value) VALUES (?, ?)`);
      try {
        insert.setParameter(1, 1);
        insert.setParameter(2, new OjpTypedParam(content, ParameterType.BINARY_STREAM));
        expect(await insert.executeUpdate()).toBe(1);

        const result = await client.executeQuery(`SELECT binary_value FROM ${table} WHERE id = ?`, [1]);
        expect(result.rows).toHaveLength(1);
        expect(Buffer.isBuffer(result.rows[0].binary_value)).toBe(true);
        expect(result.rows[0].binary_value).toEqual(content);
      } finally {
        await insert.close();
      }
    }, 30_000);

    it('round-trips a large binary stream byte-for-byte and permits repeated hydrated reads', async () => {
      const content = Buffer.alloc(1_048_579);
      for (let index = 0; index < content.length; index++) {
        content[index] = (index * 31) % 256;
      }
      const insert = client.prepareStatement(`INSERT INTO ${table} (id, binary_value) VALUES (?, ?)`);
      try {
        insert.setParameter(1, 2);
        insert.setParameter(2, new OjpTypedParam(content, ParameterType.BINARY_STREAM));
        expect(await insert.executeUpdate()).toBe(1);
      } finally {
        await insert.close();
      }

      for (let read = 0; read < 2; read++) {
        const result = await client.executeQuery(`SELECT binary_value FROM ${table} WHERE id = ?`, [2]);
        const hydrated = result.rows[0].binary_value;
        expect(Buffer.isBuffer(hydrated)).toBe(true);
        expect((hydrated as Buffer).length).toBe(content.length);
        expect(hydrated).toEqual(content);
      }
    }, 60_000);

    it('round-trips Unicode TEXT values without losing content or UTF-8 bytes', async () => {
      const content = 'PostgreSQL TEXT value: äöü ñ 中文 🚀 — '.repeat(2_000);
      const insert = client.prepareStatement(`INSERT INTO ${table} (id, text_value) VALUES (?, ?)`);
      try {
        insert.setParameter(1, 3);
        insert.setParameter(2, content);
        expect(await insert.executeUpdate()).toBe(1);
      } finally {
        await insert.close();
      }

      const result = await client.executeQuery(`SELECT text_value FROM ${table} WHERE id = ?`, [3]);
      expect(result.rows).toEqual([{ text_value: content }]);
      expect(Buffer.byteLength(result.rows[0].text_value as string, 'utf8')).toBe(Buffer.byteLength(content, 'utf8'));
    }, 60_000);

    it('preserves empty binary/text values as distinct from SQL NULL', async () => {
      const insert = client.prepareStatement(
        `INSERT INTO ${table} (id, binary_value, text_value) VALUES (?, ?, ?)`,
      );
      try {
        insert.setParameter(1, 4);
        insert.setParameter(2, new OjpTypedParam(Buffer.alloc(0), ParameterType.BINARY_STREAM));
        insert.setParameter(3, '');
        expect(await insert.executeUpdate()).toBe(1);

        insert.clearParameters();
        insert.setParameter(1, 5);
        insert.setNull(2, -2);
        insert.setNull(3, 12);
        expect(await insert.executeUpdate()).toBe(1);
      } finally {
        await insert.close();
      }

      const result = await client.executeQuery(
        `SELECT id, binary_value, text_value FROM ${table} ORDER BY id`,
      );
      expect(result.rows).toHaveLength(2);
      expect(result.rows[0]).toEqual({
        id: 4,
        binary_value: Buffer.alloc(0),
        text_value: '',
      });
      expect(result.rows[1]).toEqual({
        id: 5,
        binary_value: null,
        text_value: null,
      });
    }, 30_000);

    it('updates hydrated binary and text values and returns only the replacement content', async () => {
      const originalBytes = Buffer.from('original binary value');
      const updatedBytes = Buffer.from([0, 1, 127, 128, 255]);
      const updatedText = 'updated Unicode text — 中文 🚀';
      await client.executeUpdate(
        `INSERT INTO ${table} (id, binary_value, text_value) VALUES (?, ?, ?)`,
        [6, originalBytes, 'original text'],
      );

      const update = client.prepareStatement(
        `UPDATE ${table} SET binary_value = ?, text_value = ? WHERE id = ?`,
      );
      try {
        update.setParameter(1, new OjpTypedParam(updatedBytes, ParameterType.BINARY_STREAM));
        update.setParameter(2, updatedText);
        update.setParameter(3, 6);
        expect(await update.executeUpdate()).toBe(1);
      } finally {
        await update.close();
      }

      const result = await client.executeQuery(
        `SELECT binary_value, text_value FROM ${table} WHERE id = ?`,
        [6],
      );
      expect(result.rows).toEqual([{ binary_value: updatedBytes, text_value: updatedText }]);
      expect(result.rows[0].binary_value).not.toEqual(originalBytes);
    }, 30_000);
  },
);
