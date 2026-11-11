package com.anilili.errors

/**
 * Every failure this app can put in front of someone, as one short code.
 *
 * The problem this solves is two problems that are really one. On screen, error text was written at
 * the throw site as a full paragraph and rendered raw, so a download list could carry eleven copies
 * of a fifteen-word sentence. In diagnostics, most failures were logged through the free-text
 * `DiagnosticsLog.event(String)` overload, whose category is guessed from the first word of the
 * message — so "Episode export repair skipped" and every other line beginning "Episode" landed in
 * the same bucket, and a bundle could only be searched by prose that nobody promised to keep stable.
 *
 * A catalog entry is the single source of truth for both: [userMessage] is what a viewer reads,
 * [code] is what they quote and what the log records. Neither can drift from the other because
 * there is only one of them.
 *
 * ## Rules
 *
 * - **[number] is assigned once and never reused, never renumbered.** That is the whole value of a
 *   code: `EXP-04` has to mean the same thing in v0.1.78 and v0.3.0, or a bug report ages into a
 *   lie. Retiring an entry means leaving its number vacant, not filling the gap.
 * - **[code] is derived from data, never from the enum constant name.** R8 minifies release builds
 *   here, so anything resting on a class or constant name reaches you as `a.b.c` and has to be
 *   deobfuscated against the mapping archive before it means anything. `prefix` plus an `Int`
 *   survives minification untouched.
 * - **[userMessage] is one plain line.** No file names, no codec names, no host names, no stack
 *   detail. Everything technical belongs in the detail a viewer can open deliberately, or in the
 *   diagnostics attributes — not in a list row.
 *
 * Platform error codes are *not* replaced by this. Where the underlying library already numbers a
 * failure — Media3's `PlaybackException.errorCode`, an HTTP status, a `MediaCodec` result — that
 * number is carried alongside as a qualifier and shown as `PLAY-02/2004`. See [platformErrorCode].
 */
enum class ErrorDomain(val prefix: String, val label: String) {
    /** Reaching anything at all: DNS, TLS, timeouts, the catalogue endpoints. */
    NETWORK("NET", "Network"),

    /** Finding an episode and a server that has it. */
    SOURCE("SRC", "Sources"),

    /** Playing one back, in the native player or an embed. */
    PLAYBACK("PLAY", "Playback"),

    /** Subtitles: tracks, imports, OpenSubtitles, timing. */
    SUBTITLE("SUB", "Subtitles"),

    /** Saving an episode into the in-app library. */
    DOWNLOAD("DL", "Downloads"),

    /** Turning a saved episode into an MP4 on the device. */
    EXPORT("EXP", "Saving to device"),

    /** AniList, MyAnimeList, Discord, OpenSubtitles accounts and list sync. */
    ACCOUNT("ACC", "Accounts"),

    /** Backup files, list imports and exports. */
    BACKUP("BAK", "Backup"),

    /** Signing in on a television from a phone. */
    PAIRING("PAIR", "TV pairing"),

    /** Finding, downloading and installing a new build. */
    UPDATE("UPD", "Updates"),

    /** The diagnostics pipeline failing to report on itself. */
    DIAGNOSTICS("DIAG", "Diagnostics"),

    /** `anilili://` links from outside the app. The prefix is the desktop app's, so a code means the same in both. */
    LINK("DLINK", "Links"),

    /** Anything with no better home. Prefer a real domain. */
    APP("APP", "App"),
}

/**
 * The one thing a viewer can usefully do next, if there is one.
 *
 * This exists so "retry" stops being reimplemented per screen with slightly different wording and
 * slightly different enabled-conditions. A null action is a real answer: it means nothing they do
 * from here helps, and offering a button would be a lie.
 */
enum class ErrorAction(val label: String) {
    RETRY("Try again"),
    RETRY_EXPORT("Save again"),
    FREE_SPACE("Free up space"),
    CHANGE_SERVER("Try another server"),
    SIGN_IN("Sign in"),
    OPEN_SETTINGS("Open settings"),
    CHOOSE_FILE("Choose a file"),
    SEND_DIAGNOSTICS("Send diagnostics"),
}

