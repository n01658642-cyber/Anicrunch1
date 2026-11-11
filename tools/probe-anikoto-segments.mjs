// Walks a MegaPlay/AniKoto master playlist the way EpisodeDownloads does: master -> chosen
// rendition -> first segments, using only the headers the downloader sends (UA + Referer).
// Proves the download would finish, not just that DownloadHelper.prepare() succeeds.

const UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/126.0.6478.122 Mobile Safari/537.36';

const SOURCE_ROUTE = /["'](stream\/getSources[\w/-]*)/gi;
const SCRIPT_SRC = /<script\b[^>]*\bsrc=["']([^"']+)["']/gi;

async function get(url, headers = {}) {
  const res = await fetch(url, { headers: { 'User-Agent': UA, ...headers }, redirect: 'follow' });
  return { status: res.status, body: await res.text(), headers: res.headers };
}

async function head(url, headers = {}) {
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, Range: 'bytes=0-65535', ...headers },
    redirect: 'follow',
  });
  const buf = await res.arrayBuffer();
  return { status: res.status, bytes: buf.byteLength, type: res.headers.get('content-type') };
}

async function anikotoMaster(anilistId, episode, audio) {
  const embedUrl = `https://megaplay.buzz/stream/ani/${anilistId}/${episode}/${audio}`;
  const page = await get(embedUrl, { Referer: 'https://hianimes.re/' });
  const fileId = /data-id=["']([^"']+)["']/i.exec(page.body)?.[1];
  if (!fileId) throw new Error('no data-id on embed page');
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
    if (found.length) {
      routes = found;
      break;
    }
  }
  for (const route of routes) {
    const answer = await get(
      `${origin}/${route}?id=${encodeURIComponent(fileId)}&id=${encodeURIComponent(fileId)}`,
      { Referer: `${origin}/`, 'X-Requested-With': 'XMLHttpRequest' },
    );
    try {
      const file = JSON.parse(answer.body)?.sources?.file;
      if (file) return { file, origin };
    } catch {}
  }
  throw new Error('no sources.file from any route');
}

const CASES = [
  { title: 'Frieren', anilistId: 154587, episode: 1, audio: 'sub' },
  { title: 'Solo Leveling', anilistId: 151807, episode: 1, audio: 'dub' },
];

for (const c of CASES) {
  console.log(`\n=== AniKoto · ${c.title} ep${c.episode} ${c.audio.toUpperCase()} ===`);
  try {
    const { file, origin } = await anikotoMaster(c.anilistId, c.episode, c.audio);
    const referer = `${origin}/`;
    const master = await get(file, { Referer: referer });
    console.log(`  master: HTTP ${master.status} ${master.body.startsWith('#EXTM3U') ? 'ok' : 'BAD'}`);

    // Pick the highest RESOLUTION rendition, as DownloadQuality.BEST does.
    const lines = master.body.split(/\r?\n/);
    const variants = [];
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].startsWith('#EXT-X-STREAM-INF')) {
        const h = Number(/RESOLUTION=\d+x(\d+)/.exec(lines[i])?.[1] ?? 0);
        variants.push({ height: h, url: new URL(lines[i + 1].trim(), file).toString() });
      }
    }
    variants.sort((a, b) => b.height - a.height);
    const chosen = variants[0] ?? { height: null, url: file };
    console.log(`  chosen rendition: ${chosen.height ? chosen.height + 'p' : 'single'}`);

    const media = await get(chosen.url, { Referer: referer });
    console.log(`  media playlist: HTTP ${media.status} ${media.body.startsWith('#EXTM3U') ? 'ok' : 'BAD'}`);
    const key = /#EXT-X-KEY[^\n]*/.exec(media.body);
    console.log(`  encryption: ${key ? key[0].slice(0, 80) : 'none'}`);

    const segments = media.body
      .split(/\r?\n/)
      .filter((l) => l && !l.startsWith('#'))
      .map((l) => new URL(l.trim(), chosen.url).toString());
    console.log(`  segments: ${segments.length}`);

    for (const s of segments.slice(0, 3)) {
      const r = await head(s, { Referer: referer });
      console.log(`    seg HTTP ${r.status} ${r.bytes}B ${r.type}  ${s.split('/').pop().slice(0, 40)}`);
    }
    // Downloads are the one place a hostile referer matters least, but check anyway: if the CDN
    // is referer-gated the download would 403 even though prepare() succeeded.
    const bare = await head(segments[0]);
    console.log(`    seg with NO referer: HTTP ${bare.status} ${bare.bytes}B`);
  } catch (e) {
    console.log(`  FAILED: ${e.message}`);
  }
}
