package com.example.mimic.downloader.data.model

enum class DownloadCategory { VIDEO, MUSIC, IMAGE, SUBTITLE, OTHER }

/**
 * The state of one download job. It lives on the job (the `downloads` row); the engine is the only
 * writer and every screen/notification just observes it.
 *
 * ```
 * QUEUED → FETCHING_INFO → DOWNLOADING → PROCESSING → COMPLETED
 *    ↑           │               │             │
 *    │           └───────┬───────┴─────────────┘
 *    │                   ↓
 *    └──── retry ── FAILED / CANCELLED        PAUSED ── resume → QUEUED
 * ```
 */
enum class DownloadStatus {
    QUEUED,
    /** yt-dlp started and is resolving the page/formats; no bytes yet. */
    FETCHING_INFO,
    DOWNLOADING,
    /** Transfer finished; merging, converting to MP3 or saving to the chosen folder. */
    PROCESSING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    /** A worker currently owns this job (a process may be running for it). */
    val isActive: Boolean get() = this in ACTIVE

    /** The job has ended and will not change unless the user retries it. */
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val canPause: Boolean get() = this == QUEUED || this == FETCHING_INFO || this == DOWNLOADING
    val canCancel: Boolean get() = this == QUEUED || this == PAUSED || this.isActive
    val canRetry: Boolean get() = this == FAILED || this == CANCELLED

    companion object {
        val ACTIVE: Set<DownloadStatus> = setOf(FETCHING_INFO, DOWNLOADING, PROCESSING)

        /** Legal transitions; anything else is a bug (checked in tests, logged by the engine). */
        fun canTransition(from: DownloadStatus, to: DownloadStatus): Boolean = when (to) {
            QUEUED -> from == PAUSED || from == FAILED || from == CANCELLED || from.isActive || from == QUEUED
            FETCHING_INFO -> from == QUEUED
            DOWNLOADING -> from == FETCHING_INFO || from == DOWNLOADING
            PROCESSING -> from == DOWNLOADING || from == FETCHING_INFO
            COMPLETED -> from == PROCESSING
            FAILED -> from.isActive
            CANCELLED -> from.canCancel
            PAUSED -> from.canPause
        }
    }
}

fun FormatKind.suggestedCategory(): DownloadCategory = when (this) {
    FormatKind.VIDEO -> DownloadCategory.VIDEO
    FormatKind.AUDIO -> DownloadCategory.MUSIC
}
