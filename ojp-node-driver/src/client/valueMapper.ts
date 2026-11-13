import { ParameterProto, ParameterType, ParameterValue, TemporalType } from '../proto/types';

/**
 * Conversion of JS values to ParameterValue/ParameterProto (parameter binding in
 * executeQuery/executeUpdate) and from ParameterValue to JS values (reading result
 * set rows). Covers the most common MVP types; advanced types (streams, SQL arrays,
 * RowId) will be added in future phases.
 */

const MAX_SAFE_INT32 = 2147483647;
const MIN_SAFE_INT32 = -2147483648;

/**
 * Reference to a LOB (BLOB/CLOB) already sent to the ojp-server via `OjpClient.createLob()`.
 * Used as a parameter value in `executeUpdate`/`executeQuery` to bind the LOB
 * to a column (e.g. `INSERT INTO t (content) VALUES (?)`, with `[lobRef]`).
 * Should not be instantiated directly by the application — it is returned by `createLob()`.
 */
export class OjpLobRef {
  constructor(public readonly uuid: string, public readonly lobKind: 'BLOB' | 'CLOB') {}
}

/**
 * java.sql.Types.NULL code (0). For null parameters, the ojp-server expects a
 * ParameterValue with intValue containing the SQL type code (see PreparedStatement.setNull
 * in the official JDBC driver, which always sends an int, never the isNull marker). We
 * use the generic NULL code by default; per-column typing may be added later.
 */
const JAVA_SQL_TYPES_NULL = 0;

/**
 * Explicit type hint for a bind parameter, bypassing `toParameterProto`'s JS-value-based
 * type inference. Needed whenever the JS value's own type does not unambiguously map to
 * the SQL type the target column/parameter actually has — most commonly:
 *  - DECIMAL/NUMERIC/MONEY columns: a plain JS `number` bound as PT_DOUBLE loses precision
 *    (IEEE 754 double, not the exact fixed-point value the database stores). Wrapping the
 *    value as `new OjpTypedParam(value, 'PT_BIG_DECIMAL')` sends it using the same exact
 *    wire format (`BigDecimalWire`) the reference JDBC driver uses for `setBigDecimal`.
 *    Passing a decimal STRING (e.g. "19.99") instead of a computed `number` avoids float
 *    rounding entirely and is the recommended way to get full precision.
 *  - BIGINT columns: a JS `number` outside the 32-bit range is inferred as PT_DOUBLE by
 *    `toParameterProto`, when it should bind as PT_LONG.
 *  - DATE-only / TIME-only columns: a JS `Date` is inferred as PT_TIMESTAMP by default;
 *    binding a date-only or time-only column may require PT_DATE/PT_TIME instead.
 *
 * `temporalType` is only meaningful for `type: 'PT_TIMESTAMP'` and lets callers select a
 * variant such as `'TEMPORAL_TYPE_OFFSET_DATE_TIME'` (e.g. SQL Server's DATETIMEOFFSET);
 * it defaults to `'TEMPORAL_TYPE_TIMESTAMP'` when omitted.
 *
 * Used by dialect shims (e.g. `@ojp/typeorm-driver`'s SQL Server adapter) that receive
 * their own explicit type information from the ORM (e.g. `mssql.Decimal(p, s)`,
 * `mssql.BigInt`, `mssql.DateTimeOffset`) and want to forward it faithfully instead of
 * relying on inference from the raw JS value.
 */
export class OjpTypedParam {
  constructor(
    public readonly value: unknown,
    public readonly type: ParameterType,
    public readonly temporalType?: TemporalType,
  ) {}
}

