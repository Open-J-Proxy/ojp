import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { randomBytes } from 'crypto';
import { OjpClient, OjpConnectionError, OjpSqlError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { isEndpointHealthy, resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_L8_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',').filter(Boolean);
const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060').split(',').map(Number);
const throttledContainer = process.env.OJP_TEST_ORACLE_L8_THROTTLED_NODE_CONTAINER;
const throttledPort = Number(process.env.OJP_TEST_ORACLE_L8_THROTTLED_NODE_PORT);
if (enabled && (containers.length < 2 || ports.length < containers.length
    || new Set(containers).size !== containers.length
    || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name))
    || new Set(ports).size !== ports.length || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535))) {
  throw new Error('Oracle L8 requires at least two distinct OJP test containers and valid TCP ports');
}
if (enabled && (!throttledContainer || !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(throttledContainer)
    || !Number.isInteger(throttledPort) || throttledPort < 1 || throttledPort > 65535
    || ports.includes(throttledPort))) {
  throw new Error('Oracle L8 requires a distinct throttled OJP node container and port');
}
const nodes = containers.map((container, index) => ({ container, port: ports[index] }));
const throttledNode = { container: throttledContainer ?? '', port: throttledPort };

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L8 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const password = process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword;
    const prefix = `ojp_node_oracle_l8_${randomBytes(6).toString('hex')}`;
    const clusterUrl = ojpUrl(nodes.map(({ port }) => `localhost:${port}`).join(','), jdbcUrl);
    const singleNodeUrl = (port: number): string => ojpUrl(`localhost:${port}`, jdbcUrl);
    const stopped = new Set<string>();
    const newClient = async (url = clusterUrl, options: { clientThrottleMode?: 'off' } = {}) => {
      const client = new OjpClient(url, {
        user, password, healthCheckIntervalMs: 0, failoverRetryDelayMs: 0, ...options,
      });
      await client.connect();
      return client;
    };

    beforeEach(() => {
      resetEndpointHealthForTests();
      resetPoolCacheForTests();
    });

    afterEach(async () => {
      for (const node of nodes) {
        if (stopped.has(node.container)) {
          execFileSync('docker', ['start', node.container], { stdio: 'ignore' });
          await waitForPortOpen(node.port, 60_000);
          stopped.delete(node.container);
        }
      }
      resetEndpointHealthForTests();
      resetPoolCacheForTests();
    }, 90_000);

    it('retries an eligible stateless write and stores exactly one row after node loss', async () => {
      const table = `${prefix}_retry`;
      const setup = await newClient();
      await setup.executeUpdate(
        `CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY, label VARCHAR2(80) NOT NULL)`,
      );
      await setup.close();
      const client = await newClient();
      const originalPort = client.boundEndpoint!.port;
      const original = nodes.find(({ port }) => port === originalPort)!;
      try {
        execFileSync('docker', ['kill', original.container], { stdio: 'ignore' });
        stopped.add(original.container);
        await waitForPortClosed(original.port);
        await expect(client.executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (7, 'recovered')`,
        )).resolves.toBe(1);
        expect(client.boundEndpoint?.port).not.toBe(originalPort);
        await expect(client.executeQuery(
          `SELECT id AS "ID", label AS "LABEL" FROM ${table}`,
        )).resolves.toMatchObject({ rows: [{ ID: '7', LABEL: 'recovered' }] });
      } finally {
        await client.close().catch(() => undefined);
        if (stopped.has(original.container)) {
          execFileSync('docker', ['start', original.container], { stdio: 'ignore' });
          stopped.delete(original.container);
          await waitForPortOpen(original.port, 60_000);
        }
        const cleanup = await newClient();
        try {
          await cleanup.executeUpdate(`DROP TABLE ${table}`);
        } finally {
          await cleanup.close();
        }
      }
    }, 120_000);

    it('surfaces sticky Oracle temporary-table loss without rerouting it', async () => {
      const client = await newClient();
      const originalPort = client.boundEndpoint!.port;
      const original = nodes.find(({ port }) => port === originalPort)!;
      const table = `${prefix}_sticky`;
      try {
        await client.executeUpdate(
          `CREATE GLOBAL TEMPORARY TABLE ${table} (id NUMBER(10) PRIMARY KEY) ON COMMIT PRESERVE ROWS`,
        );
        await client.executeUpdate(`INSERT INTO ${table} (id) VALUES (1)`);
        execFileSync('docker', ['kill', original.container], { stdio: 'ignore' });
        stopped.add(original.container);
        await waitForPortClosed(original.port);
        await expect(client.executeQuery(`SELECT id FROM ${table}`)).rejects.toBeInstanceOf(OjpConnectionError);
        expect(client.boundEndpoint?.port).toBe(originalPort);
      } finally {
        await client.close().catch(() => undefined);
        if (stopped.has(original.container)) {
          execFileSync('docker', ['start', original.container], { stdio: 'ignore' });
          stopped.delete(original.container);
          await waitForPortOpen(original.port, 60_000);
        }
        const cleanup = await newClient();
        try {
          await cleanup.executeUpdate(`DROP TABLE ${table}`);
        } finally {
          await cleanup.close();
        }
      }
    }, 120_000);

    it('classifies Oracle SQL errors without marking the endpoint unhealthy or failing over', async () => {
      const client = await newClient();
      const endpoint = client.boundEndpoint!;
      try {
        await expect(client.executeQuery('THIS IS NOT VALID SQL')).rejects.toBeInstanceOf(OjpSqlError);
        expect(isEndpointHealthy(endpoint)).toBe(true);
        expect(client.boundEndpoint).toEqual(endpoint);
        await expect(client.executeQuery('SELECT dummy AS "DUMMY" FROM dual'))
          .resolves.toMatchObject({ rows: [{ DUMMY: 'X' }] });
        expect(client.boundEndpoint).toEqual(endpoint);
      } finally {
        await client.close();
      }
    }, 60_000);

    it('does not retry or reroute work from an active transaction after node loss', async () => {
      const client = await newClient();
      const originalPort = client.boundEndpoint!.port;
      const original = nodes.find(({ port }) => port === originalPort)!;
      try {
        await client.startTransaction();
        execFileSync('docker', ['kill', original.container], { stdio: 'ignore' });
        stopped.add(original.container);
        await waitForPortClosed(original.port);
        await expect(client.executeUpdate(`CREATE TABLE ${prefix}_unsafe (id NUMBER(10))`))
          .rejects.toBeInstanceOf(OjpConnectionError);
        expect(client.boundEndpoint?.port).toBe(originalPort);
      } finally {
        await client.close().catch(() => undefined);
      }
    }, 90_000);

    it('commits and rolls back through a recovered node and verifies final data', async () => {
      const table = `${prefix}_recovery`;
      const down = nodes[0];
      let client: OjpClient | undefined;
      try {
        const setup = await newClient(singleNodeUrl(down.port));
        await setup.executeUpdate(`CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY)`);
        await setup.close();

        execFileSync('docker', ['kill', down.container], { stdio: 'ignore' });
        stopped.add(down.container);
        await waitForPortClosed(down.port);
        const outage = await newClient();
        await outage.close();
        execFileSync('docker', ['start', down.container], { stdio: 'ignore' });
        stopped.delete(down.container);
        await waitForPortOpen(down.port, 60_000);

        client = await newClient(singleNodeUrl(down.port));
        await client.withTransaction(async (connection) => {
          await connection.executeUpdate(`INSERT INTO ${table} (id) VALUES (1)`);
        });
        await expect(client.withTransaction(async (connection) => {
          await connection.executeUpdate(`INSERT INTO ${table} (id) VALUES (2)`);
          throw new Error('force rollback');
        })).rejects.toThrow('force rollback');
        await expect(client.executeQuery(`SELECT id AS "ID" FROM ${table} ORDER BY id`))
          .resolves.toMatchObject({ rows: [{ ID: '1' }] });
      } finally {
        await client?.close();
        if (stopped.has(down.container)) {
          execFileSync('docker', ['start', down.container], { stdio: 'ignore' });
          stopped.delete(down.container);
          await waitForPortOpen(down.port, 60_000);
        }
        const cleanup = await newClient(singleNodeUrl(down.port));
        try {
          await cleanup.executeUpdate(`DROP TABLE ${table}`);
        } finally {
          await cleanup.close();
        }
      }
    }, 150_000);

    it('rejects concurrent work at the OJP admission limit without failing over', async () => {
      const alternate = nodes[0];
      const functionName = `${prefix}_sleep`;
      const holder = await newClient(singleNodeUrl(throttledNode.port));
      const client = await newClient(ojpUrl(
        `localhost:${throttledNode.port},localhost:${alternate.port}`,
        jdbcUrl,
      ), { clientThrottleMode: 'off' });
      expect(client.boundEndpoint?.port).toBe(throttledNode.port);
      let running: Promise<unknown> | undefined;

      try {
        await holder.executeUpdate(
          `CREATE FUNCTION ${functionName} (seconds IN NUMBER) RETURN NUMBER IS `
          + 'BEGIN DBMS_LOCK.SLEEP(seconds); RETURN 42; END;',
        );
        running = holder.executeQuery(`SELECT ${functionName}(2) AS "HELD" FROM dual`);
        await new Promise((resolve) => setTimeout(resolve, 200));
        await expect(client.executeQuery('SELECT 1 AS "SHOULD_NOT_RETRY" FROM dual'))
          .rejects.toBeInstanceOf(OjpSqlError);
        expect(client.boundEndpoint?.port).toBe(throttledNode.port);
        await expect(running).resolves.toMatchObject({ rows: [{ HELD: '42' }] });
        await expect(client.executeQuery('SELECT 7 AS "USABLE" FROM dual'))
          .resolves.toMatchObject({ rows: [{ USABLE: '7' }] });
        expect(client.boundEndpoint?.port).toBe(throttledNode.port);
      } finally {
        await running?.catch(() => undefined);
        await client.close();
        await holder.executeUpdate(`DROP FUNCTION ${functionName}`).catch(() => undefined);
        await holder.close();
      }
    }, 60_000);

    it('surfaces Oracle JDBC pool exhaustion without failover, then succeeds after the holder releases its connection', async () => {
      const dataSourceName = `${prefix}_pool_exhaustion`;
      const poolConnectionProperties = {
        'ojp.connection.pool.maximumPoolSize': '2',
        'ojp.connection.pool.minimumIdle': '0',
        'ojp.connection.pool.connectionTimeout': '1000',
        // Avoid the server's default connection-creation grace retries masking an exhausted pool.
        'ojp.connection.pool.connectionCreationGracePeriod': '0',
      };
      const namedUrl = (endpoints: string): string =>
        `jdbc:ojp[${endpoints}(${dataSourceName})]_${jdbcUrl}`;
      const poolClient = (url: string): OjpClient => new OjpClient(url, {
        user,
        password,
        healthCheckIntervalMs: 0,
        failoverRetryDelayMs: 0,
        clientThrottleMode: 'off',
        connectionProperties: poolConnectionProperties,
      });

      const poolEndpoints = nodes.slice(0, 2);
      const client = poolClient(namedUrl(poolEndpoints.map(({ port }) => `localhost:${port}`).join(',')));
      let holder: OjpClient | undefined;
      let transactionStarted = false;
      try {
        await client.connect();
        const boundPort = client.boundEndpoint?.port;
        if (!boundPort || !poolEndpoints.some(({ port }) => port === boundPort)) {
          throw new Error(`Pool-test client bound to unexpected Oracle OJP port ${boundPort ?? 'unknown'}`);
        }
        const boundEndpoint = { host: 'localhost', port: boundPort };

        holder = poolClient(namedUrl(`localhost:${boundPort}`));
        await holder.connect();
        await holder.startTransaction();
        transactionStarted = true;
        await holder.executeQuery('SELECT dummy AS "DUMMY" FROM dual');

        await expect(client.executeQuery('SELECT dummy AS "DUMMY" FROM dual')).rejects.toMatchObject({
          name: 'OjpSqlError',
          sqlState: '08001',
          message: expect.stringMatching(/pool exhausted/i),
        });
        expect(client.boundEndpoint?.port).toBe(boundPort);
        expect(isEndpointHealthy(boundEndpoint)).toBe(true);

        await holder.rollback();
        transactionStarted = false;
        await holder.close();
        holder = undefined;

        await expect(client.executeQuery('SELECT dummy AS "DUMMY" FROM dual'))
          .resolves.toMatchObject({ rows: [{ DUMMY: 'X' }] });
        expect(client.boundEndpoint?.port).toBe(boundPort);
      } finally {
        if (holder) {
          if (transactionStarted) {
            await holder.rollback().catch(() => undefined);
          }
          await holder.close().catch(() => undefined);
        }
        await client.close().catch(() => undefined);
      }
    }, 60_000);
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
