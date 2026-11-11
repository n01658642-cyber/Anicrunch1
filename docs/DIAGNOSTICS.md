# Diagnostics and privacy

Anilili creates a diagnostic report only when a user chooses **Send diagnostics** or approves the
crash-report prompt. The app then opens Android's share sheet; the user chooses whether and where
to send the ZIP. Anilili has no background diagnostics uploader or private report service.

Manual reports ask the user to describe the problem. A JPEG, PNG or WebP screenshot up to 5 MB can
also be selected explicitly; it is never captured automatically. The description and screenshot
are included inside the diagnostic ZIP. Crash reports offer the same optional context, while the
slow-start action and launcher shortcut remain usable without it. TV sharing depends on the share
targets installed by the device vendor.

Reports can include app/device configuration, Android version, memory and CPU samples, thermal and
frame-jank windows, playback/decoder/buffering statistics, resolver lifecycle, provider hosts and
HTTP timing/status data, WorkManager state, app logs, Java/Kotlin crash details, and Android native
exit traces when the OS exposes them.

Before a report is created, the app centrally redacts the written description plus passwords,
cookies, authorization headers,
access and refresh tokens, JWTs, email addresses, signed URL paths/query parameters, anime titles,
slugs and other sensitive content identifiers. Screenshots can contain whatever is visible in the
selected image, so the UI warns users to attach only content they intend to send. Raw IP addresses
are not stored.

Generated ZIPs remain in Android's disposable app cache for up to 24 hours so the selected share
target can read them. Android may clear them sooner under storage pressure, and the app removes old
reports the next time it creates one. Once the user shares a ZIP, retention and delivery are
controlled by the app they selected.
