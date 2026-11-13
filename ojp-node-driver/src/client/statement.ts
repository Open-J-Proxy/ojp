import type { OjpClient, OjpClientStatementOptions, QueryResult } from './OjpClient';
import { OjpNullParam } from './valueMapper';

export type OjpExecutionResult =
  | { kind: 'query'; result: QueryResult }
  | { kind: 'update'; affectedRows: number };

/** Plain statement API. The owning client supplies the session for every operation. */
export class OjpStatement {
  private closed = false;
  private statementUUID?: string;
  private generatedKeysStatementUUID?: string;

  constructor(private readonly client: OjpClient) {}

  async executeQuery(sql: string, params: unknown[] = []): Promise<QueryResult> {
    this.assertOpen();
    return this.client.executeQuery(sql, params);
  }

  async executeUpdate(
    sql: string,
    params: unknown[] = [],
    options: OjpClientStatementOptions = {},
  ): Promise<number> {
    this.assertOpen();
    if (this.generatedKeysStatementUUID) {
      await this.client.closePreparedStatement(this.generatedKeysStatementUUID);
      this.generatedKeysStatementUUID = undefined;
    }
    const result = await this.client.executeStatementUpdate(sql, params, options);
    this.generatedKeysStatementUUID = options.generatedKeys ? result.statementUUID : undefined;
    return result.affectedRows;
  }

  async execute(sql: string, params: unknown[] = []): Promise<OjpExecutionResult> {
    this.assertOpen();
    if (this.client.isQuery(sql)) {
      return { kind: 'query', result: await this.client.executeQuery(sql, params) };
    }
    return { kind: 'update', affectedRows: await this.executeUpdate(sql, params) };
  }

  async getGeneratedKeys(): Promise<QueryResult> {
    this.assertOpen();
    if (!this.generatedKeysStatementUUID) {
      return { columns: [], rows: [], metadata: { columnCount: 0, columns: [] } };
    }
    return this.client.getGeneratedKeys(this.generatedKeysStatementUUID);
  }

  async addBatch(sql: string): Promise<void> {
    this.assertOpen();
    this.statementUUID = await this.client.addStatementBatch(sql, this.statementUUID);
  }

  async executeBatch(): Promise<number[]> {
    this.assertOpen();
    if (!this.statementUUID) {
      return [];
    }
    return this.client.executeStatementBatch(this.statementUUID);
  }

  async clearBatch(): Promise<void> {
    this.assertOpen();
    if (this.statementUUID) {
      await this.client.clearStatementBatch(this.statementUUID, false);
    }
  }

  async close(): Promise<void> {
    if (this.closed) {
      return;
    }
    this.closed = true;
    if (this.statementUUID) {
      await this.client.closeStatement(this.statementUUID);
    }
    if (this.generatedKeysStatementUUID && this.generatedKeysStatementUUID !== this.statementUUID) {
      await this.client.closePreparedStatement(this.generatedKeysStatementUUID);
    }
  }

  private assertOpen(): void {
    if (this.closed) {
      throw new Error('Statement is closed.');
    }
  }
}

/** Prepared SQL with 1-based parameter slots and reusable binding values. */
export class OjpPreparedStatement {
  private readonly bindings = new Map<number, unknown>();
  private statementUUID?: string;
  private generatedKeysStatementUUID?: string;
  private closed = false;

  constructor(
    private readonly client: OjpClient,
    public readonly sql: string,
    private readonly options: OjpClientStatementOptions = {},
  ) {}

  setParameter(index: number, value: unknown): this {
    this.assertOpen();
    if (!Number.isSafeInteger(index) || index < 1) {
      throw new RangeError('Prepared statement parameter indexes are 1-based positive integers.');
    }
    this.bindings.set(index, value);
    return this;
  }

  setNull(index: number, sqlType: number): this {
    if (!Number.isSafeInteger(sqlType)) {
      throw new TypeError('sqlType must be an integer java.sql.Types code.');
    }
    return this.setParameter(index, new OjpNullParam(sqlType));
  }

  clearParameters(): void {
    this.assertOpen();
    this.bindings.clear();
  }

  async executeQuery(params?: unknown[]): Promise<QueryResult> {
    this.assertOpen();
    const values = this.resolveParams(params);
    if (this.client.isDmlReturning(this.sql)) {
      return this.client.executeDmlReturningQuery(this.sql, values);
    }
    return this.client.executePreparedQuery(this.sql, values);
  }

  async executeUpdate(params?: unknown[]): Promise<number> {
    this.assertOpen();
    const result = await this.client.executePreparedUpdate(
      this.sql,
      this.resolveParams(params),
      this.options,
      this.statementUUID,
    );
    this.statementUUID = result.statementUUID ?? this.statementUUID;
    this.generatedKeysStatementUUID = this.options.generatedKeys
      ? result.statementUUID ?? this.statementUUID
      : undefined;
    return result.affectedRows;
  }

  async execute(params?: unknown[]): Promise<OjpExecutionResult> {
    this.assertOpen();
    if (this.client.isQuery(this.sql)) {
      return { kind: 'query', result: await this.executeQuery(params) };
    }
    return { kind: 'update', affectedRows: await this.executeUpdate(params) };
  }

  async addBatch(params?: unknown[]): Promise<void> {
    this.assertOpen();
    this.statementUUID = await this.client.addPreparedBatch(
      this.sql,
      this.resolveParams(params),
      this.statementUUID,
      this.options,
    );
    this.bindings.clear();
  }

  async executeBatch(): Promise<number[]> {
    this.assertOpen();
    if (!this.statementUUID) {
      return [];
    }
    return this.client.executePreparedBatch(this.statementUUID);
  }

  async clearBatch(): Promise<void> {
    this.assertOpen();
    if (this.statementUUID) {
      await this.client.clearStatementBatch(this.statementUUID, true);
    }
  }

  async getGeneratedKeys(): Promise<QueryResult> {
    this.assertOpen();
    if (!this.generatedKeysStatementUUID) {
      return { columns: [], rows: [], metadata: { columnCount: 0, columns: [] } };
    }
    return this.client.getGeneratedKeys(this.generatedKeysStatementUUID);
  }

  async close(): Promise<void> {
    if (this.closed) {
      return;
    }
    this.closed = true;
    if (this.statementUUID) {
      await this.client.closePreparedStatement(this.statementUUID);
    }
  }

  private resolveParams(params?: unknown[]): unknown[] {
    if (params) {
      this.bindings.clear();
      params.forEach((value, index) => this.bindings.set(index + 1, value));
    }
    if (this.bindings.size === 0) {
      return [];
    }
    const maxIndex = Math.max(...this.bindings.keys());
    const values: unknown[] = [];
    for (let index = 1; index <= maxIndex; index++) {
      if (!this.bindings.has(index)) {
        throw new Error(`Prepared statement parameter ${index} has not been set.`);
      }
      values.push(this.bindings.get(index));
    }
    return values;
  }

  private assertOpen(): void {
    if (this.closed) {
      throw new Error('Prepared statement is closed.');
    }
  }
}
