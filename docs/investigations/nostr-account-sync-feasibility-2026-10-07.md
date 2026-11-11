# A Nostr account for syncing everything: feasibility (2026-10-07)

Question: can the phone, TV and desktop apps keep *all* their state in step through Nostr — not just
what AniList/MAL hold (list status, episode count), but settings, Continue watching, resume points,
custom lists, search history, sign-ins — with a Nostr key as the "account"?

**Verdict: feasible, and a better fit than it first sounds.** Both apps already speak Nostr, already
have a document that describes "everything about this app" (the backup), already sort settings into
"travels" and "stays on this device", and already merge history by timestamp. What is genuinely new
is three things: per-device keys with a way to move one between devices, encryption to self (NIP-44),
and publishing (today both apps only *read* Nostr). The real risks are operational — relay
retention and key loss — not technical.

## 1. What exists today (the parts that carry over)

| Piece | Android (phone + TV, one codebase) | Desktop (Tauri/Rust) |
|---|---|---|
| Nostr signatures | `secp256k1-kmp` 0.19 (`NostrUpdateSource`, `AnnouncementSource`) — the library also **signs** | `k256` with `schnorr` (`crates/core/src/update.rs`) — also signs |
| Relay client | OkHttp WebSocket, read-only, 4 relays (damus, nos.lol, nostr.band, primal) | none — reads the HTTP mirror only |
| "Everything" document | `BackupDocument`: settings, watchlist, history, search history, custom lists, sealed credentials | `backup.rs` reads/writes the same format (parity fixture `backup-sealed.json`) |
| Settings taxonomy | `BACKUP_*_KEYS` (≈90 keys travel) vs `nonBackupKeys` (8 stay: own backdrop file, video cache size, external player, form factor…) | maps ~79 Android keys onto its own `Settings` |
| Secrets sealed for travel | `BackupCrypto`: PBKDF2-SHA256 (210k) + AES-GCM over AniList/MAL/OpenSubtitles logins | same format, read and written |
| Merge by time | `mergedHistory` — newer `updatedAt` per title wins (backup restore and account sync) | `progress.updated_at`, library `updated_at` + `synced` flag |
| Moving a secret phone → TV | "Link a TV" / "Use my phone" LAN pairing (`data/pairing/*`, encrypted handshake) | pairing protocol is a shared contract |

Sizes are small. On the test phone today: history 23 KB (36 titles), taste ratings 2.4 KB, account
snapshot 1.6 KB, watchlist 1 KB, custom lists < 100 B; settings well under 10 KB. Encrypted and
base64'd, every domain fits one event under the usual relay limits (64–128 KB).

## 2. What Nostr would add over AniList/MAL sync

From today's live test (`tmp/synctest`, phone + TV emulators + desktop on one AniList account),
these did **not** cross devices and would with a Nostr account:

- the **resume point** inside an episode (phone paused at 6:41 → TV offered 0:00);
- an episode **started but not finished** (nothing reaches AniList below 80%);
- **Continue watching** itself (each device builds its own; desktop only shows titles played on it,
  the phone held 36 rows and the TV 27);
- removing a Continue watching card, the saved watchlist ("Saved" drifted between phone and TV);
- **settings** — theme, subtitles style, languages, server order, player gestures, Home layout…;
- search history, taste ratings / dice feedback (recommendations), reminders, notification read state;
- **sign-ins**: sign in to AniList/MAL/OpenSubtitles once, every device has it (sealed);
- everything for people who use **no** AniList/MAL account at all — today they get no sync.

AniList/MAL stay authoritative for what they own (status, episode count, score, AniList custom
lists). Nostr carries the rest, and the two never fight over one field.

## 3. Proposed design

**Identity.** The account is a Nostr keypair (secp256k1), created on the first device. No server,
no email, no password database — which is the point, and matches why updates and announcements
already ride Nostr (they survive the domain, GitHub and the APK host going away).

**Storage.** NIP-78 application data: kind `30078` parameterized-replaceable events, one per domain
(`settings`, `continue`, `positions`, `saved`, `custom-lists`, `search`, `taste`, `credentials`,
`devices`). Replaceable means relays keep only the newest per domain, so storage never grows.
- **d-tags are opaque**: `HMAC(key, "anilili/settings")`, so a relay cannot tell what the event is
  or that it belongs to this app.
- **content is NIP-44 v2 encrypted to self** (ECDH of the key with its own pubkey). Relays see a
  pubkey, a size and a timestamp, nothing else.

**Merging (the important part).** Never "last device overwrites everything":
- settings: per-key last-writer-wins with a per-key `updatedAt` (DataStore has no timestamps today —
  needs a sidecar map, written in `save()`); 
- Continue watching / positions: per-title newest `updatedAt` — `mergedHistory` already does this;
- saved / custom lists: add/remove sets with tombstones — the custom-list pending-op queue already
  works this way;
- **form-factor settings**: `UI_SCALE`, `PLAYER_CONTENT_SCALE`, Home layout and a few more should
  sync only between devices of the same kind (phone↔phone, TV↔TV), else a TV inherits a phone's
  interface scale. A `scope` field per key.

