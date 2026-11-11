// Off-device replica of AnivexaClient.reanime() -> FlixcloudBridge.resolve(), so the
// "is ReAnime downloadable" question can be answered without a device.
//
// The flixcloud embed owns rotating JS decryption, so the app runs it in a WebView; here a
// headless Chrome over CDP stands in for that WebView. The capture mirrors
// FlixcloudBridge.captureScript(): hook Hls.prototype.loadSource / fetch / XHR, take the first
// .m3u8 and read window.__pk alongside it.
//
// Usage: node tools/probe-reanime-download.mjs
// Requires Chrome; launches its own headless instance on port 9333.

import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9333;
const UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/126.0.6478.122 Mobile Safari/537.36';

const CASES = [
  { title: 'Frieren', anilistId: 154587, episode: 1, audio: 'sub' },
  { title: 'Solo Leveling', anilistId: 151807, episode: 1, audio: 'sub' },
];

// ---- reanime() up to the embed list -------------------------------------------------------

async function reanimeEmbeds(anilistId, episode, audio) {
  const base = 'https://reanime.to';
  const res = await fetch(`${base}/api/flix/${anilistId}/${episode}`, {
    headers: { 'User-Agent': UA, Referer: `${base}/` },
  });
  if (!res.ok) throw new Error(`flix api HTTP ${res.status}`);
  const json = await res.json();
  const accepted = audio === 'dub' ? ['dub', 's-dub'] : ['sub', 's-sub'];
  const embeds = (json.servers ?? [])
    .filter((s) => accepted.includes(String(s.dataType ?? '').toLowerCase()))
    .map((s) => {
      let url = s.dataLink;
      if (audio === 'dub' && !/[?&]a=/.test(url)) url += url.includes('?') ? '&a=1' : '?a=1';
      return { url, name: s.serverName ?? 'ReAnime' };
    });
  const seen = new Set();
  const unique = embeds.filter((e) => !seen.has(e.url) && seen.add(e.url));
  unique.sort(
    (a, b) =>
      (a.name.toLowerCase() === 'hd-2' ? 0 : 1) - (b.name.toLowerCase() === 'hd-2' ? 0 : 1) ||
      a.name.localeCompare(b.name),
  );
  return unique;
}

// ---- CDP ----------------------------------------------------------------------------------

function cdp(ws) {
  let id = 0;
  const pending = new Map();
  const listeners = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m);
      pending.delete(m.id);
    } else if (m.method) {
      listeners.forEach((fn) => fn(m));
    }
  });
  return {
    send: (method, params = {}) =>
      new Promise((res) => {
        const n = ++id;
        pending.set(n, res);
        ws.send(JSON.stringify({ id: n, method, params }));
      }),
    on: (fn) => listeners.push(fn),
  };
}

const CAPTURE = `
(function() {
  if (window.__probeHooked) return;
  window.__probeHooked = true;
  window.__hits = [];
  function report(value, how) {
    try {
      var url = String(value || '');
      if (url.indexOf('.m3u8') >= 0) {
        window.__hits.push({ url: url, how: how, pk: String(window.__pk || '') });
      }
    } catch (e) {}
  }
  function hookHls() {
    try {
      if (window.Hls && window.Hls.prototype && !window.Hls.prototype.__probeHooked) {
        var original = window.Hls.prototype.loadSource;
        window.Hls.prototype.__probeHooked = true;
        window.Hls.prototype.loadSource = function(s) { report(s, 'hls.loadSource'); return original.apply(this, arguments); };
      }
    } catch (e) {}
  }
  var of = window.fetch;
  window.fetch = function(input) {
    try { report(typeof input === 'string' ? input : (input && input.url), 'fetch'); } catch (e) {}
    return of.apply(this, arguments);
  };
  var oo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(m, u) { report(u, 'xhr'); return oo.apply(this, arguments); };
  setInterval(hookHls, 100);
  hookHls();
})()`;

