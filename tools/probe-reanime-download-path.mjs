// Validates, off-device, that everything the downloader would need from ReAnime/flixcloud is
// reachable with plain HTTP once the playlistKey is in hand:
//
//   master.m3u8 (XOR'd)  -> decodeFlixcloudPlaylist
//   media playlist (XOR'd?) -> decodeFlixcloudPlaylist
//   segment (.png/.webp wrapped + XOR masked) -> unwrapFlixcloudSegmentInPlace
//
// Mirrors app/src/main/java/com/anilili/playback/FlixcloudPlaylistDataSource.kt exactly, and
// reports the signed-URL lifetime, which decides whether a 24-minute episode can finish.

import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9334;
const UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/126.0.6478.122 Mobile Safari/537.36';

// FlixcloudPlaylistDataSource.segmentMask
const SEGMENT_MASK = Buffer.from([
  157, 42, 241, 71, 179, 142, 92, 112, 166, 25, 228, 59, 216, 98, 15, 197,
]);

// decodeFlixcloudPlaylist(): base64 -> XOR with base64(key) -> must start #EXTM3U
function decodePlaylist(encodedText, keyBase64) {
  if (encodedText.startsWith('#EXTM3U')) return { body: encodedText, wasEncoded: false };
  try {
    const key = Buffer.from(keyBase64, 'base64');
    if (!key.length) return null;
    const encrypted = Buffer.from(encodedText, 'base64');
    const out = Buffer.alloc(encrypted.length);
    for (let i = 0; i < encrypted.length; i++) out[i] = encrypted[i] ^ key[i % key.length];
    const text = out.toString('utf8');
    return text.startsWith('#EXTM3U') ? { body: text, wasEncoded: true } : null;
  } catch {
    return null;
  }
}

// unwrapFlixcloudSegmentInPlace(): strip PNG/WEBP header, then XOR mask if needed, expect 0x47
function unwrapSegment(buf) {
  let headerSize;
  if (buf.subarray(0, 4).toString('latin1') === 'RIFF' && buf.subarray(8, 12).toString('latin1') === 'WEBP') {
    headerSize = 12;
  } else if (buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    headerSize = 8;
  } else {
    return { ok: false, why: 'no PNG/WEBP header', headerSize: 0, masked: false };
  }
  if (buf[headerSize] === 0x47) {
    return { ok: true, headerSize, masked: false, body: buf.subarray(headerSize) };
  }
  const out = Buffer.from(buf.subarray(headerSize));
  for (let i = 0; i < out.length; i++) out[i] ^= SEGMENT_MASK[i & 15];
  if (out[0] === 0x47) return { ok: true, headerSize, masked: true, body: out };
  return { ok: false, why: 'not TS after header strip or XOR mask', headerSize, masked: false };
}

function jwtExpiry(url) {
  const token = new URL(url).searchParams.get('token');
  if (!token) return null;
  try {
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    return payload;
  } catch {
    return null;
  }
}

// ---- resolve via headless Chrome (stands in for FlixcloudBridge's WebView) ----------------

const CAPTURE = `
(function() {
  if (window.__probeHooked) return;
  window.__probeHooked = true;
  window.__hits = [];
  function report(v, how) { try { var u = String(v || ''); if (u.indexOf('.m3u8') >= 0) window.__hits.push({url:u,how:how,pk:String(window.__pk||'')}); } catch(e){} }
  function hookHls() { try { if (window.Hls && window.Hls.prototype && !window.Hls.prototype.__h) { var o = window.Hls.prototype.loadSource; window.Hls.prototype.__h = true; window.Hls.prototype.loadSource = function(s){ report(s,'hls'); return o.apply(this, arguments); }; } } catch(e){} }
  var of = window.fetch; window.fetch = function(i){ try { report(typeof i === 'string' ? i : (i && i.url), 'fetch'); } catch(e){} return of.apply(this, arguments); };
  var oo = XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open = function(m,u){ report(u,'xhr'); return oo.apply(this, arguments); };
  setInterval(hookHls, 100); hookHls();
})()`;

function cdp(ws) {
  let id = 0;
  const pending = new Map();
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  });
  return (method, params = {}) =>
    new Promise((res) => { const n = ++id; pending.set(n, res); ws.send(JSON.stringify({ id: n, method, params })); });
}

async function get(url, headers = {}) {
  const res = await fetch(url, { headers: { 'User-Agent': UA, ...headers }, redirect: 'follow' });
  return { status: res.status, body: await res.text() };
}

const profile = mkdtempSync(join(tmpdir(), 'flixpath-'));
const chrome = spawn(CHROME, [
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--headless=new',
  '--no-first-run', '--disable-gpu', '--mute-audio', '--autoplay-policy=no-user-gesture-required',
]);

