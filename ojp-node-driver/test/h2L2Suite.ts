import { randomBytes } from 'crypto';
import { OjpClient, OjpSqlError, OjpTypedParam, ParameterType } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_TEST_H2 === 'true';
const endpoint = process.env.OJP_TEST_H2_ADDR ?? 'localhost:1059';
const connections = readConnectionFixtures('h2_connections.csv', process.env.OJP_TEST_H2_CSV);

if (enabled && !process.env.OJP_TEST_H2_ADDR) {
  throw new Error('OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true');
}

(enabled ? describe : describe.skip).each(connections)(
  'H2 L2 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const credentials = {
      user,
      password: process.env.OJP_TEST_H2_PASSWORD ?? fixturePassword,
    };
    const table = `ojp_node_h2_l2_${randomBytes(6).toString('hex')}`;
    let client: OjpClient;

    beforeAll(async () => {
      const setup = new OjpClient(url, credentials);
      await setup.connect();
      try {
        await setup.executeUpdate(`
          CREATE TABLE ${table} (
            id BIGINT PRIMARY KEY,
            label VARCHAR(100),
            amount DECIMAL(18, 4),
            enabled BOOLEAN,
            event_date DATE,
            event_time TIME,
            event_timestamp TIMESTAMP,
            payload VARBINARY(64),
            tiny_value TINYINT,
            small_value SMALLINT,
            int_value INT,
            float_value REAL,
            double_value DOUBLE,
            stream_text VARCHAR(100),
            stream_binary VARBINARY(64)
          )
        `);
      } finally {
        await setup.close();
      }
    }, 60_000);

    beforeEach(async () => {
      client = new OjpClient(url, credentials);
      await client.connect();
    }, 30_000);

    afterEach(async () => {
      if (client) {
        await client.close();
      }
    }, 30_000);

    afterAll(async () => {
      const cleanup = new OjpClient(url, credentials);
      await cleanup.connect();
      try {
        await cleanup.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
      } finally {
        await cleanup.close();
      }
    }, 60_000);

    it('round-trips numeric, text, binary, boolean, temporal, and typed NULL parameters', async () => {
      const insert = client.prepareStatement(
        `INSERT INTO ${table} (id, label, amount, enabled, event_date, event_time, event_timestamp, payload, `
        + 'tiny_value, small_value, int_value, float_value, double_value, stream_text, stream_binary) '
        + 'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      );
      const date = new Date('2025-03-29T00:00:00.000Z');
      const time = new Date('1970-01-01T11:12:13.000Z');
      const timestamp = new Date('2025-03-30T21:22:23.456Z');
      const payload = Buffer.from([0, 0x41, 0xff, 0x00]);
      const streamPayload = Buffer.from([0x42, 0x00, 0xff]);
      try {
        insert.setParameter(1, 33333333333333n);
        insert.setParameter(2, new OjpTypedParam('typed H2 text', ParameterType.N_STRING));
        insert.setParameter(3, new OjpTypedParam('50000.5000', ParameterType.BIG_DECIMAL));
        insert.setParameter(4, true);
        insert.setParameter(5, new OjpTypedParam(date, ParameterType.DATE));
        insert.setParameter(6, new OjpTypedParam(time, ParameterType.TIME));
        insert.setParameter(7, new OjpTypedParam(timestamp, ParameterType.TIMESTAMP));
        insert.setParameter(8, payload);
        insert.setParameter(9, new OjpTypedParam(127, ParameterType.BYTE));
        insert.setParameter(10, new OjpTypedParam(32767, ParameterType.SHORT));
        insert.setParameter(11, 2147483647);
        insert.setParameter(12, new OjpTypedParam(20.25, ParameterType.FLOAT));
        insert.setParameter(13, new OjpTypedParam(2.2222, ParameterType.DOUBLE));
        insert.setParameter(14, new OjpTypedParam('stream π text', ParameterType.UNICODE_STREAM));
        insert.setParameter(15, new OjpTypedParam(streamPayload, ParameterType.BINARY_STREAM));
        expect(await insert.executeUpdate()).toBe(1);

        insert.clearParameters();
        insert.setParameter(1, 33333333333334n);
        insert.setNull(2, 12);
        insert.setNull(3, 3);
        insert.setNull(4, 16);
        insert.setNull(5, 91);
        insert.setNull(6, 92);
        insert.setNull(7, 93);
        insert.setNull(8, -2);
        insert.setNull(9, -6);
        insert.setNull(10, 5);
        insert.setNull(11, 4);
        insert.setNull(12, 6);
        insert.setNull(13, 8);
        insert.setNull(14, 12);
        insert.setNull(15, -2);
        expect(await insert.executeUpdate()).toBe(1);
      } finally {
        await insert.close();
      }

      const result = await client.executeQuery(
        `SELECT id AS "id", label AS "label", amount AS "amount", enabled AS "enabled", `
        + `event_date AS "event_date", event_time AS "event_time", event_timestamp AS "event_timestamp", `
        + `payload AS "payload", tiny_value AS "tiny_value", small_value AS "small_value", `
        + `int_value AS "int_value", float_value AS "float_value", double_value AS "double_value", `
        + `stream_text AS "stream_text", stream_binary AS "stream_binary" FROM ${table} ORDER BY id`,
      );
      expect(result.columns).toEqual([
        'id', 'label', 'amount', 'enabled', 'event_date', 'event_time', 'event_timestamp', 'payload',
        'tiny_value', 'small_value', 'int_value', 'float_value', 'double_value', 'stream_text', 'stream_binary',
      ]);
      expect(result.metadata).toEqual({
        columnCount: 15,
        columns: result.columns.map((label, index) => ({ index: index + 1, label })),
      });
      expect(result.rows).toHaveLength(2);
      expect(result.rows[0]).toMatchObject({
        id: 33333333333333n,
        label: 'typed H2 text',
        amount: '50000.5000',
        enabled: true,
        event_time: { hours: 11, minutes: 12, seconds: 13 },
        tiny_value: 127,
        small_value: 32767,
        int_value: 2147483647,
        float_value: 20.25,
        double_value: 2.2222,
        stream_text: 'stream π text',
      });
      expect((result.rows[0].event_date as Date).toISOString()).toBe(date.toISOString());
      expect((result.rows[0].event_timestamp as Date).toISOString()).toBe(timestamp.toISOString());
      expect((result.rows[0].payload as Buffer).equals(payload)).toBe(true);
      expect((result.rows[0].stream_binary as Buffer).equals(streamPayload)).toBe(true);
      expect(result.rows[1]).toMatchObject({
        id: 33333333333334n,
        label: null,
        amount: null,
        enabled: null,
        event_date: null,
        event_time: null,
        event_timestamp: null,
        payload: null,
        tiny_value: null,
        small_value: null,
        int_value: null,
        float_value: null,
        double_value: null,
        stream_text: null,
        stream_binary: null,
      });
    }, 30_000);

    it('rebinds prepared parameters and executes prepared queries and updates', async () => {
      const insert = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      try {
        insert.setParameter(1, 11);
        insert.setParameter(2, 'first');
        expect(await insert.executeUpdate()).toBe(1);
        insert.setParameter(1, 12);
        insert.setParameter(2, 'second');
        expect(await insert.executeUpdate()).toBe(1);
        expect((await client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [11])).rows)
          .toEqual([{ label: 'first' }]);
      } finally {
        await insert.close();
      }

      const query = client.prepareStatement(`SELECT id, label FROM ${table} WHERE id = ?`);
      try {
        expect(await query.execute([11])).toMatchObject({
          kind: 'query',
          result: { rows: [{ id: 11n, label: 'first' }] },
        });
      } finally {
        await query.close();
      }
    }, 30_000);

    it('enforces 1-based complete prepared bindings and rejects execution after close', async () => {
      const prepared = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      expect(() => prepared.setParameter(0, 'invalid')).toThrow(RangeError);
      prepared.setParameter(2, 'missing first parameter');
      await expect(prepared.executeUpdate()).rejects.toThrow(/parameter 1 has not been set/i);
      await prepared.close();
      await expect(prepared.executeUpdate()).rejects.toThrow('Prepared statement is closed.');
    }, 15_000);

    it('supports plain Statement query, update, and automatic session propagation', async () => {
      const statement = client.createStatement();
      try {
        expect(await statement.execute(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
          [13, 'plain execute'],
        )).toEqual({ kind: 'update', affectedRows: 1 });
        expect(await statement.execute(
          `SELECT id, label FROM ${table} WHERE id = ?`,
          [13],
        )).toMatchObject({
          kind: 'query',
          result: { rows: [{ id: 13n, label: 'plain execute' }] },
        });
        expect(await statement.execute(
          `UPDATE ${table} SET label = ? WHERE id = ?`,
          ['updated', 13],
        )).toEqual({ kind: 'update', affectedRows: 1 });
        expect((await client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [13])).rows)
          .toEqual([{ label: 'updated' }]);
      } finally {
        await statement.close();
      }
    }, 30_000);

    it('executes prepared batches, clears them, and verifies each affected row', async () => {
      const batch = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      try {
        await batch.addBatch([21, 'prepared batch one']);
        await batch.addBatch([22, 'prepared batch two']);
        expect(await batch.executeBatch()).toEqual([1, 1]);
        expect((await client.executeQuery(
          `SELECT id, label FROM ${table} WHERE id BETWEEN ? AND ? ORDER BY id`,
          [21, 22],
        )).rows).toEqual([
          { id: 21n, label: 'prepared batch one' },
          { id: 22n, label: 'prepared batch two' },
        ]);
        await batch.clearBatch();
        expect(await batch.executeBatch()).toEqual([]);
      } finally {
        await batch.close();
      }
    }, 30_000);

    it('executes plain Statement batches and verifies every inserted row', async () => {
      const batch = client.createStatement();
      try {
        await batch.addBatch(`INSERT INTO ${table} (id, label) VALUES (31, 'plain batch one')`);
        await batch.addBatch(`INSERT INTO ${table} (id, label) VALUES (32, 'plain batch two')`);
        expect(await batch.executeBatch()).toEqual([1, 1]);
        expect((await client.executeQuery(
          `SELECT id, label FROM ${table} WHERE id BETWEEN 31 AND 32 ORDER BY id`,
        )).rows).toEqual([
          { id: 31n, label: 'plain batch one' },
          { id: 32n, label: 'plain batch two' },
        ]);
      } finally {
        await batch.close();
      }
    }, 30_000);

    it('returns generated keys for plain and prepared inserts', async () => {
      const keyTable = `${table}_keys`;
      await client.executeUpdate(
        `CREATE TABLE ${keyTable} (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, label VARCHAR(100))`,
      );
      try {
        const plain = client.createStatement();
        try {
          expect(await plain.executeUpdate(
            `INSERT INTO ${keyTable} (label) VALUES (?)`,
            ['plain key'],
            { generatedKeys: true },
          )).toBe(1);
          const keys = await plain.getGeneratedKeys();
          expect(keys.columns).toHaveLength(1);
          expect(keys.rows).toHaveLength(1);
          const id = keys.rows[0][keys.columns[0]];
          expect(typeof id).toBe('bigint');
          expect((await client.executeQuery(`SELECT label FROM ${keyTable} WHERE id = ?`, [id])).rows)
            .toEqual([{ label: 'plain key' }]);
        } finally {
          await plain.close();
        }

        const prepared = client.prepareStatement(
          `INSERT INTO ${keyTable} (label) VALUES (?)`,
          { generatedKeys: true },
        );
        try {
          expect(await prepared.executeUpdate(['prepared key'])).toBe(1);
          const keys = await prepared.getGeneratedKeys();
          expect(keys.columns).toHaveLength(1);
          expect(keys.rows).toHaveLength(1);
          const id = keys.rows[0][keys.columns[0]];
          expect(typeof id).toBe('bigint');
          expect((await client.executeQuery(`SELECT label FROM ${keyTable} WHERE id = ?`, [id])).rows)
            .toEqual([{ label: 'prepared key' }]);
        } finally {
          await prepared.close();
        }
      } finally {
        await client.executeUpdate(`DROP TABLE IF EXISTS ${keyTable}`);
      }
    }, 30_000);

    it('returns one generated key per prepared batch row', async () => {
      const keyTable = `${table}_batch_keys`;
      await client.executeUpdate(
        `CREATE TABLE ${keyTable} (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, label VARCHAR(100))`,
      );
      const batch = client.prepareStatement(
        `INSERT INTO ${keyTable} (label) VALUES (?)`,
        { generatedKeys: true },
      );
      try {
        await batch.addBatch(['batch key one']);
        await batch.addBatch(['batch key two']);
        await batch.addBatch(['batch key three']);
        expect(await batch.executeBatch()).toEqual([1, 1, 1]);
        const keys = await batch.getGeneratedKeys();
        expect(keys.columns).toHaveLength(1);
        expect(keys.rows).toHaveLength(3);
        const ids = keys.rows.map((row) => row[keys.columns[0]]);
        expect(ids.every((id) => typeof id === 'bigint' && id > 0n)).toBe(true);
        const inserted = await client.executeQuery(`SELECT id, label FROM ${keyTable} ORDER BY id`);
        expect(inserted.rows).toHaveLength(3);
        expect(inserted.rows.map((row) => row.label)).toEqual([
          'batch key one', 'batch key two', 'batch key three',
        ]);
        expect(ids.every((id) => inserted.rows.some((row) => row.id === id))).toBe(true);
      } finally {
        await batch.close();
        await client.executeUpdate(`DROP TABLE IF EXISTS ${keyTable}`);
      }
    }, 30_000);

    it('returns alias metadata and well-formed empty query results', async () => {
      const result = await client.executeQuery(
        `SELECT id AS "id", label AS "label" FROM ${table} WHERE id = ?`,
        [-1],
      );
      expect(result.rows).toEqual([]);
      expect(result.columns).toEqual(['id', 'label']);
      expect(result.metadata).toEqual({
        columnCount: 2,
        columns: [
          { index: 1, label: 'id' },
          { index: 2, label: 'label' },
        ],
      });
    }, 15_000);

    it('returns rows from direct and prepared H2 data-changing table queries', async () => {
      const statement = client.createStatement();
      try {
        const direct = await statement.execute(
          `SELECT id, label FROM FINAL TABLE (INSERT INTO ${table} (id, label) VALUES (?, ?))`,
          [41, 'direct returning'],
        );
        expect(direct.kind).toBe('query');
        if (direct.kind !== 'query') {
          throw new Error('H2 data-changing table expression did not return rows.');
        }
        expect(direct.result.rows).toEqual([{ id: 41n, label: 'direct returning' }]);
        expect(direct.result.metadata).toEqual({
          columnCount: 2,
          columns: [{ index: 1, label: 'ID' }, { index: 2, label: 'LABEL' }],
        });

        const prepared = client.prepareStatement(
          `SELECT id, label FROM FINAL TABLE (INSERT INTO ${table} (id, label) VALUES (?, ?))`,
        );
        try {
          const rows = await prepared.executeQuery([42, 'prepared returning']);
          expect(rows.rows).toEqual([{ id: 42n, label: 'prepared returning' }]);
          expect(rows.columns).toEqual(['ID', 'LABEL']);
        } finally {
          await prepared.close();
        }
      } finally {
        await statement.close();
      }
    }, 30_000);

    it('surfaces SQL errors and rejects unsupported protocol parameter shapes', async () => {
      await expect(client.executeQuery('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
      await expect(client.executeUpdate(
        'SELECT ?',
        [new OjpTypedParam([], ParameterType.ARRAY)],
      )).rejects.toThrow(/ARRAY requires an OJP server-side array reference/);
      await expect(client.executeUpdate(
        'SELECT ?',
        [new OjpTypedParam({}, ParameterType.REF)],
      )).rejects.toThrow(/REF cannot be represented/);
    }, 15_000);
  },
);
