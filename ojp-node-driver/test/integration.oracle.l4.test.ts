import { randomBytes } from 'crypto';
import { OjpClient, TransactionIsolation, TransactionIsolationLevel } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_ORACLE_TESTS === 'true';
const connections = readConnectionFixtures('oracle_connections.csv', process.env.OJP_TEST_ORACLE_CSV);
const endpoint = process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059';

(enabled ? describe : describe.skip).each(connections)(
  'Oracle L4 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const options = { user, password: process.env.OJP_TEST_ORACLE_PASSWORD ?? fixturePassword };
    const table = `ojp_node_l4_${randomBytes(6).toString('hex')}`;
    let client: OjpClient;

    const readRows = async (): Promise<Record<string, unknown>[]> =>
      (await client.executeQuery(`SELECT id AS "ID", label AS "LABEL" FROM ${table} ORDER BY id`)).rows;

    beforeAll(async () => {
      client = new OjpClient(url, options);
      await client.connect();
      await client.executeUpdate(`CREATE TABLE ${table} (id NUMBER(10) PRIMARY KEY, label VARCHAR2(80) NOT NULL)`);
    }, 60_000);

    afterEach(async () => {
      await client.executeUpdate(`DELETE FROM ${table}`);
    }, 30_000);

    afterAll(async () => {
      if (client) {
        try {
          await client.executeUpdate(`DROP TABLE ${table}`);
        } finally {
          await client.close();
        }
      }
    }, 60_000);

    it('starts in autocommit mode, changes isolation, and restores the original level', async () => {
      expect(client.getAutoCommit()).toBe(true);
      const original = await client.getTransactionIsolation();
      expect(original).toBe(TransactionIsolation.READ_COMMITTED);
      try {
        await client.setTransactionIsolation(TransactionIsolation.SERIALIZABLE);
        await expect(client.getTransactionIsolation()).resolves.toBe(TransactionIsolation.SERIALIZABLE);
        await client.startTransaction();
        expect(client.getAutoCommit()).toBe(false);
        await client.rollback();
        expect(client.getAutoCommit()).toBe(true);
      } finally {
        await client.setTransactionIsolation(original as TransactionIsolationLevel);
      }
      await expect(client.getTransactionIsolation()).resolves.toBe(original);
    }, 30_000);

    it('commits durable rows and rolls back uncommitted rows', async () => {
      await client.startTransaction();
      await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'committed']);
      await client.commit();
      expect(client.getAutoCommit()).toBe(true);

      await client.startTransaction();
      await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'rolled back']);
      await client.rollback();
      expect(client.getAutoCommit()).toBe(true);
      await expect(readRows()).resolves.toEqual([{ ID: '1', LABEL: 'committed' }]);
    }, 30_000);

    it('commits successful transaction callbacks and rolls back failed callbacks', async () => {
      const failure = new Error('transaction callback failed');
      await expect(client.withTransaction(async (connection) => {
        await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'callback commit']);
        return 'done';
      })).resolves.toBe('done');
      await expect(client.withTransaction(async (connection) => {
        await connection.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'callback rollback']);
        throw failure;
      })).rejects.toBe(failure);
      expect(client.getAutoCommit()).toBe(true);
      await expect(readRows()).resolves.toEqual([{ ID: '1', LABEL: 'callback commit' }]);
    }, 30_000);

    it('rolls back to nested savepoints and expires handles after transaction completion', async () => {
      await client.startTransaction();
      await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before']);
      const first = await client.setSavepoint('first_point');
      await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'middle']);
      const second = await client.setSavepoint('second_point');
      await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [3, 'after']);

      await client.rollback(second);
      await expect(readRows()).resolves.toEqual([{ ID: '1', LABEL: 'before' }, { ID: '2', LABEL: 'middle' }]);
      await client.rollback(first);
      await expect(readRows()).resolves.toEqual([{ ID: '1', LABEL: 'before' }]);
      await client.commit();
      await expect(client.rollback(first)).rejects.toThrow(/transaction|savepoint/i);
      await expect(readRows()).resolves.toEqual([{ ID: '1', LABEL: 'before' }]);
    }, 30_000);

    it('matches Oracle releaseSavepoint behavior and keeps the savepoint usable', async () => {
      await client.startTransaction();
      await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before release']);
      const point = await client.setSavepoint('release_point');
      await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'after release']);
      await expect(client.releaseSavepoint(point)).rejects.toBeInstanceOf(Error);
      await expect(readRows()).resolves.toEqual([
        { ID: '1', LABEL: 'before release' },
        { ID: '2', LABEL: 'after release' },
      ]);
      await client.rollback(point);
      await expect(readRows()).resolves.toEqual([{ ID: '1', LABEL: 'before release' }]);
      await client.commit();
    }, 30_000);
  },
);