try {
  for (let i = 0; i < 40; i++) {
    try { await fetch(`http://127.0.0.1:${PORT}/json/version`); break; }
    catch { await new Promise((r) => setTimeout(r, 250)); }
  }

  const base = 'https://reanime.to';
  const api = await (await fetch(`${base}/api/flix/154587/1`, { headers: { 'User-Agent': UA, Referer: `${base}/` } })).json();
  const server = (api.servers ?? []).find((s) => ['sub', 's-sub'].includes(String(s.dataType).toLowerCase()));
  console.log(`server: ${server.serverName} -> ${server.dataLink}`);

  const tab = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json();
  const ws = new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener('open', r));
  const send = cdp(ws);
  await send('Network.enable');
  await send('Network.setExtraHTTPHeaders', { headers: { Referer: `${base}/` } });
  await send('Network.setUserAgentOverride', { userAgent: UA });
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Page.addScriptToEvaluateOnNewDocument', { source: CAPTURE });
  await send('Page.navigate', { url: server.dataLink });

  let hit = null;
  for (let i = 0; i < 30 && !hit; i++) {
    await new Promise((r) => setTimeout(r, 700));
    const v = (await send('Runtime.evaluate', { expression: 'JSON.stringify(window.__hits||[])', returnByValue: true })).result?.result?.value;
    hit = JSON.parse(v || '[]')[0] ?? null;
  }
  ws.close();
  await fetch(`http://127.0.0.1:${PORT}/json/close/${tab.id}`);
  if (!hit) { console.log('no stream captured'); process.exit(1); }

  const origin = new URL(server.dataLink).origin + '/';
  console.log(`master: ${hit.url.slice(0, 100)}`);
  console.log(`playlistKey (__pk): ${hit.pk} (${hit.pk.length} chars)`);

  const claims = jwtExpiry(hit.url);
  if (claims) {
    const now = Math.floor(Date.now() / 1000);
    console.log(`token claims: ${JSON.stringify(claims)}`);
    if (claims.exp) {
      const mins = Math.round((claims.exp - now) / 60);
      console.log(`>>> token lifetime remaining: ${mins} min ${mins > 40 ? '(enough for a full episode)' : '(TIGHT — may expire mid-download)'}`);
    }
  } else {
    console.log('token: not a JWT / no exp claim');
  }

  // --- master ---
  const rawMaster = await get(hit.url, { Referer: origin });
  const master = decodePlaylist(rawMaster.body, hit.pk);
  console.log(`\nmaster: HTTP ${rawMaster.status} ${rawMaster.body.length}B raw -> ` +
    (master ? `DECODED (${master.wasEncoded ? 'was XOR-encoded' : 'was plaintext'}) ${master.body.length}B` : 'DECODE FAILED'));
  if (!master) process.exit(1);
  console.log(master.body.split('\n').slice(0, 8).join('\n'));

  // --- media playlist ---
  const lines = master.body.split(/\r?\n/);
  let best = null;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith('#EXT-X-STREAM-INF')) {
      const h = Number(/RESOLUTION=\d+x(\d+)/.exec(lines[i])?.[1] ?? 0);
      if (!best || h > best.h) best = { h, url: new URL(lines[i + 1].trim(), hit.url).toString() };
    }
  }
  const mediaUrl = best?.url ?? hit.url;
  console.log(`\nchosen rendition: ${best ? best.h + 'p' : 'single'}`);
  const rawMedia = await get(mediaUrl, { Referer: origin });
  const media = decodePlaylist(rawMedia.body, hit.pk);
  console.log(`media: HTTP ${rawMedia.status} ${rawMedia.body.length}B raw -> ` +
    (media ? `DECODED (${media.wasEncoded ? 'was XOR-encoded' : 'was plaintext'})` : 'DECODE FAILED'));
  if (!media) process.exit(1);
  console.log(`  EXT-X-KEY: ${/#EXT-X-KEY/.test(media.body) ? 'yes' : 'no'}`);

  // --- segments ---
  const segs = media.body.split(/\r?\n/).filter((l) => l && !l.startsWith('#'))
    .map((l) => new URL(l.trim(), mediaUrl).toString());
  console.log(`  segments: ${segs.length}`);
  console.log(`  first segment path: ${new URL(segs[0]).pathname}`);

  const parts = [];
  for (const s of segs.slice(0, 3)) {
    const r = await fetch(s, { headers: { 'User-Agent': UA, Referer: origin } });
    const buf = Buffer.from(await r.arrayBuffer());
    const un = unwrapSegment(buf);
    console.log(`  seg HTTP ${r.status} ${buf.length}B type=${r.headers.get('content-type')} -> ` +
      (un.ok ? `UNWRAPPED header=${un.headerSize} masked=${un.masked} -> ${un.body.length}B` : `FAILED (${un.why})`));
    if (un.ok) parts.push(un.body);
  }
  if (parts.length) {
    writeFileSync('C:/tmp/reanime-decoded.ts', Buffer.concat(parts));
    console.log(`\nwrote C:/tmp/reanime-decoded.ts (${Buffer.concat(parts).length}B) — run ffprobe on it`);
  }
} finally {
  chrome.kill();
}
