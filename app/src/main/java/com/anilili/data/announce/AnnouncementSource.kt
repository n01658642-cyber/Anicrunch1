package com.anilili.data.announce

import com.anilili.data.update.NostrUpdateSource
import com.anilili.data.update.compareAppVersions
import com.anilili.data.update.toNostrEvent
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A message the project can push to every install without shipping a build.
 *
 * The app has exactly one address that nobody else can take away: the Nostr key baked into
 * [NostrUpdateSource.MANIFEST_PUBKEY_HEX]. That key already carries the update manifest, and this
 * rides beside it on a second d-tag — so the thing that survives losing the GitHub account, the
 * domain, or the APK host is also the thing that can say *why* and where to go next. That is the
 * case this exists for: "the domain moved", "this version is broken, do not update yet", "the
 * provider is down, it is not your device".
 *
 * **What stops anyone else pushing to your users.** Nothing about a relay is trusted. An event is
 * used only when its NIP-01 id hash matches its contents *and* its BIP-340 signature checks out
 * against the project pubkey, which is the identical check the update manifest goes through
 * ([com.anilili.data.update.NostrEvent.isValidSignedBy]). A hostile or compromised relay can
 * withhold an announcement or replay an older signed one; it cannot write one. The replay is
 * bounded by [Announcement.expiresAt], which the publisher sets and the device enforces against
 * its own clock.
 */
object AnnouncementSource {
    /** Same key and kind as the update manifest; the d-tag is what makes it a separate channel. */
    const val D_TAG = "anilili-announcement"
    private const val SUBSCRIPTION_ID = "anilili-announcement"

    /** The same signed event served as a file, so the common case is one request, not a relay scan. */
    const val MIRROR_URL =
        "https://huggingface.co/HF_USER/HF_REPO/resolve/main/latest/announcement.json"

    /**
     * The newest valid announcement, or null when there is nothing to say.
     *
     * Null is the ordinary answer — most of the time no announcement is published — so every way
     * of arriving at it is quiet. Only an event that was *found and rejected* is worth a diagnostic
     * line, because that is the shape a forgery attempt or a broken publish would take.
     *
     * The mirror is tried first for the same reason the update manifest tries it first: relays are
     * the durable copy but several time out on every read, and the scan waits up to 15 s for the
     * slowest. Falling through to the relays is not belt-and-braces — it is the entire point, since
     * an emergency worth announcing may well be the mirror's host going away.
     */
    fun fetch(client: OkHttpClient, json: Json): Announcement? =
        fetchFromMirror(client, json) ?: fetchFromRelays(client, json)

    private fun fetchFromMirror(client: OkHttpClient, json: Json): Announcement? = runCatching {
        val request = Request.Builder()
            .url(MIRROR_URL)
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(request).execute().use { response ->
            // A 404 is "nothing published", which is the resting state of this channel, not a fault.
            if (!response.isSuccessful) return@runCatching null
            response.body?.string().orEmpty()
        }
        val event = json.parseToJsonElement(body).jsonObject.toNostrEvent()
        if (!event.isValidSignedBy(
                NostrUpdateSource.MANIFEST_PUBKEY_HEX,
                NostrUpdateSource.MANIFEST_KIND,
                D_TAG,
            )
        ) {
            DiagnosticsLog.event("Announcement mirror served an event that failed verification")
            return@runCatching null
        }
        parseAnnouncement(json, event.content)
    }.getOrNull()

    private fun fetchFromRelays(client: OkHttpClient, json: Json): Announcement? = runCatching {
        NostrUpdateSource.scanRelays(client, json, D_TAG, SUBSCRIPTION_ID)
            .maxByOrNull { it.createdAt }
            ?.let { parseAnnouncement(json, it.content) }
    }.getOrNull()
}

/**
 * One broadcast message.
 *
 * [id] is the publisher's own handle on it and is what decides whether a device has already shown
 * this. Re-publishing under the same id corrects a message silently; a new id is a new notification.
 * That is deliberately the publisher's call rather than the Nostr event id's: fixing a typo should
 * not buzz every phone in the world a second time, and a genuinely new emergency should.
 */
@kotlinx.serialization.Serializable
data class Announcement(
    val id: String,
    val title: String,
    val message: String,
    /** Vetted by [sanitizeAnnouncementUrl]; null when absent or not an https URL. */
    val url: String? = null,
    val urlLabel: String? = null,
    /** Routes to the high-importance channel, which is the one that makes a sound. */
    val urgent: Boolean = false,
    /** Unix seconds after which the device ignores this; 0 means it never expires. */
    val expiresAt: Long = 0L,
    val minVersion: String? = null,
    val maxVersion: String? = null,
) {
    /** The host of [url], for telling a viewer where a button is about to send them. */
    val urlHost: String?
        get() = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
}

internal fun parseAnnouncement(json: Json, content: String): Announcement? = runCatching {
    val body: JsonObject = json.parseToJsonElement(content).jsonObject
    val id = body["id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return null
    val message = body["message"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return null
    Announcement(
        id = id,
        title = body["title"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: "Anilili",
        message = message,
        url = sanitizeAnnouncementUrl(body["url"]?.jsonPrimitive?.content),
        urlLabel = body["urlLabel"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank),
        urgent = body["severity"]?.jsonPrimitive?.content.equals("emergency", ignoreCase = true),
        expiresAt = body["expiresAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        minVersion = body["minVersion"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank),
        maxVersion = body["maxVersion"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank),
    )
}.getOrNull()

/**
 * The only URLs a broadcast may carry: plain `https`, with a host.
 *
 * The link is signed by the project key, so this is not a defence against the publisher — it is a
 * defence against the publisher's own mistake, and against the day that key is somewhere it should
 * not be. An Android `Intent` parsed from a string is a wide door: `intent://` scheme URLs can
 * address another app's components outright, and `file://` would hand a local path to whatever
 * claims it. Restricting the scheme up front means the notification below can only ever open a web
 * page. `http` is refused as well, because every host this project publishes on is https and a
 * downgrade in a signed message is far more likely to be an error than an intention.
 */
internal fun sanitizeAnnouncementUrl(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isEmpty()) return null
    val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
    if (!"https".equals(uri.scheme, ignoreCase = true)) return null
    if (uri.host.isNullOrBlank()) return null
    return candidate
}

/**
 * Whether this device should show [announcement] at all.
 *
 * Version bounds are how "0.1.76 is broken, stay on 0.1.75" reaches only the people it is about;
 * without them the only broadcast worth sending is one that applies to everybody, and a warning
 * shown to the wrong half of the install base is worse than none. Both bounds are inclusive and
 * both are optional.
 */
internal fun announcementApplies(
    announcement: Announcement,
    installedVersion: String,
    nowSec: Long,
): Boolean {
    if (announcement.expiresAt in 1 until nowSec) return false
    announcement.minVersion?.let {
        if (compareAppVersions(installedVersion, it) < 0) return false
    }
    announcement.maxVersion?.let {
        if (compareAppVersions(installedVersion, it) > 0) return false
    }
    return true
}
