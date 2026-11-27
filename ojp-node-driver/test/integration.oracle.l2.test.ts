import { randomBytes } from 'crypto';
import { OjpClient, OjpTypedParam, ParameterType } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L2 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword };
    const table = `ojp_node_l2_${randomBytes(6).toString('hex')}`;
    let client: OjpClient;

    beforeAll(async () => {
      client = new OjpClient(url, options);
      await client.connect();
      await client.executeUpdate(`
        CREATE TABLE ${table} (
          id NUMBER(19) PRIMARY KEY,
          label VARCHAR2(200),
          amount NUMBER(18, 4),
          flag NUMBER(1),
          event_date DATE,
          event_timestamp TIMESTAMP(6),
          small_value NUMBER(5),
          integer_value NUMBER(10),
          float_value BINARY_FLOAT,
          double_value BINARY_DOUBLE,
          raw_value RAW(2000)
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

    it('round-trips typed Oracle numbers, dates, fractional pre-epoch timestamps, RAW, and typed NULLs', async () => {
      const insertedAt = new Date('1965-03-29T11:12:13.456Z');
      const insertedDate = new Date('2025-03-29T00:00:00.000Z');
      const raw = Buffer.from([0x00, 0x41, 0x7f, 0x80, 0xff]);
      const prepared = client.prepareStatement(
        `INSERT INTO ${table} (id, label, amount, flag, event_date, event_timestamp, small_value, `
        + 'integer_value, float_value, double_value, raw_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      );
      try {
        prepared.setParameter(1, 333333333333n);
        prepared.setParameter(2, new OjpTypedParam('Oracle typed text — 中文 🚀', ParameterType.N_STRING));
        prepared.setParameter(3, new OjpTypedParam('50000.5000', ParameterType.BIG_DECIMAL));
        prepared.setParameter(4, 1);
        prepared.setParameter(5, new OjpTypedParam(insertedDate, ParameterType.DATE));
        prepared.setParameter(6, new OjpTypedParam(insertedAt, ParameterType.TIMESTAMP));
        prepared.setParameter(7, new OjpTypedParam(32767, ParameterType.SHORT));
        prepared.setParameter(8, 2147483647);
        prepared.setParameter(9, new OjpTypedParam(20.25, ParameterType.FLOAT));
        prepared.setParameter(10, new OjpTypedParam(2.2222, ParameterType.DOUBLE));
        prepared.setParameter(11, raw);
        expect(await prepared.executeUpdate()).toBe(1);

        prepared.clearParameters();
        prepared.setParameter(1, 333333333334n);
        prepared.setNull(2, 12);
        prepared.setNull(3, 3);
        prepared.setNull(4, 16);
        prepared.setNull(5, 91);
        prepared.setNull(6, 93);
        prepared.setNull(7, 5);
        prepared.setNull(8, 4);
        prepared.setNull(9, 7);
        prepared.setNull(10, 8);
        prepared.setNull(11, -2);
        expect(await prepared.executeUpdate()).toBe(1);

        const result = await client.executeQuery(
          `SELECT id AS "ID", label AS "LABEL", amount AS "AMOUNT", flag AS "FLAG", `
          + `event_date AS "EVENT_DATE", event_timestamp AS "EVENT_TIMESTAMP", small_value AS "SMALL_VALUE", `
          + `integer_value AS "INTEGER_VALUE", float_value AS "FLOAT_VALUE", `
          + `double_value AS "DOUBLE_VALUE", raw_value AS "RAW_VALUE" FROM ${table} ORDER BY id`,
        );
        expect(result.columns).toEqual([
          'ID', 'LABEL', 'AMOUNT', 'FLAG', 'EVENT_DATE', 'EVENT_TIMESTAMP', 'SMALL_VALUE', 'INTEGER_VALUE',
          'FLOAT_VALUE', 'DOUBLE_VALUE', 'RAW_VALUE',
        ]);
        expect(result.metadata).toEqual({
          columnCount: 11,
          columns: result.columns.map((label, index) => ({ index: index + 1, label })),
        });
        expect(result.rows).toHaveLength(2);
        expect(result.rows[0]).toMatchObject({
          ID: '333333333333',
          LABEL: 'Oracle typed text — 中文 🚀',
          FLAG: '1',
          SMALL_VALUE: '32767',
          INTEGER_VALUE: '2147483647',
          FLOAT_VALUE: 20.25,
          DOUBLE_VALUE: 2.2222,
        });
        expect((result.rows[0].EVENT_DATE as Date).toISOString()).toBe(insertedDate.toISOString());
        expect((result.rows[0].EVENT_TIMESTAMP as Date).toISOString()).toBe(insertedAt.toISOString());
        expect(result.rows[0].RAW_VALUE).toEqual(raw);
        expect(normalizeDecimal(result.rows[0].AMOUNT)).toBe(normalizeDecimal('50000.5000'));
        expect(result.rows[1]).toMatchObject({
          ID: '333333333334',
          LABEL: null,
          AMOUNT: null,
          FLAG: null,
          EVENT_DATE: null,
          EVENT_TIMESTAMP: null,
          SMALL_VALUE: null,
          INTEGER_VALUE: null,
          FLOAT_VALUE: null,
          DOUBLE_VALUE: null,
          RAW_VALUE: null,
        });
      } finally {
        await prepared.close();
      }
    }, 60_000);

    it('round-trips Oracle NUMBER precision and DATE/RAW column boundaries', async () => {
      const boundaryTable = `${table}_bounds`;
      await client.executeUpdate(`
        CREATE TABLE ${boundaryTable} (
          id NUMBER(1) PRIMARY KEY,
          narrow_value NUMBER(5, 0),
          exact_value NUMBER(38, 18),
          date_value DATE,
          timestamp_value TIMESTAMP(6),
          raw_value RAW(2000)
        )
      `);
      const maxRaw = Buffer.alloc(2000);
      for (let index = 0; index < maxRaw.length; index++) {
        maxRaw[index] = index % 256;
      }

      try {
        const insert = client.prepareStatement(
          `INSERT INTO ${boundaryTable} (id, narrow_value, exact_value, date_value, timestamp_value, raw_value) `
          + 'VALUES (?, ?, ?, ?, ?, ?)',
        );
        try {
          await insert.executeUpdate([
            1,
            99999,
            new OjpTypedParam('99999999999999999999.999999999999999999', ParameterType.BIG_DECIMAL),
            new OjpTypedParam(new Date('2000-02-29T00:00:00.000Z'), ParameterType.DATE),
            new OjpTypedParam(new Date('2000-02-29T23:59:59.999Z'), ParameterType.TIMESTAMP),
            new OjpTypedParam(maxRaw, ParameterType.BINARY_STREAM),
          ]);
          await insert.executeUpdate([
            2,
            -99999,
            new OjpTypedParam('-99999999999999999999.999999999999999999', ParameterType.BIG_DECIMAL),
            new OjpTypedParam(new Date('9999-12-31T00:00:00.000Z'), ParameterType.DATE),
            new OjpTypedParam(new Date('1970-01-01T00:00:00.000Z'), ParameterType.TIMESTAMP),
            null,
          ]);
        } finally {
          await insert.close();
        }

        const result = await client.executeQuery(
          `SELECT id AS "ID", narrow_value AS "NARROW_VALUE", exact_value AS "EXACT_VALUE", `
          + `date_value AS "DATE_VALUE", timestamp_value AS "TIMESTAMP_VALUE", raw_value AS "RAW_VALUE" `
          + `FROM ${boundaryTable} ORDER BY id`,
        );
        expect(result.rows).toHaveLength(2);
        expect(result.rows[0]).toMatchObject({ ID: '1', NARROW_VALUE: '99999' });
        expect(normalizeDecimal(result.rows[0].EXACT_VALUE)).toBe('99999999999999999999.999999999999999999');
        expect((result.rows[0].DATE_VALUE as Date).toISOString()).toBe('2000-02-29T00:00:00.000Z');
        expect((result.rows[0].TIMESTAMP_VALUE as Date).toISOString()).toBe('2000-02-29T23:59:59.999Z');
        expect(result.rows[0].RAW_VALUE).toEqual(maxRaw);
        expect(result.rows[1]).toMatchObject({ ID: '2', NARROW_VALUE: '-99999', RAW_VALUE: null });
        expect(normalizeDecimal(result.rows[1].EXACT_VALUE)).toBe('-99999999999999999999.999999999999999999');
        expect((result.rows[1].DATE_VALUE as Date).toISOString()).toBe('9999-12-31T00:00:00.000Z');
        expect((result.rows[1].TIMESTAMP_VALUE as Date).toISOString()).toBe('1970-01-01T00:00:00.000Z');
      } finally {
        await client.executeUpdate(`DROP TABLE ${boundaryTable}`);
      }
    }, 60_000);

    it('round-trips Oracle native JSON text and a NULL JSON value', async () => {
      const jsonTable = `${table}_json`;
      await client.executeUpdate(
        `CREATE TABLE ${jsonTable} (id NUMBER(1) PRIMARY KEY, json_value JSON, null_value JSON)`,
      );
      const expected = {
        key: 'value',
        number: 42,
        nested: { enabled: true },
        items: [0, null, 'x'],
      };
      try {
        const insert = client.prepareStatement(
          `INSERT INTO ${jsonTable} (id, json_value, null_value) VALUES (?, ?, ?)`,
        );
        try {
          insert.setParameter(1, 1);
          insert.setParameter(2, JSON.stringify(expected));
          insert.setNull(3, 12);
          await insert.executeUpdate();
        } finally {
          await insert.close();
        }

        const result = await client.executeQuery(
          `SELECT json_value AS "JSON_VALUE", null_value AS "NULL_VALUE" FROM ${jsonTable} WHERE id = ?`,
          [1],
        );
        expect(result.rows).toHaveLength(1);
        expect(JSON.parse(result.rows[0].JSON_VALUE as string)).toEqual(expected);
        expect(result.rows[0].NULL_VALUE).toBeNull();
      } finally {
        await client.executeUpdate(`DROP TABLE ${jsonTable}`);
      }
    }, 60_000);

    it('supports plain and prepared statements, repeated binding, and batch execution', async () => {
      const statement = client.createStatement();
      try {
        expect(await statement.executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'plain statement'],
        )).toBe(1);
        await statement.addBatch(`INSERT INTO ${table} (id, label) VALUES (2, 'plain batch one')`);
        await statement.addBatch(`INSERT INTO ${table} (id, label) VALUES (3, 'plain batch two')`);
        expect(await statement.executeBatch()).toEqual([1, 1]);
      } finally {
        await statement.close();
      }

      const insert = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      try {
        insert.setParameter(1, 4);
        insert.setParameter(2, 'first binding');
        expect(await insert.executeUpdate()).toBe(1);
        insert.setParameter(1, 5);
        insert.setParameter(2, 'second binding');
        expect(await insert.executeUpdate()).toBe(1);
        insert.clearParameters();
        insert.setParameter(1, 8);
        insert.setNull(2, 12);
        expect(await insert.executeUpdate()).toBe(1);
        await insert.addBatch([6, 'prepared batch one']);
        await insert.addBatch([7, 'prepared batch two']);
        expect(await insert.executeBatch()).toEqual([1, 1]);
        expect((await client.executeQuery(
          `SELECT id AS "ID", label AS "LABEL" FROM ${table} ORDER BY id`,
        )).rows).toEqual([
          { ID: '1', LABEL: 'plain statement' },
          { ID: '2', LABEL: 'plain batch one' },
          { ID: '3', LABEL: 'plain batch two' },
          { ID: '4', LABEL: 'first binding' },
          { ID: '5', LABEL: 'second binding' },
          { ID: '6', LABEL: 'prepared batch one' },
          { ID: '7', LABEL: 'prepared batch two' },
          { ID: '8', LABEL: null },
        ]);
      } finally {
        await insert.close();
      }

      const preparedQuery = client.prepareStatement(
        `SELECT id AS "ID", label AS "LABEL" FROM ${table} WHERE id = ?`,
      );
      try {
        await expect(preparedQuery.execute([1])).resolves.toMatchObject({
          kind: 'query',
          result: { rows: [{ ID: '1', LABEL: 'plain statement' }] },
        });
      } finally {
        await preparedQuery.close();
      }
      await expect(preparedQuery.execute([1])).rejects.toThrow(/closed/i);

      const mixed = client.createStatement();
      try {
        await expect(mixed.execute(`SELECT id AS "ID" FROM ${table} WHERE id = ?`, [1]))
          .resolves.toMatchObject({ kind: 'query', result: { rows: [{ ID: '1' }] } });
        await expect(mixed.execute(`UPDATE ${table} SET label = ? WHERE id = ?`, ['executed', 1]))
          .resolves.toEqual({ kind: 'update', affectedRows: 1 });
        await expect(client.executeQuery(
          `SELECT label AS "LABEL" FROM ${table} WHERE id = ?`, [1],
        )).resolves.toMatchObject({ rows: [{ LABEL: 'executed' }] });
      } finally {
        await mixed.close();
      }
      await expect(mixed.execute(`SELECT id FROM ${table}`)).rejects.toThrow(/closed/i);
    }, 60_000);

    it('rolls back a prepared Oracle batch inside an explicit transaction', async () => {
      const insert = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      await client.startTransaction();
      try {
        await insert.addBatch([91, 'batch row one']);
        await insert.addBatch([92, 'batch row two']);
        expect(await insert.executeBatch()).toEqual([1, 1]);
        await client.rollback();
      } finally {
        try {
          if (!client.getAutoCommit()) {
            await client.rollback();
          }
        } finally {
          await insert.close();
        }
      }

      await expect(client.executeQuery(
        `SELECT id AS "ID", label AS "LABEL" FROM ${table} WHERE id IN (91, 92) ORDER BY id`,
      )).resolves.toMatchObject({ rows: [] });
    }, 60_000);

    it('returns the requested Oracle identity key for plain and prepared inserts', async () => {
      const keyTable = `${table}_keys`;
      await client.executeUpdate(
        `CREATE TABLE ${keyTable} (id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, label VARCHAR2(80))`,
      );
      try {
        const plain = client.createStatement();
        try {
          expect(await plain.executeUpdate(
            `INSERT INTO ${keyTable} (label) VALUES (?)`,
            ['plain generated key'],
            { generatedKeys: ['ID'] },
          )).toBe(1);
          const keys = await plain.getGeneratedKeys();
          expect(keys.columns.map((column) => column.toUpperCase())).toContain('ID');
          expect(keys.rows).toHaveLength(1);
          const id = Object.entries(keys.rows[0]).find(([column]) => column.toUpperCase() === 'ID')?.[1];
          expect(id).toBeDefined();
          expect(String(id)).toMatch(/^\d+$/);
          await expect(client.executeQuery(
            `SELECT id AS "ID", label AS "LABEL" FROM ${keyTable} WHERE id = ?`, [id],
          )).resolves.toMatchObject({ rows: [{ ID: String(id), LABEL: 'plain generated key' }] });
        } finally {
          await plain.close();
        }

        const prepared = client.prepareStatement(
          `INSERT INTO ${keyTable} (label) VALUES (?)`,
          { generatedKeys: ['ID'] },
        );
        try {
          expect(await prepared.executeUpdate(['prepared generated key'])).toBe(1);
          const keys = await prepared.getGeneratedKeys();
          expect(keys.columns.map((column) => column.toUpperCase())).toContain('ID');
          expect(keys.rows).toHaveLength(1);
          const id = Object.entries(keys.rows[0]).find(([column]) => column.toUpperCase() === 'ID')?.[1];
          expect(id).toBeDefined();
          expect(String(id)).toMatch(/^\d+$/);
          await expect(client.executeQuery(
            `SELECT id AS "ID", label AS "LABEL" FROM ${keyTable} WHERE id = ?`, [id],
          )).resolves.toMatchObject({ rows: [{ ID: String(id), LABEL: 'prepared generated key' }] });
        } finally {
          await prepared.close();
        }
      } finally {
        await client.executeUpdate(`DROP TABLE ${keyTable}`);
      }
    }, 60_000);

    it.skip(
      'Oracle DML RETURNING INTO needs callable-statement output binds; Node exposes no output-parameter API',
      () => {},
    );

    it.skip(
      'Oracle ARRAY binding is unsupported: valueMapper rejects ParameterType.ARRAY and Node cannot create Oracle collections',
      () => {},
    );

    it.skip(
      'Oracle REF values need vendor-specific references, which the Node public API cannot create',
      () => {},
    );

    it.skip(
      'Oracle 21c has no SQL BOOLEAN column type; its NUMBER(1) convention cannot verify BOOLEAN bind round-trips',
      () => {},
    );

    it.skip(
      'JDBC-only Statement controls (max rows, fetch options, timeout, cancel, warnings, cursor name, getMoreResults, holdability, and pooling) are absent from the Node Statement API',
      () => {},
    );
  },
);

function normalizeDecimal(value: unknown): string {
  if (typeof value !== 'string' || !/^[+-]?\d+(?:\.\d+)?$/.test(value)) {
    throw new Error(`Expected an exact decimal string, got ${String(value)}`);
  }
  const negative = value.startsWith('-');
  const unsigned = value.replace(/^[+-]/, '');
  const [whole, fraction = ''] = unsigned.split('.');
  const normalizedWhole = whole.replace(/^0+(?=\d)/, '');
  const normalizedFraction = fraction.replace(/0+$/, '');
  const normalized = normalizedFraction ? `${normalizedWhole}.${normalizedFraction}` : normalizedWhole;
  return negative && normalized !== '0' ? `-${normalized}` : normalized;
}
