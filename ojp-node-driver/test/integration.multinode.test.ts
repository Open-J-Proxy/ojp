/**
 * Real multinode failover tests. OJP_ENABLE_MULTINODE_FAILOVER_TESTS=true requires
 * OJP_TEST_NODE_CONTAINERS to name three dedicated containers that can be stopped and
 * started. OJP_TEST_NODE_PORTS optionally supplies their three host ports.
 */
import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { OjpClient } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_MULTINODE_FAILOVER_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;

const connections = readConnectionFixtures('sqlserver_connections.csv', process.env.OJP_TEST_SQLSERVER_CSV);
const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',');
const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060,1061').split(',').map(Number);
if (enabled && (containers.length !== 3 || new Set(containers).size !== 3
    || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)))) {
  throw new Error('OJP_TEST_NODE_CONTAINERS must name the three dedicated test containers');
}
if (enabled && (ports.length !== 3 || new Set(ports).size !== 3
    || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535))) {
  throw new Error('OJP_TEST_NODE_PORTS must contain three distinct TCP ports');
}

/** Maps each ojp-server endpoint (as seen from the host) to its Docker container name. */
const NODES = [
  { port: ports[0], container: containers[0] },
  { port: ports[1], container: containers[1] },
  { port: ports[2], container: containers[2] },
] as const;

/** Uses `docker kill` (SIGKILL) instead of `docker stop` so the port closes immediately,
 * without waiting for the default `docker stop` SIGTERM grace period. */
function killNode(container: string): void {
  execFileSync('docker', ['kill', container], { stdio: 'ignore' });
}

function startNode(container: string): void {
  execFileSync('docker', ['start', container], { stdio: 'ignore' });
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

describeIfEnabled.each(connections)('OjpClient - multinode failover: %s', (jdbcUrl, OJP_USER, fixturePassword) => {
  const OJP_PASSWORD = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? fixturePassword;
  const allEndpointsUrl = (): string => ojpUrl(NODES.map((n) => `localhost:${n.port}`).join(','), jdbcUrl);
  const singleEndpointUrl = (port: number): string => ojpUrl(`localhost:${port}`, jdbcUrl);
  beforeEach(() => {
    resetEndpointHealthForTests();
  });

  // Restores every node to "running" after each test, even if the test itself only
  // stopped one of them — protects the rest of the test suite (and other test files)
  // from inheriting a partially-down cluster if an assertion throws mid-test.
  afterEach(async () => {
    for (const node of NODES) {
      startNode(node.container);
    }
    for (const node of NODES) {
      await waitForPortOpen(node.port, 60_000);
    }
    resetEndpointHealthForTests();
  }, 90_000);

  it('connects successfully by failing over to a healthy endpoint when one node is down', async () => {
    killNode(NODES[0].container);
    await waitForNodeToStopAcceptingConnections(NODES[0].port);

    const client = new OjpClient(allEndpointsUrl(), {
      user: OJP_USER,
      password: OJP_PASSWORD,
      callTimeoutMs: 10_000,
    });
    try {
      await client.connect();
      expect(client.boundEndpoint?.port).not.toBe(NODES[0].port);
      expect([NODES[1].port, NODES[2].port]).toContain(client.boundEndpoint?.port);
    } finally {
      await client.close();
    }
  }, 30_000);

  it('marks the down endpoint unhealthy so a subsequent connect() also avoids it', async () => {
    killNode(NODES[1].container);
    await waitForNodeToStopAcceptingConnections(NODES[1].port);

    const clientOptions = { user: OJP_USER, password: OJP_PASSWORD, callTimeoutMs: 10_000 };

    const first = new OjpClient(allEndpointsUrl(), clientOptions);
    await first.connect();
    expect(first.boundEndpoint?.port).not.toBe(NODES[1].port);
    await first.close();

    const second = new OjpClient(allEndpointsUrl(), clientOptions);
    try {
      await second.connect();
      expect(second.boundEndpoint?.port).not.toBe(NODES[1].port);
    } finally {
      await second.close();
    }
  }, 30_000);

  it('fails with a clear connection-level error (no silent recovery) when the bound node goes down mid-session', async () => {
    const client = new OjpClient(singleEndpointUrl(NODES[2].port), {
      user: OJP_USER,
      password: OJP_PASSWORD,
      callTimeoutMs: 8_000,
    });
    await client.connect();
    expect(client.boundEndpoint?.port).toBe(NODES[2].port);

    killNode(NODES[2].container);
    await waitForNodeToStopAcceptingConnections(NODES[2].port);

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
  throw new Error(`Port ${port} did not close within 5000ms`);
}
