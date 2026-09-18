/**
 * Real integration tests against an ojp-server + PostgreSQL running for real
 * (not mocked). Requires:
 *   - ojp-server listening on localhost:1065, with the PostgreSQL driver loaded in
 *     ojp-libs/ (see README.md, "Testing locally" section)
 *   - PostgreSQL reachable at the URL defined in OJP_TEST_BACKEND_URL
 *
 * Only runs when OJP_ENABLE_INTEGRATION_TESTS=true, so it doesn't break the default
 * `npm test` (unit tests, no external infrastructure) in environments without the
 * server running.
 */
import { OjpClient } from '../src/client/OjpClient';

const enabled = process.env.OJP_ENABLE_INTEGRATION_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;

const OJP_URL = process.env.OJP_TEST_URL ?? 'jdbc:ojp[localhost:1065]_postgresql://localhost:5442/ojptest';
const OJP_USER = process.env.OJP_TEST_USER ?? 'ojptest';
const OJP_PASSWORD = process.env.OJP_TEST_PASSWORD ?? 'ojptest123';

/**
 * IMPORTANT — transaction/autocommit semantics:
 * OJP replicates the real JDBC contract: `startTransaction()` turns off autocommit on
 * the physical connection on the server. Unlike `java.sql.Connection` (where autocommit
 * must be restored explicitly via `setAutoCommit(true)`), this client always restores
 * autocommit on the physical connection right after `commit()`/`rollback()` return (see
 * `OjpClient.restoreAutoCommit()`), because every `startTransaction()`/`commit()`/
 * `rollback()` pair here is meant to be a self-contained unit of work — matching the
 * plain autocommit session semantics TypeORM/`@ojp/typeorm-driver` assume between
 * transactions. Each test below still uses its **own connection**
 * (`beforeEach`/`afterEach`) to keep transactional state fully isolated between tests,
 * which remains the recommended pattern for real applications (one connection per unit
 * of work).
 */
