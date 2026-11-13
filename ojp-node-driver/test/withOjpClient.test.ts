import { OjpClient, withOjpClient } from '../src';

describe('withOjpClient', () => {
  const session = {
    connHash: 'pool',
    clientUUID: 'client',
    sessionUUID: '',
    sessionStatus: 'SESSION_ACTIVE' as const,
  };
  let connect: jest.SpyInstance;
  let close: jest.SpyInstance;

  beforeEach(() => {
    connect = jest.spyOn(OjpClient.prototype, 'connect').mockResolvedValue(session);
    close = jest.spyOn(OjpClient.prototype, 'close').mockResolvedValue();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('connects once, gives the client to the callback, and closes it after success', async () => {
    const result = await withOjpClient('jdbc:ojp[localhost:1059]_h2:mem:test', async (client) => {
      expect(client).toBeInstanceOf(OjpClient);
      return 'result';
    });

    expect(result).toBe('result');
    expect(connect).toHaveBeenCalledTimes(1);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('closes the client when the callback fails', async () => {
    const failure = new Error('operation failed');

    await expect(
      withOjpClient('jdbc:ojp[localhost:1059]_h2:mem:test', async () => {
        throw failure;
      }),
    ).rejects.toBe(failure);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('closes the client when connecting fails', async () => {
    const failure = new Error('connect failed');
    connect.mockRejectedValue(failure);

    await expect(
      withOjpClient('jdbc:ojp[localhost:1059]_h2:mem:test', async () => 'unreachable'),
    ).rejects.toBe(failure);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('creates a new client for every scope instead of reusing virtual connections', async () => {
    const clients: OjpClient[] = [];
    for (let i = 0; i < 2; i++) {
      await withOjpClient('jdbc:ojp[localhost:1059]_h2:mem:test', (client) => {
        clients.push(client);
      });
    }

    expect(clients[0]).not.toBe(clients[1]);
  });
});
