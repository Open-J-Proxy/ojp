import { randomBytes } from 'crypto';
import { OjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_INTEGRATION_TESTS === 'true';
const connections = readConnectionFixtures('postgresql_connections.csv', process.env.OJP_TEST_POSTGRES_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';
const totalRows = 10_001;

(enabled ? describe : describe.skip).each(connections)(
  'PostgreSQL L3 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_PASSWORD ?? fixturePassword };
    const table = `ojp_node_l3_${randomBytes(6).toString('hex')}`;
    let client: OjpClient;

    beforeAll(async () => {
      client = new OjpClient(url, options);
      await client.connect();
      await client.executeUpdate(`CREATE TABLE ${table} (id INTEGER PRIMARY KEY, label TEXT NOT NULL)`);
      await client.executeUpdate(
        `INSERT INTO ${table} (id, label) `
        + `SELECT id, 'l3-' || id FROM generate_series(0, ${totalRows - 1}) AS series(id)`,
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

    it('returns selected-column metadata for an empty result and reaches end of results', async () => {
      const result = await client.executeQuery(`SELECT id, label FROM ${table} WHERE id < 0`);

      expect(result.columns).toEqual(['id', 'label']);
      expect(result.rows).toEqual([]);
      expect(result.metadata).toEqual({
        columnCount: 2,
        columns: [
          { index: 1, label: 'id' },
          { index: 2, label: 'label' },
        ],
      });
      await expect(collectRows(client.executeQueryStream(`SELECT id, label FROM ${table} WHERE id < 0`)))
        .resolves.toEqual([]);
    }, 15_000);

    it('streams a single row exactly once and then reports end of results', async () => {
      const rows = client.executeQueryStream(`SELECT id, label FROM ${table} WHERE id = ?`, [617]);

      await expect(rows.next()).resolves.toEqual({ done: false, value: { id: 617, label: 'l3-617' } });
      await expect(rows.next()).resolves.toEqual({ done: true, value: undefined });
      await expect(rows.next()).resolves.toEqual({ done: true, value: undefined });
    }, 15_000);

    it('streams all ordered rows across server result blocks without loss or duplication', async () => {
      let count = 0;
      for await (const row of client.executeQueryStream(`SELECT id, label FROM ${table} ORDER BY id`)) {
        expect(row).toEqual({ id: count, label: `l3-${count}` });
        count++;
      }

      expect(count).toBe(totalRows);
    }, 60_000);

    it('materializes a multi-block result with complete column metadata and ordered rows', async () => {
      const result = await client.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`);

      expect(result.columns).toEqual(['id', 'label']);
      expect(result.metadata).toEqual({
        columnCount: 2,
        columns: [
          { index: 1, label: 'id' },
          { index: 2, label: 'label' },
        ],
      });
      expect(result.rows).toHaveLength(totalRows);
      result.rows.forEach((row, id) => {
        expect(row).toEqual({ id, label: `l3-${id}` });
      });
    }, 60_000);

    it('fetches every PostgreSQL RETURNING row through fetchNextRows without gaps or duplicates', async () => {
      const returnedIds = new Set<number>();
      for await (const row of client.executeQueryStream(
        `INSERT INTO ${table} (id, label) `
        + `SELECT id + ${totalRows}, 'returning-' || id FROM ${table} `
        + 'WHERE id >= 0 RETURNING id, label',
      )) {
        expect(typeof row.id).toBe('number');
        if (typeof row.id !== 'number') {
          throw new Error('PostgreSQL RETURNING returned a non-numeric id.');
        }
        expect(row.label).toBe(`returning-${row.id - totalRows}`);
        expect(returnedIds.has(row.id)).toBe(false);
        returnedIds.add(row.id);
      }

      expect(returnedIds.size).toBe(totalRows);
      expect([...returnedIds].sort((a, b) => a - b))
        .toEqual(Array.from({ length: totalRows }, (_, index) => index + totalRows));
      await expect(client.executeUpdate(`DELETE FROM ${table} WHERE id >= ${totalRows}`)).resolves.toBe(totalRows);
    }, 60_000);

    it('reads SQL LIMIT/OFFSET pages without gaps, duplicates, or reordering', async () => {
      const pageSize = 1_000;
      let totalRead = 0;

      for (let offset = 0; offset < totalRows; offset += pageSize) {
        const page = await client.executeQuery(
          `SELECT id, label FROM ${table} ORDER BY id LIMIT ? OFFSET ?`,
          [pageSize, offset],
        );
        const expectedSize = Math.min(pageSize, totalRows - offset);
        expect(page.rows).toHaveLength(expectedSize);
        page.rows.forEach((row, index) => {
          const id = offset + index;
          expect(row).toEqual({ id, label: `l3-${id}` });
        });
        totalRead += page.rows.length;
      }

      expect(totalRead).toBe(totalRows);
    }, 60_000);

    it('consumes independent result cursors concurrently', async () => {
      const secondClient = new OjpClient(url, options);
      await secondClient.connect();
      try {
        const [firstRows, secondRows] = await Promise.all([
          collectRows(client.executeQueryStream(`SELECT id, label FROM ${table} WHERE id < 20 ORDER BY id`)),
          collectRows(secondClient.executeQueryStream(
            `SELECT id, label FROM ${table} WHERE id >= ${totalRows - 20} ORDER BY id`,
          )),
        ]);

        expect(firstRows).toEqual(Array.from({ length: 20 }, (_, id) => ({ id, label: `l3-${id}` })));
        expect(secondRows).toEqual(
          Array.from({ length: 20 }, (_, index) => {
            const id = totalRows - 20 + index;
            return { id, label: `l3-${id}` };
          }),
        );
      } finally {
        await secondClient.close();
      }
    }, 30_000);

    it('closes a partially consumed cursor and leaves the session usable', async () => {
      let consumed = 0;
      for await (const row of client.executeQueryStream(`SELECT id, label FROM ${table} ORDER BY id`)) {
        expect(row).toEqual({ id: consumed, label: `l3-${consumed}` });
        consumed++;
        if (consumed === 10) {
          break;
        }
      }

      expect(consumed).toBe(10);
      await expect(client.executeQuery(`SELECT id, label FROM ${table} WHERE id = ?`, [totalRows - 1]))
        .resolves.toMatchObject({ rows: [{ id: totalRows - 1, label: `l3-${totalRows - 1}` }] });
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
