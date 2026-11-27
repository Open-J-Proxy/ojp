import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'crypto';
import { OjpClient, OjpConnectionError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_L6_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',').filter(Boolean);
const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060').split(',').map(Number);
if (enabled && (containers.length < 2 || ports.length < containers.length
    || new Set(containers).size !== containers.length || new Set(ports).size !== ports.length)) {
  throw new Error('Oracle L6 requires two distinct OJP_TEST_NODE_CONTAINERS and matching ports');
}
const nodes = containers.map((container, index) => ({ container, port: ports[index] }));

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L6 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const password = process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword;
    const prefix = `ojp_node_l6_${randomBytes(6).toString('hex')}`;
    const clusterUrl = ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
    const temporaryTables = [`${prefix}_gtt`, `${prefix}_lost_gtt`];
    const clientOptions = { user, password, healthCheckIntervalMs: 0 };
    const stopped = new Set<string>();

    afterEach(async () => {
      for (const node of nodes) {
        if (stopped.has(node.container)) {
          execFileSync('docker', ['start', node.container], { stdio: 'ignore' });
          await waitForPortOpen(node.port, 60_000);
          stopped.delete(node.container);
        }
      }
      const cleanup = new OjpClient(ojpUrl(`localhost:${nodes[0].port}`, jdbcUrl), clientOptions);
      await cleanup.connect();
      try {
        for (const table of temporaryTables) {
          const exists = await cleanup.executeQuery(
            'SELECT table_name AS "table_name" FROM user_tables WHERE table_name = ?',
            [table.toUpperCase()],
          );
          if (exists.rows.length > 0) {
            await cleanup.executeUpdate(`TRUNCATE TABLE ${table}`);
            await cleanup.executeUpdate(`DROP TABLE ${table}`);
          }
        }
      } finally {
        await cleanup.close();
      }
      resetPoolCacheForTests();
      resetEndpointHealthForTests();
    }, 90_000);

    it('preserves Oracle temporary-table rows across independent calls on the bound session', async () => {
      const client = new OjpClient(clusterUrl, clientOptions);
      const table = `${prefix}_gtt`;
      await client.connect();
      try {
        expect(client.boundEndpoint).toBeDefined();
        await client.executeUpdate(
          `CREATE GLOBAL TEMPORARY TABLE ${table} (id NUMBER(10) PRIMARY KEY, label VARCHAR2(80)) `
          + 'ON COMMIT PRESERVE ROWS',
        );
        expect(await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'sticky row']))
          .toBe(1);
        expect((await client.executeQuery(
          `SELECT id AS "ID", label AS "LABEL" FROM ${table} WHERE id = ?`, [1],
        )).rows).toEqual([{ ID: '1', LABEL: 'sticky row' }]);
        await client.executeUpdate(`TRUNCATE TABLE ${table}`);
        await client.executeUpdate(`DROP TABLE ${table}`);
      } finally {
        await client.close();
      }
    }, 60_000);

    it('surfaces loss of the bound Oracle session rather than rerouting sticky state', async () => {
      const client = new OjpClient(clusterUrl, clientOptions);
      const table = `${prefix}_lost_gtt`;
      await client.connect();
      const endpoint = client.boundEndpoint;
      const boundNode = nodes.find(({ port }) => port === endpoint?.port);
      if (!boundNode) {
        await client.close();
        throw new Error(`Connected endpoint ${endpoint?.port ?? 'unknown'} is not in the Oracle test cluster`);
      }
      try {
        await client.executeUpdate(
          `CREATE GLOBAL TEMPORARY TABLE ${table} (id NUMBER(10) PRIMARY KEY) ON COMMIT PRESERVE ROWS`,
        );
        await client.executeUpdate(`INSERT INTO ${table} (id) VALUES (1)`);
        execFileSync('docker', ['kill', boundNode.container], { stdio: 'ignore' });
        stopped.add(boundNode.container);
        await waitForPortClosed(boundNode.port);
        await expect(client.executeQuery(`SELECT id FROM ${table}`)).rejects.toBeInstanceOf(OjpConnectionError);
        expect(client.boundEndpoint?.port).toBe(boundNode.port);
      } finally {
        await client.close().catch(() => undefined);
      }
    }, 90_000);
  },
);

async function waitForPortClosed(port: number): Promise<void> {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const open = await new Promise<boolean>((resolve) => {
      const socket = net.connect({ host: 'localhost', port }, () => {
        socket.end();
        resolve(true);
      });
      socket.on('error', () => resolve(false));
    });
    if (!open) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Port ${port} did not close within 5000ms`);
}

async function waitForPortOpen(port: number, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      await new Promise<void>((resolve, reject) => {
        const socket = net.connect({ host: 'localhost', port }, () => {
          socket.end();
          resolve();
        });
        socket.on('error', reject);
      });
      return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  throw new Error(`Port ${port} did not reopen within ${timeoutMs}ms`);
}
