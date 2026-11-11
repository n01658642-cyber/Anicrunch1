// Dumps the `tracks` array MegaPlay/AniKoto returns for a given title+episode, so the subtitle
// list the app builds can be compared against what the site itself offers.
//
//   node tools/probe-anikoto-tracks.mjs <anilistId> <episode> [sub|dub]
//
// With no arguments it walks a small fixed set: one back-catalogue title and one current one.

const UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/126.0.6478.122 Mobile Safari/537.36';

const SOURCE_ROUTE = /["'](stream\/getSources[\w/-]*)/gi;
const SCRIPT_SRC = /<script\b[^>]*\bsrc=["']([^"']+)["']/gi;

async function get(url, headers = {}) {
  const res = await fetch(url, { headers: { 'User-Agent': UA, ...headers }, redirect: 'follow' });
  return { status: res.status, body: await res.text() };
}

async function answerFor(embedUrl) {
  const page = await get(embedUrl, { Referer: 'https://hianimes.re/' });
  const fileId = /data-id=["']([^"']+)["']/i.exec(page.body)?.[1];
  if (!fileId) return { error: `no data-id (status ${page.status})` };
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
  for (const route of [...new Set([...routes, 'stream/getSources'])]) {
    const answer = await get(
      `${origin}/${route}?id=${encodeURIComponent(fileId)}&id=${encodeURIComponent(fileId)}`,
      { Referer: `${origin}/`, 'X-Requested-With': 'XMLHttpRequest' },
    );
    let parsed;
    try {
      parsed = JSON.parse(answer.body);
    } catch {
      continue;
    }
    if (parsed) return { route, fileId, parsed };
  }
  return { error: 'no route answered with JSON' };
}

function describe(label, result) {
  console.log(`\n=== ${label}`);
  if (result.error) {
    console.log(`  ${result.error}`);
    return;
  }
  const { parsed, route } = result;
  const tracks = Array.isArray(parsed.tracks) ? parsed.tracks : [];
  const keys = Object.keys(parsed).join(', ');
  console.log(`  route=${route} keys=[${keys}] tracks=${tracks.length}`);
  for (const t of tracks) {
    console.log(
      `    kind=${t.kind ?? '-'} label=${JSON.stringify(t.label ?? null)} ` +
        `default=${t.default ?? false} file=${String(t.file ?? '').slice(0, 110)}`,
    );
  }
}

const CASES = process.argv[2]
  ? [
      {
        label: `anilist ${process.argv[2]} ep ${process.argv[3]} ${process.argv[4] ?? 'sub'}`,
        url: `https://megaplay.buzz/stream/ani/${process.argv[2]}/${process.argv[3]}/${process.argv[4] ?? 'sub'}`,
      },
    ]
  : [
      // Tsuki ga Kirei (2017) ep 6 — the reported case.
      { label: 'Tsuki ga Kirei ep6 sub (ani/98202)', url: 'https://megaplay.buzz/stream/ani/98202/6/sub' },
      { label: 'Tsuki ga Kirei ep6 sub (mal/34822)', url: 'https://megaplay.buzz/stream/mal/34822/6/sub' },
      // Frieren (2023) ep 1 — a title known to resolve.
      { label: 'Frieren ep1 sub (ani/154587)', url: 'https://megaplay.buzz/stream/ani/154587/1/sub' },
      // One Piece ep 1100 — long-running, recent episode.
      { label: 'One Piece ep1100 sub (ani/21)', url: 'https://megaplay.buzz/stream/ani/21/1100/sub' },
    ];

for (const c of CASES) {
  describe(c.label, await answerFor(c.url).catch((e) => ({ error: String(e) })));
}
