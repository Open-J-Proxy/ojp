import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import * as net from 'node:net';
import { join } from 'node:path';
import { OjpClient, OjpConnectionError, OjpSqlError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { OjpXADataSource } from '../src/xa/OjpXADataSource';
import { Xid } from '../src/xa/xid';
import * as XA from '../src/xa/xaConstants';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_L10_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const certDir = process.env.OJP_TEST_MTLS_CERT_DIR;
const endpoints = (process.env.OJP_TEST_ORACLE_L10_MTLS_ENDPOINTS ?? 'localhost:10591,localhost:10592')
  .split(',').map((endpoint) => endpoint.trim());
const containers = (process.env.OJP_TEST_ORACLE_L10_MTLS_CONTAINERS ?? '').split(',').filter(Boolean);
const ports = endpoints.map((endpoint) => Number(endpoint.slice(endpoint.lastIndexOf(':') + 1)));
if (enabled && (!certDir || endpoints.length < 2 || new Set(endpoints).size !== endpoints.length
    || containers.length !== endpoints.length || new Set(containers).size !== containers.length
    || endpoints.some((endpoint) => !/^localhost:\d+$/.test(endpoint))
    || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535)
    || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
  throw new Error('Oracle L10 requires a certificate directory and two dedicated mTLS OJP servers');
}
const nodes = endpoints.map((endpoint, index) => ({ endpoint, port: ports[index], container: containers[index] }));

function tlsOptions(identity: 'trusted' | 'untrusted' | 'none') {
  if (!certDir) {
    throw new Error('OJP_TEST_MTLS_CERT_DIR is required for Oracle L10');
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

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L10 mTLS for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const password = process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword;
    const clusterUrl = ojpUrl(endpoints.join(','), jdbcUrl);
    const prefix = `ojp_node_oracle_l10_${randomBytes(6).toString('hex')}`;
    const stopped = new Set<string>();

    const newClient = async (
      url = clusterUrl,
      identity: 'trusted' | 'untrusted' | 'none' = 'trusted',
      strategy?: 'least-connections' | 'round-robin',
    ) => {
      const client = new OjpClient(url, {
        user, password, healthCheckIntervalMs: 0, failoverRetryDelayMs: 0,
        loadBalancingStrategy: strategy, ...tlsOptions(identity),
      });
      await client.connect();
      return client;
    };

    const withClient = async <T>(
      fn: (client: OjpClient) => Promise<T>,
      url = clusterUrl,
      identity: 'trusted' | 'untrusted' | 'none' = 'trusted',
    ): Promise<T> => {
      const client = await newClient(url, identity);
      try {
        return await fn(client);
      } finally {
        await client.close().catch(() => undefined);
      }
    };

    const startStoppedNodes = async (): Promise<void> => {
      for (const node of nodes) {
        if (stopped.has(node.container)) {
          execFileSync('docker', ['start', node.container], { stdio: 'ignore' });
          await waitForPortOpen(node.port, 60_000);
          stopped.delete(node.container);
        }
      }
    };

    const stopNode = async (port: number): Promise<void> => {
      const node = nodes.find((candidate) => candidate.port === port);
      if (!node) {
        throw new Error(`No dedicated Oracle mTLS OJP container maps to port ${port}`);
      }
      execFileSync('docker', ['kill', node.container], { stdio: 'ignore' });
      stopped.add(node.container);
      await waitForPortClosed(node.port);
    };

    afterEach(async () => {
      await startStoppedNodes();
      resetPoolCacheForTests();
      resetEndpointHealthForTests();
    }, 90_000);

    it('authenticates to each required-mTLS endpoint, distinguishes SQL errors, and verifies transaction outcomes', async () => {
      for (const endpoint of endpoints) {
        await withClient(async (client) => {
          await expect(client.executeQuery('SELECT dummy AS "DUMMY" FROM dual'))
            .resolves.toMatchObject({ rows: [{ DUMMY: 'X' }] });
        }, ojpUrl(endpoint, jdbcUrl));
      }

      const table = `${prefix}_sql`;
      await withClient((client) => client.executeUpdate(
        `CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY, label VARCHAR2(80) NOT NULL)`,
      ));
      try {
        const client = await newClient();
        const bound = client.boundEndpoint?.port;
        try {
          await expect(client.executeUpdate('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
          expect(client.boundEndpoint?.port).toBe(bound);
          await expect(client.withTransaction(async (connection) => {
            expect(await connection.executeUpdate(
              `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'local commit'],
            )).toBe(1);
          })).resolves.toBeUndefined();
          await expect(client.withTransaction(async (connection) => {
            await connection.executeUpdate(
              `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'local rollback'],
            );
            throw new Error('rollback expected');
          })).rejects.toThrow('rollback expected');
        } finally {
          await client.close();
        }

        const xaSource = new OjpXADataSource(clusterUrl, {
          user, password, healthCheckIntervalMs: 0, ...tlsOptions('trusted'),
        });
        const commitXid = newXid();
        const rollbackXid = newXid();
        await xaSource.withXAConnection(async (connection) => {
          const xa = connection.getXAResource();
          const sql = connection.getConnection();
          await xa.start(commitXid, XA.TMNOFLAGS);
          await sql.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [3, 'XA commit']);
          await xa.end(commitXid, XA.TMSUCCESS);
          expect(await xa.prepare(commitXid)).toBe(XA.XA_OK);
          expect((await xa.recover(XA.TMSTARTRSCAN | XA.TMENDRSCAN))
            .some((xid) => xid.formatId === commitXid.formatId
              && xid.globalTransactionId.equals(commitXid.globalTransactionId)
              && xid.branchQualifier.equals(commitXid.branchQualifier))).toBe(true);
          await xa.commit(commitXid, false);

          await xa.start(rollbackXid, XA.TMNOFLAGS);
          await sql.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [4, 'XA rollback']);
          await xa.end(rollbackXid, XA.TMSUCCESS);
          await xa.rollback(rollbackXid);
        });
        await withClient(async (client) => {
          await expect(client.executeQuery(
            `SELECT id AS "ID", label AS "LABEL" FROM ${table} ORDER BY id`,
          )).resolves.toMatchObject({
            rows: [{ ID: '1', LABEL: 'local commit' }, { ID: '3', LABEL: 'XA commit' }],
          });
        });
      } finally {
        await withClient((client) => client.executeUpdate(`DROP TABLE ${table}`));
      }
    }, 180_000);

    it('fails over stateless Oracle work after a node outage, then refuses sticky-session rerouting', async () => {
      const table = `${prefix}_failover`;
      await withClient((client) => client.executeUpdate(
        `CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY, label VARCHAR2(80) NOT NULL)`,
      ));
      try {
        const stateless = await newClient();
        const oldPort = stateless.boundEndpoint!.port;
        try {
          await stopNode(oldPort);
          expect(await stateless.executeUpdate(
            `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'retried once'],
          )).toBe(1);
          expect(stateless.boundEndpoint?.port).not.toBe(oldPort);
          await expect(stateless.executeQuery(
            `SELECT label AS "LABEL" FROM ${table} WHERE id = ?`, [1],
          )).resolves.toMatchObject({ rows: [{ LABEL: 'retried once' }] });
        } finally {
          await stateless.close().catch(() => undefined);
          await startStoppedNodes();
        }

        const selected = new Set<number>();
        for (let index = 0; index < nodes.length * 2; index++) {
          const client = await newClient(clusterUrl, 'trusted', 'round-robin');
          try {
            selected.add(client.boundEndpoint!.port);
            await expect(client.executeQuery('SELECT dummy AS "DUMMY" FROM dual'))
              .resolves.toMatchObject({ rows: [{ DUMMY: 'X' }] });
          } finally {
            await client.close();
          }
        }
        expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));

        const sticky = await newClient();
        const stickyPort = sticky.boundEndpoint!.port;
        const stickyTable = `${prefix}_gtt`;
        try {
          await sticky.executeUpdate(
            `CREATE GLOBAL TEMPORARY TABLE ${stickyTable} (id NUMBER(10) PRIMARY KEY) ON COMMIT PRESERVE ROWS`,
          );
          await sticky.executeUpdate(`INSERT INTO ${stickyTable} (id) VALUES (1)`);
          await stopNode(stickyPort);
          await expect(sticky.executeUpdate(`INSERT INTO ${stickyTable} (id) VALUES (2)`))
            .rejects.toBeInstanceOf(OjpConnectionError);
          expect(sticky.boundEndpoint?.port).toBe(stickyPort);
        } finally {
          await sticky.close().catch(() => undefined);
          await startStoppedNodes();
          await withClient((client) => client.executeUpdate(`DROP TABLE ${stickyTable}`));
        }
      } finally {
        await startStoppedNodes();
        await withClient((client) => client.executeUpdate(`DROP TABLE ${table}`));
      }
    }, 240_000);

    it('surfaces active XA branch loss without rerouting or replaying Oracle work', async () => {
      const table = `${prefix}_xa_loss`;
      await withClient((client) => client.executeUpdate(
        `CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY)`,
      ));
      const dataSource = new OjpXADataSource(clusterUrl, {
        user, password, healthCheckIntervalMs: 0, ...tlsOptions('trusted'),
      });
      let connection: Awaited<ReturnType<typeof dataSource.getXAConnection>> | undefined;
      try {
        connection = await dataSource.getXAConnection();
        const client = connection.getConnection();
        const xa = connection.getXAResource();
        const boundPort = client.boundEndpoint!.port;
        const xid = newXid();
        await xa.start(xid, XA.TMNOFLAGS);
        expect(await client.executeUpdate(`INSERT INTO ${table} (id) VALUES (1)`)).toBe(1);
        await stopNode(boundPort);
        await expect(xa.end(xid, XA.TMSUCCESS)).rejects.toMatchObject({
          name: 'OjpXaError',
          errorCode: XA.XAER_RMFAIL,
        });
        expect(client.boundEndpoint?.port).toBe(boundPort);
      } finally {
        await startStoppedNodes();
        await connection?.close().catch(() => undefined);
        await withClient(async (client) => {
          await expect(client.executeQuery(`SELECT id FROM ${table}`)).resolves.toMatchObject({ rows: [] });
          await client.executeUpdate(`DROP TABLE ${table}`);
        });
      }
    }, 120_000);

    it('rejects missing and untrusted client certificates at every required-mTLS endpoint', async () => {
      for (const endpoint of endpoints) {
        const url = ojpUrl(endpoint, jdbcUrl);
        resetPoolCacheForTests();
        await expect(newClient(url, 'none')).rejects.toBeInstanceOf(OjpConnectionError);
        resetPoolCacheForTests();
        await expect(newClient(url, 'untrusted')).rejects.toBeInstanceOf(OjpConnectionError);
      }
    }, 90_000);
  },
);

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
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Port ${port} did not close within 5000ms`);
}

async function waitForPortOpen(port: number, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
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
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  throw new Error(`Port ${port} did not reopen within ${timeoutMs}ms`);
}
