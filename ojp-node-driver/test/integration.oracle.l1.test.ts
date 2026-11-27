import { randomBytes } from 'crypto';
import { OjpClient, OjpConnectionError, OjpSqlError, withOjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L1 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword };

    it('supports parameterized CRUD, empty results, and Oracle SQL errors', async () => {
      const client = new OjpClient(url, options);
      const table = `ojp_node_l1_${randomBytes(6).toString('hex')}`;
      await client.connect();
      try {
        await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
        expect((await client.executeQuery('SELECT dummy AS "DUMMY" FROM dual')).rows)
          .toEqual([{ DUMMY: 'X' }]);

        await client.executeUpdate(`CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY, label VARCHAR2(100) NOT NULL)`);
        try {
          expect(await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before']))
            .toBe(1);
          expect((await client.executeQuery(
            `SELECT id AS "ID", label AS "LABEL" FROM ${table} WHERE id = ?`, [1],
          )).rows).toEqual([{ ID: '1', LABEL: 'before' }]);

          expect(await client.executeUpdate(`UPDATE ${table} SET label = ? WHERE id = ?`, ['after', 1])).toBe(1);
          expect((await client.executeQuery(
            `SELECT label AS "LABEL" FROM ${table} WHERE id = ?`, [1],
          )).rows).toEqual([{ LABEL: 'after' }]);

          await expect(client.executeUpdate(
            `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'duplicate'],
          )).rejects.toMatchObject({
            name: 'OjpSqlError',
            sqlState: expect.stringMatching(/^[0-9A-Z]{5}$/),
            vendorCode: expect.any(Number),
          });
          await expect(client.executeQuery('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);

          expect(await client.executeUpdate(`DELETE FROM ${table} WHERE id = ?`, [1])).toBe(1);
          const empty = await client.executeQuery(
            `SELECT id AS "ID", label AS "LABEL" FROM ${table} WHERE id = ?`, [1],
          );
          expect(empty.rows).toEqual([]);
          expect(empty.columns).toEqual(['ID', 'LABEL']);
        } finally {
          await client.executeUpdate(`DROP TABLE ${table}`);
        }
      } finally {
        await client.close();
      }
    }, 60_000);

    it('terminates the Oracle session on close and does not pool scoped clients', async () => {
      const client = new OjpClient(url, options);
      expect((await client.connect()).sessionStatus).toBe('SESSION_ACTIVE');
      expect((await client.executeQuery('SELECT dummy AS "DUMMY" FROM dual')).rows).toEqual([{ DUMMY: 'X' }]);

      await client.close();
      await expect(client.executeQuery('SELECT 1 FROM dual')).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(client.close()).resolves.toBeUndefined();

      const scoped: OjpClient[] = [];
      await expect(withOjpClient(url, async (current) => {
        scoped.push(current);
        return current.executeQuery('SELECT dummy AS "DUMMY" FROM dual');
      }, options)).resolves.toMatchObject({ rows: [{ DUMMY: 'X' }] });
      await expect(withOjpClient(url, async (current) => {
        scoped.push(current);
        return current.executeQuery('SELECT dummy AS "DUMMY" FROM dual');
      }, options)).resolves.toMatchObject({ rows: [{ DUMMY: 'X' }] });
      expect(scoped).toHaveLength(2);
      expect(scoped[0]).not.toBe(scoped[1]);
      await expect(scoped[0].executeQuery('SELECT 1 FROM dual')).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(scoped[1].executeQuery('SELECT 1 FROM dual')).rejects.toBeInstanceOf(OjpConnectionError);
    }, 60_000);

    it('closes the scoped Oracle session when the callback fails', async () => {
      const failure = new Error('operation failed');
      let failedClient: OjpClient | undefined;
      await expect(withOjpClient(url, async (client) => {
        failedClient = client;
        throw failure;
      }, options)).rejects.toBe(failure);
      expect(failedClient).toBeInstanceOf(OjpClient);
      await expect(failedClient!.executeQuery('SELECT 1 FROM dual')).rejects.toBeInstanceOf(OjpConnectionError);
    }, 60_000);
  },
);
