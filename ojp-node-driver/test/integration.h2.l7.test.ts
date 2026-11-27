import { randomBytes } from 'node:crypto';
import { OjpClient } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { isEndpointHealthy, resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { h2TestNodes, startH2Node, stopH2Node, waitForPort } from './h2MultinodeFixtures';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_H2_L7_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const nodes = enabled ? h2TestNodes() : [];
const connections = readConnectionFixtures('h2_multinode_connections.csv', process.env.OJP_TEST_H2_MULTINODE_CSV);

describeIfEnabled.each(connections)('H2 L7 multinode operations for %s', (jdbcUrl, user, fixturePassword) => {
  const credentials = { user, password: process.env.OJP_TEST_H2_PASSWORD ?? fixturePassword };
  const prefix = `ojp_node_h2_l7_${randomBytes(6).toString('hex')}`;
  const clusterUrl = (): string => ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
  const singleNodeUrl = (port: number): string => ojpUrl(`localhost:${port}`, jdbcUrl);
  const connectClient = async (
    url = clusterUrl(),
    options: { loadBalancingStrategy?: 'least-connections' | 'round-robin' } = {},
  ): Promise<{ client: OjpClient; session: Awaited<ReturnType<OjpClient['connect']>> }> => {
    const client = new OjpClient(url, {
      ...credentials,
      healthCheckIntervalMs: 0,
      failoverRetryDelayMs: 0,
      ...options,
    });
    return { client, session: await client.connect() };
  };
  const newClient = async (
    url = clusterUrl(),
    options: { loadBalancingStrategy?: 'least-connections' | 'round-robin' } = {},
  ): Promise<OjpClient> => (await connectClient(url, options)).client;

  beforeEach(() => {
    resetEndpointHealthForTests();
    resetPoolCacheForTests();
  });

  afterEach(() => {
    resetEndpointHealthForTests();
    resetPoolCacheForTests();
  });

  it('selects a less-loaded healthy endpoint for a new session', async () => {
    const first = await newClient();
    const second = await newClient();
    try {
      expect(first.boundEndpoint).toBeDefined();
      expect(second.boundEndpoint).toBeDefined();
      expect(second.boundEndpoint).not.toEqual(first.boundEndpoint);
      await expect(second.executeQuery('SELECT 1 AS healthy')).resolves.toMatchObject({
        rows: [{ HEALTHY: 1 }],
      });
    } finally {
      await second.close();
      await first.close();
    }
  }, 60_000);

  it('distributes equal-load sessions across all endpoints with round-robin', async () => {
    const selected = new Set<number>();
    for (let index = 0; index < nodes.length * 2; index++) {
      const client = await newClient(clusterUrl(), { loadBalancingStrategy: 'round-robin' });
      selected.add(client.boundEndpoint!.port);
      await client.close();
    }
    expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
  }, 90_000);

  it('detects an unavailable endpoint and propagates cluster health to new sessions', async () => {
    const down = nodes[0];
    stopH2Node(down);
    try {
      await waitForPort(down.port, false);
      const { client, session } = await connectClient();
      try {
        expect(client.boundEndpoint?.port).not.toBe(down.port);
        expect(session.clusterHealth).toContain(`localhost:${down.port}(DOWN)`);
        await expect(client.executeQuery('SELECT 1 AS healthy')).resolves.toMatchObject({
          rows: [{ HEALTHY: 1 }],
        });
      } finally {
        await client.close();
      }
    } finally {
      await startH2Node(down);
    }
  }, 90_000);

  it('probes a recovered endpoint and redistributes new sessions to it', async () => {
    const down = nodes[0];
    stopH2Node(down);
    try {
      await waitForPort(down.port, false);
      const duringOutage = await newClient();
      try {
        expect(duringOutage.boundEndpoint?.port).not.toBe(down.port);
      } finally {
        await duringOutage.close();
      }
    } finally {
      await startH2Node(down);
    }

    const selected = new Set<number>();
    for (let index = 0; index < nodes.length * 3; index++) {
      const client = await newClient(clusterUrl(), { loadBalancingStrategy: 'round-robin' });
      selected.add(client.boundEndpoint!.port);
      await client.close();
    }
    expect(isEndpointHealthy({ host: down.host, port: down.port })).toBe(true);
    expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));
  }, 120_000);

  it('reuses cached connHash metadata without exposing session plumbing to callers', async () => {
    const { client: first, session: firstSession } = await connectClient();
    await first.close();

    const { client: second, session: secondSession } = await connectClient();
    try {
      expect(secondSession.connHash).toBe(firstSession.connHash);
      expect(secondSession.sessionUUID).toBe('');
      await expect(second.executeQuery('SELECT 2 AS cached')).resolves.toMatchObject({
        rows: [{ CACHED: 2 }],
      });
    } finally {
      await second.close();
    }
  }, 60_000);

  it('reconnects and retries once after a restarted server loses its cached pool', async () => {
    const node = nodes[0];
    const table = `${prefix}_reconnect`;
    const setup = await newClient(singleNodeUrl(node.port));
    try {
      await setup.executeUpdate(`CREATE TABLE ${table} (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)`);
      await setup.executeUpdate(`INSERT INTO ${table} VALUES (1, 'before restart')`);
    } finally {
      await setup.close();
    }

    stopH2Node(node);
    try {
      await waitForPort(node.port, false);
    } finally {
      await startH2Node(node);
    }

    const recovered = await newClient(singleNodeUrl(node.port));
    try {
      await expect(recovered.executeUpdate(`INSERT INTO ${table} VALUES (2, 'retried once')`)).resolves.toBe(1);
      await expect(recovered.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`))
        .resolves.toMatchObject({
          rows: [
            { ID: 1, LABEL: 'before restart' },
            { ID: 2, LABEL: 'retried once' },
          ],
        });
    } finally {
      await recovered.close();
      const cleanup = await newClient(singleNodeUrl(node.port));
      try {
        await cleanup.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
      } finally {
        await cleanup.close();
      }
    }
  }, 120_000);
});
