/**
 * PostgreSQL multinode resilience and recovery. Requires at least two dedicated OJP
 * server containers and a separate OJP server configured with maxConcurrentRequests=1
 * when OJP_ENABLE_POSTGRES_L8_TESTS=true.
 */
import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'node:crypto';
import { OjpClient, OjpConnectionError, OjpSqlError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { isEndpointHealthy, resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { OjpServerEndpoint } from '../src/client/connectionString';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_POSTGRES_L8_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const connections = readConnectionFixtures('postgresql_connections.csv', process.env.OJP_TEST_POSTGRES_CSV);
const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',');
const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060,1061').split(',').map(Number);
const throttledContainer = process.env.OJP_TEST_POSTGRES_L8_THROTTLED_NODE_CONTAINER;
const throttledPort = Number(process.env.OJP_TEST_POSTGRES_L8_THROTTLED_NODE_PORT);

if (enabled && (containers.length < 2 || new Set(containers).size !== containers.length
    || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
  throw new Error('OJP_TEST_NODE_CONTAINERS must name at least two dedicated test containers');
}
if (enabled && (ports.length < containers.length || ports.length < 2 || new Set(ports).size !== ports.length
    || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535))) {
  throw new Error('OJP_TEST_NODE_PORTS must contain a distinct TCP port for every configured test container');
}
if (enabled && (!throttledContainer || !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(throttledContainer)
    || !Number.isInteger(throttledPort) || throttledPort < 1 || throttledPort > 65535
    || ports.includes(throttledPort))) {
  throw new Error('PostgreSQL L8 requires a distinct throttled OJP node container and port');
}

const nodes = containers.map((container, index) => ({ port: ports[index], container }));
const throttledNode = { container: throttledContainer ?? '', port: throttledPort };

describeIfEnabled.each(connections)('OjpClient - PostgreSQL L8: %s', (jdbcUrl, user, fixturePassword) => {
  const password = process.env.OJP_TEST_PASSWORD ?? fixturePassword;
  const prefix = `ojp_node_pg_l8_${randomBytes(6).toString('hex')}`;
  const clusterUrl = (endpoints = nodes): string =>
    ojpUrl(endpoints.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
  const singleNodeUrl = (port: number): string => ojpUrl(`localhost:${port}`, jdbcUrl);
  const connectClient = async (
    url = clusterUrl(),
    options: { loadBalancingStrategy?: 'least-connections' | 'round-robin'; healthCheckIntervalMs?: number } = {},
  ): Promise<{ client: OjpClient; session: Awaited<ReturnType<OjpClient['connect']>> }> => {
    const client = new OjpClient(url, {
      user,
      password,
      healthCheckIntervalMs: 0,
      failoverRetryDelayMs: 0,
      ...options,
    });
    return { client, session: await client.connect() };
  };
  const newClient = async (
    url = clusterUrl(),
    options: { loadBalancingStrategy?: 'least-connections' | 'round-robin'; healthCheckIntervalMs?: number } = {},
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

  it('retries a stateless update on a healthy node and persists exactly one row', async () => {
    const table = `${prefix}_update`;
    const client = await newClient();
    const bound = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    try {
      await client.executeUpdate(`CREATE TABLE ${table} (id INTEGER PRIMARY KEY, value TEXT NOT NULL)`);
      execFileSync('docker', ['kill', bound.container], { stdio: 'ignore' });
      stoppedContainers.add(bound.container);
      await waitForPortClosed(bound.port);

      await expect(client.executeUpdate(`INSERT INTO ${table} VALUES (7, 'retried')`)).resolves.toBe(1);
      expect(client.boundEndpoint?.port).not.toBe(bound.port);
      await expect(client.executeQuery(`SELECT id, value FROM ${table} ORDER BY id`)).resolves.toMatchObject({
        rows: [{ id: 7, value: 'retried' }],
      });
    } finally {
      await client.close().catch(() => undefined);
      const cleanup = await newClient();
      try {
        await cleanup.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
      } finally {
        await cleanup.close();
      }
    }
  }, 120_000);

  it('retries a query stream only before its first result block', async () => {
    const client = await newClient();
    const bound = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    execFileSync('docker', ['kill', bound.container], { stdio: 'ignore' });
    stoppedContainers.add(bound.container);
    await waitForPortClosed(bound.port);

    try {
      await expect(client.executeQuery('SELECT 9 AS recovered')).resolves.toMatchObject({
        rows: [{ recovered: 9 }],
      });
      expect(client.boundEndpoint?.port).not.toBe(bound.port);
    } finally {
      await client.close().catch(() => undefined);
    }
  }, 120_000);

  it('surfaces sticky-session loss without rerouting temporary-table operations', async () => {
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

  it('does not fail over or replay operations from an active transaction', async () => {
    const client = await newClient();
    const bound = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    const table = `${prefix}_transaction`;
    await client.startTransaction();
    execFileSync('docker', ['kill', bound.container], { stdio: 'ignore' });
    stoppedContainers.add(bound.container);
    await waitForPortClosed(bound.port);

    try {
      await expect(client.executeUpdate(`CREATE TABLE ${table} (id INTEGER)`))
        .rejects.toBeInstanceOf(OjpConnectionError);
      expect(client.boundEndpoint?.port).toBe(bound.port);
    } finally {
      await client.close().catch(() => undefined);
    }
  }, 90_000);

  it('does not fail over for a PostgreSQL error and remains usable on the same endpoint', async () => {
    const client = await newClient();
    const endpoint = client.boundEndpoint!;
    try {
      await expect(client.executeUpdate('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
      expect(isEndpointHealthy(endpoint)).toBe(true);
      expect(client.boundEndpoint).toEqual(endpoint);
      await expect(client.executeQuery('SELECT 1 AS usable')).resolves.toMatchObject({
        rows: [{ usable: 1 }],
      });
      expect(client.boundEndpoint).toEqual(endpoint);
    } finally {
      await client.close();
    }
  }, 60_000);

  it('routes around an unavailable node and propagates its health state', async () => {
    const seed = await newClient();
    const down = nodes.find(({ port }) => port === seed.boundEndpoint?.port)!;
    await seed.close();
    execFileSync('docker', ['kill', down.container], { stdio: 'ignore' });
    stoppedContainers.add(down.container);
    await waitForPortClosed(down.port);

    const duringOutage = await connectClient(clusterUrl(), { healthCheckIntervalMs: 0 });
    try {
      expect(duringOutage.client.boundEndpoint?.port).not.toBe(down.port);
      expect(duringOutage.session.clusterHealth).toContain(`localhost:${down.port}(DOWN)`);
      await expect(duringOutage.client.executeQuery('SELECT 1 AS healthy')).resolves.toMatchObject({
        rows: [{ healthy: 1 }],
      });
    } finally {
      await duringOutage.client.close();
    }

  }, 90_000);

  it('reuses a recovered node and redistributes new sessions to every endpoint', async () => {
    const seed = await newClient();
    const down = nodes.find(({ port }) => port === seed.boundEndpoint?.port)!;
    await seed.close();
    execFileSync('docker', ['kill', down.container], { stdio: 'ignore' });
    stoppedContainers.add(down.container);
    await waitForPortClosed(down.port);

    const duringOutage = await connectClient(clusterUrl(), { healthCheckIntervalMs: 0 });
    try {
      expect(duringOutage.client.boundEndpoint?.port).not.toBe(down.port);
      expect(duringOutage.session.clusterHealth).toContain(`localhost:${down.port}(DOWN)`);
    } finally {
      await duringOutage.client.close();
    }

    execFileSync('docker', ['start', down.container], { stdio: 'ignore' });
    stoppedContainers.delete(down.container);
    await waitForPortOpen(down.port, 60_000);
    const recovered = await connectClient(clusterUrl(), { healthCheckIntervalMs: 0 });
    try {
      const endpoint: OjpServerEndpoint = { host: 'localhost', port: down.port };
      expect(isEndpointHealthy(endpoint)).toBe(true);
      expect(recovered.session.clusterHealth).toContain(`localhost:${down.port}(UP)`);
    } finally {
      await recovered.client.close();
    }

    const selected = new Set<number>();
    for (let index = 0; index < nodes.length * 3; index++) {
      const client = await newClient(clusterUrl(), { loadBalancingStrategy: 'round-robin' });
      selected.add(client.boundEndpoint!.port);
      await client.close();
    }
    expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
  }, 180_000);

  it('completes repeated stateless writes on healthy nodes while one endpoint is down', async () => {
    const table = `${prefix}_outage_writes`;
    const seed = await newClient();
    const down = nodes.find(({ port }) => port === seed.boundEndpoint?.port)!;
    try {
      await seed.executeUpdate(`CREATE TABLE ${table} (id INTEGER PRIMARY KEY, value TEXT NOT NULL)`);
      await seed.close();
      execFileSync('docker', ['kill', down.container], { stdio: 'ignore' });
      stoppedContainers.add(down.container);
      await waitForPortClosed(down.port);

      const affectedRows: number[] = [];
      for (let id = 1; id <= 5; id++) {
        const client = await newClient();
        try {
          expect(client.boundEndpoint?.port).not.toBe(down.port);
          affectedRows.push(await client.executeUpdate(`INSERT INTO ${table} VALUES (${id}, 'outage-${id}')`));
        } finally {
          await client.close();
        }
      }
      expect(affectedRows).toEqual([1, 1, 1, 1, 1]);

      const verify = await newClient();
      try {
        await expect(verify.executeQuery(`SELECT id, value FROM ${table} ORDER BY id`)).resolves.toMatchObject({
          rows: [1, 2, 3, 4, 5].map((id) => ({ id, value: `outage-${id}` })),
        });
      } finally {
        await verify.close();
      }
    } finally {
      await seed.close().catch(() => undefined);
      if (stoppedContainers.has(down.container)) {
        execFileSync('docker', ['start', down.container], { stdio: 'ignore' });
        stoppedContainers.delete(down.container);
        await waitForPortOpen(down.port, 60_000);
      }
      const cleanup = await newClient();
      try {
        await cleanup.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
      } finally {
        await cleanup.close();
      }
    }
  }, 150_000);

  it('commits and rolls back transactions through a recovered PostgreSQL node', async () => {
    const table = `${prefix}_recovered_transactions`;
    const down = nodes[0];
    let recovered: OjpClient | undefined;
    try {
      const setup = await newClient(singleNodeUrl(down.port));
      try {
        await setup.executeUpdate(`CREATE TABLE ${table} (id INTEGER PRIMARY KEY)`);
      } finally {
        await setup.close();
      }

      execFileSync('docker', ['kill', down.container], { stdio: 'ignore' });
      stoppedContainers.add(down.container);
      await waitForPortClosed(down.port);
      const outage = await newClient();
      try {
        expect(outage.boundEndpoint?.port).not.toBe(down.port);
        expect(isEndpointHealthy({ host: 'localhost', port: down.port })).toBe(false);
      } finally {
        await outage.close();
      }

      execFileSync('docker', ['start', down.container], { stdio: 'ignore' });
      stoppedContainers.delete(down.container);
      await waitForPortOpen(down.port, 60_000);
      recovered = await newClient(singleNodeUrl(down.port));
      await recovered.withTransaction(async (client) => {
        await client.executeUpdate(`INSERT INTO ${table} VALUES (1)`);
      });
      expect(isEndpointHealthy({ host: 'localhost', port: down.port })).toBe(true);
      await expect(recovered.withTransaction(async (client) => {
        await client.executeUpdate(`INSERT INTO ${table} VALUES (2)`);
        throw new Error('force rollback');
      })).rejects.toThrow('force rollback');
      await expect(recovered.executeQuery(`SELECT id FROM ${table} ORDER BY id`)).resolves.toMatchObject({
        rows: [{ id: 1 }],
      });
    } finally {
      await recovered?.close();
      if (stoppedContainers.has(down.container)) {
        execFileSync('docker', ['start', down.container], { stdio: 'ignore' });
        stoppedContainers.delete(down.container);
        await waitForPortOpen(down.port, 60_000);
      }
      const cleanup = await newClient(singleNodeUrl(down.port));
      try {
        await cleanup.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
      } finally {
        await cleanup.close();
      }
    }
  }, 150_000);

  it('surfaces pool exhaustion without retrying on another PostgreSQL node', async () => {
    const alternate = nodes[0];
    const holder = await newClient(singleNodeUrl(alternate.port));
    const client = await newClient(clusterUrl([throttledNode, alternate]));
    expect(client.boundEndpoint?.port).toBe(throttledNode.port);

    const running = client.executeQuery('SELECT 42 AS held FROM pg_sleep(2)');
    try {
      await new Promise((resolve) => setTimeout(resolve, 200));
      await expect(client.executeQuery('SELECT 1 AS should_not_retry'))
        .rejects.toBeInstanceOf(OjpSqlError);
      expect(client.boundEndpoint?.port).toBe(throttledNode.port);
      await expect(running).resolves.toMatchObject({ rows: [{ held: 42 }] });
      await expect(client.executeQuery('SELECT 7 AS usable')).resolves.toMatchObject({
        rows: [{ usable: 7 }],
      });
      expect(client.boundEndpoint?.port).toBe(throttledNode.port);
    } finally {
      await running;
      await client.close();
      await holder.close();
    }
  }, 60_000);
});

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
