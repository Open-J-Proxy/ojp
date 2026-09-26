import { OjpTypedParam } from '@ojp/node-driver';
import { OjpMssqlPool } from '../src/mssql/OjpMssqlPool';
import { OjpMssqlRequest } from '../src/mssql/OjpMssqlRequest';
import { mssqlTypes } from '../src/mssql/mssqlTypes';

const mockConnect = jest.fn();
const mockExecuteQuery = jest.fn();
const mockExecuteUpdate = jest.fn();
const mockStartTransaction = jest.fn();
const mockCommit = jest.fn();
const mockRollback = jest.fn();
const mockClose = jest.fn();

jest.mock('@ojp/node-driver', () => {
  const actual = jest.requireActual('@ojp/node-driver');
  return {
    ...actual,
    OjpClient: jest.fn().mockImplementation(() => ({
      connect: mockConnect,
      executeQuery: mockExecuteQuery,
      executeUpdate: mockExecuteUpdate,
      startTransaction: mockStartTransaction,
      commit: mockCommit,
      rollback: mockRollback,
      close: mockClose,
    })),
  };
});

function connectAsync(pool: OjpMssqlPool): Promise<OjpMssqlPool> {
  return new Promise((resolve, reject) => {
    pool.connect((err, connected) => (err || !connected ? reject(err ?? new Error('no pool')) : resolve(connected)));
  });
}

describe('OjpMssqlPool', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockConnect.mockResolvedValue(undefined);
    mockClose.mockResolvedValue(undefined);
  });

  it('should throw synchronously when ojpUrl is missing', () => {
    expect(() => new OjpMssqlPool({} as never)).toThrow(/ojpUrl/);
  });

  it('should return itself synchronously from connect()', () => {
    const pool = new OjpMssqlPool({ ojpUrl: 'jdbc:ojp[localhost:1065]_sqlserver://host:1433;databaseName=db' });
    const returned = pool.connect();
    expect(returned).toBe(pool);
  });

  it('should warm and release exactly one session when connect() is given a callback', async () => {
    const pool = new OjpMssqlPool({ ojpUrl: 'jdbc:ojp[localhost:1065]_sqlserver://host:1433;databaseName=db' });
    await connectAsync(pool);
    expect(mockConnect).toHaveBeenCalledTimes(1);
  });

  it('should not exceed "pool.max" concurrently connected sessions', async () => {
    const pool = new OjpMssqlPool({
      ojpUrl: 'jdbc:ojp[localhost:1065]_sqlserver://host:1433;databaseName=db',
      pool: { max: 1 },
    });

    const clientA = await pool.acquireClient();
    let secondAcquired = false;
    const secondPromise = pool.acquireClient().then((client) => {
      secondAcquired = true;
      return client;
    });

    await new Promise((resolve) => setImmediate(resolve));
    expect(secondAcquired).toBe(false);
    expect(mockConnect).toHaveBeenCalledTimes(1);

    pool.releaseClient(clientA);
    const clientB = await secondPromise;
    expect(clientB).toBe(clientA);
  });

  it('should drop a client released with an error instead of reusing it', async () => {
    const pool = new OjpMssqlPool({ ojpUrl: 'jdbc:ojp[localhost:1065]_sqlserver://host:1433;databaseName=db' });
    pool.on('error', () => { /* expected in this test */ });

    const client = await pool.acquireClient();
    pool.releaseClient(client, new Error('connection reset'));
    expect(mockClose).toHaveBeenCalledTimes(1);

    await pool.acquireClient();
    expect(mockConnect).toHaveBeenCalledTimes(2);
  });

  it('should close every tracked session on close()', async () => {
    const pool = new OjpMssqlPool({ ojpUrl: 'jdbc:ojp[localhost:1065]_sqlserver://host:1433;databaseName=db' });
    await pool.acquireClient();

    await new Promise<void>((resolve, reject) => {
      pool.close((err) => (err ? reject(err) : resolve()));
    });

    expect(mockClose).toHaveBeenCalledTimes(1);
  });
});

describe('OjpMssqlTransaction', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockConnect.mockResolvedValue(undefined);
    mockStartTransaction.mockResolvedValue(undefined);
    mockCommit.mockResolvedValue(undefined);
    mockRollback.mockResolvedValue(undefined);
  });

  function newPool(): OjpMssqlPool {
    return new OjpMssqlPool({ ojpUrl: 'jdbc:ojp[localhost:1065]_sqlserver://host:1433;databaseName=db' });
  }

  it('should lease a client and start a transaction on begin()', async () => {
    const pool = newPool();
    const trx = pool.transaction();

    await new Promise<void>((resolve, reject) => {
      trx.begin((err) => (err ? reject(err) : resolve()));
    });

    expect(mockStartTransaction).toHaveBeenCalledTimes(1);
    expect(trx.activeClient).toBeDefined();
  });

  it('should accept an isolation level argument before the callback, ignoring its value', async () => {
    const trx = newPool().transaction();

    await new Promise<void>((resolve, reject) => {
      trx.begin(mssqlTypes.ISOLATION_LEVEL.SERIALIZABLE, (err) => (err ? reject(err) : resolve()));
    });

    expect(mockStartTransaction).toHaveBeenCalledTimes(1);
  });

  it('should commit and release the pinned client back to the pool', async () => {
    const pool = newPool();
    const trx = pool.transaction();
    await new Promise<void>((resolve) => trx.begin(() => resolve()));

    await new Promise<void>((resolve, reject) => {
      trx.commit((err) => (err ? reject(err) : resolve()));
    });

    expect(mockCommit).toHaveBeenCalledTimes(1);
    expect(trx.activeClient).toBeUndefined();

    // The released client should be reusable by a fresh acquireClient() call.
    await pool.acquireClient();
    expect(mockConnect).toHaveBeenCalledTimes(1);
  });

  it('should rollback and release the pinned client back to the pool', async () => {
    const trx = newPool().transaction();
    await new Promise<void>((resolve) => trx.begin(() => resolve()));

    await new Promise<void>((resolve, reject) => {
      trx.rollback((err) => (err ? reject(err) : resolve()));
    });

    expect(mockRollback).toHaveBeenCalledTimes(1);
    expect(trx.activeClient).toBeUndefined();
  });

  it('should error when commit()/rollback() is called without a matching begin()', async () => {
    const trx = newPool().transaction();

    await expect(new Promise<void>((resolve, reject) => {
      trx.commit((err) => (err ? reject(err) : resolve()));
    })).rejects.toThrow(/begin/);

    await expect(new Promise<void>((resolve, reject) => {
      trx.rollback((err) => (err ? reject(err) : resolve()));
    })).rejects.toThrow(/begin/);
  });
});

