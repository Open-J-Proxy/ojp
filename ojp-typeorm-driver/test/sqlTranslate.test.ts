import {
  detectCommand,
  matchTransactionSentinel,
  producesResultSet,
  translatePositionalParams,
} from '../src/postgres/sqlTranslate';

describe('translatePositionalParams', () => {
  it('should return the sql unchanged when there are no placeholders', () => {
    const result = translatePositionalParams('SELECT 1', []);
    expect(result).toEqual({ sql: 'SELECT 1', params: [] });
  });

  it('should replace each distinct $N placeholder with ? in order', () => {
    const result = translatePositionalParams('INSERT INTO t (a, b) VALUES ($1, $2)', ['alpha', 'beta']);
    expect(result.sql).toBe('INSERT INTO t (a, b) VALUES (?, ?)');
    expect(result.params).toEqual(['alpha', 'beta']);
  });

  it('should duplicate the referenced value when a placeholder is reused (e.g. upsert)', () => {
    const sql = 'INSERT INTO t (id, updated_at) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET updated_at = $2';
    const result = translatePositionalParams(sql, ['id-1', 'now']);
    expect(result.sql).toBe(
      'INSERT INTO t (id, updated_at) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET updated_at = ?',
    );
    expect(result.params).toEqual(['id-1', 'now', 'now']);
  });

  it('should honor placeholders that are out of numeric order in the sql text', () => {
    const result = translatePositionalParams('SELECT * FROM t WHERE b = $2 AND a = $1', ['first', 'second']);
    expect(result.sql).toBe('SELECT * FROM t WHERE b = ? AND a = ?');
    expect(result.params).toEqual(['second', 'first']);
  });
});

describe('producesResultSet', () => {
  it.each([
    ['SELECT * FROM t', true],
    ['  select * from t', true],
    ['WITH cte AS (SELECT 1) SELECT * FROM cte', true],
    ['SHOW search_path', true],
    ['EXPLAIN SELECT 1', true],
    ['VALUES (1), (2)', true],
    ['TABLE t', true],
    ['UPDATE t SET a = 1', false],
    ['DELETE FROM t', false],
    ['INSERT INTO t (a) VALUES (1)', false],
  ])('%s -> %s', (sql, expected) => {
    expect(producesResultSet(sql)).toBe(expected);
  });

  it('should treat INSERT/UPDATE/DELETE with a RETURNING clause as a result-set query', () => {
    expect(producesResultSet('INSERT INTO t (a) VALUES (1) RETURNING id')).toBe(true);
    expect(producesResultSet('UPDATE t SET a = 1 RETURNING id')).toBe(true);
    expect(producesResultSet('DELETE FROM t RETURNING id')).toBe(true);
  });
});

describe('detectCommand', () => {
  it('should extract the leading sql verb in uppercase', () => {
    expect(detectCommand('select * from t')).toBe('SELECT');
    expect(detectCommand('  Insert into t (a) values (1)')).toBe('INSERT');
    expect(detectCommand('')).toBe('');
  });
});

describe('matchTransactionSentinel', () => {
  it('should recognize START TRANSACTION and BEGIN as "begin"', () => {
    expect(matchTransactionSentinel('START TRANSACTION')).toEqual({ kind: 'begin' });
    expect(matchTransactionSentinel('BEGIN')).toEqual({ kind: 'begin' });
    expect(matchTransactionSentinel('  begin  ')).toEqual({ kind: 'begin' });
  });

  it('should recognize COMMIT and ROLLBACK', () => {
    expect(matchTransactionSentinel('COMMIT')).toEqual({ kind: 'commit' });
    expect(matchTransactionSentinel('ROLLBACK')).toEqual({ kind: 'rollback' });
  });

  it('should treat savepoint-related statements as passthrough, not sentinels', () => {
    expect(matchTransactionSentinel('SAVEPOINT typeorm_1')).toEqual({ kind: 'passthrough' });
    expect(matchTransactionSentinel('RELEASE SAVEPOINT typeorm_1')).toEqual({ kind: 'passthrough' });
    expect(matchTransactionSentinel('ROLLBACK TO SAVEPOINT typeorm_1')).toEqual({ kind: 'passthrough' });
  });

  it('should treat ordinary SQL as passthrough', () => {
    expect(matchTransactionSentinel('SELECT 1')).toEqual({ kind: 'passthrough' });
  });
});
