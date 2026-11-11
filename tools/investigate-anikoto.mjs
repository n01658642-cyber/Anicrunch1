// Read-only, browser-free MegaPlay feasibility probe. Node 22+, no dependencies.
// Usage: node tools/investigate-anikoto.mjs [output-directory]
// Fetches metadata, playlists and at most 8 KiB from one segment per sample.
// Does not run downloaded JavaScript, send cookies, or contact telemetry endpoints.
import { createDecipheriv, createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const out = process.argv[2] || 'build/anikoto-investigation/probe';
await mkdir(out, { recursive: true });
const origin = 'https://megaplay.buzz';
const ua = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';
// Public client-side format, also implemented by AnivexaClient.kt.
const key = Buffer.alloc(32);
key.write('i?LMTAx0Q6,:}50U');
const iv = Buffer.from("W0;27ToaUpl_P%'c");
function decrypt(encoded) {
  const cipher = createDecipheriv('aes-256-cbc', key, iv);
  return Buffer.concat([cipher.update(Buffer.from(encoded, 'base64url')), cipher.final()]).toString('utf8');
}
function decodeUrl(url) {
  const token = /\/segment\/([A-Za-z0-9_-]+)/.exec(url)?.[1];
  return token ? decrypt(token) : url;
}
const calls = [];
async function get(url, { headers = {}, limit = 2 * 1024 * 1024, partial = false, ...rest } = {}) {
  const started = performance.now();
  const record = { url, ...rest };
  calls.push(record);
  try {
    const response = await fetch(url, {
      headers: { 'User-Agent': ua, ...headers }, signal: AbortSignal.timeout(12000),
    });
    Object.assign(record, {
      status: response.status, headersMs: Math.round(performance.now() - started),
      mime: response.headers.get('content-type'), cors: response.headers.get('access-control-allow-origin'),
      range: response.headers.get('content-range'),
    });
    const reader = response.body.getReader();
    const chunks = [];
    let length = 0;
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        if (length + value.length > limit && !partial) throw new Error(`response exceeded ${limit} bytes`);
        const bytes = value.subarray(0, Math.max(0, limit - length));
        chunks.push(bytes);
        length += bytes.length;
        if (length >= limit) break;
      }
    } finally { await reader.cancel(); }
    const bytes = Buffer.concat(chunks);
    record.bytesRead = bytes.length;
    return { record, bytes, text: bytes.toString('utf8') };
  } catch (error) {
    record.error = `${error.message}${error.cause ? ': ' + error.cause.message : ''}`;
    return { record, bytes: Buffer.alloc(0), text: '' };
  } finally { record.ms = Math.round(performance.now() - started); }
}
const ref = { Referer: origin + '/' };
const health = await get(origin + '/lib/check_domain.json', { headers: ref, stage: 'health' });
await writeFile(join(out, 'health.json'), health.text);
let routes;
async function resolve(sample, mode) {
  const start = performance.now();
  const firstCall = calls.length;
  const path = sample.path || `/stream/ani/${sample.id}/${sample.episode}/${sample.audio}`;
  const embed = await get(origin + path, { headers: { Referer: 'https://hianimes.re/' }, stage: 'embed' });
  const fileId = /data-id=["']([^"']+)["']/.exec(embed.text)?.[1];
  const row = { ...sample, mode, fileId, callStart: firstCall };
  if (!fileId) return { ...row, error: 'no file ID', resolveMs: Math.round(performance.now() - start) };
  if (!routes) {
    const scriptPath = [...embed.text.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["']/gi)]
      .map(m => new URL(m[1], origin).href).find(u => u.startsWith(origin + '/') && u.includes('newclient'));
    if (!scriptPath) return { ...row, error: 'no client script' };
    const script = await get(scriptPath, { headers: ref, stage: 'route-discovery' });
    routes = [...new Set([...script.text.matchAll(/["'](stream\/getSources[\w/-]*)/g)].map(m => m[1]))]
      .sort((a, b) => b.length - a.length || a.localeCompare(b));
    await writeFile(join(out, 'newclient.js'), script.text);
    row.scriptSha256 = createHash('sha256').update(script.bytes).digest('hex');
  }
  for (const route of routes) {
    const answer = await get(`${origin}/${route}?id=${fileId}&id=${fileId}`, {
      headers: { ...ref, 'X-Requested-With': 'XMLHttpRequest' }, stage: 'sources',
    });
    try {
      const data = JSON.parse(answer.text);
      const decoded = data.sources || (data.enc ? JSON.parse(decrypt(data.enc)) : null);
      row.master = decoded?.file || decoded?.[0]?.file;
      row.tracks = data.tracks || [];
      row.intro = data.intro;
      row.outro = data.outro;
      row.sourceEncrypted = Boolean(data.enc);
      if (row.master) break;
    } catch (error) { row.decodeError = error.message; }
  }
  row.directResolveMs = Math.round(performance.now() - start);
  if (!row.master) return { ...row, error: 'no source URL' };
  // Current Android implementation probes this historical sibling before returning the URL.
  const legacyUrl = row.master.replace(/\/master\.m3u8(?=[?#]|$)/, '/index-f1-v1-a1.m3u8');
  const legacy = await get(legacyUrl, { headers: ref, stage: 'current-app-rendition-probe' });
  row.legacyStatus = legacy.record.status;
  row.legacyMs = legacy.record.ms;
  row.appSelectedUrl = legacy.text.trimStart().startsWith('#EXTM3U') ? legacyUrl : row.master;
  row.appResolveMs = Math.round(performance.now() - start);
  const manifest = await get(row.appSelectedUrl, { headers: ref, stage: 'master' });
  if (!manifest.text.startsWith('#EXTM3U')) return { ...row, error: 'not an HLS playlist' };
  const variants = [...manifest.text.matchAll(/#EXT-X-STREAM-INF:([^\r\n]+)\r?\n([^\r\n]+)/g)]
    .map(m => ({ attributes: m[1], url: new URL(m[2], row.appSelectedUrl).href }));
  row.variants = variants;
  const media = variants.length ? await get(variants[0].url, { headers: ref, stage: 'media' }) : manifest;
  const mediaUrl = variants[0]?.url || row.appSelectedUrl;
  row.mediaStatus = media.record.status;
  row.hasMediaEncryption = media.text.includes('#EXT-X-KEY');
  row.hasEncodedSegmentUrls = media.text.includes('/segment/');
  row.segmentCount = (media.text.match(/#EXTINF:/g) || []).length;
  const firstSegment = media.text.split(/\r?\n/).find(l => l.trim() && !l.startsWith('#'));
  if (!firstSegment) return { ...row, error: 'no segment' };
  row.segmentUrl = decodeUrl(new URL(firstSegment, mediaUrl).href);
  const segment = await get(row.segmentUrl, {
    headers: { ...ref, Range: 'bytes=0-8191' }, stage: 'segment-prefix', limit: 8192, partial: true,
  });
  row.segmentStatus = segment.record.status;
  row.tsSyncOffset = -1;
  for (let i = 0; i < Math.min(4096, segment.bytes.length - 188 * 4); i++) {
    if ([0, 1, 2, 3, 4].every(j => segment.bytes[i + 188 * j] === 0x47)) { row.tsSyncOffset = i; break; }
  }
  row.toSegmentPrefixMs = Math.round(performance.now() - start);
  row.success = segment.record.status >= 200 && segment.record.status < 300 && row.tsSyncOffset >= 0;
  if (!row.success) row.error = 'segment request did not return a recognizable transport stream';
  row.callEnd = calls.length;
  await writeFile(join(out, `${sample.slug}-${mode}-master.m3u8`), manifest.text);
  await writeFile(join(out, `${sample.slug}-${mode}-media.m3u8`), media.text);
  return row;
}
const samples = [
  { slug: 'liar-game-25-sub', id: 197754, episode: 25, audio: 'sub' },
  { slug: 'liar-game-25-dub', id: 197754, episode: 25, audio: 'dub' },
  { slug: 'frieren-1-sub', id: 154587, episode: 1, audio: 'sub' },
  { slug: 'frieren-1-dub', id: 154587, episode: 1, audio: 'dub' },
  { slug: 'bleach-298-sub', id: 269, episode: 298, audio: 'sub' },
  { slug: 'bleach-298-dub', id: 269, episode: 298, audio: 'dub' },
  { slug: 'one-piece-1-sub', id: 21, episode: 1, audio: 'sub' },
  { slug: 'one-piece-1-dub', id: 21, episode: 1, audio: 'dub' },
  { slug: 'solo-leveling-1-sub', id: 151807, episode: 1, audio: 'sub' },
  { slug: 'solo-leveling-1-dub', id: 151807, episode: 1, audio: 'dub' },
];
const results = [];
for (const [index, sample] of samples.entries()) {
  const row = await resolve(sample, index === 0 ? 'cold-route-cache' : 'warm-route-cache');
  results.push(row);
  console.log(JSON.stringify({ sample: sample.slug, fileId: row.fileId, directMs: row.directResolveMs,
    appMs: row.appResolveMs, legacy: row.legacyStatus, ladder: row.variants?.map(v => /RESOLUTION=([^,]+)/.exec(v.attributes)?.[1]),
    segment: row.segmentStatus, tsOffset: row.tsSyncOffset, encoded: row.hasEncodedSegmentUrls,
    firstBytesMs: row.toSegmentPrefixMs, error: row.error }));
}
// Same-title repeats separate normal warm latency from a single lucky response.
for (let i = 0; i < 3; i++) results.push(await resolve(samples[0], `repeat-${i + 1}`));
await writeFile(join(out, 'results.json'), JSON.stringify({ at: new Date().toISOString(), routes, results, calls }, null, 2));
console.log(`Saved ${results.length} samples and ${calls.length} bounded GETs to ${out}/results.json`);
