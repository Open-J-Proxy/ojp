export function translateParameters(
  sql: string,
  params: ReadonlyArray<unknown>,
  dialect: 'postgres' | 'mssql',
): { sql: string; params: unknown[] } {
  const ordered: unknown[] = [];
  let result = '';
  let index = 0;
  while (index < sql.length) {
    const rest = sql.slice(index);
    if (rest.startsWith('--')) {
      const end = sql.indexOf('\n', index + 2);
      const next = end < 0 ? sql.length : end;
      result += sql.slice(index, next);
      index = next;
      continue;
    }
    if (rest.startsWith('/*')) {
      let depth = 1;
      let end = index + 2;
      while (end < sql.length && depth > 0) {
        if (sql.slice(end, end + 2) === '/*') {
          depth++;
          end += 2;
        } else if (sql.slice(end, end + 2) === '*/') {
          depth--;
          end += 2;
        } else {
          end++;
        }
      }
      result += sql.slice(index, end);
      index = end;
      continue;
    }
    const delimiter = sql[index];
    if (delimiter === '\'' || delimiter === '"' || (dialect === 'mssql' && delimiter === '[')) {
      const endDelimiter = delimiter === '[' ? ']' : delimiter;
      const escapeBackslash = dialect === 'postgres' && delimiter === '\''
        && index > 0 && /[eE]/.test(sql[index - 1])
        && (index < 2 || !/[A-Za-z0-9_$]/.test(sql[index - 2]));
      let end = index + 1;
      while (end < sql.length) {
        if (escapeBackslash && sql[end] === '\\') {
          end += 2;
        } else if (sql[end] === endDelimiter) {
          if (sql[end + 1] === endDelimiter) {
            end += 2;
          } else {
            end++;
            break;
          }
        } else {
          end++;
        }
      }
      result += sql.slice(index, end);
      index = end;
      continue;
    }
    const precedingIdentifier = index > 0 && /[A-Za-z0-9_$@]/.test(sql[index - 1]);
    if (dialect === 'postgres' && delimiter === '$' && !precedingIdentifier) {
      const tag = /^\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$/.exec(rest)?.[0];
      if (tag) {
        const end = sql.indexOf(tag, index + tag.length);
        const next = end < 0 ? sql.length : end + tag.length;
        result += sql.slice(index, next);
        index = next;
        continue;
      }
    }
    if (!precedingIdentifier && delimiter === (dialect === 'postgres' ? '$' : '@')) {
      const match = dialect === 'postgres' ? /^\$(\d+)/.exec(rest) : /^@(\d+)\b/.exec(rest);
      if (match) {
        const paramIndex = Number(match[1]) - (dialect === 'postgres' ? 1 : 0);
        if (!Number.isSafeInteger(paramIndex) || paramIndex < 0 || paramIndex >= params.length) {
          throw new RangeError(`SQL parameter ${match[0]} has no matching value.`);
        }
        ordered.push(params[paramIndex]);
        result += '?';
        index += match[0].length;
        continue;
      }
    }
    result += delimiter;
    index++;
  }
  return { sql: result, params: ordered };
}
