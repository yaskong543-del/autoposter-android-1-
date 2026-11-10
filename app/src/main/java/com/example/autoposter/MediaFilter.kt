package com.example.autoposter

enum class MediaSort { NEWEST, OLDEST, NAME, LARGEST }

/** Search + filters for the library. Pure, so the whole thing is covered by unit tests. */
data class MediaFilter(
    val query: String = "",
    val kind: MediaKind? = null,
    val folderId: String? = null,           // a folder id, or null for "all folders"
    val unfiledOnly: Boolean = false,       // true: only items that are in no folder (overrides folderId)
    val tag: String? = null,
    val duplicatesOnly: Boolean = false,
    val sort: MediaSort = MediaSort.NEWEST
) {
    val active: Boolean get() = query.isNotBlank() || kind != null || folderId != null || unfiledOnly || tag != null || duplicatesOnly

    fun apply(items: List<MediaItemEntity>, folderNames: Map<String, String> = emptyMap()): List<MediaItemEntity> {
        val dupes = if (duplicatesOnly) MediaDuplicates.duplicateIds(items) else emptySet()
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.trimStart('#') }.filter { it.isNotEmpty() }
        val out = items.filter { m ->
            (kind == null || m.kindEnum == kind) &&
                (!unfiledOnly || m.folderId == null) &&
                (unfiledOnly || folderId == null || m.folderId == folderId) &&
                (tag == null || tag.lowercase() in m.tagList) &&
                (!duplicatesOnly || m.id in dupes) &&
                words.all { w ->
                    m.name.lowercase().contains(w) || m.tagList.any { it.contains(w) } ||
                        (m.folderId?.let { folderNames[it] }?.lowercase()?.contains(w) == true)
                }
        }
        return when (sort) {
            MediaSort.NEWEST -> out.sortedByDescending { it.addedAt }
            MediaSort.OLDEST -> out.sortedBy { it.addedAt }
            MediaSort.NAME -> out.sortedBy { it.name.lowercase() }
            MediaSort.LARGEST -> out.sortedByDescending { it.sizeBytes }
        }
    }
}

object MediaDuplicates {
    /** Items sharing a fingerprint with at least one other item (same size + same first-MB hash, as in v0.5). */
    fun duplicateIds(items: List<MediaItemEntity>): Set<String> =
        items.filter { it.fingerprint != null }.groupBy { it.fingerprint }.values.filter { it.size > 1 }.flatten().map { it.id }.toSet()

    /** Per fingerprint: the oldest item is kept, the rest are suggested for removal. */
    fun removable(items: List<MediaItemEntity>): List<MediaItemEntity> =
        items.filter { it.fingerprint != null }.groupBy { it.fingerprint }.values
            .filter { it.size > 1 }.flatMap { g -> g.sortedBy { it.addedAt }.drop(1) }

    fun allTags(items: List<MediaItemEntity>): List<String> =
        items.flatMap { it.tagList }.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
}

object MediaFormat {
    fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val s = (ms + 500) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
    fun resolution(w: Int, h: Int): String = if (w > 0 && h > 0) "${w}×${h}" else ""
}
