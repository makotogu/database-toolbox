// Visual lexer only: execution boundaries and confirmation remain server-owned.
const keywords = new Set(("SELECT FROM WHERE JOIN LEFT RIGHT FULL INNER OUTER CROSS ON AS WITH RECURSIVE UNION ALL DISTINCT INSERT INTO VALUES UPDATE SET DELETE MERGE USING WHEN MATCHED THEN ELSE END CREATE ALTER DROP TRUNCATE TABLE VIEW INDEX DATABASE SCHEMA PROCEDURE FUNCTION TRIGGER REPLACE RETURNS RETURN BEGIN DECLARE IF LOOP WHILE FOR EACH EXEC EXECUTE CALL DO DELIMITER EXPLAIN ANALYZE COMMIT ROLLBACK SAVEPOINT RELEASE START TRANSACTION GRANT REVOKE ORDER BY GROUP HAVING LIMIT OFFSET FETCH FIRST NEXT ROW ROWS ONLY ASC DESC NULL NULLS TRUE FALSE AND OR NOT IS IN EXISTS BETWEEN LIKE ILIKE CASE CAST OVER PARTITION WINDOW DEFAULT PRIMARY KEY FOREIGN REFERENCES UNIQUE CHECK CONSTRAINT ADD COLUMN INT INTEGER BIGINT SMALLINT DECIMAL NUMERIC FLOAT DOUBLE REAL VARCHAR CHAR TEXT BOOLEAN DATE TIME TIMESTAMP INTERVAL LANGUAGE OUT INOUT INHERITS MATERIALIZED SHOW DESCRIBE USE LOCK SHARE NOWAIT SKIP LOCKED").split(" "));
const escape = s => s.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const HIGHLIGHT_LIMIT = 200000;
export function highlightSql(sql) {
  if (sql.length > HIGHLIGHT_LIMIT) return null;
  const parts = [];
  let i = 0;
  const token = (end, kind) => {
    const text = escape(sql.slice(i, end));
    parts.push(kind ? `<span class="sql-${kind}">${text}</span>` : text);
    i = end;
  };
  while (i < sql.length) {
    const rest = sql.slice(i), c = sql[i];
    if (rest.startsWith('--') || c === '#') {
      const end = sql.indexOf('\n', i); token(end < 0 ? sql.length : end, 'comment'); continue;
    }
    if (rest.startsWith('/*')) {
      let end = i + 2, depth = 1;
      while (end < sql.length && depth) {
        if (sql.startsWith('/*', end)) { depth++; end += 2; }
        else if (sql.startsWith('*/', end)) { depth--; end += 2; }
        else end++;
      }
      token(end, 'comment'); continue;
    }
    const dollar = /^\$(?:[a-zA-Z_][a-zA-Z_0-9]*)?\$/.exec(rest);
    if (dollar) {
      const end = sql.indexOf(dollar[0], i + dollar[0].length);
      token(end < 0 ? sql.length : end + dollar[0].length, 'string'); continue;
    }
    if (/^[qQ]'[^\s]/.test(rest)) {
      const opening = sql[i + 2], closing = ({'[':']','(':')','{':'}','<':'>'})[opening] || opening;
      const end = sql.indexOf(closing + "'", i + 3);
      token(end < 0 ? sql.length : end + 2, 'string'); continue;
    }
    if (c === "'" || c === '"' || c === '`') {
      let end = i + 1;
      while (end < sql.length) {
        if (sql[end] === '\\') { end = Math.min(sql.length, end + 2); continue; }
        if (sql[end++] === c) { if (sql[end] === c) end++; else break; }
      }
      token(end, c === "'" ? 'string' : 'identifier'); continue;
    }
    const number = /^(?:0[xX][\da-fA-F]+|(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)/.exec(rest);
    if (number) { token(i + number[0].length, 'number'); continue; }
    const word = /^[a-zA-Z_\u0080-\uffff][a-zA-Z_0-9$\u0080-\uffff]*/.exec(rest);
    if (word) { token(i + word[0].length, keywords.has(word[0].toUpperCase()) ? 'keyword' : null); continue; }
    const space = /^\s+/.exec(rest);
    token(i + (space ? space[0].length : 1), null);
  }
  return parts.join('');
}
