// Replays what a Cast receiver would fetch for each stream CastReachProbeTest dumped.
//
//   node tools/probe-cast-reach.mjs <streams.jsonl>
//
// A receiver fetches its queue itself: none of the app's headers, none of its DataSource wrappers,
// and — for HLS, DASH and subtitles — from a web page, so every playlist, key, segment and track
// needs CORS. Progressive MP4 goes to a <video> element and needs no CORS. This walks the same chain
// the receiver would (proxy check as the app does it, then manifest -> variant -> key -> segment)
// and reports the first link that would stop it.
import { readFileSync } from 'node:fs';

const ORIGIN = 'https://www.gstatic.com';
const UA = 'Mozilla/5.0 (X11; Linux armv7l) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 CrKey/1.56.500000';

async function get(url, { method = 'GET', range } = {}) {
  const headers = { 'User-Agent': UA, Origin: ORIGIN };
  if (range) headers.Range = range;
  try {
    const res = await fetch(url, { method, headers, redirect: 'follow', signal: AbortSignal.timeout(15000) });
    const acao = res.headers.get('access-control-allow-origin');
    const body = method === 'HEAD' ? '' : (range ? Buffer.from(await res.arrayBuffer()) : await res.text());
    return { status: res.status, type: res.headers.get('content-type'), cors: acao === '*' || acao === ORIGIN, body, url: res.url };
  } catch (e) {
    return { status: 0, error: e.cause?.code || e.name, cors: false, body: '' };
  }
}

const abs = (base, ref) => new URL(ref, base).toString();
const host = (u) => { try { return new URL(u).host; } catch { return '?'; } };

async function walkHls(url) {
  const steps = [];
  let m = await get(url);
  steps.push(['playlist', m]);
  if (m.status !== 200 || !m.body.startsWith('#EXTM3U')) return steps;
  let media = m;
  let mediaUrl = m.url || url;
  if (m.body.includes('#EXT-X-STREAM-INF')) {
    const lines = m.body.split('\n').map((l) => l.trim());
    const i = lines.findIndex((l) => l.startsWith('#EXT-X-STREAM-INF'));
    const ref = lines.slice(i + 1).find((l) => l && !l.startsWith('#'));
    mediaUrl = abs(mediaUrl, ref);
    media = await get(mediaUrl);
    steps.push(['variant', media]);
    if (media.status !== 200 || !media.body.startsWith('#EXTM3U')) return steps;
  }
  const key = media.body.match(/#EXT-X-KEY:([^\n]*)/);
  if (key && !/METHOD=NONE/.test(key[1])) {
    const uri = key[1].match(/URI="([^"]+)"/)?.[1];
    const hasIv = /IV=0x/i.test(key[1]);
    if (uri) {
      const k = await get(abs(mediaUrl, uri));
      k.note = hasIv ? 'explicit IV' : 'implicit IV (media sequence)';
      steps.push(['key', k]);
    }
  }
  const seg = media.body.split('\n').map((l) => l.trim()).find((l) => l && !l.startsWith('#'));
  if (seg) steps.push(['segment', await get(abs(mediaUrl, seg), { range: 'bytes=0-2047' })]);
  return steps;
}

const lines = readFileSync(process.argv[2], 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
for (const line of lines) {
  if (line.error) continue;
  for (const s of line.streams) {
    const label = `${line.provider}/${line.category} ${s.type} ${host(s.url)}`;
    if (!s.castable) { console.log(`${label}\n  NOT OFFERED (${s.keyed ? 'keyed playlist' : 'embed'})`); continue; }
    // The app's own pre-handover check: a refused proxy address falls back to the direct one.
    let castUrl = s.castUrl;
    let proxy = 'none';
    if (s.probeUrl) {
      const head = await get(s.probeUrl, { method: 'HEAD' });
      proxy = head.status >= 200 && head.status < 300 ? 'serving' : head.status === 0 ? `error ${head.error} (kept)` : `refused ${head.status} -> direct`;
      if (head.status >= 300) castUrl = s.url;
    }
    console.log(`${label}\n  proxy=${proxy} receiver gets ${host(castUrl)}`);
    if (s.mime === 'application/x-mpegURL' || s.type === 'hls' || /\.m3u8/.test(castUrl)) {
      for (const [name, r] of await walkHls(castUrl)) {
        const ok = r.status >= 200 && r.status < 300;
        console.log(`  ${name.padEnd(8)} ${r.status || r.error} cors=${r.cors} ${r.type ?? ''} ${host(r.url ?? '')}${r.note ? ' ' + r.note : ''}${ok && r.cors ? '' : '  <-- receiver stops here'}`);
        if (!ok || !r.cors) break;
      }
    } else if (s.type === 'dash' || /\.mpd/.test(castUrl)) {
      const r = await get(castUrl);
      console.log(`  manifest ${r.status || r.error} cors=${r.cors} ${r.type ?? ''}`);
    } else {
      const r = await get(castUrl, { range: 'bytes=0-2047' });
      const sig = r.body?.length ? r.body.subarray(4, 8).toString('latin1') : '';
      console.log(`  file     ${r.status || r.error} ${r.type ?? ''} ${sig === 'ftyp' ? 'mp4' : 'not-mp4:' + JSON.stringify(sig)} (no CORS needed for <video>)`);
    }
  }
}
