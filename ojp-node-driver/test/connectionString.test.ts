import { parseOjpUrl } from '../src/client/connectionString';
import { ojpUrl } from './connectionFixtures';

describe('parseOjpUrl', () => {
  it('should format a named Oracle URL without duplicating the JDBC prefix', () => {
    const url = ojpUrl(
      'localhost:1059(oraclePool)',
      'jdbc:oracle:thin:@localhost:1521/XEPDB1',
    );

    expect(url).toBe('jdbc:ojp[localhost:1059(oraclePool)]_oracle:thin:@localhost:1521/XEPDB1');
    expect(parseOjpUrl(url).backendUrl).toBe('oracle:thin:@localhost:1521/XEPDB1');
  });

  it('should parse a simple URL with a single endpoint', () => {
    const result = parseOjpUrl('jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb');
    expect(result.endpoints).toEqual([{ host: 'localhost', port: 1059 }]);
    expect(result.dataSourceName).toBeUndefined();
    expect(result.backendUrl).toBe('postgresql://localhost:5432/mydb');
  });

  it('should extract the datasource name when present', () => {
    const result = parseOjpUrl('jdbc:ojp[localhost:1059(mainApp)]_postgresql://localhost:5432/mydb');
    expect(result.endpoints).toEqual([{ host: 'localhost', port: 1059 }]);
    expect(result.dataSourceName).toBe('mainApp');
    expect(result.backendUrl).toBe('postgresql://localhost:5432/mydb');
  });

  it('should parse multiple endpoints (multinode)', () => {
    const result = parseOjpUrl('jdbc:ojp[host1:1059,host2:1060,host3:1061]_mysql://localhost:3306/mydb');
    expect(result.endpoints).toEqual([
      { host: 'host1', port: 1059 },
      { host: 'host2', port: 1060 },
      { host: 'host3', port: 1061 },
    ]);
    expect(result.backendUrl).toBe('mysql://localhost:3306/mydb');
  });

  it('should throw an error for a URL with an invalid format', () => {
    expect(() => parseOjpUrl('postgresql://localhost:5432/mydb')).toThrow();
  });

  it('should throw an error when an endpoint has no valid port', () => {
    expect(() => parseOjpUrl('jdbc:ojp[localhost]_postgresql://localhost:5432/mydb')).toThrow();
  });

  it.each(['0', '-1', '1.5', 'Infinity', '65536', '', '1059:extra'])(
    'rejects invalid endpoint port %s',
    (port) => {
      expect(() => parseOjpUrl(`jdbc:ojp[localhost:${port}]_h2:mem:test`)).toThrow();
    },
  );

  it.each(['1', '65535'])('accepts valid boundary port %s', (port) => {
    expect(parseOjpUrl(`jdbc:ojp[localhost:${port}]_h2:mem:test`).endpoints[0].port).toBe(Number(port));
  });
});
