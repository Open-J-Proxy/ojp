import { OjpClient, OjpClientOptions } from '../client/OjpClient';
import { OjpXAConnection } from './OjpXAConnection';

/**
 * Factory for XA-enabled connections, mirroring `javax.sql.XADataSource`. Each call to
 * `getXAConnection()` opens a brand new physical session against the ojp-server with
 * `isXA=true`, ready to be enlisted in a distributed transaction.
 *
 * ```ts
 * const xaDs = new OjpXADataSource('ojp://localhost:1059_h2:mem:testdb', { insecure: true });
 * const xaConn = await xaDs.getXAConnection();
 * const xaRes = xaConn.getXAResource();
 * const conn = xaConn.getConnection();
 *
 * await xaRes.start(xid, TMNOFLAGS);
 * await conn.executeUpdate('INSERT INTO t (id) VALUES (?)', [1]);
 * await xaRes.end(xid, TMSUCCESS);
 * const prepareResult = await xaRes.prepare(xid);
 * if (prepareResult !== XA_RDONLY) {
 *   await xaRes.commit(xid, false);
 * }
 * await xaConn.close();
 * ```
 */
export class OjpXADataSource {
  constructor(private readonly ojpUrl: string, private readonly options: OjpClientOptions = {}) {}

  /**
   * Opens a new XA session. `user`/`password` override the ones passed to the
   * constructor's `options`, mirroring `XADataSource.getXAConnection(user, password)`.
   */
  async getXAConnection(user?: string, password?: string): Promise<OjpXAConnection> {
    const client = new OjpClient(this.ojpUrl, {
      ...this.options,
      user: user ?? this.options.user,
      password: password ?? this.options.password,
      xa: true,
    });
    await client.connect();
    return new OjpXAConnection(client);
  }
}
