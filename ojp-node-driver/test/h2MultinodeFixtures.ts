import { execFileSync } from 'node:child_process';
import * as net from 'node:net';
import { OjpServerEndpoint } from '../src';

export interface H2TestNode extends OjpServerEndpoint {
  container: string;
}

export function h2TestNodes(minimum = 2): H2TestNode[] {
  const containers = (process.env.OJP_TEST_NODE_CONTAINERS ?? '').split(',').filter(Boolean);
  const ports = (process.env.OJP_TEST_NODE_PORTS ?? '1059,1060,1061').split(',').map(Number);
  if (containers.length < minimum
      || new Set(containers).size !== containers.length
      || containers.some((name) => !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name))) {
    throw new Error(`OJP_TEST_NODE_CONTAINERS must name at least ${minimum} dedicated test containers`);
  }
  if (ports.length < containers.length
      || new Set(ports).size !== ports.length
      || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65535)) {
    throw new Error('OJP_TEST_NODE_PORTS must contain a distinct valid port for every test container');
  }
  return containers.map((container, index) => ({ host: 'localhost', port: ports[index], container }));
}

export function stopH2Node(node: H2TestNode): void {
  execFileSync('docker', ['kill', node.container], { stdio: 'ignore' });
}

export async function startH2Node(node: H2TestNode): Promise<void> {
  execFileSync('docker', ['start', node.container], { stdio: 'ignore' });
  await waitForPort(node.port, true, 60_000);
}

export async function waitForPort(port: number, expectedOpen: boolean, timeoutMs = 5_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const open = await new Promise<boolean>((resolve) => {
      const socket = net.connect({ host: 'localhost', port }, () => {
        socket.end();
        resolve(true);
      });
      socket.on('error', () => resolve(false));
    });
    if (open === expectedOpen) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Port ${port} did not become ${expectedOpen ? 'open' : 'closed'} within ${timeoutMs}ms`);
}
