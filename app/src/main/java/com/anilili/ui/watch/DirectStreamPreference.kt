package com.anilili.ui.watch

/**
 * Whether an automatic server choice should look past an embed for a direct stream.
 *
 * On a TV with little memory an embed is the expensive answer twice over: it starts a whole
 * browser engine beside the video decoder, and when the web player inside it fails the app only
 * learns so after the page has loaded. Both showed in a Fire TV Stick 3rd gen bundle (AFTSSS,
 * 2026-10-03): the stick reports `lowRam=false` but a 128 MB memory class, sat about 25 MB above
 * its low-memory line all day, and auto landed on an embed in 9 of 14 resolves — AniKoto's MegaPlay
 * page, whose JW player then failed with 232011 on every episode the viewer tried — while AniPM's
 * direct stream played whenever it was reached.
 *
 * Keyed on the memory class as well as the flag, the same way the low-cost TV effects are, because
 * Fire OS does not set the flag on its 1 GB sticks.
 */
internal fun prefersDirectStreams(isTv: Boolean, isLowRamDevice: Boolean, memoryClassMb: Int): Boolean =
    isTv && (isLowRamDevice || memoryClassMb in 1..CONSTRAINED_TV_MEMORY_CLASS_MB)

private const val CONSTRAINED_TV_MEMORY_CLASS_MB = 256

/**
 * How many embed-only answers one resolve sets aside while it looks for a direct stream.
 *
 * Each pass is a fresh walk, and on a stick a walk costs seconds, so the search is bounded: after
 * this many, the first embed set aside is played after all.
 */
internal const val MAX_HELD_EMBEDS = 2

/**
 * Whether a resolve that came back with only an embed to play should be set aside for another pass.
 *
 * Only an automatic choice: a server the viewer picked is played as it comes. [alreadyHeld] counts
 * the embeds this resolve has set aside so far.
 */
internal fun shouldHoldEmbedForDirectStream(
    prefersDirect: Boolean,
    automaticChoice: Boolean,
    wouldPlayEmbed: Boolean,
    alreadyHeld: Int,
): Boolean = prefersDirect && automaticChoice && wouldPlayEmbed && alreadyHeld < MAX_HELD_EMBEDS
