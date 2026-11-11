# Desktop (Windows + macOS) port: feasibility and stack choice

2026-09-24. iOS is out of scope (dropped: App Store-blocked, and Kotlin/Native would force
replacing OkHttp, `javax.crypto` and `java.io` everywhere).

**Recommendation: Compose Multiplatform for Desktop (JVM), mpv for playback, KCEF/JCEF loaded on
demand for the few things that need a browser engine. Not Tauri. Rust is not needed.**

The deciding fact: this app's value is its ~40 providers and the pipe, and those break weekly. On a
JVM desktop they are the *same code* as Android — a provider fix lands once and ships to phone, TV
and PC. Every alternative stack either rewrites that layer (and doubles every future fix) or wraps
it in a sidecar JVM, which cancels the alternative's only real advantage.

---

## 1. What of today's code runs on a desktop JVM

`app/src/main`: 339 files, ~109k lines. Classified by imports:

| Bucket | Files | Lines | On desktop |
| --- | ---: | ---: | --- |
| No Android, no Compose | 115 | 21.7k | Moves as-is |
| Compose only | 69 | 15.6k | Moves as-is (CMP is the same API) |
| Android, but only `Context`/`Uri`/`Build`/`SystemClock`/`Log`/`Toast`-level | 43 | 19.0k | Small `expect`/interface seams |
| 1–3 "real" Android imports | 55 | 16.6k | Mostly seams (e.g. `AnivexaClient`, `LibraryStore`: one each) |
| 4+ real Android imports | 57 | 36.1k | Rewrite or desktop-specific replacement |

So roughly **two thirds (~73k lines) is reusable directly or behind thin seams**; the ~36k-line
hard core is concentrated in the player, WebView, Android services and `MainActivity`:

| Heavy file | Lines | Desktop answer |
| --- | ---: | --- |
| `ui/watch/EmbedWebView.kt` | 3.8k | JCEF, JS injection logic carried over |
| `ui/settings/SettingsScreen.kt` | 3.6k | Mostly Compose; strip Android intents/pickers |
| `ui/watch/WatchScreen.kt` | 3.5k | Shared screen, Android bits to seams |
| `ui/watch/PlayerSurface.kt` | 3.5k | **Rewrite** against mpv |
| `data/settings/SettingsStore.kt` | 2.0k | DataStore is multiplatform; path provider seam |
| `MainActivity.kt` | 1.7k | Desktop `main()` + window; mostly not ported |
| `playback/EpisodeDownloads.kt`, `EpisodeExport.kt` | 2.5k | **Rewrite**: OkHttp segment fetch + mpv/ffmpeg remux |
| `playback/PlaybackService.kt` | 1.1k | Not needed (no background service); OS media keys instead |
| `ui/profile/LoginWebView.kt` | 1.2k | **Not needed** — see §4 (system browser) |
| `diagnostics/DiagnosticsLog.kt` | 1.0k | Seam: no exit records/logcat, file log + uncaught handler |
| TV-only UI (`Tv*.kt`) | 5.3k | **Not needed** on desktop |

Other facts that make this cheaper than it looks:

- **288 of 293 unit-test files import nothing from Android** and there is no Robolectric — the
  30k-line test suite runs on plain JVM already and moves to `jvmTest` unchanged.
- **OkHttp, `javax.crypto`, `java.io`, `ConcurrentHashMap`, `Executors`, `Regex`** — all JVM, all
  work. Desktop `Regex` is `java.util.regex`, i.e. the non-ICU engine the provider parsers were
  written against in tests.
- `AppDns`/`AutoDns` (DoH) is OkHttp-level and works unchanged — the ISP-block bypass survives.
- `LegacyTrustAnchors` (the ISRG Root X1 fix for old Android) is not needed; JBR has current CAs.
- UI strings are hard-coded (9 `stringResource` calls in total), so there is no resource
  migration problem.
- `org.json` (6 files) is built into Android but not the JVM → add the Maven artifact (the tests
  already do exactly this).
- `LocalStreamProxy` is `ServerSocket` + OkHttp: runs as-is and is the bridge that feeds a desktop
  player the header-gated and byte-rewritten streams (Kwik IV, Flixcloud XOR, UniqueStream key,
  PNG-wrapped TS).
- Pairing (`PairingTransport`) is plain sockets → a PC can pair with the TV as a sender for free.

## 2. The three hard pieces

### 2a. Video player — mpv

