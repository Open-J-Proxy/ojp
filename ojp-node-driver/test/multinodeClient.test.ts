import * as grpc from '@grpc/grpc-js';
import { EventEmitter } from 'events';

interface MockSession {
  connHash: string;
  clientUUID: string;
  sessionUUID: string;
  sessionStatus: 'SESSION_ACTIVE';
  targetServer?: string;
}

const mockRpcState: {
  connectRequests: Array<{ url: string; address: string; clusterHealth?: string; serverEndpoints?: string[] }>;
  connectErrors: Map<string, Error>;
  probeErrors: Map<string, Error>;
  updateErrors: Array<Error | undefined>;
  updateAddresses: string[];
  updateTargetServers: Array<string | undefined>;
  startTransactionErrors: Array<Error | undefined>;
  startTransactionAddresses: string[];
  responseTargetServer?: string;
  responseSessionUUID?: string;
  sessionUUID: string;
  lobBlocks: Array<{ position: string; data: Buffer; session: MockSession }>;
} = {
  connectRequests: [],
  connectErrors: new Map(),
  probeErrors: new Map(),
  updateErrors: [],
  updateAddresses: [],
  updateTargetServers: [],
  startTransactionErrors: [],
  startTransactionAddresses: [],
  sessionUUID: '',
  lobBlocks: [],
};

jest.mock('../src/proto/loader', () => ({
  loadStatementServiceGrpcObject: () => ({
    com: {
      openjproxy: {
        grpc: {
          StatementService: class {
            constructor(private readonly address: string) {}

            connect(
              request: { url: string; clientUUID: string; clusterHealth?: string; serverEndpoints?: string[] },
              callbackOrOptions: unknown,
              maybeCallback?: (err: Error | null, session: MockSession) => void,
            ): void {
              const callback = typeof callbackOrOptions === 'function'
                ? callbackOrOptions as (err: Error | null, session: MockSession) => void
                : maybeCallback!;
              mockRpcState.connectRequests.push({
                url: request.url,
                address: this.address,
                clusterHealth: request.clusterHealth,
                serverEndpoints: request.serverEndpoints,
              });
              const connectError = request.url
                ? mockRpcState.connectErrors.get(this.address)
                : mockRpcState.probeErrors.get(this.address);
              if (connectError) {
                callback(connectError, {} as MockSession);
                return;
              }
              callback(null, {
                connHash: request.url ? 'pool-hash' : '',
                clientUUID: request.clientUUID,
                sessionUUID: request.url ? mockRpcState.sessionUUID : '',
                sessionStatus: 'SESSION_ACTIVE',
              });
            }

            executeUpdate(
              request: { session: MockSession },
              callback: (err: Error | null, result: { intValue: number; session: MockSession }) => void,
            ): void {
              mockRpcState.updateAddresses.push(this.address);
              mockRpcState.updateTargetServers.push(request.session.targetServer);
              const error = mockRpcState.updateErrors.shift();
              if (error) {
                callback(error, { intValue: 0, session: request.session });
              } else {
                const targetServer = mockRpcState.responseTargetServer ?? request.session.targetServer;
                const sessionUUID = mockRpcState.responseSessionUUID ?? request.session.sessionUUID;
                mockRpcState.responseTargetServer = undefined;
                mockRpcState.responseSessionUUID = undefined;
                callback(null, {
                  intValue: 1,
                  session: { ...request.session, sessionUUID, targetServer },
                });
              }
            }

            startTransaction(
              session: MockSession,
              callback: (err: Error | null, response: MockSession) => void,
            ): void {
              mockRpcState.startTransactionAddresses.push(this.address);
              const error = mockRpcState.startTransactionErrors.shift();
              callback(error ?? null, {
                ...session,
                sessionUUID: mockRpcState.responseSessionUUID ?? session.sessionUUID,
                targetServer: mockRpcState.responseTargetServer ?? session.targetServer,
              });
            }

            commitTransaction(session: MockSession, callback: (err: Error | null, response: MockSession) => void): void {
              callback(null, { ...session, targetServer: mockRpcState.responseTargetServer ?? session.targetServer });
            }

            rollbackTransaction(session: MockSession, callback: (err: Error | null, response: MockSession) => void): void {
              callback(null, { ...session, targetServer: mockRpcState.responseTargetServer ?? session.targetServer });
            }

            callResource(
              request: { session: MockSession },
              callback: (err: Error | null, response: { session: MockSession }) => void,
            ): void {
              callback(null, { session: request.session });
            }

            createLob(): EventEmitter & {
              write: (block: { position: string; data: Buffer; session: MockSession }, callback: () => void) => void;
              end: () => void;
            } {
              const call = new EventEmitter() as EventEmitter & {
                write: (block: { position: string; data: Buffer; session: MockSession }, callback: () => void) => void;
                end: () => void;
              };
              call.write = (block, callback) => {
                mockRpcState.lobBlocks.push(block);
                callback();
                if (mockRpcState.lobBlocks.length === 1) {
                  process.nextTick(() => call.emit('data', {
                    uuid: 'lob', lobType: 'LT_CLOB', session: { ...block.session, sessionUUID: 'lob-session' },
                  }));
                }
              };
              call.end = () => process.nextTick(() => call.emit('end'));
              return call;
            }

            terminateSession(_session: unknown, callbackOrOptions: unknown, maybeCallback?: (err: Error | null) => void): void {
              const callback = typeof callbackOrOptions === 'function'
                ? callbackOrOptions as (err: Error | null) => void
                : maybeCallback!;
              callback(null);
            }

            close(): void {}
          },
        },
      },
    },
  }),
}));

