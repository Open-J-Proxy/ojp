import { randomBytes } from 'crypto';
import { OjpClient, OjpTypedParam, ParameterType } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_INTEGRATION_TESTS === 'true';
const connections = readConnectionFixtures('postgresql_connections.csv', process.env.OJP_TEST_POSTGRES_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

(enabled ? describe : describe.skip).each(connections)(
  'PostgreSQL L2 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_PASSWORD ?? fixturePassword };
    const table = `ojp_node_l2_${randomBytes(6).toString('hex')}`;
    let client: OjpClient;

    beforeAll(async () => {
      client = new OjpClient(url, options);
      await client.connect();
      await client.executeUpdate(`
        CREATE TABLE ${table} (
          id BIGINT PRIMARY KEY,
          label TEXT,
          amount NUMERIC(18, 4),
          enabled BOOLEAN,
          event_date DATE,
          event_time TIME,
          event_timestamp TIMESTAMP,
          payload BYTEA,
          small_value SMALLINT,
          integer_value INTEGER,
          real_value REAL,
          double_value DOUBLE PRECISION,
          stream_text TEXT,
          stream_binary BYTEA
        )
      `);
    }, 60_000);

    afterAll(async () => {
      if (client) {
        await client.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
        await client.close();
      }
    }, 60_000);

    it('binds typed PostgreSQL values, 1-based parameters, exact decimals, binary, and typed NULLs', async () => {
      const prepared = client.prepareStatement(
        `INSERT INTO ${table} (id, label, amount, enabled, event_date, event_time, event_timestamp, payload, `
        + 'small_value, integer_value, real_value, double_value, stream_text, stream_binary) '
        + 'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      );
      const eventDate = new Date('2025-03-29T00:00:00.000Z');
      const eventTime = new Date('1970-01-01T11:12:13.000Z');
      const eventTimestamp = new Date('2025-03-30T21:22:23.456Z');
      const payload = Buffer.from([0, 0x41, 0xff, 0x00]);

      try {
        prepared.setParameter(1, 33333333333333n);
        prepared.setParameter(2, new OjpTypedParam('typed PostgreSQL text', ParameterType.N_STRING));
        prepared.setParameter(3, new OjpTypedParam('50000.5000', ParameterType.BIG_DECIMAL));
        prepared.setParameter(4, true);
        prepared.setParameter(5, new OjpTypedParam(eventDate, ParameterType.DATE));
        prepared.setParameter(6, new OjpTypedParam(eventTime, ParameterType.TIME));
        prepared.setParameter(7, eventTimestamp);
        prepared.setParameter(8, payload);
        prepared.setParameter(9, new OjpTypedParam(127, ParameterType.BYTE));
        prepared.setParameter(10, new OjpTypedParam(32767, ParameterType.SHORT));
        prepared.setParameter(11, new OjpTypedParam(20.25, ParameterType.FLOAT));
        prepared.setParameter(12, new OjpTypedParam(2.2222, ParameterType.DOUBLE));
        prepared.setParameter(13, new OjpTypedParam('stream π text', ParameterType.UNICODE_STREAM));
        const streamPayload = Buffer.from('binary stream');
        prepared.setParameter(14, new OjpTypedParam(streamPayload, ParameterType.BINARY_STREAM));
        expect(await prepared.executeUpdate()).toBe(1);

        prepared.clearParameters();
        prepared.setParameter(1, 33333333333334n);
        prepared.setNull(2, 12);
        prepared.setNull(3, 3);
        prepared.setNull(4, 16);
        prepared.setNull(5, 91);
        prepared.setNull(6, 92);
        prepared.setNull(7, 93);
        prepared.setNull(8, -2);
        prepared.setNull(9, 5);
        prepared.setNull(10, 4);
        prepared.setNull(11, 7);
        prepared.setNull(12, 8);
        prepared.setNull(13, 12);
        prepared.setNull(14, -2);
        expect(await prepared.executeUpdate()).toBe(1);

        const { columns, rows, metadata } = await client.executeQuery(
          `SELECT id, label, amount, enabled, event_date, event_time, event_timestamp, payload, `
          + 'small_value, integer_value, real_value, double_value, stream_text, stream_binary '
          + `FROM ${table} WHERE id >= ? ORDER BY id`,
          [33333333333333n],
        );
        expect(columns).toEqual([
          'id', 'label', 'amount', 'enabled', 'event_date', 'event_time', 'event_timestamp', 'payload',
          'small_value', 'integer_value', 'real_value', 'double_value', 'stream_text', 'stream_binary',
        ]);
        expect(metadata).toEqual({
          columnCount: 14,
          columns: columns.map((label, index) => ({ index: index + 1, label })),
        });
        expect(rows).toHaveLength(2);
        expect(rows[0]).toMatchObject({
          id: 33333333333333n,
          label: 'typed PostgreSQL text',
          amount: '50000.5000',
          enabled: true,
        });
        expect((rows[0].event_date as Date).toISOString()).toBe(eventDate.toISOString());
        expect(rows[0].event_time).toEqual({ hours: 11, minutes: 12, seconds: 13 });
        expect((rows[0].event_timestamp as Date).getTime()).toBe(eventTimestamp.getTime());
        expect((rows[0].payload as Buffer).equals(payload)).toBe(true);
        expect(rows[0]).toMatchObject({
          small_value: 127,
          integer_value: 32767,
          real_value: 20.25,
          double_value: 2.2222,
          stream_text: 'stream π text',
        });
        expect((rows[0].stream_binary as Buffer).equals(streamPayload)).toBe(true);
        expect(rows[1]).toMatchObject({
          id: 33333333333334n,
          label: null,
          amount: null,
          enabled: null,
          event_date: null,
          event_time: null,
          event_timestamp: null,
          payload: null,
          small_value: null,
          integer_value: null,
          real_value: null,
          double_value: null,
          stream_text: null,
          stream_binary: null,
        });
      } finally {
        await prepared.close();
      }
    }, 30_000);

    it('supports repeated prepared bindings and executeUpdate', async () => {
      const prepared = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      try {
        prepared.setParameter(1, 11);
        prepared.setParameter(2, 'first');
        expect(await prepared.executeUpdate()).toBe(1);
        prepared.setParameter(1, 12);
        prepared.setParameter(2, 'second');
        expect(await prepared.executeUpdate()).toBe(1);
        const selected = await client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [11]);
        expect(selected.rows).toEqual([{ label: 'first' }]);
      } finally {
        await prepared.close();
      }

      const preparedQuery = client.prepareStatement(`SELECT id, label FROM ${table} WHERE id = ?`);
      try {
        const result = await preparedQuery.execute([11]);
        expect(result).toEqual({ kind: 'query', result: expect.objectContaining({
          rows: [{ id: 11n, label: 'first' }],
        }) });
      } finally {
        await preparedQuery.close();
      }
    }, 30_000);

    it('supports plain Statement execute query and update variants', async () => {
      const queryStatement = client.createStatement();
      try {
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [13, 'plain execute']);
        const query = await queryStatement.execute(`SELECT id, label FROM ${table} WHERE id = ?`, [13]);
        expect(query).toMatchObject({ kind: 'query', result: { rows: [{ id: 13n, label: 'plain execute' }] } });

        const update = await queryStatement.execute(`UPDATE ${table} SET label = ? WHERE id = ?`, ['updated', 13]);
        expect(update).toEqual({ kind: 'update', affectedRows: 1 });
        expect((await client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [13])).rows)
          .toEqual([{ label: 'updated' }]);
      } finally {
        await queryStatement.close();
      }
    }, 30_000);

    it('executes a prepared batch and clears it after execution', async () => {
      const preparedBatch = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      try {
        await preparedBatch.addBatch([21, 'prepared batch one']);
        await preparedBatch.addBatch([22, 'prepared batch two']);
        expect(await preparedBatch.executeBatch()).toEqual([1, 1]);
        const result = await client.executeQuery(
          `SELECT id, label FROM ${table} WHERE id BETWEEN ? AND ? ORDER BY id`,
          [21, 22],
        );
        expect(result.rows).toEqual([
          { id: 21n, label: 'prepared batch one' },
          { id: 22n, label: 'prepared batch two' },
        ]);
        await preparedBatch.clearBatch();
        expect(await preparedBatch.executeBatch()).toEqual([]);
      } finally {
        await preparedBatch.close();
      }
    }, 30_000);

    it('executes and closes a plain Statement batch', async () => {
      const plainBatch = client.createStatement();
      try {
        await plainBatch.addBatch(`INSERT INTO ${table} (id, label) VALUES (31, 'plain batch one')`);
        await plainBatch.addBatch(`INSERT INTO ${table} (id, label) VALUES (32, 'plain batch two')`);
        expect(await plainBatch.executeBatch()).toEqual([1, 1]);
        expect((await client.executeQuery(`SELECT count(*) AS total FROM ${table} WHERE id BETWEEN 31 AND 32`))
          .rows).toEqual([{ total: 2n }]);
      } finally {
        await plainBatch.close();
      }
    }, 30_000);

    it('returns rows from direct and prepared PostgreSQL RETURNING DML with result metadata', async () => {
      const statement = client.createStatement();
      const directExecution = await statement.execute(
        `INSERT INTO ${table} (id, label) VALUES (?, ?) RETURNING id, label`,
        [41, 'direct returning'],
      );
      expect(directExecution.kind).toBe('query');
      if (directExecution.kind !== 'query') {
        throw new Error('PostgreSQL INSERT ... RETURNING did not return a result set.');
      }
      const direct = directExecution.result;
      expect(direct.rows).toEqual([{ id: 41n, label: 'direct returning' }]);
      expect(direct.metadata).toEqual({
        columnCount: 2,
        columns: direct.columns.map((label, index) => ({ index: index + 1, label })),
      });

      const prepared = client.prepareStatement(
        `UPDATE ${table} SET label = ? WHERE id = ? RETURNING id, label`,
      );
      try {
        const returned = await prepared.executeQuery(['prepared returning', 41]);
        expect(returned.rows).toEqual([{ id: 41n, label: 'prepared returning' }]);
        expect(returned.columns).toEqual(['id', 'label']);
        expect((await client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [41])).rows)
          .toEqual([{ label: 'prepared returning' }]);
        const execution = await prepared.execute(['executed returning', 41]);
        expect(execution.kind).toBe('query');
        if (execution.kind !== 'query') {
          throw new Error('PostgreSQL UPDATE ... RETURNING did not return a result set.');
        }
        expect(execution.result.rows).toEqual([{ id: 41n, label: 'executed returning' }]);
      } finally {
        await prepared.close();
        await statement.close();
      }
    }, 30_000);

    it('retrieves generated keys from plain and prepared inserts', async () => {
      const keyTable = `${table}_keys`;
      await client.executeUpdate(
        `CREATE TABLE ${keyTable} (id BIGSERIAL PRIMARY KEY, label TEXT NOT NULL)`,
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
          expect(keys.columns).toContain('id');
          expect(keys.rows).toHaveLength(1);
          const plainId = keys.rows[0].id;
          expect(typeof plainId).toBe('bigint');
          expect((await client.executeQuery(`SELECT label FROM ${keyTable} WHERE id = ?`, [plainId])).rows)
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
          expect(keys.columns).toContain('id');
          expect(keys.rows).toHaveLength(1);
          const preparedId = keys.rows[0].id;
          expect(typeof preparedId).toBe('bigint');
          expect((await client.executeQuery(`SELECT label FROM ${keyTable} WHERE id = ?`, [preparedId])).rows)
            .toEqual([{ label: 'prepared key' }]);
        } finally {
          await prepared.close();
        }
      } finally {
        await client.executeUpdate(`DROP TABLE IF EXISTS ${keyTable}`);
      }
    }, 30_000);

    it('retrieves generated keys after a prepared batch insert', async () => {
      const keyTable = `${table}_batch_keys`;
      await client.executeUpdate(
        `CREATE TABLE ${keyTable} (id BIGSERIAL PRIMARY KEY, label TEXT NOT NULL)`,
      );
      const prepared = client.prepareStatement(
        `INSERT INTO ${keyTable} (label) VALUES (?)`,
        { generatedKeys: true },
      );
      try {
        await prepared.addBatch(['batch key one']);
        await prepared.addBatch(['batch key two']);
        await prepared.addBatch(['batch key three']);
        expect(await prepared.executeBatch()).toEqual([1, 1, 1]);

        const keys = await prepared.getGeneratedKeys();
        expect(keys.columns).toContain('id');
        expect(keys.rows.length).toBeGreaterThan(0);
        const ids = keys.rows.map((row) => row.id);
        expect(ids.every((id) => typeof id === 'bigint' && id > 0n)).toBe(true);
        const generatedRows = await client.executeQuery(`SELECT id, label FROM ${keyTable} ORDER BY id`);
        expect(generatedRows.rows).toHaveLength(3);
        expect(generatedRows.rows.map((row) => row.label)).toEqual([
          'batch key one', 'batch key two', 'batch key three',
        ]);
        const insertedIds = new Set(generatedRows.rows.map((row) => row.id));
        expect(ids.every((id) => insertedIds.has(id))).toBe(true);
      } finally {
        await prepared.close();
        await client.executeUpdate(`DROP TABLE IF EXISTS ${keyTable}`);
      }
    }, 30_000);

    it('returns a clean empty result and rejects invalid bindings and closed statements', async () => {
      const empty = await client.executeQuery(`SELECT id, label FROM ${table} WHERE id = ?`, [-1]);
      expect(empty.rows).toEqual([]);
      expect(empty.columns).toEqual(['id', 'label']);
      expect(empty.metadata.columnCount).toBe(2);

      const prepared = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      expect(() => prepared.setParameter(0, 'invalid')).toThrow(RangeError);
      prepared.setParameter(2, 'missing first parameter');
      await expect(prepared.executeUpdate()).rejects.toThrow(/parameter 1 has not been set/i);
      await prepared.close();
      await expect(prepared.executeUpdate()).rejects.toThrow('Prepared statement is closed.');
    }, 15_000);

    it('rejects protocol types that this Node API cannot represent', async () => {
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
