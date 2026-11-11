// The ReAnime/flixcloud media playlist carries #EXT-X-KEY, so FlixcloudPlaylistDataSource (which
// sits *below* Media3's Aes128DataSource) only ever sees ciphertext. This decrypts a segment the
// way Media3 would and inspects the plaintext, to settle where the PNG/WEBP unwrap has to happen
// and whether the downloader can simply cache ciphertext.

import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createDecipheriv } from 'node:crypto';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9335;
const UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/126.0.6478.122 Mobile Safari/537.36';
const SEGMENT_MASK = Buffer.from([157, 42, 241, 71, 179, 142, 92, 112, 166, 25, 228, 59, 216, 98, 15, 197]);

function decodePlaylist(text, keyBase64) {
  if (text.startsWith('#EXTM3U')) return text;
  const key = Buffer.from(keyBase64, 'base64');
  const enc = Buffer.from(text, 'base64');
  const out = Buffer.alloc(enc.length);
  for (let i = 0; i < enc.length; i++) out[i] = enc[i] ^ key[i % key.length];
  const s = out.toString('utf8');
  return s.startsWith('#EXTM3U') ? s : null;
}

const CAPTURE = `
(function(){ if(window.__p)return; window.__p=1; window.__hits=[];
function rep(v,h){try{var u=String(v||'');if(u.indexOf('.m3u8')>=0)window.__hits.push({url:u,pk:String(window.__pk||'')})}catch(e){}}
function hk(){try{if(window.Hls&&window.Hls.prototype&&!window.Hls.prototype.__h){var o=window.Hls.prototype.loadSource;window.Hls.prototype.__h=1;window.Hls.prototype.loadSource=function(s){rep(s);return o.apply(this,arguments)}}}catch(e){}}
var of=window.fetch;window.fetch=function(i){try{rep(typeof i==='string'?i:(i&&i.url))}catch(e){}return of.apply(this,arguments)};
var oo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){rep(u);return oo.apply(this,arguments)};
setInterval(hk,100);hk();})()`;

function cdp(ws) {
  let id = 0; const pending = new Map();
  ws.addEventListener('message', (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } });
  return (method, params = {}) => new Promise((res) => { const n = ++id; pending.set(n, res); ws.send(JSON.stringify({ id: n, method, params })); });
}

async function get(url, headers = {}) {
  const r = await fetch(url, { headers: { 'User-Agent': UA, ...headers }, redirect: 'follow' });
  return { status: r.status, body: await r.text() };
}

