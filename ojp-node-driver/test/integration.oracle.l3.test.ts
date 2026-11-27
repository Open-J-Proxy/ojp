import { randomBytes } from 'crypto';
import { OjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';
const totalRows = 10_001;

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L3 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword };
    const table = `ojp_node_l3_${randomBytes(6).toString('hex')}`;
    let client: OjpClient;

    beforeAll(async () => {
      client = new OjpClient(url, options);
      await client.connect();
      await client.executeUpdate(`CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY, label VARCHAR2(40) NOT NULL)`);
      await client.executeUpdate(
        `INSERT INTO ${table} (id, label) SELECT LEVEL - 1, 'l3-' || (LEVEL - 1) `
        + `FROM dual CONNECT BY LEVEL <= ${totalRows}`,
      );
    }, 60_000);

    afterAll(async () => {
      if (client) {
        try {
          await client.executeUpdate(`DROP TABLE ${table}`);
        } finally {
          await client.close();
        }
      }
    }, 60_000);

    it('returns selected-column metadata for empty results and reports end-of-results repeatedly', async () => {
      const empty = await client.executeQuery(`SELECT id AS "ID", label AS "LABEL" FROM ${table} WHERE id < 0`);
      expect(empty.columns).toEqual(['ID', 'LABEL']);
      expect(empty.rows).toEqual([]);
      expect(empty.metadata).toEqual({
        columnCount: 2,
        columns: [{ index: 1, label: 'ID' }, { index: 2, label: 'LABEL' }],
      });

      const stream = client.executeQueryStream(`SELECT id AS "ID" FROM ${table} WHERE id < 0`);
      await expect(stream.next()).resolves.toEqual({ done: true, value: undefined });
      await expect(stream.next()).resolves.toEqual({ done: true, value: undefined });
    }, 30_000);

    it('streams a single row once and traverses all rows across fetch blocks in order', async () => {
      const single = client.executeQueryStream(
        `SELECT id AS "ID", label AS "LABEL" FROM ${table} WHERE id = ?`, [617],
      );
      await expect(single.next()).resolves.toEqual({ done: false, value: { ID: '617', LABEL: 'l3-617' } });
      await expect(single.next()).resolves.toEqual({ done: true, value: undefined });

      let count = 0;
      for await (const row of client.executeQueryStream(
        `SELECT id AS "ID", label AS "LABEL" FROM ${table} ORDER BY id`,
      )) {
        expect(row).toEqual({ ID: `${count}`, LABEL: `l3-${count}` });
        count++;
      }
      expect(count).toBe(totalRows);
    }, 90_000);

    it('materializes a multi-block result and safely closes a partially consumed cursor', async () => {
      const result = await client.executeQuery(
        `SELECT id AS "ID", label AS "LABEL" FROM ${table} ORDER BY id`,
      );
      expect(result.columns).toEqual(['ID', 'LABEL']);
      expect(result.metadata.columnCount).toBe(2);
      expect(result.rows).toHaveLength(totalRows);
      result.rows.forEach((row, id) => expect(row).toEqual({ ID: `${id}`, LABEL: `l3-${id}` }));

      let consumed = 0;
      for await (const row of client.executeQueryStream(
        `SELECT id AS "ID", label AS "LABEL" FROM ${table} ORDER BY id`,
      )) {
        expect(row).toEqual({ ID: `${consumed}`, LABEL: `l3-${consumed}` });
        if (++consumed === 10) {
          break;
        }
      }
      expect(consumed).toBe(10);
      await expect(client.executeQuery(
        `SELECT label AS "LABEL" FROM ${table} WHERE id = ?`, [totalRows - 1],
      )).resolves.toMatchObject({ rows: [{ LABEL: `l3-${totalRows - 1}` }] });
    }, 90_000);

    it('keeps independently opened cursors isolated when consumed in interleaved order', async () => {
      const first = client.executeQueryStream(
        `SELECT id AS "ID", label AS "LABEL" FROM ${table} WHERE id BETWEEN 10 AND 12 ORDER BY id`,
      );
      const second = client.executeQueryStream(
        `SELECT id AS "ID", label AS "LABEL" FROM ${table} WHERE id BETWEEN 20 AND 22 ORDER BY id`,
      );

      for (let offset = 0; offset < 3; offset++) {
        await expect(first.next()).resolves.toEqual({
          done: false,
          value: { ID: `${10 + offset}`, LABEL: `l3-${10 + offset}` },
        });
        await expect(second.next()).resolves.toEqual({
          done: false,
          value: { ID: `${20 + offset}`, LABEL: `l3-${20 + offset}` },
        });
      }
      await expect(first.next()).resolves.toEqual({ done: true, value: undefined });
      await expect(second.next()).resolves.toEqual({ done: true, value: undefined });
    }, 30_000);

    it.skip(
      'JDBC scrollable/updatable ResultSet navigation and wasNull are unavailable: Node exposes rows and forward-only async iterators',
      () => {},
    );
  },
);
