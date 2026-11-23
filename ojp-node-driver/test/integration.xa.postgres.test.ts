/**
 * Real OJP server, PostgreSQL, and multinode XA integration. Enabled by
 * OJP_ENABLE_XA_TESTS=true. PostgreSQL must have max_prepared_transactions enabled.
 */
import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'node:crypto';
import { OjpClient, OjpConnectionError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { OjpXAConnection } from '../src/xa/OjpXAConnection';
import { OjpXADataSource } from '../src/xa/OjpXADataSource';
import { OjpXaError } from '../src/xa/OjpXaError';
import { Xid, xidEquals } from '../src/xa/xid';
import * as XA from '../src/xa/xaConstants';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_XA_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const connections = readConnectionFixtures('postgresql_connections.csv', process.env.OJP_TEST_POSTGRES_CSV);
const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',').filter(Boolean);
const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060,1061').split(',').map(Number);

if (enabled && (containers.length < 2 || new Set(containers).size !== containers.length
    || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
  throw new Error('OJP_ENABLE_XA_TESTS requires at least two distinct OJP_TEST_NODE_CONTAINERS');
}
if (enabled && (ports.length < containers.length || new Set(ports).size !== ports.length
    || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535))) {
  throw new Error('OJP_TEST_NODE_PORTS must contain a distinct valid port for every XA test container');
}

const nodes = containers.map((container, index) => ({ container, port: ports[index] }));

function newXid(): Xid {
  return {
    formatId: 0x4f4a50,
    globalTransactionId: randomBytes(16),
    branchQualifier: randomBytes(4),
  };
}