| Option | Verdict |
| --- | --- |
| **libmpv** (via `open-ani/mediamp` mpv backend, or own JNA/FFM binding) | **Choose.** Plays HLS, DASH (AnimeOnsen works — unlike AVPlayer/WebView), AES-128, progressive MP4; takes `http-header-fields`/`referrer` natively so many gated streams need no proxy at all; full ASS/SSA subtitle rendering (better than Media3); `sub-delay` built in (replaces `SubtitleDelay` ForwardingRenderer hack); hardware decode on both OSes. LGPL build available for Windows/macOS. |
| libVLC via vlcj | Works, heavier, GPL/LGPL mix; Animeko ran it for years then **deprecated it for mpv**. |
| JavaFX Media | No DASH, weak HLS, no headers. No. |
| FFmpeg via FFM (`skinema`) | DIY player — too much to own. |

**The known hard part is getting video *under* Compose controls.** `SwingPanel` with a heavyweight
surface always draws on top of Compose (interop blending is experimental and fails over a GPU video
surface on Direct3D). The workable routes:

1. **mpv render API → GPU texture composited by Skiko** (Metal on macOS, OpenGL/D3D readback on
   Windows). This is what mediamp's mpv backend does and what Animeko ships; requires CMP ≥ 1.12.
   Best result, and someone else maintains the hard native interop — but mediamp is pre-0.1
   (no API guarantees), so pin a version and be ready to fork.
2. **Software frames → Skia `Image`** (CPU copy per frame). Simple, fine at 1080p, costs CPU.
3. Fallback: a separate borderless native video window with Compose controls in an overlay window.

The shared custom Compose player controls (already used by both native and embed on Android — see
the player-controls architecture) port as-is on top of any of these. `SubtitleCueLayout` placement
logic mostly goes away: mpv renders and positions subtitles itself (`sub-pos`, `sub-scale`,
`sub-margin-*`).

### 2b. Browser engine — JCEF (KCEF), lazily

What genuinely needs a browser engine on desktop:

- Embed servers (`EmbedWebView`, iframe players) — required.
- WebView resolver bridges: Pahe (kiwi), Hanime, Flixcloud (reanime), AllAnime captcha,
  `CloudflareChallengeDialog` — required for those providers.
- **Not** login (§4), **not** the pipe (it's OkHttp now).

Options: KCEF (Chromium, via `compose-webview-multiplatform` or directly) vs a native webview
(WebView2/WKWebView) in its own window. Choose **KCEF**, because the bridges inject JS and read
cookies — same model as Android's WebView — and Chromium behaves the same on both OSes. Cost:
**~150–200 MB**, and Animeko's release notes are a warning list (too many CEF processes, high CPU,
a system-wide freeze). Mitigations: KCEF can **download the Chromium bundle on first use** instead
of shipping it, so users who never touch an embed/Pahe server never pay; one shared CEF app
instance; dispose clients after each resolve.

Cloudflare: clearance earned in JCEF still won't replay from OkHttp on TLS-fingerprinted zones —
exactly as on Android (AnimePahe). Nothing new, nothing worse.

### 2c. Downloads & export

Media3's `DownloadManager` and `Transformer` have no desktop form. Replacement: download segments
with OkHttp (already the transport), decrypt with the same keyed-source code, remux to MP4 with
mpv's `--o=` encode mode or a bundled `ffmpeg` (both LGPL-able). Simpler than Android's pipeline,
and the whole `downloaded-episode-green-video` / avcC repair class of bugs disappears with it.

## 3. Feature map

| Feature | Desktop | Notes |
| --- | --- | --- |
| Browse, search, detail, schedule, library, profile | ✅ shared Compose | Phone/tablet adaptive layouts fit a window; add hover/right-click/keyboard |
| All OkHttp providers + pipe | ✅ same code | The main win |
| WebView providers, embeds | ✅ via JCEF | Lazy download |
| Native playback, subs, delay, skip times (AniSkip) | ✅ mpv | ASS better than Android |
| AniList / MAL login | ✅ **easier** | Redirect is already `http://localhost` → open system browser, bind a loopback listener, catch the code. Also sidesteps Google-login-in-embedded-browser blocks |
| AniList/MAL sync, backup | ✅ | `ListProgressSyncWorker` → coroutine timer while app runs |
| Discord presence | ✅ **easier** | Desktop Discord takes activity over local IPC (`discord-ipc-0`) with just a client id — no Social SDK access gate |
| Downloads + MP4 export | ⚠️ rewrite | §2c |
| Episode reminders / notifications | ⚠️ | Tray notifications while running; no scheduling when closed (could add OS login item) |
| TV pairing | ✅ as sender | Sockets reused |
| Update channel (Nostr) | ⚠️ extend | Manifest gets MSI/DMG/zip entries; or Conveyor (§5) |
| Diagnostics bundle / error codes | ⚠️ | Keep `ErrorCatalog` codes; no exit records/LMK; add uncaught-exception + native crash (mpv/CEF) capture |
| Chromecast | ❌ (initially) | No desktop Cast SDK; unofficial CASTV2 Java libs exist — later, maybe |
| TV UI, D-pad, TV channels, Fire OS quirks | ❌ not applicable | |
| External player (VLC hand-off) | ✅ trivial | Desktop can just launch VLC/mpv with headers on the command line |

