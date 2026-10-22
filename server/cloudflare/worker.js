/**
 * Cloudflare Workers entry point (free plan): HTTPS at https://<name>.<account>.workers.dev,
 * data in a D1 (SQLite) database bound as `DB`.
 */
import { SCHEMA, handle, RateLimiter } from '../src/handler.js';

const limiter = new RateLimiter();
let schemaReady = null;

function d1Adapter(d1) {
  const prep = (sql, p) => d1.prepare(sql).bind(...p);
  return {
    async run(sql, ...p) { return prep(sql, p).run(); },
    async all(sql, ...p) { return (await prep(sql, p).all()).results; },
    async first(sql, ...p) { return (await prep(sql, p).first()) ?? null; },
    async batch(list) { return d1.batch(list.map(([sql, ...p]) => prep(sql, p))); },
  };
}

export default {
  async fetch(request, env) {
    const json = (status, body) => new Response(JSON.stringify(body), {
      status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
    });
    if (!env.DB) return json(500, { error: 'D1-Datenbank ist nicht als "DB" verbunden (Worker → Einstellungen → Bindungen).' });
    try {
      schemaReady ||= env.DB.batch(SCHEMA.map(s => env.DB.prepare(s)));
      await schemaReady;
    } catch (e) { schemaReady = null; return json(500, { error: 'Datenbank-Initialisierung fehlgeschlagen: ' + e.message }); }
    const url = new URL(request.url);
    const body = request.method === 'POST' || request.method === 'PUT' ? await request.text() : null;
    try {
      const out = await handle(d1Adapter(env.DB), {
        method: request.method, path: url.pathname, query: url.searchParams, body,
        ip: request.headers.get('CF-Connecting-IP') || 'unknown',
      }, limiter, n => crypto.getRandomValues(new Uint8Array(n)));
      return json(out.status, out.body);
    } catch (e) {
      return json(500, { error: 'Serverfehler' });
    }
  },
};