describeIfEnabled('OjpClient - real integration (ojp-server + PostgreSQL)', () => {
  const tableName = `ojp_node_driver_test_${Date.now()}`;

  const newClient = async (): Promise<OjpClient> => {
    const c = new OjpClient(OJP_URL, { user: OJP_USER, password: OJP_PASSWORD });
    await c.connect();
    return c;
  };

  let client: OjpClient;

  beforeAll(async () => {
    client = await newClient();
    await client.executeUpdate(
      `CREATE TABLE ${tableName} (id INT PRIMARY KEY, label VARCHAR(100), created_at TIMESTAMP)`,
    );
  }, 30_000);

  afterAll(async () => {
    if (client) {
      await client.executeUpdate(`DROP TABLE IF EXISTS ${tableName}`);
      await client.close();
    }
  }, 30_000);

  let txClient: OjpClient;

  afterEach(async () => {
    if (txClient) {
      await txClient.close();
    }
  });

  it('should insert and read a row with parameters of varied types', async () => {
    const now = new Date('2026-01-15T10:30:00.000Z');
    const affected = await client.executeUpdate(
      `INSERT INTO ${tableName} (id, label, created_at) VALUES (?, ?, ?)`,
      [1, 'first row', now],
    );
    expect(affected).toBe(1);

    const { columns, rows } = await client.executeQuery(`SELECT id, label, created_at FROM ${tableName} WHERE id = ?`, [1]);
    expect(columns.map((c) => c.toLowerCase())).toEqual(['id', 'label', 'created_at']);
    expect(rows).toHaveLength(1);
    expect(rows[0].id).toBe(1);
    expect(rows[0].label).toBe('first row');
  }, 15_000);

  it('should update and delete rows', async () => {
    await client.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [2, 'to be updated']);

    const updated = await client.executeUpdate(`UPDATE ${tableName} SET label = ? WHERE id = ?`, ['updated', 2]);
    expect(updated).toBe(1);

    const { rows: afterUpdate } = await client.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [2]);
    expect(afterUpdate[0].label).toBe('updated');

    const deleted = await client.executeUpdate(`DELETE FROM ${tableName} WHERE id = ?`, [2]);
    expect(deleted).toBe(1);

    const { rows: afterDelete } = await client.executeQuery(`SELECT * FROM ${tableName} WHERE id = ?`, [2]);
    expect(afterDelete).toHaveLength(0);
  }, 15_000);

  it('should support transaction commit', async () => {
    txClient = await newClient();
    await txClient.startTransaction();
    await txClient.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [10, 'committed']);
    await txClient.commit();

    const { rows } = await txClient.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [10]);
    expect(rows).toHaveLength(1);
    expect(rows[0].label).toBe('committed');
  }, 15_000);

  it('should support transaction rollback', async () => {
    txClient = await newClient();
    await txClient.startTransaction();
    await txClient.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [11, 'should disappear']);
    await txClient.rollback();

    const { rows } = await txClient.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [11]);
    expect(rows).toHaveLength(0);
  }, 15_000);

  /**
   * Regression test for a durability bug: after `commit()`, the physical connection
   * must go back to autocommit mode so that a later plain statement (issued outside any
   * explicit transaction, exactly as TypeORM does between two independent repository
   * calls) is committed on its own and survives the session being closed. Before
   * `OjpClient.restoreAutoCommit()` existed, this INSERT would silently run inside a
   * still-open transaction and be lost the moment the connection closed without an
   * explicit commit — undetectable by tests that only check same-session visibility.
   */
  it('should autocommit a plain statement issued after a committed transaction, surviving reconnect', async () => {
    txClient = await newClient();
    await txClient.startTransaction();
    await txClient.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [12, 'in transaction']);
    await txClient.commit();

    await txClient.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [13, 'plain after commit']);
    await txClient.close();

    const fresh = await newClient();
    try {
      const { rows } = await fresh.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [13]);
      expect(rows).toHaveLength(1);
      expect(rows[0].label).toBe('plain after commit');
    } finally {
      await fresh.close();
    }
  }, 15_000);

  /**
   * Same regression, but after a rollback instead of a commit — `rollback()` must also
   * restore autocommit so subsequent plain statements aren't silently swallowed.
   */
  it('should autocommit a plain statement issued after a rolled-back transaction, surviving reconnect', async () => {
    txClient = await newClient();
    await txClient.startTransaction();
    await txClient.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [14, 'should disappear']);
    await txClient.rollback();

    await txClient.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [15, 'plain after rollback']);
    await txClient.close();

    const fresh = await newClient();
    try {
      const { rows: rolledBack } = await fresh.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [14]);
      expect(rolledBack).toHaveLength(0);

      const { rows } = await fresh.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [15]);
      expect(rows).toHaveLength(1);
      expect(rows[0].label).toBe('plain after rollback');
    } finally {
      await fresh.close();
    }
  }, 15_000);

  it('should return null for null values', async () => {
    await client.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [20, null]);
    const { rows } = await client.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [20]);
    expect(rows[0].label).toBeNull();
  }, 15_000);

  it('should throw OjpSqlError for invalid SQL', async () => {
    txClient = await newClient();
    await expect(txClient.executeQuery('SELECT * FROM table_that_does_not_exist')).rejects.toThrow();
  }, 15_000);

  /**
   * Known limitation: the PostgreSQL JDBC driver (pgjdbc) does not implement
   * `Connection.createBlob()`/`createClob()` — it throws SQLFeatureNotSupportedException
   * ("... not yet implemented.") as soon as the ojp-server tries to initialize the LOB,
   * even for a single small chunk. This is not a bug in the Node driver or in the OJP
   * protocol — `createLob` is simply not supported against PostgreSQL backends. Backends
   * validated with full support: SQL Server (multi-chunk, see
   * `test/integration.sqlServerAg.test.ts`) and H2 (single chunk, see
   * `test/integration.lob.h2.test.ts`).
   *
   * Note: pgjdbc's exception message is localized based on the JVM's default locale
   * (e.g. "ainda não foi implementado" on a pt-BR JVM vs. "not yet implemented" on an
   * en-US one), so the assertion below matches on the method name (`createBlob`), which
   * is the only locale-invariant part of the message.
   */
  it('createLob should fail on PostgreSQL (pgjdbc does not implement createBlob/createClob)', async () => {
    txClient = await newClient();
    await expect(txClient.createLob(Buffer.from('x'), 'BLOB')).rejects.toThrow(/createBlob/i);
  }, 15_000);

  describe('real pagination (executeQueryStream)', () => {
    const pageTableName = `${tableName}_paging`;
    const total = 250; // larger than the server's default block size (100 rows/block), forcing multiple OpResult in the stream

    beforeAll(async () => {
      await client.executeUpdate(`CREATE TABLE ${pageTableName} (id INT PRIMARY KEY, label VARCHAR(100))`);
      const valuesSql = Array.from({ length: total }, (_, i) => `(${i}, 'pag-${i}')`).join(', ');
      await client.executeUpdate(`INSERT INTO ${pageTableName} (id, label) VALUES ${valuesSql}`);
    }, 30_000);

    afterAll(async () => {
      await client.executeUpdate(`DROP TABLE IF EXISTS ${pageTableName}`);
    }, 30_000);

    it('should consume a large result set (multiple blocks) on demand, row by row', async () => {
      const streamClient = await newClient();
      try {
        let count = 0;
        for await (const row of streamClient.executeQueryStream(
          `SELECT id, label FROM ${pageTableName} ORDER BY id`,
        )) {
          expect(row.id).toBe(count);
          expect(row.label).toBe(`pag-${count}`);
          count++;
        }
        expect(count).toBe(total);
      } finally {
        await streamClient.close();
      }
    }, 30_000);

    it('should cancel the stream when interrupting the iteration early and keep the connection usable', async () => {
      const streamClient = await newClient();
      try {
        let count = 0;
        for await (const row of streamClient.executeQueryStream(
          `SELECT id, label FROM ${pageTableName} ORDER BY id`,
        )) {
          count++;
          if (count === 5) {
            break; // should cancel the underlying gRPC call without breaking the connection
          }
        }
        expect(count).toBe(5);

        // The connection should remain usable normally after the early cancellation.
        const { rows } = await streamClient.executeQuery(
          `SELECT COUNT(*) AS total FROM ${pageTableName}`,
        );
        expect(Number(rows[0].total)).toBe(total);
      } finally {
        await streamClient.close();
      }
    }, 30_000);

    it('executeQuery (buffered) should keep returning the complete result set, consistent with executeQueryStream', async () => {
      const { rows } = await client.executeQuery(`SELECT id, label FROM ${pageTableName} ORDER BY id`);
      expect(rows).toHaveLength(total);
      expect(rows[0].label).toBe('pag-0');
      expect(rows[total - 1].label).toBe(`pag-${total - 1}`);
    }, 30_000);
  });
});
