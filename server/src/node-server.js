/**
 * Self-hosting entry point: Node.js ≥ 22.13 with the built-in SQLite module.
 *   PORT=8080 DB_FILE=./foggymap.db node src/node-server.js
 * Put it behind an HTTPS reverse proxy (see Caddyfile / docker-compose.yml).
 */
import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { SCHEMA, handle, RateLimiter } from './handler.js';

export function sqliteAdapter(file) {
  const db = new DatabaseSync(file);
  db.exec('PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA busy_timeout = 5000;');
  for (const s of SCHEMA) db.exec(s);
  const cache = new Map();
  const stmt = sql => { let s = cache.get(sql); if (!s) { s = db.prepare(sql); cache.set(sql, s); } return s; };
  return {
    raw: db,
    async run(sql, ...p) { return stmt(sql).run(...p); },
    async all(sql, ...p) { return stmt(sql).all(...p); },
    async first(sql, ...p) { return stmt(sql).get(...p) ?? null; },
    async batch(list) {
      db.exec('BEGIN IMMEDIATE');
      try { for (const [sql, ...p] of list) stmt(sql).run(...p); db.exec('COMMIT'); }
      catch (e) { db.exec('ROLLBACK'); throw e; }
    },
  };
}

export function createServer({ file = process.env.DB_FILE || './foggymap.db', trustProxy = process.env.TRUST_PROXY === '1' } = {}) {
  const db = sqliteAdapter(file);
  const limiter = new RateLimiter();
  const server = http.createServer(async (req, res) => {
    const send = (status, body) => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      res.end(JSON.stringify(body));
    };
    try {
      let body = '';
      if (req.method === 'POST' || req.method === 'PUT') {
        for await (const chunk of req) { body += chunk; if (body.length > 1_600_000) return send(413, { error: 'Zu groß' }); }
      }
      const url = new URL(req.url, 'http://localhost');
      const fwd = trustProxy ? (req.headers['x-forwarded-for'] || '').split(',')[0].trim() : '';
      const out = await handle(db, { method: req.method, path: url.pathname, query: url.searchParams, body, ip: fwd || req.socket.remoteAddress }, limiter, n => randomBytes(n));
      send(out.status, out.body);
    } catch (e) {
      console.error(e);
      send(500, { error: 'Serverfehler' });
    }
  });
  return { server, db };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT || 8080);
  createServer().server.listen(port, '0.0.0.0', () => console.log(`FoggyMap sync on :${port} (HTTPS via reverse proxy)`));
}
