const RESULT_SET_PREFIXES = [
  'SELECT', 'WITH', 'EXEC ', 'EXEC(', 'EXECUTE ', 'EXECUTE(', 'SHOW ', 'DESCRIBE ',
  'DESC ', 'EXPLAIN ', 'VALUES ', 'VALUES(', 'PRAGMA ', 'SP_',
];

export function returnsRowsFromDml(sql: string): boolean {
  const tokens = tokenize(sql);
  if (tokens.length === 0) {
    return false;
  }
  const first = tokens[0];
  if (isDml(first) && (tokens.includes('RETURNING') || tokens.includes('OUTPUT'))) {
    return true;
  }
  return tokens.some((token, index) =>
    token === 'FINAL' && tokens[index + 1] === 'TABLE' && tokens[index + 2] === '('
      && isDml(tokens[index + 3] ?? ''),
  );
}

export function looksLikeQuery(sql: string): boolean {
  if (returnsRowsFromDml(sql)) {
    return true;
  }
  const normalized = skipLeadingComments(sql).toUpperCase();
  return RESULT_SET_PREFIXES.some((prefix) => normalized.startsWith(prefix));
}

function isDml(token: string): boolean {
  return token === 'INSERT' || token === 'UPDATE' || token === 'DELETE';
}

function tokenize(sql: string): string[] {
  const tokens: string[] = [];
  let index = 0;
  while (index < sql.length) {
    const current = sql[index];
    if (/\s/.test(current)) {
      index++;
    } else if (current === '-' && sql[index + 1] === '-') {
      const end = sql.indexOf('\n', index + 2);
      index = end < 0 ? sql.length : end + 1;
    } else if (current === '/' && sql[index + 1] === '*') {
      const end = sql.indexOf('*/', index + 2);
      index = end < 0 ? sql.length : end + 2;
    } else if (current === '\'' || current === '"' || current === '`' || current === '[') {
      const closing = current === '[' ? ']' : current;
      index++;
      while (index < sql.length) {
        if (sql[index++] === closing) {
          if (sql[index] === closing) {
            index++;
          } else {
            break;
          }
        }
      }
    } else if (/[A-Za-z0-9_]/.test(current)) {
      const start = index++;
      while (index < sql.length && /[A-Za-z0-9_$]/.test(sql[index])) {
        index++;
      }
      tokens.push(sql.slice(start, index).toUpperCase());
    } else {
      tokens.push(current);
      index++;
    }
  }
  return tokens;
}

function skipLeadingComments(sql: string): string {
  let index = 0;
  while (index < sql.length) {
    while (/\s/.test(sql[index] ?? '')) {
      index++;
    }
    if (sql.startsWith('--', index)) {
      const end = sql.indexOf('\n', index + 2);
      index = end < 0 ? sql.length : end + 1;
    } else if (sql.startsWith('/*', index)) {
      const end = sql.indexOf('*/', index + 2);
      index = end < 0 ? sql.length : end + 2;
    } else {
      break;
    }
  }
  return sql.slice(index);
}
