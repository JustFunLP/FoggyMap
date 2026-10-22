/** FoggyMap room sync v1. Standalone Node 20+ server; put behind HTTPS reverse proxy. */
import http from 'node:http';
import fs from 'node:fs';
import {URL} from 'node:url';

const FILE = process.env.DATA_FILE || './rooms.json';
const PORT = Number(process.env.PORT || 8080);
const rooms = Object.create(null);
try {
  const saved = JSON.parse(fs.readFileSync(FILE,'utf8'));
  for (const [k,v] of Object.entries(saved)) if (/^[A-Z2-9]{10,32}$/.test(k)) rooms[k] = v;
} catch { /* start with empty database */ }
const isCode = s => typeof s === 'string' && /^[A-Z2-9]{10,32}$/.test(s);
const isFeature = obj => {
  if (!obj || typeof obj.id !== 'string' || obj.id.length > 120 || !/^(road|block|building):[a-zA-Z0-9:.+\-]+$/.test(obj.id)) return false;
  if (!Array.isArray(obj.polygon) || obj.polygon.length < 3 || obj.polygon.length > 5000) return false;
  return obj.polygon.every(p => Array.isArray(p) && p.length === 2 &&
    Number.isFinite(p[0]) && Number.isFinite(p[1]) &&
    p[0] >= -90 && p[0] <= 90 && p[1] >= -180 && p[1] <= 180);
};
const json = (res, code, data) => {
  res.writeHead(code, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store',
    'X-Content-Type-Options':'nosniff','Access-Control-Allow-Origin':'none'});
  res.end(JSON.stringify(data));
};
const persist = () => {
  const tmp = `${FILE}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(rooms));
  fs.renameSync(tmp, FILE);
};
const getRoom = code => rooms[code] ||= {revision:0, features:[]};
const buckets = new Map();
const allowed = ip => {
  const now=Date.now(); const o=buckets.get(ip) || {start:now,count:0};
  if (now-o.start>60_000) {o.start=now;o.count=0;}
  buckets.set(ip,o); return ++o.count <= 120;
};
setInterval(() => {const now=Date.now();for(const [key,b] of buckets)if(now-b.start>120_000)buckets.delete(key);}, 60000).unref();

http.createServer(async(req,res) => {
  const remote = req.socket.remoteAddress || 'unknown';
  if(!allowed(remote)) return json(res,429,{error:'Rate limited'});
  const url = new URL(req.url, 'http://localhost');
  if(req.method==='GET' && url.pathname==='/health') return json(res,200,{ok:true,service:'FoggyMap sync v1'});
  const route = url.pathname.match(/^\/v1\/rooms\/([A-Z2-9]{10,32})\/(changes|reveal)$/);
  if(!route) return json(res,404,{error:'Not found'});
  const [,code,operation]=route;
  if(!isCode(code)) return json(res,400,{error:'Invalid room'});
  if(operation==='changes' && req.method==='GET') {
    const since = Number(url.searchParams.get('since')||0);
    if(!Number.isSafeInteger(since)||since<0) return json(res,400,{error:'Invalid revision'});
    const r = getRoom(code);
    const changes = r.features.filter(x => x.revision > since).slice(0, 2500);
    const revision = changes.length === 2500 ? changes.at(-1).revision : r.revision;
    return json(res,200,{revision,items:changes.map(({revision,...data})=>data)});
  }
  if(operation==='reveal' && req.method==='POST') {
    let text='';
    try {
      for await(const chunk of req) {
        text+=chunk;
        if(text.length > 256_000) return json(res,413,{error:'Payload too large'});
      }
      const data=JSON.parse(text);
      if(!data || !Array.isArray(data.items) || data.items.length>100 || !data.items.every(isFeature))
        return json(res,400,{error:'Invalid features'});
      const r=getRoom(code);
      if(r.features.length > 100_000) return json(res,507,{error:'Room storage limit'});
      const known = new Set(r.features.map(x=>x.id));
      let added=0;
      for(const item of data.items) {
        if(known.has(item.id))continue;
        known.add(item.id);
        r.features.push({...item,revision:++r.revision}); added++;
      }
      if(added)persist();
      return json(res,200,{ok:true,revision:r.revision,added});
    } catch {return json(res,400,{error:'Invalid JSON or storage failure'});}
  }
  return json(res,405,{error:'Method not allowed'});
}).listen(PORT,'0.0.0.0',()=>console.log(`FoggyMap sync listening on ${PORT}; HTTPS reverse proxy required`));
