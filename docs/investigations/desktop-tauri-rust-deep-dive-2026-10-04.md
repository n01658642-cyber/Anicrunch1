# Desktop app on Tauri + Rust: deep dive

2026-10-04. Follow-up to [desktop-port-feasibility-2026-09-24.md](desktop-port-feasibility-2026-09-24.md),
which rejected Tauri mainly because it reuses no Kotlin. This study drops that constraint: the user
is willing to build the desktop app from scratch, and asks what the best-engineered Tauri v2 + Rust
version looks like, including newer and experimental libraries that fit together.

**Short answer: yes, it is buildable, and it is a better desktop app than the Compose port would be.**
Its installer is about a third of the size. It has a real HTML/CSS interface over mpv, Chromium-grade
web tooling on Windows, and one Rust core that can also run inside the Android app. The September
objections to Tauri are mostly gone, with one exception, and that one is a strategic decision rather
than a technical one:

> **If Android stays on Kotlin, every provider fix is written twice, forever.** The only way to
> make Tauri the right call long-term is to make the Rust core the single source of truth and
> have Android consume it through UniFFI, provider by provider. §6 is the heart of this document.

---

## 0. What changed since the September study

| September objection | Status now (verified 2026-10-04) |
| --- | --- |
| "Reuses none of the Kotlin; two provider implementations forever" | Still true *unless* Android adopts a Rust core via **UniFFI**, which Firefox, Matrix (Element X) and Bitwarden all ship in production. See §6. |
| "Playback inside the webview is weaker (no Referer, no DASH, HEVC, ASS)" | **We don't play inside the webview.** libmpv draws into a native surface *under* a transparent webview, with HTML controls on top. Several Tauri 2 apps ship exactly this: [UniPlayer](https://github.com/xiaolai/uniplayer), [LibreVP](https://github.com/libreflow/LibreVP), and [tauri-plugin-libmpv](https://github.com/nini22p/tauri-plugin-libmpv), which is "fully tested" on Windows. |
| "Every gated stream needs a Rust proxy re-implementing the byte quirks" | True, but small. `LocalStreamProxy` is 421 lines, and the Kwik/Flixcloud/UniqueStream data sources are a few hundred more. mpv takes headers natively (`http-header-fields`), so only byte-rewriting streams need the proxy. |
| "Webview engine differs per OS" | Still true for Tauri 2 (WebView2 = Chromium, WKWebView = WebKit). **Tauri 3 (alpha since 2026-09-13) adds a first-class CEF/Chromium runtime**, selected per app at build time. That gives an upgrade path to identical Chromium on both OSes when it stabilises. |
| "Cloudflare clearance is bound to the engine" | Rust now has **[wreq](https://crates.io/crates/wreq)**: BoringSSL with byte-accurate Chrome/Edge/Safari TLS and HTTP/2 fingerprints. It is the first realistic chance to replay a `cf_clearance` from code. See §5. |

---

## 1. Target architecture

```
┌──────────────────────── Tauri 2 shell (src-tauri) ─────────────────────────┐
│  windows · tray · menus · deep links · updater · single-instance · OS glue │
│  ┌────────────── Webview (UI) ──────────────┐   ┌── native video layer ──┐ │
│  │ React/TS app, transparent over video     │   │ libmpv (wid / render)  │ │
│  │ typed IPC (tauri-specta) ↔ commands      │   │ under the webview      │ │
│  └──────────────────────────────────────────┘   └────────────────────────┘ │
│  ┌─ helper webviews (hidden / child) ─┐  embeds · CF challenge · resolvers  │
│  │ adblock-rust · popunder lock · JS  │  cookies_for_url → wreq jar         │
│  └────────────────────────────────────┘                                     │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │ plain Rust API (no Tauri types)
┌──────────────────────────────▼──── anilili-core (Cargo workspace) ──────────┐
│ net      wreq (impersonation) · hickory DoH · cookie jar · governor limits  │
│ catalog  Miruro /api/v1 · AniList (cynic GraphQL) · MAL · AniZip · AniSkip  │
│ provider Provider trait · scraper/lol_html · regex · rquickjs · RustCrypto  │
│ stream   axum localhost proxy · m3u8-rs rewrite · byte transforms           │
│ store    SQLite (rusqlite + migrations) · settings · library · backups      │
│ sync     AniList/MAL progress · pairing protocol · Nostr (nostr-sdk)        │
│ media    downloads (tokio) · ffmpeg remux · Discord IPC · Cast (oxicast)    │
│ diag     tracing · error-code catalogue · minidumps (crash-handler)         │
└───────────────┬─────────────────────────────────┬───────────────────────────┘
                │ desktop: linked in              │ Android: UniFFI → Kotlin (§6)
```

**Rule that makes the whole plan work:** `anilili-core` never imports `tauri`. It is a library with
an async Rust API. Tauri is one consumer, the UniFFI Kotlin bindings are another, and a CLI
(`anilili-cli`, for probing providers from a terminal) is a third. Each consumer costs almost nothing
once the core exists.

---

## 2. Tauri itself: version, runtime, and the parts we'd lean on

**Version.** Build on **Tauri 2.x stable** (2.11.x as of May 2026). Tauri 3 is at `3.0.0-alpha.4`
(2026-10-01), and its plugin packages lag behind its alphas. The main v3 change is choosing the
runtime at build time (`.runtime(tauri_runtime_wry::Wry)` or `tauri_runtime_cef::Cef`), plus moving
runtime-specific APIs into extension traits. That is a contained migration. Keep every
runtime-specific call (WebView2 COM, WKWebView objc) in one `platform/` module so the v3 move touches
one place.

**Wry or CEF?**

| | Wry (system webview) | CEF (Tauri 3 alpha) |
| --- | --- | --- |
| Windows engine | WebView2 = Chromium, evergreen, preinstalled on 10/11 | Bundled Chromium 152 |
| macOS engine | WKWebView = WebKit/Safari | Bundled Chromium |
| Install size | +0 MB | +150–200 MB |
| Request interception | WebView2 COM: yes. WKWebView: content rules only | CDP everywhere |
| Maturity | Stable | Alpha. Open bugs are mostly Linux (idle CPU spin, no hardware WebGPU, startup flash) |

**Pick Wry now; revisit CEF when Tauri 3 is stable.** On Windows we already get Chromium for free.
On macOS, WebKit is fine for the UI and for Cloudflare Turnstile (it is Safari). The only real cost
of WebKit is that interception is weaker (§5).

**Tauri features we'd rely on, and why each matters here:**

- **Raw IPC payloads and `Channel<T>`**: stream download progress, log tails and player telemetry
  to the UI without JSON-encoding megabytes.
- **Async custom URI schemes** (`register_asynchronous_uri_scheme_protocol`): an `img://` scheme
  serves posters and stills from a Rust disk cache (replaces Coil, works offline). Same idea for
  `sub://` (subtitle files) and `thumb://` (seek previews).
- **`Webview::cookies_for_url` / `set_cookie`** (since 2.4): read HttpOnly cookies, including
  `cf_clearance`, out of a helper webview and seed them back. Call these from async commands only;
  synchronous handlers can deadlock on Windows.
- **`with_webview`** gives the raw `ICoreWebView2Controller` (via `webview2-com`) and
  `WKWebView` (via `objc2-web-kit`). That is where request interception, frame scripts, CDP and
  content blockers live.
- **Multiwebview** (`unstable` feature) for an embed player webview *inside* the main window.
  Still flagged unstable in 2.x, with known resize/positioning bugs. The fallback is a borderless
  owned child window that tracks the player rectangle.
- **Official plugins**: `single-instance`, `deep-link` (`anilili://` links shared from the phone),
  `window-state`, `updater`, `notification`, `global-shortcut`, `opener` (system-browser login),
  `log`.

### Alternatives to Tauri in the Rust world (checked, rejected)

| Framework | Why not |
| --- | --- |
| **Dioxus 0.7** desktop | Uses wry too, i.e. the same webview, but you write the UI in Rust RSX. That's a smaller ecosystem than React/TS for a big consumer UI, and no gain. |
| **Dioxus Native / Blitz** (wgpu HTML renderer) | Exciting: GPU-rendered, no webview. But it is "experimental/beta", has no JS (embeds and resolvers still need a webview), and has no video-surface story. Watch for 0.8. |
| **GPUI**, Slint, Iced, egui | Native GPU UIs with no browser engine. We'd still need a webview for embeds and Cloudflare, and we'd lose CSS/animation tooling. Video compositing is DIY in each. |
| Servo embedding | Too young for streaming sites and Cloudflare. |
| Electron | Not Rust; ships Chromium anyway. This is roughly what Tauri 3 + CEF becomes, minus Node. |

---

## 3. Video: mpv under a transparent webview

This is the make-or-break subsystem, so here it is in full detail.

### 3a. Why mpv, not `<video>`

| Need | `<video>` + hls.js/shaka in the webview | libmpv |
| --- | --- | --- |
| Referer/Origin/custom headers | Forbidden from page JS, so everything goes through the proxy | `http-header-fields`, `referrer`, `user-agent` per file |
| HLS | hls.js. Chromium ≥142 has native HLS, but hls.js advises against relying on it | ffmpeg demuxer |
| DASH (AnimeOnsen) | shaka/dash.js | native |
| HEVC / 10-bit | WebView2 needs the paid HEVC extension and hardware; WebKit varies | ffmpeg + hwdec (d3d11va / videotoolbox) |
| ASS/SSA | JASSUB (libass-wasm) | libass, best in class |
| Subtitle delay, audio delay, speed with pitch correction | DIY | `sub-delay`, `audio-delay`, `speed` + scaletempo2 |
| HDR | Limited | `vo=gpu-next` (libplacebo) |

`<video>` stays useful for one thing: hover previews and trailers in the browse UI, where no
headers are needed.

### 3b. Compositing per OS

**Windows: `--wid` child HWND under a transparent WebView2.** This is proven: tauri-plugin-libmpv
(Windows "fully tested"), LibreVP, UniPlayer and a PR in `lpbborges/grid` all do it. We'd write our
own thin binding rather than take the plugin: the `libmpv2` crate or bindgen against `client.h`,
about 500 lines, and we own the event loop. Recipe and known traps:

1. Tauri window `transparent: true`; `html, body { background: transparent }`.
2. Set `WEBVIEW2_DEFAULT_BACKGROUND_COLOR=0x00000000` **before** the webview is created. The API
   setter still flashes white ([WebView2 known issue](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2controller.defaultbackgroundcolor)).
3. Create an mpv host `WS_CHILD` HWND with `WS_CLIPSIBLINGS`, put it at `HWND_BOTTOM` below the
   WebView2 child (the getter is `WebViewExtWindows::hwnd`), give the parent `WS_CLIPCHILDREN`, and
   return non-zero from `WM_ERASEBKGND`.
4. Resize both in one `BeginDeferWindowPos` batch. WebView2 has a known "stale frame after resize"
   bug in exactly this layout ([#2419](https://github.com/MicrosoftEdge/WebView2Feedback/issues/2419)),
   and Tauri has a related repaint bug ([#12800](https://github.com/tauri-apps/tauri/issues/12800)).
   This is the spike's main thing to test.
5. Input: the webview owns all input (it's on top). Controls send commands over IPC; mpv never
   needs focus.

*Fallback (only if step 4 fails):* the render API through **ANGLE** (GLES → D3D11), sharing the
texture into a DirectComposition visual under a composition-hosted WebView2. media-kit (Flutter)
ships this approach. It costs weeks, so don't start here.

**macOS: render API in a layer under WKWebView.** Stock libmpv **does not implement `--wid` on
macOS**. UniPlayer carries a +162/−14 patch across four files to add it. Two routes:

- **(a) Render API (`MPV_RENDER_API_TYPE_OPENGL`)** into a `CAOpenGLLayer`-backed NSView added
  below the WKWebView (`drawsBackground = NO`). No libmpv patch is needed. OpenGL is deprecated on
  macOS but still works, and the Lokii project took this route for macOS.
- **(b) Patched libmpv** with `--wid` + `vo=gpu-next` over MoltenVK. This is UniPlayer's route; it
  gives HDR/Dolby Vision, but we'd maintain the patch.

Start with (a). Move to (b) only if HDR matters. Upstream is adding a `gpu-next` render backend
(OpenGL first, [mpv#16818](https://github.com/mpv-player/mpv/pull/16818)), which would bring
libplacebo to route (a) without a patch.

### 3c. The player core in Rust

- One `PlayerEngine` actor (a tokio task owning the `mpv_handle`). It turns `mpv_wait_event`
  into typed events and observes `time-pos`, `duration`, `pause`, `track-list`, `demuxer-cache-state`,
  `paused-for-cache` and `video-params`.
- UI state is a mirror of what mpv reports, pushed over a `Channel<PlayerEvent>` at about 10 Hz,
  plus immediate pushes on discrete changes. The UI never polls.
- Skip times (AniSkip / catalogue `skip_times`) become mpv chapters, so they show on the seek
  bar. Auto-skip is an observer on `time-pos`.
- Seek previews: a second, lightweight ffmpeg session (UniPlayer's "storyboard pass") for local and
  downloaded files. For remote HLS, only show them when the provider gives sprite VTTs.
- Mini player: shrink the main window to an always-on-top compact mode. A second window would
  need a second mpv instance.
- Media keys / Now Playing: `souvlaki` is effectively unmaintained (pinned to `windows` 0.44).
  Write ~300 lines against `windows` (SMTC) and `objc2-media-player` (MPNowPlayingInfoCenter)
  directly.

### 3d. Licensing, which needs action

The common prebuilt `libmpv-2.dll` builds include GPL parts. A closed-source app must ship an
**LGPL build** (`-Dgpl=false`, no x264/x265, no GPL filters), as UniPlayer does. That means we
own an mpv + ffmpeg build pipeline per OS/arch: about 20 minutes per build, scripted once with
`mpv-winbuild-cmake` on Windows and meson on macOS. Same LGPL story for the bundled `ffmpeg`
used for remuxing.

---

## 4. The stream proxy and downloads

**`stream` crate:** axum on `127.0.0.1:<random>`, with a per-session token in the path.

- **Playlist rewrite**: `m3u8-rs`. Absolute-ise URLs, route segments back through the proxy only
  when a byte transform or a header the CDN checks per segment is needed. Otherwise let mpv fetch
  directly with headers (lower latency, no copy).
- **Byte transforms**: a `trait SegmentTransform { fn apply(&self, seg: Bytes, ctx) -> Bytes }`
  with ports of Kwik (AES IV = segment number), Flixcloud XOR, UniqueStream key and PNG-wrapped
  TS stripping. These are pure functions, so property tests (`proptest`) can check them against
  fixtures captured from the Kotlin implementation.
- **Upstream client**: the same `wreq` client and cookie jar as the providers, so impersonation
  and clearance carry over to segment fetches.
- mpv's `stream_cb` API (custom `anilili://` protocol in-process) is tempting, but ffmpeg's HLS
  demuxer opens segments through its own IO, not through the callback. So it doesn't remove the
  proxy for HLS. Skip it.

**Downloads/export**: bounded-concurrency segment fetch (tokio + `governor` per host), resumable
via a SQLite job table, decrypted with the same transforms, then remuxed with the bundled
**LGPL ffmpeg** `-c copy` to MP4 (`ffmpeg-sidecar` to drive it). This is simpler than Android: no
Media3 Transformer, no avcC/PPS repair class of bugs, no OOM from parallel workers.

---

## 5. Browser-engine work, and the Cloudflare experiment

Same jobs as on Android, with better tooling on Windows.

| Job | Windows (WebView2) | macOS (WKWebView) |
| --- | --- | --- |
| Embed players | Load the embed URL as the **top-level document** of a child webview (so init scripts apply, as on Android). `NewWindowRequested` → deny (popunders). Navigation handler locks the origin. | Same, via wry navigation/new-window handlers. |
| Ad blocking | **Brave's `adblock-rust`** behind `AddWebResourceRequestedFilter("*")` + `WebResourceRequested` (~2.6 µs per check, used by several Tauri browsers), plus cosmetic CSS/scriptlets injected per document. | `adblock-rust`'s content-blocking conversion → `WKContentRuleList` (the mechanism Brave iOS uses). |
| Resolver bridges (Pahe/kiwi, Hanime, Flixcloud, AllAnime captcha) | Hidden webview. Read network responses directly with `WebResourceResponseReceived` or **CDP** (`CallDevToolsProtocolMethod`, `Network.*`) instead of injected fetch/XHR hooks. | Injected JS hooks, as on Android today. |
| Cloudflare challenge | Visible dialog-webview, then `cookies_for_url` → wreq jar | Same |
| Login (AniList/MAL) | **System browser + loopback listener**. The redirect is already `http://localhost`, so no embedded login and no Google "insecure browser" block. | Same |

### The `wreq` experiment, which matters for Android too

Several memory notes end at "clearance is bound to the engine that solved it" (AnimePahe, the old
Gecko pipe). Cloudflare ties `cf_clearance` to the TLS (JA3/JA4) and HTTP/2 fingerprint and to the
User-Agent. OkHttp can't match a browser's fingerprint. **wreq can**: it ships BoringSSL (Chrome's
TLS stack) and 60+ browser profiles with exact HTTP/2 SETTINGS order.

The test:
1. Solve the challenge in WebView2 (Chromium N). Read `navigator.userAgent` and the cookies.
2. Replay with `wreq` using `Emulation::Edge<N>` (version-matched) and the same UA + cookies.
3. If AnimePahe serves the page, **earn-in-browser / replay-in-code works**. Then the AnimePahe
   clearance budget (~6 s of interstitial per lookup) drops to one solve per clearance lifetime.
4. On macOS, solve in WKWebView and replay with a Safari profile.

If it works, the same crate solves the same problem on Android via the shared core (§6). That
alone could justify the Rust core. **Unverified**: it's the first thing the spike measures.

Also in this crate: `hickory-resolver` for DoH (same ISP-block bypass as `AppDns`/`AutoDns`), wired
in as wreq's DNS resolver, and `governor` rate limiters (AniList's degraded 30/min, the 429 =
1-minute timeout).

---

## 6. One core for desktop *and* Android: the strategic part

Three options, in increasing ambition:

**(A) Desktop-only Rust; Android stays Kotlin.** Fastest to a desktop app. Cost: every provider
break (weekly) is fixed twice in two languages, and the two cores drift. Over a year this costs
more than building the desktop app. **Not recommended.**

**(B) Rust core consumed by Android through UniFFI.** Proven at scale: Firefox (UniFFI's origin),
Element X (matrix-rust-sdk), Bitwarden. UniFFI 0.32 (June 2026) generates Kotlin from
`#[uniffi::export]` proc-macros. Android loads per-ABI `.so` files built with `cargo-ndk`, through
JNA. Migrate **one provider at a time behind the existing Kotlin provider interface**: the Kotlin
class becomes a 30-line adapter over the Rust call, and the app never notices.

What Android gets beyond "one fix, both platforms", from problems this project already measured:
- **Regex**: the `regex` crate is linear-time and doesn't copy input per match. The memory notes
  [android-regex-is-icu] and [tv-catalogue-parse-cost] record ICU regex being 30–60× slower on TV,
  which a Rust parser sidesteps entirely.
- **wreq on Android**: the Cloudflare experiment above, for phones and TVs.
- **Memory**: parsing in native memory outside the Java heap helps the low-RAM TVs.

Costs: APK size (BoringSSL + core ≈ 6–10 MB per ABI before compression; ship ABI-split APKs or
accept a bigger universal APK). NDK builds in the Gradle pipeline (a `cargo ndk` task before
`assemble`). A JNA dependency. A Kotlin wrapper layer that turns callbacks into Flows and frees
FFI objects, as Element X does. minSdk 22 / Fire OS 5 is fine, because NDK supports API 21.

**(C) Providers as signed, hot-updatable WebAssembly modules.** Today a provider fix means a full
release: version bump, both flavors, mirrors, Nostr manifest. With (C), each provider is a Rust
crate against a small `provider-sdk` host interface (`fetch`, `select`, `js_eval`, `crypto`,
`log`), compiled **two ways**:
- statically linked into the core, as the baseline that always ships, and
- as a `.wasm` module, published over the existing **signed Nostr/CDN channel**. A newer signed
  module overrides the built-in version without an app update.

Desktop runs modules in **wasmtime**. Android runs them in **Chicory**: pure JVM, no JNI,
build-time AOT to bytecode, Android-compatible but single-threaded and limited by Android's 1 MB
thread stack. Its Bytecode Alliance fork **Endive** (May 2026) adds a Cranelift backend. Neither
supports the component model yet, so use a core-wasm ABI (Extism's PDK has a Chicory host SDK).
This is the "experimental synergy" option: the industry is converging on wasm plugins, and this
app's defining pain is provider churn.

**Recommendation: build (B) from day one and design for (C).** Concretely, the `provider-sdk` host
trait is the only thing providers may call. Native linking implements it directly, and a wasm
host can implement it later without touching a provider. Start shipping (C) only after the core has
proven itself on both platforms.

### Keeping two implementations honest during the migration

While Kotlin providers are still live, build a **parity harness**. It runs the Kotlin provider (JVM
test runtime) and the Rust provider (via the UniFFI bindings, loaded on the desktop JVM) against
the same live titles and diffs search hits, episode lists and resolved stream sets. Extend the
existing catalogue-measurement and provider-benchmark tooling for this. A Rust provider replaces
the Kotlin one only when the diff is clean for a week.

The **test fixtures port as-is**. The 30k lines of Kotlin tests are mostly recorded HTML/JSON plus
assertions; the fixtures become `insta` snapshot inputs and only the assertions are rewritten.

---

## 7. The frontend (UI in the webview)

| Concern | Pick | Why |
| --- | --- | --- |
| Framework | **React 19 + React Compiler** | Biggest ecosystem, best AI-assisted accuracy, and the compiler removes memo boilerplate. Svelte 5 or Solid would also be fine; UniPlayer uses Svelte. The framework matters far less than the IPC contract. |
| Routing / data | **TanStack Router + Query + Virtual** | Typed routes. Query's cache *is* the client state, keyed by IPC commands. Rust pushes invalidations as events. Virtualised poster grids. |
| Typed IPC | **tauri-specta 2.0.0-rc.25** (pin exact versions; it has never left RC) or `tauri-typed-ipc` | Rust types → `bindings.ts`, and the build fails if they drift. Errors are a typed enum carrying the **stable error codes** (`EXP-04` style). |
| Styling / motion | Tailwind v4, Motion, View Transitions API | M3-flavoured tokens generated from the same Appearance seed colour as Android (`material-color-utilities` has a TS port). |
| Primitives | Radix / Ark UI + `cmdk` | Accessible menus and dialogs, a command palette (Ctrl+K: search titles, jump to settings, switch source). |
| Desktop-native UX | Keyboard-first, hover previews, right-click menus, drag a title onto the dock/taskbar, multi-window detail pages | This is where desktop beats the phone layouts. |
| Tests | Vitest (logic), Playwright against `vite dev` with `@tauri-apps/api/mocks`, `tauri-driver` WebDriver on Windows | macOS has no WKWebView WebDriver; cover it with Rust-side and mocked-IPC tests. |

---

## 8. Everything else, mapped

| Feature | Crate / approach | Notes |
| --- | --- | --- |
| Persistence | `rusqlite` + `refinery` migrations; WAL | One DB: library, progress, caches, download jobs, catalog cache (the 2 h degraded-catalog rule ports). |
| Settings | serde struct ↔ SQLite row, change events | Avoid the Android "re-applies on every write" trap: diff before applying. |
| Backup/restore | **Same JSON format as Android** | Users can move a library phone → PC → phone. |
| AniList / MAL | `cynic` (typed GraphQL from schema), `oauth2` crate with PKCE | Loopback redirect; tokens in the OS keychain (`keyring` crate: Credential Manager / Keychain). |
| Update channel | `nostr-sdk` 0.44 verifies the **same pubkey** event; payload points to assets on the HF mirror; `tauri-plugin-updater` verifies minisign and installs | Reuses existing infrastructure. Desktop keeps a separate minisign key in `anilili-private/`, alongside the existing keys. |
| Announcements | `nostr-sdk` subscription | Same channel as Android. |
| Discord presence | `discord-rich-presence` over local IPC | No Social SDK gate on desktop. |
| Chromecast | **`oxicast`** (async CASTV2, mDNS discovery, built-in file server) | Desktop gains Cast, which the September study marked ❌. The receiver still fetches URLs itself, so the same edge-proxy constraint applies. |
| TV pairing | Port the `PairingTransport` socket protocol | A PC becomes a sender to the Android TV. |
| External player | Spawn `mpv`/`vlc` with headers on the CLI | Trivial. |
| Notifications / reminders | `tauri-plugin-notification` + optional login item | While running; no OS scheduler. |
| Diagnostics | `tracing` + rolling files; **`crash-handler` + `minidumper`** (out-of-process minidumps for crashes in mpv/WebView2/BoringSSL); error-code catalogue as a Rust enum | Same "send diagnostics bundle" UX. |
| Recommendations | Port `TasteEngine` (pure maths) and read `neighbours-v1.tsv.gz` | Nice fit for Rust. |

---

## 9. Build, sign, ship: the Mac problem

- **Windows**: NSIS installer via the Tauri bundler, WebView2 evergreen bootstrapper. Unsigned
  means a SmartScreen warning. Paid options: OV certificate, or Azure Artifact Signing if eligible.
- **macOS**: building a Tauri `.app` needs Xcode's SDK, so **cross-building from Windows is not
  practical**. AGENTS.md rules out GitHub-hosted CI (code must not leave the machine). So: **rent
  an Apple-silicon Mac you control** (hourly cloud Mac) or buy a used Mac mini. Building on it
  gives you Intel + arm64 (universal binary) and real testing, which matters anyway for the
  render-API compositing.
- **Signing/notarisation**: Apple Developer ID ($99/yr). `rcodesign` can sign and notarise
  **from Windows** using the App Store Connect API, so release steps can stay on this machine even
  if builds happen on the Mac.
- **Sizes** (estimates): app ≈ 10–15 MB + LGPL libmpv/ffmpeg ≈ 30–45 MB → **~45–60 MB installer**,
  against ~100–150 MB for the Compose plan. Idle memory is *not* tiny: WebView2 is multi-process
  Chromium (~150–250 MB total across browser/GPU/renderer processes).
- **Toolchain gotchas**: BoringSSL (wreq) on Windows needs CMake + NASM + clang. rquickjs marks
  Windows MSVC as experimental (test early, or use `boa_engine` for the rare JS unpacking: slower,
  but pure Rust). Pin everything in `rust-toolchain.toml`.

---

## 10. Risk register

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| WebView2 stale-frame/repaint glitches over the mpv HWND | Medium | Spike measures it first; DComp/ANGLE fallback is known but costly. |
| macOS compositing (no `--wid`) | Medium | Render-API route needs no patch; patched libmpv as plan B. |
| Embed players behave differently in WebKit (macOS) | Medium | Tauri 3 CEF runtime later; or per-OS embed allow-list. |
| wreq clearance replay doesn't work | Medium | Then Cloudflare stays as on Android (solve in webview, fetch in webview). Nothing lost. |
| Porting provider code while it keeps changing | High | Port in waves behind the parity harness; freeze each provider's Kotlin while it ports. |
| Tauri 3 churn | Low | Build on 2.x; runtime-specific code isolated in `platform/`. |
| tauri-specta stays RC | Low | Pin exact versions; generated file is committed and diff-checked. |
| LGPL compliance | Low with care | Own mpv/ffmpeg builds with `-Dgpl=false`; generated third-party notices. |
| One developer's time | High | Phase gates below; the core is useful to Android even if the desktop UI slips. |

---

## 11. Plan with go/no-go gates

**Phase 0: setup (≈1 week).** Decisions: Apple Developer ID, rented Mac vs owned, Windows signing.
Script LGPL libmpv + ffmpeg builds for win-x64 and mac-universal.

**Phase 1: spike (≈2–3 weeks). The gate.** A bare Tauri 2 window that must:
1. play a header-gated HLS provider and AnimeOnsen DASH through mpv **under HTML controls, on
   Windows and on the Mac**, including resize, fullscreen, and an ASS subtitle;
2. serve one Kwik stream through the Rust proxy (byte transform);
3. resolve one Pahe episode in a hidden webview, then run the **wreq clearance-replay experiment**;
4. call one Rust provider **from a debug Android build via UniFFI** (proves §6 with the Fire OS
   minSdk);
5. block popunders/ads in one embed via adblock-rust.

If (1) fails on an OS, stop and re-plan for that OS before writing UI.

**Phase 2: core (≈6–8 weeks).** net, Miruro catalog + AniList/MAL, store, stream proxy, the
~8 most-used providers, parity harness, CLI. Android starts swapping in Rust providers one at a
time.

**Phase 3: desktop beta (≈6–10 weeks).** UI (home, search, detail, watch, library, settings,
schedule), login, sync, downloads/export, Discord, Cast, updater over Nostr, diagnostics,
installers.

**Phase 4: convergence.** Remaining providers in Rust; Android's Kotlin providers deleted as they
reach parity; the wasm OTA provider channel (§6C) once the core is stable.

Rough total to a desktop beta: **3.5–5 months** for one developer with AI help, against ~2.5–4
for the Compose port. What the extra time buys: a smaller, faster, more native-feeling desktop app;
a shared core that also makes Android faster and potentially Cloudflare-proof; and a path to
release-free provider fixes.

---

## 12. Decision update (same day): desktop is a separate product

The user ruled that **Anilili Desktop is a separate entity**. Writing providers twice (Kotlin for
Android, Rust for desktop) is accepted, and Tauri 3 is acceptable even though it is new. What
changes as a result:

- **§6 (B) UniFFI is dropped.** The Rust core is desktop-only. There's no Android `.so`, no JNA,
  and no parity harness across runtimes.
- **§6 (C) wasm providers move *up*, from "later" to day one.** With two codebases, the desktop's
  ongoing cost is porting provider fixes. Hot-updatable providers mean a desktop provider fix
  ships without a desktop release. Now that there's no Android/Chicory constraint, use the full
  **component model**: WIT-defined `provider` world, **wasmtime 46** (WASI 0.3, async on by
  default), with fuel/epoch limits for runaway providers. Providers never touch TLS: `http` is a
  host import backed by wreq. Keep a cargo feature that links a provider natively, as an escape
  hatch while debugging.
- **Tauri 3 alpha with the wry runtime, not CEF.** `tauri-runtime-cef` creates a *windowed* CEF
  child, and CEF only documents transparent painting for off-screen (windowless) rendering
  ([delinoio/oss#1240](https://github.com/delinoio/oss/issues/1240)). So mpv-under-the-webview is
  established on wry (WebView2/WKWebView) and **not** on Tauri's CEF runtime. Pin exact alpha
  versions and keep runtime-specific code in `platform/`. Revisit CEF if it gains OSR or
  transparent compositing.
- **Compatible at the edges, independent inside.** The desktop shares *contracts*, not code, with
  Android: backup JSON format, pairing protocol, `anilili://` deep links, AniList/MAL as the sync
  source of truth, and a separate error-code namespace.
- **Keeping two implementations in step cheaply:**
  - a **shared, language-neutral fixture corpus** (recorded provider HTML/JSON plus expected
    results) that both test suites read;
  - a **port ledger** script that lists Android commits touching `data/remote/` since the last
    desktop sync.
- **Separate repo** (`anilili-desktop`, local-only like this one) with its own AGENTS.md, keys
  (minisign updater key, its own Nostr manifest `d` tag) and backups in `anilili-private/`.

The Phase 1 spike's gate 4 (UniFFI on Android) becomes: **one provider built as a wasm component,
loaded by the host, then replaced by a newer signed module at runtime.**

---

## 13. Seanime: what to learn from it (read 2026-10-04)

[5rahim/seanime](https://github.com/5rahim/seanime): 4.2k stars, active (last push 2026-09-20), a
one-person project. It's a Go backend (`internal/`, ~50 packages) with a React web UI, and a
desktop client called **Denshi**: Electron 42 bundling the Go server.

**Licence: GPL-3.0. We can read it and learn from its designs, but no code, types or files may be
copied into Anilili Desktop**, which is closed source. Its `onlinestream-provider.d.ts` counts as
code too. A shallow clone for reading sits in the temp folder, outside this repo.

### The most important lesson: they left Tauri for Electron, because of the player

- Seanime Desktop was Tauri until **v3.0.0**, when it was replaced by Electron "Denshi". The cause
  was the built-in player: they wanted one Chromium with consistent codecs on every OS.
- Their default player (**VideoCore**) is HTML5: `hls.js` + `jassub` (libass-wasm) + Anime4K,
  fed through the Go server's video proxy.
- Their mpv player (**MpvCore**, v3.9.0, still "experimental") uses a closed native package,
  `@mpv-prism/electron`. It is downloaded from seanime.app, pinned to the Electron major, and
  renders through **libmpv's OpenGL render API into the Chromium viewport**. It has open
  frame-pacing bugs: periodic stutter with "`mpv_render_context_render()` not being called or
  stuck" ([#965](https://github.com/5rahim/seanime/issues/965)), and laggy PiP
  ([#963](https://github.com/5rahim/seanime/issues/963)).

What this means for us:
1. **It supports §3's choice of `--wid` on Windows.** mpv presents its own swapchain, so it
   never goes through the browser compositor and has no frame-pacing coupling. Their path is the
   one that stutters.
2. **It shows the risk is real.** A capable developer hit enough webview/player friction to change
   shells. Our Phase 1 gate (1) is the right gate.
3. **Change: put an HTML5 fallback engine behind the same player interface.** The UI talks to a
   `PlayerEngine` trait. mpv is the primary engine. A `<video>` + hls.js/shaka + JASSUB engine,
   fed by our Rust proxy, is the fallback for any OS/GPU where compositing fails. The proxy has to
   be complete anyway, so the fallback costs mostly UI glue.

### Things worth re-implementing (the ideas, in our own code)

| Seanime idea | Where | Our version |
| --- | --- | --- |
| Extension `fetch` impersonates Chrome by default (`imroc/req` + uTLS) | `goja_bindings/fetch.go` | Confirms §5: providers' `http` host import goes through `wreq` with a browser profile by default. |
| Per-extension **domain allowlist** in `fetch`; "Extension Secure Mode" prompts for sensitive actions | `fetch.go`, `core/security.go` | Each wasm provider's manifest declares allowed hosts and capabilities (`browser`, `js_eval`). The host enforces them, so a hijacked OTA module can't reach arbitrary hosts. |
| **Headless browser API for extensions** (ChromeDP: navigate, waitVisible, evaluate, `executeCDP`) | `goja_bindings/chromedp.go` | A `browser` host capability backed by our hidden helper webview (CDP on WebView2). Pahe/kiwi-style resolvers become ordinary providers that request it. |
| Providers run in **separate runtimes**, after a pool memory leak (v2.8.4 → v3.0) | CHANGELOG | Fresh wasmtime instance per call, plus fuel and memory limits. |
| **Extension playground**: run a provider's search/episodes/servers against a chosen AniList title in-app, with a debug log | `internal/extension_playground` | A "Provider lab" screen (dev builds): pick a title, run each stage, see requests, timings and output. Most useful for weekly provider fixes. |
| Video proxy details: HTTP/1.1 first with HTTP/2 retry on ALPN errors; HEAD served via upstream GET; **HLS key normalisation** (a base64 text key decoded to the 16 bytes); hop/blocked-header filtering; key size cap | `handlers/proxy.go` | Add these to our `stream` crate's test list. The base64-key case is a real-world quirk we haven't documented. |
| Online-stream **recovery**: refresh stale source URLs before switching provider; keep provider/server/quality/audio/subtitle choices across recovery; auto-cycle providers (v3.8–3.10) | CHANGELOG, `onlinestream/` | Same rules as Android's source picker; worth matching from day one. |
| "Episode 6 [{6.5}]" part-numbering convention in provider titles | `onlinestream/repository.go` | Another label fault to handle, next to the known pipe episode-label faults. |
| **Anime4K** shaders | VideoCore / mpv | Trivial with mpv (`glsl-shaders`). Anime4K is MIT-licensed, so we can bundle it. A big visible win on desktop GPUs. |
| **Subtitle translation** (Google, DeepL, OpenAI, or any OpenAI-compatible local server) | `videocore/translator.go` | Translate the subtitle file and load it into mpv as an extra track. A good desktop feature. |
| **InSight**: character info during playback (Jikan, rate-limited 3/s) | `videocore/insight.go` | Optional "X-Ray"-style panel. Low priority. |
| **Watch party** (Nakama): drift-compensated sync, catch-up before pause, seek cooldown, tiered thresholds, stale-message drop; cloud rooms so hosts needn't expose their PC | `nakama/watch_party_syncing.go` | A later feature. The pairing protocol could carry it to the Android TV app. |
| Generated TS types from the Go API (`codegen/`) | repo root | Same idea as tauri-specta. |
| Frontend stack: React + TanStack Router/Query + **jotai** + motion + hls.js + jassub | `seanime-web/package.json` | Almost identical to §7. Add jotai for UI-only state. |

### Things not to take

- **Torrent/debrid streaming, the auto-downloader, local library scanning, transcoding.** That's a
  different product (a media server).
- **The goja (ES5 JavaScript) extension runtime.** Too loose for first-party providers. Our wasm
  components are typed and sandboxed.
- **Seanime-extension compatibility** (running community `onlinestream-provider` TS extensions).
  It's possible later with an rquickjs host written from scratch, but most community providers
  cover sites we already have, quality varies, and it widens the attack surface. Not for v1.
- Chromium flag tuning (`chromium-flags.ts`) only matters if we ever move to the CEF runtime.

---

## 14. tauri-plugin-libmpv and L-MPV (read 2026-10-04)

Both answer the riskiest question in this plan, "can mpv sit under a Tauri webview on Windows?",
with working code. Both use a **simpler variant of §3b**, which replaces the child-HWND recipe:

> **Pass the Tauri window's own top-level HWND as `wid`.** WebView2 is already a child HWND of
> that window, so mpv paints the parent's surface and the transparent WebView2 sits on top. No
> extra child window, no z-order management. The video rectangle is positioned with mpv's
> **`video-margin-ratio-left/right/top/bottom`**, so the UI can shrink the video for a side
> panel or a mini layout with one property write.

### [nini22P/tauri-plugin-libmpv](https://github.com/nini22P/tauri-plugin-libmpv): MPL-2.0, v0.3.2, 22★, last push 2025-11-24

- ~1k lines of Rust. It loads a small C shim, [`libmpv-wrapper`](https://github.com/nini22P/libmpv-wrapper)
  (LGPL-2.1), with `libloading`. All options, observed properties and events cross that shim as
  **JSON strings**. Commands are exposed to page JS (`command`, `set_property`, `get_property`,
  `set_video_margin_ratio`), plus one mpv instance per window label.
- Windows "fully tested". macOS passes `ns_view` as `wid`, which stock macOS libmpv ignores, so
  that path doesn't really work. Linux: no embedding.
- Built against Tauri 2.9 and not updated in ~10 months.
- **Licensing works for us.** MPL-2.0 is file-level copyleft, so we can depend on it from closed
  source; only changes to its own files must be published. The LGPL wrapper and libmpv are
  separate, replaceable DLLs.
- **Useful fact from its README:** [zhongfly/mpv-winbuild](https://github.com/zhongfly/mpv-winbuild)
  publishes **prebuilt `mpv-dev-lgpl-*` libmpv builds for Windows x64/arm64**. That removes most
  of §3d's "own the LGPL build pipeline" work on Windows. macOS still needs our own build.

**Verdict: use it to get video on screen in the first day of the spike, then replace it.** For
the product, the player belongs to the Rust core (a player actor that sees every event for
progress sync, auto-skip and recovery), not to page JS. The JSON-string shim is an extra DLL and
untyped. Writing our own ~1–2k-line binding over libmpv's `client.h`, with the same top-level-wid
technique, is cheap once the technique is proven.

### [Menely/L-MPV](https://github.com/Menely/L-MPV): GPL-3.0, v2.6.0, 16★, last push 2026-10-03

A polished **Windows-only** Tauri 2 + React 19 player: about 16.7k lines of Rust, with
`mpv_manager.rs` alone at 2.2k. **GPL-3.0, so ideas only, no code.** What it proves and teaches:

- **`vo=gpu-next` + `gpu-api=d3d11` works with top-level `wid` under WebView2**, which means
  full libplacebo quality and HDR tone-mapping on Windows, with no render-API compromise. That's
  better than Seanime's render-API route.
- **A tuned mpv option set** to evaluate (as options, not copied code):
  - A/V sync: `video-sync=audio`, `initial-audio-sync`, `hr-seek`, `hr-seek-framedrop`.
  - Buffering: `demuxer-max-bytes`, `demuxer-readahead-secs`, `cache-pause`.
  - Audio: `ao=wasapi`, `audio-pitch-correction`, `volume-max=150` with a limiter.
  - Subtitles: `sub-ass-force-margins`, `sub-use-margins`, `demuxer-mkv-subtitle-preroll`.
  - Windows: `vo-mmcss-profile=Playback`.
- **WebView2 tricks:**
  - `WEBVIEW2_USER_DATA_FOLDER` for a portable or relocated profile.
  - `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` for font rendering flags.
  - Private fonts registered with `AddFontResourceExW(FR_PRIVATE)`.
  - `backdrop-filter` on a panel kills ClearType text, so blur only the scrim behind it.
- **Frame geometry from `osd-dimensions`** (margins included), which lets HTML overlays line up
  exactly with the video picture, e.g. ambilight or subtitle-aware UI.
- **`screenshot-raw`** for sampling frames in Rust (ambilight, thumbnails) without disk round-trips.
- **AI upscaling**: the AnimeJaNai `vf` filter (ONNX via DirectML/TensorRT) in a custom mpv
  build. That's a Windows-only upgrade over Anime4K shaders, but check the build's licence first.

### Effect on the plan

- Spike gate (1) on Windows drops from "unknown" to "**expected to pass**". Two independent
  projects, plus LibreVP and UniPlayer, do it. The resize/repaint bugs in §3b remain the thing to
  test.
- **macOS stays the open risk.** Neither project solves it (plugin: ignored `wid`; L-MPV:
  Windows-only). UniPlayer's patched libmpv, or the render API, is still the macOS plan.
- Phase 0 gets shorter on Windows thanks to the zhongfly LGPL builds.

---

## Sources

- Tauri 3 alpha / CEF runtime: [releases](https://github.com/tauri-apps/tauri/releases), [tauri-runtime-cef v3.0.0-alpha.4](https://github.com/tauri-apps/tauri/releases/tag/tauri-runtime-cef-v3.0.0-alpha.4), [CEF Linux CPU bug #16189](https://github.com/tauri-apps/tauri/issues/16189), [SableClient tauri-runtime-cef](https://github.com/SableClient/tauri-runtime-cef), [Gitru Tauri 3 migration](https://github.com/ruru-m07/gitru/pull/133)
- mpv in Tauri: [UniPlayer](https://github.com/xiaolai/uniplayer), [tauri-plugin-libmpv](https://github.com/nini22p/tauri-plugin-libmpv), [LibreVP](https://github.com/libreflow/LibreVP), [tauri discussion #6343](https://github.com/orgs/tauri-apps/discussions/6343), [get-air tauri-video-plugin](https://github.com/get-air/tauri-video-plugin/blob/main/docs/linux.md), [lokii Windows plan](https://github.com/mhensberg2003/lokii/issues/1)
- WebView2 transparency bugs: [#2419](https://github.com/MicrosoftEdge/WebView2Feedback/issues/2419), [tauri #12800](https://github.com/tauri-apps/tauri/issues/12800), [tauri #4881](https://github.com/tauri-apps/tauri/issues/4881)
- libmpv render API: [gpu-next render backend PR #16818](https://github.com/mpv-player/mpv/pull/16818), [d3d11 render API issue #5979](https://github.com/mpv-player/mpv/issues/5979), [media-kit ANGLE note](https://github.com/media-kit/media-kit/issues/296)
- Cookies API: [tauri PR #12665](https://github.com/tauri-apps/tauri/pull/12665), [tauri 2.4.0](https://tauri.app/release/tauri/v2.4.0/)
- wreq: [crates.io](https://crates.io/crates/wreq), [wreq-js profiles](https://github.com/sqdshguy/wreq-js), [crw PR using wreq 0.16](https://github.com/us/crw/pull/527)
- adblock-rust in Tauri/WebView2: [adblock crate](https://crates.io/crates/adblock), [Bushido](https://github.com/visualstudioblyat/bushido), [Kessel PR](https://github.com/puss-in-boots-org/kessel-browser/pull/5)
- UniFFI: [uniffi-rs](https://github.com/mozilla/uniffi-rs), [matrix-rust-components-kotlin](https://deepwiki.com/matrix-org/matrix-rust-components-kotlin), [bitwarden sdk-internal](https://deepwiki.com/bitwarden/sdk-internal), [SchildiChat Revenge](https://github.com/SchildiChat/schildi-revenge)
- Wasm on the JVM/Android: [Chicory on Android](https://blog.evacchi.dev/posts/2025/07/11/wasm-the-hard-way-porting-the-chicory-compiler-to-android/), [Endive announcement](https://wasmcloud.com/community/2026-05-27-community-meeting/)
- JS engines: [rquickjs](https://docs.rs/rquickjs), [Boa v0.21](https://lobste.rs/s/upi3xa/boa_release_v0_21_new_release_boa)
- Native HLS in Chromium 142+: [hls.js discussion](https://github.com/video-dev/hls.js/discussions/7644), [WebView2 HLS request #5092](https://github.com/MicrosoftEdge/WebView2Feedback/issues/5092)
- tauri-specta: [releases](https://github.com/specta-rs/tauri-specta/releases), [tauri-typed-ipc](https://github.com/johncarmack1984/tauri-typed-ipc)
- Dioxus/Blitz: [Dioxus 0.7](https://dioxuslabs.com/blog/release-070/), [Blitz](https://github.com/dioxuslabs/blitz)
- Signing: [rcodesign notarization](https://gregoryszorc.com/docs/apple-codesign/stable/apple_codesign_rcodesign_notarizing.html)
- Seanime: [repo](https://github.com/5rahim/seanime), [CHANGELOG](https://github.com/5rahim/seanime/blob/main/CHANGELOG.md), [MpvCore stutter #965](https://github.com/5rahim/seanime/issues/965), [extension docs](https://seanime.gitbook.io/seanime-extensions)
- WASI 0.3 / wasmtime 46: [WASI 0.3 launch](https://bytecodealliance.org/articles/WASI-0.3), [wasmtime v46.0.0](https://github.com/bytecodealliance/wasmtime/releases/tag/v46.0.0)
- Misc crates: [nostr-sdk](https://crates.io/crates/nostr-sdk), [oxicast](https://docs.rs/oxicast/latest/oxicast/), [rust_cast](https://crates.io/crates/rust_cast), [souvlaki maintenance note](https://github.com/esoltys/luminous/issues/576)
