import { OjpClient } from '../client/OjpClient';
import { OjpXAResource } from './OjpXAResource';

/**
 * Thin wrapper around an XA-enabled `OjpClient` session, mirroring the shape of
 * `javax.sql.XAConnection`: a physical connection that exposes both an ordinary
 * connection (for executing SQL while enlisted in a transaction branch) and an
 * `OjpXAResource` (for the transaction manager to drive two-phase commit).
 *
 * Obtained via `OjpXADataSource.getXAConnection()`.
 *
 * Unlike the reference JDBC driver, there is no separate "logical connection" layer
 * here — `getConnection()` returns the very same `OjpClient` used internally by the
 * `OjpXAResource`. Calling `client.close()` directly on it (instead of
 * `OjpXAConnection.close()`) ends the underlying session outright; always close through
 * `OjpXAConnection` when you are done with the physical connection.
 */
export class OjpXAConnection {
  private xaResource?: OjpXAResource;

  constructor(private readonly client: OjpClient) {}

  /** The underlying `OjpClient`, for executing SQL while enlisted in an XA transaction branch. */
  getConnection(): OjpClient {
    return this.client;
  }

  /** The `OjpXAResource` bound to this connection's session (created lazily, cached for the lifetime of this `OjpXAConnection`). */
  getXAResource(): OjpXAResource {
    if (!this.xaResource) {
      this.xaResource = this.client.createXAResource();
    }
    return this.xaResource;
  }

  /** Terminates the underlying session and closes the gRPC channel. Idempotent (delegates to `OjpClient.close()`). */
  async close(): Promise<void> {
    await this.client.close();
  }
}
