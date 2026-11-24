import { randomBytes } from 'crypto';
import { OjpClient, OjpSqlError, OjpTypedParam, ParameterType } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

interface DatabaseL2Options {
  database: 'MariaDB' | 'MySQL';
  enabledEnvironmentVariable: string;
  fixture: string;
  fixtureEnvironmentVariable: string;
  passwordEnvironmentVariable: string;
}

export function registerDatabaseL2Suite(options: DatabaseL2Options): void {
  const enabled = process.env[options.enabledEnvironmentVariable] === 'true';
  const connections = readConnectionFixtures(
    options.fixture,
    process.env[options.fixtureEnvironmentVariable],
  );
  const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

  (enabled ? describe : describe.skip).each(connections)(
    `${options.database} L2 for %s`,
    (jdbcUrl: string, user: string, fixturePassword: string) => {
      const url = ojpUrl(endpoint, jdbcUrl);
      const credentials = {
        user,
        password: process.env[options.passwordEnvironmentVariable] ?? fixturePassword,
      };
      const table = `ojp_node_l2_${randomBytes(6).toString('hex')}`;
      let client: OjpClient;

      beforeAll(async () => {
        const setupClient = new OjpClient(url, credentials);
        await setupClient.connect();
        try {
          await setupClient.executeUpdate(`
            CREATE TABLE ${table} (
              id BIGINT PRIMARY KEY,
              label VARCHAR(100),
              amount DECIMAL(18, 4),
              enabled BOOLEAN,
              event_date DATE,
              event_time TIME,
              event_datetime DATETIME(3),
              payload LONGBLOB,
              tiny_value TINYINT,
              small_value SMALLINT,
              int_value INT,
              float_value FLOAT,
              double_value DOUBLE,
              stream_text TEXT,
              stream_binary LONGBLOB
            )
          `);
        } finally {
          await setupClient.close();
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
        const cleanupClient = new OjpClient(url, credentials);
        await cleanupClient.connect();
        try {
          await cleanupClient.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
        } finally {
          await cleanupClient.close();
        }
      }, 60_000);

      it('round-trips MySQL/MariaDB numeric, text, binary, date/time, and typed NULL parameters', async () => {
        const prepared = client.prepareStatement(
          `INSERT INTO ${table} (id, label, amount, enabled, event_date, event_time, event_datetime, payload, `
          + 'tiny_value, small_value, int_value, float_value, double_value, stream_text, stream_binary) '
          + 'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        );
        const eventDate = new Date('2025-03-29T00:00:00.000Z');
        const eventTime = new Date('1970-01-01T11:12:13.000Z');
        const eventDateTime = new Date('2025-03-30T21:22:23.456Z');
        const payload = Buffer.from([0, 0x41, 0xff, 0x00]);
        const streamPayload = Buffer.from('binary stream');

        try {
          prepared.setParameter(1, 33333333333333n);
          prepared.setParameter(2, new OjpTypedParam('typed MySQL text', ParameterType.N_STRING));
          prepared.setParameter(3, new OjpTypedParam('50000.5000', ParameterType.BIG_DECIMAL));
          prepared.setParameter(4, true);
          prepared.setParameter(5, new OjpTypedParam(eventDate, ParameterType.DATE));
          prepared.setParameter(6, new OjpTypedParam(eventTime, ParameterType.TIME));
          prepared.setParameter(7, new OjpTypedParam(eventDateTime, ParameterType.TIMESTAMP));
          prepared.setParameter(8, payload);
          prepared.setParameter(9, new OjpTypedParam(127, ParameterType.BYTE));
          prepared.setParameter(10, new OjpTypedParam(32767, ParameterType.SHORT));
          prepared.setParameter(11, 2147483647);
          prepared.setParameter(12, new OjpTypedParam(20.25, ParameterType.FLOAT));
          prepared.setParameter(13, new OjpTypedParam(2.2222, ParameterType.DOUBLE));
          prepared.setParameter(14, new OjpTypedParam('stream π text', ParameterType.UNICODE_STREAM));
          prepared.setParameter(15, new OjpTypedParam(streamPayload, ParameterType.BINARY_STREAM));
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
          prepared.setNull(9, -6);
          prepared.setNull(10, 5);
          prepared.setNull(11, 4);
          prepared.setNull(12, 6);
          prepared.setNull(13, 8);
          prepared.setNull(14, 12);
          prepared.setNull(15, -2);
          expect(await prepared.executeUpdate()).toBe(1);
        } finally {
          await prepared.close();
        }

        const result = await client.executeQuery(
          `SELECT id, label, amount, CASE WHEN enabled IS NULL THEN NULL WHEN enabled THEN 'true' ELSE 'false' END AS enabled, event_date, event_time, event_datetime, payload, `
          + 'tiny_value, small_value, int_value, float_value, double_value, stream_text, stream_binary '
          + `FROM ${table} ORDER BY id`,
        );
        expect(result.columns).toEqual([
          'id', 'label', 'amount', 'enabled', 'event_date', 'event_time', 'event_datetime', 'payload',
          'tiny_value', 'small_value', 'int_value', 'float_value', 'double_value', 'stream_text', 'stream_binary',
        ]);
        expect(result.metadata).toEqual({
          columnCount: 15,
          columns: result.columns.map((label, index) => ({ index: index + 1, label })),
        });
        expect(result.rows).toHaveLength(2);
        expect(result.rows[0]).toMatchObject({
          id: 33333333333333n,
          label: 'typed MySQL text',
          amount: '50000.5000',
          enabled: 'true',
          event_time: { hours: 11, minutes: 12, seconds: 13 },
          tiny_value: 127,
          small_value: 32767,
          int_value: 2147483647,
          float_value: 20.25,
          double_value: 2.2222,
          stream_text: 'stream π text',
        });
        expect((result.rows[0].event_date as Date).toISOString()).toBe(eventDate.toISOString());
        expect((result.rows[0].event_datetime as Date).toISOString()).toBe(eventDateTime.toISOString());
        expect((result.rows[0].payload as Buffer).equals(payload)).toBe(true);
        expect((result.rows[0].stream_binary as Buffer).equals(streamPayload)).toBe(true);
        expect(result.rows[1]).toMatchObject({
          id: 33333333333334n,
          label: null,
          amount: null,
          enabled: null,
          event_date: null,
          event_time: null,
          event_datetime: null,
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

      it('binds URL parameters through the database and returns the original URL value', async () => {
        const value = 'https://example.test/items/42?source=ojp';
        const result = await client.executeQuery(
          'SELECT ? AS url_value',
          [new OjpTypedParam(new URL(value), ParameterType.URL)],
        );
        expect(result.columns).toEqual(['url_value']);
        expect(result.rows).toEqual([{ url_value: value }]);
      }, 15_000);

      it('round-trips scalar OBJECT and opaque ROW_ID parameters', async () => {
        const rowIdBytes = Buffer.from([0, 0x41, 0xff, 0x00]);
        const result = await client.executeQuery(
          'SELECT ? AS object_value, CAST(? AS BINARY(4)) AS row_id_value',
          [
            new OjpTypedParam(42, ParameterType.OBJECT),
            new OjpTypedParam(rowIdBytes, ParameterType.ROW_ID),
          ],
        );
        expect(result.rows).toEqual([{
          object_value: options.database === 'MySQL' ? 42n : 42,
          row_id_value: rowIdBytes,
        }]);
      }, 15_000);

      it('round-trips ASCII, character-reader, and national-character stream parameters', async () => {
        const insert = client.prepareStatement(
          `INSERT INTO ${table} (id, stream_text) VALUES (?, ?)`,
        );
        const values: Array<[number, string, ParameterType]> = [
          [61, 'ascii stream text', ParameterType.ASCII_STREAM],
          [62, 'character reader text', ParameterType.CHARACTER_READER],
          [63, 'national stream π text', ParameterType.N_CHARACTER_STREAM],
        ];
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
          `SELECT id, stream_text FROM ${table} WHERE id BETWEEN 61 AND 63 ORDER BY id`,
        )).rows).toEqual(values.map(([id, text]) => ({ id: BigInt(id), stream_text: text })));
      }, 30_000);

      it('rebinds prepared parameters and supports prepared query and update execution', async () => {
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

      it('enforces 1-based complete bindings and rejects execution after prepared close', async () => {
        const prepared = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
        expect(() => prepared.setParameter(0, 'invalid')).toThrow(RangeError);
        prepared.setParameter(2, 'missing first parameter');
        await expect(prepared.executeUpdate()).rejects.toThrow(/parameter 1 has not been set/i);
        await prepared.close();
        await expect(prepared.executeUpdate()).rejects.toThrow('Prepared statement is closed.');
      }, 15_000);

      it('supports plain Statement query and update execution with parameters', async () => {
        const statement = client.createStatement();
        try {
          expect(await statement.execute(
            `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
            [13, 'plain execute'],
          )).toEqual({ kind: 'update', affectedRows: 1 });
          expect(await statement.execute(
            `SELECT id, label FROM ${table} WHERE id = ?`,
            [13],
          )).toMatchObject({ kind: 'query', result: { rows: [{ id: 13n, label: 'plain execute' }] } });

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

      it('executes a prepared batch and clears it after execution', async () => {
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

      it('executes a plain Statement batch and verifies every inserted row', async () => {
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
          `CREATE TABLE ${keyTable} (id BIGINT AUTO_INCREMENT PRIMARY KEY, label VARCHAR(100) NOT NULL)`,
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

      it('returns generated keys for every row in a prepared batch', async () => {
        const keyTable = `${table}_batch_keys`;
        await client.executeUpdate(
          `CREATE TABLE ${keyTable} (id BIGINT AUTO_INCREMENT PRIMARY KEY, label VARCHAR(100) NOT NULL)`,
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

      it('returns column-label metadata and a well-formed empty result', async () => {
        const result = await client.executeQuery(
          `SELECT id, label FROM ${table} WHERE id = ?`,
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

      if (options.database === 'MariaDB') {
        it('returns rows and metadata for direct and prepared MariaDB INSERT RETURNING', async () => {
          const statement = client.createStatement();
          try {
            const direct = await statement.execute(
              `INSERT INTO ${table} (id, label) VALUES (?, ?) RETURNING id, label`,
              [41, 'direct returning'],
            );
            expect(direct.kind).toBe('query');
            if (direct.kind !== 'query') {
              throw new Error('MariaDB INSERT ... RETURNING did not return a result set.');
            }
            expect(direct.result.rows).toEqual([{ id: 41n, label: 'direct returning' }]);
            expect(direct.result.metadata).toEqual({
              columnCount: 2,
              columns: [
                { index: 1, label: 'id' },
                { index: 2, label: 'label' },
              ],
            });

            const prepared = client.prepareStatement(
              `INSERT INTO ${table} (id, label) VALUES (?, ?) RETURNING id, label`,
            );
            try {
              const returned = await prepared.executeQuery([42, 'prepared returning']);
              expect(returned.rows).toEqual([{ id: 42n, label: 'prepared returning' }]);
              expect(returned.columns).toEqual(['id', 'label']);
              expect(await prepared.execute([43, 'prepared execute returning'])).toMatchObject({
                kind: 'query',
                result: { rows: [{ id: 43n, label: 'prepared execute returning' }] },
              });
            } finally {
              await prepared.close();
            }
          } finally {
            await statement.close();
          }
        }, 30_000);
      } else {
        it('surfaces MySQL syntax errors for unsupported RETURNING DML without inserting rows', async () => {
          const statement = client.createStatement();
          try {
            await expect(statement.execute(
              `INSERT INTO ${table} (id, label) VALUES (?, ?) RETURNING id, label`,
              [51, 'unsupported direct returning'],
            )).rejects.toBeInstanceOf(OjpSqlError);
            const prepared = client.prepareStatement(
              `INSERT INTO ${table} (id, label) VALUES (?, ?) RETURNING id, label`,
            );
            try {
              await expect(prepared.executeQuery([52, 'unsupported prepared returning']))
                .rejects.toBeInstanceOf(OjpSqlError);
            } finally {
              await prepared.close();
            }
            expect((await client.executeQuery(
              `SELECT id FROM ${table} WHERE id IN (?, ?) ORDER BY id`,
              [51, 52],
            )).rows).toEqual([]);
          } finally {
            await statement.close();
          }
        }, 30_000);
      }

      it('rejects protocol parameter types not representable by the public Node API', async () => {
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
}
