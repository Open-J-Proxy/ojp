import { OjpPgPool } from '../src/postgres/OjpPgPool';
import { OjpPgClient } from '../src/postgres/OjpPgClient';

const mockConnect = jest.fn();
const mockExecuteQuery = jest.fn();
const mockExecuteUpdate = jest.fn();
const mockStartTransaction = jest.fn();
const mockCommit = jest.fn();
const mockRollback = jest.fn();
const mockClose = jest.fn();

jest.mock('@ojp/node-driver', () => ({
  OjpClient: jest.fn().mockImplementation(() => ({
    connect: mockConnect,
    executeQuery: mockExecuteQuery,
    executeUpdate: mockExecuteUpdate,
    startTransaction: mockStartTransaction,
    commit: mockCommit,
    rollback: mockRollback,
    close: mockClose,
  })),
}));

function connectAsync(pool: OjpPgPool): Promise<{ connection: OjpPgClient; release: (err?: Error) => void }> {
  return new Promise((resolve, reject) => {
    pool.connect((err, connection, release) => {
      if (err || !connection || !release) {
        reject(err ?? new Error('connect() did not yield a connection'));
        return;
      }
      resolve({ connection, release });
    });
  });
}

describe('OjpPgPool', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockConnect.mockResolvedValue(undefined);
    mockClose.mockResolvedValue(undefined);
  });

  it('should throw synchronously when ojpUrl is missing', () => {
    expect(() => new OjpPgPool({} as never)).toThrow(/ojpUrl/);
  });

  it('should create and connect a new OjpClient on first connect()', async () => {
    const pool = new OjpPgPool({ ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://host:5432/db', user: 'u', password: 'p' });
    const { connection } = await connectAsync(pool);

    expect(mockConnect).toHaveBeenCalledTimes(1);
    expect(connection).toBeInstanceOf(OjpPgClient);
  });

  it('closes released connections and opens a fresh one', async () => {
    const pool = new OjpPgPool({ ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://host:5432/db' });

    const first = await connectAsync(pool);
    first.release();

    await connectAsync(pool);

    expect(mockClose).toHaveBeenCalledTimes(1);
    expect(mockConnect).toHaveBeenCalledTimes(2);
  });

  it('should drop a client released with an error instead of reusing it', async () => {
    const pool = new OjpPgPool({ ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://host:5432/db' });
    const first = await connectAsync(pool);
    first.release(new Error('connection reset'));
    expect(mockClose).toHaveBeenCalledTimes(1);

    await connectAsync(pool);
    // The broken client was dropped, so a second one had to be created/connected.
    expect(mockConnect).toHaveBeenCalledTimes(2);
  });

  it('opens independent connections without waiting for a client-side pool', async () => {
    const pool = new OjpPgPool({ ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://host:5432/db', max: 1 });

    const first = await connectAsync(pool);
    const second = await connectAsync(pool);
    expect(mockConnect).toHaveBeenCalledTimes(2);
    first.release();
    expect(second.connection).toBeInstanceOf(OjpPgClient);
    second.release();
    expect(mockClose).toHaveBeenCalledTimes(2);
  });

  it('should close every tracked client on end()', async () => {
    const pool = new OjpPgPool({ ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://host:5432/db' });
    await connectAsync(pool);

    await new Promise<void>((resolve, reject) => {
      pool.end((err) => (err ? reject(err) : resolve()));
    });

    expect(mockClose).toHaveBeenCalledTimes(1);
  });

  it('waits for an in-progress connection before closing it', async () => {
    let finishConnect!: () => void;
    mockConnect.mockReturnValueOnce(new Promise<void>((resolve) => { finishConnect = resolve; }));
    const pool = new OjpPgPool({ ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://host:5432/db' });
    const opening = connectAsync(pool);
    const openingError = expect(opening).rejects.toThrow('closed while connecting');
    let ended = false;
    const ending = new Promise<void>((resolve, reject) => {
      pool.end((err) => err ? reject(err) : resolve());
    }).then(() => { ended = true; });

    await Promise.resolve();
    expect(ended).toBe(false);
    expect(mockClose).not.toHaveBeenCalled();
    finishConnect();
    await openingError;
    await ending;
    expect(mockClose).toHaveBeenCalledTimes(1);
    await expect(connectAsync(pool)).rejects.toThrow('already been closed');
  });

  it('reports cleanup failures when shutdown races with connection setup', async () => {
    let finishConnect!: () => void;
    mockConnect.mockReturnValueOnce(new Promise<void>((resolve) => { finishConnect = resolve; }));
    mockClose.mockRejectedValueOnce(new Error('close failed'));
    const pool = new OjpPgPool({ ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://host:5432/db' });
    const openingError = expect(connectAsync(pool)).rejects.toThrow('close failed');
    const ending = new Promise<void>((resolve, reject) => {
      pool.end((err) => err ? reject(err) : resolve());
    });
    const endingError = expect(ending).rejects.toThrow('close failed');
    finishConnect();
    await Promise.all([openingError, endingError]);
  });
});

describe('OjpPgClient.query', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockConnect.mockResolvedValue(undefined);
  });

  async function acquireClient(): Promise<OjpPgClient> {
    const pool = new OjpPgPool({ ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://host:5432/db' });
    const { connection } = await connectAsync(pool);
    return connection;
  }

  it('should route SELECT statements through executeQuery and translate $N placeholders', async () => {
    mockExecuteQuery.mockResolvedValue({ columns: ['id'], rows: [{ id: 1 }] });
    const client = await acquireClient();

    const result = await client.query('SELECT * FROM t WHERE id = $1', [42]);

    expect(mockExecuteQuery).toHaveBeenCalledWith('SELECT * FROM t WHERE id = ?', [42]);
    expect(result).toEqual({ rows: [{ id: 1 }], rowCount: 1, command: 'SELECT' });
  });

  it('should route plain UPDATE/DELETE statements through executeUpdate', async () => {
    mockExecuteUpdate.mockResolvedValue(3);
    const client = await acquireClient();

    const result = await client.query('UPDATE t SET a = $1 WHERE b = $2', ['x', 'y']);

    expect(mockExecuteUpdate).toHaveBeenCalledWith('UPDATE t SET a = ? WHERE b = ?', ['x', 'y']);
    expect(result).toEqual({ rows: [], rowCount: 3, command: 'UPDATE' });
  });

  it('should route INSERT ... RETURNING through executeQuery, using rows.length as rowCount', async () => {
    mockExecuteQuery.mockResolvedValue({ columns: ['id'], rows: [{ id: 7 }] });
    const client = await acquireClient();

    const result = await client.query('INSERT INTO t (a) VALUES ($1) RETURNING id', ['x']);

    expect(mockExecuteQuery).toHaveBeenCalledWith('INSERT INTO t (a) VALUES (?) RETURNING id', ['x']);
    expect(result).toEqual({ rows: [{ id: 7 }], rowCount: 1, command: 'INSERT' });
  });

  it('should translate transaction sentinel statements into OjpClient RPC calls', async () => {
    const client = await acquireClient();

    await client.query('START TRANSACTION');
    expect(mockStartTransaction).toHaveBeenCalledTimes(1);

    await client.query('COMMIT');
    expect(mockCommit).toHaveBeenCalledTimes(1);

    await client.query('ROLLBACK');
    expect(mockRollback).toHaveBeenCalledTimes(1);

    expect(mockExecuteQuery).not.toHaveBeenCalled();
    expect(mockExecuteUpdate).not.toHaveBeenCalled();
  });

  it('should pass savepoint statements through as plain SQL via executeUpdate', async () => {
    mockExecuteUpdate.mockResolvedValue(0);
    const client = await acquireClient();

    await client.query('SAVEPOINT typeorm_1');

    expect(mockExecuteUpdate).toHaveBeenCalledWith('SAVEPOINT typeorm_1', []);
    expect(mockStartTransaction).not.toHaveBeenCalled();
  });
});
