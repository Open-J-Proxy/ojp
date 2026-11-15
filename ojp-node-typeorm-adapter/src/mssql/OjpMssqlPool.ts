import { OjpClient } from '@ojp/node-driver';
import { EventEmitter } from 'events';
import { Connections } from '../common/connections';
import { OjpMssqlTransaction } from './OjpMssqlTransaction';

export interface OjpMssqlPoolOptions {
  user?: string;
  password?: string;
  ojpUrl: string;
  ojpInsecure?: boolean;
  ojpCallTimeoutMs?: number;
  [extra: string]: unknown;
}

/** TypeORM's mssql-compatible connection factory. Connections are never pooled or reused. */
export class OjpMssqlPool extends EventEmitter {
  private readonly connections: Connections;

  constructor(options: OjpMssqlPoolOptions) {
    super();
    if (!options.ojpUrl) {
      throw new Error('@ojp/node-typeorm-adapter: missing "ojpUrl" in DataSourceOptions.extra.');
    }
    this.connections = new Connections(options.ojpUrl, {
      user: options.user,
      password: options.password,
      insecure: options.ojpInsecure,
      callTimeoutMs: options.ojpCallTimeoutMs,
    });
  }

  connect(callback?: (err: Error | null, pool?: this) => void): this {
    this.acquireClient()
      .then(async (client) => {
        await this.releaseClient(client);
        callback?.(null, this);
      })
      .catch((error: Error) => callback ? callback(error) : this.emit('error', error));
    return this;
  }

  close(callback?: (err?: Error) => void): void {
    this.connections.closeAll().then(
      () => callback?.(),
      (error: Error) => callback ? callback(error) : this.emit('error', error),
    );
  }

  transaction(): OjpMssqlTransaction {
    return new OjpMssqlTransaction(this);
  }

  acquireClient(): Promise<OjpClient> {
    return this.connections.open();
  }

  releaseClient(client: OjpClient): Promise<void> {
    return this.connections.close(client);
  }
}
