import { detectCommand, producesResultSet, translateIndexedParams } from '../src/mssql/sqlTranslate';

describe('translateIndexedParams', () => {
  it('should return the sql unchanged when there are no placeholders', () => {
    const result = translateIndexedParams('SELECT 1', []);
    expect(result).toEqual({ sql: 'SELECT 1', params: [] });
  });

  it('should replace each distinct @N placeholder with ? in order', () => {
    const result = translateIndexedParams('INSERT INTO t (a, b) VALUES (@0, @1)', ['alpha', 'beta']);
    expect(result.sql).toBe('INSERT INTO t (a, b) VALUES (?, ?)');
    expect(result.params).toEqual(['alpha', 'beta']);
  });

  it('should duplicate the referenced value when a placeholder is reused', () => {
    const sql = 'UPDATE t SET a = @0, b = @1 WHERE a = @0';
    const result = translateIndexedParams(sql, ['x', 'y']);
    expect(result.sql).toBe('UPDATE t SET a = ?, b = ? WHERE a = ?');
    expect(result.params).toEqual(['x', 'y', 'x']);
  });

  it('should honor placeholders that are out of numeric order in the sql text', () => {
    const result = translateIndexedParams('SELECT * FROM t WHERE b = @1 AND a = @0', ['first', 'second']);
    expect(result.sql).toBe('SELECT * FROM t WHERE b = ? AND a = ?');
    expect(result.params).toEqual(['second', 'first']);
  });
});

describe('producesResultSet', () => {
  it.each([
    ['SELECT * FROM t', true],
    ['  select * from t', true],
    ['WITH cte AS (SELECT 1) SELECT * FROM cte', true],
    ['UPDATE t SET a = 1', false],
    ['DELETE FROM t', false],
    ['INSERT INTO t (a) VALUES (1)', false],
  ])('%s -> %s', (sql, expected) => {
    expect(producesResultSet(sql)).toBe(expected);
  });

  it('should treat a DELETE ... OUTPUT clause (no INTO) as a result-set query', () => {
    expect(producesResultSet('DELETE FROM t OUTPUT DELETED.id WHERE id = 1')).toBe(true);
  });

  it('should NOT treat an OUTPUT ... INTO clause alone as a result-set query', () => {
    expect(producesResultSet('INSERT INTO t (a) OUTPUT INSERTED.id INTO @OutputTable VALUES (1)')).toBe(false);
  });

  it('should treat the DECLARE/INSERT-OUTPUT-INTO/SELECT batch as a result-set query, based on its last statement', () => {
    const batch = 'DECLARE @OutputTable TABLE (id int); '
      + 'INSERT INTO t (a) OUTPUT INSERTED.id INTO @OutputTable VALUES (1); '
      + 'SELECT * FROM @OutputTable';
    expect(producesResultSet(batch)).toBe(true);
  });

  it('should not be confused by semicolons inside string literals when splitting the batch', () => {
    const batch = "INSERT INTO t (a) VALUES ('a;b')";
    expect(producesResultSet(batch)).toBe(false);
  });
});

describe('detectCommand', () => {
  it('should extract the leading sql verb in uppercase', () => {
    expect(detectCommand('select * from t')).toBe('SELECT');
    expect(detectCommand('  Insert into t (a) values (1)')).toBe('INSERT');
    expect(detectCommand('DECLARE @x int')).toBe('DECLARE');
    expect(detectCommand('')).toBe('');
  });
});
