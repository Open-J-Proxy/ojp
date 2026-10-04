/**
 * Real XA (distributed transaction) integration tests against an ojp-server + PostgreSQL
 * running for real (not mocked). Requires the same environment as
 * `integration.ojpServer.test.ts`:
 *   - ojp-server listening on localhost:1065, with the PostgreSQL driver loaded
 *   - PostgreSQL reachable at OJP_TEST_BACKEND_URL (default: localhost:5442/ojptest)
 *
 * Only runs when OJP_ENABLE_XA_TESTS=true, so it doesn't break the default `npm test`
 * (unit tests, no external infrastructure) or the other integration suites.
 */
import { randomBytes } from 'crypto';
import { OjpClient } from '../src/client/OjpClient';
import { OjpXADataSource } from '../src/xa/OjpXADataSource';
import { OjpXAConnection } from '../src/xa/OjpXAConnection';
import { OjpXaError } from '../src/xa/OjpXaError';
import { Xid } from '../src/xa/xid';
import * as XA from '../src/xa/xaConstants';

const enabled = process.env.OJP_ENABLE_XA_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;

const OJP_URL = process.env.OJP_TEST_URL ?? 'jdbc:ojp[localhost:1065]_postgresql://localhost:5442/ojptest';
const OJP_USER = process.env.OJP_TEST_USER ?? 'ojptest';
const OJP_PASSWORD = process.env.OJP_TEST_PASSWORD ?? 'ojptest123';

/** Generates a fresh, unique Xid for each test — formatId is an arbitrary value only meaningful within this test suite. */
function newXid(): Xid {
  return {
    formatId: 0x4f4a50, // 'OJP' — arbitrary, test-only format id.
    globalTransactionId: randomBytes(16),
    branchQualifier: randomBytes(4),
  };
}

