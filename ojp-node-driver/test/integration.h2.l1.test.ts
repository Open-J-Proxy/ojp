import { randomBytes } from 'crypto';
import { readFileSync } from 'fs';
import { join } from 'path';
import { parse } from 'csv-parse/sync';
import { OjpClient, OjpConnectionError, OjpSqlError } from '../src';

const enabled = process.env.OJP_TEST_H2 === 'true';
const endpoint = process.env.OJP_TEST_H2_ADDR;
const records: string[][] = parse(readFileSync(join(__dirname, '../testdata/h2_connections.csv')), {
  skip_empty_lines: true,
});
if (records.length === 0 || records.some((row) => row.length !== 3 || !row[0].startsWith('jdbc:'))) {
  throw new Error('Expected one or more CSV rows with JDBC URL, username, and password');
}
if (enabled && !endpoint) {
  throw new Error('OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true');
}

(enabled ? describe : describe.skip).each(records)(
  'H2 L1 for %s',
  (jdbcUrl: string, user: string, password: string) => {
    const url = `jdbc:ojp[${endpoint}]_${jdbcUrl.slice('jdbc:'.length)}`;

    it('supports CRUD, SQL errors, and connection lifecycle', async () => {
      const client = new OjpClient(url, { user, password });
      const table = `ojp_node_l1_${randomBytes(6).toString('hex')}`;
      await client.connect();
      try {
        expect((await client.executeQuery('SELECT 1')).rows).toHaveLength(1);
        await client.executeUpdate(`CREATE TABLE ${table} (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)`);
        try {
          expect(await client.executeUpdate(`INSERT INTO ${table} (id, name) VALUES (?, ?)`, [1, 'before'])).toBe(1);
          expect((await client.executeQuery(`SELECT id, name FROM ${table} WHERE id = ?`, [1])).rows)
            .toEqual([{ ID: 1, NAME: 'before' }]);
          expect(await client.executeUpdate(`UPDATE ${table} SET name = ? WHERE id = ?`, ['after', 1])).toBe(1);
          expect((await client.executeQuery(`SELECT name FROM ${table} WHERE id = ?`, [1])).rows)
            .toEqual([{ NAME: 'after' }]);
          await expect(client.executeUpdate(`INSERT INTO ${table} (id, name) VALUES (?, ?)`, [1, 'duplicate']))
            .rejects.toMatchObject({ name: 'OjpSqlError', sqlState: '23505' });
          await expect(client.executeQuery('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
          expect(await client.executeUpdate(`DELETE FROM ${table} WHERE id = ?`, [1])).toBe(1);
          const empty = await client.executeQuery(`SELECT id, name FROM ${table} WHERE id = ?`, [1]);
          expect(empty.rows).toEqual([]);
          expect(empty.columns).toEqual(['ID', 'NAME']);
        } finally {
          await client.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
        }
      } finally {
        await client.close();
      }
      await client.close();
      await expect(client.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
    }, 60_000);

    it('forwards a named datasource to the server', async () => {
      const defaultClient = new OjpClient(url, { user, password });
      const namedClient = new OjpClient(
        `jdbc:ojp[${endpoint}(nodeTest)]_${jdbcUrl.slice('jdbc:'.length)}`,
        { user, password },
      );
      try {
        const defaultSession = await defaultClient.connect();
        const namedSession = await namedClient.connect();
        expect(namedSession.connHash).not.toBe(defaultSession.connHash);
        expect((await namedClient.executeQuery('SELECT 1')).rows).toHaveLength(1);
      } finally {
        await defaultClient.close();
        await namedClient.close();
      }
    }, 60_000);
  },
);
