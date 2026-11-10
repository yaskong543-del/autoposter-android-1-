package com.example.autoposter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How clips are spread across the chosen accounts. */
enum class Mode(val label: String, val help: String) {
    ROTATE("One by one, switch account", "Clip 1 to account A, clip 2 to account B, and so on. Each clip is posted once."),
    RANDOM("Random clip, switch account", "Takes any clip from your list, posts it, switches account, then takes another random one."),
    SEQUENTIAL("All clips in one account, then the next", "Account A gets every clip, then account B gets every clip."),
    SAME_CLIP("Same clip on every account", "Clip 1 goes to all accounts, then clip 2, and so on.")
}

/** The campaign being built on the Create screen. */
class Draft {
    var videos by mutableStateOf<List<VideoRef>>(emptyList())
    var accountIds by mutableStateOf<Set<String>>(emptySet())
    var baseCaption by mutableStateOf("")
    var baseTags by mutableStateOf("")
    var sound by mutableStateOf("")
    var skipDuplicates by mutableStateOf(true)
    var mode by mutableStateOf(Mode.ROTATE)
    var seed by mutableStateOf(System.nanoTime())   // changes the random order
    val overrideCaption = mutableStateMapOf<Platform, String>()
    val overrideTags = mutableStateMapOf<Platform, String>()
    val starts = mutableStateMapOf<String, Long>()   // accountId -> first post time (0/absent = now)
    val gaps = mutableStateMapOf<String, Int>()      // accountId -> minutes between posts

    fun clear() {
        videos = emptyList(); baseCaption = ""; baseTags = ""; sound = ""
        overrideCaption.clear(); overrideTags.clear(); starts.clear(); gaps.clear(); seed = System.nanoTime()
    }

    fun rawTextFor(p: Platform, index: Int): String {
        val variants = (overrideCaption[p]?.takeIf { it.isNotBlank() } ?: baseCaption)
            .split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val cap = if (variants.isEmpty()) "" else variants[index % variants.size]
        val tags = parseTags(overrideTags[p]?.takeIf { it.isNotBlank() } ?: baseTags).take(p.tagLimit)
        return listOf(cap, if (sound.isNotBlank()) "🎵 ${sound.trim()}" else "", tags.joinToString(" "))
            .filter { it.isNotBlank() }.joinToString("\n\n")
    }
    fun textFor(p: Platform, index: Int) = rawTextFor(p, index).take(p.captionLimit)
}

fun parseTags(s: String): List<String> = s.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    .map { if (it.startsWith("#")) it else "#$it" }.distinct()

/** Every (account, video) pair in posting order, plus how many were skipped as duplicates. */
fun planUploads(d: Draft, history: List<PostItem>, accounts: List<Account>): Pair<List<Pair<Account, VideoRef>>, Int> {
    val live = setOf(Status.QUEUED, Status.UPLOADING, Status.DONE)
    val seen = HashSet<String>()
    val vids = d.videos.filter { v -> !d.skipDuplicates || v.fingerprint == null || seen.add(v.fingerprint) }
    var skipped = 0
    val pairs: List<Pair<Account, VideoRef>> = if (accounts.isEmpty()) emptyList() else when (d.mode) {
        Mode.ROTATE -> vids.mapIndexed { i, v -> accounts[i % accounts.size] to v }
        Mode.RANDOM -> vids.shuffled(java.util.Random(d.seed)).mapIndexed { i, v -> accounts[i % accounts.size] to v }
        Mode.SEQUENTIAL -> accounts.flatMap { a -> vids.map { v -> a to v } }
        Mode.SAME_CLIP -> vids.flatMap { v -> accounts.map { a -> a to v } }
    }
    skipped += if (d.mode == Mode.ROTATE || d.mode == Mode.RANDOM) d.videos.size - vids.size else (d.videos.size - vids.size) * accounts.size
    val out = ArrayList<Pair<Account, VideoRef>>()
    for ((a, v) in pairs) {
        if (d.skipDuplicates && v.fingerprint != null &&
            history.any { it.accountId == a.id && it.fingerprint == v.fingerprint && it.status in live }) { skipped++; continue }
        out += a to v
    }
    return out to skipped
}