describeIfEnabled('OjpXAResource - real integration (ojp-server + PostgreSQL)', () => {
  const tableName = `ojp_node_driver_xa_test_${Date.now()}`;
  const dataSource = new OjpXADataSource(OJP_URL, { user: OJP_USER, password: OJP_PASSWORD });

  let setupClient: OjpClient;

  beforeAll(async () => {
    setupClient = new OjpClient(OJP_URL, { user: OJP_USER, password: OJP_PASSWORD });
    await setupClient.connect();
    await setupClient.executeUpdate(`CREATE TABLE ${tableName} (id INT PRIMARY KEY, label VARCHAR(100))`);
  }, 30_000);

  afterAll(async () => {
    await setupClient.executeUpdate(`DROP TABLE IF EXISTS ${tableName}`);
    await setupClient.close();
  }, 30_000);

  /** Reads back a row through a plain (non-XA) connection, to verify what actually landed in the database. */
  const rowExists = async (id: number): Promise<boolean> => {
    const result = await setupClient.executeQuery(`SELECT id FROM ${tableName} WHERE id = ?`, [id]);
    return result.rows.length === 1;
  };

  let xaConn: OjpXAConnection;

  afterEach(async () => {
    if (xaConn) {
      await xaConn.close();
    }
  });

  it('commits a two-phase transaction (start -> execute -> end -> prepare -> commit)', async () => {
    xaConn = await dataSource.getXAConnection();
    const xaRes = xaConn.getXAResource();
    const conn = xaConn.getConnection();
    const xid = newXid();
    const id = 1;

    await xaRes.start(xid, XA.TMNOFLAGS);
    await conn.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [id, 'two-phase-commit']);
    await xaRes.end(xid, XA.TMSUCCESS);
    const prepareResult = await xaRes.prepare(xid);
    expect([XA.XA_OK, XA.XA_RDONLY]).toContain(prepareResult);
    if (prepareResult !== XA.XA_RDONLY) {
      await xaRes.commit(xid, false);
    }

    await expect(rowExists(id)).resolves.toBe(true);
  }, 30_000);

  it('rolls back a two-phase transaction (row must not be visible)', async () => {
    xaConn = await dataSource.getXAConnection();
    const xaRes = xaConn.getXAResource();
    const conn = xaConn.getConnection();
    const xid = newXid();
    const id = 2;

    await xaRes.start(xid, XA.TMNOFLAGS);
    await conn.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [id, 'to-be-rolled-back']);
    await xaRes.end(xid, XA.TMSUCCESS);
    await xaRes.prepare(xid);
    await xaRes.rollback(xid);

    await expect(rowExists(id)).resolves.toBe(false);
  }, 30_000);

  it('commits with the one-phase optimization (start -> execute -> end -> commit(onePhase=true))', async () => {
    xaConn = await dataSource.getXAConnection();
    const xaRes = xaConn.getXAResource();
    const conn = xaConn.getConnection();
    const xid = newXid();
    const id = 3;

    await xaRes.start(xid, XA.TMNOFLAGS);
    await conn.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [id, 'one-phase-commit']);
    await xaRes.end(xid, XA.TMSUCCESS);
    await xaRes.commit(xid, true);

    await expect(rowExists(id)).resolves.toBe(true);
  }, 30_000);

  it('recover() lists a prepared (in-doubt) transaction branch before it is resolved', async () => {
    xaConn = await dataSource.getXAConnection();
    const xaRes = xaConn.getXAResource();
    const conn = xaConn.getConnection();
    const xid = newXid();
    const id = 4;

    await xaRes.start(xid, XA.TMNOFLAGS);
    await conn.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [id, 'in-doubt']);
    await xaRes.end(xid, XA.TMSUCCESS);
    const prepareResult = await xaRes.prepare(xid);

    if (prepareResult === XA.XA_RDONLY) {
      // Nothing to recover — the resource manager already forgot the branch. Not expected for an INSERT, but guard anyway.
      return;
    }

    const inDoubt = await xaRes.recover(XA.TMSTARTRSCAN | XA.TMENDRSCAN);
    expect(inDoubt.some((x) => x.globalTransactionId.equals(xid.globalTransactionId))).toBe(true);

    // Clean up: resolve the branch we just found in-doubt so it doesn't leak into later tests.
    await xaRes.commit(xid, false);
    await expect(rowExists(id)).resolves.toBe(true);
  }, 30_000);

  it('isSameRM() reports whether two XA resources share the same resource manager', async () => {
    xaConn = await dataSource.getXAConnection();
    const otherConn = await dataSource.getXAConnection();
    try {
      const result = await xaConn.getXAResource().isSameRM(otherConn.getXAResource());
      expect(typeof result).toBe('boolean');
    } finally {
      await otherConn.close();
    }
  }, 30_000);

  it('maps an unexpected resource-manager error (commit on an unknown xid) to OjpXaError', async () => {
    xaConn = await dataSource.getXAConnection();
    const xaRes = xaConn.getXAResource();
    const unknownXid = newXid();

    // The PostgreSQL driver raises PGXAException for an xid it never prepared — the
    // ojp-server surfaces this as a gRPC-level failure (not `XaResponse.success=false`),
    // so it's classified the same way as any other resource-manager error: XAER_RMERR.
    let caught: unknown;
    try {
      await xaRes.commit(unknownXid, true);
    } catch (err) {
      caught = err;
    }

    expect(caught).toBeInstanceOf(OjpXaError);
    expect((caught as OjpXaError).errorCode).toBe(XA.XAER_RMERR);
  }, 30_000);

  it('rejects startTransaction()/commit()/rollback() on the ordinary connection of an XA session', async () => {
    xaConn = await dataSource.getXAConnection();
    const conn = xaConn.getConnection();

    await expect(conn.startTransaction()).rejects.toThrow(/createXAResource/);
    await expect(conn.commit()).rejects.toThrow(/createXAResource/);
    await expect(conn.rollback()).rejects.toThrow(/createXAResource/);
  }, 30_000);
});
