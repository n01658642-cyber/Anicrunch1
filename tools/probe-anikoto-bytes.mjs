// MegaPlay serves its segments with Content-Type: image/png. Either that is a mislabelled MPEG-TS
// (harmless — Media3 reads the sync bytes, not the header) or a real PNG wrapper the downloader
// would have to strip. This checks the magic bytes to tell which.

const UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/126.0.6478.122 Mobile Safari/537.36';

const SOURCE_ROUTE = /["'](stream\/getSources[\w/-]*)/gi;
const SCRIPT_SRC = /<script\b[^>]*\bsrc=["']([^"']+)["']/gi;

async function get(url, headers = {}) {
  const res = await fetch(url, { headers: { 'User-Agent': UA, ...headers }, redirect: 'follow' });
  return { status: res.status, body: await res.text() };
}

async function anikotoMaster(anilistId, episode, audio) {
  const embedUrl = `https://megaplay.buzz/stream/ani/${anilistId}/${episode}/${audio}`;
  const page = await get(embedUrl, { Referer: 'https://hianimes.re/' });
  const fileId = /data-id=["']([^"']+)["']/i.exec(page.body)?.[1];
  const origin = new URL(embedUrl).origin;
  const scripts = [...page.body.matchAll(SCRIPT_SRC)]
    .map((m) => new URL(m[1], embedUrl).toString())
    .filter((u) => u.startsWith(origin + '/'))
    .sort((a, b) => Number(b.includes('newclient')) - Number(a.includes('newclient')));
  let routes = ['stream/getSources'];
  for (const src of scripts.slice(0, 2)) {
    const js = await get(src, { Referer: `${origin}/` });
    const found = [...new Set([...js.body.matchAll(SOURCE_ROUTE)].map((m) => m[1]))].sort(
      (a, b) => b.length - a.length || a.localeCompare(b),
    );
    if (found.length) { routes = found; break; }
  }
  for (const route of routes) {
    const a = await get(`${origin}/${route}?id=${encodeURIComponent(fileId)}&id=${encodeURIComponent(fileId)}`, {
      Referer: `${origin}/`, 'X-Requested-With': 'XMLHttpRequest',
    });
    try {
      const file = JSON.parse(a.body)?.sources?.file;
      if (file) return { file, origin };
    } catch {}
  }
  throw new Error('no sources.file');
}

const { file, origin } = await anikotoMaster(154587, 1, 'sub');
const master = await get(file, { Referer: `${origin}/` });
const lines = master.body.split(/\r?\n/);
let best = null;
for (let i = 0; i < lines.length; i++) {
  if (lines[i].startsWith('#EXT-X-STREAM-INF')) {
    const h = Number(/RESOLUTION=\d+x(\d+)/.exec(lines[i])?.[1] ?? 0);
    if (!best || h > best.height) best = { height: h, url: new URL(lines[i + 1].trim(), file).toString() };
  }
}
const media = await get(best.url, { Referer: `${origin}/` });
const seg = media.body.split(/\r?\n/).filter((l) => l && !l.startsWith('#'))[0];
const segUrl = new URL(seg.trim(), best.url).toString();

console.log('segment URL:', segUrl);
const res = await fetch(segUrl, {
  headers: { 'User-Agent': UA, Referer: `${origin}/`, Range: 'bytes=0-2047' },
});
const buf = Buffer.from(await res.arrayBuffer());
console.log(`HTTP ${res.status}  content-type=${res.headers.get('content-type')}  bytes=${buf.length}`);
console.log('first 16 bytes hex :', buf.subarray(0, 16).toString('hex'));
console.log('first 16 bytes ascii:', JSON.stringify(buf.subarray(0, 16).toString('latin1')));

const isPng = buf[0] === 0x89 && buf.subarray(1, 4).toString('latin1') === 'PNG';
const isTs = buf[0] === 0x47 && buf[188] === 0x47;
const isMp4 = buf.subarray(4, 8).toString('latin1') === 'ftyp' || buf.subarray(4, 8).toString('latin1') === 'styp';
console.log(
  `verdict: ${isTs ? 'MPEG-TS (sync 0x47 at 0 and 188) — mislabelled MIME, plays/downloads fine'
    : isPng ? 'REAL PNG WRAPPER — downloader would save garbage'
    : isMp4 ? 'fMP4 segment — fine'
    : 'unrecognised container'}`,
);
