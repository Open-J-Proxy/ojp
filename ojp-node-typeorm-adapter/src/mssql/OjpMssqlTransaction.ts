import { OjpClient, TransactionIsolation, TransactionIsolationLevel } from '@ojp/node-driver';
import { ConnectionCleanupError } from '../common/connections';
import type { OjpMssqlPool } from './OjpMssqlPool';

export type OjpMssqlTransactionCallback = (err: Error | null) => void;

const isolationLevels: Record<string, TransactionIsolationLevel> = {
  READ_UNCOMMITTED: TransactionIsolation.READ_UNCOMMITTED,
  READ_COMMITTED: TransactionIsolation.READ_COMMITTED,
  REPEATABLE_READ: TransactionIsolation.REPEATABLE_READ,
  SERIALIZABLE: TransactionIsolation.SERIALIZABLE,
};

/** Keeps one connection for the transaction, then closes it. */
export class OjpMssqlTransaction {
  private client: OjpClient | undefined;

  constructor(private readonly pool: OjpMssqlPool) {}

  get activeClient(): OjpClient | undefined {
    return this.client;
  }

  begin(callback?: OjpMssqlTransactionCallback): void;
  begin(isolationLevel: unknown, callback?: OjpMssqlTransactionCallback): void;
  begin(isolationLevelOrCallback?: unknown, maybeCallback?: OjpMssqlTransactionCallback): void {
    const callback = typeof isolationLevelOrCallback === 'function'
      ? isolationLevelOrCallback as OjpMssqlTransactionCallback
      : maybeCallback;
    const isolation = typeof isolationLevelOrCallback === 'function' ? undefined : isolationLevelOrCallback;
    this.start(isolation).then(
      () => callback?.(null),
      (error: Error) => callback ? callback(error) : this.pool.emit('error', error),
    );
  }

  private async start(isolation: unknown): Promise<void> {
    if (this.client) {
      throw new Error('@ojp/node-typeorm-adapter: transaction has already begun.');
    }
    let level: TransactionIsolationLevel | undefined;
    if (isolation !== undefined) {
      level = typeof isolation === 'string' ? isolationLevels[isolation] : undefined;
      if (level === undefined) {
        throw new Error(`@ojp/node-typeorm-adapter: unsupported transaction isolation level: ${String(isolation)}`);
      }
    }
    const client = await this.pool.acquireClient();
    try {
      if (level !== undefined) {
        await client.setTransactionIsolation(level);
      }
      await client.startTransaction();
      this.client = client;
    } catch (error) {
      try {
        await this.pool.releaseClient(client);
      } catch (cleanupError) {
        throw new ConnectionCleanupError(error, cleanupError);
      }
      throw error;
    }
  }

  commit(callback?: OjpMssqlTransactionCallback): void {
    this.finish('commit').then(
      () => callback?.(null),
      (error: Error) => callback ? callback(error) : this.pool.emit('error', error),
    );
  }

  rollback(callback?: OjpMssqlTransactionCallback): void {
    this.finish('rollback').then(
      () => callback?.(null),
      (error: Error) => callback ? callback(error) : this.pool.emit('error', error),
    );
  }

  private async finish(action: 'commit' | 'rollback'): Promise<void> {
    const client = this.client;
    if (!client) {
      throw new Error(`@ojp/node-typeorm-adapter: ${action}() called without a matching begin().`);
    }
    this.client = undefined;
    try {
      await client[action]();
    } catch (error) {
      try {
        await this.pool.releaseClient(client);
      } catch (cleanupError) {
        throw new ConnectionCleanupError(error, cleanupError);
      }
      throw error;
    }
    await this.pool.releaseClient(client);
  }
}
