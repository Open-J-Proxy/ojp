import { OjpClient, OjpClientOptions } from '@ojp/node-driver';

export class ConnectionCleanupError extends Error {
  constructor(readonly operationError: unknown, readonly cleanupError: unknown) {
    super(`OJP operation failed (${String(operationError)}) and connection cleanup failed (${String(cleanupError)}).`);
    this.name = 'ConnectionCleanupError';
  }
}

export class ConnectionShutdownError extends Error {
  constructor(readonly errors: unknown[]) {
    super(`Multiple OJP connection cleanup operations failed: ${errors.map(String).join('; ')}`);
    this.name = 'ConnectionShutdownError';
  }
}

/** Tracks live connections for shutdown; it never caches or reuses them. */
export class Connections {
  private readonly active = new Set<OjpClient>();
  private readonly opening = new Set<Promise<OjpClient>>();
  private readonly closing = new Map<OjpClient, Promise<void>>();
  private ended = false;

  constructor(private readonly url: string, private readonly options: OjpClientOptions) {}

  async open(): Promise<OjpClient> {
    if (this.ended) {
      throw new Error('OJP adapter has already been closed.');
    }
    const client = new OjpClient(this.url, this.options);
    const operation = (async () => {
      try {
        await client.connect();
        if (this.ended) {
          throw new Error('OJP adapter was closed while connecting.');
        }
        this.active.add(client);
        return client;
      } catch (error) {
        try {
          await client.close();
        } catch (cleanupError) {
          throw new ConnectionCleanupError(error, cleanupError);
        }
        throw error;
      }
    })();
    this.opening.add(operation);
    try {
      return await operation;
    } finally {
      this.opening.delete(operation);
    }
  }

  close(client: OjpClient): Promise<void> {
    const inProgress = this.closing.get(client);
    if (inProgress) {
      return inProgress;
    }
    if (!this.active.delete(client)) {
      return Promise.resolve();
    }
    const operation = client.close();
    this.closing.set(client, operation);
    operation.then(
      () => this.closing.delete(client),
      () => this.closing.delete(client),
    );
    return operation;
  }

  async closeAll(): Promise<void> {
    this.ended = true;
    const opening = await Promise.allSettled([...this.opening]);
    const closing = await Promise.allSettled(new Set([
      ...[...this.active].map((client) => this.close(client)),
      ...this.closing.values(),
    ]));
    const errors = [
      ...opening.flatMap((result) =>
        result.status === 'rejected' && result.reason instanceof ConnectionCleanupError ? [result.reason] : []),
      ...closing.flatMap((result) => result.status === 'rejected' ? [result.reason] : []),
    ];
    if (errors.length === 1) {
      throw errors[0];
    }
    if (errors.length > 1) {
      throw new ConnectionShutdownError(errors);
    }
  }
}
