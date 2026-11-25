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
        'ID', 'LABEL', 'AMOUNT', 'ENABLED', 'EVENT_DATE', 'EVENT_TIME', 'EVENT_TIMESTAMP', 'PAYLOAD',
        'TINY_VALUE', 'SMALL_VALUE', 'INT_VALUE', 'FLOAT_VALUE', 'DOUBLE_VALUE', 'STREAM_TEXT', 'STREAM_BINARY',
      ]);
      expect(result.metadata).toEqual({
        columnCount: 15,
        columns: result.columns.map((label, index) => ({ index: index + 1, label })),
      });
      expect(result.rows).toHaveLength(2);
      expect(result.rows[0]).toMatchObject({
        ID: 33333333333333n,
        LABEL: 'typed H2 text',
        AMOUNT: '50000.5000',
        ENABLED: true,
        EVENT_TIME: { hours: 11, minutes: 12, seconds: 13 },
        TINY_VALUE: 127,
        SMALL_VALUE: 32767,
        INT_VALUE: 2147483647,
        FLOAT_VALUE: 20.25,
        DOUBLE_VALUE: 2.2222,
        STREAM_TEXT: 'stream π text',
      });
      expect((result.rows[0].EVENT_DATE as Date).toISOString()).toBe(date.toISOString());
      expect((result.rows[0].EVENT_TIMESTAMP as Date).toISOString()).toBe(timestamp.toISOString());
      expect((result.rows[0].PAYLOAD as Buffer).equals(payload)).toBe(true);
      expect((result.rows[0].STREAM_BINARY as Buffer).equals(streamPayload)).toBe(true);
      expect(result.rows[1]).toMatchObject({
        ID: 33333333333334n,
        LABEL: null,
        AMOUNT: null,
        ENABLED: null,
        EVENT_DATE: null,
        EVENT_TIME: null,
        EVENT_TIMESTAMP: null,
        PAYLOAD: null,
        TINY_VALUE: null,
        SMALL_VALUE: null,
        INT_VALUE: null,
        FLOAT_VALUE: null,
        DOUBLE_VALUE: null,
        STREAM_TEXT: null,
        STREAM_BINARY: null,
      });
    }, 30_000);

    it('reports H2 URL parameter binding as unsupported', async () => {
      const urlValue = 'https://example.test/items/42?source=ojp';
      await expect(client.executeQuery(
        'SELECT ? AS "url_value"',
        [new OjpTypedParam(new URL(urlValue), ParameterType.URL)],
      )).rejects.toMatchObject({ name: 'OjpSqlError', sqlState: 'HYC00' });
    }, 15_000);

    it('binds scalar OBJECT values', async () => {
      const result = await client.executeQuery(
        'SELECT ? AS "object_value"',
        [new OjpTypedParam(42, ParameterType.OBJECT)],
      );
      expect(result.rows).toEqual([{ object_value: 42 }]);
    }, 15_000);

    it('binds opaque ROW_ID bytes without altering their contents', async () => {
      const rowIdBytes = Buffer.from([0, 0x41, 0xff, 0x00]);
      const result = await client.executeQuery(
        'SELECT CAST(? AS BINARY(4)) AS "row_id_value"',
        [new OjpTypedParam(rowIdBytes, ParameterType.ROW_ID)],
      );
      expect(result.rows).toHaveLength(1);
      expect((result.rows[0].row_id_value as Buffer).equals(rowIdBytes)).toBe(true);
    }, 15_000);

    it('round-trips ASCII, character-reader, and national-character stream parameters', async () => {
      const values: Array<[number, string, ParameterType]> = [
        [61, 'ascii stream text', ParameterType.ASCII_STREAM],
        [62, 'character reader text', ParameterType.CHARACTER_READER],
        [63, 'national stream π text', ParameterType.N_CHARACTER_STREAM],
      ];
      const insert = client.prepareStatement(`INSERT INTO ${table} (id, stream_text) VALUES (?, ?)`);
      try {
        for (const [id, text, type] of values) {
          insert.setParameter(1, id);
          insert.setParameter(2, new OjpTypedParam(text, type));
          expect(await insert.executeUpdate()).toBe(1);
        }
      } finally {
        await insert.close();
      }
      expect((await client.executeQuery(
        `SELECT id AS "id", stream_text AS "stream_text" FROM ${table} WHERE id BETWEEN 61 AND 63 ORDER BY id`,
      )).rows).toEqual(values.map(([id, text]) => ({ ID: BigInt(id), STREAM_TEXT: text })));
    }, 30_000);

    it('classifies CTE queries as result-producing statements', async () => {
      const statement = client.createStatement();
      try {
        const cte = await statement.execute('WITH numbers AS (SELECT 7 AS "n") SELECT "n" FROM numbers');
        expect(cte).toMatchObject({ kind: 'query', result: { rows: [{ n: 7 }] } });
      } finally {
        await statement.close();
      }
    }, 15_000);

    it('classifies a query with a leading comment as result-producing', async () => {
      const statement = client.createStatement();
      try {
        const commented = await statement.execute('/* leading comment */ SELECT 8 AS "n"');
        expect(commented).toMatchObject({ kind: 'query', result: { rows: [{ n: 8 }] } });
      } finally {
        await statement.close();
      }
    }, 15_000);

    it('classifies VALUES statements as result-producing', async () => {
      const statement = client.createStatement();
      try {
        const values = await statement.execute('VALUES (9), (10)');
        expect(values.kind).toBe('query');
        if (values.kind !== 'query') {
          throw new Error('H2 VALUES statement did not return a result set.');
        }
        expect(values.result.rows).toHaveLength(2);
        expect(Object.values(values.result.rows[0])).toEqual([9]);
        expect(Object.values(values.result.rows[1])).toEqual([10]);
      } finally {
        await statement.close();
      }
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
          .toEqual([{ LABEL: 'first' }]);
      } finally {
        await insert.close();
      }

      const query = client.prepareStatement(`SELECT id, label FROM ${table} WHERE id = ?`);
      try {
        expect(await query.execute([11])).toMatchObject({
          kind: 'query',
          result: { rows: [{ ID: 11n, LABEL: 'first' }] },
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
          result: { rows: [{ ID: 13n, LABEL: 'plain execute' }] },
        });
        expect(await statement.execute(
          `UPDATE ${table} SET label = ? WHERE id = ?`,
          ['updated', 13],
        )).toEqual({ kind: 'update', affectedRows: 1 });
        expect((await client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [13])).rows)
          .toEqual([{ LABEL: 'updated' }]);
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
          { ID: 21n, LABEL: 'prepared batch one' },
          { ID: 22n, LABEL: 'prepared batch two' },
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
          { ID: 31n, LABEL: 'plain batch one' },
          { ID: 32n, LABEL: 'plain batch two' },
        ]);
        await batch.addBatch(`INSERT INTO ${table} (id, label) VALUES (33, 'cleared batch')`);
        await batch.clearBatch();
        expect(await batch.executeBatch()).toEqual([]);
        expect((await client.executeQuery(`SELECT id FROM ${table} WHERE id = ?`, [33])).rows).toEqual([]);
      } finally {
        await batch.close();
      }
    }, 30_000);

    it('rejects duplicate-key inserts with the server SQL error and leaves existing data intact', async () => {
      await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [34, 'original']);
      const insert = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      try {
        insert.setParameter(1, 34);
        insert.setParameter(2, 'duplicate');
        await expect(insert.executeUpdate()).rejects.toMatchObject({
          name: 'OjpSqlError',
          sqlState: '23505',
        });
      } finally {
        await insert.close();
      }
      expect((await client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [34])).rows)
        .toEqual([{ LABEL: 'original' }]);
    }, 15_000);

    it('rejects plain Statement operations after close', async () => {
      const statement = client.createStatement();
      await statement.close();
      await expect(statement.executeQuery('SELECT 1')).rejects.toThrow('Statement is closed.');
      await expect(statement.executeUpdate('UPDATE OJP_MISSING_TABLE SET ID = 1'))
        .rejects.toThrow('Statement is closed.');
      await expect(statement.addBatch('SELECT 1')).rejects.toThrow('Statement is closed.');
      await expect(statement.close()).resolves.toBeUndefined();
    }, 15_000);

    it('returns generated keys for plain inserts', async () => {
      const keyTable = `${table}_plain_keys`;
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
            .toEqual([{ LABEL: 'plain key' }]);
        } finally {
          await plain.close();
        }
      } finally {
        await client.executeUpdate(`DROP TABLE IF EXISTS ${keyTable}`);
      }
    }, 30_000);

    it('returns generated keys for prepared inserts', async () => {
      const keyTable = `${table}_prepared_keys`;
      await client.executeUpdate(
        `CREATE TABLE ${keyTable} (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, label VARCHAR(100))`,
      );
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
          .toEqual([{ LABEL: 'prepared key' }]);
      } finally {
        await prepared.close();
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
        expect(inserted.rows.map((row) => row.LABEL)).toEqual([
          'batch key one', 'batch key two', 'batch key three',
        ]);
        expect(ids.every((id) => inserted.rows.some((row) => row.ID === id))).toBe(true);
      } finally {
        await batch.close();
        await client.executeUpdate(`DROP TABLE IF EXISTS ${keyTable}`);
      }
    }, 30_000);

    it('returns H2 column names in metadata and well-formed empty query results', async () => {
      const result = await client.executeQuery(
        `SELECT id AS "id", label AS "label" FROM ${table} WHERE id = ?`,
        [-1],
      );
      expect(result.rows).toEqual([]);
      expect(result.columns).toEqual(['ID', 'LABEL']);
      expect(result.metadata).toEqual({
        columnCount: 2,
        columns: [
          { index: 1, label: 'ID' },
          { index: 2, label: 'LABEL' },
        ],
      });
    }, 15_000);

    it('returns rows from direct H2 data-changing table queries', async () => {
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
        expect(direct.result.rows).toEqual([{ ID: 41n, LABEL: 'direct returning' }]);
        expect(direct.result.metadata).toEqual({
          columnCount: 2,
          columns: [{ index: 1, label: 'ID' }, { index: 2, label: 'LABEL' }],
        });
      } finally {
        await statement.close();
      }
    }, 30_000);

    it('returns rows from prepared H2 data-changing table queries', async () => {
      const prepared = client.prepareStatement(
        `SELECT id, label FROM FINAL TABLE (INSERT INTO ${table} (id, label) VALUES (?, ?))`,
      );
      try {
        const rows = await prepared.executeQuery([42, 'prepared returning']);
        expect(rows.rows).toEqual([{ ID: 42n, LABEL: 'prepared returning' }]);
        expect(rows.columns).toEqual(['ID', 'LABEL']);
      } finally {
        await prepared.close();
      }
    }, 30_000);

    it('surfaces invalid SQL as an OJP SQL error', async () => {
      await expect(client.executeQuery('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
    }, 15_000);

    it('rejects unsupported ARRAY and REF parameter shapes explicitly', async () => {
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