export function toParameterProto(index: number, value: unknown): ParameterProto {
  if (value === null || value === undefined) {
    return { index, type: 'PT_NULL', values: [{ intValue: JAVA_SQL_TYPES_NULL }] };
  }

  if (value instanceof OjpTypedParam) {
    return toTypedParameterProto(index, value.value, value.type, value.temporalType);
  }

  if (value instanceof OjpLobRef) {
    // The server resolves the LOB already registered (via createLob) by UUID, loaded here
    // as a plain stringValue — only the `type` (PT_BLOB/PT_CLOB) signals to the server
    // to treat it as a LOB reference instead of a string literal.
    return {
      index,
      type: value.lobKind === 'BLOB' ? 'PT_BLOB' : 'PT_CLOB',
      values: [{ stringValue: value.uuid }],
    };
  }

  if (typeof value === 'boolean') {
    return { index, type: 'PT_BOOLEAN', values: [{ boolValue: value }] };
  }

  if (typeof value === 'bigint') {
    return { index, type: 'PT_LONG', values: [{ longValue: value.toString() }] };
  }

  if (typeof value === 'number') {
    if (Number.isInteger(value) && value >= MIN_SAFE_INT32 && value <= MAX_SAFE_INT32) {
      return { index, type: 'PT_INT', values: [{ intValue: value }] };
    }
    return { index, type: 'PT_DOUBLE', values: [{ doubleValue: value }] };
  }

  if (typeof value === 'string') {
    return { index, type: 'PT_STRING', values: [{ stringValue: value }] };
  }

  if (Buffer.isBuffer(value)) {
    return { index, type: 'PT_BYTES', values: [{ bytesValue: value }] };
  }

  if (value instanceof Date) {
    return {
      index,
      type: 'PT_TIMESTAMP',
      values: [
        {
          timestampValue: {
            instant: {
              seconds: Math.floor(value.getTime() / 1000).toString(),
              nanos: (value.getTime() % 1000) * 1_000_000,
            },
            timezone: 'UTC',
            // TEMPORAL_TYPE_TIMESTAMP makes the server reconstruct a java.sql.Timestamp,
            // which is the type expected by the PreparedStatement.setTimestamp bind. Using
            // TEMPORAL_TYPE_INSTANT breaks it because the server does a direct cast to Timestamp.
            originalType: 'TEMPORAL_TYPE_TIMESTAMP',
          },
        },
      ],
    };
  }

  throw new TypeError(`Unsupported parameter type: ${typeof value} (index ${index})`);
}

/**
 * Builds a ParameterProto honoring an explicit `OjpTypedParam` type hint instead of
 * inferring the wire type from the raw JS value. Types not handled explicitly here fall
 * back to `toParameterProto`'s inference (covers less common hints such as PT_URL/PT_OBJECT).
 */
function toTypedParameterProto(
  index: number,
  rawValue: unknown,
  type: ParameterType,
  temporalType?: TemporalType,
): ParameterProto {
  if (rawValue === null || rawValue === undefined) {
    return { index, type: 'PT_NULL', values: [{ intValue: JAVA_SQL_TYPES_NULL }] };
  }

  switch (type) {
    case 'PT_BOOLEAN':
      return { index, type, values: [{ boolValue: Boolean(rawValue) }] };
    case 'PT_BYTE':
    case 'PT_SHORT':
    case 'PT_INT':
      return { index, type, values: [{ intValue: Number(rawValue) }] };
    case 'PT_LONG':
      return { index, type, values: [{ longValue: typeof rawValue === 'bigint' ? rawValue.toString() : String(rawValue) }] };
    case 'PT_FLOAT':
      return { index, type, values: [{ floatValue: Number(rawValue) }] };
    case 'PT_DOUBLE':
      return { index, type, values: [{ doubleValue: Number(rawValue) }] };
    case 'PT_BIG_DECIMAL':
      return { index, type, values: [{ bytesValue: encodeBigDecimalWire(rawValue) }] };
    case 'PT_STRING':
    case 'PT_N_STRING':
      return { index, type, values: [{ stringValue: String(rawValue) }] };
    case 'PT_BYTES': {
      const bytes = Buffer.isBuffer(rawValue) ? rawValue : Buffer.from(String(rawValue));
      return { index, type, values: [{ bytesValue: bytes }] };
    }
    case 'PT_DATE': {
      const asDate = rawValue instanceof Date ? rawValue : new Date(rawValue as string | number);
      return {
        index,
        type,
        values: [{ dateValue: { year: asDate.getUTCFullYear(), month: asDate.getUTCMonth() + 1, day: asDate.getUTCDate() } }],
      };
    }
    case 'PT_TIME': {
      const asDate = rawValue instanceof Date ? rawValue : new Date(`1970-01-01T${rawValue}Z`);
      return {
        index,
        type,
        values: [
          {
            timeValue: {
              hours: asDate.getUTCHours(),
              minutes: asDate.getUTCMinutes(),
              seconds: asDate.getUTCSeconds(),
              nanos: asDate.getUTCMilliseconds() * 1_000_000,
            },
          },
        ],
      };
    }
    case 'PT_TIMESTAMP': {
      const asDate = rawValue instanceof Date ? rawValue : new Date(rawValue as string | number);
      return {
        index,
        type,
        values: [
          {
            timestampValue: {
              instant: {
                seconds: Math.floor(asDate.getTime() / 1000).toString(),
                nanos: (asDate.getTime() % 1000) * 1_000_000,
              },
              timezone: 'UTC',
              originalType: temporalType ?? 'TEMPORAL_TYPE_TIMESTAMP',
            },
          },
        ],
      };
    }
    default:
      // Less common hints (PT_URL, PT_OBJECT, PT_ROW_ID, ...) fall back to plain
      // value-based inference; the hint is effectively a no-op for those.
      return toParameterProto(index, rawValue);
  }
}