**When it syncs.** Pull on foreground / Home (the rule both apps now use for AniList), push after a
change (debounced ~2 s; positions on pause/exit and every 60 s of playback). While an app is open it
can hold a subscription (`REQ` with `since`) and get the other device's change **live** — faster
than AniList polling. Android doesn't keep sockets in the background; WorkManager (≥15 min) is
enough for a catch-up.

**Moving the key to another device.**
- phone → TV: the existing "Link a TV" / "Use my phone" pairing already moves a secret over an
  encrypted LAN channel — it gains one more field;
- phone ↔ desktop: a QR code on the desktop, scanned by the phone (or the reverse), carrying the key
  sealed under a short one-time code;
- **recovery** if every device is lost: a 12-word phrase (NIP-06) shown once, and/or the key inside
  the existing sealed backup (`BackupCrypto`, passphrase the viewer holds). Without one of these,
  losing every device loses the data — this must be explained at creation.

**TV profiles.** Each local profile gets its own key (profiles already have their own AniList sign-in
and history), so a family TV doesn't merge four people into one account.

## 4. Work per app

| Work | Android (phone + TV) | Desktop |
|---|---|---|
| NIP-44 v2 | **write it**: ChaCha20 is only in the platform from API 28 and minSdk is 22, so a small pure-Kotlin ChaCha20 + HKDF, checked against the official NIP-44 test vectors | `nostr` crate (rust-nostr, MIT) has it, or `chacha20` + `hkdf` crates |
| Signing + publishing | secp256k1-kmp `signSchnorr`; extend the OkHttp relay code to `EVENT`/`OK`/`REQ`/`CLOSE` | rust-nostr `nostr-sdk` (relay pool, subscriptions) or a small client on the existing HTTP stack |
| Key storage | Android Keystore-wrapped (like `SecureTokenStore`) | Windows Credential Manager / macOS Keychain (`keyring` crate) |
| Sync engine | over `SettingsStore`, `LibraryStore`, `CustomListStore`, search history, taste store | over the SQLite store and `Settings` (reuse `backup.rs`'s key map) |
| UI | create / link / recovery phrase / "devices" / sign out; TV via the pairing flow | create / QR / recovery / devices |
| Tests | shared parity fixtures: NIP-44 vectors, merge cases, d-tag derivation | the same fixtures (`fixtures/parity/`) |

Rough effort, assuming one person and the existing code:

| Phase | Scope | Effort |
|---|---|---|
| 0 | Spec (kinds, d-tags, merge rules, scopes) + parity fixtures | 2–3 days |
| 1 | Key create/link/recovery, NIP-44, publish/pull; **settings, custom lists, search history** | ~2 weeks (all three apps) |
| 2 | **Continue watching with resume points**, card dismissals, saved | ~1 week |
| 3 | **Sealed sign-ins** (sign in once, everywhere) | 3–5 days |
| 4 | Live subscriptions; optional own relay | 3–5 days |

Total ≈ 4–6 weeks. Phase 2 alone fixes most of what today's test found missing.

## 5. Risks and how to handle them

1. **Relays don't promise to keep data.** Free public relays prune, rate-limit and sometimes reject
   kind 30078 or large events. *Mitigation*: write to 4–6 relays, re-publish the newest state on each
   launch (cheap: replaceable), and preferably run **one relay of our own** for retention (strfry on
   a small VPS, or a Workers + D1 relay — Cloudflare R2/Workers would need the dashboard set-up noted
   in `apk-mirrors-and-release-rule`). Accept writes only from keys that have used the app (NIP-42
   auth) so it can't be used as free storage by others.
2. **Key loss = data loss.** No reset link exists. *Mitigation*: recovery phrase at creation, key in
   the sealed backup, "add another device" nudged early. AniList/MAL data is unaffected either way.
3. **Key leak = everything**, including sealed AniList/MAL tokens in phase 3. *Mitigation*: Keystore/
   Keychain storage, never logged, never in diagnostics bundles; credentials as a separate domain the
   viewer opts into; a "sign out everywhere" that rotates to a new key and re-publishes.
4. **Old Android.** Devices before 7.1.1 can't validate Let's Encrypt (memory
   `old-android-lets-encrypt`), and most relays use it — those devices would need a relay with
   another CA (an own relay solves it).
5. **Metadata.** Content and d-tags are opaque, but a relay sees one pubkey writing at viewing hours.
   Acceptable for this kind of data; use a fresh key per account, never the update-channel key.
6. **Merge mistakes** are the likeliest bug class (a setting "flipping back"). The fixtures in phase 0
   and per-key timestamps are the guard; the backup restore's "replace" semantics must not be reused.
7. **TV background limits.** No live socket in the background; catch-up on foreground is enough.

## 6. Recommendation

Do it, in phases, starting with Phase 0 + 1 (settings, custom lists, search history): low risk, small
data, and it builds the key/encryption/relay plumbing everything else uses. Then Phase 2 (Continue
watching + resume points), which is what users will notice. Decide before Phase 1 whether to run our
own relay — it removes the two biggest risks (retention, old-Android TLS) for little cost.

Open decisions for the owner:
- own relay or public relays only;
- whether sign-ins (Phase 3) sync at all, or stay per device;
- recovery: phrase, sealed backup, or both;
- one account per TV profile (recommended) or one per TV.