## 4. Build, package, sign, update

- **Packaging**: the Compose Gradle plugin (`jpackage`) makes MSI/EXE and DMG with a jlinked
  JetBrains Runtime. Rough sizes: runtime 50–70 MB + app ~20 MB + libmpv tens of MB → **~100–150 MB
  installer without Chromium**, +150–200 MB if JCEF is bundled rather than lazy.
- **You don't have a Mac** — this is the most important practical constraint:
  - `packageDmg` only runs on macOS; there is no GitHub CI (code must not be pushed there).
  - **Hydraulic Conveyor** builds, signs and *notarizes* Mac packages **from Windows**, and does
    auto-update (Sparkle on macOS, MSIX/.appinstaller on Windows, delta updates). It is free only
    for OSI-open-source projects; this one is closed, so it's a paid subscription.
  - Either way, **testing** mpv/Metal rendering and Gatekeeper behaviour needs a real Mac (a used
    Apple-silicon Mac mini, or a rented cloud Mac). Intel + Apple-silicon builds both needed.
- **macOS signing**: without an Apple Developer ID ($99/yr) + notarization, macOS 15+ makes users
  go to System Settings → Privacy & Security → "Open anyway" for every new build. Workable for
  testers, poor for users.
- **Windows signing**: unsigned installers get the SmartScreen "Windows protected your PC" screen
  (More info → Run anyway). A certificate (OV/EV, or Azure Artifact Signing where eligible) costs
  money and reputation builds slowly. Launching unsigned is common for this genre of app.
- **Updates**: extend the Nostr manifest with desktop assets per OS/arch; the app downloads and
  launches the installer (Windows) or swaps the `.app` (macOS). Mirrors (HuggingFace) apply the same
  way. Conveyor replaces all of this if bought.
- **Toolchain**: Kotlin 2.1.21 → current (2.3/2.4), AGP 8.10 → 9 with the KMP Android plugin, CMP
  1.12. And the **minSdk 23** question (Compose 1.10+ drops API 22 = Fire OS 5) — *unless* the
  desktop shares only the non-UI core with Android at first (see §7 phase 1), which keeps Android
  on its current Compose and defers the Fire OS decision.

## 5. Tauri — would it help?

Tauri = Rust core + the OS's own webview (WebView2 on Windows, WKWebView on macOS) + a web UI.

**What it would do well**

- Small installers (~10–20 MB) and low idle memory, *because* it doesn't ship Chromium.
- WebView2 is real Chromium, so embed servers and resolver pages would run in a genuine browser
  without bundling JCEF.
- Good updater and signing tooling out of the box.

**Why it's the wrong fit here**

1. **It reuses none of the Kotlin.** UI rewritten in HTML/TS; providers, pipe, crypto, matching,
   catalogue, sync, settings rewritten in Rust or TS. That's the ~73k reusable lines above plus the
   30k lines of tests.
2. **Two provider implementations forever.** Android stays Kotlin. Every weekly provider break
   (see the provider memory notes: MegaPlay, MKissa, hanime, HentaiHaven rewrites…) gets fixed
   twice, in two languages, tested twice. This is the cost that never ends.
3. **Playback inside the webview is weaker than mpv**:
   - WebView2 has no native HLS in practice → hls.js over MSE; **DASH needs dash.js/shaka**.
   - Page JS **cannot set `Referer`** (forbidden header) → every gated stream must go through a Rust
     custom-protocol proxy that also rewrites playlists — i.e. re-implement `LocalStreamProxy` and
     every byte-level quirk (Kwik IV, Flixcloud XOR, PNG-wrapped TS, UniqueStream key) in Rust.
   - Codecs differ per OS: HEVC on WebView2 needs the paid "HEVC Video Extensions"; WKWebView's
     capabilities are frozen to the macOS version.
   - ASS subtitles: needs libass-wasm/JASSUB.
   - Or bolt mpv onto Tauri anyway — then the webview's playback advantages are gone and you have
     the same native-video-under-web-UI compositing problem, with less prior art than Compose.
