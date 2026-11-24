import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'node:crypto';
import { OjpClient, OjpConnectionError, OjpSqlError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { isEndpointHealthy, resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

interface DatabaseL7Options {
  database: 'MariaDB' | 'MySQL';
  enabledEnvironmentVariable: string;
  fixture: string;
  fixtureEnvironmentVariable: string;
  passwordEnvironmentVariable: string;
}

export function registerDatabaseL7Suite(options: DatabaseL7Options): void {
  const enabled = process.env[options.enabledEnvironmentVariable] === 'true';
  const describeIfEnabled = enabled ? describe : describe.skip;
  const connections = readConnectionFixtures(options.fixture, process.env[options.fixtureEnvironmentVariable]);
  const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',');
  const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060,1061').split(',').map(Number);
  if (enabled && (containers.length < 2 || new Set(containers).size !== containers.length
      || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
    throw new Error('OJP_TEST_NODE_CONTAINERS must name at least two dedicated test containers');
  }
  if (enabled && (ports.length < containers.length || ports.length < 2 || new Set(ports).size !== ports.length
      || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535))) {
    throw new Error('OJP_TEST_NODE_PORTS must contain at least two distinct TCP ports');
  }
  const nodes = containers.map((container, index) => ({ port: ports[index], container }));
  describeIfEnabled.each(connections)(`OjpClient - ${options.database} L7: %s`, (jdbcUrl, user, fixturePassword) => {
    const password = process.env[options.passwordEnvironmentVariable] ?? fixturePassword;
    const prefix = `ojp_node_l7_${randomBytes(6).toString('hex')}`;
    const clusterUrl = (): string => ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
    const singleNodeUrl = (port: number): string => ojpUrl(`localhost:${port}`, jdbcUrl);
    const connectClient = async (
      url = clusterUrl(),
      options: { loadBalancingStrategy?: 'least-connections' | 'round-robin' } = {},
    ): Promise<{ client: OjpClient; session: Awaited<ReturnType<OjpClient['connect']>> }> => {
      const client = new OjpClient(url, {
        user,
        password,
        healthCheckIntervalMs: 0,
        ...options,
      });
      return { client, session: await client.connect() };
    };
    const newClient = async (
      url = clusterUrl(),
      options: { loadBalancingStrategy?: 'least-connections' | 'round-robin' } = {},
    ): Promise<OjpClient> => (await connectClient(url, options)).client;
    const stoppedContainers = new Set<string>();
    beforeEach(() => {
      resetEndpointHealthForTests();
      resetPoolCacheForTests();
    });
    afterEach(async () => {
      for (const node of nodes) {
        if (stoppedContainers.has(node.container)) {
          execFileSync('docker', ['start', node.container], { stdio: 'ignore' });
          await waitForPortOpen(node.port, 60_000);
          stoppedContainers.delete(node.container);
        }
      }
      resetEndpointHealthForTests();
      resetPoolCacheForTests();
    }, 90_000);
    it('chooses a less-loaded healthy endpoint for a new session', async () => {
      const first = await newClient();
      const second = await newClient();
      try {
        expect(first.boundEndpoint).toBeDefined();
        expect(second.boundEndpoint).toBeDefined();
        expect(second.boundEndpoint).not.toEqual(first.boundEndpoint);
        expect(Number((await second.executeQuery('SELECT 1 AS healthy')).rows[0].healthy)).toBe(1);
      } finally {
        await second.close();
        await first.close();
      }
    }, 60_000);
    it('distributes equal-load sessions across every healthy endpoint with round-robin', async () => {
      const selected = new Set<number>();
      for (let index = 0; index < nodes.length * 2; index++) {
        const client = await newClient(clusterUrl(), { loadBalancingStrategy: 'round-robin' });
        selected.add(client.boundEndpoint!.port);
        await client.close();
      }
      expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
    }, 120_000);
    it('uses a released endpoint again when its load becomes lowest', async () => {
      const clients: OjpClient[] = [];
      try {
        for (let index = 0; index < nodes.length; index++) {
          clients.push(await newClient());
        }
        const releasedPort = clients[0].boundEndpoint!.port;
        expect(new Set(clients.map((client) => client.boundEndpoint!.port)).size).toBe(nodes.length);
        await clients[0].close();
        const next = await newClient();
        clients.push(next);
        expect(next.boundEndpoint?.port).toBe(releasedPort);
        expect(Number((await next.executeQuery('SELECT 1 AS usable')).rows[0].usable)).toBe(1);
      } finally {
        for (const client of clients) {
          await client.close();
        }
      }
    }, 120_000);
    it('round-robin continues using loaded nodes rather than preferring the least loaded', async () => {
      const clients: OjpClient[] = [];
      try {
        for (let index = 0; index < nodes.length * 2 + 1; index++) {
          clients.push(await newClient(singleNodeUrl(nodes[0].port)));
        }
        const selected = new Set<number>();
        for (let index = 0; index < nodes.length; index++) {
          const client = await newClient(clusterUrl(), { loadBalancingStrategy: 'round-robin' });
          clients.push(client);
          selected.add(client.boundEndpoint!.port);
        }
        expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
      } finally {
        for (const client of clients) {
          await client.close();
        }
      }
    }, 120_000);
    it('detects an unavailable endpoint, routes around it, and propagates cluster health', async () => {
      const seed = await newClient();
      await seed.close();
      const down = nodes[0];
      execFileSync('docker', ['kill', down.container], { stdio: 'ignore' });
      stoppedContainers.add(down.container);
      await waitForPortClosed(down.port);
      const { client, session } = await connectClient();
      try {
        expect(client.boundEndpoint?.port).not.toBe(down.port);
        expect(session.clusterHealth).toContain(`localhost:${down.port}(DOWN)`);
        expect(Number((await client.executeQuery('SELECT 1 AS healthy')).rows[0].healthy)).toBe(1);
      } finally {
        await client.close();
      }
    }, 90_000);
    it('detects a recovered endpoint and redistributes new sessions to it', async () => {
      const seed = await newClient();
      await seed.close();
      const down = nodes[0];
      execFileSync('docker', ['kill', down.container], { stdio: 'ignore' });
      stoppedContainers.add(down.container);
      await waitForPortClosed(down.port);
      const duringOutage = await newClient();
      expect(duringOutage.boundEndpoint?.port).not.toBe(down.port);
      await duringOutage.close();
      execFileSync('docker', ['start', down.container], { stdio: 'ignore' });
      stoppedContainers.delete(down.container);
      await waitForPortOpen(down.port, 60_000);
      const selected = new Set<number>();
      for (let index = 0; index < nodes.length * 3; index++) {
        const client = await newClient(clusterUrl(), { loadBalancingStrategy: 'round-robin' });
        selected.add(client.boundEndpoint!.port);
        await client.close();
      }
      expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
    }, 150_000);
    it('reuses the cached connHash without requiring caller-managed session state', async () => {
      const { client: first, session: firstSession } = await connectClient();
      await first.close();
      const { client: second, session: secondSession } = await connectClient();
      try {
        expect(secondSession.connHash).toBe(firstSession.connHash);
        expect(secondSession.sessionUUID).toBe('');
        expect(second.boundEndpoint).toBeDefined();
        expect(Number((await second.executeQuery('SELECT 1 AS usable')).rows[0].usable)).toBe(1);
      } finally {
        await second.close();
      }
    }, 60_000);
    it('reconnects and retries once when a stateless session uses a stale pool identity', async () => {
      const table = `${prefix}_reconnect`;
      const selector = await newClient();
      const targetPort = selector.boundEndpoint!.port;
      await selector.close();
      const warmPool = await newClient(singleNodeUrl(targetPort));
      await warmPool.executeUpdate(`CREATE TABLE ${table} (id INTEGER PRIMARY KEY, value VARCHAR(100) NOT NULL)`);
      await warmPool.executeUpdate(`INSERT INTO ${table} VALUES (7, 'recovered')`);
      await warmPool.close();
      const target = nodes.find(({ port }) => port === targetPort)!;
      execFileSync('docker', ['kill', target.container], { stdio: 'ignore' });
      stoppedContainers.add(target.container);
      await waitForPortClosed(target.port);
      execFileSync('docker', ['start', target.container], { stdio: 'ignore' });
      stoppedContainers.delete(target.container);
      await waitForPortOpen(target.port, 60_000);
      const client = await newClient(singleNodeUrl(target.port));
      try {
        await expect(client.executeUpdate(`INSERT INTO ${table} VALUES (8, 'retried')`)).resolves.toBe(1);
        await expect(client.executeQuery(`SELECT id, value FROM ${table} ORDER BY id`)).resolves.toMatchObject({
          rows: [
            { id: 7, value: 'recovered' },
            { id: 8, value: 'retried' },
          ],
        });
        await expect(client.executeUpdate(`DROP TABLE ${table}`)).resolves.toBeDefined();
      } finally {
        await client.close();
      }
    }, 120_000);
    it('does not retry or reroute sticky sessions when their endpoint is lost', async () => {
      const client = await newClient();
      const bound = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
      const table = `${prefix}_sticky`;
      await client.executeUpdate(`CREATE TEMPORARY TABLE ${table} (id INTEGER PRIMARY KEY)`);
      execFileSync('docker', ['kill', bound.container], { stdio: 'ignore' });
      stoppedContainers.add(bound.container);
      await waitForPortClosed(bound.port);
      try {
        await expect(client.executeUpdate(`INSERT INTO ${table} VALUES (1)`))
          .rejects.toBeInstanceOf(OjpConnectionError);
        expect(client.boundEndpoint?.port).toBe(bound.port);
      } finally {
        await client.close().catch(() => undefined);
      }
    }, 90_000);
    it('keeps endpoint health intact after a SQL error', async () => {
      const client = await newClient();
      const endpoint = client.boundEndpoint!;
      try {
        await expect(client.executeUpdate('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
        expect(isEndpointHealthy(endpoint)).toBe(true);
      } finally {
        await client.close();
      }
      const next = await newClient();
      try {
        expect(next.boundEndpoint).toBeDefined();
        expect(next.boundEndpoint?.port).toBeGreaterThan(0);
        expect(Number((await next.executeQuery('SELECT 1 AS healthy')).rows[0].healthy)).toBe(1);
      } finally {
        await next.close();
      }
    }, 60_000);
  });
}

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
  let lastError: unknown;
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
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  throw new Error(`Port ${port} did not open within ${timeoutMs}ms: ${String(lastError)}`);
}
