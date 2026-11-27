import { randomBytes } from 'node:crypto';
import { OjpClient, OjpConnectionError, OjpSqlError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { isEndpointHealthy, resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { h2TestNodes, startH2Node, stopH2Node, waitForPort, H2TestNode } from './h2MultinodeFixtures';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_H2_L8_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const nodes = enabled ? h2TestNodes() : [];
const connections = readConnectionFixtures('h2_multinode_connections.csv', process.env.OJP_TEST_H2_MULTINODE_CSV);
const throttledContainer = process.env.OJP_TEST_H2_L8_THROTTLED_NODE_CONTAINER;
const throttledPort = Number(process.env.OJP_TEST_H2_L8_THROTTLED_NODE_PORT);

if (enabled && (!throttledContainer || !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(throttledContainer)
    || !Number.isInteger(throttledPort) || throttledPort < 1 || throttledPort > 65535
    || nodes.some(({ port, container }) => port === throttledPort || container === throttledContainer))) {
  throw new Error('H2 L8 requires a distinct throttled OJP node container and port');
}

const throttledNode: H2TestNode = {
  host: 'localhost',
  port: throttledPort,
  container: throttledContainer ?? '',
};

describeIfEnabled.each(connections)('H2 L8 resilience and recovery for %s', (jdbcUrl, user, fixturePassword) => {
  const credentials = { user, password: process.env.OJP_TEST_H2_PASSWORD ?? fixturePassword };
  const prefix = `ojp_node_h2_l8_${randomBytes(6).toString('hex')}`;
  const clusterUrl = (endpoints: H2TestNode[] = nodes): string =>
    ojpUrl(endpoints.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
  const singleNodeUrl = (port: number): string => ojpUrl(`localhost:${port}`, jdbcUrl);
  const stoppedContainers = new Set<string>();

  const newClient = async (
    url = clusterUrl(),
    options: { loadBalancingStrategy?: 'least-connections' | 'round-robin'; clientThrottleMode?: 'off' } = {},
  ): Promise<OjpClient> => {
    const client = new OjpClient(url, {
      ...credentials,
      healthCheckIntervalMs: 0,
      failoverRetryDelayMs: 0,
      ...options,
    });
    await client.connect();
    return client;
  };
  const stop = async (node: H2TestNode): Promise<void> => {
    stopH2Node(node);
    stoppedContainers.add(node.container);
    await waitForPort(node.port, false);
  };
  const start = async (node: H2TestNode): Promise<void> => {
    await startH2Node(node);
    stoppedContainers.delete(node.container);
  };

  beforeEach(() => {
    resetEndpointHealthForTests();
    resetPoolCacheForTests();
  });

  afterEach(async () => {
    for (const node of [...nodes, throttledNode]) {
      if (stoppedContainers.has(node.container)) {
        await start(node);
      }
    }
    resetEndpointHealthForTests();
    resetPoolCacheForTests();
  }, 90_000);

  it('retries a stateless update on a healthy node and persists exactly one row', async () => {
    const table = `${prefix}_retry`;
    const setup = await newClient(singleNodeUrl(nodes[0].port));
    try {
      await setup.executeUpdate(`CREATE TABLE ${table} (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)`);
    } finally {
      await setup.close();
    }

    const client = await newClient();
    const failedNode = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    try {
      await stop(failedNode);
      await expect(client.executeUpdate(`INSERT INTO ${table} VALUES (1, 'retried')`)).resolves.toBe(1);
      expect(client.boundEndpoint?.port).not.toBe(failedNode.port);
      await expect(client.executeQuery(`SELECT id, label FROM ${table}`)).resolves.toMatchObject({
        rows: [{ ID: 1, LABEL: 'retried' }],
      });
    } finally {
      await client.close().catch(() => undefined);
      if (stoppedContainers.has(failedNode.container)) {
        await start(failedNode);
      }
      const cleanup = await newClient(singleNodeUrl(nodes[0].port));
      try {
        await cleanup.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
      } finally {
        await cleanup.close();
      }
    }
  }, 120_000);

  it('fails over a query when its initial endpoint is unavailable', async () => {
    const client = await newClient();
    const failedNode = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    try {
      await stop(failedNode);
      await expect(client.executeQuery('SELECT 9 AS recovered')).resolves.toMatchObject({
        rows: [{ RECOVERED: 9 }],
      });
      expect(client.boundEndpoint?.port).not.toBe(failedNode.port);
    } finally {
      await client.close().catch(() => undefined);
      if (stoppedContainers.has(failedNode.container)) {
        await start(failedNode);
      }
    }
  }, 120_000);

  it('surfaces sticky-session loss without rerouting temporary-table operations', async () => {
    const client = await newClient();
    const failedNode = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    const table = `${prefix}_sticky`;
    try {
      await client.executeUpdate(`CREATE LOCAL TEMPORARY TABLE ${table} (id INT PRIMARY KEY)`);
      await stop(failedNode);
      await expect(client.executeUpdate(`INSERT INTO ${table} VALUES (1)`))
        .rejects.toBeInstanceOf(OjpConnectionError);
      expect(client.boundEndpoint?.port).toBe(failedNode.port);
    } finally {
      await client.close().catch(() => undefined);
      if (stoppedContainers.has(failedNode.container)) {
        await start(failedNode);
      }
    }
  }, 90_000);

  it('does not replay operations from an active transaction after endpoint loss', async () => {
    const client = await newClient();
    const failedNode = nodes.find(({ port }) => port === client.boundEndpoint?.port)!;
    try {
      await client.startTransaction();
      await stop(failedNode);
      await expect(client.executeUpdate(`CREATE TABLE ${prefix}_transaction (id INT)`))
        .rejects.toBeInstanceOf(OjpConnectionError);
      expect(client.boundEndpoint?.port).toBe(failedNode.port);
    } finally {
      await client.close().catch(() => undefined);
      if (stoppedContainers.has(failedNode.container)) {
        await start(failedNode);
      }
    }
  }, 90_000);

  it('does not mark a node unhealthy or fail over on an H2 SQL error', async () => {
    const client = await newClient();
    const endpoint = client.boundEndpoint!;
    try {
      await expect(client.executeUpdate('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
      expect(isEndpointHealthy(endpoint)).toBe(true);
      expect(client.boundEndpoint).toEqual(endpoint);
      await expect(client.executeQuery('SELECT 1 AS usable')).resolves.toMatchObject({
        rows: [{ USABLE: 1 }],
      });
      expect(client.boundEndpoint).toEqual(endpoint);
    } finally {
      await client.close();
    }
  }, 60_000);

  it('routes around an outage, observes recovery, and redistributes new sessions', async () => {
    const down = nodes[0];
    await stop(down);
    try {
      const duringOutage = await newClient();
      try {
        expect(duringOutage.boundEndpoint?.port).not.toBe(down.port);
        expect(isEndpointHealthy(down)).toBe(false);
      } finally {
        await duringOutage.close();
      }

      await start(down);
      const recovered = await newClient();
      try {
        expect(isEndpointHealthy(down)).toBe(true);
        await expect(recovered.executeQuery('SELECT 7 AS recovered')).resolves.toMatchObject({
          rows: [{ RECOVERED: 7 }],
        });
      } finally {
        await recovered.close();
      }
    } finally {
      if (stoppedContainers.has(down.container)) {
        await start(down);
      }
    }

    const selected = new Set<number>();
    for (let index = 0; index < nodes.length * 3; index++) {
      const client = await newClient(clusterUrl(), { loadBalancingStrategy: 'round-robin' });
      selected.add(client.boundEndpoint!.port);
      await client.close();
    }
    expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
  }, 150_000);

  it('surfaces H2 pool exhaustion without retrying on another endpoint', async () => {
    const alternate = nodes[0];
    const holder = await newClient(singleNodeUrl(alternate.port));
    const overloaded = await newClient(clusterUrl([throttledNode, alternate]), {
      clientThrottleMode: 'off',
    });
    expect(overloaded.boundEndpoint?.port).toBe(throttledNode.port);

    const running = overloaded.executeQuery('SELECT SUM(MOD(X, 97)) AS held FROM SYSTEM_RANGE(1, 100000000)');
    try {
      await new Promise((resolve) => setTimeout(resolve, 100));
      await expect(overloaded.executeQuery('SELECT 1 AS should_not_retry')).rejects.toBeInstanceOf(OjpSqlError);
      expect(overloaded.boundEndpoint?.port).toBe(throttledNode.port);
      const completed = await running;
      expect(completed.rows).toHaveLength(1);
      expect(completed.rows[0].HELD).toBe(4799999433n);
      await expect(overloaded.executeQuery('SELECT 7 AS usable')).resolves.toMatchObject({
        rows: [{ USABLE: 7 }],
      });
      expect(overloaded.boundEndpoint?.port).toBe(throttledNode.port);
    } finally {
      await running;
      await overloaded.close();
      await holder.close();
    }
  }, 90_000);
});