async function resolveFlixcloud(client, embedUrl, referer) {
  await client.send('Network.enable');
  await client.send('Network.setExtraHTTPHeaders', { headers: { Referer: referer } });
  await client.send('Network.setUserAgentOverride', { userAgent: UA });
  await client.send('Page.enable');
  await client.send('Runtime.enable');
  await client.send('Page.addScriptToEvaluateOnNewDocument', { source: CAPTURE });

  const networkM3u8 = [];
  client.on((m) => {
    if (m.method === 'Network.requestWillBeSent' && m.params.request.url.includes('.m3u8')) {
      networkM3u8.push(m.params.request.url);
    }
  });

  await client.send('Page.navigate', { url: embedUrl });
  for (let i = 0; i < 30; i++) {
    await new Promise((r) => setTimeout(r, 700));
    const hits = (
      await client.send('Runtime.evaluate', {
        expression: 'JSON.stringify(window.__hits || [])',
        returnByValue: true,
      })
    ).result?.result?.value;
    const parsed = JSON.parse(hits || '[]');
    if (parsed.length) return { hits: parsed, networkM3u8 };
  }
  const pk = (
    await client.send('Runtime.evaluate', {
      expression: 'String(window.__pk || "")',
      returnByValue: true,
    })
  ).result?.result?.value;
  return { hits: [], networkM3u8, pk };
}

// EpisodeDownloads.canDownload()
function canDownload(s) {
  return s && s.type !== 'embed' && s.playlistKey == null && (s.type === 'hls' || /\.m3u8/i.test(s.url));
}

// ---- run ----------------------------------------------------------------------------------

const profile = mkdtempSync(join(tmpdir(), 'flixprobe-'));
const chrome = spawn(CHROME, [
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`,
  '--headless=new',
  '--no-first-run',
  '--disable-gpu',
  '--mute-audio',
  '--autoplay-policy=no-user-gesture-required',
]);
chrome.on('error', (e) => console.log('chrome failed to start:', e.message));

async function newTab() {
  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' });
  return res.json();
}

try {
  // Wait for the debugging endpoint.
  for (let i = 0; i < 40; i++) {
    try {
      await fetch(`http://127.0.0.1:${PORT}/json/version`);
      break;
    } catch {
      await new Promise((r) => setTimeout(r, 250));
    }
  }

  for (const c of CASES) {
    console.log(`\n=== ReAnime · ${c.title} ep${c.episode} ${c.audio.toUpperCase()} ===`);
    let embeds;
    try {
      embeds = await reanimeEmbeds(c.anilistId, c.episode, c.audio);
    } catch (e) {
      console.log(`  flix api FAILED: ${e.message}`);
      continue;
    }
    console.log(`  servers: ${embeds.map((e) => e.name).join(', ') || 'none'}`);
    if (!embeds.length) continue;
    const primary = embeds[0];
    console.log(`  primary: ${primary.name} -> ${primary.url}`);

    const tab = await newTab();
    const ws = new WebSocket(tab.webSocketDebuggerUrl);
    await new Promise((r) => ws.addEventListener('open', r));
    const client = cdp(ws);
    const { hits, networkM3u8, pk } = await resolveFlixcloud(client, primary.url, 'https://reanime.to/');
    ws.close();
    await fetch(`http://127.0.0.1:${PORT}/json/close/${tab.id}`);

    if (!hits.length) {
      console.log(`  >>> no .m3u8 captured (window.__pk = ${JSON.stringify(pk)})`);
      console.log(`  network .m3u8 seen: ${networkM3u8.length}`);
      continue;
    }
    const hit = hits[0];
    console.log(`  captured via ${hit.how}: ${hit.url.slice(0, 110)}`);
    console.log(`  window.__pk: ${hit.pk ? `"${hit.pk}" (len ${hit.pk.length})` : 'EMPTY'}`);

    const stream = { url: hit.url, type: 'hls', playlistKey: hit.pk || null };
    console.log(`  >>> canDownload: ${canDownload(stream) ? 'YES' : 'NO (playlistKey set)'}`);

    // What the downloader's plain OkHttp chain would receive for that manifest.
    const res = await fetch(hit.url, {
      headers: { 'User-Agent': UA, Referer: new URL(primary.url).origin + '/' },
    });
    const body = await res.text();
    const looksLikePlaylist = body.startsWith('#EXTM3U');
    console.log(
      `  >>> raw manifest fetch: HTTP ${res.status} ${looksLikePlaylist ? 'valid #EXTM3U' : 'OBFUSCATED / not a playlist'} (${body.length}B)`,
    );
    if (!looksLikePlaylist) console.log(`      first bytes: ${JSON.stringify(body.slice(0, 60))}`);
  }
} finally {
  chrome.kill();
}
