# Anilili TV pairing protocol (v1, draft)

How a phone running the `mobile` flavor signs a `tv` install into AniList or MyAnimeList over the
local network, without either credential touching a third party.

**Status: implemented, 39 unit tests, not yet run on real hardware.**

| Piece | Where |
| --- | --- |
| Crypto, frames, handshake | `com.anilili.data.pairing` — `PairingCrypto`, `PairingFrames`, `PairingHandshake` |
| Conversation and sockets | `PairingConversation`, `PairingTransport` |
| mDNS | `PairingDiscovery` |
| Screens and state | `com.anilili.ui.pairing` — `TvPairingScreen`, `PhonePairingScreen`, both ViewModels |
| Entry points | `ProfileScreen` — "Sign in from my phone" (TV), "Link a TV" (phone) |

What has **not** happened: no two-device run. Everything below is verified by unit tests and by
both flavors compiling, linting and assembling — not by a phone talking to a television.

## Why this exists

TV sign-in drives the real anilist.co login page in a WebView with a hand-written D-pad focus
walker ([`LoginWebView.kt`](../app/src/main/java/com/anilili/ui/profile/LoginWebView.kt)). Two
cross-origin overlays now sit in front of the form — a Cloudflare Turnstile check and a Google
Funding Choices consent modal — and neither is navigable with a remote in practice. Viewers report
being unable to link an account at all.

The phone build has none of these problems: it has a touchscreen, a real IME, and a browser-grade
WebView session. So the phone signs in, and hands the result to the TV.

Everything below runs between two devices on one LAN. There is **no server**, no relay, no hosted
page, and no change to either OAuth client registration.

## Roles

| Role | Flavor | Does |
|---|---|---|
| **Responder** | `tv` | Advertises, listens, displays the pairing code, receives the credential |
| **Initiator** | `mobile` | Discovers, connects, runs the OAuth, sends the credential |

The phone is always the TCP client. The TV never dials out.

## Discovery

The TV registers an NSD service while — and only while — pairing mode is open.

```
type:  _anilili._tcp.
name:  <device label>            e.g. "Anilili on BRAVIA-XR"
port:  <ephemeral, OS-assigned>
TXT:   v=1                       protocol version
       id=<8 hex chars>          session id, so a phone can dedupe re-registrations
       svc=anilist,mal           services this build can accept
```

**No key material in TXT.** mDNS records are broadcast in the clear and cached by unrelated
resolvers on the network.

`NsdManager` needs no permission and exists since API 16, comfortably under our minSdk 22. The
phone should hold a `WifiManager.MulticastLock` while browsing — several vendors drop multicast to
sleeping Wi-Fi radios otherwise — which needs `CHANGE_WIFI_MULTICAST_STATE` in the `mobile`
manifest. Nothing new is needed in the `tv` manifest.

**Manual fallback.** Router client isolation, guest VLANs, and some wired/wireless bridges silently
drop mDNS. The TV therefore also displays its `IP:port` alongside the pairing code, and the phone
offers "enter address manually". The protocol from `COMMIT` onward is identical.

## Transport

Raw TCP, length-prefixed JSON frames. No HTTP — there is no browser on either end, and a framed
socket is less code than a server.

```
frame = [4-byte big-endian uint32 length][UTF-8 JSON body]
```

Maximum frame length 64 KiB; anything larger is a protocol error and aborts the session. A declared
length of zero is refused too, so a peer cannot drive the reader with empty frames.

Bodies are `kotlinx.serialization` data classes, which the codebase already uses everywhere. The
polymorphic discriminator is **`kind`** for both frames and credential payloads. Byte fields ride
as Base64 strings and are typed `okio.ByteString` rather than `ByteArray`: a `data class` holding a
`ByteArray` compares by *reference*, so frame equality would silently be identity, and okio's type
is immutable so key material cannot be mutated under a session that already used it.

## Cryptography

