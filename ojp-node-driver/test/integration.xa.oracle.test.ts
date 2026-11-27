import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'crypto';
import { OjpClient, OjpConnectionError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { OjpXADataSource } from '../src/xa/OjpXADataSource';
import { Xid, xidEquals } from '../src/xa/xid';
import * as XA from '../src/xa/xaConstants';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_L9_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',').filter(Boolean);
const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060').split(',').map(Number);
if (enabled && (containers.length < 2 || ports.length < containers.length
    || new Set(containers).size !== containers.length || new Set(ports).size !== ports.length)) {
  throw new Error('Oracle L9 requires at least two distinct OJP test containers and ports');
}
const nodes = containers.map((container, index) => ({ container, port: ports[index] }));

function newXid(): Xid {
  return {
    formatId: 0x4f4a50,
    globalTransactionId: randomBytes(16),
    branchQualifier: randomBytes(4),
  };
}

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L9 XA for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const password = process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword;
    const clusterUrl = ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
    const table = `ojp_node_oracle_xa_${randomBytes(6).toString('hex')}`;
    const dataSource = new OjpXADataSource(clusterUrl, { user, password, healthCheckIntervalMs: 0 });
    const withClient = async <T>(fn: (client: OjpClient) => Promise<T>): Promise<T> => {
      const client = new OjpClient(clusterUrl, { user, password, healthCheckIntervalMs: 0 });
      await client.connect();
      try {
        return await fn(client);
      } finally {
        await client.close();
      }
    };
    const readRows = async (): Promise<Record<string, unknown>[]> => withClient(async (client) =>
      (await client.executeQuery(`SELECT id AS "ID", label AS "LABEL" FROM ${table} ORDER BY id`)).rows);
    const stopped = new Set<string>();

    beforeAll(async () => {
      await withClient((client) => client.executeUpdate(
        `CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY, label VARCHAR2(100) NOT NULL)`,
      ));
    }, 60_000);

    beforeEach(async () => {
      await withClient((client) => client.executeUpdate(`DELETE FROM ${table}`));
    }, 30_000);

    afterAll(async () => {
      await withClient((client) => client.executeUpdate(`DROP TABLE ${table}`));
    }, 60_000);

    afterEach(async () => {
      for (const node of nodes) {
        if (stopped.has(node.container)) {
          execFileSync('docker', ['start', node.container], { stdio: 'ignore' });
          await waitForPortOpen(node.port, 60_000);
          stopped.delete(node.container);
        }
      }
      await withClient((client) => client.executeUpdate(`DELETE FROM ${table}`));
      resetPoolCacheForTests();
      resetEndpointHealthForTests();
    }, 90_000);

    it('scopes XA connection cleanup and exposes one cached resource per connection', async () => {
      let scoped: OjpClient | undefined;
      await dataSource.withXAConnection(async (connection) => {
        scoped = connection.getConnection();
        expect(connection.getXAResource()).toBe(connection.getXAResource());
        expect(scoped.getAutoCommit()).toBe(false);
        await expect(scoped.executeQuery('SELECT dummy AS "DUMMY" FROM dual'))
          .resolves.toMatchObject({ rows: [{ DUMMY: 'X' }] });
      });
      await expect(scoped!.executeQuery('SELECT 1 FROM dual')).rejects.toBeInstanceOf(OjpConnectionError);
    }, 30_000);

    it('commits Oracle two-phase work and verifies the row independently', async () => {
      const id = 1;
      const xid = newXid();
      await dataSource.withXAConnection(async (connection) => {
        const xa = connection.getXAResource();
        await xa.start(xid, XA.TMNOFLAGS);
        expect(await connection.getConnection().executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [id, 'two-phase commit'],
        )).toBe(1);
        await xa.end(xid, XA.TMSUCCESS);
        expect(await xa.prepare(xid)).toBe(XA.XA_OK);
        await xa.commit(xid, false);
      });
      await expect(readRows()).resolves.toEqual([{ ID: `${id}`, LABEL: 'two-phase commit' }]);
    }, 60_000);

    it('rolls back a prepared branch and leaves no row behind', async () => {
      const id = 2;
      const xid = newXid();
      await dataSource.withXAConnection(async (connection) => {
        const xa = connection.getXAResource();
        await xa.start(xid, XA.TMNOFLAGS);
        await connection.getConnection().executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [id, 'two-phase rollback'],
        );
        await xa.end(xid, XA.TMSUCCESS);
        expect(await xa.prepare(xid)).toBe(XA.XA_OK);
        await xa.rollback(xid);
      });
      await expect(readRows()).resolves.toEqual([]);
    }, 60_000);

    it('commits the one-phase path and recovers a prepared branch exactly once', async () => {
      const onePhaseXid = newXid();
      const preparedXid = newXid();
      await dataSource.withXAConnection(async (connection) => {
        const xa = connection.getXAResource();
        await xa.start(onePhaseXid, XA.TMNOFLAGS);
        await connection.getConnection().executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [3, 'one-phase commit'],
        );
        await xa.end(onePhaseXid, XA.TMSUCCESS);
        await xa.commit(onePhaseXid, true);

        await xa.start(preparedXid, XA.TMNOFLAGS);
        await connection.getConnection().executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [4, 'recovered commit'],
        );
        await xa.end(preparedXid, XA.TMSUCCESS);
        expect(await xa.prepare(preparedXid)).toBe(XA.XA_OK);
        const recovered = await xa.recover(XA.TMSTARTRSCAN | XA.TMENDRSCAN);
        expect(recovered.some((candidate) => xidEquals(candidate, preparedXid))).toBe(true);
        await xa.commit(preparedXid, false);
      });
      await expect(readRows()).resolves.toEqual([
        { ID: '3', LABEL: 'one-phase commit' },
        { ID: '4', LABEL: 'recovered commit' },
      ]);
    }, 90_000);

    it('reports Oracle XA timeout, resource-manager identity, and invalid local demarcation', async () => {
      const sameSource = new OjpXADataSource(ojpUrl(`localhost:${nodes[0].port}`, jdbcUrl), {
        user, password, healthCheckIntervalMs: 0,
      });
      const first = await sameSource.getXAConnection();
      const second = await sameSource.getXAConnection();
      try {
        const resource = first.getXAResource();
        await expect(resource.isSameRM(resource)).resolves.toBe(true);
        await expect(resource.isSameRM(second.getXAResource())).resolves.toBe(true);
        const timeoutSupported = await resource.setTransactionTimeout(30);
        expect(typeof timeoutSupported).toBe('boolean');
        if (timeoutSupported) {
          await expect(resource.getTransactionTimeout()).resolves.toBe(30);
        }
        await resource.setTransactionTimeout(0);
        await expect(resource.getTransactionTimeout()).resolves.toBe(0);
        await expect(first.getConnection().startTransaction()).rejects.toThrow(/createXAResource/);
        await expect(first.getConnection().commit()).rejects.toThrow(/createXAResource/);
        await expect(first.getConnection().rollback()).rejects.toThrow(/createXAResource/);
      } finally {
        await second.close();
        await first.close();
      }
    }, 60_000);

    it('does not reroute XA work when its bound Oracle OJP server is lost', async () => {
      const connection = await dataSource.getXAConnection();
      const client = connection.getConnection();
      const xa = connection.getXAResource();
      const boundPort = client.boundEndpoint!.port;
      const node = nodes.find(({ port }) => port === boundPort);
      if (!node) {
        await connection.close();
        throw new Error(`XA connection bound to unexpected test port ${boundPort}`);
      }
      const xid = newXid();
      try {
        await xa.start(xid, XA.TMNOFLAGS);
        await client.executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [5, 'must not be replayed'],
        );
        execFileSync('docker', ['kill', node.container], { stdio: 'ignore' });
        stopped.add(node.container);
        await waitForPortClosed(node.port);
        await expect(xa.end(xid, XA.TMSUCCESS)).rejects.toMatchObject({
          name: 'OjpXaError',
          errorCode: XA.XAER_RMFAIL,
        });
        expect(client.boundEndpoint?.port).toBe(boundPort);
      } finally {
        await connection.close().catch(() => undefined);
      }
      await expect(readRows()).resolves.toEqual([]);
    }, 120_000);
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