import { OjpClient, OjpConnectionError, OjpSqlError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { isEndpointHealthy, markEndpointUnhealthy, resetEndpointHealthForTests } from '../src/client/endpointHealth';
import { throttleFor } from '../src/client/clientThrottle';

describe('OjpClient multinode pool cache', () => {
  const url = 'jdbc:ojp[localhost:1059]_h2:mem:l7';

  beforeEach(() => {
    mockRpcState.connectRequests = [];
    mockRpcState.connectErrors.clear();
    mockRpcState.probeErrors.clear();
    mockRpcState.updateErrors = [];
    mockRpcState.updateAddresses = [];
    mockRpcState.updateTargetServers = [];
    mockRpcState.startTransactionErrors = [];
    mockRpcState.startTransactionAddresses = [];
    mockRpcState.responseTargetServer = undefined;
    mockRpcState.responseSessionUUID = undefined;
    mockRpcState.sessionUUID = '';
    mockRpcState.lobBlocks = [];
    resetEndpointHealthForTests();
    resetPoolCacheForTests();
  });

  it.each([
    ['mariadb://localhost/db', undefined, 'CLOB', '65534'],
    ['mysql://localhost/db', undefined, 'CLOB', '65535'],
    ['mysql://localhost/db?permitMysqlScheme', 'codePoint', 'CLOB', '65534'],
    ['mariadb://localhost/db', 'utf16', 'CLOB', '65535'],
    ['mariadb://localhost/db', undefined, 'BLOB', '65537'],
  ] as const)('uses the correct LOB position for %s with %s and %s', async (backend, unit, kind, expected) => {
    const client = new OjpClient(`jdbc:ojp[localhost:1059]_${backend}`, { clobPositionUnit: unit });
    await client.connect();
    try {
      await client.createLob(`${'a'.repeat(65_532)}🚀${'b'.repeat(10)}`, kind);
      expect(mockRpcState.lobBlocks.map((block) => block.position)).toEqual(['1', expected]);
      expect(mockRpcState.lobBlocks[1].session.sessionUUID).toBe('lob-session');
    } finally {
      await client.close();
    }
  });

  it('rejects invalid CLOB position units', () => {
    expect(() => new OjpClient(url, { clobPositionUnit: 'invalid' as 'utf16' })).toThrow(/clobPositionUnit/);
  });

  it('reuses the pool connHash on a cached connection without a connect RPC', async () => {
    const first = new OjpClient(url);
    const firstSession = await first.connect();
    await first.close();
    const initialPoolConnects = mockRpcState.connectRequests.filter(({ url: value }) => value !== '').length;

    const second = new OjpClient(url);
    const secondSession = await second.connect();
    try {
      expect(secondSession.connHash).toBe(firstSession.connHash);
      expect(secondSession.sessionUUID).toBe('');
      expect(mockRpcState.connectRequests.filter(({ url: value }) => value !== '')).toHaveLength(initialPoolConnects);
      await expect(second.executeUpdate('INSERT INTO t VALUES (1)')).resolves.toBe(1);
    } finally {
      await second.close();
    }
  });

  it('shares admission limits across clients and bypasses them inside transactions', async () => {
    const first = new OjpClient(url);
    const second = new OjpClient(url);
    await first.connect();
    await second.connect();
    const throttle = throttleFor('pool-hash');
    throttle.update({
      connHash: 'pool-hash', clientUUID: 'client', sessionUUID: '',
      sessionStatus: 'SESSION_ACTIVE', maxAdmission: 1, clientCount: 1,
    });
    const release = throttle.acquire('reactive', false);
    try {
      await expect(second.executeUpdate('INSERT INTO t VALUES (1)')).rejects.toThrow(/overload/i);
      expect(mockRpcState.updateAddresses).toHaveLength(0);
      await first.startTransaction();
      await expect(first.executeUpdate('INSERT INTO t VALUES (2)')).resolves.toBe(1);
      await first.rollback();
      release();
      await expect(second.executeUpdate('INSERT INTO t VALUES (3)')).resolves.toBe(1);
      expect(mockRpcState.updateAddresses).toHaveLength(2);
    } finally {
      release();
      await second.close();
      await first.close();
    }
  });

  it('probes each configured endpoint and sends cluster health when creating the pool', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l7', {
      healthCheckIntervalMs: 0,
    });
    await client.connect();

    const poolConnects = mockRpcState.connectRequests.filter(({ url: value }) => value !== '');
    expect(poolConnects).toHaveLength(2);
    expect(poolConnects.map(({ clusterHealth }) => clusterHealth)).toEqual([
      'localhost:1059(UP);localhost:1060(UP)',
      'localhost:1059(UP);localhost:1060(UP)',
    ]);
    expect(mockRpcState.connectRequests.filter(({ url: value }) => value === '')).toHaveLength(2);

    await client.close();
  });

  it('sends the selected endpoint as targetServer and preserves it when responses omit it', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l6', {
      healthCheckIntervalMs: 0,
    });
    const session = await client.connect();
    const selectedEndpoint = client.boundEndpoint!;
    mockRpcState.responseTargetServer = '';

    try {
      await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).resolves.toBe(1);
      expect(session.targetServer).toBe(`localhost:${selectedEndpoint.port}`);
      expect(mockRpcState.updateTargetServers).toEqual([`localhost:${selectedEndpoint.port}`]);
      expect(client.boundEndpoint).toEqual(selectedEndpoint);
    } finally {
      await client.close();
    }
  });

  it('routes subsequent sticky requests to the configured response targetServer', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l6', {
      healthCheckIntervalMs: 0,
    });

    await client.connect();
    const originalEndpoint = client.boundEndpoint!;
    const targetPort = originalEndpoint.port === 1059 ? 1060 : 1059;
    mockRpcState.responseTargetServer = `localhost:${targetPort}`;
    mockRpcState.responseSessionUUID = 'sticky-session';

    try {
      await expect(client.executeUpdate('CREATE TEMP TABLE sticky_test (id INT)')).resolves.toBe(1);
      expect(client.boundEndpoint?.port).toBe(targetPort);
      await expect(client.executeUpdate('INSERT INTO sticky_test VALUES (1)')).resolves.toBe(1);
      expect(mockRpcState.updateAddresses).toEqual([
        `localhost:${originalEndpoint.port}`,
        `localhost:${targetPort}`,
      ]);
      expect(mockRpcState.updateTargetServers).toEqual([
        `localhost:${originalEndpoint.port}`,
        `localhost:${targetPort}`,
      ]);
    } finally {
      await client.close();
    }
  });

  it('routes requests to the target returned when a transaction starts', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l6-transaction', {
      healthCheckIntervalMs: 0,
    });
    await client.connect();
    const target = client.boundEndpoint!.port === 1059 ? 'localhost:1060' : 'localhost:1059';
    mockRpcState.responseTargetServer = target;
    mockRpcState.responseSessionUUID = 'transaction-session';

    try {
      await client.startTransaction();
      await client.executeUpdate('INSERT INTO t VALUES (1)');
      expect(mockRpcState.updateAddresses).toEqual([target]);
      expect(mockRpcState.updateTargetServers).toEqual([target]);
    } finally {
      await client.close();
    }
  });

  it('rejects an unconfigured target returned when a transaction starts', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l6-transaction', {
      healthCheckIntervalMs: 0,
    });
    await client.connect();
    mockRpcState.responseTargetServer = 'localhost:9999';
    mockRpcState.responseSessionUUID = 'transaction-session';
    try {
      await expect(client.startTransaction()).rejects.toThrow(/targetServer "localhost:9999".*not a configured endpoint/i);
      await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).rejects.toThrow(/targetServer "localhost:9999"/i);
      expect(mockRpcState.updateAddresses).toHaveLength(0);
    } finally {
      await client.close();
    }
  });

  it('fails a sticky request immediately when its bound endpoint is unhealthy', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l6-unhealthy', {
      healthCheckIntervalMs: 0,
    });
    await client.connect();
    mockRpcState.responseSessionUUID = 'sticky-session';
    try {
      await client.executeUpdate('CREATE TEMPORARY TABLE t (id INT)');
      markEndpointUnhealthy(client.boundEndpoint!);
      await expect(client.executeUpdate('INSERT INTO t VALUES (1)'))
        .rejects.toThrow(/session bound to .* is no longer usable/i);
      expect(mockRpcState.updateAddresses).toHaveLength(1);
    } finally {
      await client.close();
    }
  });

  it.each(['commit', 'rollback'] as const)('uses the %s response target for subsequent calls', async (operation) => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l6-finish', {
      healthCheckIntervalMs: 0,
    });
    await client.connect();
    try {
      await client.startTransaction();
      const target = client.boundEndpoint!.port === 1059 ? 'localhost:1060' : 'localhost:1059';
      mockRpcState.responseTargetServer = target;
      await client[operation]();
      await client.executeUpdate('INSERT INTO t VALUES (1)');
      expect(mockRpcState.updateAddresses).toEqual([target]);
      expect(mockRpcState.updateTargetServers).toEqual([target]);
    } finally {
      await client.close();
    }
  });

  it('rejects a response targetServer that is not configured instead of routing elsewhere', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l6', {
      healthCheckIntervalMs: 0,
    });
    await client.connect();
    const originalEndpoint = client.boundEndpoint!;
    mockRpcState.responseTargetServer = 'localhost:9999';

    try {
      await expect(client.executeUpdate('CREATE TEMP TABLE sticky_test (id INT)'))
        .rejects.toThrow(/targetServer "localhost:9999".*not a configured endpoint/i);
      expect(mockRpcState.updateAddresses).toEqual([`localhost:${originalEndpoint.port}`]);
      expect(client.boundEndpoint).toEqual(originalEndpoint);
    } finally {
      await client.close();
    }
  });

  it('continues endpoint health probes while a multinode client remains open', async () => {
    jest.useFakeTimers();
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l7', {
      healthCheckIntervalMs: 50,
    });
    try {
      await client.connect();
      mockRpcState.connectRequests = [];

      await jest.advanceTimersByTimeAsync(50);

      expect(mockRpcState.connectRequests.filter(({ url: value }) => value === '')).toHaveLength(2);
    } finally {
      await client.close();
      jest.useRealTimers();
    }
  });

  it('probes independent clusters without sharing their probe interval', async () => {
    const first = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:one');
    const second = new OjpClient('jdbc:ojp[localhost:1061,localhost:1062]_h2:mem:two');
    try {
      await first.connect();
      await second.connect();
      expect(mockRpcState.connectRequests.filter(({ url: value }) => value === '').map(({ address }) => address))
        .toEqual(expect.arrayContaining(['localhost:1059', 'localhost:1060', 'localhost:1061', 'localhost:1062']));
    } finally {
      await first.close();
      await second.close();
    }
  });

  it('propagates shared-cluster health changes to each connection pool', async () => {
    jest.useFakeTimers();
    const first = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:one', {
      healthCheckIntervalMs: 50,
    });
    const second = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:two', {
      healthCheckIntervalMs: 50,
    });
    try {
      await first.connect();
      await second.connect();
      mockRpcState.connectRequests = [];
      mockRpcState.probeErrors.set('localhost:1059', Object.assign(new Error('server unavailable'), {
        code: grpc.status.UNAVAILABLE,
      }));
      await jest.advanceTimersByTimeAsync(100);
      const updates = mockRpcState.connectRequests.filter(({ url: value, clusterHealth: health }) =>
        value !== '' && health?.includes('localhost:1059(DOWN)'));
      expect(updates.map(({ url: value }) => value)).toEqual(expect.arrayContaining([
        'jdbc:h2:mem:one', 'jdbc:h2:mem:two',
      ]));
    } finally {
      await first.close();
      await second.close();
      jest.useRealTimers();
    }
  });

  it('does not initialize unrelated pools on a different cluster during health refresh', async () => {
    jest.useFakeTimers();
    const first = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:one', {
      healthCheckIntervalMs: 50,
    });
    const second = new OjpClient('jdbc:ojp[localhost:1061,localhost:1062]_h2:mem:two', {
      healthCheckIntervalMs: 50,
    });
    try {
      await first.connect();
      await second.connect();
      mockRpcState.probeErrors.set('localhost:1059', Object.assign(new Error('server unavailable'), {
        code: grpc.status.UNAVAILABLE,
      }));
      mockRpcState.connectRequests = [];
      await jest.advanceTimersByTimeAsync(50);
      const poolRequests = mockRpcState.connectRequests.filter(({ url: value }) => value !== '');
      expect(poolRequests.length).toBeGreaterThan(0);
      for (const request of poolRequests) {
        expect(request.serverEndpoints).toEqual(
          request.url.endsWith('one') ? ['localhost:1059', 'localhost:1060'] : ['localhost:1061', 'localhost:1062'],
        );
      }
    } finally {
      await first.close();
      await second.close();
      jest.useRealTimers();
    }
  });

  it('continues connecting when initializing another endpoint encounters a transport failure', async () => {
    mockRpcState.connectErrors.set('localhost:1060', Object.assign(new Error('server unavailable'), {
      code: grpc.status.UNAVAILABLE,
    }));
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:transport', {
      healthCheckIntervalMs: 0,
    });
    try {
      await expect(client.connect()).resolves.toMatchObject({ connHash: 'pool-hash' });
      expect(client.boundEndpoint?.port).toBe(1059);
      expect(isEndpointHealthy({ host: 'localhost', port: 1060 })).toBe(false);
    } finally {
      await client.close();
    }
  });

  it('reconnects and performs exactly one safe retry for a stateless stale pool', async () => {
    const client = new OjpClient(url);
    await client.connect();
    mockRpcState.updateErrors.push(
      Object.assign(new Error('No pool found for connection hash: pool-hash. Client must reconnect.'), {
        code: grpc.status.NOT_FOUND,
        details: 'No pool found for connection hash: pool-hash. Client must reconnect.',
      }),
    );

    await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).resolves.toBe(1);

    expect(mockRpcState.connectRequests.filter(({ url: value }) => value !== '')).toHaveLength(2);
    await client.close();
  });

  it('reconnects once before starting a transaction on a stale stateless pool', async () => {
    const client = new OjpClient(url);
    await client.connect();
    mockRpcState.startTransactionErrors.push(
      Object.assign(new Error('No pool found for connection hash: pool-hash. Client must reconnect.'), {
        code: grpc.status.NOT_FOUND,
        details: 'No pool found for connection hash: pool-hash. Client must reconnect.',
      }),
    );

    try {
      await expect(client.startTransaction()).resolves.toBeUndefined();
      expect(mockRpcState.startTransactionAddresses).toHaveLength(2);
      expect(mockRpcState.connectRequests.filter(({ url: value }) => value !== '')).toHaveLength(2);
    } finally {
      await client.close();
    }
  });

  it('marks a recovered endpoint healthy after reconnecting to a stale pool', async () => {
    const client = new OjpClient(url);
    await client.connect();
    const endpoint = client.boundEndpoint!;
    markEndpointUnhealthy(endpoint);
    mockRpcState.startTransactionErrors.push(
      Object.assign(new Error('No pool found for connection hash: pool-hash.'), {
        code: grpc.status.NOT_FOUND,
        details: 'No pool found for connection hash: pool-hash.',
      }),
    );
    mockRpcState.responseSessionUUID = 'transaction-session';

    try {
      await client.withTransaction(async (transaction) => {
        await transaction.executeUpdate('INSERT INTO t VALUES (1)');
      });
      expect(isEndpointHealthy(endpoint)).toBe(true);
      await expect(client.withTransaction(async (transaction) => {
        await transaction.executeUpdate('INSERT INTO t VALUES (2)');
        throw new Error('force rollback');
      })).rejects.toThrow('force rollback');
    } finally {
      await client.close();
    }
  });

  it('marks an endpoint healthy after a successful transaction on a cached pool', async () => {
    const first = new OjpClient(url);
    await first.connect();
    const endpoint = first.boundEndpoint!;
    await first.close();
    markEndpointUnhealthy(endpoint);
    const client = new OjpClient(url);
    await client.connect();
    mockRpcState.responseSessionUUID = 'transaction-session';

    try {
      expect(isEndpointHealthy(endpoint)).toBe(false);
      await client.withTransaction(async (transaction) => {
        await transaction.executeUpdate('INSERT INTO t VALUES (1)');
      });
      expect(isEndpointHealthy(endpoint)).toBe(true);
    } finally {
      await client.close();
    }
  });

  it('marks a successfully connected endpoint healthy before using a sticky session', async () => {
    const endpoint = { host: 'localhost', port: 1059 };
    markEndpointUnhealthy(endpoint);
    mockRpcState.sessionUUID = 'sticky-session';
    const client = new OjpClient(url);
    await client.connect();
    try {
      expect(isEndpointHealthy(endpoint)).toBe(true);
      await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).resolves.toBe(1);
    } finally {
      await client.close();
    }
  });

  it('marks an endpoint healthy after a stateless update creates a sticky session', async () => {
    const client = new OjpClient(url);
    await client.connect();
    const endpoint = client.boundEndpoint!;
    markEndpointUnhealthy(endpoint);
    mockRpcState.responseSessionUUID = 'sticky-session';
    try {
      await client.executeUpdate('CREATE TEMPORARY TABLE t (id INT)');
      expect(isEndpointHealthy(endpoint)).toBe(true);
      await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).resolves.toBe(1);
    } finally {
      await client.close();
    }
  });

  it('only marks the responding endpoint healthy when a transaction changes its target', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l8-target', {
      healthCheckIntervalMs: 0,
    });
    await client.connect();
    const original = client.boundEndpoint!;
    const target = { host: 'localhost', port: original.port === 1059 ? 1060 : 1059 };
    markEndpointUnhealthy(original);
    markEndpointUnhealthy(target);
    mockRpcState.responseTargetServer = `localhost:${target.port}`;
    mockRpcState.responseSessionUUID = 'transaction-session';
    try {
      await client.startTransaction();
      expect(isEndpointHealthy(original)).toBe(true);
      expect(isEndpointHealthy(target)).toBe(false);
      await expect(client.rollback()).rejects.toThrow(/endpoint is unhealthy/i);
    } finally {
      await client.close();
    }
  });

  it('does not retry a stale-pool transaction start for a sticky session', async () => {
    mockRpcState.sessionUUID = 'sticky-session';
    const client = new OjpClient(url);
    await client.connect();
    mockRpcState.startTransactionErrors.push(
      Object.assign(new Error('No pool found for connection hash: pool-hash.'), {
        code: grpc.status.NOT_FOUND,
        details: 'No pool found for connection hash: pool-hash.',
      }),
    );

    try {
      await expect(client.startTransaction()).rejects.toBeInstanceOf(OjpSqlError);
      expect(mockRpcState.startTransactionAddresses).toHaveLength(1);
      expect(mockRpcState.connectRequests.filter(({ url: value }) => value !== '')).toHaveLength(1);
    } finally {
      await client.close();
    }
  });

  it('does not retry a stale-pool response for a sticky session', async () => {
    mockRpcState.sessionUUID = 'sticky-session';
    const client = new OjpClient(url);
    await client.connect();
    mockRpcState.updateErrors.push(
      Object.assign(new Error('No pool found for connection hash: pool-hash.'), {
        code: grpc.status.NOT_FOUND,
        details: 'No pool found for connection hash: pool-hash.',
      }),
    );

    await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).rejects.toBeInstanceOf(OjpSqlError);
    expect(mockRpcState.connectRequests.filter(({ url: value }) => value !== '')).toHaveLength(1);
    await client.close();
  });

  it('fails over and retries a stateless operation after a transport failure', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l8', {
      healthCheckIntervalMs: 0,
      failoverRetryDelayMs: 0,
    });
    await client.connect();
    const failedEndpoint = client.boundEndpoint!.port;
    mockRpcState.updateErrors.push(
      Object.assign(new Error('server unavailable'), {
        code: grpc.status.UNAVAILABLE,
        details: 'server unavailable',
      }),
    );

    try {
      await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).resolves.toBe(1);
      expect(mockRpcState.updateAddresses).toHaveLength(2);
      expect(mockRpcState.updateAddresses[0]).toBe(`localhost:${failedEndpoint}`);
      expect(mockRpcState.updateAddresses[1]).not.toBe(`localhost:${failedEndpoint}`);
      expect(client.boundEndpoint?.port).not.toBe(failedEndpoint);
    } finally {
      await client.close();
    }
  });

  it('does not retry transport errors for a sticky session', async () => {
    mockRpcState.sessionUUID = 'sticky-session';
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l8', {
      healthCheckIntervalMs: 0,
      failoverRetryDelayMs: 0,
    });
    await client.connect();
    mockRpcState.updateErrors.push(
      Object.assign(new Error('server unavailable'), {
        code: grpc.status.UNAVAILABLE,
        details: 'server unavailable',
      }),
    );

    try {
      await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).rejects.toThrow(/session bound to/);
      expect(mockRpcState.updateAddresses).toHaveLength(1);
    } finally {
      await client.close();
    }
  });

  it('does not retry SQL or pool-exhaustion errors', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l8', {
      healthCheckIntervalMs: 0,
      failoverRetryDelayMs: 0,
    });
    await client.connect();
    mockRpcState.updateErrors.push(
      Object.assign(new Error('pool exhausted'), {
        code: grpc.status.RESOURCE_EXHAUSTED,
        details: 'connection pool exhausted',
      }),
    );

    try {
      await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).rejects.toBeInstanceOf(OjpSqlError);
      expect(mockRpcState.updateAddresses).toHaveLength(1);
    } finally {
      await client.close();
    }
  });

  it('stops after the configured number of failover attempts', async () => {
    const client = new OjpClient('jdbc:ojp[localhost:1059,localhost:1060]_h2:mem:l8', {
      healthCheckIntervalMs: 0,
      failoverRetryDelayMs: 0,
      maxFailoverAttempts: 1,
    });
    await client.connect();
    mockRpcState.updateErrors.push(
      ...[1, 2].map(() => Object.assign(new Error('server unavailable'), {
        code: grpc.status.UNAVAILABLE,
        details: 'server unavailable',
      })),
    );

    try {
      await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).rejects.toBeInstanceOf(OjpConnectionError);
      expect(mockRpcState.updateAddresses).toHaveLength(2);
      expect(new Set(mockRpcState.updateAddresses).size).toBe(2);
    } finally {
      await client.close();
    }
  });

  it('does not replay a prepared statement handle after a pool loss', async () => {
    const client = new OjpClient(url);
    await client.connect();
    mockRpcState.updateErrors.push(
      Object.assign(new Error('No pool found for connection hash: pool-hash.'), {
        code: grpc.status.NOT_FOUND,
        details: 'No pool found for connection hash: pool-hash.',
      }),
    );

    await expect(client.executePreparedUpdate('INSERT INTO t VALUES (1)', [], {}, 'prepared-handle'))
      .rejects.toBeInstanceOf(OjpSqlError);
    expect(mockRpcState.connectRequests.filter(({ url: value }) => value !== '')).toHaveLength(1);
    await client.close();
  });

  it('does not treat unrelated NOT_FOUND errors as missing pools', async () => {
    const client = new OjpClient(url);
    await client.connect();
    mockRpcState.updateErrors.push(
      Object.assign(new Error('Resource was not found.'), {
        code: grpc.status.NOT_FOUND,
        details: 'Resource was not found.',
      }),
    );

    await expect(client.executeUpdate('INSERT INTO t VALUES (1)')).rejects.toBeInstanceOf(OjpSqlError);
    expect(mockRpcState.connectRequests.filter(({ url: value }) => value !== '')).toHaveLength(1);
    await client.close();
  });
});
