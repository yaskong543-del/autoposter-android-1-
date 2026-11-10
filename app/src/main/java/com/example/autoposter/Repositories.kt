package com.example.autoposter

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Local profile row (Room) mirrored with public.profiles. Edits are written locally first, then pushed. */
class ProfileRepository(
    private val dao: ProfileDao, private val http: SupabaseHttp, private val auth: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun observe(userId: String) = dao.observe(userId)

    suspend fun ensureLocal(s: AuthSession) {
        if (dao.get(s.user.id) == null)
            dao.upsert(ProfileEntity(s.user.id, s.user.displayName, s.user.email, null, "system", "free", clock(), false))
    }

    private suspend fun token(): String = auth.accessToken() ?: throw ApiException(401, "Not signed in", ErrorKind.AUTH, "no_session")

    suspend fun refresh() {
        val s = auth.currentSession ?: return
        val text = http.call("GET", "/rest/v1/profiles?id=eq.${s.user.id}&select=display_name,avatar_path,language,plan", bearer = token())
        val arr = JSONArray(text)
        if (arr.length() == 0) { ensureLocal(s); return }
        val o = arr.getJSONObject(0)
        if (dao.get(s.user.id)?.dirty == true) { pushDirty(); return }
        dao.upsert(ProfileEntity(s.user.id, o.str("display_name") ?: s.user.displayName, s.user.email, o.str("avatar_path"),
            o.str("language") ?: "system", o.str("plan") ?: "free", clock(), false))
    }

    suspend fun updateDisplayName(name: String) {
        val s = auth.currentSession ?: return
        ensureLocal(s)
        val cur = dao.get(s.user.id) ?: return
        dao.upsert(cur.copy(displayName = name.trim(), updatedAtMs = clock(), dirty = true))
        pushDirty()
    }

    suspend fun updateLanguage(code: String) {
        val s = auth.currentSession ?: return
        ensureLocal(s)
        val cur = dao.get(s.user.id) ?: return
        dao.upsert(cur.copy(language = code, updatedAtMs = clock(), dirty = true))
        runCatching { pushDirty() }
    }

    /** Pushes locally edited fields. Throws on failure so callers (and WorkManager) can retry later. */
    suspend fun pushDirty() {
        for (p in dao.dirty()) {
            val body = JSONObject().put("display_name", p.displayName ?: JSONObject.NULL).put("language", p.language).toString()
            http.call("PATCH", "/rest/v1/profiles?id=eq.${p.userId}", body, bearer = token(), headers = mapOf("Prefer" to "return=minimal"))
            if (p.displayName != null) runCatching { auth.updateDisplayName(p.displayName) }
            dao.get(p.userId)?.let { if (it.updatedAtMs == p.updatedAtMs) dao.upsert(it.copy(dirty = false)) }
        }
    }

    suspend fun clearLocal() = dao.clear()
}

/** Registers this installation and lists the other devices signed in to the same account. */
class DeviceRepository(
    private val dao: DeviceDao, private val http: SupabaseHttp, private val auth: AuthRepository, private val prefs: UserPrefs,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun observe() = dao.observe()

    private suspend fun token(): String = auth.accessToken() ?: throw ApiException(401, "Not signed in", ErrorKind.AUTH, "no_session")

    private fun row(d: DeviceEntity) = JSONObject().put("install_id", d.installId).put("name", d.name).put("platform", d.platform)
        .put("app_version", d.appVersion).put("os_version", d.osVersion).put("last_seen_at", Iso.utc(d.lastSeenAt))

    private suspend fun push(d: DeviceEntity) {
        http.call("POST", "/rest/v1/devices?on_conflict=owner,install_id", JSONArray().put(row(d)).toString(), bearer = token(),
            headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"))
        dao.upsert(d.copy(synced = true))
    }

    suspend fun registerThisDevice() {
        val id = prefs.installId(); val now = clock()
        val prev = dao.find(id)
        val d = DeviceEntity(
            id, listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android device" },
            "android", BuildConfig.VERSION_NAME, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            now, prev?.createdAt ?: now, true, false
        )
        dao.upsert(d)
        push(d)
    }

    suspend fun pushUnsynced() { for (d in dao.unsynced()) push(d) }

    suspend fun refresh() {
        val me = prefs.installId()
        val arr = JSONArray(http.call("GET", "/rest/v1/devices?select=install_id,name,platform,app_version,os_version,last_seen_at,created_at&order=last_seen_at.desc", bearer = token()))
        val others = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("install_id") != me }.map {
            DeviceEntity(it.getString("install_id"), it.str("name") ?: "Device", it.str("platform") ?: "android",
                it.str("app_version") ?: "", it.str("os_version") ?: "", Iso.parse(it.str("last_seen_at")),
                Iso.parse(it.str("created_at")), false, true)
        }
        dao.replaceOthers(others)
    }

    /** Removes another device from the list. This does not sign that device out; use "sign out everywhere" for that. */
    suspend fun remove(installId: String) {
        http.call("DELETE", "/rest/v1/devices?install_id=eq.$installId", bearer = token())
        dao.delete(installId)
    }

    suspend fun clearLocal() = dao.clear()
}

/** Social-account metadata only (platform, label, status, expiry). Tokens never leave the phone. */
class AccountMetaRepository(
    private val dao: AccountMetaDao, private val http: SupabaseHttp, private val auth: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun mirror(accounts: List<Account>, needsAuthIds: Set<String>) {
        val now = clock()
        val existing = dao.all().associateBy { it.id }
        val upserts = ArrayList<AccountMetaEntity>()
        for (a in accounts) {
            val status = AccountHealthCheck.of(a, now, a.id in needsAuthIds).code
            val old = existing[a.id]
            val same = old != null && !old.pendingDelete && old.platform == a.platform.name && old.label == a.label &&
                old.status == status && old.expiresAt == a.expiresAt && old.externalId == a.igUserId
            if (!same) upserts += AccountMetaEntity(a.id, a.platform.name, a.label, status, a.expiresAt, a.igUserId,
                old?.createdAt ?: now, now, synced = false)
        }
        val keep = accounts.map { it.id }.toSet()
        existing.values.filter { it.id !in keep && !it.pendingDelete }.forEach { upserts += it.copy(pendingDelete = true, synced = false, updatedAt = now) }
        if (upserts.isNotEmpty()) dao.upsertAll(upserts)
    }

    /** Pushes pending changes; throws on failure so WorkManager can retry. */
    suspend fun push() {
        if (auth.currentSession == null) return
        val token = auth.accessToken() ?: return
        val pending = dao.unsynced()
        if (pending.isNotEmpty()) {
            val arr = JSONArray()
            pending.forEach {
                arr.put(JSONObject().put("id", it.id).put("platform", it.platform).put("label", it.label).put("status", it.status)
                    .put("expires_at", Iso.orNull(it.expiresAt)).put("external_id", it.externalId ?: JSONObject.NULL)
                    .put("created_at", Iso.utc(it.createdAt)))
            }
            http.call("POST", "/rest/v1/social_accounts?on_conflict=owner,id", arr.toString(), bearer = token,
                headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"))
            dao.upsertAll(pending.map { it.copy(synced = true) })
        }
        val gone = dao.pendingDeletes()
        if (gone.isNotEmpty()) {
            val ids = gone.joinToString(",") { "\"${it.id}\"" }
            http.call("DELETE", "/rest/v1/social_accounts?id=in.(${java.net.URLEncoder.encode(ids, "UTF-8")})", bearer = token)
            dao.delete(gone.map { it.id })
        }
    }

    suspend fun clearLocal() = dao.clear()
}
