/**
 * Recursively-flat helper shared by the Postgres and SQL Server shims to reshape decoded
 * result rows before handing them back to TypeORM.
 *
 * `@ojp/node-driver` intentionally decodes LONG/BIGINT column values as a native JS
 * `bigint` (exact, no float rounding), since a JS `number` cannot safely hold the full
 * 64-bit range. Both real drivers TypeORM targets stringify that same value for the exact
 * same precision-safety reason — `pg` returns `int8`/`bigint` columns as `string` (its
 * default OID 20 type parser), and `mssql`/tedious always returns `BigInt` columns as
 * `string` (see tedious's own datatype docs: "values can exceed 53 bits of significant
 * data"). TypeORM's hydration logic for both dialects is written assuming that string
 * shape (no special-cased `bigint`-to-anything conversion exists in either driver). Left
 * unconverted, a native `bigint` would also break plain `JSON.stringify()` calls anywhere
 * downstream in a consuming application.
 */
export function stringifyBigInts<T extends Record<string, unknown>>(rows: T[]): T[] {
  return rows.map((row) => {
    const hasBigInt = Object.values(row).some((value) => typeof value === 'bigint');
    if (!hasBigInt) {
      return row;
    }
    const converted: Record<string, unknown> = { ...row };
    for (const [key, value] of Object.entries(row)) {
      if (typeof value === 'bigint') {
        converted[key] = value.toString();
      }
    }
    return converted as T;
  });
}
