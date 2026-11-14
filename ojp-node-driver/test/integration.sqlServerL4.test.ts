import { OjpClient, TransactionIsolation } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_SQLSERVER_L4_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const connections = readConnectionFixtures('sqlserver_connections.csv', process.env.OJP_TEST_SQLSERVER_CSV);

describeIfEnabled.each(connections)('OjpClient - SQL Server L4: %s', (jdbcUrl, user, fixturePassword) => {
  const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';
  const url = ojpUrl(endpoint, jdbcUrl);
  const password = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? fixturePassword;
  const table = `ojp_node_l4_${Date.now()}_${Math.floor(Math.random() * 1_000_000)}`;
  let client: OjpClient;

  const newClient = async (): Promise<OjpClient> => {
    const connection = new OjpClient(url, { user, password });
    await connection.connect();
    return connection;
  };

  beforeAll(async () => {
    client = await newClient();
    await client.executeUpdate(`CREATE TABLE ${table} (id INT PRIMARY KEY, label NVARCHAR(100))`);
  }, 30_000);

  afterEach(async () => {
    const cleanup = await newClient();
    try {
      await cleanup.executeUpdate(`DELETE FROM ${table}`);
    } finally {
      await cleanup.close();
    }
  }, 30_000);

  afterAll(async () => {
    if (client) {
      try {
        await client.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
      } finally {
        await client.close();
      }
    }
  }, 30_000);

  it('starts transactions from autocommit mode and gets/sets SQL Server isolation', async () => {
    const connection = await newClient();
    try {
      expect(connection.getAutoCommit()).toBe(true);

      const originalIsolation = await connection.getTransactionIsolation();
      expect(originalIsolation).toBe(TransactionIsolation.READ_COMMITTED);
      await connection.setTransactionIsolation(TransactionIsolation.SERIALIZABLE);
      await expect(connection.getTransactionIsolation()).resolves.toBe(TransactionIsolation.SERIALIZABLE);

      await connection.startTransaction();
      expect(connection.getAutoCommit()).toBe(false);
      await connection.rollback();
      expect(connection.getAutoCommit()).toBe(true);

      await connection.setTransactionIsolation(originalIsolation);
      await expect(connection.getTransactionIsolation()).resolves.toBe(originalIsolation);
    } finally {
      await connection.close();
    }
  }, 30_000);

  it('commits changes durably and returns to autocommit for later statements', async () => {
    const connection = await newClient();
    try {
      await connection.startTransaction();
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'committed']);
      await connection.commit();
      expect(connection.getAutoCommit()).toBe(true);

      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'autocommit']);
    } finally {
      await connection.close();
    }

    const verifier = await newClient();
    try {
      const result = await verifier.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`);
      expect(result.rows).toEqual([
        { id: 1, label: 'committed' },
        { id: 2, label: 'autocommit' },
      ]);
    } finally {
      await verifier.close();
    }
  }, 30_000);

  it('rolls back changes durably and returns to autocommit for later statements', async () => {
    const connection = await newClient();
    try {
      await connection.startTransaction();
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'rolled back']);
      await connection.rollback();
      expect(connection.getAutoCommit()).toBe(true);

      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'autocommit']);
    } finally {
      await connection.close();
    }

    const verifier = await newClient();
    try {
      const result = await verifier.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`);
      expect(result.rows).toEqual([{ id: 2, label: 'autocommit' }]);
    } finally {
      await verifier.close();
    }
  }, 30_000);

  it('rolls back to a named savepoint while preserving earlier work in the transaction', async () => {
    const connection = await newClient();
    try {
      await connection.startTransaction();
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before']);
      const savepoint = await connection.setSavepoint('partial_rollback');
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'after']);
      expect(await connection.getSavepointName(savepoint)).toBe('partial_rollback');

      await connection.rollback(savepoint);
      await expect(connection.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`))
        .resolves.toMatchObject({ rows: [{ id: 1, label: 'before' }] });
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [3, 'continued']);
      await connection.commit();
    } finally {
      await connection.close();
    }

    const verifier = await newClient();
    try {
      const result = await verifier.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`);
      expect(result.rows).toEqual([
        { id: 1, label: 'before' },
        { id: 3, label: 'continued' },
      ]);
    } finally {
      await verifier.close();
    }
  }, 30_000);

  it('supports nested savepoints and rolls back incrementally to each boundary', async () => {
    const connection = await newClient();
    try {
      await connection.startTransaction();
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'base']);
      const first = await connection.setSavepoint('first');
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'middle']);
      const second = await connection.setSavepoint('second');
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [3, 'last']);

      await connection.rollback(second);
      await expect(connection.executeQuery(`SELECT id FROM ${table} ORDER BY id`))
        .resolves.toMatchObject({ rows: [{ id: 1 }, { id: 2 }] });
      await connection.rollback(first);
      await expect(connection.executeQuery(`SELECT id FROM ${table} ORDER BY id`))
        .resolves.toMatchObject({ rows: [{ id: 1 }] });
      await connection.commit();
    } finally {
      await connection.close();
    }
  }, 30_000);

  it('rolls back a prepared batch to a savepoint without losing preceding rows', async () => {
    const connection = await newClient();
    const insert = connection.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
    try {
      await connection.startTransaction();
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before batch']);
      const savepoint = await connection.setSavepoint('before_batch');
      for (let id = 2; id <= 5; id++) {
        await insert.addBatch([id, `batch-${id}`]);
      }
      await insert.executeBatch();
      await connection.rollback(savepoint);
      await expect(connection.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`))
        .resolves.toMatchObject({ rows: [{ id: 1, label: 'before batch' }] });
      await connection.commit();
    } finally {
      await insert.close();
      await connection.close();
    }
  }, 30_000);

  it('recovers from a duplicate-key error by rolling back to a savepoint', async () => {
    const connection = await newClient();
    try {
      await connection.startTransaction();
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'valid']);
      const savepoint = await connection.setSavepoint('before_error');
      await expect(connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'duplicate']))
        .rejects.toThrow();

      await connection.rollback(savepoint);
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'recovered']);
      await connection.commit();
    } finally {
      await connection.close();
    }

    const verifier = await newClient();
    try {
      const result = await verifier.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`);
      expect(result.rows).toEqual([
        { id: 1, label: 'valid' },
        { id: 2, label: 'recovered' },
      ]);
    } finally {
      await verifier.close();
    }
  }, 30_000);

  it('releases savepoints where SQL Server supports it without changing transaction data', async () => {
    const connection = await newClient();
    try {
      await connection.startTransaction();
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before release']);
      const savepoint = await connection.setSavepoint('release_test');
      await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'after release']);

      try {
        await connection.releaseSavepoint(savepoint);
      } catch {
        // SQL Server JDBC may report savepoint release as unsupported.
      }

      await expect(connection.executeQuery(`SELECT id FROM ${table} ORDER BY id`))
        .resolves.toMatchObject({ rows: [{ id: 1 }, { id: 2 }] });
      await connection.commit();
    } finally {
      await connection.close();
    }
  }, 30_000);

  it('rejects using a savepoint after its transaction has completed', async () => {
    const connection = await newClient();
    try {
      await connection.startTransaction();
      const savepoint = await connection.setSavepoint('expired');
      await connection.commit();

      await expect(connection.rollback(savepoint)).rejects.toThrow(/transaction|savepoint/i);
    } finally {
      await connection.close();
    }
  }, 30_000);
});
