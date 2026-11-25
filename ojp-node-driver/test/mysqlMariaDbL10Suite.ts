/**
 * Combined MySQL/MariaDB multinode and XA integration over mutual TLS.
 */
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

interface SuiteOptions {
  database: 'MySQL' | 'MariaDB';
  enabledEnvironmentVariable: string;
  fixture: string;
  fixtureEnvironmentVariable: string;
  passwordEnvironmentVariable: string;
}

export function registerDatabaseL10Suite(options: SuiteOptions): void {
  const enabled = process.env[options.enabledEnvironmentVariable] === 'true';
  const describeIfEnabled = enabled ? describe : describe.skip;
  const connections = readConnectionFixtures(options.fixture, process.env[options.fixtureEnvironmentVariable]);
  const certDir = process.env.OJP_TEST_MTLS_CERT_DIR;
  const endpoints = (process.env.OJP_TEST_MYSQL_MARIADB_L10_MTLS_ENDPOINTS ?? 'localhost:10593,localhost:10594')
    .split(',').map((endpoint) => endpoint.trim());
  const endpointPorts = endpoints.map((endpoint) => Number(endpoint.slice(endpoint.lastIndexOf(':') + 1)));
  const containers = (process.env.OJP_TEST_MYSQL_MARIADB_L10_MTLS_CONTAINERS ?? '').split(',').filter(Boolean);

  if (enabled && (!certDir || endpoints.length < 2 || new Set(endpoints).size !== endpoints.length
      || containers.length !== endpoints.length || new Set(containers).size !== containers.length
      || endpoints.some((endpoint) => !/^localhost:\d+$/.test(endpoint))
      || endpointPorts.some((port) => !Number.isInteger(port) || port < 1 || port > 65535)
      || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
    throw new Error(`${options.database} L10 requires a certificate directory and two distinct dedicated mTLS OJP servers`);
  }

  const nodes = endpoints.map((endpoint, index) => ({
    endpoint,
    port: endpointPorts[index],
    container: containers[index],
  }));

  function tlsOptions(identity: 'trusted' | 'untrusted' | 'none') {
    if (!certDir) {
      throw new Error('OJP_TEST_MTLS_CERT_DIR is required for L10');
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

  describeIfEnabled.each(connections)(`OjpClient - ${options.database} L10 mTLS: %s`, (jdbcUrl, user, fixturePassword) => {
    const password = process.env[options.passwordEnvironmentVariable] ?? fixturePassword;
    const clusterUrl = ojpUrl(endpoints.join(','), jdbcUrl);
    const stoppedContainers = new Set<string>();

    const newClient = async (
      url = clusterUrl,
      identity: 'trusted' | 'untrusted' | 'none' = 'trusted',
      loadBalancingStrategy?: 'least-connections' | 'round-robin',
    ) => {
      const client = new OjpClient(url, {
        user,
        password,
        callTimeoutMs: 5_000,
        healthCheckIntervalMs: 0,
        failoverRetryDelayMs: 0,
        loadBalancingStrategy,
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
        await client.close();
      }
    };

    const expectOne = async (client: OjpClient, column: string): Promise<void> => {
      const result = await client.executeQuery(`SELECT 1 AS ${column}`);
      expect(result.rows).toHaveLength(1);
      expect(Number(result.rows[0][column])).toBe(1);
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
      resetPoolCacheForTests();
      resetEndpointHealthForTests();
    }, 90_000);

    it('authenticates to every mTLS endpoint and verifies SQL, local transactions, and XA outcomes', async () => {
      for (const endpoint of endpoints) {
        await withClient(async (client) => {
          await expectOne(client, 'authenticated');
        }, ojpUrl(endpoint, jdbcUrl));
      }

      const table = `ojp_node_l10_${randomBytes(6).toString('hex')}`;
      await withClient((client) => client.executeUpdate(
        `CREATE TABLE ${table} (id INTEGER PRIMARY KEY, label VARCHAR(80) NOT NULL)`,
      ));

      try {
        const client = await newClient();
        const endpointBeforeSqlError = client.boundEndpoint?.port;
        try {
          await client.executeUpdate('CREATE TEMPORARY TABLE ojp_node_l10_session (value INTEGER)');
          await expect(client.executeUpdate('INSERT INTO ojp_node_l10_session VALUES (7)')).resolves.toBe(1);
          await expect(client.executeQuery('SELECT value FROM ojp_node_l10_session'))
            .resolves.toMatchObject({ rows: [{ value: 7 }] });
          await client.executeUpdate('DROP TEMPORARY TABLE ojp_node_l10_session');
          const invalidSql = client.executeUpdate('THIS IS NOT VALID SQL');
          await expect(invalidSql).rejects.toBeInstanceOf(OjpSqlError);
          await expect(invalidSql).rejects.toMatchObject({ sqlState: expect.stringMatching(/^42/) });
          expect(client.boundEndpoint?.port).toBe(endpointBeforeSqlError);
          await expectOne(client, 'usable');

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
          await client.close();
        }

        const xaDataSource = new OjpXADataSource(clusterUrl, {
          user,
          password,
          healthCheckIntervalMs: 0,
          ...tlsOptions('trusted'),
        });
        const committedXid = newXid();
        const rolledBackXid = newXid();
        await xaDataSource.withXAConnection(async (connection) => {
          const client = connection.getConnection();
          const xa = connection.getXAResource();
          let currentXid: Xid | undefined;
          let ended = false;
          try {
            await xa.start(committedXid, XA.TMNOFLAGS);
            currentXid = committedXid;
            expect(await client.executeUpdate(
              `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [3, 'xa-commit'],
            )).toBe(1);
            await xa.end(committedXid, XA.TMSUCCESS);
            ended = true;
            expect(await xa.prepare(committedXid)).toBe(XA.XA_OK);
            const recovered = await xa.recover(XA.TMSTARTRSCAN | XA.TMENDRSCAN);
            expect(recovered.some((candidate) =>
              candidate.formatId === committedXid.formatId
              && candidate.globalTransactionId.equals(committedXid.globalTransactionId)
              && candidate.branchQualifier.equals(committedXid.branchQualifier))).toBe(true);
            await xa.commit(committedXid, false);
            currentXid = undefined;

            await xa.start(rolledBackXid, XA.TMNOFLAGS);
            currentXid = rolledBackXid;
            ended = false;
            expect(await client.executeUpdate(
              `INSERT INTO ${table} (id, label) VALUES (?, ?)`, [4, 'xa-rollback'],
            )).toBe(1);
            await xa.end(rolledBackXid, XA.TMSUCCESS);
            ended = true;
            await xa.rollback(rolledBackXid);
            currentXid = undefined;
          } finally {
            if (currentXid) {
              if (!ended) {
                await xa.end(currentXid, XA.TMFAIL);
              }
              await xa.rollback(currentXid);
            }
          }
        });

        await withClient(async (verification) => {
          const result = await verification.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`);
          expect(result.rows).toEqual([
            { id: 1, label: 'local-commit' },
            { id: 3, label: 'xa-commit' },
          ]);
        });
      } finally {
        await withClient((client) => client.executeUpdate(`DROP TABLE IF EXISTS ${table}`));
      }
    }, 180_000);

    it('streams results and round-trips a LOB with automatically propagated session state over mTLS', async () => {
      const table = `ojp_node_l10_lob_${randomBytes(6).toString('hex')}`;
      await withClient((client) => client.executeUpdate(
        `CREATE TABLE ${table} (id INTEGER PRIMARY KEY, payload LONGBLOB)`,
      ));
      try {
        await withClient(async (client) => {
          const content = Buffer.alloc(70_000, 0xab);
          const lob = await client.createLob(content, 'BLOB');
          await expect(client.executeUpdate(`INSERT INTO ${table} VALUES (?, ?)`, [1, lob])).resolves.toBe(1);
          const rows = [];
          for await (const row of client.executeQueryStream(`SELECT id, payload FROM ${table} ORDER BY id`)) {
            rows.push(row);
          }
          expect(rows).toEqual([{ id: 1, payload: content }]);
        });
      } finally {
        await withClient((client) => client.executeUpdate(`DROP TABLE IF EXISTS ${table}`));
      }
    }, 90_000);

    it('releases unused XA sessions over mTLS without exhausting the server pool', async () => {
      for (const endpoint of endpoints) {
        const dataSource = new OjpXADataSource(ojpUrl(endpoint, jdbcUrl), {
          user,
          password,
          healthCheckIntervalMs: 0,
          ...tlsOptions('trusted'),
        });
        for (let index = 0; index < 30; index++) {
          await dataSource.withXAConnection(async (connection) => {
            expect(connection.getConnection().getAutoCommit()).toBe(false);
          });
        }
      }
    }, 120_000);

    it('fails over stateless work, redistributes sessions, and does not reroute sticky state', async () => {
      const table = `ojp_node_l10_failover_${randomBytes(6).toString('hex')}`;
      await withClient((client) => client.executeUpdate(
        `CREATE TABLE ${table} (id INTEGER PRIMARY KEY, label VARCHAR(80) NOT NULL)`,
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
          try {
            await stateless.close();
          } finally {
            await startStoppedNodes();
          }
        }

        const selected = new Set<number>();
        for (let index = 0; index < nodes.length * 2; index++) {
          const client = await newClient(clusterUrl, 'trusted', 'round-robin');
          try {
            selected.add(client.boundEndpoint!.port);
            await expectOne(client, 'healthy');
          } finally {
            await client.close();
          }
        }
        expect(selected).toEqual(new Set(nodes.map(({ port }) => port)));

        const sticky = await newClient();
        const stickyPort = sticky.boundEndpoint!.port;
        try {
          await sticky.executeUpdate('CREATE TEMPORARY TABLE ojp_node_l10_sticky (id INTEGER PRIMARY KEY)');
          await stopNode(stickyPort);
          await expect(sticky.executeUpdate('INSERT INTO ojp_node_l10_sticky VALUES (1)'))
            .rejects.toBeInstanceOf(OjpConnectionError);
          expect(sticky.boundEndpoint?.port).toBe(stickyPort);
        } finally {
          await sticky.close().catch((error: unknown) => {
            if (!(error instanceof OjpConnectionError)) throw error;
          });
        }
      } finally {
        await startStoppedNodes();
        await withClient((client) => client.executeUpdate(`DROP TABLE IF EXISTS ${table}`));
      }
    }, 240_000);

    it('surfaces active XA branch loss without rerouting or replaying its work', async () => {
      const table = `ojp_node_l10_xa_${randomBytes(6).toString('hex')}`;
      await withClient((client) => client.executeUpdate(
        `CREATE TABLE ${table} (id INTEGER PRIMARY KEY)`,
      ));
      const dataSource = new OjpXADataSource(clusterUrl, {
        user,
        password,
        healthCheckIntervalMs: 0,
        ...tlsOptions('trusted'),
      });
      let connection: Awaited<ReturnType<typeof dataSource.getXAConnection>> | undefined;
      try {
        connection = await dataSource.getXAConnection();
        const client = connection.getConnection();
        const xa = connection.getXAResource();
        const boundPort = client.boundEndpoint!.port;
        const xid = newXid();
        await xa.start(xid, XA.TMNOFLAGS);
        await expect(client.executeUpdate(`INSERT INTO ${table} VALUES (1)`)).resolves.toBe(1);
        await stopNode(boundPort);
        await expect(xa.end(xid, XA.TMSUCCESS)).rejects.toMatchObject({
          name: 'OjpXaError',
          errorCode: XA.XAER_RMFAIL,
        });
        expect(client.boundEndpoint?.port).toBe(boundPort);
      } finally {
        await startStoppedNodes();
        await connection?.close().catch((error: unknown) => {
          if (!(error instanceof OjpConnectionError)) throw error;
        });
        await withClient(async (verification) => {
          await expect(verification.executeQuery(`SELECT id FROM ${table}`))
            .resolves.toMatchObject({ rows: [] });
        });
        await withClient((verification) => verification.executeUpdate(`DROP TABLE IF EXISTS ${table}`));
      }
    }, 120_000);

    it('rejects a connection when the required client certificate is absent', async () => {
      for (const endpoint of endpoints) {
        resetPoolCacheForTests();
        resetEndpointHealthForTests();
        await withClient(async (client) => {
          await expectOne(client, 'authenticated');
        }, ojpUrl(endpoint, jdbcUrl));
        await expect(newClient(ojpUrl(endpoint, jdbcUrl), 'none')).rejects.toBeInstanceOf(OjpConnectionError);
      }
    }, 60_000);

    it('rejects a client certificate that the server does not trust', async () => {
      for (const endpoint of endpoints) {
        resetPoolCacheForTests();
        resetEndpointHealthForTests();
        await withClient(async (client) => {
          await expectOne(client, 'authenticated');
        }, ojpUrl(endpoint, jdbcUrl));
        await expect(newClient(ojpUrl(endpoint, jdbcUrl), 'untrusted')).rejects.toBeInstanceOf(OjpConnectionError);
      }
    }, 60_000);
  });
}

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
