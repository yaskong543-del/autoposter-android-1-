package com.example.autoposter

enum class Platform { YOUTUBE, INSTAGRAM, TIKTOK }
enum class Status { QUEUED, UPLOADING, DONE, FAILED, NEEDS_AUTH, CANCELED }

data class PostItem(
    val id: String, val campaignId: String, val accountId: String, val accountLabel: String,
    val platform: Platform, val filePath: String, val name: String, val fingerprint: String?,
    val text: String, val scheduledAt: Long, val status: Status, val progress: Float, val attempts: Int,
    val remoteId: String?, val error: String?, val createdAt: Long, val finishedAt: Long,
    val sizeBytes: Long, val views: Long, val likes: Long, val comments: Long
)
