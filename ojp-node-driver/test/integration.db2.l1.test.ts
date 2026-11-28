import { randomBytes } from 'crypto';
import { OjpClient, OjpConnectionError, OjpSqlError, withOjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_DB2_TESTS === 'true';
const connections = readConnectionFixtures('db2_connections.csv', process.env.OJP_TEST_DB2_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

(enabled ? describe : describe.skip).each(connections)(
  'DB2 L1 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_DB2_PASSWORD ?? fixturePassword };
    const scalar = 'SELECT 1 AS "VALUE" FROM SYSIBM.SYSDUMMY1';

    it('supports parameterized CRUD, empty results and SQL errors', async () => {
      const client = new OjpClient(url, options);
      const table = `ojp_node_l1_${randomBytes(6).toString('hex')}`;
      await client.connect();
      try {
        await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
        expect((await client.executeQuery(scalar)).rows).toEqual([{ VALUE: 1 }]);
        await client.executeUpdate(`CREATE TABLE ${table} (id INTEGER NOT NULL PRIMARY KEY, label VARCHAR(100) NOT NULL)`);
        try {
          expect(await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before'])).toBe(1);
          expect((await client.executeQuery(`SELECT id, label FROM ${table} WHERE id = ?`, [1])).rows)
            .toEqual([{ ID: 1, LABEL: 'before' }]);
          expect(await client.executeUpdate(`UPDATE ${table} SET label = ? WHERE id = ?`, ['after', 1])).toBe(1);
          expect((await client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [1])).rows)
            .toEqual([{ LABEL: 'after' }]);
          await expect(client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'duplicate']))
            .rejects.toMatchObject({ name: 'OjpSqlError', sqlState: '23505' });
          await expect(client.executeQuery('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
          expect(await client.executeUpdate(`DELETE FROM ${table} WHERE id = ?`, [1])).toBe(1);
          const empty = await client.executeQuery(`SELECT id, label FROM ${table} WHERE id = ?`, [1]);
          expect(empty.rows).toEqual([]);
          expect(empty.columns).toEqual(['ID', 'LABEL']);
        } finally {
          await client.executeUpdate(`DROP TABLE ${table}`);
        }
      } finally {
        await client.close();
      }
    }, 60_000);

    it('closes the session and prevents use of the closed client', async () => {
      const client = new OjpClient(url, options);
      expect((await client.connect()).sessionStatus).toBe('SESSION_ACTIVE');
      expect((await client.executeQuery(scalar)).rows).toEqual([{ VALUE: 1 }]);
      await client.close();
      await expect(client.executeQuery(scalar)).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(client.close()).resolves.toBeUndefined();
    }, 60_000);

    it('creates a fresh scoped client and propagates its session automatically', async () => {
      const clients: OjpClient[] = [];
      for (let i = 0; i < 2; i++) {
        await expect(withOjpClient(url, async (client) => {
          clients.push(client);
          return client.executeQuery(scalar);
        }, options)).resolves.toMatchObject({ rows: [{ VALUE: 1 }] });
      }
      expect(clients).toHaveLength(2);
      expect(clients[0]).not.toBe(clients[1]);
      for (const client of clients) {
        await expect(client.executeQuery(scalar)).rejects.toBeInstanceOf(OjpConnectionError);
      }
    }, 60_000);

    it('closes a scoped session when the callback fails', async () => {
      const failure = new Error('operation failed');
      let failedClient: OjpClient | undefined;
      await expect(withOjpClient(url, async (client) => {
        failedClient = client;
        throw failure;
      }, options)).rejects.toBe(failure);
      expect(failedClient).toBeInstanceOf(OjpClient);
      await expect(failedClient!.executeQuery(scalar)).rejects.toBeInstanceOf(OjpConnectionError);
    }, 60_000);
  },
);
