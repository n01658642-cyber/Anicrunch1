// Full anatomy of one MegaPlay/AniKoto segment: is the PNG a real image with the video hidden
// inside it, a thin header in front of an MPEG-TS, or something else entirely?

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
const segUrl = new URL(
  media.body.split(/\r?\n/).filter((l) => l && !l.startsWith('#'))[0].trim(),
  best.url,
).toString();

const res = await fetch(segUrl, { headers: { 'User-Agent': UA, Referer: `${origin}/` } });
const buf = Buffer.from(await res.arrayBuffer());
console.log(`segment: HTTP ${res.status}  ${buf.length} bytes  content-type=${res.headers.get('content-type')}`);
console.log(`host: ${new URL(segUrl).host}`);
console.log(`first 32: ${buf.subarray(0, 32).toString('hex')}`);

// Walk PNG chunks.
let p = 8;
const chunks = [];
while (p + 8 <= buf.length) {
  const len = buf.readUInt32BE(p);
  const type = buf.subarray(p + 4, p + 8).toString('latin1');
  if (!/^[A-Za-z]{4}$/.test(type)) break;
  chunks.push({ type, len, at: p });
  p += 12 + len;
  if (type === 'IEND') break;
  if (len > buf.length) break;
}
console.log(`PNG chunks: ${chunks.map((c) => `${c.type}(${c.len})`).join(' ')}`);
console.log(`bytes after last chunk: ${buf.length - p}`);

// Where does the first plausible MPEG-TS run start (0x47 every 188 bytes, 5+ in a row)?
let tsStart = -1;
for (let i = 0; i < Math.min(buf.length - 188 * 6, 200000); i++) {
  if (buf[i] !== 0x47) continue;
  let ok = true;
  for (let k = 1; k <= 5; k++) if (buf[i + 188 * k] !== 0x47) { ok = false; break; }
  if (ok) { tsStart = i; break; }
}
console.log(`MPEG-TS sync run starts at offset: ${tsStart >= 0 ? tsStart : 'NOT FOUND'}`);
if (tsStart >= 0) {
  const tail = buf.length - tsStart;
  console.log(`  TS payload ${tail} bytes, ${tail % 188 === 0 ? 'exact' : 'NOT exact'} multiple of 188`);
}

// Try the flixcloud unwrap rule (strip 8, then XOR 16-byte mask) to see if it applies here.
const after8 = buf[8];
console.log(`byte at offset 8: 0x${after8.toString(16)} (flixcloud rule wants 0x47 here or after its XOR mask)`);
