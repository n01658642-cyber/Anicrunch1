// Off-device replica of AnivexaClient.megaPlaySources() for AniKoto, plus the
// EpisodeDownloads.canDownload() predicate, so "is this server downloadable today"
// can be answered without a device.
//
// Mirrors: anikoto() -> megaPlaySources() -> megaPlayRoutes()/megaPlayClientScripts()
// in app/src/main/java/com/anilili/data/remote/AnivexaClient.kt.

const UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/126.0.6478.122 Mobile Safari/537.36';

const SOURCE_ROUTE = /["'](stream\/getSources[\w/-]*)/gi;
const SCRIPT_SRC = /<script\b[^>]*\bsrc=["']([^"']+)["']/gi;

async function get(url, headers = {}) {
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, ...headers },
    redirect: 'follow',
  });
  const body = await res.text();
  return { status: res.status, body, url: res.url };
}

function dataAttr(html, name) {
  const m = new RegExp(`data-${name}=["']([^"']+)["']`, 'i').exec(html);
  return m ? m[1] : null;
}

function routesFrom(script) {
  const found = [...script.matchAll(SOURCE_ROUTE)].map((m) => m[1]);
  return [...new Set(found)].sort((a, b) => b.length - a.length || a.localeCompare(b));
}

function clientScripts(html, embedUrl) {
  const origin = new URL(embedUrl).origin;
  const all = [...html.matchAll(SCRIPT_SRC)].map((m) => new URL(m[1], embedUrl).toString());
  return [...new Set(all)]
    .filter((u) => u.startsWith(origin + '/'))
    .sort((a, b) => Number(b.includes('newclient')) - Number(a.includes('newclient')));
}

async function megaPlaySources(embedUrl, referer) {
  const log = [];
  let page = await get(embedUrl, { Referer: referer });
  log.push(`embed page ${embedUrl} -> HTTP ${page.status} (${page.body.length}B)`);

  const iframe = /<iframe\b[^>]*src=["']([^"']+)["']/i.exec(page.body);
  if (!dataAttr(page.body, 'id') && iframe) {
    embedUrl = new URL(iframe[1], embedUrl).toString();
    page = await get(embedUrl, { Referer: referer });
    log.push(`  followed iframe -> ${embedUrl} HTTP ${page.status}`);
  }

  const fileId = dataAttr(page.body, 'id');
  const origin = new URL(embedUrl).origin;
  log.push(`  data-id = ${fileId ?? 'MISSING'}`);
  if (!fileId) return { log, streams: [], subtitles: [] };

  // megaPlayRoutes(): read the routes off the page's own scripts, first 2 lookups.
  const scripts = clientScripts(page.body, embedUrl);
  let routes = [];
  for (const src of scripts.slice(0, 2)) {
    const js = await get(src, { Referer: `${origin}/` });
    const found = routesFrom(js.body);
    log.push(`  script ${src.split('/').pop()} HTTP ${js.status} routes=[${found}]`);
    if (found.length) {
      routes = found;
      break;
    }
  }
  if (!routes.length) {
    routes = ['stream/getSources'];
    log.push('  no routes discovered, falling back to stream/getSources');
  }

  let metadata = null;
  let sourceFile = null;
  for (const route of routes) {
    const url = `${origin}/${route}?id=${encodeURIComponent(fileId)}&id=${encodeURIComponent(fileId)}`;
    const answer = await get(url, {
      Referer: `${origin}/`,
      'X-Requested-With': 'XMLHttpRequest',
    });
    let json = null;
    try {
      json = JSON.parse(answer.body);
    } catch {
      log.push(`  ${route} -> HTTP ${answer.status} NOT JSON (${answer.body.slice(0, 60)})`);
      continue;
    }
    const file = json?.sources?.file;
    log.push(
      `  ${route} -> HTTP ${answer.status} keys=[${Object.keys(json)}] ` +
        `sources.file=${file ? 'YES' : 'no'}`,
    );
    if (metadata === null) metadata = json;
    if (file) {
      metadata = json;
      sourceFile = file;
      break;
    }
  }

  const streams = [];
  if (sourceFile) streams.push({ url: sourceFile, type: 'hls', label: 'MegaPlay', playlistKey: null });
  const subtitles = (metadata?.tracks ?? []).filter(
    (t) => !t.kind || t.kind === 'captions' || t.kind === 'subtitles',
  );
  // The embed stream anikoto() always appends when the page is alive.
  streams.push({ url: embedUrl, type: 'embed', label: 'MegaPlay embed', playlistKey: null });
  return { log, streams, subtitles, origin };
}

// EpisodeDownloads.canDownload()
function canDownload(s) {
  if (!s) return false;
  if (s.type === 'embed') return false;
  if (s.playlistKey != null) return false;
  const isHls = s.type === 'hls' || /\.m3u8/i.test(s.url);
  const isDirect = !isHls && !['dash', 'mpd', 'webrtc'].includes(s.type);
  return isHls || isDirect;
}

const CASES = [
  { title: 'Frieren', anilistId: 154587, episode: 1, audio: 'sub' },
  { title: 'One Piece', anilistId: 21, episode: 1100, audio: 'sub' },
  { title: 'Solo Leveling', anilistId: 151807, episode: 1, audio: 'dub' },
];

for (const c of CASES) {
  console.log(`\n=== AniKoto · ${c.title} ep${c.episode} ${c.audio.toUpperCase()} ===`);
  try {
    const r = await megaPlaySources(
      `https://megaplay.buzz/stream/ani/${c.anilistId}/${c.episode}/${c.audio}`,
      'https://hianimes.re/',
    );
    r.log.forEach((l) => console.log(l));
    console.log(`  streams: ${r.streams.map((s) => s.type).join(', ') || 'none'}`);
    console.log(`  subtitles: ${r.subtitles.length}`);
    const downloadable = r.streams.find(canDownload);
    console.log(
      `  >>> canDownload: ${downloadable ? 'YES — ' + downloadable.url.slice(0, 110) : 'NO (embed only)'}`,
    );
    if (downloadable) {
      const m = await get(downloadable.url, { Referer: `${r.origin}/` });
      const isManifest = m.body.startsWith('#EXTM3U');
      console.log(
        `  >>> manifest fetch: HTTP ${m.status} ${isManifest ? 'valid #EXTM3U' : 'NOT a playlist'} ` +
          `(${m.body.length}B)`,
      );
      if (isManifest) {
        const variants = [...m.body.matchAll(/RESOLUTION=(\d+x\d+)/g)].map((x) => x[1]);
        console.log(`  >>> renditions: ${variants.join(', ') || '(media playlist, single rendition)'}`);
        console.log(`  >>> encrypted: ${/#EXT-X-KEY/.test(m.body) ? 'yes (EXT-X-KEY)' : 'no'}`);
      }
    }
  } catch (e) {
    console.log(`  FAILED: ${e.message}`);
  }
}
