import { readFileSync } from 'fs';
import { join } from 'path';
import { parse } from 'csv-parse/sync';

export type ConnectionFixture = [string, string, string];

export function readConnectionFixtures(defaultFile: string, overridePath?: string): ConnectionFixture[] {
  const file = overridePath ?? join(__dirname, '../testdata', defaultFile);
  const rows: string[][] = parse(readFileSync(file), { skip_empty_lines: true });
  if (rows.length === 0 || rows.some((row) => row.length !== 3 || !row[0].startsWith('jdbc:'))) {
    throw new Error(`Expected JDBC URL, username, and password in each row of ${file}`);
  }
  return rows.map(([url, user, password]) => [url, user, password]);
}

export function ojpUrl(endpoint: string, jdbcUrl: string): string {
  return `jdbc:ojp[${endpoint}]_${jdbcUrl.slice('jdbc:'.length)}`;
}
