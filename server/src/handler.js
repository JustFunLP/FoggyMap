/**
 * FoggyMap group sync – platform independent request handling.
 *
 * Data model: every group holds a grow-only set of revealed areas (id → item). Inserts are
 * "INSERT OR IGNORE", so retries, offline queues and concurrent uploads of two players can
 * neither duplicate nor delete progress. A global autoincrement `seq` is the change feed.
 *
 * Privacy: the server only ever receives revealed areas (street corridors, blocks, buildings),
 * a display name and – only if the user explicitly enables it – a live position that expires
 * after 10 minutes. Raw GPS tracks are never uploaded.
 */

export const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS groups (code TEXT PRIMARY KEY, name TEXT NOT NULL, created INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS members (code TEXT NOT NULL, mkey TEXT NOT NULL, name TEXT NOT NULL, last_seen INTEGER NOT NULL,
     live_lat REAL, live_lon REAL, live_ts INTEGER, PRIMARY KEY (code, mkey))`,
  `CREATE TABLE IF NOT EXISTS items (seq INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, id TEXT NOT NULL,
     kind TEXT NOT NULL, len REAL NOT NULL DEFAULT 0, by_key TEXT NOT NULL, data TEXT NOT NULL, created INTEGER NOT NULL,
     UNIQUE (code, id))`,
  `CREATE INDEX IF NOT EXISTS items_feed ON items (code, seq)`,
  `CREATE INDEX IF NOT EXISTS items_by ON items (code, by_key, kind)`,
];

export const LIMITS = {
  itemsPerRequest: 200,
  bodyBytes: 1_500_000,
  itemBytes: 64_000,
  verticesPerItem: 4000,
  itemsPerGroup: 300_000,
  membersPerGroup: 50,
  pageSize: 500,
  liveTtlMs: 10 * 60 * 1000,
};

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const CODE_RE = /^[A-HJ-NP-Z2-9]{12}$/;
const ID_RE = /^[rbsg]:[A-Za-z0-9:._~\-]{1,110}$/;
const MEMBER_RE = /^[a-f0-9]{16,64}$/;

/** Simple fixed-window rate limiter (per process / isolate). */
export class RateLimiter {
  constructor() { this.buckets = new Map(); }
  allow(key, limit, windowMs, now = Date.now()) {
    let b = this.buckets.get(key);
    if (!b || now - b.start >= windowMs) { b = { start: now, count: 0 }; this.buckets.set(key, b); }
    if (this.buckets.size > 50_000) this.buckets.clear();
    return ++b.count <= limit;
  }
}

export function newGroupCode(randomBytes) {
  const bytes = randomBytes(12);
  let s = '';
  for (let i = 0; i < 12; i++) s += CODE_ALPHABET[bytes[i] & 31];   // 32 symbols → 60 bit
  return s;
}

async function memberKey(id) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('foggymap:' + id));
  return [...new Uint8Array(digest)].slice(0, 8).map(b => b.toString(16).padStart(2, '0')).join('');
}

function validRing(r) {
  if (!Array.isArray(r) || r.length < 8 || r.length % 2) return false;
  for (let i = 0; i < r.length; i += 2) {
    const lat = r[i], lon = r[i + 1];
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) return false;
  }
  return true;
}

/** Validates one reveal item and returns its canonical JSON (or null). */
export function canonicalItem(o) {
  if (!o || typeof o !== 'object' || typeof o.id !== 'string' || !ID_RE.test(o.id)) return null;
  if (typeof o.k !== 'string' || !/^[rbsg]$/.test(o.k) || !o.id.startsWith(o.k + ':')) return null;
  if (!Array.isArray(o.p) || o.p.length < 1 || o.p.length > 200) return null;
  let vertices = 0;
  for (const poly of o.p) {
    if (!Array.isArray(poly) || poly.length < 1 || poly.length > 100) return null;
    for (const ring of poly) { if (!validRing(ring)) return null; vertices += ring.length / 2; }
  }
  if (o.l !== undefined) { if (!Array.isArray(o.l) || o.l.length < 4 || o.l.length % 2 || !validRing([...o.l, ...o.l])) return null; vertices += o.l.length / 2; }
  if (vertices > LIMITS.verticesPerItem) return null;
  const num = (v, max) => (v === undefined ? undefined : (Number.isFinite(v) && v >= 0 && v <= max ? v : NaN));
  const h = num(o.h, 1000), mh = num(o.mh, 1000), len = num(o.len, 100_000);
  if ([h, mh, len].some(Number.isNaN)) return null;
  const clean = { id: o.id, k: o.k, p: o.p };
  if (o.l !== undefined) clean.l = o.l;
  if (h !== undefined) clean.h = h;
  if (mh !== undefined) clean.mh = mh;
  if (len !== undefined) clean.len = len;
  if (typeof o.n === 'string') clean.n = o.n.slice(0, 80);
  const json = JSON.stringify(clean);
  return json.length <= LIMITS.itemBytes ? { id: o.id, kind: o.k, len: len || 0, json } : null;
}

const cleanName = (s, fallback) => (typeof s === 'string' && s.trim() ? s.trim().replace(/[\u0000-\u001f]/g, '').slice(0, 24) : fallback);

/**
 * @param db  adapter: { run(sql, ...params), all(sql, ...params), first(sql, ...params), batch([[sql, ...params]]) }
 * @param req { method, path, query: URLSearchParams, body: string|null, ip }
 * @returns { status, body }
 */
export async function handle(db, req, limiter, randomBytes, now = Date.now()) {
  const ip = req.ip || 'unknown';
  if (!limiter.allow('all:' + ip, 240, 60_000, now)) return { status: 429, body: { error: 'Zu viele Anfragen – bitte kurz warten.' } };

  const { method, path } = req;
  if (method === 'GET' && (path === '/' || path === '/health')) return { status: 200, body: { ok: true, service: 'FoggyMap sync', version: 2 } };

  if (method === 'POST' && path === '/v2/groups') {
    if (!limiter.allow('create:' + ip, 20, 3_600_000, now)) return { status: 429, body: { error: 'Zu viele neue Gruppen.' } };
    const data = parseBody(req.body) || {};
    const name = cleanName(data.name, 'FoggyMap-Gruppe');
    for (let attempt = 0; attempt < 5; attempt++) {
      const code = newGroupCode(randomBytes);
      const exists = await db.first('SELECT code FROM groups WHERE code = ?', code);
      if (exists) continue;
      await db.run('INSERT INTO groups (code, name, created) VALUES (?, ?, ?)', code, name, now);
      return { status: 201, body: { code, name } };
    }
    return { status: 500, body: { error: 'Code-Erzeugung fehlgeschlagen' } };
  }

  const m = path.match(/^\/v2\/groups\/([^/]+)(\/(reveals|live))?$/);
  if (!m) return { status: 404, body: { error: 'Not found' } };
  const code = m[1];
  const sub = m[3] || '';
  if (!CODE_RE.test(code)) return { status: 400, body: { error: 'Ungültiger Gruppencode' } };

  const group = await db.first('SELECT code, name FROM groups WHERE code = ?', code);
  if (!group) {
    // Slow down code guessing: unknown codes are heavily rate limited per IP.
    if (!limiter.allow('miss:' + ip, 30, 3_600_000, now)) return { status: 429, body: { error: 'Zu viele Versuche.' } };
    return { status: 404, body: { error: 'Gruppe nicht gefunden' } };
  }

  if (sub === '' && method === 'GET') {
    const rows = await db.all(`SELECT m.mkey, m.name, m.last_seen, m.live_lat, m.live_lon, m.live_ts,
        (SELECT COUNT(*) FROM items i WHERE i.code = m.code AND i.by_key = m.mkey AND i.kind = 'r') AS roads,
        (SELECT COALESCE(SUM(len), 0) FROM items i WHERE i.code = m.code AND i.by_key = m.mkey AND i.kind = 'r') AS meters
        FROM members m WHERE m.code = ? ORDER BY meters DESC`, code);
    const rev = await db.first('SELECT COALESCE(MAX(seq), 0) AS rev FROM items WHERE code = ?', code);
    const totals = await db.first(`SELECT COUNT(*) AS n, COALESCE(SUM(len), 0) AS meters FROM items WHERE code = ? AND kind = 'r'`, code);
    const members = rows.map(r => ({
      key: r.mkey, name: r.name, lastSeen: r.last_seen, roads: r.roads, km: Math.round(r.meters) / 1000,
      live: r.live_ts && now - r.live_ts < LIMITS.liveTtlMs ? { lat: r.live_lat, lon: r.live_lon, ts: r.live_ts } : null,
    }));
    return { status: 200, body: { code, name: group.name, revision: rev.rev, roads: totals.n, km: Math.round(totals.meters) / 1000, members } };
  }

  if (sub === 'reveals' && method === 'GET') {
    const since = Number(req.query.get('since') || 0);
    if (!Number.isSafeInteger(since) || since < 0) return { status: 400, body: { error: 'since' } };
    const limit = Math.min(Math.max(Number(req.query.get('limit') || LIMITS.pageSize) | 0, 1), LIMITS.pageSize);
    const rows = await db.all('SELECT seq, data, by_key FROM items WHERE code = ? AND seq > ? ORDER BY seq LIMIT ?', code, since, limit + 1);
    const more = rows.length > limit;
    const page = more ? rows.slice(0, limit) : rows;
    const revision = page.length ? page[page.length - 1].seq : since;
    return { status: 200, body: { revision, more, items: page.map(r => ({ ...JSON.parse(r.data), by: r.by_key })) } };
  }

  if (sub === 'reveals' && method === 'POST') {
    if (req.body && req.body.length > LIMITS.bodyBytes) return { status: 413, body: { error: 'Zu groß' } };
    const data = parseBody(req.body);
    const member = data && data.member;
    if (!member || typeof member.id !== 'string' || !MEMBER_RE.test(member.id)) return { status: 400, body: { error: 'member' } };
    if (!Array.isArray(data.items) || data.items.length > LIMITS.itemsPerRequest) return { status: 400, body: { error: 'items' } };
    const items = [];
    for (const raw of data.items) {
      const c = canonicalItem(raw);
      if (!c) return { status: 400, body: { error: 'Ungültiges Element', id: raw && raw.id } };
      items.push(c);
    }
    const key = await memberKey(member.id);
    const known = await db.first('SELECT COUNT(*) AS n FROM members WHERE code = ? AND mkey != ?', code, key);
    const isMember = await db.first('SELECT mkey FROM members WHERE code = ? AND mkey = ?', code, key);
    if (!isMember && known.n >= LIMITS.membersPerGroup) return { status: 403, body: { error: 'Gruppe ist voll' } };
    const count = await db.first('SELECT COUNT(*) AS n FROM items WHERE code = ?', code);
    if (count.n + items.length > LIMITS.itemsPerGroup) return { status: 507, body: { error: 'Speichergrenze der Gruppe erreicht' } };

    const statements = [[
      `INSERT INTO members (code, mkey, name, last_seen) VALUES (?, ?, ?, ?)
       ON CONFLICT (code, mkey) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen`,
      code, key, cleanName(member.name, 'Entdecker'), now,
    ]];
    for (const it of items) {
      statements.push(['INSERT OR IGNORE INTO items (code, id, kind, len, by_key, data, created) VALUES (?, ?, ?, ?, ?, ?, ?)',
        code, it.id, it.kind, it.len, key, it.json, now]);
    }
    const before = count.n;
    await db.batch(statements);
    const after = await db.first('SELECT COUNT(*) AS n, COALESCE(MAX(seq), 0) AS rev FROM items WHERE code = ?', code);
    return { status: 200, body: { ok: true, added: after.n - before, revision: after.rev, member: key } };
  }

  if (sub === 'live' && method === 'PUT') {
    const data = parseBody(req.body);
    const member = data && data.member;
    if (!member || !MEMBER_RE.test(member.id || '')) return { status: 400, body: { error: 'member' } };
    const lat = Number(data.lat), lon = Number(data.lon);
    if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) return { status: 400, body: { error: 'position' } };
    const key = await memberKey(member.id);
    await db.run(`INSERT INTO members (code, mkey, name, last_seen, live_lat, live_lon, live_ts) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (code, mkey) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen,
      live_lat = excluded.live_lat, live_lon = excluded.live_lon, live_ts = excluded.live_ts`,
      code, key, cleanName(member.name, 'Entdecker'), now, Math.round(lat * 1e5) / 1e5, Math.round(lon * 1e5) / 1e5, now);
    return { status: 200, body: { ok: true } };
  }

  if (sub === 'live' && method === 'DELETE') {
    const id = req.query.get('member') || '';
    if (!MEMBER_RE.test(id)) return { status: 400, body: { error: 'member' } };
    await db.run('UPDATE members SET live_lat = NULL, live_lon = NULL, live_ts = NULL WHERE code = ? AND mkey = ?', code, await memberKey(id));
    return { status: 200, body: { ok: true } };
  }

  return { status: 405, body: { error: 'Method not allowed' } };
}

function parseBody(text) {
  if (!text) return null;
  try { return JSON.parse(text); } catch { return null; }
}
