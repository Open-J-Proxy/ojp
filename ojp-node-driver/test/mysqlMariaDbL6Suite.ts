import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'crypto';
import { OjpClient, withOjpClient } from '../src';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

interface DatabaseL6Options {
  database: 'MariaDB' | 'MySQL';
  enabledEnvironmentVariable: string;
  fixture: string;
  fixtureEnvironmentVariable: string;
  passwordEnvironmentVariable: string;
}

export function registerDatabaseL6Suite(options: DatabaseL6Options): void {
  const enabled = process.env[options.enabledEnvironmentVariable] === 'true';
  const multinodeEnabled = enabled && process.env.OJP_ENABLE_MULTINODE_FAILOVER_TESTS === 'true';
  const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',');
  const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060,1061').split(',').map(Number);
  if (multinodeEnabled && (containers.length !== 3 || new Set(containers).size !== 3
      || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
    throw new Error('OJP_TEST_NODE_CONTAINERS must name the three dedicated test containers');
  }
  if (multinodeEnabled && (ports.length !== 3 || new Set(ports).size !== 3
      || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535))) {
    throw new Error('OJP_TEST_NODE_PORTS must contain three distinct TCP ports');
  }
  const nodes = ports.map((port, index) => ({ port, container: containers[index] }));
  const connections = readConnectionFixtures(options.fixture, process.env[options.fixtureEnvironmentVariable]);

  (enabled ? describe : describe.skip).each(connections)(
    `${options.database} L6 for %s`,
    (jdbcUrl: string, user: string, fixturePassword: string) => {
      const url = ojpUrl(process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059', jdbcUrl);
      const credentials = { user, password: process.env[options.passwordEnvironmentVariable] ?? fixturePassword };
      const table = `ojp_l6_${randomBytes(6).toString('hex')}`;

      it('retains a temporary table across independent statements', async () => {
        await withOjpClient(url, async (client) => {
          const temporary = `${table}_basic`;
          await client.executeUpdate(`CREATE TEMPORARY TABLE ${temporary} (id INT, value VARCHAR(100))`);
          expect(await client.executeUpdate(`INSERT INTO ${temporary} VALUES (?, ?)`, [1, 'test_value'])).toBe(1);
          const result = await client.executeQuery(`SELECT id, value FROM ${temporary}`);
          expect(result.columns.map((column) => column.toLowerCase())).toEqual(['id', 'value']);
          expect(result.rows).toEqual([{ id: 1, value: 'test_value' }]);
          await client.executeUpdate(`DROP TEMPORARY TABLE ${temporary}`);
        }, credentials);
      }, 30_000);

      it('retains a session variable across independent statements', async () => {
        await withOjpClient(url, async (client) => {
          await client.executeUpdate('SET @ojp_l6_test_var = 12345');
          const result = await client.executeQuery('SELECT @ojp_l6_test_var AS var_value');
          expect(result.rows).toHaveLength(1);
          expect(Number(result.rows[0].var_value)).toBe(12345);
        }, credentials);
      }, 30_000);

      it('retains temporary-table inserts, updates and exact decimal values', async () => {
        await withOjpClient(url, async (client) => {
          const temporary = `${table}_complex`;
          await client.executeUpdate(
            `CREATE TEMPORARY TABLE ${temporary} (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2))`,
          );
          await client.executeUpdate(`INSERT INTO ${temporary} VALUES (1, 'Alice', 100.50)`);
          await client.executeUpdate(`INSERT INTO ${temporary} VALUES (2, 'Bob', 200.75)`);
          await client.executeUpdate(`INSERT INTO ${temporary} VALUES (3, 'Charlie', 150.25)`);
          expect(await client.executeUpdate(`UPDATE ${temporary} SET amount = amount + 50.00 WHERE id = 2`)).toBe(1);
          expect((await client.executeQuery(`SELECT id, name, amount FROM ${temporary} ORDER BY id`)).rows).toEqual([
            { id: 1, name: 'Alice', amount: '100.50' },
            { id: 2, name: 'Bob', amount: '250.75' },
            { id: 3, name: 'Charlie', amount: '150.25' },
          ]);
          await client.executeUpdate(`DROP TEMPORARY TABLE ${temporary}`);
        }, credentials);
      }, 30_000);

      it('preserves temporary tables through commits and rolls back only uncommitted rows', async () => {
        await withOjpClient(url, async (client) => {
          const temporary = `${table}_transactions`;
          await client.executeUpdate(`CREATE TEMPORARY TABLE ${temporary} (id INT PRIMARY KEY, value VARCHAR(100))`);
          await client.withTransaction(async (transaction) => {
            await transaction.executeUpdate(`INSERT INTO ${temporary} VALUES (?, ?)`, [1, 'committed']);
          });
          await client.startTransaction();
          await client.executeUpdate(`INSERT INTO ${temporary} VALUES (?, ?)`, [2, 'rolled back']);
          await client.rollback();
          await client.withTransaction(async (transaction) => {
            expect((await transaction.executeQuery(`SELECT id, value FROM ${temporary} ORDER BY id`)).rows)
              .toEqual([{ id: 1, value: 'committed' }]);
          });
          expect(Number((await client.executeQuery('SELECT 1 AS usable')).rows[0].usable)).toBe(1);
          await client.executeUpdate(`DROP TEMPORARY TABLE ${temporary}`);
        }, credentials);
      }, 30_000);

      it('closes the session and prevents the closed client from being reused', async () => {
        const client = new OjpClient(url, credentials);
        await client.connect();
        await client.executeUpdate(`CREATE TEMPORARY TABLE ${table}_closed (id INT)`);
        expect(client.boundEndpoint).toBeDefined();
        await client.executeUpdate(`DROP TEMPORARY TABLE ${table}_closed`);
        await client.close();
        expect(client.boundEndpoint).toBeUndefined();
        await expect(client.close()).resolves.toBeUndefined();
        await expect(client.executeQuery('SELECT 1')).rejects.toThrow(/client is closed/i);
        await withOjpClient(url, async (verifier) => {
          expect(Number((await verifier.executeQuery('SELECT 1 AS usable')).rows[0].usable)).toBe(1);
        }, credentials);
      }, 30_000);

      (multinodeEnabled ? it : it.skip)('does not reroute a session after its bound node fails', async () => {
        resetEndpointHealthForTests();
        const clusterUrl = ojpUrl(nodes.map((node) => `localhost:${node.port}`).join(','), jdbcUrl);
        const client = new OjpClient(clusterUrl, { ...credentials, callTimeoutMs: 8_000 });
        await client.connect();
        const boundNode = nodes.find((node) => node.port === client.boundEndpoint?.port);
        if (!boundNode) {
          await client.close();
          throw new Error('The bound endpoint is not in the dedicated test cluster');
        }
        let stopped = false;
        try {
          await client.executeUpdate(`CREATE TEMPORARY TABLE ${table} (id INT, value VARCHAR(100))`);
          await client.executeUpdate(`INSERT INTO ${table} VALUES (1, 'sticky state')`);
          execFileSync('docker', ['kill', boundNode.container], { stdio: 'ignore' });
          stopped = true;
          await waitForPort(boundNode.port, false, 5_000);
          await expect(client.executeQuery(`SELECT value FROM ${table} WHERE id = 1`)).rejects.toMatchObject({
            name: 'OjpConnectionError',
            message: expect.stringMatching(/session bound to .* is no longer usable/i),
          });
          expect(client.boundEndpoint?.port).toBe(boundNode.port);
          const otherNode = nodes.find((node) => node.port !== boundNode.port)!;
          await withOjpClient(ojpUrl(`localhost:${otherNode.port}`, jdbcUrl), async (verifier) => {
            expect(Number((await verifier.executeQuery('SELECT 1 AS usable')).rows[0].usable)).toBe(1);
          }, credentials);
        } finally {
          if (stopped) {
            execFileSync('docker', ['start', boundNode.container], { stdio: 'ignore' });
            await waitForPort(boundNode.port, true, 60_000);
          }
          await client.close().catch(() => undefined);
          resetEndpointHealthForTests();
        }
      }, 90_000);
    },
  );
}

async function waitForPort(port: number, open: boolean, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const isOpen = await new Promise<boolean>((resolve) => {
      const socket = net.connect({ host: 'localhost', port }, () => {
        socket.end();
        resolve(true);
      });
      socket.on('error', () => resolve(false));
    });
    if (isOpen === open) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Port ${port} did not ${open ? 'open' : 'close'} within ${timeoutMs}ms`);
}