export function toParameterProtos(values: unknown[]): ParameterProto[] {
  return values.map((value, i) => toParameterProto(i + 1, value));
}

/** Maximum accepted length for the unscaled value string, mirroring the server's BigDecimalWire limit. */
const BIG_DECIMAL_MAX_UNSCALED_LENGTH = 10_000_000;

/**
 * Attempts to decode a Buffer in the `BigDecimalWire` binary format used by the ojp-server
 * for DECIMAL/NUMERIC/MONEY columns (java.math.BigDecimal), since the result set streaming
 * protocol (ResultRow) does not carry each column's SQL type — only the raw value.
 * Format (big-endian): presence byte (0/1), int32 with the unscaled value string length,
 * UTF-8 bytes of that string, int32 with the scale.
 *
 * This is a "sniffing" heuristic (the same strategy used by the reference JDBC driver in
 * `ProtoConverter.fromParameterValue` when the column type is unknown): it tries to
 * decode and, if the layout doesn't match exactly, returns `null` for the caller to
 * treat the value as plain binary bytes.
 */
function tryDecodeBigDecimalWire(buf: Buffer): string | null {
  if (buf.length < 1) {
    return null;
  }
  const presence = buf.readUInt8(0);
  if (presence === 0) {
    return buf.length === 1 ? null : null; // A null BigDecimal shouldn't reach here (isNull covers nulls)
  }
  if (presence !== 1 || buf.length < 5) {
    return null;
  }
  const unscaledLength = buf.readInt32BE(1);
  if (unscaledLength < 0 || unscaledLength > BIG_DECIMAL_MAX_UNSCALED_LENGTH) {
    return null;
  }
  const expectedLength = 1 + 4 + unscaledLength + 4;
  if (buf.length !== expectedLength) {
    return null;
  }
  const unscaledStr = buf.toString('utf8', 5, 5 + unscaledLength);
  if (!/^-?\d+$/.test(unscaledStr)) {
    return null;
  }
  const scale = buf.readInt32BE(5 + unscaledLength);
  return formatUnscaledDecimal(unscaledStr, scale);
}

/** Reconstructs the decimal (string) representation from unscaledValue/scale, same as `new BigDecimal(unscaled, scale).toPlainString()`. */
function formatUnscaledDecimal(unscaledStr: string, scale: number): string {
  const negative = unscaledStr.startsWith('-');
  const digits = negative ? unscaledStr.slice(1) : unscaledStr;
  const sign = negative ? '-' : '';

  if (scale <= 0) {
    return `${sign}${digits}${'0'.repeat(-scale)}`;
  }

  const padded = digits.padStart(scale + 1, '0');
  const integerPart = padded.slice(0, padded.length - scale);
  const fractionPart = padded.slice(padded.length - scale);
  return `${sign}${integerPart}.${fractionPart}`;
}

