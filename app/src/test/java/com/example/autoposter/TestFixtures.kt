package com.example.autoposter

import java.time.ZoneId

object Fx {
    fun media(id: String, name: String = "$id.mp4", kind: MediaKind = MediaKind.VIDEO, fp: String? = "fp-$id", tags: String = "", folder: String? = null,
              added: Long = 1000, size: Long = 1_000_000) =
        MediaItemEntity(id, kind.name, name, if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg", "/data/$id", size, 5000, 1080, 1920, 0, added, fp, folder, tags)

    fun account(id: String, p: Platform = Platform.YOUTUBE, label: String = id, expiresAt: Long = 0L) =
        Account(id = id, platform = p, label = label, accessToken = "t", expiresAt = expiresAt)

    fun post(id: String, status: Status, at: Long, acc: String = "a1", fp: String? = null, finished: Long = 0L, platform: Platform = Platform.YOUTUBE) =
        PostItem(id = id, campaignId = "c", accountId = acc, accountLabel = acc, platform = platform, filePath = "/f/$id", name = "$id.mp4",
            fingerprint = fp, text = "t", scheduledAt = at, status = status, finishedAt = finished, createdAt = at - 1000)

    fun draft(id: String, planned: Long = 0L, updated: Long = 1L) =
        ComposerDraftEntity(id, "T$id", "cap", "{}", "", "[]", "[]", null, "casual", "en", planned, "UTC", 1L, updated)

    val utc: ZoneId = ZoneId.of("UTC")
}
