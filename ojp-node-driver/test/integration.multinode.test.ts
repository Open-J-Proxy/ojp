/**
 * Real integration tests for multinode failover / round-robin endpoint selection,
 * against the same 3-node ojp-server cluster used by `integration.sqlServerAg.test.ts`
 * (ports 1059/1060/1061, all pointing at the same SQL Server AG primary).
 *
 * Unlike the other integration suites, these tests actively stop/start real Docker
 * containers (`ojp-server-1`/`ojp-server-2`/`ojp-server-3`) to simulate a node going
 * down, mirroring how the reference JDBC driver's `MultinodeFailoverTest` is
 * orchestrated in CI (see `.github/workflows/main.yml` in the main OJP repo). Because
 * this is more invasive/slow than the rest of the suite, it is gated behind its own
 * flag: only runs when OJP_ENABLE_MULTINODE_FAILOVER_TESTS=true.
 *
 * Requires:
 *   - Docker CLI available on PATH, with permission to control the named containers.
 *   - The 3-node cluster + SQL Server AG primary already running (see
 *     integration.sqlServerAg.test.ts header comment for the expected topology).
 */
import { execSync } from 'node:child_process';
import * as net from 'node:net';
import { OjpClient } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';

const enabled = process.env.OJP_ENABLE_MULTINODE_FAILOVER_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;

const BACKEND_URL = process.env.OJP_TEST_SQLSERVER_BACKEND_URL
  ?? 'sqlserver://sql-primary:1433;databaseName=AdventureWorks;encrypt=true;trustServerCertificate=true;';
const OJP_USER = process.env.OJP_TEST_SQLSERVER_USER ?? 'sa';
const OJP_PASSWORD = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? 'Teste@AG2019Local!';

/** Maps each ojp-server endpoint (as seen from the host) to its Docker container name. */
const NODES = [
  { port: 1059, container: 'ojp-server-1' },
  { port: 1060, container: 'ojp-server-2' },
  { port: 1061, container: 'ojp-server-3' },
] as const;

const allEndpointsUrl = (): string =>
  `jdbc:ojp[${NODES.map((n) => `localhost:${n.port}`).join(',')}]_${BACKEND_URL}`;

const singleEndpointUrl = (port: number): string => `jdbc:ojp[localhost:${port}]_${BACKEND_URL}`;

/** Uses `docker kill` (SIGKILL) instead of `docker stop` so the port closes immediately,
 * without waiting for the default `docker stop` SIGTERM grace period. */
function killNode(container: string): void {
  execSync(`docker kill ${container}`, { stdio: 'ignore' });
}

function startNode(container: string): void {
  execSync(`docker start ${container}`, { stdio: 'ignore' });
}

/** Polls a TCP port until it accepts connections (or the timeout elapses). Used after
 * restarting a container, since the ojp-server JVM takes a few seconds to boot and
 * start listening again. */
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
    } catch (err) {
      lastError = err;
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  throw new Error(`Port ${port} did not open within ${timeoutMs}ms: ${String(lastError)}`);
}

describeIfEnabled('OjpClient - multinode failover (real ojp-server cluster)', () => {
  beforeEach(() => {
    resetEndpointHealthForTests();
  });

  // Restores every node to "running" after each test, even if the test itself only
  // stopped one of them — protects the rest of the test suite (and other test files)
  // from inheriting a partially-down cluster if an assertion throws mid-test.
  afterEach(async () => {
    for (const node of NODES) {
      try {
        startNode(node.container);
      } catch {
        // Ignore: container may already be running ("start" on a running container
        // is a harmless no-op error from the Docker CLI's point of view here).
      }
    }
    for (const node of NODES) {
      await waitForPortOpen(node.port, 60_000);
    }
    resetEndpointHealthForTests();
  }, 90_000);

  it('connects successfully by failing over to a healthy endpoint when one node is down', async () => {
    killNode('ojp-server-1');
    await waitForNodeToStopAcceptingConnections(1059);

    const client = new OjpClient(allEndpointsUrl(), {
      user: OJP_USER,
      password: OJP_PASSWORD,
      callTimeoutMs: 10_000,
    });
    try {
      await client.connect();
      expect(client.boundEndpoint?.port).not.toBe(1059);
      expect([1060, 1061]).toContain(client.boundEndpoint?.port);
    } finally {
      await client.close();
    }
  }, 30_000);

  it('marks the down endpoint unhealthy so a subsequent connect() also avoids it', async () => {
    killNode('ojp-server-2');
    await waitForNodeToStopAcceptingConnections(1060);

    const clientOptions = { user: OJP_USER, password: OJP_PASSWORD, callTimeoutMs: 10_000 };

    const first = new OjpClient(allEndpointsUrl(), clientOptions);
    await first.connect();
    expect(first.boundEndpoint?.port).not.toBe(1060);
    await first.close();

    const second = new OjpClient(allEndpointsUrl(), clientOptions);
    try {
      await second.connect();
      expect(second.boundEndpoint?.port).not.toBe(1060);
    } finally {
      await second.close();
    }
  }, 30_000);

  it('fails with a clear connection-level error (no silent recovery) when the bound node goes down mid-session', async () => {
    const client = new OjpClient(singleEndpointUrl(1061), {
      user: OJP_USER,
      password: OJP_PASSWORD,
      callTimeoutMs: 8_000,
    });
    await client.connect();
    expect(client.boundEndpoint?.port).toBe(1061);

    killNode('ojp-server-3');
    await waitForNodeToStopAcceptingConnections(1061);

    await expect(client.executeUpdate('SELECT 1')).rejects.toThrow(/no longer usable/i);

    // The session must not be silently recovered against another node: further use
    // still fails, rather than transparently reconnecting elsewhere.
    await expect(client.executeUpdate('SELECT 1')).rejects.toThrow();
  }, 30_000);

  it('round-robins new connections across all healthy endpoints', async () => {
    const clientOptions = { user: OJP_USER, password: OJP_PASSWORD, callTimeoutMs: 10_000 };
    const boundPorts = new Set<number>();

    for (let i = 0; i < 6; i++) {
      const client = new OjpClient(allEndpointsUrl(), clientOptions);
      await client.connect();
      if (client.boundEndpoint) {
        boundPorts.add(client.boundEndpoint.port);
      }
      await client.close();
    }

    expect(boundPorts.size).toBeGreaterThan(1);
  }, 60_000);
});

/**
 * After `docker kill`, the port stops accepting new TCP connections almost
 * immediately, but this gives a brief grace period so the failover tests don't race
 * the container teardown.
 */
async function waitForNodeToStopAcceptingConnections(port: number): Promise<void> {
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
    await new Promise((r) => setTimeout(r, 200));
  }
}
