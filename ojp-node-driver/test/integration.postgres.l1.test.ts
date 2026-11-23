import { randomBytes } from 'crypto';
import { OjpClient, OjpConnectionError, OjpSqlError, withOjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_INTEGRATION_TESTS === 'true';
const connections = readConnectionFixtures('postgresql_connections.csv', process.env.OJP_TEST_POSTGRES_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

(enabled ? describe : describe.skip).each(connections)(
  'PostgreSQL L1 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_PASSWORD ?? fixturePassword };

    it('supports parameterized CRUD, empty results, and PostgreSQL SQL errors', async () => {
      const client = new OjpClient(url, options);
      const table = `ojp_node_l1_${randomBytes(6).toString('hex')}`;
      await client.connect();
      try {
        await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
        expect((await client.executeQuery('SELECT 1 AS value')).rows).toEqual([{ value: 1 }]);

        await client.executeUpdate(`CREATE TABLE ${table} (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)`);
        try {
          expect(await client.executeUpdate(
            `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
            [1, 'before'],
          )).toBe(1);
          expect((await client.executeQuery(`SELECT id, label FROM ${table} WHERE id = ?`, [1])).rows)
            .toEqual([{ id: 1, label: 'before' }]);

          expect(await client.executeUpdate(`UPDATE ${table} SET label = ? WHERE id = ?`, ['after', 1])).toBe(1);
          expect((await client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [1])).rows)
            .toEqual([{ label: 'after' }]);

          await expect(client.executeUpdate(
            `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
            [1, 'duplicate'],
          )).rejects.toMatchObject({ name: 'OjpSqlError', sqlState: '23505' });
          await expect(client.executeQuery('THIS IS NOT VALID SQL'))
            .rejects.toBeInstanceOf(OjpSqlError);

          expect(await client.executeUpdate(`DELETE FROM ${table} WHERE id = ?`, [1])).toBe(1);
          const empty = await client.executeQuery(`SELECT id, label FROM ${table} WHERE id = ?`, [1]);
          expect(empty.rows).toEqual([]);
          expect(empty.columns).toEqual(['id', 'label']);
        } finally {
          await client.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
        }
      } finally {
        await client.close();
      }
    }, 60_000);

    it('terminates the session on close and makes the closed client unusable', async () => {
      const client = new OjpClient(url, options);
      const session = await client.connect();
      expect(session.sessionStatus).toBe('SESSION_ACTIVE');
      expect((await client.executeQuery('SELECT 1 AS value')).rows).toEqual([{ value: 1 }]);

      await client.close();
      await expect(client.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(client.close()).resolves.toBeUndefined();

      await expect(withOjpClient(url, (replacement) => replacement.executeQuery('SELECT 1 AS value'), options))
        .resolves.toMatchObject({ rows: [{ value: 1 }] });
    }, 60_000);

    it('manages session propagation and creates a fresh scoped client for each call', async () => {
      const clients: OjpClient[] = [];
      const first = await withOjpClient(url, async (client) => {
        clients.push(client);
        return client.executeQuery('SELECT 1 AS value');
      }, options);
      const second = await withOjpClient(url, async (client) => {
        clients.push(client);
        return client.executeQuery('SELECT 2 AS value');
      }, options);

      expect(first.rows).toEqual([{ value: 1 }]);
      expect(second.rows).toEqual([{ value: 2 }]);
      expect(clients).toHaveLength(2);
      expect(clients[0]).not.toBe(clients[1]);
      await expect(clients[0].executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(clients[1].executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
    }, 60_000);

    it('closes the scoped session when the callback fails', async () => {
      const failure = new Error('operation failed');
      let failedClient: OjpClient | undefined;

      await expect(withOjpClient(url, async (client) => {
        failedClient = client;
        throw failure;
      }, options)).rejects.toBe(failure);

      expect(failedClient).toBeInstanceOf(OjpClient);
      if (!failedClient) {
        throw new Error('withOjpClient callback was not invoked');
      }
      await expect(failedClient.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
    }, 60_000);
  },
);
