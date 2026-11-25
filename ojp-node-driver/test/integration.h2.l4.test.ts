import { randomBytes } from 'crypto';
import { OjpClient, OjpSqlError, TransactionIsolation, TransactionIsolationLevel } from '../src';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_TEST_H2 === 'true';
const endpoint = process.env.OJP_TEST_H2_ADDR ?? 'localhost:1059';
const connections = readConnectionFixtures('h2_connections.csv', process.env.OJP_TEST_H2_CSV);

if (enabled && !process.env.OJP_TEST_H2_ADDR) {
  throw new Error('OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true');
}

(enabled ? describe : describe.skip).each(connections)(
  'H2 L4 for %s',
  (jdbcUrl: string, user: string, fixturePassword: string) => {
    const url = ojpUrl(endpoint, jdbcUrl);
    const credentials = {
      user,
      password: process.env.OJP_TEST_H2_PASSWORD ?? fixturePassword,
    };
    const table = `ojp_node_h2_l4_${randomBytes(6).toString('hex')}`;
    const newClient = async (): Promise<OjpClient> => {
      const client = new OjpClient(url, credentials);
      await client.connect();
      return client;
    };
    const readRows = async (): Promise<Record<string, unknown>[]> => {
      const verifier = await newClient();
      try {
        return (await verifier.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`)).rows;
      } finally {
        await verifier.close();
      }
    };

    beforeAll(async () => {
      const setup = await newClient();
      try {
        await setup.executeUpdate(
          `CREATE TABLE ${table} (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)`,
        );
      } finally {
        await setup.close();
      }
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
      const cleanup = await newClient();
      try {
        await cleanup.executeUpdate(`DROP TABLE IF EXISTS ${table}`);
      } finally {
        await cleanup.close();
      }
    }, 30_000);

    it('starts and ends transactions from autocommit mode and gets/sets isolation', async () => {
      const client = await newClient();
      let originalIsolation: TransactionIsolationLevel | undefined;
      try {
        expect(client.getAutoCommit()).toBe(true);

        originalIsolation = await client.getTransactionIsolation();
        expect(originalIsolation).toBe(TransactionIsolation.READ_COMMITTED);
        await client.setTransactionIsolation(TransactionIsolation.SERIALIZABLE);
        await expect(client.getTransactionIsolation()).resolves.toBe(TransactionIsolation.SERIALIZABLE);

        await client.startTransaction();
        expect(client.getAutoCommit()).toBe(false);
        await client.rollback();
        expect(client.getAutoCommit()).toBe(true);
      } finally {
        try {
          if (originalIsolation !== undefined) {
            await client.setTransactionIsolation(originalIsolation);
            await expect(client.getTransactionIsolation()).resolves.toBe(originalIsolation);
          }
        } finally {
          await client.close();
        }
      }
    }, 30_000);

    it('commits changes durably and autocommits subsequent statements', async () => {
      const client = await newClient();
      try {
        await client.startTransaction();
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'committed']);
        await client.commit();
        expect(client.getAutoCommit()).toBe(true);
        expect(await client.executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
          [2, 'autocommit'],
        )).toBe(1);
      } finally {
        await client.close();
      }

      await expect(readRows()).resolves.toEqual([
        { ID: 1, LABEL: 'committed' },
        { ID: 2, LABEL: 'autocommit' },
      ]);
    }, 30_000);

    it('rolls back changes durably and autocommits subsequent statements', async () => {
      const client = await newClient();
      try {
        await client.startTransaction();
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'rolled back']);
        await client.rollback();
        expect(client.getAutoCommit()).toBe(true);
        expect(await client.executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
          [2, 'autocommit'],
        )).toBe(1);
      } finally {
        await client.close();
      }

      await expect(readRows()).resolves.toEqual([{ ID: 2, LABEL: 'autocommit' }]);
    }, 30_000);

    it('commits successful withTransaction work and rolls back a failed callback', async () => {
      const client = await newClient();
      const failure = new Error('transaction callback failed');
      try {
        await expect(client.withTransaction(async (transaction) => {
          expect(await transaction.executeUpdate(
            `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
            [1, 'committed'],
          )).toBe(1);
          return 'result';
        })).resolves.toBe('result');
        expect(client.getAutoCommit()).toBe(true);

        await expect(client.withTransaction(async (transaction) => {
          await transaction.executeUpdate(
            `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
            [2, 'rolled back'],
          );
          throw failure;
        })).rejects.toBe(failure);
        expect(client.getAutoCommit()).toBe(true);
      } finally {
        await client.close();
      }

      await expect(readRows()).resolves.toEqual([{ ID: 1, LABEL: 'committed' }]);
    }, 30_000);

    it('rolls back to a named savepoint and commits work from before the savepoint', async () => {
      const client = await newClient();
      try {
        await client.startTransaction();
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before']);
        const savepoint = await client.setSavepoint('partial_rollback');
        expect(savepoint.name).toBe('partial_rollback');
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'after']);

        await client.rollback(savepoint);
        await expect(client.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`))
          .resolves.toMatchObject({ rows: [{ ID: 1, LABEL: 'before' }] });
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [3, 'continued']);
        await client.commit();
      } finally {
        await client.close();
      }

      await expect(readRows()).resolves.toEqual([
        { ID: 1, LABEL: 'before' },
        { ID: 3, LABEL: 'continued' },
      ]);
    }, 30_000);

    it('supports nested savepoints and invalidates later handles after rollback', async () => {
      const client = await newClient();
      try {
        await client.startTransaction();
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'base']);
        const first = await client.setSavepoint();
        expect(first.name).toBeUndefined();
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'middle']);
        const second = await client.setSavepoint('second');
        expect(second.name).toBe('second');
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [3, 'last']);

        await client.rollback(second);
        await expect(client.executeQuery(`SELECT id FROM ${table} ORDER BY id`))
          .resolves.toMatchObject({ rows: [{ ID: 1 }, { ID: 2 }] });
        await client.rollback(first);
        await expect(client.executeQuery(`SELECT id FROM ${table} ORDER BY id`))
          .resolves.toMatchObject({ rows: [{ ID: 1 }] });
        await expect(client.rollback(second)).rejects.toThrow(/transaction|savepoint/i);
        await client.commit();
      } finally {
        await client.close();
      }

      await expect(readRows()).resolves.toEqual([{ ID: 1, LABEL: 'base' }]);
    }, 30_000);

    it('rolls a prepared batch back to a savepoint without losing preceding rows', async () => {
      const client = await newClient();
      const insert = client.prepareStatement(`INSERT INTO ${table} (id, label) VALUES (?, ?)`);
      try {
        await client.startTransaction();
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before batch']);
        const savepoint = await client.setSavepoint('before_batch');
        for (let id = 2; id <= 5; id++) {
          await insert.addBatch([id, `batch-${id}`]);
        }
        expect(await insert.executeBatch()).toEqual([1, 1, 1, 1]);
        await client.rollback(savepoint);
        await expect(client.executeQuery(`SELECT id, label FROM ${table} ORDER BY id`))
          .resolves.toMatchObject({ rows: [{ ID: 1, LABEL: 'before batch' }] });
        await client.commit();
      } finally {
        await insert.close();
        await client.close();
      }

      await expect(readRows()).resolves.toEqual([{ ID: 1, LABEL: 'before batch' }]);
    }, 30_000);

    it('recovers from a duplicate-key error by rolling back to a savepoint', async () => {
      const client = await newClient();
      try {
        await client.startTransaction();
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'valid']);
        const savepoint = await client.setSavepoint('before_error');
        await expect(client.executeUpdate(
          `INSERT INTO ${table} (id, label) VALUES (?, ?)`,
          [1, 'duplicate'],
        )).rejects.toMatchObject({
          name: 'OjpSqlError',
          sqlState: '23505',
          vendorCode: expect.any(Number),
        } satisfies Partial<OjpSqlError>);

        await client.rollback(savepoint);
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'recovered']);
        await client.commit();
      } finally {
        await client.close();
      }

      await expect(readRows()).resolves.toEqual([
        { ID: 1, LABEL: 'valid' },
        { ID: 2, LABEL: 'recovered' },
      ]);
    }, 30_000);

    it('releases savepoints and preserves transaction data', async () => {
      const client = await newClient();
      try {
        await client.startTransaction();
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [1, 'before release']);
        const savepoint = await client.setSavepoint('release_test');
        await client.executeUpdate(`INSERT INTO ${table} (id, label) VALUES (?, ?)`, [2, 'after release']);

        await client.releaseSavepoint(savepoint);
        await expect(client.rollback(savepoint)).rejects.toThrow(/transaction|savepoint/i);
        await expect(client.executeQuery(`SELECT id FROM ${table} ORDER BY id`))
          .resolves.toMatchObject({ rows: [{ ID: 1 }, { ID: 2 }] });
        await client.commit();
      } finally {
        await client.close();
      }

      await expect(readRows()).resolves.toEqual([
        { ID: 1, LABEL: 'before release' },
        { ID: 2, LABEL: 'after release' },
      ]);
    }, 30_000);

    it('rejects savepoint use after commit or rollback completes its transaction', async () => {
      const client = await newClient();
      try {
        await client.startTransaction();
        const committedSavepoint = await client.setSavepoint('expired_after_commit');
        await client.commit();
        await expect(client.rollback(committedSavepoint)).rejects.toThrow(/transaction|savepoint/i);

        await client.startTransaction();
        const rolledBackSavepoint = await client.setSavepoint('expired_after_rollback');
        await client.rollback();
        await expect(client.rollback(rolledBackSavepoint)).rejects.toThrow(/transaction|savepoint/i);
      } finally {
        await client.close();
      }
    }, 30_000);
  },
);
