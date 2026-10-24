package dev.gyeol.core.session

import java.time.Instant

enum class Entry { SEEDS, TAKEOUT }

enum class SessionStatus { CREATED, UPLOADING, PROCESSING, DONE, FAILED }

data class Session(
    val id: Long,
    val entry: Entry,
    val status: SessionStatus,
    val totalCount: Int?,
    val musicCount: Int?,
    val unclassifiedCount: Int?,
    val savedCount: Int?,
    val periodStart: Instant?,
    val periodEnd: Instant?,
    val errorMessage: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class SeedTrack(val mbid: java.util.UUID, val title: String, val artist: String, val position: Int)
