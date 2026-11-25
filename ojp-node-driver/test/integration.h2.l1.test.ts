import { randomBytes } from 'crypto';
import { OjpClient, OjpConnectionError, OjpSqlError, withOjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_TEST_H2 === 'true';
const endpoint = process.env.OJP_TEST_H2_ADDR;
const records = readConnectionFixtures('h2_connections.csv', process.env.OJP_TEST_H2_CSV);
if (enabled && !endpoint) {
  throw new Error('OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true');
}

(enabled ? describe : describe.skip).each(records)(
  'H2 L1 for %s',
  (jdbcUrl: string, user: string, password: string) => {
    const url = ojpUrl(endpoint ?? '', jdbcUrl);
    const credentials = { user, password };

    it('supports parameterized CRUD, empty results, and SQL errors', async () => {
      const client = new OjpClient(url, credentials);
      const statement = client.createStatement();
      const table = `ojp_node_l1_${randomBytes(6).toString('hex')}`;
      await client.connect();
      try {
        await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
        expect((await statement.executeQuery('SELECT 1 AS L1_VALUE')).rows)
          .toEqual([{ L1_VALUE: 1 }]);
        await statement.executeUpdate(
          `CREATE TABLE ${table} (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)`,
        );
        try {
          expect(await statement.executeUpdate(
            `INSERT INTO ${table} (id, name) VALUES (?, ?)`,
            [1, 'before'],
          )).toBe(1);
          expect((await statement.executeQuery(
            `SELECT id, name FROM ${table} WHERE id = ?`,
            [1],
          )).rows).toEqual([{ ID: 1, NAME: 'before' }]);

          expect(await statement.executeUpdate(
            `UPDATE ${table} SET name = ? WHERE id = ?`,
            ['after', 1],
          )).toBe(1);
          expect((await statement.executeQuery(
            `SELECT name FROM ${table} WHERE id = ?`,
            [1],
          )).rows).toEqual([{ NAME: 'after' }]);

          await expect(statement.executeUpdate(
            `INSERT INTO ${table} (id, name) VALUES (?, ?)`,
            [1, 'duplicate'],
          )).rejects.toMatchObject({
            name: 'OjpSqlError',
            sqlState: '23505',
          });
          await expect(statement.executeQuery('THIS IS NOT VALID SQL'))
            .rejects.toBeInstanceOf(OjpSqlError);

          expect(await statement.executeUpdate(`DELETE FROM ${table} WHERE id = ?`, [1])).toBe(1);
          const empty = await statement.executeQuery(
            `SELECT id, name FROM ${table} WHERE id = ?`,
            [1],
          );
          expect(empty.rows).toEqual([]);
          expect(empty.columns).toEqual(['ID', 'NAME']);
        } finally {
          await statement.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
        }
      } finally {
        await statement.close();
        await client.close();
      }
    }, 60_000);

    it('terminates the session on close and makes the closed client unusable', async () => {
      const client = new OjpClient(url, credentials);
      const session = await client.connect();
      expect(session.sessionStatus).toBe('SESSION_ACTIVE');
      expect((await client.executeQuery('SELECT 1 AS L1_VALUE')).rows)
        .toEqual([{ L1_VALUE: 1 }]);

      await client.close();
      await expect(client.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
      await expect(client.close()).resolves.toBeUndefined();

      await expect(withOjpClient(
        url,
        (replacement) => replacement.executeQuery('SELECT 1 AS L1_VALUE'),
        credentials,
      )).resolves.toMatchObject({ rows: [{ L1_VALUE: 1 }] });
    }, 60_000);

    it('manages session propagation and creates a fresh scoped client for each call', async () => {
      const clients: OjpClient[] = [];
      const first = await withOjpClient(url, async (client) => {
        clients.push(client);
        return client.executeQuery('SELECT 1 AS L1_VALUE');
      }, credentials);
      const second = await withOjpClient(url, async (client) => {
        clients.push(client);
        return client.executeQuery('SELECT 2 AS L1_VALUE');
      }, credentials);

      expect(first.rows).toEqual([{ L1_VALUE: 1 }]);
      expect(second.rows).toEqual([{ L1_VALUE: 2 }]);
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
      }, credentials)).rejects.toBe(failure);

      expect(failedClient).toBeInstanceOf(OjpClient);
      if (!failedClient) {
        throw new Error('withOjpClient callback was not invoked');
      }
      await expect(failedClient.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
    }, 60_000);

    it('forwards a named datasource to the server', async () => {
      const defaultClient = new OjpClient(url, credentials);
      const namedClient = new OjpClient(
        `jdbc:ojp[${endpoint}(nodeTest)]_${jdbcUrl.slice('jdbc:'.length)}`,
        credentials,
      );
      try {
        const defaultSession = await defaultClient.connect();
        const namedSession = await namedClient.connect();
        expect(namedSession.connHash).not.toBe(defaultSession.connHash);
        expect(namedSession.clientUUID).toBe(defaultSession.clientUUID);
        expect((await namedClient.executeQuery('SELECT 1')).rows).toHaveLength(1);
      } finally {
        await defaultClient.close();
        await namedClient.close();
      }
    }, 60_000);
  },
);