All primitives are already in the tree. `fr.acinq.secp256k1` ships for the Nostr update verifier,
its R8 keep rule is already in place (added after
[the update channel shipped inert for a release](../app/src/main/java/com/anilili/data/update/NostrUpdateSource.kt#L202)),
and the `-jni-jvm` variant is already a `testImplementation` dependency — so the entire handshake is
unit-testable on the JVM with no device attached.

| Purpose | Primitive |
|---|---|
| Key agreement | `Secp256k1.ecdh(privkey, pubkey)` → 32 bytes (SHA-256 of the compressed shared point) |
| Key derivation | HKDF-SHA256 (RFC 5869), hand-rolled over `Mac("HmacSHA256")` — ~20 lines |
| Commitment | SHA-256 |
| Confidentiality | `AES/GCM/NoPadding`, 12-byte IV, 128-bit tag |

Public keys go on the wire as **65-byte uncompressed** points, base64url-encoded. Compression buys
nothing here — there is no QR code and no size pressure.

secp256k1 rather than JCA P-256 because it is already shipped, already proven across this device
matrix, already kept by R8, and already available to unit tests. It is an unusual curve for generic
ECDH but there is nothing wrong with it for this purpose.

## Handshake

Four frames, then the credential. The commitment in frame 1 is load-bearing — see
"Why not a typed PIN" below.

```
Phone                                                        TV
  |                                                           |
  |  1. COMMIT   { v, svc, commit = SHA256(Qp || Np) }         |
  |---------------------------------------------------------->|
  |                                                           |
  |  2. HELLO    { v, Qtv, Ntv, label, challenge? }            |
  |<----------------------------------------------------------|
  |                                                           |
  |  3. OPEN     { Qp, Np }                                    |
  |---------------------------------------------------------->|   verifies commit
  |                                                           |
  |        -- both derive Z, PRK, K_session, SAS --            |
  |        -- both display SAS, both users confirm --          |
  |                                                           |
  |  4. CONFIRM  { ok: true }                                  |
  |---------------------------------------------------------->|
  |  5. CONFIRM  { ok: true }                                  |
  |<----------------------------------------------------------|
  |                                                           |
  |        -- phone runs OAuth in its own WebView --           |
  |                                                           |
  |  6. CREDENTIAL { iv, ciphertext }                          |
  |---------------------------------------------------------->|
  |  7. RESULT   { ok, error? }                                |
  |<----------------------------------------------------------|
```

`Qp`/`Qtv` are ephemeral public keys, fresh per session, never reused. `Np`/`Ntv` are 16 random
bytes each.

### Key schedule

```
Z          = ECDH(d_self, Q_peer)                      // 32 bytes, identical on both sides
transcript = "anilili-pair/1" || Qp || Qtv || Np || Ntv || svc
PRK        = HKDF-Extract(salt = SHA256(transcript), IKM = Z)
K_session  = HKDF-Expand(PRK, "anilili-pair/1 session", 32)
sas_bytes  = HKDF-Expand(PRK, "anilili-pair/1 sas", 4)
SAS        = (be_uint32(sas_bytes) mod 1_000_000), zero-padded to 6 digits
```

Both public keys and both nonces are in the transcript, so a man-in-the-middle — who necessarily
runs two distinct ECDH sessions — derives two different `SAS` values and cannot make them agree.

### Confirmation

Both devices display the same 6 digits. **Both users confirm**, and each confirmation defends a
different direction:

- Confirming **on the phone** stops a hostile device on the LAN impersonating the TV and
  harvesting a real account token.
- Confirming **on the TV** stops a hostile phone injecting *its own* account into someone else's
  TV — an account-injection attack that would quietly log the victim's viewing to the attacker's
  list.

The TV's confirmation is a single D-pad press on one button. That is the entire TV-side interaction
for the whole feature, which is the point.

### Why not a typed PIN

The tempting simplification is: the TV shows a random PIN, the user types it into the phone, done —
one action instead of two.

It does not work, in two different ways, and a future implementer will be tempted by it again:

- **Typing a transcript-derived SAS authenticates only one direction.** The phone can compute that
  value itself without ever seeing the TV screen, so typing it proves nothing to the TV.
- **Mixing a random low-entropy PIN into the key schedule is offline-breakable.** `PRK =
  HKDF-Extract(SHA256(transcript || PIN), Z)` looks like it authenticates both ways, but a passive
  eavesdropper who records the handshake and the first AEAD frame can try all 10⁶ PINs offline and
  recover the session key. Doing this safely needs a real PAKE (SPAKE2, CPace) — a meaningful
  implementation burden with sharp edges, for the sake of removing one button press.

Numeric comparison keeps every secret high-entropy and puts the whole security argument in one
short paragraph. Keep it.

### Why the commitment frame

Without frame 1, a man-in-the-middle can grind: it learns both real public keys, then searches its
own ephemeral keys until the two `SAS` values it produces happen to match. Six digits is 10⁶
trials — seconds of CPU.

Committing to `SHA256(Qp || Np)` before the TV reveals `Qtv` forces the attacker to fix its
phone-facing key before it can see what it needs to match, which removes the search entirely. This
is the same reason ZRTP has a hash commitment. It costs one round trip.

## Credential payload

Frame 6's plaintext, encrypted with `AES/GCM/NoPadding` under `K_session` with a fresh random
12-byte IV and the frame's `svc` value as AAD:

```jsonc
// svc = "anilist"
{ "kind": "anilist_token", "token": "<JWT>" }

// svc = "mal"
{ "kind": "mal_code", "code": "<authorization code>" }
```

The AAD already binds the ciphertext to the negotiated service, but the responder additionally
checks that the decrypted `kind` agrees with `svc` — a phone that negotiated one service must not
deliver another.

### AniList

Implicit grant, no PKCE, so the access token itself crosses the wire. This is safe here: it is
AES-GCM under a key that only these two devices hold, on a channel a human just authenticated.

The phone runs a **second, separate authorization** for the TV rather than copying its own token.
AniList issues a fresh JWT per authorization, so each device gets an independent credential and
logging out on one does not disturb the other.

> **Code hazard.** `AuthManager.authorizeUrl()` stashes `pendingOAuthState` in an object singleton
> and `extractToken()` validates against it
> ([`AuthManager.kt:51`](../app/src/main/java/com/anilili/data/auth/AuthManager.kt#L51)).
> A pairing authorization must not clobber the phone's own login state — give the pairing flow its
> own state value instead of mutating the singleton.

### MyAnimeList

Only the authorization **code** crosses. The TV generates the PKCE verifier when it enters pairing
mode and sends the derived `code_challenge` in frame 2; the phone authorizes with it and returns
the code; the TV performs the token exchange itself.

This is *not* about hiding the verifier from the phone — MAL supports `code_challenge_method=plain`
only ([`MalAuthManager.kt:79`](../app/src/main/java/com/anilili/data/auth/MalAuthManager.kt#L79)),
so under `plain` the challenge *is* the verifier and the phone necessarily sees it. It is about
**independent token lifecycles**. MAL rotates the refresh token on every refresh; if both devices
held the same pair, whichever refreshed first would silently log the other out. Exchanging on the
TV gives it its own pair.

No registration change is needed: exactly one redirect URI stays registered, so `redirect_uri` can
continue to be omitted from both the authorize call and the exchange, exactly as today.

### Storage

The TV writes the received credential through the existing `SecureTokenStore`, re-encrypting under
its own Keystore key.

> Worth stating because it looks like a blocker: the note that an AniList token "can't be copied
> between devices" is about the **stored ciphertext** — `SecureTokenStore` encrypts under a
> non-exportable Keystore key, so the prefs blob is device-bound. The JWT itself is an ordinary
> bearer token and transfers fine.

## State machines

### TV

| State | Enter on | Leave on |
|---|---|---|
| `IDLE` | — | user opens "Link an account" |
| `ADVERTISING` | keypair + verifier generated, socket bound, NSD registered | connection accepted / 3 min timeout / user cancels |
| `HANDSHAKING` | connection accepted | `OPEN` verified against commit / any error |
| `AWAITING_CONFIRM` | SAS derived and displayed | both `CONFIRM` frames / 60 s timeout |
| `AWAITING_CREDENTIAL` | confirmed | frame 6 / 120 s timeout (the phone is doing OAuth) |
| `STORING` | frame 6 decrypted | stored, `RESULT` sent, socket closed |

### Phone

| State | Enter on | Leave on |
|---|---|---|
| `IDLE` | — | user opens "Link a TV" |
| `BROWSING` | multicast lock held, NSD discovery started | user picks a TV / manual address entered |
| `HANDSHAKING` | TCP connected, `COMMIT` sent | `OPEN` sent, SAS derived |
| `AWAITING_CONFIRM` | SAS displayed | user confirms / cancels |
| `AUTHORIZING` | both `CONFIRM` frames exchanged | OAuth result intercepted |
| `SENDING` | credential encrypted | `RESULT` received |

### Aborts

Any protocol error, timeout, or mismatched commit aborts both sides immediately. The TV then
**regenerates its keypair, nonce, session id and PKCE verifier** before accepting another
connection — no state is ever reused across attempts. The TV serves one connection at a time and
leaves pairing mode after three failed attempts.

The whole pairing window is 3 minutes. The socket and the NSD registration are torn down on exit
from `ADVERTISING` by any path, including process death — the TV must not be left listening.

## UX notes

Both services usually **skip the consent screen** for an already-authorized account, so the phone's
OAuth step will often flash past in under a second.
[`LoginWebView.kt:992`](../app/src/main/java/com/anilili/ui/profile/LoginWebView.kt#L992) already
documents this and warns that it reads as "nothing happened" — a viewer previously reported a
successful login as a failure for exactly this reason. The pairing UI must confirm loudly on both
screens.

The TV's existing WebView login stays as a fallback. Pairing is an addition, not a replacement.

## Testing

The JVM secp256k1 artifact means almost all of this is testable without a device:

- Known-answer vectors for the transcript, HKDF outputs, and SAS derivation.
- **MITM test:** run two handshakes sharing an attacker in the middle; assert the two SAS values
  differ.
- **Commitment test:** assert the TV rejects an `OPEN` whose `SHA256(Qp || Np)` does not match the
  `COMMIT` it received.
- **Rollover test:** assert the TV's keypair, nonce and verifier all differ after an abort.
- Frame codec: truncated frames, oversized length prefix, trailing bytes.

On-device, the two emulators (`anilili_phone`, `anilili_tv`) are on the same host network and
should discover each other; real-hardware testing needs a router that does not isolate clients.

## Diagnosing a failure in the field

Pairing breaks on networks nobody here can reproduce, in front of someone holding a remote with no
adb and no logcat. So the whole trace is printed on both screens under **Connection details**,
where it can be photographed:

- every address this device has, **with its interface** — a TV on Ethernet and a phone on Wi-Fi is
  a common and otherwise invisible cause, and printing only the first address is how you tell
  someone to type one their phone cannot route to
- whether the mDNS registration or browse actually succeeded, by name, and the **renamed** service
  name when a second TV in the house forces a collision rename
- a television that was **seen but failed to resolve** — previously the list just stayed empty
- the classified socket failure, because "refused", "timed out" and "no route" have three
  different fixes and look identical to a viewer

`describePairingFailure` turns each into a sentence and a suggestion; it is pure and unit-tested,
so the branches are pinned rather than discovered by a user. Failure lines are coloured, so the one
line that matters is the one that stands out.

If nothing is found after twelve seconds the phone prints the four usual causes rather than
spinning silently.

## Open questions

1. **Does `svc` extend to OpenSubtitles?** There is an `OpenSubtitlesAuthManager` with the same
   TV-input problem. The frame format carries `svc` so it can, but v1 should ship AniList + MAL only.
2. **NSD behaviour at targetSdk 36.** Registration-listener semantics changed in API 34; needs a
   check on a current TV build.
3. **Device labelling.** `Build.MODEL` is often unhelpful ("BRAVIA 4K GB"). Consider letting the
   viewer name the TV, or appending the last octet of its IP.
4. **Re-pairing prompt.** AniList tokens expire after a year with no refresh. The TV should prompt
   to re-pair rather than failing syncs quietly.