4. **The hybrid (Tauri UI + Kotlin core as a JVM sidecar)** keeps the providers but ships a jlinked
   JVM (~50–70 MB) anyway, adds a local IPC/HTTP API between two processes, and still rewrites the
   entire UI in web tech. Worst of both.

**Verdict: no.** Tauri makes sense for a greenfield desktop app or one whose logic is already
Rust/JS. Here, the codebase *is* the asset.

## 6. Would Rust help at all?

Not as the app language. Inside a Compose Desktop app the native pieces are C libraries (libmpv,
CEF) reached via JNA/Java FFM — Rust adds a third language for nothing.

**One niche where it could genuinely help, and on Android too:** TLS/HTTP-2 fingerprint
impersonation. Several memory notes end at "Cloudflare binds clearance to the engine" / "useless
against TLS-fingerprint zones" — OkHttp's ClientHello doesn't look like Chrome. Rust crates like
`wreq` (formerly `rquest`, BoringSSL, Chrome fingerprints) — or the C `curl-impersonate` — can
send Chrome-identical TLS + HTTP/2 frames. Exposed through JNI as an OkHttp-like call, it *might*
let a `cf_clearance` earned in Chromium (JCEF, or Android WebView) replay from code, reviving the
dead AnimePahe earn-in-WebView/replay-in-HTTP approach. **Unverified**; it's a separate
experiment, valuable on both platforms, and independent of the desktop decision.

## 7. Plan

**Phase 0 — decisions**: Apple Developer ID yes/no; Mac for testing (buy/rent); Conveyor yes/no;
Windows signing yes/no.

**Phase 1 — `:core` module, JVM + Android, no CMP yet (~2–4 weeks).** Move the 115 pure files and
the 98 light/1–3-import files behind small seams (`PlatformPaths`, `Prefs`, `Clock`, `Log`,
`DeviceInfo`, `OpenUrl`). Tests move with them. Android keeps its current Compose/minSdk 22, so
**no Fire OS decision is forced yet**. Zero user-visible change.

**Phase 2 — desktop spike (~1–2 weeks), go/no-go gate.** A bare Compose Desktop window that:
lists a title via the pipe (core module), plays one header-gated HLS provider and AnimeOnsen (DASH)
through mpv with Compose controls on top, resolves one Pahe episode in lazy KCEF. Run on Windows
*and* a Mac. If mpv-under-Compose fails on either OS, fall back to the separate-video-window
approach before going further.

**Phase 3 — desktop beta (~6–10 weeks).** Shared UI module (components, home, detail, search,
settings, theme); desktop window/menus/keyboard shortcuts; login via browser; downloads/export;
Discord IPC; diagnostics; packaging + update channel. Decide then whether the Android app moves
onto the shared UI module (that's when minSdk 23 / Fire OS 5 must be settled).

Estimates are rough and for one developer with AI help; the player and browser-engine layers are
historically where this project's time goes.

## Sources

- Animeko (CMP desktop anime app; JCEF + VLC→mpv): https://github.com/open-ani/animeko ,
  https://deepwiki.com/open-ani/animeko/1-overview , JCEF fixes in
  https://newreleases.io/project/github/open-ani/animeko/release/v5.5.2 and
  https://newreleases.io/project/github/open-ani/animeko/release/v5.7.0-alpha01
- mediamp (mpv backend, Skiko texture interop): https://github.com/open-ani/mediamp ,
  https://github.com/open-ani/mediamp/pull/71
- SwingPanel z-order / interop blending limits: https://github.com/JetBrains/compose-multiplatform/issues/4941 ,
  https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-desktop-swing-interoperability.html
- vlcj direct rendering: https://capricasoftware.co.uk/tutorials/vlcj/4/direct-rendering
- compose-webview-multiplatform desktop (KCEF): https://github.com/KevinnZou/compose-webview-multiplatform/blob/main/README.desktop.md
- CMP 1.12.0: https://blog.jetbrains.com/kotlin/2026/08/compose-multiplatform-1-12-0/
- Compose minSdk 23: https://developer.android.com/jetpack/androidx/releases/compose-ui
- Conveyor: https://hydraulic.dev/pricing.html , https://conveyor.hydraulic.dev/12.1/faq/
- Tauri referer/custom protocol: https://github.com/tauri-apps/tauri/issues/14278 ,
  https://github.com/orgs/tauri-apps/discussions/15171 , https://v2.tauri.app/develop/sidecar/
- Tauri webview versions: https://v2.tauri.app/reference/webview-versions/
- WebView2 HEVC: https://github.com/MicrosoftEdge/WebView2Feedback/issues/4285 , https://caniuse.com/hevc