const profile = mkdtempSync(join(tmpdir(), 'flixseg-'));
const chrome = spawn(CHROME, [`--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--headless=new', '--no-first-run', '--disable-gpu', '--mute-audio', '--autoplay-policy=no-user-gesture-required']);

try {
  for (let i = 0; i < 40; i++) { try { await fetch(`http://127.0.0.1:${PORT}/json/version`); break; } catch { await new Promise((r) => setTimeout(r, 250)); } }

  const base = 'https://reanime.to';
  const api = await (await fetch(`${base}/api/flix/154587/1`, { headers: { 'User-Agent': UA, Referer: `${base}/` } })).json();
  const server = api.servers.find((s) => ['sub', 's-sub'].includes(String(s.dataType).toLowerCase()));

  const tab = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json();
  const ws = new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener('open', r));
  const send = cdp(ws);
  await send('Network.enable');
  await send('Network.setExtraHTTPHeaders', { headers: { Referer: `${base}/` } });
  await send('Network.setUserAgentOverride', { userAgent: UA });
  await send('Page.enable'); await send('Runtime.enable');
  await send('Page.addScriptToEvaluateOnNewDocument', { source: CAPTURE });
  await send('Page.navigate', { url: server.dataLink });
  let hit = null;
  for (let i = 0; i < 30 && !hit; i++) {
    await new Promise((r) => setTimeout(r, 700));
    const v = (await send('Runtime.evaluate', { expression: 'JSON.stringify(window.__hits||[])', returnByValue: true })).result?.result?.value;
    hit = JSON.parse(v || '[]')[0] ?? null;
  }
  ws.close(); await fetch(`http://127.0.0.1:${PORT}/json/close/${tab.id}`);
  if (!hit) { console.log('no capture'); process.exit(1); }

  const origin = new URL(server.dataLink).origin + '/';
  const master = decodePlaylist((await get(hit.url, { Referer: origin })).body, hit.pk);
  const lines = master.split(/\r?\n/);
  let videoUrl = null;
  for (let i = 0; i < lines.length; i++) if (lines[i].startsWith('#EXT-X-STREAM-INF')) { videoUrl = new URL(lines[i + 1].trim(), hit.url).toString(); break; }
  const media = decodePlaylist((await get(videoUrl, { Referer: origin })).body, hit.pk);

  // #EXT-X-KEY:METHOD=AES-128,URI="...",IV=0x...
  const keyLine = /#EXT-X-KEY:([^\n]*)/.exec(media)[1];
  console.log(`EXT-X-KEY: ${keyLine}`);
  const keyUri = /URI="([^"]+)"/.exec(keyLine)[1];
  const ivHex = /IV=0x([0-9a-fA-F]+)/.exec(keyLine)?.[1];
  const keyUrl = new URL(keyUri, videoUrl).toString();
  console.log(`key url: ${keyUrl}`);

  const keyRes = await fetch(keyUrl, { headers: { 'User-Agent': UA, Referer: origin } });
  const keyBuf = Buffer.from(await keyRes.arrayBuffer());
  console.log(`key fetch: HTTP ${keyRes.status} ${keyBuf.length} bytes  hex=${keyBuf.toString('hex')}`);
  if (keyBuf.length !== 16) { console.log('  !! not a 16-byte AES key'); }

  const segs = media.split(/\r?\n/).filter((l) => l && !l.startsWith('#')).map((l) => new URL(l.trim(), videoUrl).toString());
  const segRes = await fetch(segs[0], { headers: { 'User-Agent': UA, Referer: origin } });
  const cipher = Buffer.from(await segRes.arrayBuffer());
  console.log(`\nsegment 0: HTTP ${segRes.status} ${cipher.length}B type=${segRes.headers.get('content-type')}`);
  console.log(`  ciphertext first 16: ${cipher.subarray(0, 16).toString('hex')}`);

  // Media3 uses the media sequence number as IV when none is given.
  const iv = ivHex ? Buffer.from(ivHex.padStart(32, '0'), 'hex') : (() => { const b = Buffer.alloc(16); b.writeUInt32BE(0, 12); return b; })();
  console.log(`  IV: ${iv.toString('hex')} (${ivHex ? 'from playlist' : 'derived from media sequence 0'})`);

  const d = createDecipheriv('aes-128-cbc', keyBuf, iv);
  d.setAutoPadding(false);
  const plain = Buffer.concat([d.update(cipher.subarray(0, cipher.length - (cipher.length % 16))), d.final()]);
  console.log(`  plaintext first 16: ${plain.subarray(0, 16).toString('hex')}`);
  console.log(`  plaintext ascii   : ${JSON.stringify(plain.subarray(0, 16).toString('latin1'))}`);

  const isPng = plain.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
  const isWebp = plain.subarray(0, 4).toString('latin1') === 'RIFF' && plain.subarray(8, 12).toString('latin1') === 'WEBP';
  const isTs = plain[0] === 0x47;
  const isMp4 = ['ftyp', 'styp'].includes(plain.subarray(4, 8).toString('latin1'));
  console.log(`  container: ${isPng ? 'PNG-wrapped' : isWebp ? 'WEBP-wrapped' : isTs ? 'raw MPEG-TS' : isMp4 ? 'fMP4' : 'unknown'}`);

  if (isPng || isWebp) {
    const hs = isWebp ? 12 : 8;
    let body = plain.subarray(hs);
    if (body[0] !== 0x47) {
      const masked = Buffer.from(body);
      for (let i = 0; i < masked.length; i++) masked[i] ^= SEGMENT_MASK[i & 15];
      console.log(`  after header strip + XOR mask: ${masked.subarray(0, 8).toString('hex')} -> ${masked[0] === 0x47 ? 'MPEG-TS' : 'still unknown'}`);
      body = masked;
    } else {
      console.log('  after header strip: MPEG-TS (no mask needed)');
    }
    writeFileSync('C:/tmp/reanime-plain.ts', body);
    console.log('  wrote C:/tmp/reanime-plain.ts');
  } else if (isTs || isMp4) {
    writeFileSync('C:/tmp/reanime-plain.ts', plain);
    console.log('  wrote C:/tmp/reanime-plain.ts');
  }
} finally {
  chrome.kill();
}
