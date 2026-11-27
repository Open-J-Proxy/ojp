import { Readable } from 'stream';
import { EventEmitter } from 'events';
import { OjpClient, OjpLobRef } from '../src';
import { ReadLobRequest, SessionInfo, StatementRequest } from '../src/proto/types';

let mockPosition = '-1';
let mockWrites: Array<{ position: string; data: Buffer }> = [];

jest.mock('../src/proto/loader', () => ({
  loadStatementServiceGrpcObject: () => ({
    com: { openjproxy: { grpc: {
      StatementService: class {
        connect(
          request: { clientUUID: string },
          _options: unknown,
          callback: (error: null, session: SessionInfo) => void,
        ): void {
          callback(null, {
            connHash: 'lob-test-pool',
            clientUUID: request.clientUUID,
            sessionUUID: '',
            sessionStatus: 'SESSION_ACTIVE',
          });
        }

        executeQuery(request: StatementRequest): Readable {
          return Object.assign(Readable.from([{
            session: request.session,
            queryResult: {
              resultSetUUID: '',
              labels: ['TEXT'],
              rows: [{ columns: [{ stringValue: 'OJP_CLOB_PREFIX:missing' }] }],
            },
          }], { objectMode: true }), { cancel: jest.fn() });
        }

        readLob(request: ReadLobRequest): Readable {
          return Object.assign(Readable.from([{
            session: request.lobReference.session,
            position: mockPosition,
            data: Buffer.alloc(0),
          }], { objectMode: true }), { cancel: jest.fn() });
        }

        createLob(): EventEmitter {
          const call = new EventEmitter();
          return Object.assign(call, {
            write: (block: { session: SessionInfo; position: string; data: Buffer }, callback: () => void) => {
              mockWrites.push(block);
              callback();
              queueMicrotask(() => call.emit('data', {
                session: block.session,
                uuid: 'clob',
                lobType: 'LT_CLOB',
              }));
            },
            end: () => queueMicrotask(() => call.emit('end')),
            destroy: (error: Error) => queueMicrotask(() => call.emit('error', error)),
          });
        }

        terminateSession(_session: SessionInfo, _options: unknown, callback: (error: null) => void): void {
          callback(null);
        }

        close(): void {}
      },
    } } },
  }),
}));

describe('LOB reference reads', () => {
  let client: OjpClient;

  beforeEach(async () => {
    mockPosition = '-1';
    mockWrites = [];
    client = new OjpClient('jdbc:ojp[localhost:1059]_h2:mem:lob-read-test');
    await client.connect();
  });

  afterEach(async () => {
    await client.close();
  });

  it('surfaces a missing reference instead of returning an empty stream', async () => {
    const stream = client.readLobStream(new OjpLobRef('missing', 'BLOB'));
    await expect(async () => {
      for await (const _chunk of stream) {
        // Consume the stream to observe errors.
      }
    }).rejects.toThrow('could not find the LOB reference');
  });

  it('surfaces a missing reference during transparent CLOB hydration', async () => {
    await expect(client.executeQuery('SELECT text FROM lob_test'))
      .rejects.toThrow('could not find the LOB reference');
  });

  it('preserves genuinely empty LOBs', async () => {
    mockPosition = '0';
    const chunks: Buffer[] = [];
    for await (const chunk of client.readLobStream(new OjpLobRef('empty', 'BLOB'))) {
      chunks.push(Buffer.from(chunk));
    }
    expect(Buffer.concat(chunks)).toEqual(Buffer.alloc(0));
    expect((await client.executeQuery('SELECT text FROM lob_test')).rows[0].TEXT).toBe('');
  });

  it.each(['é', '中', '🚀'])('does not split %s at the upload boundary', async (character) => {
    const text = `${'a'.repeat(65535)}${character}end`;
    const bytes = Buffer.from(text);
    await client.createLob(Readable.from([bytes.subarray(0, 65536), bytes.subarray(65536)]), 'CLOB');
    expect(mockWrites.map((block) => block.position)).toEqual(['1', '65536']);
    expect(mockWrites.map((block) => block.data.toString('utf8')).join('')).toBe(text);
  });

  it('writes a complete boundary-sized CLOB block without waiting for another chunk', async () => {
    await client.createLob(`${'a'.repeat(65534)}é`, 'CLOB');
    expect(mockWrites).toHaveLength(1);
    expect(mockWrites[0].data.length).toBe(65536);
  });
});
