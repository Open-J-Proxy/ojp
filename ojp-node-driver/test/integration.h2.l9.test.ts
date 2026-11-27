import { randomBytes } from 'node:crypto';
import { OjpClient, OjpConnectionError, OjpXAConnection, OjpXADataSource, OjpXaError, Xid, xidEquals, xaConstants } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { h2TestNodes, startH2Node, stopH2Node, waitForPort } from './h2MultinodeFixtures';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_H2_L9_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const nodes = enabled ? h2TestNodes() : [];
const connections = readConnectionFixtures('h2_multinode_connections.csv', process.env.OJP_TEST_H2_MULTINODE_CSV);

function newXid(): Xid {
  return {
    formatId: 0x4f4a50,
    globalTransactionId: randomBytes(16),
    branchQualifier: randomBytes(4),
  };
}

describeIfEnabled.each(connections)('H2 L9 XA lifecycle for %s', (jdbcUrl, user, fixturePassword) => {
  const credentials = { user, password: process.env.OJP_TEST_H2_PASSWORD ?? fixturePassword };
  const clusterUrl = ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
  const singleNodeUrl = (port: number): string => ojpUrl(`localhost:${port}`, jdbcUrl);
  const table = `ojp_node_h2_xa_${randomBytes(6).toString('hex')}`;
  const dataSource = new OjpXADataSource(clusterUrl, {
    ...credentials,
    healthCheckIntervalMs: 0,
    failoverRetryDelayMs: 0,
  });
  const stoppedContainers = new Set<string>();
  let xaConnection: OjpXAConnection | undefined;

  const withClient = async <T>(operation: (client: OjpClient) => Promise<T>): Promise<T> => {
    const client = new OjpClient(clusterUrl, credentials);
    await client.connect();
    try {
      return await operation(client);
    } finally {
      await client.close();
    }
  };
  const rowExists = async (id: number): Promise<boolean> => withClient(async (client) => {
    const result = await client.executeQuery(`SELECT id FROM ${table} WHERE id = ?`, [id]);
    return result.rows.length === 1;
  });
  const stopBoundNode = async (client: OjpClient): Promise<number> => {
    const node = nodes.find(({ port }) => port === client.boundEndpoint?.port);
    if (!node) {
      throw new Error(`No H2 test container maps to bound endpoint ${client.boundEndpoint?.port ?? 'unknown'}`);
    }
    stopH2Node(node);
    stoppedContainers.add(node.container);
    await waitForPort(node.port, false);
    return node.port;
  };
  const startStoppedNodes = async (): Promise<void> => {
    for (const node of nodes) {
      if (stoppedContainers.has(node.container)) {
        await startH2Node(node);
        stoppedContainers.delete(node.container);
      }
    }
  };

  beforeAll(async () => {
    await withClient((client) => client.executeUpdate(
      `CREATE TABLE ${table} (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)`,
    ));
  }, 30_000);

  afterAll(async () => {
    await withClient((client) => client.executeUpdate(`DROP TABLE IF EXISTS ${table}`));
  }, 30_000);

  afterEach(async () => {
    await xaConnection?.close().catch(() => undefined);
    xaConnection = undefined;
    await startStoppedNodes();
    await withClient((client) => client.executeUpdate(`DELETE FROM ${table}`));
    resetEndpointHealthForTests();
    resetPoolCacheForTests();
  }, 90_000);

  it('creates a fresh XA session and scopes cleanup through the data-source helper', async () => {
    let scopedClient: OjpClient | undefined;
    await dataSource.withXAConnection(async (connection) => {
      scopedClient = connection.getConnection();
      expect(connection.getXAResource()).toBe(connection.getXAResource());
      expect(scopedClient.getAutoCommit()).toBe(false);
      await expect(scopedClient.executeQuery('SELECT 1 AS xa_open')).resolves.toMatchObject({
        rows: [{ XA_OPEN: 1 }],
      });
    });

    await expect(scopedClient!.executeQuery('SELECT 1')).rejects.toBeInstanceOf(OjpConnectionError);
  }, 30_000);

  it('commits a prepared two-phase branch and verifies the row independently', async () => {
    xaConnection = await dataSource.getXAConnection();
    const xa = xaConnection.getXAResource();
    const id = 1;
    const xid = newXid();

    await xa.start(xid, xaConstants.TMNOFLAGS);
    await expect(xaConnection.getConnection().executeUpdate(
      `INSERT INTO ${table} VALUES (?, ?)`, [id, 'two-phase-commit'],
    )).resolves.toBe(1);
    await xa.end(xid, xaConstants.TMSUCCESS);
    expect(await xa.prepare(xid)).toBe(xaConstants.XA_OK);
    await xa.commit(xid, false);

    await expect(rowExists(id)).resolves.toBe(true);
    await expect(withClient((client) => client.executeQuery(
      `SELECT label FROM ${table} WHERE id = ?`, [id],
    ))).resolves.toMatchObject({ rows: [{ LABEL: 'two-phase-commit' }] });
  }, 30_000);

  it('rolls back a prepared branch and confirms no data was committed', async () => {
    xaConnection = await dataSource.getXAConnection();
    const xa = xaConnection.getXAResource();
    const id = 2;
    const xid = newXid();

    await xa.start(xid, xaConstants.TMNOFLAGS);
    await xaConnection.getConnection().executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [id, 'rollback']);
    await xa.end(xid, xaConstants.TMSUCCESS);
    expect(await xa.prepare(xid)).toBe(xaConstants.XA_OK);
    await xa.rollback(xid);

    await expect(rowExists(id)).resolves.toBe(false);
  }, 30_000);

  it('commits with the one-phase optimization and verifies the stored value', async () => {
    xaConnection = await dataSource.getXAConnection();
    const xa = xaConnection.getXAResource();
    const id = 3;
    const xid = newXid();

    await xa.start(xid, xaConstants.TMNOFLAGS);
    await xaConnection.getConnection().executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [id, 'one-phase']);
    await xa.end(xid, xaConstants.TMSUCCESS);
    await xa.commit(xid, true);

    await expect(withClient((client) => client.executeQuery(
      `SELECT label FROM ${table} WHERE id = ?`, [id],
    ))).resolves.toMatchObject({ rows: [{ LABEL: 'one-phase' }] });
    try {
      await xa.forget(xid);
    } catch (error) {
      expect(error).toBeInstanceOf(OjpXaError);
    }
  }, 30_000);

  it('recovers the complete Xid of a prepared branch and resolves it exactly once', async () => {
    xaConnection = await dataSource.getXAConnection();
    const xa = xaConnection.getXAResource();
    const id = 4;
    const xid = newXid();
    let resolved = false;

    try {
      await xa.start(xid, xaConstants.TMNOFLAGS);
      await xaConnection.getConnection().executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [id, 'recoverable']);
      await xa.end(xid, xaConstants.TMSUCCESS);
      expect(await xa.prepare(xid)).toBe(xaConstants.XA_OK);

      const recovered = await xa.recover(xaConstants.TMSTARTRSCAN | xaConstants.TMENDRSCAN);
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

  it('reports timeout support accurately and restores the default timeout', async () => {
    xaConnection = await dataSource.getXAConnection();
    const xa = xaConnection.getXAResource();

    const timeoutSet = await xa.setTransactionTimeout(30);
    await expect(xa.getTransactionTimeout()).resolves.toBe(timeoutSet ? 30 : 0);
    await xa.setTransactionTimeout(0);
    await expect(xa.getTransactionTimeout()).resolves.toBe(0);
  }, 30_000);

  it('reuses one XA session across sequential one- and two-phase branches', async () => {
    xaConnection = await dataSource.getXAConnection();
    const xa = xaConnection.getXAResource();
    const client = xaConnection.getConnection();
    const firstXid = newXid();
    const secondXid = newXid();

    await xa.start(firstXid, xaConstants.TMNOFLAGS);
    await client.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [5, 'sequential-one-phase']);
    await xa.end(firstXid, xaConstants.TMSUCCESS);
    await xa.commit(firstXid, true);

    await xa.start(secondXid, xaConstants.TMNOFLAGS);
    await client.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [6, 'sequential-two-phase']);
    await xa.end(secondXid, xaConstants.TMSUCCESS);
    expect(await xa.prepare(secondXid)).toBe(xaConstants.XA_OK);
    await xa.commit(secondXid, false);

    await expect(withClient((verifier) => verifier.executeQuery(
      `SELECT id, label FROM ${table} WHERE id IN (?, ?) ORDER BY id`, [5, 6],
    ))).resolves.toMatchObject({
      rows: [
        { ID: 5, LABEL: 'sequential-one-phase' },
        { ID: 6, LABEL: 'sequential-two-phase' },
      ],
    });
  }, 30_000);

  it('reports resource-manager identity for itself and a separate H2 session', async () => {
    const sameEndpointDataSource = new OjpXADataSource(singleNodeUrl(nodes[0].port), credentials);
    xaConnection = await sameEndpointDataSource.getXAConnection();
    const other = await sameEndpointDataSource.getXAConnection();
    try {
      const resource = xaConnection.getXAResource();
      await expect(resource.isSameRM(resource)).resolves.toBe(true);
      await expect(resource.isSameRM(other.getXAResource())).resolves.toBe(false);
    } finally {
      await other.close();
    }
  }, 30_000);

  it('maps an unknown Xid to XAER_RMERR without changing the bound server', async () => {
    xaConnection = await dataSource.getXAConnection();
    const resource = xaConnection.getXAResource();
    const boundPort = xaConnection.getConnection().boundEndpoint?.port;

    await expect(resource.commit(newXid(), true)).rejects.toMatchObject({
      name: 'OjpXaError',
      errorCode: xaConstants.XAER_RMERR,
    });
    expect(xaConnection.getConnection().boundEndpoint?.port).toBe(boundPort);
  }, 30_000);

  it('rejects local transaction demarcation on an XA session', async () => {
    xaConnection = await dataSource.getXAConnection();
    const client = xaConnection.getConnection();

    await expect(client.startTransaction()).rejects.toThrow(/createXAResource/);
    await expect(client.commit()).rejects.toThrow(/createXAResource/);
    await expect(client.rollback()).rejects.toThrow(/createXAResource/);
  }, 30_000);

  it('does not retry xaStart on another node after its bound node is lost', async () => {
    xaConnection = await dataSource.getXAConnection();
    const client = xaConnection.getConnection();
    const oldPort = client.boundEndpoint!.port;
    const node = nodes.find(({ port }) => port === oldPort)!;

    await stopBoundNode(client);
    await expect(xaConnection.getXAResource().start(newXid(), xaConstants.TMNOFLAGS))
      .rejects.toMatchObject({ errorCode: xaConstants.XAER_RMFAIL });
    expect(client.boundEndpoint?.port).toBe(oldPort);
    expect(stoppedContainers.has(node.container)).toBe(true);
  }, 120_000);

  it('surfaces bound-node loss as XAER_RMFAIL without rerouting an active branch', async () => {
    xaConnection = await dataSource.getXAConnection();
    const client = xaConnection.getConnection();
    const xa = xaConnection.getXAResource();
    const xid = newXid();
    const id = 7;
    const boundPort = client.boundEndpoint!.port;

    await xa.start(xid, xaConstants.TMNOFLAGS);
    await client.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [id, 'must-not-be-replayed']);
    await stopBoundNode(client);

    await expect(xa.end(xid, xaConstants.TMSUCCESS)).rejects.toMatchObject({
      name: 'OjpXaError',
      errorCode: xaConstants.XAER_RMFAIL,
    });
    expect(client.boundEndpoint?.port).toBe(boundPort);
    await expect(rowExists(id)).resolves.toBe(false);
  }, 120_000);
});
