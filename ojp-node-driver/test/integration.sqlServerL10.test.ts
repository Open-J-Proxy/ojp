/**
 * SQL Server L10 certification over mutual TLS. Requires two dedicated OJP servers
 * configured to require trusted client certificates.
 */
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import * as net from 'node:net';
import { join } from 'node:path';
import { OjpClient, OjpConnectionError, OjpSqlError } from '../src';
import { OjpXADataSource } from '../src/xa/OjpXADataSource';
import { OjpXaError } from '../src/xa/OjpXaError';
import { Xid } from '../src/xa/xid';
import * as XA from '../src/xa/xaConstants';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_SQLSERVER_L10_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const connections = readConnectionFixtures('sqlserver_connections.csv', process.env.OJP_TEST_SQLSERVER_CSV);
const certDir = process.env.OJP_TEST_MTLS_CERT_DIR;
const endpoints = (process.env.OJP_TEST_MTLS_NODE_ENDPOINTS ?? 'localhost:10591,localhost:10592')
  .split(',').map((endpoint) => endpoint.trim());
const containers = (process.env.OJP_TEST_MTLS_NODE_CONTAINERS ?? '').split(',').filter(Boolean);

if (enabled && (!certDir || endpoints.length < 2 || new Set(endpoints).size !== endpoints.length
    || containers.length !== endpoints.length || new Set(containers).size !== containers.length
    || endpoints.some((endpoint) => !/^localhost:\d+$/.test(endpoint))
    || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
  throw new Error('SQL Server L10 requires a certificate directory and two distinct dedicated mTLS OJP servers');
}

const nodes = endpoints.map((endpoint, index) => ({
  endpoint,
  port: Number(endpoint.slice(endpoint.lastIndexOf(':') + 1)),
  container: containers[index],
}));

function tlsOptions(identity: 'trusted' | 'untrusted' | 'none') {
  if (!certDir) {
    throw new Error('OJP_TEST_MTLS_CERT_DIR is required for SQL Server L10');
  }
  const options = { rootCert: readFileSync(join(certDir, 'server-cert.pem')) };
  if (identity === 'none') {
    return { tls: options };
  }
  const name = identity === 'trusted' ? 'client' : 'untrusted-client';
  return {
    tls: {
      ...options,
      privateKey: readFileSync(join(certDir, `${name}-key.pem`)),
      certChain: readFileSync(join(certDir, `${name}-cert.pem`)),
    },
  };
}

function newXid(): Xid {
  return {
    formatId: 0x4f4a50,
    globalTransactionId: randomBytes(16),
    branchQualifier: randomBytes(4),
  };
}

describeIfEnabled.each(connections)('OjpClient - SQL Server L10 mTLS: %s', (jdbcUrl, user, fixturePassword) => {
  const password = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? fixturePassword;
  const clusterUrl = ojpUrl(endpoints.join(','), jdbcUrl);
  const stoppedContainers = new Set<string>();

  const newClient = async (url = clusterUrl, identity: 'trusted' | 'untrusted' | 'none' = 'trusted') => {
    const client = new OjpClient(url, {
      user,
      password,
      healthCheckIntervalMs: 0,
      failoverRetryDelayMs: 0,
      ...tlsOptions(identity),
    });
    await client.connect();
    return client;
  };

  const withClient = async <T>(
    operation: (client: OjpClient) => Promise<T>,
    url = clusterUrl,
    identity: 'trusted' | 'untrusted' | 'none' = 'trusted',
  ): Promise<T> => {
    const client = await newClient(url, identity);
    try {
      return await operation(client);
    } finally {
      await client.close().catch(() => undefined);
    }
  };

  const stopNode = async (port: number): Promise<void> => {
    const node = nodes.find((candidate) => candidate.port === port);
    if (!node) {
      throw new Error(`No dedicated mTLS OJP container maps to port ${port}`);
    }
    execFileSync('docker', ['kill', node.container], { stdio: 'ignore' });
    stoppedContainers.add(node.container);
    await waitForPortClosed(node.port);
  };

  const startStoppedNodes = async (): Promise<void> => {
    for (const node of nodes) {
      if (stoppedContainers.has(node.container)) {
        execFileSync('docker', ['start', node.container], { stdio: 'ignore' });
        await waitForPortOpen(node.port, 60_000);
        stoppedContainers.delete(node.container);
      }
    }
  };

  afterEach(async () => {
    await startStoppedNodes();
  }, 90_000);

  it('runs SQL, transaction, and XA commit/rollback/recovery through every mTLS endpoint', async () => {
    for (const endpoint of endpoints) {
      await withClient(async (client) => {
        await expect(client.executeQuery('SELECT 1 AS authenticated'))
          .resolves.toMatchObject({ rows: [{ authenticated: 1 }] });
      }, ojpUrl(endpoint, jdbcUrl));
    }

    const table = `ojp_node_l10_${Date.now()}`;
    await withClient((client) => client.executeUpdate(
      `CREATE TABLE ${table} (id INT PRIMARY KEY, label NVARCHAR(80))`,
    ));

    try {
      const client = await newClient();
      const endpointBeforeSqlError = client.boundEndpoint?.port;
      try {
        await expect(client.executeUpdate('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
        expect(client.boundEndpoint?.port).toBe(endpointBeforeSqlError);

        await expect(client.withTransaction(async (connection) => {
          expect(await connection.executeUpdate(
            `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'local-commit'],
          )).toBe(1);
        })).resolves.toBeUndefined();

        await expect(client.withTransaction(async (connection) => {
          await connection.executeUpdate(
            `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'local-rollback'],
          );
          throw new Error('rollback expected');
        })).rejects.toThrow('rollback expected');
      } finally {
        await client.close().catch(() => undefined);
      }

      const xaDataSource = new OjpXADataSource(clusterUrl, {
        user,
        password,
        healthCheckIntervalMs: 0,
        ...tlsOptions('trusted'),
      });
      const committedXid = newXid();
      const rolledBackXid = newXid();
      const recoveryXid = newXid();
      await xaDataSource.withXAConnection(async (connection) => {
        const client = connection.getConnection();
        const xa = connection.getXAResource();
        await xa.start(committedXid, XA.TMNOFLAGS);
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [3, 'xa-commit']);
        await xa.end(committedXid, XA.TMSUCCESS);
        expect(await xa.prepare(committedXid)).toBe(XA.XA_OK);
        expect(await xa.recover(XA.TMSTARTRSCAN | XA.TMENDRSCAN)).toEqual(
          expect.arrayContaining([expect.objectContaining({
            formatId: committedXid.formatId,
            globalTransactionId: committedXid.globalTransactionId,
            branchQualifier: committedXid.branchQualifier,
          })]),
        );
        await xa.commit(committedXid, false);

        await xa.start(rolledBackXid, XA.TMNOFLAGS);
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [4, 'xa-rollback']);
        await xa.end(rolledBackXid, XA.TMSUCCESS);
        await xa.rollback(rolledBackXid);

        await xa.start(recoveryXid, XA.TMNOFLAGS);
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [5, 'xa-recovery']);
        await xa.end(recoveryXid, XA.TMSUCCESS);
        expect(await xa.prepare(recoveryXid)).toBe(XA.XA_OK);
      });

      await xaDataSource.withXAConnection(async (connection) => {
        const recovered = await connection.getXAResource().recover(XA.TMSTARTRSCAN | XA.TMENDRSCAN);
        expect(recovered.some((candidate) =>
          candidate.formatId === recoveryXid.formatId
          && candidate.globalTransactionId.equals(recoveryXid.globalTransactionId)
          && candidate.branchQualifier.equals(recoveryXid.branchQualifier))).toBe(true);
        await connection.getXAResource().commit(recoveryXid, false);
      });

      await withClient(async (verification) => {
        const result = await verification.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`);
        expect(result.rows).toEqual([
          { id: 1, label: 'local-commit' },
          { id: 3, label: 'xa-commit' },
          { id: 5, label: 'xa-recovery' },
        ]);
      });
    } finally {
      await withClient((client) => client.executeUpdate(`DROP TABLE IF EXISTS ${table}`));
    }
  }, 180_000);

  it('fails over stateless work, reuses recovered nodes, and never reroutes sticky sessions', async () => {
    const table = `ojp_node_l10_failover_${Date.now()}`;
    await withClient((client) => client.executeUpdate(
      `CREATE TABLE ${table} (id INT PRIMARY KEY, label NVARCHAR(80))`,
    ));

    try {
      const stateless = await newClient();
      const failedPort = stateless.boundEndpoint!.port;
      try {
        await stopNode(failedPort);
        await expect(stateless.executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'recovered'],
        )).resolves.toBe(1);
        expect(stateless.boundEndpoint?.port).not.toBe(failedPort);
        await expect(stateless.executeQuery(`SELECT label FROM ${table} WHERE id = ?`, [1]))
          .resolves.toMatchObject({ rows: [{ label: 'recovered' }] });
      } finally {
        await stateless.close().catch(() => undefined);
        await startStoppedNodes();
      }

      const selected = new Set<number>();
      for (let index = 0; index < nodes.length * 2; index++) {
        await withClient(async (client) => {
          selected.add(client.boundEndpoint!.port);
          await expect(client.executeQuery('SELECT 1 AS healthy'))
            .resolves.toMatchObject({ rows: [{ healthy: 1 }] });
        });
      }
      expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));

      const sticky = await newClient();
      const stickyPort = sticky.boundEndpoint!.port;
      try {
        await sticky.executeUpdate('CREATE TABLE #ojp_node_l10_sticky (id INT PRIMARY KEY)');
        await stopNode(stickyPort);
        await expect(sticky.executeUpdate('INSERT INTO #ojp_node_l10_sticky VALUES (1)'))
          .rejects.toBeInstanceOf(OjpConnectionError);
        expect(sticky.boundEndpoint?.port).toBe(stickyPort);
      } finally {
        await sticky.close().catch(() => undefined);
      }
    } finally {
      await startStoppedNodes();
      await withClient((client) => client.executeUpdate(`DROP TABLE IF EXISTS ${table}`));
    }
  }, 240_000);

  it('surfaces XA branch loss as XAER_RMFAIL without rerouting the active branch', async () => {
    const dataSource = new OjpXADataSource(clusterUrl, {
      user,
      password,
      healthCheckIntervalMs: 0,
      ...tlsOptions('trusted'),
    });
    const connection = await dataSource.getXAConnection();
    const client = connection.getConnection();
    const xa = connection.getXAResource();
    const boundPort = client.boundEndpoint!.port;
    try {
      const xid = newXid();
      await xa.start(xid, XA.TMNOFLAGS);
      await client.executeQuery('SELECT 1 AS xa_active');
      await stopNode(boundPort);
      await expect(xa.end(xid, XA.TMSUCCESS)).rejects.toMatchObject({
        name: 'OjpXaError',
        errorCode: XA.XAER_RMFAIL,
      });
      expect(client.boundEndpoint?.port).toBe(boundPort);
    } finally {
      await startStoppedNodes();
      await connection.close().catch(() => undefined);
    }
  }, 120_000);

  it('rejects a connection when the required client certificate is absent', async () => {
    await expect(newClient(clusterUrl, 'none')).rejects.toBeInstanceOf(OjpConnectionError);
  }, 60_000);

  it('rejects a client certificate that the server does not trust', async () => {
    await expect(newClient(clusterUrl, 'untrusted')).rejects.toBeInstanceOf(OjpConnectionError);
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
