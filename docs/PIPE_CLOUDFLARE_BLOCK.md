# Why the pipe could not reach miruro: the hidden tab was too small to answer

Investigation record, 2026-09-08. Read this before touching `PipeBridge` or re-opening
"why are the miruro providers missing". Companion to [PIPE_PROTOCOL.md](PIPE_PROTOCOL.md),
which describes the protocol itself; this file is about why we could not speak it.

**Status — RESOLVED 2026-09-10/11 (§0.22). The pipe runs on plain OkHttp and GeckoView is gone.**
Read §0.21 then §0.22 first; most of what follows is the road there, kept for its measurements and
its wrong turns, not for its conclusions.

**Update 2026-09-09 (§0.21): the header, and every earlier "client identity" theory, is moot for
*spending* — the pipe answers plain OkHttp holding a clearance, the earner's UA and same-origin
headers. Only *earning* still needs a real engine. §0.19's conclusion is overturned there.**

**Update 2026-09-10/11 (§0.22): *earning* turned out not to be needed either — re-measured, the
zone was not challenging, and an engine-free client got byte-identical payloads to the in-page
fetch. Corroborated by six tester devices (zero 403s fleet-wide) and a 26-run replay. So the
original status below is superseded twice over: the `X-Requested-With` finding stands as history,
but it is no longer what stops anything.**

~~Status: root cause confirmed — `X-Requested-With` (§2, replicated in §0.7) — and there is no API
left to remove it (§0.8). Not fixable inside an Android WebView; the realistic routes are asking
miruro's operator or moving the pipe to a non-WebView engine (§0.9).~~

**Separately, four real bugs behind "everything is walled" were found and fixed (§0): the hidden tab
could not render the challenge at all, then could not paint it fast enough, the app pinned a dead
mirror, and the walk abandoned live ones. None of them were the wall, but every experiment in this
file predating them was run against a tab that physically could not answer a challenge — so their
"still walled" results meant nothing, and the ones that mattered had to be re-run (§0.6). That is
the reusable lesson, and it caught this file's author out twice, in both directions.**

---

## 0. Root cause: Turnstile has nowhere to render in a 1dp tab

Cloudflare's managed challenge answers itself by rendering Turnstile. The pipe hosts its session in
a deliberately hidden 1dp WebView, and at that size Turnstile renders **nothing** — so no clearance
is ever issued and the interstitial sits there until the bridge gives up on it.

Read off the stuck page in the app's own WebView, no navigation, nothing spoofed:

```
url        https://www.miruro.bz/          title  "Just a moment..."
inner      [4, 4]      outer [2, 2]        body   [1, 0]
visibility "visible"   hasFocus true       rAF    3 frames in 22ms   setTimeout fires
scripts    /cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1?ray=…
           challenges.cloudflare.com/turnstile/v0/g/330e41bb475c/api.js?onload=…&render=explicit
iframes    []            <-- the widget was asked for explicitly and never created
```

The page is alive — frames render, timers fire, the loader and `api.js` both arrive. There is simply
no room: a 4×4 viewport with a 1×0 body.

### 0.1 The A/B

Same WebView, same cookie jar, same `; wv` user agent, same `X-Requested-With`, same IP, same URL.
The only variable is `Emulation.setDeviceMetricsOverride`:

| Viewport | `www.miruro.bz/` |
| --- | --- |
| 4×4 (as shipped) | **WALL** — 30s, `iframes=0`, never granted a cookie |
| 412×915 | **CLEARED in 3.19s**, `cf_clearance` set |

```
  0.5s title="Just a moment..." inner=412,915 body=412,0 iframes=0 len=261
  2.1s title="Just a moment..." inner=412,915 body=412,0 iframes=0 len=261
  2.7s title="Miruro · Watch Anime Online Free…"  body=0,0    len=0
  3.2s title="Miruro · Watch Free Anime Online…"  body=412,64 len=3533   -> CLEARED
```

**Layout size alone is sufficient.** The CDP override changes the layout viewport while the Android
view stays 1dp and unpainted, so the challenge needs to be *laid out*, not seen. That is what makes
the fix cheap.

### 0.2 The fix

`PipeWebView` keeps its 1dp footprint and lets the page overflow it:

```kotlin
Box(modifier = if (visible) modifier else Modifier.size(1.dp).clipToBounds()) {
    AndroidView(
        …,
        modifier = if (visible) Modifier.fillMaxSize()
                   else Modifier.requiredSize(412.dp, 915.dp),
    )
}
```

`requiredSize` overrides the incoming 1dp constraint, so the tab is measured at a whole phone
viewport and clipped away by the Box. The footprint, the hit region and the accessibility tree are
unchanged. 412×915 rather than a token size because the tab already claims to be a phone in its user
agent, and a viewport that matches no real phone is its own tell.

**The Box and the `AndroidView` must stay structurally identical between hidden and shown.** Adding
or removing a wrapper as `visible` flips destroys the WebView holding the challenge and reloads
straight into a fresh one — only modifiers may differ.

### 0.3 Why this took three sessions to find

Two independent confounds, both of which produce "everything is walled":

1. **Per-mirror verdicts differ, and `.ru` is genuinely blocked.** At 412×915, `.bz` clears in 3.2s
   while `.ru` is still walled after 45s *while holding a valid `cf_clearance`*. §3 below eliminated
   the viewport hypothesis on evidence gathered against `.ru` (see the frame tree in §5, which is a
   `www.miruro.ru` page) and generalised it to the zone. **Test a viewport or transport hypothesis
   on at least two mirrors before believing it.**
2. **The app pinned itself to the dead mirror.** `setLastWorkingPipeOrigin` was written only on
   success and never cleared on failure, so once `.ru` worked once it was tried first on every
   attach *and* given the longer remembered watchdog. Measured on the S25: **71 of 78 page loads
   went to `.ru`**, which is why the failure looked total rather than per-mirror.

### 0.4 A wrong turn, recorded so it is not repeated

Mid-session this file briefly claimed §2 was disproven, on the strength of one fresh-context arm in
which the header cleared in 4.6s. **That was wrong** — see §0.7, where a properly interleaved repeat
reproduces §2 exactly. One arm is not a result when the thing being measured is a challenge whose
outcome varies run to run; §8.2 already said to run the control on both sides of every treatment,
and that advice was not followed.

What does survive from that detour: `.ru` is a real, separate fault — walled at a full viewport for
45s *while holding a valid `cf_clearance`*.

### 0.5 What is still broken

Fixing the viewport changed the app's behaviour measurably and reproducibly, across two cold starts,
in its own diagnostics — but did not get the pipe working:

| | Before (4×4) | After (real viewport) |
| --- | --- | --- |
| Page loads per mirror | 1, then frozen | 3–4 reload rounds |
| `cf_clearance` granted | **never** | on every round (`clearance=true cookies=2`) |
| Outcome | inert interstitial | grant → refuse → reload, indefinitely |

So the challenge went from *unable to run* to *running and losing*. Ruled out since:

- **Not time.** One mirror left completely undisturbed for **120s** never cleared, while desktop
  Chrome on the same IP cleared `/health` about five minutes earlier. The 12s `CHALLENGE_SETTLE_MS`
  is not the binding constraint.
- **Not the network.** Desktop Chrome control run immediately after: interstitial, then
  `{"status":"ok",…,"version":"1.14.0"}`. The IP is clean.
- **Not viewport/screen coherence.** A hardcoded 412×915 on a 384×832 dp device is a mismatch no
  real phone has, so the tab now sizes itself from `LocalConfiguration`. Worth doing on principle,
  but a coherent 384×832 arm walled too.