describe('OjpMssqlRequest', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockConnect.mockResolvedValue(undefined);
  });

  function newPool(): OjpMssqlPool {
    return new OjpMssqlPool({ ojpUrl: 'jdbc:ojp[localhost:1065]_sqlserver://host:1433;databaseName=db' });
  }

  it('should route a plain SELECT through executeQuery and translate @N placeholders', async () => {
    mockExecuteQuery.mockResolvedValue({ columns: ['id'], rows: [{ id: 1 }] });
    const request = new OjpMssqlRequest(newPool());
    request.input('0', 42);

    const raw = await request.query('SELECT * FROM t WHERE id = @0');

    expect(mockExecuteQuery).toHaveBeenCalledWith('SELECT * FROM t WHERE id = ?', [42]);
    expect(raw).toEqual({ recordset: [{ id: 1 }], rowsAffected: [1] });
  });

  it('should route a plain UPDATE through executeUpdate', async () => {
    mockExecuteUpdate.mockResolvedValue(3);
    const request = new OjpMssqlRequest(newPool());
    request.input('0', 'x');

    const raw = await request.query('UPDATE t SET a = @0');

    expect(mockExecuteUpdate).toHaveBeenCalledWith('UPDATE t SET a = ?', ['x']);
    expect(raw).toEqual({ recordset: [], rowsAffected: [3] });
  });

  it('should support the node-style (err, raw) callback signature', (done) => {
    mockExecuteUpdate.mockResolvedValue(1);
    const request = new OjpMssqlRequest(newPool());

    request.query('DELETE FROM t', (err, raw) => {
      expect(err).toBeNull();
      expect(raw).toEqual({ recordset: [], rowsAffected: [1] });
      done();
    });
  });

  it('should wrap a value in OjpTypedParam when .input() is called with an explicit mssql type', async () => {
    mockExecuteUpdate.mockResolvedValue(1);
    const request = new OjpMssqlRequest(newPool());
    request.input('0', mssqlTypes.Decimal(10, 2), '123.45');

    await request.query('UPDATE t SET price = @0');

    const [, params] = mockExecuteUpdate.mock.calls[0];
    expect(params[0]).toBeInstanceOf(OjpTypedParam);
    expect((params[0] as OjpTypedParam).value).toBe('123.45');
    expect((params[0] as OjpTypedParam).type).toBe('PT_BIG_DECIMAL');
  });

  it('should route queries against a transaction through its pinned client, not the pool', async () => {
    mockExecuteQuery.mockResolvedValue({ columns: ['id'], rows: [{ id: 1 }] });
    mockStartTransaction.mockResolvedValue(undefined);
    const pool = newPool();
    const trx = pool.transaction();
    await new Promise<void>((resolve) => trx.begin(() => resolve()));
    jest.clearAllMocks();
    mockExecuteQuery.mockResolvedValue({ columns: ['id'], rows: [{ id: 1 }] });

    const request = new OjpMssqlRequest(trx);
    await request.query('SELECT 1');

    expect(mockExecuteQuery).toHaveBeenCalledTimes(1);
    expect(mockConnect).not.toHaveBeenCalled();
  });

  it('should error when querying a transaction that has not been begun', async () => {
    const trx = newPool().transaction();
    const request = new OjpMssqlRequest(trx);

    await expect(request.query('SELECT 1')).rejects.toThrow(/no active transaction/);
  });

  it('should treat the DECLARE/INSERT-OUTPUT-INTO/SELECT batch as a query producing a recordset', async () => {
    mockExecuteQuery.mockResolvedValue({ columns: ['id'], rows: [{ id: 7 }] });
    const request = new OjpMssqlRequest(newPool());
    request.input('0', 'value');

    const batch = 'DECLARE @OutputTable TABLE (id int); '
      + 'INSERT INTO t (a) OUTPUT INSERTED.id INTO @OutputTable VALUES (@0); '
      + 'SELECT * FROM @OutputTable';
    const raw = await request.query(batch);

    expect(mockExecuteQuery).toHaveBeenCalledTimes(1);
    expect(mockExecuteUpdate).not.toHaveBeenCalled();
    expect(raw).toEqual({ recordset: [{ id: 7 }], rowsAffected: [1] });
  });
});
