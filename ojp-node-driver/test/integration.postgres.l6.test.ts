import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'crypto';
import { OjpClient } from '../src';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_INTEGRATION_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const multinodeEnabled = enabled && process.env.OJP_ENABLE_MULTINODE_FAILOVER_TESTS === 'true';
const itIfMultinode = multinodeEnabled ? it : it.skip;
const connections = readConnectionFixtures('postgresql_connections.csv', process.env.OJP_TEST_POSTGRES_CSV);
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

describeIfEnabled.each(connections)('PostgreSQL L6 for %s', (jdbcUrl, user, fixturePassword) => {
  const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';
  const url = ojpUrl(endpoint, jdbcUrl);
  const options = { user, password: process.env.OJP_TEST_PASSWORD ?? fixturePassword };
  const tablePrefix = `ojp_node_l6_${randomBytes(6).toString('hex')}`;
  let client: OjpClient;

  beforeAll(async () => {
    client = new OjpClient(url, options);
    await client.connect();
  }, 60_000);

  afterAll(async () => {
    if (client) {
      await client.close();
    }
  }, 60_000);

  it('creates, populates, and queries a temporary table across independent calls', async () => {
    const table = `${tablePrefix}_temporary`;
    await client.executeUpdate(`CREATE TEMPORARY TABLE ${table} (id INTEGER PRIMARY KEY, value TEXT NOT NULL)`);
    expect(await client.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [1, 'same session'])).toBe(1);

    const result = await client.executeQuery(`SELECT id, value FROM ${table} WHERE id = ?`, [1]);

    expect(result.columns.map((column) => column.toLowerCase())).toEqual(['id', 'value']);
    expect(result.rows).toEqual([{ id: 1, value: 'same session' }]);
  }, 30_000);

  it('preserves PostgreSQL SET LOCAL state through the active transaction', async () => {
    await client.startTransaction();
    try {
      await client.executeUpdate(`SET LOCAL work_mem = '4MB'`);
      const result = await client.executeQuery('SHOW work_mem');
      expect(result.rows).toHaveLength(1);
      expect(result.rows[0].work_mem).toMatch(/^(4MB|4096kB)$/i);
    } finally {
      await client.rollback();
    }
  }, 30_000);

  it('keeps complex temporary-table DDL and data operations on the same session', async () => {
    const table = `${tablePrefix}_complex`;
    await client.executeUpdate(
      `CREATE TEMPORARY TABLE ${table} (id INTEGER PRIMARY KEY, name TEXT NOT NULL, amount INTEGER NOT NULL)`,
    );
    await client.executeUpdate(
      `INSERT INTO ${table} VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)`,
      [1, 'Alice', 100, 2, 'Bob', 200, 3, 'Charlie', 150],
    );
    expect(await client.executeUpdate(`UPDATE ${table} SET amount = amount + 50 WHERE id = ?`, [2])).toBe(1);

    const result = await client.executeQuery(`SELECT id, name, amount FROM ${table} ORDER BY id`);

    expect(result.rows).toEqual([
      { id: 1, name: 'Alice', amount: 100 },
      { id: 2, name: 'Bob', amount: 250 },
      { id: 3, name: 'Charlie', amount: 150 },
    ]);
  }, 30_000);

  it('preserves temporary tables across transaction boundaries and rolls back only uncommitted rows', async () => {
    const table = `${tablePrefix}_transactions`;
    await client.executeUpdate(`CREATE TEMPORARY TABLE ${table} (id INTEGER PRIMARY KEY, value TEXT NOT NULL)`);

    await client.startTransaction();
    await client.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [1, 'committed']);
    await client.commit();

    await client.startTransaction();
    await client.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [2, 'rolled back']);
    await client.rollback();

    const result = await client.executeQuery(`SELECT id, value FROM ${table} ORDER BY id`);
    expect(result.rows).toEqual([{ id: 1, value: 'committed' }]);
    await expect(client.executeQuery('SELECT 1 AS usable')).resolves.toMatchObject({
      rows: [{ usable: 1 }],
    });
  }, 30_000);

  it('clears local affinity on close, permits a new client, and rejects reuse of the closed client', async () => {
    const table = `${tablePrefix}_closed`;
    const disposable = new OjpClient(url, options);
    await disposable.connect();
    await disposable.executeUpdate(`CREATE TEMPORARY TABLE ${table} (id INTEGER)`);
    expect(disposable.boundEndpoint).toBeDefined();

    await disposable.close();

    expect(disposable.boundEndpoint).toBeUndefined();
    await expect(disposable.close()).resolves.toBeUndefined();
    await expect(disposable.executeQuery('SELECT 1')).rejects.toThrow(/client is closed/i);

    const verifier = new OjpClient(url, options);
    await verifier.connect();
    try {
      await expect(verifier.executeQuery('SELECT 1 AS usable')).resolves.toMatchObject({
        rows: [{ usable: 1 }],
      });
    } finally {
      await verifier.close();
    }
  }, 30_000);

  itIfMultinode('surfaces loss of a sticky PostgreSQL session without rerouting it', async () => {
    resetEndpointHealthForTests();
    const clusterUrl = ojpUrl(nodes.map((node) => `localhost:${node.port}`).join(','), jdbcUrl);
    const boundClient = new OjpClient(clusterUrl, { ...options, callTimeoutMs: 8_000 });
    await boundClient.connect();
    const boundEndpoint = boundClient.boundEndpoint;
    const boundNode = nodes.find((node) => node.port === boundEndpoint?.port);
    if (!boundNode) {
      await boundClient.close();
      throw new Error(`Connected endpoint ${boundEndpoint?.port ?? 'unknown'} is not in the test cluster`);
    }

    let nodeStopped = false;
    try {
      const table = `${tablePrefix}_sticky`;
      await boundClient.executeUpdate(`CREATE TEMPORARY TABLE ${table} (id INTEGER PRIMARY KEY, value TEXT)`);
      expect(await boundClient.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [1, 'sticky state'])).toBe(1);

      execFileSync('docker', ['kill', boundNode.container], { stdio: 'ignore' });
      nodeStopped = true;
      await waitForPortClosed(boundNode.port);

      await expect(boundClient.executeQuery(`SELECT value FROM ${table} WHERE id = ?`, [1]))
        .rejects.toMatchObject({
          name: 'OjpConnectionError',
          message: expect.stringMatching(/session bound to .* is no longer usable/i),
        });
      expect(boundClient.boundEndpoint?.port).toBe(boundNode.port);
    } finally {
      if (nodeStopped) {
        execFileSync('docker', ['start', boundNode.container], { stdio: 'ignore' });
        await waitForPortOpen(boundNode.port, 60_000);
      }
      await boundClient.close().catch(() => undefined);
      resetEndpointHealthForTests();
    }
  }, 90_000);
});

async function waitForPortClosed(port: number): Promise<void> {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const isOpen = await new Promise<boolean>((resolve) => {
      const socket = net.connect({ host: 'localhost', port }, () => {
        socket.end();
        resolve(true);
      });
      socket.on('error', () => resolve(false));
    });
    if (!isOpen) {
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
