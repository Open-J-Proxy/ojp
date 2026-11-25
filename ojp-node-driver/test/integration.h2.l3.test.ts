import { randomBytes } from 'crypto';
import { OjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_TEST_H2 === 'true';
const endpoint = process.env.OJP_TEST_H2_ADDR ?? 'localhost:1059';
const connections = readConnectionFixtures('h2_connections.csv', process.env.OJP_TEST_H2_CSV);
const totalRows = 10_001;

if (enabled && !process.env.OJP_TEST_H2_ADDR) {
  throw new Error('OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true');
}

(enabled ? describe : describe.skip).each(connections)(
  'H2 L3 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const credentials = {
      user,
      password: process.env.OJP_TEST_H2_PASSWORD ?? fixturePassword,
    };
    const table = `ojp_node_h2_l3_${randomBytes(6).toString('hex')}`;
    let client: OjpClient;

    beforeAll(async () => {
      client = new OjpClient(url, credentials);
      await client.connect();
      await client.executeUpdate(
        `CREATE TABLE ${table} (id INTEGER PRIMARY KEY, label VARCHAR(100) NOT NULL)`,
      );
      await client.executeUpdate(
        `INSERT INTO ${table} (id, label) `
        + `SELECT X, 'l3-' || X FROM SYSTEM_RANGE(0, ${totalRows - 1})`,
      );
    }, 60_000);

    afterAll(async () => {
      if (client) {
        try {
          await client.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
        } finally {
          await client.close();
        }
      }
    }, 60_000);

    it('returns metadata for an empty result and reaches end of results', async () => {
      const result = await client.executeQuery(`SELECT id, label FROM ${table} WHERE id < 0`);

      expect(result.columns).toEqual(['ID', 'LABEL']);
      expect(result.rows).toEqual([]);
      expect(result.metadata).toEqual({
        columnCount: 2,
        columns: [
          { index: 1, label: 'ID' },
          { index: 2, label: 'LABEL' },
        ],
      });
      await expect(collectRows(client.executeQueryStream(`SELECT id, label FROM ${table} WHERE id < 0`)))
        .resolves.toEqual([]);
    }, 15_000);

    it('streams a single row exactly once and then reports end of results', async () => {
      const rows = client.executeQueryStream(`SELECT id, label FROM ${table} WHERE id = ?`, [617]);

      await expect(rows.next()).resolves.toEqual({ done: false, value: { ID: 617, LABEL: 'l3-617' } });
      await expect(rows.next()).resolves.toEqual({ done: true, value: undefined });
      await expect(rows.next()).resolves.toEqual({ done: true, value: undefined });
    }, 15_000);

    it('streams all ordered rows across server result blocks without loss or duplication', async () => {
      let count = 0;
      for await (const row of client.executeQueryStream(`SELECT id, label FROM ${table} ORDER BY id`)) {
        expect(row).toEqual({ ID: count, LABEL: `l3-${count}` });
        count++;
      }

      expect(count).toBe(totalRows);
    }, 60_000);

    it('materializes a multi-block result with complete metadata and ordered rows', async () => {
      const result = await client.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`);

      expect(result.columns).toEqual(['ID', 'LABEL']);
      expect(result.metadata).toEqual({
        columnCount: 2,
        columns: [
          { index: 1, label: 'ID' },
          { index: 2, label: 'LABEL' },
        ],
      });
      expect(result.rows).toHaveLength(totalRows);
      result.rows.forEach((row, id) => {
        expect(row).toEqual({ ID: id, LABEL: `l3-${id}` });
      });
    }, 60_000);

    it('fetches every H2 FINAL TABLE row across fetchNextRows pages', async () => {
      const returnedIds = new Set<number>();
      for await (const row of client.executeQueryStream(
        `SELECT id, label FROM FINAL TABLE (INSERT INTO ${table} (id, label) `
        + `SELECT X, 'returning-' || X FROM SYSTEM_RANGE(${totalRows}, ${totalRows * 2 - 1}))`,
      )) {
        expect(typeof row.ID).toBe('number');
        if (typeof row.ID !== 'number') {
          throw new Error('H2 FINAL TABLE returned a non-numeric id.');
        }
        expect(row.LABEL).toBe(`returning-${row.ID}`);
        expect(returnedIds.has(row.ID)).toBe(false);
        returnedIds.add(row.ID);
      }

      expect(returnedIds.size).toBe(totalRows);
      expect([...returnedIds].sort((a, b) => a - b))
        .toEqual(Array.from({ length: totalRows }, (_, index) => index + totalRows));
      await expect(client.executeUpdate(`DELETE FROM ${table} WHERE id >= ${totalRows}`)).resolves.toBe(totalRows);
    }, 60_000);

    it('consumes independent result cursors concurrently', async () => {
      const [firstRows, secondRows] = await Promise.all([
        collectRows(client.executeQueryStream(`SELECT id, label FROM ${table} WHERE id < 20 ORDER BY id`)),
        collectRows(
          client.executeQueryStream(`SELECT id, label FROM ${table} WHERE id >= ${totalRows - 20} ORDER BY id`),
        ),
      ]);

      expect(firstRows).toEqual(Array.from({ length: 20 }, (_, id) => ({ ID: id, LABEL: `l3-${id}` })));
      expect(secondRows).toEqual(
        Array.from({ length: 20 }, (_, index) => {
          const id = totalRows - 20 + index;
          return { ID: id, LABEL: `l3-${id}` };
        }),
      );
    }, 30_000);

    it('closes a partially consumed cursor and leaves the session usable', async () => {
      let consumed = 0;
      for await (const row of client.executeQueryStream(`SELECT id, label FROM ${table} ORDER BY id`)) {
        expect(row).toEqual({ ID: consumed, LABEL: `l3-${consumed}` });
        consumed++;
        if (consumed === 10) {
          break;
        }
      }

      expect(consumed).toBe(10);
      await expect(client.executeQuery(`SELECT id, label FROM ${table} WHERE id = ?`, [totalRows - 1]))
        .resolves.toMatchObject({ rows: [{ ID: totalRows - 1, LABEL: `l3-${totalRows - 1}` }] });
    }, 15_000);
  },
);

async function collectRows(rows: AsyncIterable<Record<string, unknown>>): Promise<Record<string, unknown>[]> {
  const result: Record<string, unknown>[] = [];
  for await (const row of rows) {
    result.push(row);
  }
  return result;
}