describeIfEnabled.each(connections)('OjpXAResource - PostgreSQL L9: %s', (jdbcUrl, user, fixturePassword) => {
  const password = process.env.OJP_TEST_PASSWORD ?? fixturePassword;
  const clusterUrl = ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
  const tableName = `ojp_node_driver_xa_test_${randomBytes(6).toString('hex')}`;
  let nextId = 1;
  let xaConn: OjpXAConnection | undefined;
  const stoppedContainers = new Set<string>();
  const dataSource = new OjpXADataSource(clusterUrl, {
    user,
    password,
    healthCheckIntervalMs: 0,
    failoverRetryDelayMs: 0,
  });

  const withClient = async <T>(operation: (client: OjpClient) => Promise<T>): Promise<T> => {
    const client = new OjpClient(clusterUrl, { user, password, healthCheckIntervalMs: 0 });
    await client.connect();
    try {
      return await operation(client);
    } finally {
      await client.close();
    }
  };

  const rowExists = async (id: number): Promise<boolean> => withClient(async (client) => {
    const result = await client.executeQuery(`SELECT id FROM ${tableName} WHERE id = ?`, [id]);
    return result.rows.length === 1;
  });

  const stopBoundNode = async (client: OjpClient): Promise<number> => {
    const port = client.boundEndpoint?.port;
    const node = nodes.find(({ port: nodePort }) => nodePort === port);
    if (!node) {
      throw new Error(`No dedicated OJP test container maps to bound endpoint port ${port}`);
    }
    execFileSync('docker', ['kill', node.container], { stdio: 'ignore' });
    stoppedContainers.add(node.container);
    await waitForPortClosed(node.port);
    return node.port;
  };

  beforeAll(async () => {
    await withClient((client) => client.executeUpdate(
      `CREATE TABLE ${tableName} (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)`,
    ));
  }, 30_000);

  afterAll(async () => {
    await withClient((client) => client.executeUpdate(`DROP TABLE IF EXISTS ${tableName}`));
  }, 30_000);

  afterEach(async () => {
    await xaConn?.close().catch(() => undefined);
    xaConn = undefined;
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

  it('creates a fresh XA session, caches its resource, and scopes cleanup with the data-source helper', async () => {
    let scopedClient: OjpClient | undefined;
    await dataSource.withXAConnection(async (connection) => {
      scopedClient = connection.getConnection();
      expect(connection.getXAResource()).toBe(connection.getXAResource());
      expect(scopedClient.getAutoCommit()).toBe(false);
      await expect(scopedClient.executeQuery('SELECT 1 AS xa_open'))
        .resolves.toMatchObject({ rows: [{ xa_open: 1 }] });
    });

    await expect(scopedClient!.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
  }, 30_000);

  it('commits a two-phase transaction and verifies the row through an independent session', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const id = nextId++;
    const xid = newXid();

    await xa.start(xid, XA.TMNOFLAGS);
    await expect(xaConn.getConnection().executeUpdate(
      `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [id, 'two-phase-commit'],
    )).resolves.toBe(1);
    await xa.end(xid, XA.TMSUCCESS);
    const prepareResult = await xa.prepare(xid);
    expect(prepareResult).toBe(XA.XA_OK);
    await xa.commit(xid, false);

    await expect(rowExists(id)).resolves.toBe(true);
  }, 30_000);

  it('rolls back a prepared transaction and verifies the row is absent', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const id = nextId++;
    const xid = newXid();

    await xa.start(xid, XA.TMNOFLAGS);
    await expect(xaConn.getConnection().executeUpdate(
      `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [id, 'two-phase-rollback'],
    )).resolves.toBe(1);
    await xa.end(xid, XA.TMSUCCESS);
    expect(await xa.prepare(xid)).toBe(XA.XA_OK);
    await xa.rollback(xid);

    await expect(rowExists(id)).resolves.toBe(false);
  }, 30_000);

  it('commits with the one-phase optimization and verifies the stored value', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const id = nextId++;
    const xid = newXid();

    await xa.start(xid, XA.TMNOFLAGS);
    await expect(xaConn.getConnection().executeUpdate(
      `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [id, 'one-phase-commit'],
    )).resolves.toBe(1);
    await xa.end(xid, XA.TMSUCCESS);
    await xa.commit(xid, true);

    await withClient(async (client) => {
      await expect(client.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [id]))
        .resolves.toMatchObject({ rows: [{ label: 'one-phase-commit' }] });
    });

    try {
      await xa.forget(xid);
    } catch (error) {
      expect(error).toBeInstanceOf(OjpXaError);
    }
  }, 30_000);

  it('recovers the complete Xid of a prepared branch and resolves it exactly once', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const id = nextId++;
    const xid = newXid();
    let resolved = false;

    try {
      await xa.start(xid, XA.TMNOFLAGS);
      await xaConn.getConnection().executeUpdate(
        `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [id, 'recoverable'],
      );
      await xa.end(xid, XA.TMSUCCESS);
      expect(await xa.prepare(xid)).toBe(XA.XA_OK);

      const recovered = await xa.recover(XA.TMSTARTRSCAN | XA.TMENDRSCAN);
      expect(recovered.some((candidate) => xidEquals(candidate, xid))).toBe(true);

      await xa.commit(xid, false);
      resolved = true;
      await expect(rowExists(id)).resolves.toBe(true);
    } finally {
      if (!resolved) {
        await xa.rollback(xid).catch(() => undefined);
      }
    }
  }, 30_000);

  it('reports PostgreSQL XA timeout support accurately and restores the default', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();

    await expect(xa.setTransactionTimeout(300)).resolves.toBe(false);
    await expect(xa.getTransactionTimeout()).resolves.toBe(0);
    await expect(xa.setTransactionTimeout(0)).resolves.toBe(false);
    await expect(xa.getTransactionTimeout()).resolves.toBe(0);
  }, 30_000);

  it('reuses one XA session across sequential one- and two-phase branches', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const client = xaConn.getConnection();
    const firstId = nextId++;
    const secondId = nextId++;
    const firstXid = newXid();
    const secondXid = newXid();

    await xa.start(firstXid, XA.TMNOFLAGS);
    await client.executeUpdate(
      `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [firstId, 'sequential-one-phase'],
    );
    await xa.end(firstXid, XA.TMSUCCESS);
    await xa.commit(firstXid, true);

    await xa.start(secondXid, XA.TMNOFLAGS);
    await client.executeUpdate(
      `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [secondId, 'sequential-two-phase'],
    );
    await xa.end(secondXid, XA.TMSUCCESS);
    expect(await xa.prepare(secondXid)).toBe(XA.XA_OK);
    await xa.commit(secondXid, false);

    await withClient(async (verifier) => {
      await expect(verifier.executeQuery(
        `SELECT id, label FROM ${tableName} WHERE id IN (?, ?) ORDER BY id`, [firstId, secondId],
      )).resolves.toMatchObject({
        rows: [
          { id: firstId, label: 'sequential-one-phase' },
          { id: secondId, label: 'sequential-two-phase' },
        ],
      });
    });
  }, 30_000);

  it('reports XA resource identity for a resource itself and a separate PostgreSQL session', async () => {
    const sameEndpointDataSource = new OjpXADataSource(
      ojpUrl(`localhost:${nodes[0].port}`, jdbcUrl),
      { user, password, healthCheckIntervalMs: 0 },
    );
    xaConn = await sameEndpointDataSource.getXAConnection();
    const other = await sameEndpointDataSource.getXAConnection();
    try {
      const resource = xaConn.getXAResource();
      await expect(resource.isSameRM(resource)).resolves.toBe(true);
      await expect(resource.isSameRM(other.getXAResource())).resolves.toBe(false);
    } finally {
      await other.close();
    }
  }, 30_000);

  it('maps an unknown Xid to XAER_RMERR without changing the bound server', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const boundPort = xaConn.getConnection().boundEndpoint?.port;
    const caught = xa.commit(newXid(), true);

    await expect(caught).rejects.toBeInstanceOf(OjpXaError);
    await expect(caught).rejects.toMatchObject({ errorCode: XA.XAER_RMERR });
    expect(xaConn.getConnection().boundEndpoint?.port).toBe(boundPort);
  }, 30_000);

  it('rejects local transaction demarcation on an XA session', async () => {
    xaConn = await dataSource.getXAConnection();
    const connection = xaConn.getConnection();

    await expect(connection.startTransaction()).rejects.toThrow(/createXAResource/);
    await expect(connection.commit()).rejects.toThrow(/createXAResource/);
    await expect(connection.rollback()).rejects.toThrow(/createXAResource/);
  }, 30_000);

  it('recreates an XA session and retries xaStart on a different healthy node', async () => {
    xaConn = await dataSource.getXAConnection();
    const client = xaConn.getConnection();
    const oldPort = client.boundEndpoint!.port;
    const xid = newXid();

    await stopBoundNode(client);
    await expect(xaConn.getXAResource().start(xid, XA.TMNOFLAGS)).resolves.toBeUndefined();
    expect(client.boundEndpoint?.port).not.toBe(oldPort);

    const xa = xaConn.getXAResource();
    await xa.end(xid, XA.TMSUCCESS);
    await xa.rollback(xid);
  }, 120_000);

  it('surfaces bound-node loss as XAER_RMFAIL without rerouting an active branch', async () => {
    xaConn = await dataSource.getXAConnection();
    const client = xaConn.getConnection();
    const xa = xaConn.getXAResource();
    const xid = newXid();
    const id = nextId++;
    const boundPort = client.boundEndpoint!.port;

    await xa.start(xid, XA.TMNOFLAGS);
    await client.executeUpdate(
      `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [id, 'must-not-be-replayed'],
    );
    await stopBoundNode(client);

    await expect(xa.end(xid, XA.TMSUCCESS)).rejects.toMatchObject({
      name: 'OjpXaError',
      errorCode: XA.XAER_RMFAIL,
    });
    expect(client.boundEndpoint?.port).toBe(boundPort);
    await expect(rowExists(id)).resolves.toBe(false);
  }, 120_000);
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
