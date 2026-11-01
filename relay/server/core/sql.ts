// The only storage interface the core needs. Durable Object SQLite and bun:sqlite both fit it,
// so the ledger and budget code (and its SQL) is shared verbatim.

export type SqlValue = string | number | null;

export interface Sql {
  run(sql: string, ...params: SqlValue[]): void;
  all<T = Record<string, SqlValue>>(sql: string, ...params: SqlValue[]): T[];
}
