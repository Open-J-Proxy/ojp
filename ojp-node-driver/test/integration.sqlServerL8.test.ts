/**
 * SQL Server multinode failover and recovery operations. Requires two or more
 * dedicated OJP server containers when OJP_ENABLE_SQLSERVER_L8_TESTS=true.
 */
import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { OjpClient, OjpConnectionError, OjpSqlError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_SQLSERVER_L8_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const connections = readConnectionFixtures('sqlserver_connections.csv', process.env.OJP_TEST_SQLSERVER_CSV);
const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',');
const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060,1061').split(',').map(Number);

if (enabled && (containers.length < 2 || new Set(containers).size !== containers.length
    || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
  throw new Error('OJP_TEST_NODE_CONTAINERS must name at least two dedicated test containers');
}
if (enabled && (ports.length < 2 || new Set(ports).size !== ports.length
    || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535))) {
  throw new Error('OJP_TEST_NODE_PORTS must contain at least two distinct TCP ports');
}

const nodes = ports.map((port, index) => ({ port, container: containers[index] }));

describeIfEnabled.each(connections)('OjpClient - SQL Server L8: %s', (jdbcUrl, user, fixturePassword) => {
  const password = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? fixturePassword;
  const clusterUrl = (): string => ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
  const newClient = async (
    options: { loadBalancingStrategy?: 'least-connections' | 'round-robin' } = {},
  ): Promise<OjpClient> => {
    const client = new OjpClient(clusterUrl(), {
      user,
      password,
      healthCheckIntervalMs: 0,
      failoverRetryDelayMs: 0,
      ...options,
    });
    await client.connect();
    return client;
  };
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
  }, 90_000);

  it('retries an eligible stateless operation on a healthy node after its bound node stops', async () => {
    const client = await newClient();
    const bound = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    const table = `ojp_node_l8_failover_${Date.now()}`;
    try {
      await client.executeUpdate(`CREATE TABLE ${table} (id INT PRIMARY KEY, label NVARCHAR(80))`);
      execFileSync('docker', ['kill', bound.container], { stdio: 'ignore' });
      stoppedContainers.add(bound.container);
      await waitForPortClosed(bound.port);

      await expect(client.executeUpdate(`INSERT INTO ${table} VALUES (1, N'recovered')`)).resolves.toBe(1);
      await expect(client.executeQuery(`SELECT id, label FROM ${table}`)).resolves.toMatchObject({
        rows: [{ id: 1, label: 'recovered' }],
      });
      expect(client.boundEndpoint?.port).not.toBe(bound.port);
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

  it('retries a query stream before delivering its first result block', async () => {
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

  it('surfaces sticky-session loss without rerouting its operation', async () => {
    const client = await newClient();
    const bound = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    await client.executeUpdate('CREATE TABLE #ojp_l8_sticky (id INT PRIMARY KEY)');
    execFileSync('docker', ['kill', bound.container], { stdio: 'ignore' });
    stoppedContainers.add(bound.container);
    await waitForPortClosed(bound.port);

    try {
      await expect(client.executeUpdate('INSERT INTO #ojp_l8_sticky VALUES (1)'))
        .rejects.toBeInstanceOf(OjpConnectionError);
      expect(client.boundEndpoint?.port).toBe(bound.port);
    } finally {
      await client.close().catch(() => undefined);
    }
  }, 90_000);

  it('does not retry or reroute an operation from an active transaction', async () => {
    const client = await newClient();
    const bound = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    await client.startTransaction();
    execFileSync('docker', ['kill', bound.container], { stdio: 'ignore' });
    stoppedContainers.add(bound.container);
    await waitForPortClosed(bound.port);

    try {
      await expect(client.executeUpdate('CREATE TABLE #ojp_l8_transaction (id INT)'))
        .rejects.toBeInstanceOf(OjpConnectionError);
      expect(client.boundEndpoint?.port).toBe(bound.port);
    } finally {
      await client.close().catch(() => undefined);
    }
  }, 90_000);

  it('does not fail over for a SQL error and remains usable on the same endpoint', async () => {
    const client = await newClient();
    const bound = client.boundEndpoint?.port;
    try {
      await expect(client.executeUpdate('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
      expect(client.boundEndpoint?.port).toBe(bound);
      await expect(client.executeQuery('SELECT 1 AS usable')).resolves.toMatchObject({
        rows: [{ usable: 1 }],
      });
      expect(client.boundEndpoint?.port).toBe(bound);
    } finally {
      await client.close();
    }
  }, 60_000);

  it('avoids a failed node for new sessions and redistributes sessions after recovery', async () => {
    const seed = await newClient();
    const down = nodes.find(({ port }) => port === seed.boundEndpoint?.port)!;
    await seed.close();
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
      const client = await newClient({ loadBalancingStrategy: 'round-robin' });
      selected.add(client.boundEndpoint!.port);
      await client.close();
    }
    expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
  }, 150_000);
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
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Port ${port} is still open after stopping its OJP server`);
}

async function waitForPortOpen(port: number, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const open = await new Promise<boolean>((resolve) => {
      const socket = net.connect({ host: 'localhost', port }, () => {
        socket.end();
        resolve(true);
      });
      socket.on('error', () => resolve(false));
    });
    if (open) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Port ${port} did not reopen within ${timeoutMs}ms`);
}
