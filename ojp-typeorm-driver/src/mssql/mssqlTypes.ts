import { ParameterType, TemporalType } from '@ojp/node-driver';

/**
 * Stands in for the `mssql` package's type constructors (`mssql.Int`, `mssql.Decimal(p, s)`,
 * ...). `SqlServerQueryRunner.mssqlParameterToNativeParameter()` reads some of these as bare
 * properties (e.g. `this.driver.mssql.Int`) and calls others as factory functions with
 * length/precision/scale arguments (e.g. `this.driver.mssql.Decimal(...parameter.params)`).
 * Since ojp-server's JDBC write path (`ParameterHandler`) binds purely off the declared
 * `ParameterType` — it never needs an explicit SQL length/precision/scale — every marker
 * here only needs to carry the `ParameterType` (and, for TIMESTAMP variants, an optional
 * `TemporalType`); the length/precision/scale arguments TypeORM passes are accepted but
 * intentionally discarded.
 */
export interface OjpMssqlTypeDescriptor {
  readonly ojpParamType: ParameterType;
  readonly temporalType?: TemporalType;
}

function bareType(ojpParamType: ParameterType, temporalType?: TemporalType): OjpMssqlTypeDescriptor {
  return { ojpParamType, temporalType };
}

/** Builds a type constructor accepting (and discarding) length/precision/scale arguments. */
function typeFactory(ojpParamType: ParameterType, temporalType?: TemporalType) {
  return (..._params: number[]): OjpMssqlTypeDescriptor => bareType(ojpParamType, temporalType);
}

/** True when `value` is a type marker (bare, e.g. `mssqlTypes.Int`) rather than a bound query value. */
export function isTypeDescriptor(value: unknown): value is OjpMssqlTypeDescriptor {
  return typeof value === 'object' && value !== null && 'ojpParamType' in value;
}

/**
 * The subset of the real `mssql` package's exports that `SqlServerDriver`/`SqlServerQueryRunner`
 * actually reference (via `this.driver.mssql.<Name>`). Bare markers (no invocation in
 * TypeORM's source, e.g. `mssql.Int`) are plain objects; factory markers (invoked with
 * length/precision/scale, e.g. `mssql.Decimal(10, 2)`) are functions returning the same
 * shape of object.
 */
export const mssqlTypes = {
  // Bare markers (referenced as plain properties, never invoked).
  Bit: bareType('PT_BOOLEAN'),
  BigInt: bareType('PT_LONG'),
  Float: bareType('PT_DOUBLE'),
  Int: bareType('PT_INT'),
  Money: bareType('PT_BIG_DECIMAL'),
  SmallInt: bareType('PT_SHORT'),
  SmallMoney: bareType('PT_BIG_DECIMAL'),
  Real: bareType('PT_FLOAT'),
  TinyInt: bareType('PT_BYTE'),
  Xml: bareType('PT_SQL_XML'),
  Date: bareType('PT_DATE'),
  DateTime: bareType('PT_TIMESTAMP'),
  SmallDateTime: bareType('PT_TIMESTAMP'),
  UniqueIdentifier: bareType('PT_STRING'),
  Variant: bareType('PT_STRING'),
  Binary: bareType('PT_BYTES'),
  Image: bareType('PT_BYTES'),
  UDT: bareType('PT_BYTES'),
  RowVersion: bareType('PT_BYTES'),
  Text: bareType('PT_STRING'),
  Ntext: bareType('PT_N_STRING'),

  // Factory markers (invoked with length/precision/scale args, which are discarded).
  Decimal: typeFactory('PT_BIG_DECIMAL'),
  Numeric: typeFactory('PT_BIG_DECIMAL'),
  Char: typeFactory('PT_STRING'),
  NChar: typeFactory('PT_N_STRING'),
  VarChar: typeFactory('PT_STRING'),
  NVarChar: typeFactory('PT_N_STRING'),
  Time: typeFactory('PT_TIME'),
  DateTime2: typeFactory('PT_TIMESTAMP'),
  DateTimeOffset: typeFactory('PT_TIMESTAMP', 'TEMPORAL_TYPE_OFFSET_DATE_TIME'),
  VarBinary: typeFactory('PT_BYTES'),

  /**
   * Mirrors `mssql.ISOLATION_LEVEL`, read by `SqlServerQueryRunner.convertIsolationLevel()`.
   * Values themselves are never sent over the wire by this shim (`OjpClient.startTransaction()`
   * takes no isolation-level argument today); they only need to be distinct, comparable markers.
   */
  ISOLATION_LEVEL: {
    READ_UNCOMMITTED: 'READ_UNCOMMITTED',
    READ_COMMITTED: 'READ_COMMITTED',
    REPEATABLE_READ: 'REPEATABLE_READ',
    SERIALIZABLE: 'SERIALIZABLE',
    SNAPSHOT: 'SNAPSHOT',
  } as const,
};
