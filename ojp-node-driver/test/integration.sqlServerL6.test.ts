import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { OjpClient, OjpConnectionError } from '../src';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_SQLSERVER_L6_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const multinodeEnabled = enabled && process.env.OJP_ENABLE_MULTINODE_FAILOVER_TESTS === 'true';
const itIfMultinode = multinodeEnabled ? it : it.skip;
const connections = readConnectionFixtures('sqlserver_connections.csv', process.env.OJP_TEST_SQLSERVER_CSV);
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

describeIfEnabled.each(connections)('OjpClient - SQL Server L6: %s', (jdbcUrl, user, fixturePassword) => {
  const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';
  const url = ojpUrl(endpoint, jdbcUrl);
  const password = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? fixturePassword;
  const tempTable = `#ojp_node_l6_${Date.now()}_${Math.floor(Math.random() * 1_000_000)}`;
  const transactionTable = `${tempTable}_transaction`;
  const newClient = async (clientUrl = url, callTimeoutMs = 30_000): Promise<OjpClient> => {
    const client = new OjpClient(clientUrl, { user, password, callTimeoutMs });
    await client.connect();
    return client;
  };
  let client: OjpClient;

  beforeAll(async () => {
    client = await newClient();
  }, 30_000);

  afterAll(async () => {
    if (client) {
      await client.close();
    }
  }, 30_000);

  it('creates, populates, and queries a local temporary table across independent calls', async () => {
    await client.executeUpdate(`CREATE TABLE ${tempTable} (id INT PRIMARY KEY, value NVARCHAR(100))`);
    expect(await client.executeUpdate(`INSERT INTO ${tempTable} VALUES (?, ?)`, [1, 'same session']))
      .toBe(1);

    const result = await client.executeQuery(`SELECT id, value FROM ${tempTable} WHERE id = ?`, [1]);

    expect(result.columns.map((column) => column.toLowerCase())).toEqual(['id', 'value']);
    expect(result.rows).toEqual([{ id: 1, value: 'same session' }]);
  }, 30_000);

  it('keeps complex temporary-table DDL and data operations on the same SQL Server session', async () => {
    const table = `${tempTable}_complex`;
    await client.executeUpdate(
      `CREATE TABLE ${table} (id INT PRIMARY KEY, name NVARCHAR(100), amount DECIMAL(10,2))`,
    );
    await client.executeUpdate(
      `INSERT INTO ${table} VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)`,
      [1, 'Alice', '100.50', 2, 'Bob', '200.75', 3, 'Charlie', '150.25'],
    );
    expect(await client.executeUpdate(`UPDATE ${table} SET amount = amount + 50.00 WHERE id = ?`, [2]))
      .toBe(1);

    const result = await client.executeQuery(`SELECT id, name, amount FROM ${table} ORDER BY id`);

    expect(result.rows).toEqual([
      { id: 1, name: 'Alice', amount: '100.50' },
      { id: 2, name: 'Bob', amount: '250.75' },
      { id: 3, name: 'Charlie', amount: '150.25' },
    ]);
  }, 30_000);

  it('preserves local temporary tables and committed data across transaction boundaries', async () => {
    await client.executeUpdate(`CREATE TABLE ${transactionTable} (id INT PRIMARY KEY, value NVARCHAR(100))`);

    await client.startTransaction();
    await client.executeUpdate(`INSERT INTO ${transactionTable} VALUES (?, ?)`, [1, 'committed']);
    await client.commit();

    await client.startTransaction();
    await client.executeUpdate(`INSERT INTO ${transactionTable} VALUES (?, ?)`, [2, 'rolled back']);
    await client.rollback();

    const result = await client.executeQuery(`SELECT id, value FROM ${transactionTable} ORDER BY id`);
    expect(result.rows).toEqual([{ id: 1, value: 'committed' }]);
    await expect(client.executeQuery('SELECT 1 AS usable')).resolves.toMatchObject({
      rows: [{ usable: 1 }],
    });
  }, 30_000);

  itIfMultinode('surfaces loss of a sticky SQL Server session instead of routing it to another node', async () => {
    resetEndpointHealthForTests();
    const allEndpoints = nodes.map((node) => `localhost:${node.port}`).join(',');
    const boundClient = await newClient(ojpUrl(allEndpoints, jdbcUrl), 8_000);
    const boundEndpoint = boundClient.boundEndpoint;
    const boundNode = nodes.find((node) => node.port === boundEndpoint?.port);
    if (!boundNode) {
      await boundClient.close();
      throw new Error(`Connected endpoint ${boundEndpoint?.port ?? 'unknown'} is not in the test cluster`);
    }

    let nodeStopped = false;
    try {
      await boundClient.executeUpdate(
        `CREATE TABLE ${tempTable} (id INT PRIMARY KEY, value NVARCHAR(100))`,
      );
      await boundClient.executeUpdate(`INSERT INTO ${tempTable} VALUES (?, ?)`, [1, 'sticky']);

      execFileSync('docker', ['kill', boundNode.container], { stdio: 'ignore' });
      nodeStopped = true;
      await waitForPortClosed(boundNode.port);

      await expect(boundClient.executeQuery(`SELECT value FROM ${tempTable} WHERE id = ?`, [1]))
        .rejects.toMatchObject({
          name: 'OjpConnectionError',
          message: expect.stringMatching(/session bound to .* is no longer usable/i),
        });
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
