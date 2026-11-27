import { randomBytes } from 'node:crypto';
import { OjpClient, OjpConnectionError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { h2TestNodes, startH2Node, stopH2Node, waitForPort } from './h2MultinodeFixtures';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_H2_L6_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const nodes = enabled ? h2TestNodes() : [];
const connections = readConnectionFixtures('h2_multinode_connections.csv', process.env.OJP_TEST_H2_MULTINODE_CSV);

describeIfEnabled.each(connections)('H2 L6 session affinity for %s', (jdbcUrl, user, fixturePassword) => {
  const clusterUrl = ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
  const credentials = { user, password: process.env.OJP_TEST_H2_PASSWORD ?? fixturePassword };
  const prefix = `ojp_node_h2_l6_${randomBytes(6).toString('hex')}`;

  beforeEach(() => {
    resetEndpointHealthForTests();
    resetPoolCacheForTests();
  });

  afterEach(() => {
    resetEndpointHealthForTests();
    resetPoolCacheForTests();
  });

  it('keeps temporary-table state across independent calls and transaction boundaries', async () => {
    const client = new OjpClient(clusterUrl, credentials);
    const table = `${prefix}_temporary`;
    await client.connect();
    try {
      await client.executeUpdate(
        `CREATE LOCAL TEMPORARY TABLE ${table} (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)`,
      );
      expect(await client.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [1, 'committed'])).toBe(1);
      await client.startTransaction();
      await client.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [2, 'rolled back']);
      await client.rollback();

      await expect(client.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`))
        .resolves.toMatchObject({ rows: [{ ID: 1, LABEL: 'committed' }] });
      expect(client.boundEndpoint).toBeDefined();
      expect(await client.executeUpdate(`UPDATE ${table} SET label = ? WHERE id = ?`, ['updated', 1])).toBe(1);
      await expect(client.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [1]))
        .resolves.toMatchObject({ rows: [{ LABEL: 'updated' }] });
    } finally {
      await client.close();
    }
  }, 60_000);

  it('clears local affinity on close and rejects reuse of the terminated client', async () => {
    const client = new OjpClient(clusterUrl, credentials);
    await client.connect();
    await client.executeUpdate(`CREATE LOCAL TEMPORARY TABLE ${prefix}_closed (id INT)`);
    const originalEndpoint = client.boundEndpoint;
    expect(originalEndpoint).toBeDefined();

    await client.close();

    expect(client.boundEndpoint).toBeUndefined();
    await expect(client.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
    await expect(client.close()).resolves.toBeUndefined();

    const replacement = new OjpClient(clusterUrl, credentials);
    try {
      await replacement.connect();
      await expect(replacement.executeQuery('SELECT 1 AS usable')).resolves.toMatchObject({
        rows: [{ USABLE: 1 }],
      });
    } finally {
      await replacement.close();
    }
  }, 60_000);

  it('surfaces bound-node loss without rerouting temporary-table operations', async () => {
    const client = new OjpClient(clusterUrl, { ...credentials, callTimeoutMs: 8_000 });
    await client.connect();
    const bound = nodes.find(({ port }) => port === client.boundEndpoint?.port);
    if (!bound) {
      await client.close();
      throw new Error(`Connected endpoint ${client.boundEndpoint?.port ?? 'unknown'} is not in the H2 test cluster`);
    }
    let nodeStopped = false;
    try {
      const table = `${prefix}_sticky`;
      await client.executeUpdate(`CREATE LOCAL TEMPORARY TABLE ${table} (id INT PRIMARY KEY)`);
      await client.executeUpdate(`INSERT INTO ${table} VALUES (1)`);
      stopH2Node(bound);
      nodeStopped = true;
      await waitForPort(bound.port, false);

      await expect(client.executeQuery(`SELECT id FROM ${table} WHERE id = 1`))
        .rejects.toBeInstanceOf(OjpConnectionError);
      expect(client.boundEndpoint?.port).toBe(bound.port);
    } finally {
      if (nodeStopped) {
        await startH2Node(bound);
      }
      await client.close().catch(() => undefined);
      resetEndpointHealthForTests();
      resetPoolCacheForTests();
    }
  }, 90_000);
});