**Render throttling was the next real blocker, and it is also fixed.** The clipped-away tab produced
**2–3 frames per second** because `LAYER_TYPE_SOFTWARE` — inherited from when this was a 1dp helper
— now meant repainting a 1080×2340 bitmap every frame. The layer was there to keep a *1dp* WebView
off Chromium's SurfaceControl overlay path; there is no 1dp WebView any more, so it is gone and the
tab runs on the hardware layer hidden as well as shown. Measured after: **~120fps**, and a
screenshot confirms no blackout regression.

### 0.6 Where it actually stands

With viewport, frame rate, mirror pinning and the walk all corrected, the wire trace over a full
cold-start walk is unambiguous — **19 main-document requests carrying a valid `cf_clearance`
(`blockedReasons=[]`), across all four mirrors, every one `403 cf-mitigated: challenge`:**

```
www.miruro.to  1. cookie=clr -> 403 challenge   ... 4 of 4 refused
www.miruro.tv  1. cookie=none-> 403 challenge   2-5. cookie=clr -> 403 challenge
www.miruro.bz  1. cookie=none-> 403 challenge   2-5. cookie=clr -> 403 challenge
www.miruro.ru  1. cookie=none-> 403 challenge   2-5. cookie=clr -> 403 challenge
```

The control that makes this meaningful: **the device's own Chrome, same handset, same Wi-Fi,
minutes later, returned `{"status":"ok",…,"version":"1.14.0"}` from `/health` with no challenge at
all.** So it is not the IP, not the network, not a probing burst, and not the mirror.

Re-tested under the now-correct conditions (challenge renders, tab paints):

| Hypothesis | Result |
| --- | --- |
| UA + full client-hint spoof to the device's real Chrome | **still walled** — §3's verdict survives a fair re-test |
| Strip `X-Requested-With` via CDP `Fetch.continueRequest` | **impossible.** Fetch sees only `Accept, Upgrade-Insecure-Requests, User-Agent, sec-ch-ua*`; the wire still carried `X-Requested-With: com.miruronative.debug` on all 17 requests |

