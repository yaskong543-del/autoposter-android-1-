package com.example.autoposter

/** Connection health of one social account. [code] is what the backend stores in social_accounts.status. */
enum class AccountHealth(val code: String) {
    OK("CONNECTED"), EXPIRING("EXPIRING"), EXPIRED("EXPIRED"), NEEDS_RECONNECT("NEEDS_RECONNECT"), UNKNOWN("UNKNOWN");
    val needsAttention: Boolean get() = this == EXPIRING || this == EXPIRED || this == NEEDS_RECONNECT
}

object AccountHealthCheck {
    const val WARN_WINDOW_MS = 24L * 3_600_000L

    /** [needsAuth] is true when the platform already rejected this account's token during an upload. */
    fun of(a: Account, now: Long, needsAuth: Boolean): AccountHealth = when {
        needsAuth -> AccountHealth.NEEDS_RECONNECT
        TokenRefresher.refreshable(a) -> AccountHealth.OK
        a.expiresAt <= 0L -> AccountHealth.UNKNOWN
        a.expiresAt < now -> AccountHealth.EXPIRED
        a.expiresAt - now < WARN_WINDOW_MS -> AccountHealth.EXPIRING
        else -> AccountHealth.OK
    }

    fun needsAuthIds(items: List<PostItem>): Set<String> = items.filter { it.status == Status.NEEDS_AUTH }.map { it.accountId }.toSet()
}
