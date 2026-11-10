package com.example.autoposter

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Saved brand voices: written to Room first (works offline), pushed to public.brand_profiles when signed in. */
class BrandRepository(
    private val dao: BrandDao, private val http: SupabaseHttp, private val auth: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis
) {
    val brands = dao.observe()

    suspend fun get(id: String) = dao.get(id)

    /** Creates or updates. Throws IllegalArgumentException for an empty name. */
    suspend fun save(
        id: String?, name: String, description: String, tone: AiTone, audience: String, hashtags: String,
        language: String, cta: String, signature: String
    ): BrandProfile {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "name" }
        val now = clock()
        val old = id?.let { dao.get(it) }
        val b = BrandProfile(
            id = old?.id ?: UUID.randomUUID().toString(), name = clean.take(80), description = description.trim().take(1000),
            tone = tone.wire, audience = audience.trim().take(300),
            hashtags = parseTags(hashtags).joinToString(" ").take(500), language = language.ifBlank { "en" },
            cta = cta.trim().take(200), signature = signature.trim().take(300),
            createdAt = old?.createdAt ?: now, updatedAt = now, dirty = true, pendingDelete = false
        )
        dao.upsert(b)
        return b
    }

    /** Hides the row at once; the server copy (if any) is removed by the next [push]. */
    suspend fun delete(id: String) {
        val b = dao.get(id) ?: return
        dao.upsert(b.copy(pendingDelete = true, dirty = true))
    }

    suspend fun push() {
        if (auth.currentSession == null) return
        val token = auth.accessToken() ?: return
        val pending = dao.dirty()
        val live = pending.filter { !it.pendingDelete }
        if (live.isNotEmpty()) {
            val arr = JSONArray()
            live.forEach { arr.put(toRow(it)) }
            http.call("POST", "/rest/v1/brand_profiles?on_conflict=id", arr.toString(), bearer = token,
                headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"))
            // Only clear the flag if the row was not edited while the request was in flight.
            dao.upsertAll(live.mapNotNull { sent -> dao.get(sent.id)?.takeIf { it.updatedAt == sent.updatedAt }?.copy(dirty = false) })
        }
        val gone = pending.filter { it.pendingDelete }
        if (gone.isNotEmpty()) {
            val ids = gone.joinToString(",") { it.id }
            http.call("DELETE", "/rest/v1/brand_profiles?id=in.($ids)", bearer = token)
            dao.delete(gone.map { it.id })
        }
    }

    /** Merges the server's copies. Local edits that have not been pushed always win. */
    suspend fun pull() {
        val s = auth.currentSession ?: return
        val token = auth.accessToken() ?: return
        val text = http.call("GET", "/rest/v1/brand_profiles?select=id,name,description,tone,target_audience,default_hashtags,language,default_cta,signature,created_at,updated_at", bearer = token)
        val remote = parseRows(text)
        val local = dao.all().associateBy { it.id }
        val upserts = remote.filter { local[it.id]?.dirty != true }
        if (upserts.isNotEmpty()) dao.upsertAll(upserts)
        val remoteIds = remote.map { it.id }.toSet()
        dao.delete(local.values.filter { !it.dirty && it.id !in remoteIds }.map { it.id })
        AppLog.i("Brand", "pulled ${remote.size} for ${s.user.id.take(4)}")
    }

    suspend fun sync() { push(); pull() }
    suspend fun clearLocal() = dao.clear()

    companion object {
        fun toRow(b: BrandProfile): JSONObject = JSONObject()
            .put("id", b.id).put("name", b.name).put("description", b.description).put("tone", b.tone)
            .put("target_audience", b.audience).put("default_hashtags", b.hashtags).put("language", b.language)
            .put("default_cta", b.cta).put("signature", b.signature).put("created_at", Iso.utc(b.createdAt))

        fun parseRows(text: String): List<BrandProfile> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                BrandProfile(
                    id = o.getString("id"), name = o.getString("name"), description = o.optString("description"),
                    tone = AiTone.fromWire(o.optString("tone")).wire, audience = o.optString("target_audience"),
                    hashtags = o.optString("default_hashtags"), language = o.optString("language", "en"),
                    cta = o.optString("default_cta"), signature = o.optString("signature"),
                    createdAt = Iso.parse(o.optString("created_at")), updatedAt = Iso.parse(o.optString("updated_at")),
                    dirty = false, pendingDelete = false
                )
            }
        }
    }
}