/**
 * Encodes a decimal value into the `BigDecimalWire` binary format, matching
 * `BigDecimalWire.writeBigDecimal` on the server (org.openjproxy.grpc.BigDecimalWire) exactly,
 * so it can be bound as a PT_BIG_DECIMAL parameter (see `OjpTypedParam`).
 *
 * Accepts a decimal string (e.g. "19.99", "-0.005", "100") for full precision — the safest
 * input, since it bypasses IEEE 754 float rounding entirely — or a plain JS `number`/`bigint`,
 * converted via `.toString()` first (only as precise as the JS number already was; float
 * rounding that happened before reaching this function, e.g. from `0.1 + 0.2`, cannot be
 * recovered here). Passing a decimal string is strongly recommended for DECIMAL/NUMERIC/
 * MONEY columns where exact precision matters.
 *
 * Only plain decimal notation is supported (no exponential/scientific notation).
 */
export function encodeBigDecimalWire(value: unknown): Buffer {
  const text = (typeof value === 'number' || typeof value === 'bigint' ? value.toString() : String(value)).trim();
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(text);
  if (!match) {
    throw new TypeError(`Invalid decimal value for PT_BIG_DECIMAL: "${text}" (exponential notation is not supported)`);
  }
  const [, sign, integerDigits, fractionDigits = ''] = match;
  const scale = fractionDigits.length;
  const unscaledDigits = (integerDigits + fractionDigits).replace(/^0+(?=\d)/, '');
  const unscaledStr = (sign === '-' && unscaledDigits !== '0' ? '-' : '') + unscaledDigits;

  const unscaledBytes = Buffer.from(unscaledStr, 'utf8');
  const buf = Buffer.alloc(1 + 4 + unscaledBytes.length + 4);
  buf.writeUInt8(1, 0);
  buf.writeInt32BE(unscaledBytes.length, 1);
  unscaledBytes.copy(buf, 5);
  buf.writeInt32BE(scale, 5 + unscaledBytes.length);
  return buf;
}

export function fromParameterValue(value: ParameterValue | undefined): unknown {
  if (!value || value.isNull) {
    return null;
  }
  if (value.boolValue !== undefined) {
    return value.boolValue;
  }
  if (value.intValue !== undefined) {
    return value.intValue;
  }
  if (value.longValue !== undefined) {
    return BigInt(value.longValue);
  }
  if (value.floatValue !== undefined) {
    return value.floatValue;
  }
  if (value.doubleValue !== undefined) {
    return value.doubleValue;
  }
  if (value.stringValue !== undefined) {
    return value.stringValue;
  }
  if (value.bytesValue !== undefined) {
    // DECIMAL/NUMERIC/MONEY columns (java.math.BigDecimal) arrive as bytes_value
    // encoded in BigDecimalWire, since the streaming protocol doesn't carry the column's
    // SQL type. Returned as a string (same convention as the `pg` driver for NUMERIC),
    // avoiding the precision loss that would occur when converting to `number`.
    const decoded = tryDecodeBigDecimalWire(value.bytesValue);
    return decoded ?? value.bytesValue;
  }
  if (value.uuidValue !== undefined) {
    return value.uuidValue;
  }
  if (value.bigintegerValue !== undefined) {
    return BigInt(value.bigintegerValue);
  }
  if (value.urlValue !== undefined) {
    return value.urlValue;
  }
  if (value.dateValue) {
    const { year, month, day } = value.dateValue;
    return new Date(Date.UTC(year, month - 1, day));
  }
  if (value.timeValue) {
    const { hours, minutes, seconds } = value.timeValue;
    return { hours, minutes, seconds };
  }
  if (value.timestampValue?.instant) {
    const { seconds, nanos } = value.timestampValue.instant;
    return new Date(Number(seconds) * 1000 + Math.floor((nanos ?? 0) / 1_000_000));
  }
  if (value.intArrayValue) {
    return value.intArrayValue.values;
  }
  if (value.longArrayValue) {
    return value.longArrayValue.values.map((v) => BigInt(v));
  }
  if (value.stringArrayValue) {
    return value.stringArrayValue.values;
  }
  return null;
}

export function inferParameterType(value: unknown): ParameterType {
  return toParameterProto(0, value).type;
}
