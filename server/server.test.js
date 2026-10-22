import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

const sleep = ms => new Promise(r=>setTimeout(r,ms));
test('room accepts geographic discoveries, is incremental, and rejects bad payloads',async()=>{
  const dir=mkdtempSync(join(tmpdir(),'foggy-'));
  const port=48000+Math.floor(Math.random()*1000);
  const child=spawn(process.execPath,['index.js'],{cwd:new URL('.',import.meta.url),env:{...process.env,PORT:String(port),DATA_FILE:join(dir,'rooms.json')}});
  try {
    let ok=false;
    for(let i=0;i<30;i++){
      try {const r=await fetch(`http://127.0.0.1:${port}/health`);ok=r.ok;if(ok)break;} catch{}
      await sleep(100);
    }
    assert.equal(ok,true,'server starts');
    const root=`http://127.0.0.1:${port}/v1/rooms/ABCDEFGHJK`;
    const original={id:'road:100:201:300',polygon:[[51.1,7.1],[51.1,7.2],[51.11,7.2]]};
    const send = items=>fetch(`${root}/reveal`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({items})});
    let response=await send([original]);assert.equal(response.status,200);assert.equal((await response.json()).added,1);
    response=await send([original]);assert.equal((await response.json()).added,0);
    response=await fetch(`${root}/changes?since=0`);const first=await response.json();
    assert.equal(first.revision,1);assert.equal(first.items.length,1);
    response=await fetch(`${root}/changes?since=1`);assert.equal((await response.json()).items.length,0);
    response=await send([{id:'road:hacked',polygon:[[1000,7],[0,0],[1,1]]}]);assert.equal(response.status,400);
  } finally {child.kill('SIGTERM');rmSync(dir,{recursive:true,force:true});}
});
