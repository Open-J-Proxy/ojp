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
  Bit: bareType(ParameterType.BOOLEAN),
  BigInt: bareType(ParameterType.LONG),
  Float: bareType(ParameterType.DOUBLE),
  Int: bareType(ParameterType.INT),
  Money: bareType(ParameterType.BIG_DECIMAL),
  SmallInt: bareType(ParameterType.SHORT),
  SmallMoney: bareType(ParameterType.BIG_DECIMAL),
  Real: bareType(ParameterType.FLOAT),
  TinyInt: bareType(ParameterType.BYTE),
  Xml: bareType(ParameterType.SQL_XML),
  Date: bareType(ParameterType.DATE),
  DateTime: bareType(ParameterType.TIMESTAMP),
  SmallDateTime: bareType(ParameterType.TIMESTAMP),
  UniqueIdentifier: bareType(ParameterType.STRING),
  Variant: bareType(ParameterType.STRING),
  Binary: bareType(ParameterType.BYTES),
  Image: bareType(ParameterType.BYTES),
  UDT: bareType(ParameterType.BYTES),
  RowVersion: bareType(ParameterType.BYTES),
  Text: bareType(ParameterType.STRING),
  Ntext: bareType(ParameterType.N_STRING),

  // Factory markers (invoked with length/precision/scale args, which are discarded).
  Decimal: typeFactory(ParameterType.BIG_DECIMAL),
  Numeric: typeFactory(ParameterType.BIG_DECIMAL),
  Char: typeFactory(ParameterType.STRING),
  NChar: typeFactory(ParameterType.N_STRING),
  VarChar: typeFactory(ParameterType.STRING),
  NVarChar: typeFactory(ParameterType.N_STRING),
  Time: typeFactory(ParameterType.TIME),
  DateTime2: typeFactory(ParameterType.TIMESTAMP),
  DateTimeOffset: typeFactory(ParameterType.TIMESTAMP, 'TEMPORAL_TYPE_OFFSET_DATE_TIME'),
  VarBinary: typeFactory(ParameterType.BYTES),

  /**
   * Mirrors `mssql.ISOLATION_LEVEL`, read by `SqlServerQueryRunner.convertIsolationLevel()`.
   * SNAPSHOT is rejected because the Node client's JDBC isolation API does not support it.
   */
  ISOLATION_LEVEL: {
    READ_UNCOMMITTED: 'READ_UNCOMMITTED',
    READ_COMMITTED: 'READ_COMMITTED',
    REPEATABLE_READ: 'REPEATABLE_READ',
    SERIALIZABLE: 'SERIALIZABLE',
    SNAPSHOT: 'SNAPSHOT',
  } as const,
};
