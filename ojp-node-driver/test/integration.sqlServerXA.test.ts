/**
 * Real OJP server, multinode, and SQL Server XA integration. Enabled by
 * OJP_ENABLE_SQLSERVER_XA_TESTS=true and requires two or more dedicated OJP containers.
 */
import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'crypto';
import { OjpClient, OjpConnectionError } from '../src';
import { OjpXAConnection } from '../src/xa/OjpXAConnection';
import { OjpXADataSource } from '../src/xa/OjpXADataSource';
import { OjpXaError } from '../src/xa/OjpXaError';
import { Xid } from '../src/xa/xid';
import * as XA from '../src/xa/xaConstants';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_SQLSERVER_XA_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const connections = readConnectionFixtures('sqlserver_connections.csv', process.env.OJP_TEST_SQLSERVER_CSV);
const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',').filter(Boolean);
const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060,1061').split(',').map(Number);
const endpoints = process.env.OJP_TEST_SQLSERVER_XA_ADDRS
  ? process.env.OJP_TEST_SQLSERVER_XA_ADDRS.split(',')
  : ports.map((port) => `localhost:${port}`);

if (enabled && (containers.length < 2 || new Set(containers).size !== containers.length
    || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
  throw new Error('OJP_TEST_NODE_CONTAINERS must name at least two dedicated test containers');
}
if (enabled && (endpoints.length < 2 || endpoints.length > containers.length
    || new Set(endpoints).size !== endpoints.length
    || ports.length < endpoints.length || new Set(ports).size !== ports.length
    || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535)
    || endpoints.some((endpoint) => {
      const port = Number(endpoint.slice(endpoint.lastIndexOf(':') + 1));
      return !endpoint.startsWith('localhost:') || !Number.isInteger(port) || !ports.includes(port)
        || !containers[ports.indexOf(port)];
    }))) {
  throw new Error('SQL Server XA certification requires at least two distinct OJP endpoints and matching ports');
}

const nodes = endpoints.map((endpoint) => {
  const port = Number(endpoint.slice(endpoint.lastIndexOf(':') + 1));
  return { port, container: containers[ports.indexOf(port)] };
});

function newXid(): Xid {
  return {
    formatId: 0x4f4a50,
    globalTransactionId: randomBytes(16),
    branchQualifier: randomBytes(4),
  };
}

