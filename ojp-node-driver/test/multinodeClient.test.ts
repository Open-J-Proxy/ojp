import * as grpc from '@grpc/grpc-js';

interface MockSession {
  connHash: string;
  clientUUID: string;
  sessionUUID: string;
  sessionStatus: 'SESSION_ACTIVE';
}

const mockRpcState: {
  connectRequests: Array<{ url: string; clusterHealth?: string }>;
  updateErrors: Array<Error | undefined>;
  updateAddresses: string[];
  sessionUUID: string;
} = {
  connectRequests: [],
  updateErrors: [],
  updateAddresses: [],
  sessionUUID: '',
};

jest.mock('../src/proto/loader', () => ({
  loadStatementServiceGrpcObject: () => ({
    com: {
      openjproxy: {
        grpc: {
          StatementService: class {
            constructor(private readonly address: string) {}

            connect(
              request: { url: string; clientUUID: string; clusterHealth?: string },
              callbackOrOptions: unknown,
              maybeCallback?: (err: Error | null, session: MockSession) => void,
            ): void {
              const callback = typeof callbackOrOptions === 'function'
                ? callbackOrOptions as (err: Error | null, session: MockSession) => void
                : maybeCallback!;
              mockRpcState.connectRequests.push({
                url: request.url,
                clusterHealth: request.clusterHealth,
              });
              callback(null, {
                connHash: request.url ? 'pool-hash' : '',
                clientUUID: request.clientUUID,
                sessionUUID: request.url ? mockRpcState.sessionUUID : '',
                sessionStatus: 'SESSION_ACTIVE',
              });
            }

            executeUpdate(
              _request: unknown,
              callback: (err: Error | null, result: { intValue: number }) => void,
            ): void {
              mockRpcState.updateAddresses.push(this.address);
              const error = mockRpcState.updateErrors.shift();
              if (error) {
                callback(error, { intValue: 0 });
              } else {
                callback(null, { intValue: 1 });
              }
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
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';

describe('OjpClient multinode pool cache', () => {
  const url = 'jdbc:ojp[localhost:1059]_h2:mem:l7';

  beforeEach(() => {
    mockRpcState.connectRequests = [];
    mockRpcState.updateErrors = [];
    mockRpcState.updateAddresses = [];
    mockRpcState.sessionUUID = '';
    resetEndpointHealthForTests();
    resetPoolCacheForTests();
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
