package com.example.autoposter

data class ActivityEntry(val atMs: Long, val kind: Kind, val title: String, val detail: String) {
    enum class Kind { PUBLISHED, FAILED, SCHEDULED, DRAFT }
}

data class DashboardStats(
    val drafts: Int, val scheduled: Int, val published: Int, val failed: Int,
    val connectedAccounts: Int, val accountsNeedingAttention: Int,
    val upcoming: List<PostItem>, val recent: List<ActivityEntry>
) {
    companion object {
        fun compute(
            posts: List<PostItem>, drafts: List<ComposerDraftEntity>, accounts: List<Account>, now: Long,
            upcomingLimit: Int = 5, recentLimit: Int = 8
        ): DashboardStats {
            val needsAuth = AccountHealthCheck.needsAuthIds(posts)
            val attention = accounts.count { AccountHealthCheck.of(it, now, it.id in needsAuth).needsAttention }
            val recent = buildList {
                posts.forEach { p ->
                    when (p.status) {
                        Status.DONE -> add(ActivityEntry(p.finishedAt.takeIf { it > 0 } ?: p.createdAt, ActivityEntry.Kind.PUBLISHED, p.name, p.accountLabel))
                        Status.FAILED, Status.NEEDS_AUTH -> add(ActivityEntry(p.finishedAt.takeIf { it > 0 } ?: p.createdAt, ActivityEntry.Kind.FAILED, p.name, p.accountLabel))
                        Status.QUEUED -> add(ActivityEntry(p.createdAt, ActivityEntry.Kind.SCHEDULED, p.name, p.accountLabel))
                        else -> Unit
                    }
                }
                drafts.forEach { add(ActivityEntry(it.updatedAt, ActivityEntry.Kind.DRAFT, it.title.ifBlank { "Draft" }, "")) }
            }.sortedByDescending { it.atMs }.take(recentLimit)
            return DashboardStats(
                drafts = drafts.size,
                scheduled = posts.count { it.status == Status.QUEUED || it.status == Status.UPLOADING },
                published = posts.count { it.status == Status.DONE },
                failed = posts.count { it.status == Status.FAILED || it.status == Status.NEEDS_AUTH },
                connectedAccounts = accounts.size, accountsNeedingAttention = attention,
                upcoming = posts.filter { it.status == Status.QUEUED }.sortedBy { it.scheduledAt }.take(upcomingLimit),
                recent = recent
            )
        }
    }
}