describeIfEnabled.each(connections)('OjpXAResource - SQL Server: %s', (jdbcUrl, user, fixturePassword) => {
  const password = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? fixturePassword;
  const clusterUrl = ojpUrl(endpoints.join(','), jdbcUrl);
  const tableName = `ojp_node_driver_sqlserver_xa_${Date.now()}`;
  let nextId = Date.now() % 1_000_000_000;
  const dataSource = new OjpXADataSource(clusterUrl, {
    user,
    password,
    healthCheckIntervalMs: 0,
    failoverRetryDelayMs: 0,
  });
  const stoppedContainers = new Set<string>();
  let xaConn: OjpXAConnection | undefined;

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

  const newRowId = (): number => nextId++;

  const stopBoundNode = async (client: OjpClient): Promise<void> => {
    const boundPort = client.boundEndpoint?.port;
    const node = nodes.find(({ port }) => port === boundPort);
    if (!node) {
      throw new Error(`No dedicated OJP container maps to bound endpoint port ${boundPort}`);
    }
    execFileSync('docker', ['kill', node.container], { stdio: 'ignore' });
    stoppedContainers.add(node.container);
    await waitForPortClosed(node.port);
  };

  beforeAll(async () => {
    await withClient((client) => client.executeUpdate(
      `CREATE TABLE ${tableName} (id INT PRIMARY KEY, label NVARCHAR(100))`,
    ));
  }, 30_000);

  afterAll(async () => {
    await withClient((client) => client.executeUpdate(`DROP TABLE IF EXISTS ${tableName}`));
  }, 30_000);

  afterEach(async () => {
    if (xaConn) {
      await xaConn.close().catch(() => undefined);
      xaConn = undefined;
    }
    for (const node of nodes) {
      if (stoppedContainers.has(node.container)) {
        execFileSync('docker', ['start', node.container], { stdio: 'ignore' });
        await waitForPortOpen(node.port, 60_000);
        stoppedContainers.delete(node.container);
      }
    }
  }, 90_000);

  it('creates an XA connection, disables local autocommit, and scopes session cleanup', async () => {
    let scopedClient: OjpClient | undefined;
    await dataSource.withXAConnection(async (connection) => {
      scopedClient = connection.getConnection();
      expect(connection.getXAResource()).toBe(connection.getXAResource());
      expect(scopedClient.boundEndpoint).toBeDefined();
      expect(scopedClient.getAutoCommit()).toBe(false);
      await expect(scopedClient.executeQuery('SELECT 1 AS xa_connection_open'))
        .resolves.toMatchObject({ rows: [{ xa_connection_open: 1 }] });
    });

    await expect(scopedClient!.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
  }, 30_000);

  it('commits a two-phase XA transaction and verifies its data through a separate session', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const id = newRowId();
    const xid = newXid();

    await xa.start(xid, XA.TMNOFLAGS);
    await xaConn.getConnection().executeUpdate(
      `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`,
      [id, 'two-phase-commit'],
    );
    await xa.end(xid, XA.TMSUCCESS);
    const prepareResult = await xa.prepare(xid);
    expect([XA.XA_OK, XA.XA_RDONLY]).toContain(prepareResult);
    if (prepareResult === XA.XA_OK) {
      await xa.commit(xid, false);
    }

    await expect(rowExists(id)).resolves.toBe(true);
  }, 30_000);

  it('rolls back a prepared XA transaction and verifies the row is absent', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const id = newRowId();
    const xid = newXid();

    await xa.start(xid, XA.TMNOFLAGS);
    await xaConn.getConnection().executeUpdate(
      `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`,
      [id, 'rolled-back'],
    );
    await xa.end(xid, XA.TMSUCCESS);
    expect(await xa.prepare(xid)).toBe(XA.XA_OK);
    await xa.rollback(xid);

    await expect(rowExists(id)).resolves.toBe(false);
  }, 30_000);

  it('sets, reads, and resets the XA transaction timeout', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();

    await expect(xa.setTransactionTimeout(300)).resolves.toBe(true);
    await expect(xa.getTransactionTimeout()).resolves.toBe(300);
    await expect(xa.setTransactionTimeout(0)).resolves.toBe(true);
    await expect(xa.getTransactionTimeout()).resolves.toBe(0);
  }, 30_000);

  it('commits an XA transaction with the one-phase optimization', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const id = newRowId();
    const xid = newXid();

    await xa.start(xid, XA.TMNOFLAGS);
    await xaConn.getConnection().executeUpdate(
      `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`,
      [id, 'one-phase-commit'],
    );
    await xa.end(xid, XA.TMSUCCESS);
    await xa.commit(xid, true);

    await expect(rowExists(id)).resolves.toBe(true);
  }, 30_000);

  it('recovers and resolves a prepared in-doubt branch', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const id = newRowId();
    const xid = newXid();
    let resolved = false;

    try {
      await xa.start(xid, XA.TMNOFLAGS);
      await xaConn.getConnection().executeUpdate(
        `INSERT INTO ${tableName} (id, label) VALUES (?, ?)`,
        [id, 'recoverable'],
      );
      await xa.end(xid, XA.TMSUCCESS);
      const prepareResult = await xa.prepare(xid);
      expect(prepareResult).toBe(XA.XA_OK);

      const recovered = await xa.recover(XA.TMSTARTRSCAN | XA.TMENDRSCAN);
      expect(recovered.some((candidate) =>
        candidate.formatId === xid.formatId
        && candidate.globalTransactionId.equals(xid.globalTransactionId)
        && candidate.branchQualifier.equals(xid.branchQualifier))).toBe(true);

      await xa.commit(xid, false);
      resolved = true;
      await expect(rowExists(id)).resolves.toBe(true);
    } finally {
      if (!resolved) {
        await xa.rollback(xid).catch(() => undefined);
      }
    }
  }, 30_000);

  it('identifies separate XA sessions connected to the same SQL Server resource manager', async () => {
    const sameEndpointDataSource = new OjpXADataSource(ojpUrl(endpoints[0], jdbcUrl), {
      user,
      password,
      healthCheckIntervalMs: 0,
    });
    xaConn = await sameEndpointDataSource.getXAConnection();
    const other = await sameEndpointDataSource.getXAConnection();
    try {
      await expect(xaConn.getXAResource().isSameRM(other.getXAResource())).resolves.toBe(true);
    } finally {
      await other.close();
    }
  }, 30_000);

  it('maps unknown transaction identifiers to XA errors', async () => {
    xaConn = await dataSource.getXAConnection();
    const xa = xaConn.getXAResource();
    const unknownXid = newXid();

    await expect(xa.commit(unknownXid, true)).rejects.toBeInstanceOf(OjpXaError);
    await expect(xa.forget(unknownXid)).rejects.toBeInstanceOf(OjpXaError);
  }, 30_000);

  it('rejects local transaction methods on an XA session', async () => {
    xaConn = await dataSource.getXAConnection();
    const connection = xaConn.getConnection();

    await expect(connection.startTransaction()).rejects.toThrow(/createXAResource/);
    await expect(connection.commit()).rejects.toThrow(/createXAResource/);
    await expect(connection.rollback()).rejects.toThrow(/createXAResource/);
  }, 30_000);

  it('does not retry xaStart on another node after the bound node is lost', async () => {
    xaConn = await dataSource.getXAConnection();
    const client = xaConn.getConnection();
    const oldPort = client.boundEndpoint!.port;
    const xid = newXid();

    await stopBoundNode(client);
    await expect(xaConn.getXAResource().start(xid, XA.TMNOFLAGS))
      .rejects.toMatchObject({ errorCode: XA.XAER_RMFAIL });
    expect(client.boundEndpoint?.port).toBe(oldPort);
  }, 120_000);

  it('surfaces bound-node loss as XAER_RMFAIL without rerouting an active XA branch', async () => {
    xaConn = await dataSource.getXAConnection();
    const client = xaConn.getConnection();
    const xa = xaConn.getXAResource();
    const boundPort = client.boundEndpoint!.port;
    const xid = newXid();

    await xa.start(xid, XA.TMNOFLAGS);
    await stopBoundNode(client);

    await expect(xa.end(xid, XA.TMSUCCESS)).rejects.toMatchObject({
      name: 'OjpXaError',
      errorCode: XA.XAER_RMFAIL,
    });
    expect(client.boundEndpoint?.port).toBe(boundPort);
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