So `X-Requested-With` is the last standing difference between the app's WebView and the Chrome that
passes on the same device — and it can be removed neither in-app (§2's `supported=false`) nor from
CDP.

### 0.14 The header is NOT the gate — §0.7 and §2 were both measuring CORS

The inverse experiment below (§0.7) injected `X-Requested-With` with CDP
`Network.setExtraHTTPHeaders`. That sits **above** the CORS layer, so a non-safelisted header name
forces preflight `OPTIONS` on the challenge platform's cross-origin requests and breaks the
challenge. Every "header → WALL" result in this file, including the value matrix where even `""`
walled, is explained by that one artifact — any non-safelisted *name* triggers preflight regardless
of value, which is exactly the pattern §0.7 reported as "presence is matched".

Retested by injecting at the **network** layer instead — a local forward proxy that terminates TLS,
adds the header, and re-issues upstream, which is where WebView stamps it (below CORS, no preflight):

```
control-direct            -> CLEARED 4.35s / 4.44s
proxy-clean (no header)   -> CLEARED 5.09s / 4.44s
proxy-INJECT at network   -> CLEARED 4.38s / 4.38s     162/162 requests carried the header
```

**Two things follow, and the second is the useful one:**

1. `X-Requested-With` does not cause the wall. Do not spend any more effort trying to remove it.
2. **A browser clears the challenge with its bytes leaving on Node's TLS stack** — so there is no
   TLS-fingerprint gate on *earning* either, not just on spending (§0.11). Both the "two
   independent signals" claim (§3.1) and the header claim are now dead.

So what blocks the app? See §0.15 — it is the WebView itself, not anything on the wire.

### 0.15 Transport exonerated too: it is the WebView, not the bytes

The discriminator: route the app's own WebView through a local proxy, so the **JS stays WebView's**
while the **bytes leave on our stack**. Built and run on the S25 — `ProxyController` pointing at a
host proxy over `adb reverse`, the pipe's `WebViewClient` accepting the proxy's certificate, and the
spike's idle-pause and cert-retry cap lifted so the challenge got a fair run.

The plumbing worked: **181 requests, 45 POSTs, 1.2 MB of bodies** flowed through the proxy — note
that POST bodies are exactly what `shouldInterceptRequest` cannot carry (§0.12), so a proxy really
does clear that obstacle. And the verdict:

```
22:01:10 … 22:02:04   "Just a moment..."  every sample, 60s   cf_clearance=true (placeholder)
```

Against, in the same session: desktop Chrome through **that same proxy** cleared in 5.13s, and
GeckoView in-app cleared in 3.65s with no proxy at all.

**Conclusion: neither the header (§0.14) nor the transport is the gate. Cloudflare's judgement is on
Android WebView as an engine** — its JS surface — which is why every network-layer idea in this file
failed and why swapping the engine is the only thing that has ever worked. There is no
zero-megabyte fix.

*Caveat, now closed by §0.16 rather than by a rerun:* the WebView had to accept a self-signed
certificate per request and the run logged 12 upstream errors. That caveat stops mattering once the
*reason* is identified, because the same WebView fails direct, with no proxy and no certificate
friction, under every identity tweak available.

### 0.16 Why: the JS surface is unmistakably WebView, and it cannot be patched over

**First, a method warning that invalidates several earlier results.** Cloudflare's challenge runs
devtools detection, and it is visible in its own console output from the app's WebView:

```
[console.debug]  %c%d font-size:0;color:transparent NaN
[console.table]  %c%d font-size:0;color:transparent Error
[console.dir]    Error
```

Those are `%c` format-specifier and getter probes that fire only when a debugger is attached. **Any
CDP-attached measurement of challenge *outcome* is contaminated** — including §3's "UA + client
hints spoof, still walled". Read outcomes from `events-main.jsonl` with nothing attached. Attaching
to enumerate a static surface is fine; attaching to judge pass/fail is not.

**The challenge is not pre-judged.** Read from `window._cf_chl_opt` inside the app's WebView:
`cType = "managed"` — the same variant Chrome gets — and `turnstile` loads. It runs, submits (45
POSTs and 1.2 MB of bodies observed in §0.15), and its answer is rejected.

**What the answer contains, measured on the S25:**

```
windowChrome: "undefined"     <-- real Chrome always has window.chrome
Notification: "undefined"     <-- Chrome has it, WebView does not
usb / bluetooth / serial / hid: false
plugins: 0, mimeTypes: 0
webgl: "Adreno (TM) 830"      (genuine GPU — the hardware signals are real)
```

**Two tweaks tried, both properly (in-app, no debugger attached), both negative:**

| Tweak | Result |
| --- | --- |
| Coherent Chrome identity: UA string **and** client hints via `androidx.webkit` `UserAgentMetadata` | **WALL** on all 4 mirrors |
| The above **plus** a document-start shim adding `window.chrome` and `Notification` (`WebViewCompat.addDocumentStartJavaScript`) | **WALL** on all 4 mirrors |

Note the first made things measurably *worse*: with the honest WebView UA the challenge reloads 3–4
times per mirror and issues clearances; claiming to be Chrome it does one load per mirror and issues
none. The UA is not the tell — the contradiction between the claim and the surface is.

Also worth correcting an old note: a WebView **can** now set its client hints
(`UserAgentMetadata`, androidx.webkit 1.8+). The prior "can spoof UA but not Client Hints" is out of
date; it just does not help.

**Conclusion: the block is deep engine fingerprinting, not a surface check.** Patching the visible
holes changes nothing, and chasing further is a treadmill by construction — Cloudflare probes
`Function.prototype.toString` for patched natives, so each shim is itself a signal, and the target
moves on their schedule. A real browser engine has none of these holes because it is not pretending:
GeckoView cleared in 3.65s with no spoofing at all.

### 0.7 ~~The header, proven by the inverse experiment~~ — INVALID, see §0.14

The header cannot be *removed* from WebView, so it was **added to a client that passes** instead.
Headed desktop Chrome, fresh `Target.createBrowserContext` per arm, controls interleaved on both
sides, same IP, `https://www.miruro.bz/`:

```
control  -> CLEARED in 5.98s      xrw -> WALL
control  -> CLEARED in 4.36s      xrw -> WALL
control  -> CLEARED in 4.35s      xrw -> WALL
control  3/3 cleared              xrw  0/3 cleared
```

`X-Requested-With: com.miruronative.debug` is the whole difference between a 4-second pass and a
permanent wall. §2 was right.

**Presence is matched; the value is irrelevant — and miruro has not blocklisted us.** Same harness,
`sent=` read back off the wire so an arm that quietly sent nothing cannot be misread as a pass:

| Arm | Sent | Result |
| --- | --- | --- |
| control ×4 | `ABSENT` | **CLEARED** 5.11s / 5.77s / 5.12s / 5.07s |
| ours, debug | `"com.miruronative.debug"` | WALL |
| ours, release | `"com.miruronative"` | WALL |
| an unrelated app | `"com.spotify.music"` | WALL |
| `"XMLHttpRequest"` | `"XMLHttpRequest"` | WALL |
| empty string | `""` | WALL |

`com.spotify.music` walls exactly like our own package, so this is a generic rule on the header, not
a block aimed at this app. Changing `applicationId` would buy nothing — and must not change anyway,
being the install identity.

### 0.11 The header blocks *earning* a clearance, not spending one

Two measurements that together close off the cheap fixes, and correct §3.1.

**A clearance can be spent by anything.** Earn one in headed Chrome, then replay `/health` from
plain Node — same public IP, same UA, no `X-Requested-With`:

```
no cookie, no header       -> 403 cf-mitigated=challenge
WITH earned cf_clearance   -> 200  {"status":"ok",...,"version":"1.14.0"}
```

There is no TLS gate. Node's fingerprint is nothing like Chrome's and it is served happily.

**But the clearances the app earns are not real.** Polling the app's own cookie jar and spending
each new cookie the instant it appeared, from that same header-free Node client:

```
15 distinct cf_clearance cookies, all four mirrors -> 403 every time
```

Every one issued alongside `cf_chl_rc_ni`, Cloudflare's retry marker. So the header does not stop us
*using* a clearance — it stops the challenge ever genuinely completing, and the cookie handed back
is a placeholder in a loop that never terminates.

**Consequence for any fix:** a header-free *engine* is needed only to earn the clearance. Once
earned, the pipe traffic itself could be plain OkHttp. This also finally kills the most attractive
cheap idea — "earn it in the WebView, spend it from OkHttp" — because there is nothing valid to
spend.

### 0.12 Evaluated and rejected: `shouldInterceptRequest` + OkHttp

The standard Android answer, and it cannot work here. **Cloudflare submits the challenge answer by
POST.** Captured from the app's own WebView over one minute:

```
  11x  POST /cdn-cgi/challenge-platform/h/g/fo/1234776533:...
  11x  POST /cdn-cgi/challenge-platform/h/g/fo/2892024054:...
   (everything else in the flow is GET)
```

`WebResourceRequest` exposes no request body, so those 22 POSTs are exactly the requests
interception cannot replay — and they are the ones carrying the answer. Proxying only the GETs
leaves the challenge unable to complete, which is the state we are already in. The same limitation
is why Mihon's PR keeps a package-name spoof alongside its interception (§0.10).

### 0.13 GeckoView spike — PROVEN, in this app, on this device

A non-WebView engine clears the challenge. Two measurements:

1. **Firefox for Android** (`org.mozilla.fenix`, i.e. GeckoView) loaded `www.miruro.bz/health` on the
   S25 and returned `{"status":"ok",…,"version":"1.14.0"}` — no challenge. Costs nothing to repeat:
   `adb shell am start -a android.intent.action.VIEW -d <url> -p org.mozilla.fenix`.
2. **GeckoView embedded in this app** (`GeckoProbeActivity`, spike worktree), full-screen so the
   engine gets a real painted viewport:

```
GeckoProbe page start  https://www.miruro.bz/     849ms
GeckoProbe title       challenged=true           1443ms
GeckoProbe page start  https://www.miruro.bz/    3528ms
GeckoProbe title       challenged=false          3647ms
GeckoProbe CLEARED in 3648ms
```

Same challenge, same device, same network, same minute in which the app's WebView could not do it.

**Costs measured, not estimated** (debug builds, `geckoview 147.0.20260212191108`):

| APK | baseline | + GeckoView | + GeckoView, libs compressed |
| --- | --- | --- | --- |
| arm64-v8a | 37.0 MB | 212.1 MB | **123.2 MB** |
| armeabi-v7a | 37.0 MB | 176.5 MB | **118.4 MB** |
| universal | 40.6 MB | 522.9 MB | 275.5 MB |

Far worse than the ~45–60 MB per ABI Mozilla's Fx71-era figures suggest; do not quote those.

**Where it goes, and the one free win.** `lib/arm64-v8a/libxul.so` is **143.4 MB — 68% of the APK**,
and it is *stored*, not deflated (compressed size == raw size), so Android can mmap it straight out
of the APK. `packaging { jniLibs { useLegacyPackaging = true } }` deflates it and takes arm64 from
212.1 to **123.2 MB, a 42% cut, one line**, and the probe still cleared afterwards (1.79s). The
trade is real though: the library is then extracted at install, so *disk* use goes up even as the
*download* falls. For a sideloaded app whose users re-download the whole APK on every update,
download is the cost that matters.

Three further integration costs surfaced:

- **minSdk 22 → 26.** GeckoView declares 26, and its androidx uplift drags `savedstate-ktx` to 23.
  `tools:overrideLibrary` plus `SDK_INT` guards is the alternative, since pre-26 devices have no
  working pipe today anyway.
- **The androidx uplift breaks existing app source** — `lifecycle 2.8.7 → 2.10.0` changes Compose's
  `stickyHeader` signature and `IndexedSettingsContent.kt:49` stops compiling. The spike pins the
  app's versions and excludes GeckoView's; real adoption has to actually resolve this.
- **`Application.onCreate` is not multi-process safe**, and this is a latent bug worth fixing
  regardless. It runs in every process; GeckoView starts `:gpu` and `:tab*`; `ReleaseSyncScheduler`
  asks for WorkManager there and the child dies with *"WorkManager is not initialized properly"*,
  taking the engine with it. The symptom is a blank page and no load events, i.e. it looks exactly
  like a network failure. `MiruroApp` already has `currentProcessName()`/`isDiagnosticsProcess()`;
  the guard belongs there.

**Still to do before this is a fix:** the clearance has to come *out* of Gecko. There is no
`CookieManager` equivalent and `cf_clearance` is httpOnly, so `document.cookie` cannot see it — it
needs a built-in WebExtension with the `cookies` permission messaging the app. After that, §0.11
means the pipe traffic itself can stay on OkHttp and `PipeClient` as-is.

### 0.17 The clearance earner, built and working (spike)

`miruro-geckospike` now contains a working earner: `assets/pipe-clearance/` (an MV2 WebExtension)
plus `data/remote/GeckoClearance.kt`. Cold start, no debugger attached, reproduced on two mirrors:

```
GeckoClearance extension registered id=pipe-clearance@anilili
GeckoClearance port connected name=anilili
GeckoProbe CLEARED in 3689ms            (.bz)   /  3810ms (.to)
GeckoClearance returned 1 clearance cookie(s)   len=597
```

**Verified valid off-engine**, which is the whole premise: Node, plain non-browser TLS, same public
IP — `/health` and `/` both **200**.

Three details that cost real time and are not guessable:

1. **The cookie is partitioned.** Gecko's Total Cookie Protection stores it as
   `^partitionKey=(https,miruro.bz)` — visible directly in
   `files/mozilla/<profile>/cookies.sqlite`. `browser.cookies.get({url, name})` searches only the
   *unpartitioned* jar and finds nothing, which is indistinguishable from a permissions failure
   (`getAll({})` returned `[]` while `perms` showed everything correctly granted). Pass
   `partitionKey: { topLevelSite: "https://miruro.bz" }` — **registrable domain, no `www.`**.
2. **The clearance is bound to the user agent that earned it.** Same cookie, same IP:

   | UA sent | Result |
   | --- | --- |
   | `…rv:147.0) Gecko/147.0 Firefox/147.0` | **200** |
   | Chrome Android | 403 challenge |
   | none | 403 challenge |

   So the extension reports `navigator.userAgent` and the app pairs cookie+UA. Do not hardcode it;
   it changes with every GeckoView bump.
3. **The port name must match** `connectNative()` in background.js and
   `setMessageDelegate(delegate, name)` in Kotlin. A mismatch fails silently, with no error anywhere.

### 0.18 Still open: `/api/secure/*` is blocked to the off-engine client

The earner works; the *pipe* is not proven yet. With a valid clearance and the right UA:

```
/health              -> 200
/                    -> 200
/api/monkey          -> 410  {"error":"Gone"}      (reached the APP, through Cloudflare)
/api/secure/jwks     -> 403  [CF block page]
/api/secure/pipe?e=… -> 403  [CF block page]
```

Note the block page (`cf-mitigated` absent, `<!--[if lt IE 7]>`) is a *different* mechanism from the
challenge, and it does not move with `Referer`, `Origin` or `Sec-Fetch-*`. `/api/monkey` proves the
client is through Cloudflare, so this is a path-scoped rule on `/api/secure/*`.

### 0.19 ~~Resolved: the engine must host the pipe~~ — OVERTURNED, see §0.21

> The conclusion below ("earn a clearance, spend it from OkHttp does not hold for the pipe") was
> **disproven on 2026-09-09** by direct measurement, on device and on desktop: a valid clearance,
> the earner's exact User-Agent and a same-origin XHR header set get `/api/secure/pipe` to answer
> `200` from a plain non-browser TLS stack. What this section actually measured was that Node's
> *header shape* (and the content script's partitioned-jar isolation) gets the WAF block page —
> not that the path demands an in-page principal. §0.21 has the table.

The block is on the **client**, not the path. Measured side by side in one run, from the cleared
Gecko page — the page's own principal (`content.fetch`) against the content script's isolated world:

| | page principal | content-script world |
| --- | --- | --- |
| `/health` | 200 | 403 |
| `/api/secure/jwks` | **200** | 403 |
| `/api/secure/pipe` | **500** (`blocked:false`) | 403 |
| `/api/monkey` | 410 | 403 |

`jwks` returns 200 in-page and Cloudflare's block page to Node holding the identical cookie, so
§0.18's "path-scoped rule" reading was wrong. And the 500 on `pipe` is miruro's *application*
rejecting a hand-made envelope (`path:"/health"`, `version:"0.1.0"`) — not Cloudflare rejecting the
client. The transport works; the payload was junk.

**So "earn a clearance, spend it from OkHttp" does not hold for the pipe.** GeckoView has to host
it, doing the same-origin in-page fetch the WebView does today. That is the cheaper outcome: the
existing `PipeBridge` design ports across, with the JS bridge moving from `@JavascriptInterface` to
WebExtension port messaging, and the clearance-extraction extension becomes optional rather than
load-bearing.

**Two traps worth keeping.** A content script's `fetch` runs in an isolated world and 403s on
everything — including `/health`, which an outside client gets 200 on — because it does not carry
the partitioned clearance; a probe built on it proves nothing. Use `content.fetch()` for the page's
principal. And a content script's `fetch` will not resolve page-relative URLs at all (`"/health is
not a valid URL."`); pass absolute ones.

### 0.20 The pipe works on GeckoView — end to end, with real data

The spike in `miruro-geckospike` now runs the pipe inside a cleared Gecko page. `GeckoPipe.kt` and
the extension's `content.js` between them present **the same contract as `PipeBridge.fetch`** — the
base64url envelope in, the raw `{ok,status,obf,body}` JSON string out — so `PipeClient` needs no
change at all. Only the engine underneath differs.

Measured with a genuine envelope (`path:"episodes"`, `anilistId:199111`, no leading slash):

```
GeckoPipe pipe-ready pageFetch=true
PIPE ok=true status=200 obf=1 bodyLen=9772
PIPE DECODED chars=33164 keys=[mappings, providers]
             providers=[ally, pewe, bee, kiwi, hop, bonk]
```

The body was decoded the way `PipeClient` decodes it (base64url, then gunzip; the XOR step is only
for `obf=2`) and contains exactly the shape the caller consumes — including the provider names this
document opens by listing as missing (§1). A 200 alone would not have shown that.

**Four traps, one run each, none guessable from the docs:**

1. **A content script's port arrives through the *session*, not the extension.**
   `extension.setMessageDelegate(delegate, name)` covers only the background script;
   `session.webExtensionController.setMessageDelegate(extension, delegate, name)` is what answers a
   content script's `connectNative`. Register only the first and the port is never answered, with no
   error on either side.
2. **The native-app name must match `^\w+(\.\w+)*$`.** `anilili-pipe` is rejected at runtime because
   a hyphen is not a word character, and the error appears *inside the content script* — the app
   simply sees a port that never connects.
3. **`ensureBuiltIn` upgrades by manifest `version`, not by file content.** Editing the extension
   without bumping the version leaves the previous code running in the Gecko profile.
4. **`onDisconnect` must check port identity.** An upgrade connects the replacement before the old
   port drops, so an unconditional `port = null` kills the live channel.

The technique that made this tractable is worth keeping: a content script that cannot open its own
port has no way to report why, so have it `browser.runtime.sendMessage` its diagnostics through the
background script, which already has a working one. That is what produced the exact regex error
above instead of another round of guessing.

### 0.21 The pipe spends from plain TLS — the law of this zone, measured (2026-09-09)

A day of single-variable measurement (harnesses committed in `scripts/`, all with interleaved
controls) that settles what §0.18/§0.19 could not, because neither had held a *valid* clearance in
a client with *browser-shaped headers*:

**The spend law.** `/api/secure/pipe` answers `200` to any client that presents

1. a `cf_clearance` earned by a real engine on **that exact host** (`.bz`'s cookie is refused on
   `.to` — per-host, not per-zone-family),
2. the **exact User-Agent that earned it** (any other UA, including none, is challenged), and
3. a same-origin XHR header set — `Referer: {origin}/`, `Sec-Fetch-Site: same-origin`,
   `Sec-Fetch-Mode: cors`, `Sec-Fetch-Dest: empty`, plus an `Accept`/`Accept-Language`.

**The TLS fingerprint is not part of the law.** The control arm — curl_cffi with *no*
impersonation, libcurl's handshake — returned `200` with the full provider list
(`ally, bee, bonk, hop, kiwi, pewe`, byte-identical length to §0.20's GeckoView result). Plain
OkHttp qualifies. The `/api/secure/*` **block page** of §0.18 is a header-shape rule (missing
Referer/Sec-Fetch or no User-Agent), not a client-identity rule; §0.18's Node client failed on
its headers, not its handshake.

**Proven on device (S25), not just desktop:** a debug-only probe (removed after measurement,
§8.4 pattern) waited for the Gecko walk's serving page, took its cookie via `GeckoClearance`,
and spent it from a stock `OkHttpClient()` — `200`, decoded providers, repeatable 15 s later;
no-cookie and no-UA controls on the same origin answered `403 challenge`. Two traps it hit,
both worth recording:

- **Gecko's jar persists clearances across launches.** `clearanceFor` returns cookies for every
  mirror ever walked, and only the cookie minted by the *currently serving* page is spendable —
  the first probe run spent two stale ones and measured `403` on everything, which looked exactly
  like the law being false. Gate on the serving page's origin, not on the jar's contents.
- **`GeckoClearance.userAgent` is only set by a `clearanceFor` reply.** Waiting on the UA before
  asking for cookies deadlocks.

**The fingerprint thesis is dead for *earning*, too.** ja3proxy and tls-forge both claim a
real-browser ClientHello + matching UA passes Cloudflare without a cookie; on this zone it does
not: curl_cffi `chrome` (JA4 `t13d1516h2_8daaf6152771_d8a2da3f94cd`, self-checked against
tls.browserleaks) with the full XHR header set gets `403 cf-mitigated: challenge` on every path,
every mirror. The exact recipe the public Miruro-API projects ship (`chrome110`, their header
set, `www.miruro.tv`) was replicated verbatim and answered `403 challenge` on `.tv`, `.to` and
`.bz`. Their approach predates the current hardening. **Earning still requires a real engine** —
nothing measured here can mint.

**The site's own code has no side door.** Live bundles pulled with an earned clearance
(`miruro_client_dump.py`): `env2.js` names playback relays (`VITE_PROXY_A/B` =
`s1.watami.win`, `s1.piltover.li`, XOR-base64url of the media URL + referer) that proxy **media
only** — discovery stays on the pipe. `strm.cx` is a renderer the page pushes sessions into. The
footer's fifth mirror `www.miruro.cx` is challenged like the rest. `www.miruro.com` and
`status.miruro.com` are SPA catch-alls without the API (`/health` is the one wired path on
`.com`). The public repo's server directory holds only token-exchange functions; `/health`'s
response headers (`cfOrigin;dur=5`, no origin hints) read as a Cloudflare Worker reachable only
through the zones.

**One more for §8.1's collection:** headed desktop Chrome, fresh profile, *CDP-attached from the
start*, sat on "Just a moment..." for 90 s and never cleared — the same Chrome, same IP, same
hour cleared in under 45 s once left completely unattached until after the challenge window.
Devtools detection is not a WebView-only contaminant; attach to read, never to judge.

**Consequence for the architecture:** the engine is needed only to *mint*, and nothing else —
the entire data path can be plain OkHttp, and a mirror's clearance needs re-earning only when a
spend comes back challenged. Whether the earner stays GeckoView (lazy, mounting only on a refused
spend) or goes away entirely (sources then available only while the zone is not challenging,
which on 2026-09-09 was never and on 2026-09-08 was always) was still an open decision when this
was written.

### 0.22 Decision closed: the earner goes away entirely (2026-09-10/11)

§0.21's open question is settled. GeckoView is gone, and the pipe runs on plain OkHttp.

**The re-measurement** (`WebViewMintProbe`, commit `58225497`, since deleted along with its
`MainActivity` hook — this section is the record it was kept for). Debug builds only and only on an
intent extra, because it takes the zone to itself: running Gecko alongside would put a second engine
on the same mirrors, and the question was what an engine-free client manages unaided. Three arms per
mirror, because §0.11's fifteen placeholder cookies proved a jar is not a result, so the oracle was
§0.21's spend law:

- **A** — OkHttp spending whatever the WebView minted,
- **B** — OkHttp spending **nothing at all**, §0.21's control, which used to 403,
- **C** — the in-page `fetch()`, i.e. the production path, as a positive control.

**Result: the zone was not challenging at all.** The question stopped being "can the WebView mint"
and became "does anything need to". Raw logs kept at `Downloads/_diag/mint*.jsonl`:

```
MintProbe .to  titleCleared=true cf_clearance=false retryMarker=false waitedMs=5000
MintProbe SPEND-A .to cookie=…  skipped: nothing minted
MintProbe SPEND-B .to nocookie  status=200 cfMitigated=none bodyLen=3876667 bodyHead=H4sIAAAAAAAAA8y9W69dx5Em-FcKfJZj…
MintProbe SPEND-C .to inpage    status=200                 bodyLen=3876667 head=H4sIAAAAAAAAA8y9W69dx5Em-FcKfJZj…
MintProbe SPEND-B .tv nocookie  status=200 cfMitigated=none bodyLen=3874419 bodyHead=H4sIAAAAAAAAA8y9W69dx5Em-FcKfJZj…
```

Four things worth reading off that, none of which the commit message recorded:

- **Arm A never ran on any mirror.** Not "minted a placeholder" — `cf_clearance=false` and
  `retryMarker=false` everywhere, on every run. There was nothing to mint because nothing asked.
  §0.11's fifteen placeholder cookies were a property of a *challenging* zone, not a constant.
- **Arm B and arm C are byte-identical**, same length *and* same body prefix, so the engine-free
  client is getting the real gzip envelope (`H4sI…` is gzip magic) rather than a decoy or a block
  page dressed as a 200.
- **`cfMitigated=none`** — Cloudflare was not mitigating the request at all, which is a stronger
  statement than "we got through".
- **`.tv` corroborates `.to`** at `bodyLen=3874419`, so this is not one lucky mirror.

**Which device measured what, because it is not the obvious answer.** The four-mirror runs are the
S25 (WebView **152**.0.7977.66) and they only establish that the page loads with no clearance —
their spend arm is always `skipped: nothing minted`. The three-arm spend evidence above is from the
**TV emulator** (`sdk_google_atv64_x86_64`, WebView **143**.0.7499.24). So the 200s were earned on
an older WebView on an emulator, and the phone's own spend evidence comes from ordinary use instead:
`OkHttpPipe serving from https://www.miruro.to` on the S25, re-confirmed 2026-09-11 against a
previously uncached episode.

**The instrument bug, because this file exists to record them.** Handing `evaluateJavascript` an
async IIFE returns the *Promise*, which serialises as `"{}"` — indistinguishable from a successful
empty body. The first run recorded `{}` on all four mirrors and proved exactly nothing. The fix
parks the answer on a global and polls for it. Nothing attached over CDP either (§0.16: devtools
detection contaminates any measurement of challenge *outcome*), and the tab was laid out at the
device's own window and merely clipped, never at 1dp, or it would have measured §0 again.

**Corroboration, which is stronger than the probe.** Six tester bundles on `0.1.77-nogecko`
(2026-09-10, five devices + a TV): **not one 403 fleet-wide**; every pipe rejection was a `444` on
an `:hsub` request, and Miruro resolves came back in 100–600 ms. One phone A/B'd both builds on the
same day and network: the WebView pipe never cleared Miruro once in ten hours and logged zero
playback, the OkHttp pipe served full catalogues and five 1080p sessions. On 2026-09-11 the 13
id/episode/variant combos those testers exercised were replayed on a Galaxy S25 and the TV
emulator — 26 runs, every catalogue served, no 403 anywhere.

**Caveat, stated plainly.** This closes the decision on *current* evidence, not permanently. §0.21's
own framing was that the zone is sometimes challenging and sometimes not — "on 2026-09-09 never and
on 2026-09-08 always". Dropping the earner means sources are available only while the zone stays
open. The fleet and replay evidence says it has stayed open across three days and six devices; if
that changes, the failure mode is a `403` on a spend, and §0.17's earner spike is the thing to
reach for.

### 0.10 Evaluated and rejected: package-name spoofing (Mihon PR #1812)

[mihonapp/mihon#1812](https://github.com/mihonapp/mihon/pull/1812) (merged 2025-03-02) is the
best-known Android fix in this space, and it does not help here. It is a **privacy** change — stop
leaking the package name — and never mentions Cloudflare. Two parts, both dead ends for us:

- **Spoof the package** via a `getPackageName()` override so the header reads `com.android.chrome`.
  Measured against this zone, controls interleaved: `com.android.chrome` → **WALL**,
  `com.android.settings` → **WALL**, controls **CLEARED** 3/3. It changes the value, and the value
  is not what is matched.
- **`shouldInterceptRequest` + OkHttp** to drop the header outright. The PR author notes it "isn't
  called for post or xhr/fetch calls in js" — and the pipe *is* an in-page `fetch()`, so the one
  request class we need is the one it cannot reach. An OkHttp-served page could not solve the JS
  challenge either.

### 0.8 There is no API left to turn it off

Not a stale dependency and not a wrong check: the app is on `androidx.webkit 1.14.0` and calls
`WebViewFeature.isFeatureSupported(REQUESTED_WITH_HEADER_ALLOW_LIST)` correctly. **Chromium rolled
the removal back.** Per the [Intent to Ship thread](https://groups.google.com/a/chromium.org/g/blink-dev/c/Z70s8CQ8PbU),
the experiments were ended because no sufficient alternative could be provided for abuse- and
fraud-prevention use cases. The allow-list API was the opt-in half of that rollout, so with the
rollout reverted the feature reports unsupported and the header ships unconditionally.

Nor can it be intercepted. `shouldInterceptRequest` cannot touch main-frame navigation headers, and
the header is added *below* even CDP's Fetch layer — measured: `Fetch.requestPaused` sees only
`Accept, Upgrade-Insecure-Requests, User-Agent, sec-ch-ua*`, while the wire carried
`X-Requested-With` on all 17 requests of that run.

**So the header is a property of `android_webview/`, and the only way out is not to use it.** Note
this is a WebView-layer behaviour, not a Chromium-wide one — which is why every WebView provider
adds it (swapping providers is not an escape) while a full Chromium browser build does not.

### 0.9 Options, with costs

| Option | Verdict |
| --- | --- |
| Ask miruro's operator to exempt the header in their WAF | **Cheapest by far, try first.** One rule; costs nothing to ask. Contact is the Discord in their README |
| GeckoView (`org.mozilla.geckoview`) | Works in principle — no `X-Requested-With`, and Firefox's own NSS fingerprint. Costs **~45–60 MB per ABI** ([Mozilla's own `geckoview_example` measurements](https://bugzilla.mozilla.org/show_bug.cgi?id=1591725), a lower bound from the Fx71–75 era) on a 38 MB sideloaded APK, plus porting the JS bridge off `@JavascriptInterface` onto WebExtension port messaging |
| Manual `HttpURLConnection` + `loadData` | **No.** `loadData` gives an opaque origin, which kills the same-origin `fetch()` the pipe is built on, and the request would carry a non-browser TLS fingerprint — the exact thing §3.1 measured as walled |
| A de-Googled Chromium browser (e.g. BareBrowser) | Not embeddable — it is a browser *application*, not an engine library. Useful only as confirmation that non-WebView Chromium omits the header |
| Drop the pipe, keep Anivexa | Always available; costs the 12 miruro providers |

## 1. Symptom

The source sheet offers only Anivexa providers — AniBD, Anikoto, KickAssAnime, MKissa
(AllAnime), AnimeKai, Reanime. Every **miruro pipe** provider is absent:

```
bonk, kiwi, pewe, bee, ally, moo, hop      (native HLS)
nun, bun, twin, cog, telli                 (iframe embeds)
```

Reported as "no miruro sources, kiwi as well is not showing, but I know they all have it".
Kiwi is not a separate fault — it is one of the twelve, listed in
`ProviderCatalog.miruroOrder`.

The catalogue side is healthy. From the original bundle: 16 Anivexa providers asked, 7
answered in 15s, all reporting `sub=22`. Miruro's episode list never arrived
(`Watch miruro episodes still pending after 8000ms`).

## 2. ~~Root cause~~ — WITHDRAWN. The experiment measured the instrument (see §0.14)

> This section, and the §0.7 "replication" that appeared to confirm it, are both **wrong**. Every
> arm injected the header with CDP `Network.setExtraHTTPHeaders`, which adds it *above* the CORS
> layer; a non-safelisted header name then forces preflight `OPTIONS` on the challenge's
> cross-origin requests and breaks the challenge itself. Injected at the **network** layer, where
> Android WebView actually stamps it, the challenge clears — 162/162 requests carrying the header,
> cleared in 4.38s, twice. The header is not the gate.

**Android WebView stamps `X-Requested-With: <package name>` on every request it makes, and
miruro's Cloudflare zone challenges any request carrying that header.**

Proven on a desktop Chrome against the live site. One variable, a fresh browser context per
arm (so a failed challenge cannot poison the next), UA and Client Hints never touched,
control arms interleaved on both sides of every treatment:

| Arm | `X-Requested-With` | Result |
| --- | --- | --- |
| 1, 3, 7 | *absent* | **PASS** ×3 |
| 2, 8 | `com.miruronative.debug` | WALL |
| 4 | `com.miruronative` | WALL |
| 5 | `XMLHttpRequest` | WALL |
| 6 | `""` (empty) | WALL |

**The value is irrelevant; presence is what is matched.** An empty header is still a header,
so blanking it is not a fallback — it has to be *absent*.

Confirmed on the wire from the app's own WebView over DevTools:

```
x-requested-with: com.miruronative.debug
sec-ch-ua: "Chromium";v="152", "Not?A_Brand";v="24", "Android WebView";v="152"
--- response ---
403  cf-mitigated: challenge
```

### Why the existing suppression does not help

`PipeBridge.attach` calls `WebSettingsCompat.setRequestedWithHeaderOriginAllowList(…,
emptySet())`. It has **never executed on a current device**:

```
PipeBridge X-Requested-With suppression supported=false allowList=[n/a]
```

`WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST` is not supported on WebView
152.0.7977.66 — Google withdrew the API. Nothing logged that, which is how a comment came
to record "tried it, did not move the challenge" from a test that never ran. The reading is
now logged on every attach rather than assumed.

## 3. What was eliminated, with evidence

Do not re-run these.

| Hypothesis | Test | Result |
| --- | --- | --- |
| IP / network reputation | Same phone's Chrome, same wifi | Passes instantly — innocent |
| Chromium version skew | WebView `152.0.7977.66` vs Chrome `152.0.7977.75` | Same branch — innocent |
| `Sec-CH-UA: "Android WebView"` | Spoofed UA **and** full client-hint metadata to real Chrome via CDP | Still walled, 17s |
| ~~4×4px hidden viewport~~ | Overrode to 412×915 | **WRONG — see §0.** Run against `.ru`, the one mirror that is blocked at any size, and generalised to the zone. On `.bz` the same override cleared in 3.19s |
| `window.AndroidPipe` JS bridge | Deleted at document-start, verified `undefined` | Still walled |
| App's URL pin blocking the challenge | Full network trace | Nothing blocked; Turnstile iframe loads, all `/cdn-cgi/challenge-platform/` XHRs 200 |
| Poisoned cookie jar | Fresh isolated profile (`isolated=true`, `clearance=false cookies=1`) | Still walled on all 4 mirrors |
| Skip the navigation, use XHR only | Same-origin `fetch()` from *inside* the interstitial | `/health`, `/api/secure/jwks`, `/api/monkey` all `403 cf-mitigated: challenge` |
| Human solves the challenge | 120s budget, 8 re-arms, 48s of DOM sampling | **No widget exists** (§5) |

### 3.1 Transport matrix — ~~two signals~~ ONE signal (corrected 2026-09-08, see §0.11)

> **The "second signal" does not exist.** There is no TLS-fingerprint gate on *spending* a
> clearance: a browser-earned `cf_clearance` replayed from plain Node — same IP, same UA, no
> `X-Requested-With` — returns **200**. What every arm below actually measured is that a
> non-browser client cannot *earn* a clearance, because it cannot run the challenge's JavaScript.
> That is a statement about JS execution, not about fingerprinting. The arms are still correct as
> results; the conclusion drawn from them was not.

The zone was believed to challenge on two *independent* signals: the header, and a non-browser TLS
fingerprint (a plain client with clean headers is also walled).

| Transport | Header | Fingerprint | Result |
| --- | --- | --- | --- |
| WebView | ✗ always sends it | ✓ Chrome | WALL |
| OkHttp / Node | ✓ absent | ✗ | WALL |
| **Cronet** | ✓ absent | ✗ | WALL |

Cronet was the one with a real chance — Chromium's own network stack, no WebView layer to
stamp the header — and it was measured, not assumed. Play Services Cronet
**151.0.7922.83**, four header sets (bare defaults → Chrome UA → + Client Hints →
XHR-shaped `Sec-Fetch-*` + Referer + Origin), over both h2 and h3:

```
arm=cronet-default    /health           403 cf=challenge
arm=cronet-default    /api/secure/jwks  403 (Cloudflare block page)
arm=chrome-ua         /health           403 cf=challenge   proto=h3
arm=chrome-ua+hints   /health           403 cf=challenge   proto=h3
arm=xhr-same-origin   /health           403 cf=challenge   proto=h3
arm=xhr-same-origin   /api/secure/jwks  403 cf=challenge   proto=h3
```

Every arm walled. Chromium's net stack is not Chrome's fingerprint (no ALPS, different
GREASE and extension ordering). The `cf=-` responses were Cloudflare's block-page template
(`<!--[if lt IE 7]>`), **not** the application refusing — a red herring worth not chasing again.

The probe used to produce this was temporary and has been removed; §8.3 says how to rebuild it.

## 4. miruro's current client — nothing changed that we can use

Reviewed the live bundles (`index-2p1dlyrV.js`, `links-BM522CrC.js`, `info-B4JAXtLR.js`,
`sw.js`, strm.cx `client.js` / `embed`):

- **The pipe protocol is untouched.** `GET /api/secure/pipe?e=<base64url(envelope)>`,
  envelope `{path, method, query, body, version}`, response deobfuscated per the
  `x-obfuscated` header (`2` = XOR then gzip). Plus a POST/JWE path (ECDH-ES + A256GCM) for
  non-GET. Plain `fetch()`, **no custom headers, no token** — there is nothing their client
  sends that we do not.
- **No alternate API origin.** Every third-party host in the bundles is AniList, MAL,
  `status.miruro.com` or an ad network. Everything else is same-origin `/api/…`. There is no
  WAF-free backdoor.
- **The player is now a strm.cx embed** (`<strmcx-embed>` custom element, `strmcx/v1`
  postMessage protocol over a MessageChannel). Direction is wrong for us: miruro's page
  resolves the source and *pushes* it into the embed via `set-session`. strm.cx is a
  renderer, not a source.
- The envelope contains **no JS-derived secret** — it is base64url of a plain JSON object,
  and `version` comes from the jwks response header. This is why a non-WebView transport was
  worth trying at all, and it remains true if the block ever lifts.

## 5. The human check is unwinnable — no interactive control is ever offered

`MAX_HUMAN_CHECK_ATTEMPTS = 2` meant the challenge was taken off screen after ~7 seconds,
which made every previous "a human cannot pass it" claim unfounded. Retested properly with a
120s budget:

```
attempt=3  remainingMs=112521  clearance=false cookies=1
attempt=4  remainingMs=107694  clearance=true  cookies=2
attempt=5  remainingMs=100589  clearance=true  cookies=2
attempt=8  remainingMs=53784   clearance=true  cookies=2
```

Cloudflare grants a fresh `cf_clearance` and refuses it on the very next request, repeatedly.
It never escalates to an interactive challenge. DOM sampled every 4s for 48s on the surfaced,
full-size tab:

```
  0s  cfFrames=0  inputs=1  title=Just a moment...
 ...
 44s  cfFrames=0  inputs=1  title=Just a moment...
```

No `challenges.cloudflare.com` frame across that window, and the single `input` is
Cloudflare's hidden form field — nothing a person can click. The user reported exactly this:
"it's showing verifying and disappearing several times… but it's not letting me click on the
tick". Each disappearance is a reload.

**Do not read that as "Turnstile never runs against us" — it does.** An earlier network trace
of the *hidden* tab caught the full loader flow:

```
REQ  Script    challenges.cloudflare.com/turnstile/v0/g/330e41bb475c/api.js?onload=…&render=explicit
RESP 200
REQ  Document  challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g/turnstile/f/av0/rch/p5gsn/0x4AAAAAAADnPIDROrm
--- frame tree ---
FRAME  https://www.miruro.ru/
FRAME    challenges.cloudflare.com/cdn-cgi/challenge-platform/…/rch/…/0x4AAAAAAADnPIDROrm
```

Sitekey `0x4AAAAAAADnPIDROrm`, the `/rch/` orchestration endpoint, the frame — all present, all
200. The challenge platform executes; it stays on its non-interactive path, never escalates to
something a person can answer, and the page 403s on the next navigation regardless.

So the precise claim is: **the interactive path is never offered**, not that the widget
infrastructure is absent. That is what makes the human check unwinnable, and it is also why
**minima's approach cannot work** (§6) — it waits 150s for a human to answer something that is
never put on screen.

### 5.1 The challenge is not the gate — we already pass it

Worth stating plainly, because it forecloses every "…and then you have a valid token" idea.

This is a **managed challenge**, not a site-embedded Turnstile widget. The captured challenge
page says so: `cType: 'managed'`, `cZone: 'www.miruro.bz'`. There is no `cf-turnstile-response`
form field and no `siteverify` round trip — that is the integration product, and it runs on the
*site operator's* secret key, which is miruro's, not ours. In a managed challenge Cloudflare's
edge is both issuer and verifier, and the artifact it produces is the `cf_clearance` cookie.

**We already get that cookie, over and over** — `clearance=true cookies=2` on attempts 4, 5, 6,
7 and 8 above — and it is refused on the very next request every time. So the challenge output
is not what we are missing. Any approach whose deliverable is "a solved challenge" delivers
something we have had in hand the whole time.

Combined with §3: the 403 also lands on plain API XHR where no challenge is in play at all.
The rule is on the request, not on the challenge.

## 6. minima (`C:\Users\Admin\Documents\minima`) — nothing to port

Evaluated at the user's request. Same architecture as ours: headless WebView on
`https://www.miruro.bz`, JS bridge (`__minimaBridge`), same-origin `fetch()`. Its own comment
reaches our conclusion: *"OkHttp alone is fingerprint blocked on the /api/secure endpoints."*

- **No `X-Requested-With` handling anywhere** — grepped the whole tree, zero hits. Its
  WebView sends `com.minima.debug` and hits the identical wall.
- Its `CfChallengeActivity` polls for the cookie and **completes as soon as `cf_clearance`
  appears, without checking the page cleared** — precisely the grant-then-refuse trap. Its
  challenge handling is weaker than ours, not stronger.
- It could not be tested end-to-end: its AniList client omits the `Referer` header, and
  AniList answers `403 "temporarily disabled"` without one (verified: no Referer → 403, with
  Referer → 200). Its home never loads, so its miruro code never runs. That is a minima bug,
  unrelated to this one, and was left alone.

## 7. Work remaining

### 7.1 Decide the fate of the uncommitted human-check budget

`PipeBridge.kt` currently has an **uncommitted** change replacing `MAX_HUMAN_CHECK_ATTEMPTS`
with `HUMAN_CHECK_BUDGET_MS = 120_000` plus a `humanCheckExpiry` timer. It works as designed,
but **must not ship alone**: it parks a card in front of the viewer for two minutes that
provably cannot succeed.

Recommended: keep it, and add **widget detection** — on surfacing the check, look for a
`challenges.cloudflare.com` frame; if none appears within ~10s, close it with an honest
reason. That turns "Unlock more servers" from a dead button into one that either offers a
real widget or says there is nothing to answer. The probe JS is a one-liner:

```js
Array.from(document.querySelectorAll('iframe'))
  .filter(f => (f.src || '').includes('challenges.cloudflare.com')).length
```

Alternative: revert the budget change entirely and take only 7.2.

### 7.2 ~~Gate the pipe on `supported=false`~~ — DO NOT DO THIS

Withdrawn. It rested on §2 being right, and §2 is disproven (§0.4). The header is not the gate, so
`WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST` reporting unsupported predicts nothing — it is
unsupported on every current WebView. Shipping this would have permanently disabled the pipe on
every device for a reason that was never true, and it would have looked like it worked, because the
pipe was failing anyway.

The cost it was trying to avoid is real (**~36s to give up, `resolverpipeLastMs=70153`, an 8s stall
in front of the episode list**) and is worth revisiting — but it has to be gated on something
actually measured, such as consecutive challenge failures per mirror.

## 8. Diagnostic harnesses (these took effort; reuse them)

### 8.1 CDP into the app's own WebView over adb

The pipe WebView is inspectable on debug builds. This is how most of §3 was measured.

```bash
PID=$(adb -s <serial> shell pidof com.miruronative.debug | tr -d '\r')
adb -s <serial> forward tcp:9700 localabstract:webview_devtools_remote_$PID
curl -s http://127.0.0.1:9700/json/list          # find the page target id
```

Then drive it from Node 22 (global `WebSocket`, no deps) against
`ws://127.0.0.1:9700/devtools/page/<id>`. Useful domains: `Network.enable` +
`Network.requestWillBeSentExtraInfo` (**the only way to see the real wire headers** —
`Fetch.requestPaused` does not show `X-Requested-With`, it is added below that layer),
`Network.getCookies`, `Emulation.setUserAgentOverride` (this *can* set client hints, which
`WebSettings` cannot), `Page.addScriptToEvaluateOnNewDocument`.

Caveats: the DevTools endpoint dies when the WebView is released — relaunch the app and
re-forward. Cloudflare's challenge runs devtools-detection (`console.table` / `%c%d` getter
probes), so an attached debugger is itself a bot signal; treat attached-session results as
indicative and confirm unattached where it matters.

### 8.2 Single-variable A/B against the live site (desktop Chrome + CDP)

The clean-room harness that produced §2. Key requirements, all of which mattered:

- **Headed Chrome, not `--headless=new`** — headless is detected and both arms wall,
  destroying the control.
- **A fresh `Target.createBrowserContext` per arm** — otherwise a failed challenge poisons
  the shared cookie jar and every later arm walls.
- **Never spoof a mobile UA on desktop Chrome** — a mobile UA with a desktop viewport is its
  own tell and walls the control.
- **Run the control on both sides of every treatment** to prove the IP stayed clean.

```bash
"/c/Program Files/Google/Chrome/Application/chrome.exe" \
  --remote-debugging-port=9501 --user-data-dir=<temp> --no-first-run about:blank
```

### 8.3 Reading diagnostics off-device

```bash
adb -s <serial> shell run-as com.miruronative.debug \
  cat files/diagnostics/events-main.jsonl | grep -o "PipeBridge [^\"]*" | tail -30
```

Note URLs, titles and slugs are redacted in the log; `title=<redacted> a moment...` is
"Just a moment...".

### 8.4 Rebuilding the Cronet probe

Cronet is already a dependency (`play-services-cronet`, `media3-datasource-cronet`) and
`CronetUtil.buildCronetEngine(context, ua, true)` is proven on device in `PlaybackService`.
The probe was a `CronetPipeProbe` object invoked from a `BuildConfig.DEBUG` branch in
`MiruroApp.onCreate` via `startupScope`, requesting `/health` and `/api/secure/jwks` per
header arm and logging `status`, `cf-mitigated`, negotiated protocol and a body prefix. Both
probe and hook were removed after measurement.

## 9. Evaluated and ruled out

### 9.1 Turnstile solver services

They do not address the failure. The block is a deterministic rule on `X-Requested-With`
evaluated on every request (§2), including plain API XHR that involves no challenge at all
(§3). Solving a Turnstile instance does not change what the next request carries — and per
§5.1 we already hold the `cf_clearance` a solved challenge produces, and it is refused anyway.

### 9.2 The Turnstile fingerprinting surface

A reverse-engineering writeup of what Turnstile measures
(`CircuitSavage/cloudflare-turnstile-reversed`, `docs/03-fingerprinting.md`) was reviewed.
Recording the outcome so it is not reopened.

**What it explains about our data — genuinely useful:**

- Its network-layer section names the mechanism behind §3.1's Cronet result. Cloudflare
  computes JA3/JA4 from the ClientHello during the handshake, and JA4 sorts extensions
  specifically to survive Chrome's ordering randomisation. Cronet is Chromium's net stack but
  not Chrome's ClientHello, so it hashes differently. That closes the loose end in §3.1 —
  "Chromium's net stack is not Chrome's fingerprint" now has a mechanism, not just a result.
- Its automation-tells section confirms the loader probes `Function.prototype.toString` and
  error `stack`, which is why the CDP-attached eliminations in §3 are marked indicative
  rather than airtight (§8.1). An attached debugger is itself a signal.

**Why it is not an avenue:**

The document describes a *scoring* surface — canvas, audio, `Event.isTrusted`, mouse entropy,
`navigator` coherence — feeding a 1–99 bot score, where the game is looking more human. Our
block is not that shape:

- **It is deterministic, not probabilistic.** Same browser, same TLS, same IP, same cookie
  jar, back to back: header present → wall, header absent → pass, ×5 and ×3 interleaved
  (§2). A score does not flip that cleanly on one header whose value is ignored. This is a
  WAF rule matched *before* the challenge platform runs.
- **A solved token would not be spent on anything.** Everything in that document describes
  signals the Turnstile runtime collects and packages into a `cf-turnstile-response` token.
  Turnstile does run against us (§5), so a token is at least conceptually obtainable — but the
  403 lands on plain API XHR with no challenge in play (§3), so there is no exchange in which
  presenting a token changes the outcome. The rule is on the request, not on the challenge.

So the axis it optimises is orthogonal to a request that is 403'd on a header. This also
sharpens the wording used elsewhere in this file: the "two independent signals" of §3.1 are a
**rule** on the header and a **separate transport-layer judgement** on TLS. Neither is
reachable by making the client look more human, which forecloses that whole category rather
than leaving it as "we did not try hard enough".

The remaining route is asking miruro's operator to allow app clients; they are reachable via
the Discord in their README.

## 10. Commits from this investigation

| Commit | What |
| --- | --- |
| `cff2a0b8` | Pipe gets its own cookie jar (`MULTI_PROFILE`) so a refused clearance can actually be dropped — `CookieManager.setCookie` cannot delete the httpOnly `cf_clearance`, so `forgetCookies` had been a no-op for its whole life |
| `7de6dad1` | Records that the `X-Requested-With` suppression never runs (`supported=false`), and corrects the unsound comment above it |
| `3740d461` | Records the transport matrix, the Cronet measurement, and that XHR is challenged too |

None of them fix the wall. They fix a real latent bug (the clearance purge) and stop the
codebase asserting things that were never measured.

## 11. Environment

Samsung SM-S938B (Galaxy S25), Android 16 / SDK 36, `com.miruronative.debug` 0.1.77-debug.
WebView 152.0.7977.66, Chrome 152.0.7977.75, Play Services Cronet 151.0.7922.83.
Mirrors tried, all challenged: `www.miruro.to`, `.tv`, `.bz`, `.ru`.
