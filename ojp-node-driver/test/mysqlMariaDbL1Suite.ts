import { randomBytes } from 'crypto';
import { OjpClient, OjpConnectionError, OjpSqlError, withOjpClient } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

interface DatabaseL1Options {
  database: string;
  enabledEnvironmentVariable: string;
  fixture: string;
  fixtureEnvironmentVariable: string;
  passwordEnvironmentVariable: string;
}

export function registerDatabaseL1Suite(options: DatabaseL1Options): void {
  const enabled = process.env[options.enabledEnvironmentVariable] === 'true';
  const connections = readConnectionFixtures(
    options.fixture,
    process.env[options.fixtureEnvironmentVariable],
  );
  const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

  (enabled ? describe : describe.skip).each(connections)(
    `${options.database} L1 for %s`,
    (jdbcUrl: string, user: string, fixturePassword: string) => {
      const url = ojpUrl(endpoint, jdbcUrl);
      const scalarOne = options.database === 'MySQL' ? 1n : 1;
      const credentials = {
        user,
        password: process.env[options.passwordEnvironmentVariable] ?? fixturePassword,
      };

      it('supports parameterized CRUD, empty results, and SQL errors', async () => {
        const client = new OjpClient(url, credentials);
        const table = `ojp_node_l1_${randomBytes(6).toString('hex')}`;
        await client.connect();
        try {
          await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
          expect((await client.executeQuery('SELECT 1 AS l1_value')).rows).toEqual([{ l1_value: scalarOne }]);

          await client.executeUpdate(
            `CREATE TABLE ${table} (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)`,
          );
          try {
            expect(await client.executeUpdate(
              `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
              [1, 'before'],
            )).toBe(1);
            expect((await client.executeQuery(
              `SELECT id, label FROM ${table} WHERE id = ?`,
              [1],
            )).rows).toEqual([{ id: 1, label: 'before' }]);

            expect(await client.executeUpdate(
              `UPDATE ${table} SET label = ? WHERE id = ?`,
              ['after', 1],
            )).toBe(1);
            expect((await client.executeQuery(
              `SELECT label FROM ${table} WHERE id = ?`,
              [1],
            )).rows).toEqual([{ label: 'after' }]);

            await expect(client.executeUpdate(
              `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
              [1, 'duplicate'],
            )).rejects.toMatchObject({
              name: 'OjpSqlError',
              sqlState: expect.stringMatching(/^[0-9A-Z]{5}$/),
              vendorCode: expect.any(Number),
            });
            await expect(client.executeQuery('THIS IS NOT VALID SQL'))
              .rejects.toBeInstanceOf(OjpSqlError);

            expect(await client.executeUpdate(`DELETE FROM ${table} WHERE id = ?`, [1])).toBe(1);
            const empty = await client.executeQuery(
              `SELECT id, label FROM ${table} WHERE id = ?`,
              [1],
            );
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
        const client = new OjpClient(url, credentials);
        const session = await client.connect();
        expect(session.sessionStatus).toBe('SESSION_ACTIVE');
        expect((await client.executeQuery('SELECT 1 AS l1_value')).rows).toEqual([{ l1_value: scalarOne }]);

        await client.close();
        await expect(client.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
        await expect(client.connect()).rejects.toBeInstanceOf(OjpConnectionError);
        await expect(client.close()).resolves.toBeUndefined();

        await expect(withOjpClient(
          url,
          (replacement) => replacement.executeQuery('SELECT 1 AS l1_value'),
          credentials,
        )).resolves.toMatchObject({ rows: [{ l1_value: scalarOne }] });
      }, 60_000);

      it('manages session propagation and creates a fresh scoped client for each call', async () => {
        const clients: OjpClient[] = [];
        const first = await withOjpClient(url, async (client) => {
          clients.push(client);
          return client.executeQuery('SELECT 1 AS l1_value');
        }, credentials);
        const second = await withOjpClient(url, async (client) => {
          clients.push(client);
          return client.executeQuery('SELECT 2 AS l1_value');
        }, credentials);

        expect(first.rows).toEqual([{ l1_value: scalarOne }]);
        expect(second.rows).toEqual([{ l1_value: options.database === 'MySQL' ? 2n : 2 }]);
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
    },
  );
}
