import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'crypto';
import { OjpClient } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_L7_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',').filter(Boolean);
const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060').split(',').map(Number);
if (enabled && (containers.length < 2 || ports.length < containers.length
    || new Set(containers).size !== containers.length
    || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name))
    || new Set(ports).size !== ports.length || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535))) {
  throw new Error('Oracle L7 requires at least two distinct OJP test containers and valid TCP ports');
}
const nodes = containers.map((container, index) => ({ container, port: ports[index] }));

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L7 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const password = process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword;
    const clusterUrl = ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
    const prefix = `ojp_node_oracle_l7_${randomBytes(6).toString('hex')}`;
    const stopped = new Set<string>();
    const newClient = async (url = clusterUrl, strategy?: 'least-connections' | 'round-robin') => {
      const client = new OjpClient(url, {
        user, password, healthCheckIntervalMs: 0, loadBalancingStrategy: strategy,
      });
      const session = await client.connect();
      return { client, session };
    };

    beforeEach(() => {
      resetEndpointHealthForTests();
      resetPoolCacheForTests();
    });

    afterEach(async () => {
      for (const node of nodes) {
        if (stopped.has(node.container)) {
          execFileSync('docker', ['start', node.container], { stdio: 'ignore' });
          await waitForPortOpen(node.port, 60_000);
          stopped.delete(node.container);
        }
      }
      resetEndpointHealthForTests();
      resetPoolCacheForTests();
    }, 90_000);

    it('distributes new sessions across every Oracle endpoint with round-robin selection', async () => {
      const selected = new Set<number>();
      for (let index = 0; index < nodes.length * 2; index++) {
        const { client } = await newClient(clusterUrl, 'round-robin');
        selected.add(client.boundEndpoint!.port);
        await client.close();
      }
      expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
    }, 120_000);

    it('detects an unavailable endpoint and includes its health in the session response', async () => {
      const seed = await newClient();
      const down = nodes.find(({ port }) => port === seed.client.boundEndpoint?.port)!;
      await seed.client.close();
      execFileSync('docker', ['kill', down.container], { stdio: 'ignore' });
      stopped.add(down.container);
      await waitForPortClosed(down.port);

      const { client, session } = await newClient();
      try {
        expect(client.boundEndpoint?.port).not.toBe(down.port);
        expect(session.clusterHealth).toContain(`localhost:${down.port}(DOWN)`);
        await expect(client.executeQuery('SELECT dummy AS "DUMMY" FROM dual'))
        .resolves.toMatchObject({ rows: [{ DUMMY: 'X' }] });
      } finally {
        await client.close();
      }
    }, 90_000);

    it('reuses recovered endpoints and balances sessions over the entire cluster', async () => {
      const down = nodes[0];
      execFileSync('docker', ['kill', down.container], { stdio: 'ignore' });
      stopped.add(down.container);
      await waitForPortClosed(down.port);
      const outage = await newClient();
      expect(outage.client.boundEndpoint?.port).not.toBe(down.port);
      await outage.client.close();

      execFileSync('docker', ['start', down.container], { stdio: 'ignore' });
      stopped.delete(down.container);
      await waitForPortOpen(down.port, 60_000);

      const selected = new Set<number>();
      for (let index = 0; index < nodes.length * 3; index++) {
        const { client } = await newClient(clusterUrl, 'round-robin');
        selected.add(client.boundEndpoint!.port);
        await client.close();
      }
      expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
    }, 150_000);

    it('reuses the cached connection-pool identity without sharing a virtual session', async () => {
      const first = await newClient();
      const connHash = first.session.connHash;
      await first.client.close();
      const second = await newClient();
      try {
        expect(second.session.connHash).toBe(connHash);
        expect(second.session.sessionUUID).toBe('');
        await expect(second.client.executeQuery('SELECT dummy AS "DUMMY" FROM dual'))
          .resolves.toMatchObject({ rows: [{ DUMMY: 'X' }] });
      } finally {
        await second.client.close();
      }
    }, 60_000);

    it('reconnects after a stale single-node pool identity and persists one write', async () => {
      const table = `${prefix}_reconnect`;
      const target = nodes[0];
      const singleNodeUrl = ojpUrl(`localhost:${target.port}`, jdbcUrl);
      const warm = await newClient(singleNodeUrl);
      try {
        await warm.client.executeUpdate(
          `CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY, label VARCHAR2(40) NOT NULL)`,
        );
      } finally {
        await warm.client.close();
      }
      execFileSync('docker', ['kill', target.container], { stdio: 'ignore' });
      stopped.add(target.container);
      await waitForPortClosed(target.port);
      execFileSync('docker', ['start', target.container], { stdio: 'ignore' });
      stopped.delete(target.container);
      await waitForPortOpen(target.port, 60_000);

      const recovered = await newClient(singleNodeUrl);
      try {
        expect(await recovered.client.executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (7, 'reused')`,
        )).toBe(1);
        await expect(recovered.client.executeQuery(
          `SELECT id AS "ID", label AS "LABEL" FROM ${table}`,
        )).resolves.toMatchObject({ rows: [{ ID: '7', LABEL: 'reused' }] });
        await recovered.client.executeUpdate(`DROP TABLE ${table}`);
      } finally {
        await recovered.client.close();
      }
    }, 120_000);
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
