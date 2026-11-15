import { EventEmitter } from 'events';
import { Connections } from '../common/connections';
import { OjpPgClient } from './OjpPgClient';

export interface OjpPgPoolOptions {
  user?: string;
  password?: string;
  ojpUrl: string;
  ojpInsecure?: boolean;
  ojpCallTimeoutMs?: number;
  [extra: string]: unknown;
}

/** TypeORM's pg-compatible connection factory. Connections are never pooled or reused. */
export class OjpPgPool extends EventEmitter {
  private readonly connections: Connections;

  constructor(options: OjpPgPoolOptions) {
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

  connect(callback: (err: Error | null, connection?: OjpPgClient, release?: (err?: Error) => void) => void): void {
    this.connections.open().then((client) => {
      const release = (): void => {
        void this.connections.close(client).catch((error: Error) => this.emit('error', error));
      };
      callback(null, new OjpPgClient(client, release), release);
    }, (error: Error) => callback(error));
  }

  end(callback?: (err?: Error) => void): void {
    this.connections.closeAll().then(
      () => callback?.(),
      (error: Error) => callback ? callback(error) : this.emit('error', error),
    );
  }
}
