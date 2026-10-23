import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { handle, RateLimiter, canonicalItem, LIMITS } from '../src/handler.js';
import { sqliteAdapter, createServer } from '../src/node-server.js';
import worker from '../cloudflare/worker.js';

const ring = (lat, lon, d = 0.0002) => [lat, lon, lat + d, lon, lat + d, lon + d, lat, lon];
const road = (n, lat = 51.0, lon = 7.0) => ({ id: `r:${n}:1-2`, k: 'r', p: [[ring(lat, lon)]], l: [lat, lon, lat + 0.001, lon], len: 111.2 });
const block = (n) => ({ id: `b:${n}`, k: 'b', p: [[ring(51.01, 7.01, 0.001)]] });
const alice = { id: 'a'.repeat(32), name: 'Alice' };
const bob = { id: 'b'.repeat(32), name: 'Bob' };

function client(db = sqliteAdapter(':memory:'), limiter = new RateLimiter()) {
  const call = (method, path, body, ip = '1.1.1.1', now) => {
    const url = new URL(path, 'http://x');
    return handle(db, { method, path: url.pathname, query: url.searchParams, body: body === undefined ? null : JSON.stringify(body), ip },
      limiter, n => randomBytes(n), now);
  };
  return { db, call };
}

test('create group returns a 12-character code from the unambiguous alphabet', async () => {
  const { call } = client();
  const r = await call('POST', '/v2/groups', { name: 'Büro' });
  assert.equal(r.status, 201);
  assert.match(r.body.code, /^[A-HJ-NP-Z2-9]{12}$/);
  const g = await call('GET', `/v2/groups/${r.body.code}`);
  assert.equal(g.status, 200);
  assert.equal(g.body.name, 'Büro');
  assert.deepEqual(g.body.members, []);
});

test('upload is idempotent and two players merge as a union', async () => {
  const { call } = client();
  const { code } = (await call('POST', '/v2/groups', {})).body;
  let r = await call('POST', `/v2/groups/${code}/reveals`, { member: alice, items: [road(1), road(2), block(9)] });
  assert.equal(r.status, 200); assert.equal(r.body.added, 3);
  const aliceKey = r.body.member;
  r = await call('POST', `/v2/groups/${code}/reveals`, { member: alice, items: [road(1), road(2), block(9)] });   // retry
  assert.equal(r.body.added, 0);
  r = await call('POST', `/v2/groups/${code}/reveals`, { member: bob, items: [block(9), road(3)] });              // overlap
  assert.equal(r.body.added, 1);
  const feed = await call('GET', `/v2/groups/${code}/reveals?since=0`);
  assert.deepEqual(feed.body.items.map(i => i.id).sort(), ['b:9', 'r:1:1-2', 'r:2:1-2', 'r:3:1-2']);
  // The first uploader keeps ownership of a shared block; nothing was overwritten.
  assert.equal(feed.body.items.find(i => i.id === 'b:9').by, aliceKey);
  assert.notEqual(r.body.member, aliceKey);
  const g = await call('GET', `/v2/groups/${code}`);
  assert.equal(g.body.members.length, 2);
  assert.equal(g.body.roads, 3);
  assert.equal(g.body.members.find(m => m.name === 'Alice').roads, 2);
});

test('change feed is incremental and paginated', async () => {
  const { call } = client();
  const { code } = (await call('POST', '/v2/groups', {})).body;
  for (let k = 0; k < 6; k++) {
    await call('POST', `/v2/groups/${code}/reveals`, { member: alice, items: Array.from({ length: 200 }, (_, i) => road(k * 1000 + i)) });
  }
  let since = 0, all = [], pages = 0, more = true;
  while (more) {
    const r = await call('GET', `/v2/groups/${code}/reveals?since=${since}`);
    all.push(...r.body.items); since = r.body.revision; more = r.body.more; pages++;
  }
  assert.equal(all.length, 1200);
  assert.equal(new Set(all.map(i => i.id)).size, 1200);
  assert.equal(pages, 3);
  const none = await call('GET', `/v2/groups/${code}/reveals?since=${since}`);
  assert.equal(none.body.items.length, 0);
  assert.equal(none.body.revision, since);
});

test('concurrent uploads of overlapping sets never duplicate', async () => {
  const { call } = client();
  const { code } = (await call('POST', '/v2/groups', {})).body;
  const jobs = [];
  for (let p = 0; p < 10; p++) {
    const m = { id: String(p).repeat(32).slice(0, 32).replace(/[^a-f0-9]/g, 'c'), name: 'P' + p };
    jobs.push(call('POST', `/v2/groups/${code}/reveals`, { member: m, items: Array.from({ length: 50 }, (_, i) => road(i + p * 10)) }));
  }
  await Promise.all(jobs);
  const r = await call('GET', `/v2/groups/${code}/reveals?since=0&limit=500`);
  assert.equal(r.body.items.length, 140);
  assert.equal(new Set(r.body.items.map(i => i.id)).size, 140);
});

test('rejects invalid input', async () => {
  const { call } = client();
  const { code } = (await call('POST', '/v2/groups', {})).body;
  const post = items => call('POST', `/v2/groups/${code}/reveals`, { member: alice, items });
  assert.equal((await post([{ id: 'r:x', k: 'r', p: [[[100, 7, 1, 1, 2, 2, 100, 7]]] }])).status, 400);    // latitude 100
  assert.equal((await post([{ id: 'x:1', k: 'x', p: [[ring(51, 7)]] }])).status, 400);                    // unknown kind
  assert.equal((await post([{ id: 'b:1', k: 'r', p: [[ring(51, 7)]] }])).status, 400);                    // kind/id mismatch
  assert.equal((await post([{ id: "r:1'; DROP TABLE items;--", k: 'r', p: [[ring(51, 7)]] }])).status, 400);
  assert.equal((await post(Array.from({ length: LIMITS.itemsPerRequest + 1 }, (_, i) => road(i)))).status, 400);
  assert.equal((await call('POST', `/v2/groups/${code}/reveals`, { member: { id: 'nope' }, items: [] })).status, 400);
  assert.equal((await call('GET', '/v2/groups/abc')).status, 400);
  assert.equal(canonicalItem({ id: 'r:1', k: 'r', p: [[ring(51, 7)]], h: -5 }), null);
});

