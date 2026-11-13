import { looksLikeQuery, returnsRowsFromDml } from '../src/client/sqlStatementClassifier';

describe('SQL statement classification', () => {
  it('recognizes SQL Server DML OUTPUT even after leading comments', () => {
    const sql = '/* audit */ UPDATE dbo.items SET label = ? OUTPUT inserted.id WHERE id = ?';
    expect(returnsRowsFromDml(sql)).toBe(true);
    expect(looksLikeQuery(sql)).toBe(true);
  });

  it('does not treat OUTPUT or RETURNING inside quoted values as a result clause', () => {
    expect(returnsRowsFromDml("INSERT INTO t(label) VALUES ('OUTPUT')")).toBe(false);
    expect(returnsRowsFromDml("UPDATE t SET label = 'RETURNING'")).toBe(false);
  });

  it('recognizes ordinary query forms and FINAL TABLE DML', () => {
    expect(looksLikeQuery('-- read\nSELECT 1')).toBe(true);
    expect(returnsRowsFromDml('SELECT * FROM FINAL TABLE (INSERT INTO t(id) VALUES (1))')).toBe(true);
  });
});