/**
 * The catalog.
 *
 * [userMessage] may carry a single `%s`, filled at the call site through [AnililiError.message].
 * Keep the placeholder for things a viewer genuinely needs inline — a destination, an episode
 * number — and put everything else in the detail.
 */
enum class AnililiError(
    val domain: ErrorDomain,
    val number: Int,
    val userMessage: String,
    val action: ErrorAction? = null,
    /** Whether repeating the same request unchanged has a real chance of working. */
    val retryable: Boolean = false,
) {
    // ---------------------------------------------------------------- NET
    NETWORK_UNREACHABLE(ErrorDomain.NETWORK, 1, "No connection.", ErrorAction.RETRY, retryable = true),
    NETWORK_TIMEOUT(ErrorDomain.NETWORK, 2, "The server took too long to answer.", ErrorAction.RETRY, retryable = true),
    NETWORK_DNS_BLOCKED(
        ErrorDomain.NETWORK,
        3,
        "Couldn't reach the server. Check your private DNS or VPN.",
        ErrorAction.RETRY,
        retryable = true,
    ),
    NETWORK_TLS_REJECTED(
        ErrorDomain.NETWORK,
        4,
        "This device couldn't verify the server's certificate.",
        ErrorAction.SEND_DIAGNOSTICS,
    ),
    CATALOG_LOAD_FAILED(ErrorDomain.NETWORK, 5, "Couldn't load the catalogue.", ErrorAction.RETRY, retryable = true),
    HOME_LOAD_FAILED(ErrorDomain.NETWORK, 6, "Couldn't load the home page.", ErrorAction.RETRY, retryable = true),
    SCHEDULE_LOAD_FAILED(ErrorDomain.NETWORK, 7, "Couldn't load the schedule.", ErrorAction.RETRY, retryable = true),
    NOTIFICATIONS_LOAD_FAILED(
        ErrorDomain.NETWORK,
        8,
        "Couldn't load notifications.",
        ErrorAction.RETRY,
        retryable = true,
    ),
    SEARCH_FAILED(ErrorDomain.NETWORK, 9, "Search didn't come back.", ErrorAction.RETRY, retryable = true),
    CLOUDFLARE_CHALLENGE(
        ErrorDomain.NETWORK,
        10,
        "A server is asking this device to prove it isn't a bot.",
        ErrorAction.RETRY,
        retryable = true,
    ),
    TITLE_LOAD_FAILED(ErrorDomain.NETWORK, 11, "Couldn't load this title.", ErrorAction.RETRY, retryable = true),
    /**
     * Something on the way answered a TLS handshake with something that is not TLS — an ISP or
     * workplace filter's block page, a captive portal, "safe browsing" on the router. It is not
     * [NETWORK_TLS_REJECTED]: no certificate was ever offered, so nothing on this device is out of
     * date, and a diagnostics report would only confirm what the code already says. See
     * [isTlsIntercepted].
     */
    NETWORK_INTERCEPTED(
        ErrorDomain.NETWORK,
        12,
        "Your network is blocking this server. Try another network or a VPN.",
    ),
    /** Home's dice: the pool page it needed did not come back. See `RandomAnimeViewModel`. */
    RANDOM_PICK_FAILED(ErrorDomain.NETWORK, 13, "Couldn't pick a random anime.", ErrorAction.RETRY, retryable = true),

    // ---------------------------------------------------------------- SRC
    SOURCE_NO_EPISODES(ErrorDomain.SOURCE, 1, "No source lists episodes for this title yet.", ErrorAction.RETRY, retryable = true),
    SOURCE_EPISODE_MISSING(ErrorDomain.SOURCE, 2, "No source lists this episode yet.", ErrorAction.RETRY, retryable = true),
    SOURCE_NONE_PLAYABLE(
        ErrorDomain.SOURCE,
        3,
        "No server has a playable copy of this episode.",
        ErrorAction.CHANGE_SERVER,
        retryable = true,
    ),
    SOURCE_SERVER_EMPTY(
        ErrorDomain.SOURCE,
        4,
        "This server has nothing playable for this episode.",
        ErrorAction.CHANGE_SERVER,
        retryable = true,
    ),
    SOURCE_RESOLVE_FAILED(ErrorDomain.SOURCE, 5, "Couldn't load this episode.", ErrorAction.RETRY, retryable = true),
    SOURCE_REFUSED_STREAM(
        ErrorDomain.SOURCE,
        6,
        "The server refused to hand over this episode.",
        ErrorAction.CHANGE_SERVER,
        retryable = true,
    ),
    SOURCE_NO_EXTERNAL_STREAM(
        ErrorDomain.SOURCE,
        7,
        "No server offered a stream another video player can open.",
        ErrorAction.CHANGE_SERVER,
    ),
    SOURCE_NO_DOWNLOADABLE(
        ErrorDomain.SOURCE,
        8,
        "No server offered a downloadable copy.",
        ErrorAction.CHANGE_SERVER,
    ),
    /**
     * The host asking for fewer requests, which is not the same as the host being broken.
     *
     * It had no code, so a throttled server reached a bundle as an untyped stack indistinguishable
     * from a parser fault — which is how AnimeOnsen's rate limiting was read as the source simply
     * being unusably slow. Retryable on purpose: unlike every other SRC entry the condition is
     * known to be temporary, and the cooldown already says how long.
     */
    SOURCE_RATE_LIMITED(
        ErrorDomain.SOURCE,
        9,
        "This server is limiting requests right now.",
        ErrorAction.CHANGE_SERVER,
        retryable = true,
    ),

    // ---------------------------------------------------------------- PLAY
    PLAYBACK_FAILED(ErrorDomain.PLAYBACK, 1, "Playback stopped.", ErrorAction.RETRY, retryable = true),
    PLAYBACK_SOURCE_UNREACHABLE(
        ErrorDomain.PLAYBACK,
        2,
        "Lost the connection to this server.",
        ErrorAction.CHANGE_SERVER,
        retryable = true,
    ),
    PLAYBACK_DECODER_FAILED(
        ErrorDomain.PLAYBACK,
        3,
        "This device couldn't decode the video.",
        ErrorAction.CHANGE_SERVER,
    ),
    PLAYBACK_AUDIO_DECODER_FAILED(
        ErrorDomain.PLAYBACK,
        4,
        "This device couldn't decode the audio.",
        ErrorAction.CHANGE_SERVER,
    ),
    PLAYBACK_DOWNLOAD_UNPLAYABLE(
        ErrorDomain.PLAYBACK,
        5,
        "Couldn't play this download.",
        ErrorAction.SEND_DIAGNOSTICS,
    ),
    PLAYBACK_EMBED_FAILED(
        ErrorDomain.PLAYBACK,
        6,
        "The web player didn't load.",
        ErrorAction.CHANGE_SERVER,
        retryable = true,
    ),
    EXTERNAL_PLAYER_MISSING(
        ErrorDomain.PLAYBACK,
        7,
        "No other video player is installed to open this with.",
        ErrorAction.OPEN_SETTINGS,
    ),
    EXTERNAL_PLAYER_REMOTE_FILE(
        ErrorDomain.PLAYBACK,
        8,
        "This copy can't be handed to another player.",
    ),
    BROWSER_MISSING(ErrorDomain.PLAYBACK, 9, "No app on this device can open a web page."),
    /**
     * [NETWORK_INTERCEPTED], met by the player. Kept apart from `PLAY-02` because the two want
     * different things from the viewer: a dropped connection is worth another try, while a network
     * filtering a video host fails the same way every time — another server on a different host is
     * the thing that can work, and so is another network.
     */
    PLAYBACK_NETWORK_BLOCKED(
        ErrorDomain.PLAYBACK,
        10,
        "Your network is blocking this video server.",
        ErrorAction.CHANGE_SERVER,
    ),

    // ---------------------------------------------------------------- SUB
    SUBTITLE_NONE_AVAILABLE(ErrorDomain.SUBTITLE, 1, "No subtitle tracks for this video."),
    SUBTITLE_IMPORT_FAILED(ErrorDomain.SUBTITLE, 2, "Couldn't read that subtitle file.", ErrorAction.CHOOSE_FILE),
    SUBTITLE_PICKER_MISSING(ErrorDomain.SUBTITLE, 3, "No file picker on this device."),
    SUBTITLE_NO_TRACK_SELECTED(ErrorDomain.SUBTITLE, 4, "Pick a subtitle track first."),
    SUBTITLE_SEARCH_FAILED(ErrorDomain.SUBTITLE, 5, "Subtitle search didn't come back.", ErrorAction.RETRY, retryable = true),
    SUBTITLE_DOWNLOAD_FAILED(ErrorDomain.SUBTITLE, 6, "Couldn't download that subtitle.", ErrorAction.RETRY, retryable = true),
    SUBTITLE_SIGN_IN_REQUIRED(
        ErrorDomain.SUBTITLE,
        7,
        "Sign in to OpenSubtitles in Settings first.",
        ErrorAction.OPEN_SETTINGS,
    ),
    SUBTITLE_NO_LINES_NEAR(ErrorDomain.SUBTITLE, 8, "No lines were found near that moment."),
    SKIP_TIMINGS_UNAVAILABLE(ErrorDomain.SUBTITLE, 9, "Couldn't reach AniSkip.", ErrorAction.RETRY, retryable = true),

    // ---------------------------------------------------------------- DL
    DOWNLOAD_START_FAILED(ErrorDomain.DOWNLOAD, 1, "Couldn't start the download.", ErrorAction.RETRY, retryable = true),
    DOWNLOAD_FAILED(ErrorDomain.DOWNLOAD, 2, "Download failed.", ErrorAction.RETRY, retryable = true),
    DOWNLOAD_OUT_OF_STORAGE(ErrorDomain.DOWNLOAD, 3, "Not enough space left on this device.", ErrorAction.FREE_SPACE),
    DOWNLOAD_STALLED(ErrorDomain.DOWNLOAD, 4, "Download stalled.", ErrorAction.RETRY, retryable = true),
    DOWNLOAD_SUBTITLE_WRITE_FAILED(ErrorDomain.DOWNLOAD, 5, "Couldn't save this episode's subtitles."),
    DOWNLOAD_OPTIONS_UNOPENABLE(ErrorDomain.DOWNLOAD, 6, "No app can open these download options."),

    // ---------------------------------------------------------------- EXP
    EXPORT_FAILED(ErrorDomain.EXPORT, 1, "Couldn't save this episode to %s.", ErrorAction.RETRY_EXPORT, retryable = true),
    EXPORT_OUT_OF_STORAGE_CACHE(
        ErrorDomain.EXPORT,
        2,
        "Not enough free space to build the MP4.",
        ErrorAction.FREE_SPACE,
    ),
    EXPORT_OUT_OF_STORAGE_TARGET(
        ErrorDomain.EXPORT,
        3,
        "Not enough free space on %s.",
        ErrorAction.FREE_SPACE,
    ),

    /**
     * The transmux produced an MP4 whose video track carries no codec configuration — the samples
     * are copied faithfully, but nothing tells a decoder how to read them, so the picture is flat
     * green while audio and subtitles play normally. The export is refused rather than published,
     * and the library copy is kept, so nothing is lost.
     */
    EXPORT_NO_VIDEO_CONFIG(
        ErrorDomain.EXPORT,
        4,
        "Couldn't save this episode as an MP4. It still plays in the app.",
        ErrorAction.RETRY_EXPORT,
    ),
    EXPORT_DEVICE_UNSUPPORTED(
        ErrorDomain.EXPORT,
        5,
        "This device can't build MP4 files. Episodes still play in the app.",
    ),
    EXPORT_PUBLISH_FAILED(
        ErrorDomain.EXPORT,
        6,
        "Built the MP4 but couldn't write it to %s.",
        ErrorAction.RETRY_EXPORT,
        retryable = true,
    ),
    EXPORT_FOLDER_ACCESS_LOST(
        ErrorDomain.EXPORT,
        7,
        "Lost access to the chosen download folder.",
        ErrorAction.OPEN_SETTINGS,
    ),
    EXPORT_PERMISSION_REQUIRED(
        ErrorDomain.EXPORT,
        8,
        "Storage permission is needed to save files here.",
        ErrorAction.OPEN_SETTINGS,
    ),
    EXPORT_STORAGE_UNAVAILABLE(ErrorDomain.EXPORT, 9, "Device storage isn't available right now."),
    EXPORT_STORAGE_DISCONNECTED(
        ErrorDomain.EXPORT,
        10,
        "The chosen save location isn't connected.",
        ErrorAction.OPEN_SETTINGS,
    ),
    EXPORT_DOWNLOAD_MISSING(ErrorDomain.EXPORT, 11, "That download is no longer on this device."),

    // ---------------------------------------------------------------- ACC
    ACCOUNT_SIGN_IN_REQUIRED(
        ErrorDomain.ACCOUNT,
        1,
        "Sign in to AniList or MyAnimeList first.",
        ErrorAction.SIGN_IN,
    ),
    ACCOUNT_SESSION_EXPIRED(ErrorDomain.ACCOUNT, 2, "Sign in to %s again to continue.", ErrorAction.SIGN_IN),
    ACCOUNT_PROFILE_LOAD_FAILED(ErrorDomain.ACCOUNT, 3, "Couldn't load your %s profile.", ErrorAction.RETRY, retryable = true),
    ACCOUNT_LIBRARY_LOAD_FAILED(ErrorDomain.ACCOUNT, 4, "Couldn't load your %s library.", ErrorAction.RETRY, retryable = true),
    ACCOUNT_LIST_UPDATE_FAILED(ErrorDomain.ACCOUNT, 5, "Couldn't update %s.", ErrorAction.RETRY, retryable = true),
    ACCOUNT_LOGIN_PAGE_FAILED(
        ErrorDomain.ACCOUNT,
        6,
        "Couldn't open the login page.",
        ErrorAction.RETRY,
        retryable = true,
    ),
    ACCOUNT_LOGIN_ABANDONED(ErrorDomain.ACCOUNT, 7, "That sign-in didn't finish.", ErrorAction.SIGN_IN),
    ACCOUNT_RATE_LIMITED(
        ErrorDomain.ACCOUNT,
        8,
        "Too many requests to %s. Try again in a minute.",
        ErrorAction.RETRY,
        retryable = true,
    ),
    ACCOUNT_LIST_ENTRY_DELETE_FAILED(
        ErrorDomain.ACCOUNT,
        9,
        "Couldn't remove this from %s.",
        ErrorAction.RETRY,
        retryable = true,
    ),

    /** A tracker that runs beside the list service (Simkl) refused or failed its sign-in. */
    ACCOUNT_TRACKER_CONNECT_FAILED(
        ErrorDomain.ACCOUNT,
        10,
        "Couldn't connect %s.",
        ErrorAction.RETRY,
        retryable = true,
    ),
    ACCOUNT_SCORE_UPDATE_FAILED(
        ErrorDomain.ACCOUNT,
        11,
        "Couldn't save your score on %s.",
        ErrorAction.RETRY,
        retryable = true,
    ),
    ACCOUNT_PROGRESS_UPDATE_FAILED(
        ErrorDomain.ACCOUNT,
        12,
        "Couldn't save your episode count on %s.",
        ErrorAction.RETRY,
        retryable = true,
    ),

    // ---------------------------------------------------------------- BAK
    BACKUP_FILE_UNREADABLE(ErrorDomain.BACKUP, 1, "Couldn't read that file.", ErrorAction.CHOOSE_FILE),
    BACKUP_FILE_UNWRITABLE(ErrorDomain.BACKUP, 2, "Couldn't write the export file.", ErrorAction.RETRY, retryable = true),
    BACKUP_NOT_A_BACKUP(ErrorDomain.BACKUP, 3, "That isn't an Anilili backup.", ErrorAction.CHOOSE_FILE),
    BACKUP_NOT_A_MAL_EXPORT(ErrorDomain.BACKUP, 4, "That isn't a MyAnimeList export file.", ErrorAction.CHOOSE_FILE),
    BACKUP_EMPTY(ErrorDomain.BACKUP, 5, "No anime entries in that file.", ErrorAction.CHOOSE_FILE),
    BACKUP_NOTHING_TO_EXPORT(ErrorDomain.BACKUP, 6, "Nothing here can be exported."),
    BACKUP_PICKER_MISSING(ErrorDomain.BACKUP, 7, "No file picker on this device."),

    // ---------------------------------------------------------------- PAIR
    PAIRING_NOTHING_LISTENING(ErrorDomain.PAIRING, 1, "Nothing is listening on %s.", ErrorAction.RETRY, retryable = true),
    PAIRING_NO_REPLY(ErrorDomain.PAIRING, 2, "No reply from %s.", ErrorAction.RETRY, retryable = true),
    PAIRING_NO_ROUTE(ErrorDomain.PAIRING, 3, "Can't reach %s from this phone."),
    PAIRING_BAD_ADDRESS(ErrorDomain.PAIRING, 4, "That isn't an address this phone understands."),
    PAIRING_CLOSED(ErrorDomain.PAIRING, 5, "The television closed the connection.", ErrorAction.RETRY, retryable = true),
    PAIRING_PROTOCOL_MISMATCH(ErrorDomain.PAIRING, 6, "The two devices disagreed."),
    PAIRING_NONE_FOUND(ErrorDomain.PAIRING, 7, "No televisions found on this network.", ErrorAction.RETRY, retryable = true),
    PAIRING_TIMED_OUT(ErrorDomain.PAIRING, 8, "Nothing connected in time.", ErrorAction.RETRY, retryable = true),
    PAIRING_FAILED(ErrorDomain.PAIRING, 9, "Pairing didn't complete.", ErrorAction.RETRY, retryable = true),

    // ---------------------------------------------------------------- UPD
    UPDATE_CHECK_FAILED(ErrorDomain.UPDATE, 1, "Couldn't check for updates.", ErrorAction.RETRY, retryable = true),
    UPDATE_NO_MANIFEST(ErrorDomain.UPDATE, 2, "No signed update was found.", ErrorAction.RETRY, retryable = true),
    UPDATE_DOWNLOAD_FAILED(ErrorDomain.UPDATE, 3, "The update didn't finish downloading.", ErrorAction.RETRY, retryable = true),
    UPDATE_STORAGE_FAILED(ErrorDomain.UPDATE, 4, "Couldn't make room for the update.", ErrorAction.FREE_SPACE),
    UPDATE_INSTALL_REFUSED(ErrorDomain.UPDATE, 5, "This device refused to install the update."),
    UPDATE_INSTALLER_MISSING(ErrorDomain.UPDATE, 6, "No installer on this device accepted the update."),

    // ---------------------------------------------------------------- DIAG
    DIAGNOSTICS_BUNDLE_FAILED(ErrorDomain.DIAGNOSTICS, 1, "Couldn't prepare the diagnostics report.", ErrorAction.RETRY, retryable = true),
    DIAGNOSTICS_SCREENSHOT_FAILED(ErrorDomain.DIAGNOSTICS, 2, "Couldn't read the selected screenshot.", ErrorAction.CHOOSE_FILE),
    DIAGNOSTICS_WRITE_FAILED(ErrorDomain.DIAGNOSTICS, 3, "Couldn't record diagnostics on this device."),

    // ---------------------------------------------------------------- DLINK
    LINK_UNKNOWN(ErrorDomain.LINK, 1, "That Anilili link isn't one this app understands."),

    // ---------------------------------------------------------------- APP
    UNEXPECTED(ErrorDomain.APP, 1, "Something went wrong.", ErrorAction.SEND_DIAGNOSTICS),
    ;

    /**
     * The stable, printable identity of this failure — `EXP-04`.
     *
     * Built from [ErrorDomain.prefix] and [number] only. Nothing here reads the enum constant name,
     * because R8 renames those in release builds and a code that needs deobfuscating before it can
     * be read is not a code.
     */
    val code: String get() = "${domain.prefix}-${number.toString().padStart(2, '0')}"

    /** Fills the single `%s` in [userMessage], if the entry has one. */
    fun message(vararg args: Any?): String =
        if (args.isEmpty()) userMessage else userMessage.format(*args)

    override fun toString(): String = code

    companion object {
        /** Lookup for turning a quoted code from a bug report back into an entry. */
        fun byCode(code: String): AnililiError? =
            entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
    }
}
