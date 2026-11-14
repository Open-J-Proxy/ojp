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
  sessionUUID: string;
} = {
  connectRequests: [],
  updateErrors: [],
  sessionUUID: '',
};

jest.mock('../src/proto/loader', () => ({
  loadStatementServiceGrpcObject: () => ({
    com: {
      openjproxy: {
        grpc: {
          StatementService: class {
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

import { OjpClient, OjpSqlError } from '../src';
import { resetPoolCacheForTests } from '../src/client/OjpClient';
import { resetEndpointHealthForTests } from '../src/client/endpointHealth';

describe('OjpClient multinode pool cache', () => {
  const url = 'jdbc:ojp[localhost:1059]_h2:mem:l7';

  beforeEach(() => {
    mockRpcState.connectRequests = [];
    mockRpcState.updateErrors = [];
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