test('unknown codes are 404 and code guessing gets rate limited', async () => {
  const { call } = client();
  let last;
  for (let i = 0; i < 31; i++) last = await call('GET', '/v2/groups/AAAAAAAAAAAA', undefined, '9.9.9.9');
  assert.equal(last.status, 429);
  assert.equal((await call('GET', '/v2/groups/AAAAAAAAAAAA', undefined, '8.8.8.8')).status, 404);
});

test('live position is opt-in, expires after 10 minutes and can be withdrawn', async () => {
  const { call } = client();
  const { code } = (await call('POST', '/v2/groups', {})).body;
  const t0 = 1_700_000_000_000;
  assert.equal((await call('PUT', `/v2/groups/${code}/live`, { member: alice, lat: 51.123456789, lon: 7.1 }, '1.1.1.1', t0)).status, 200);
  let g = await call('GET', `/v2/groups/${code}`, undefined, '1.1.1.1', t0 + 1000);
  assert.equal(g.body.members[0].live.lat, 51.12346);  // rounded (~1 m)
  g = await call('GET', `/v2/groups/${code}`, undefined, '1.1.1.1', t0 + 11 * 60 * 1000);
  assert.equal(g.body.members[0].live, null);
  await call('PUT', `/v2/groups/${code}/live`, { member: alice, lat: 51, lon: 7 }, '1.1.1.1', t0);
  await call('DELETE', `/v2/groups/${code}/live?member=${alice.id}`, undefined, '1.1.1.1', t0);
  g = await call('GET', `/v2/groups/${code}`, undefined, '1.1.1.1', t0 + 1000);
  assert.equal(g.body.members[0].live, null);
});

test('node HTTP server end to end', async () => {
  const { server } = createServer({ file: ':memory:' });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    assert.equal((await (await fetch(`${base}/health`)).json()).ok, true);
    const { code } = await (await fetch(`${base}/v2/groups`, { method: 'POST', body: '{}' })).json();
    const r = await fetch(`${base}/v2/groups/${code}/reveals`, { method: 'POST', body: JSON.stringify({ member: alice, items: [road(1)] }) });
    assert.equal((await r.json()).added, 1);
  } finally { server.close(); }
});

/** Minimal Cloudflare D1 emulation on top of node:sqlite to exercise worker.js. */
function fakeD1() {
  const db = new DatabaseSync(':memory:');
  const bound = (sql, params) => ({
    sql, params,
    async run() { return db.prepare(sql).run(...params); },
    async all() { return { results: db.prepare(sql).all(...params) }; },
    async first() { return db.prepare(sql).get(...params) ?? null; },
  });
  return {
    prepare(sql) { return { bind: (...p) => bound(sql, p), ...bound(sql, []) }; },
    async batch(list) {
      db.exec('BEGIN');
      try { const out = list.map(s => db.prepare(s.sql).run(...s.params)); db.exec('COMMIT'); return out; }
      catch (e) { db.exec('ROLLBACK'); throw e; }
    },
  };
}

test('cloudflare worker entry point works against D1 semantics', async () => {
  const env = { DB: fakeD1() };
  const req = (method, path, body) => worker.fetch(new Request('https://foggy.example.workers.dev' + path,
    { method, body: body ? JSON.stringify(body) : undefined, headers: { 'CF-Connecting-IP': '2.2.2.2' } }), env);
  const created = await (await req('POST', '/v2/groups', { name: 'CF' })).json();
  assert.match(created.code, /^[A-HJ-NP-Z2-9]{12}$/);
  const up = await (await req('POST', `/v2/groups/${created.code}/reveals`, { member: alice, items: [road(1), block(2)] })).json();
  assert.equal(up.added, 2);
  const feed = await (await req('GET', `/v2/groups/${created.code}/reveals?since=0`)).json();
  assert.equal(feed.items.length, 2);
  const missing = await worker.fetch(new Request('https://x/health'), {});
  assert.equal(missing.status, 500);
});

for (const fixture of ['client-items.json', 'client-items-tiles.json'])
test(`contract: reveal items produced by the Android core are accepted and returned unchanged (${fixture})`, async () => {
  const { readFileSync } = await import('node:fs');
  const items = JSON.parse(readFileSync(new URL(`./fixtures/${fixture}`, import.meta.url), 'utf8'));
  const { call } = client();
  const { code } = (await call('POST', '/v2/groups', { name: 'Vertrag' })).body;
  let added = 0;
  for (let i = 0; i < items.length; i += 150) {
    const r = await call('POST', `/v2/groups/${code}/reveals`, { member: alice, items: items.slice(i, i + 150) });
    assert.equal(r.status, 200, JSON.stringify(r.body));
    added += r.body.added;
  }
  assert.equal(added, items.length);
  const back = [];
  let since = 0, more = true;
  while (more) {
    const r = await call('GET', `/v2/groups/${code}/reveals?since=${since}`);
    back.push(...r.body.items); since = r.body.revision; more = r.body.more;
  }
  const byId = new Map(back.map(({ by, ...rest }) => [rest.id, rest]));
  for (const it of items) assert.deepEqual(byId.get(it.id), it);
});
