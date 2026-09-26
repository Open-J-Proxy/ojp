import { parseOjpUrl } from '../src/client/connectionString';

describe('parseOjpUrl', () => {
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
});
