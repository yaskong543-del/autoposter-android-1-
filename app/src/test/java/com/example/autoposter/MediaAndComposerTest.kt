package com.example.autoposter

import org.junit.Assert.*
import org.junit.Test

class MediaAndComposerTest {
    private val items = listOf(
        Fx.media("a", "Beach Day.mp4", tags = "travel,goa", folder = "f1", added = 3, fp = "same"),
        Fx.media("b", "Sunset.jpg", MediaKind.IMAGE, tags = "goa", added = 2, fp = "x"),
        Fx.media("c", "beach-copy.mp4", tags = "", folder = null, added = 1, fp = "same", size = 9),
        Fx.media("d", "Recipe.mp4", tags = "food", folder = "f2", added = 4, fp = null)
    )
    private val folders = mapOf("f1" to "Holiday", "f2" to "Kitchen")

    @Test fun searchMatchesNameTagsAndFolderCaseInsensitively() {
        assertEquals(listOf("a", "c"), MediaFilter(query = "BEACH").apply(items, folders).map { it.id }.sorted())
        assertEquals(listOf("a", "b"), MediaFilter(query = "#goa").apply(items, folders).map { it.id }.sorted())
        assertEquals(listOf("a"), MediaFilter(query = "holiday").apply(items, folders).map { it.id })
        assertEquals(listOf("a"), MediaFilter(query = "beach goa").apply(items, folders).map { it.id })
    }

    @Test fun filtersByKindFolderTagAndUnfiled() {
        assertEquals(listOf("b"), MediaFilter(kind = MediaKind.IMAGE).apply(items).map { it.id })
        assertEquals(listOf("d"), MediaFilter(folderId = "f2").apply(items).map { it.id })
        assertEquals(listOf("b", "c"), MediaFilter(unfiledOnly = true).apply(items).map { it.id }.sorted())
        assertEquals(listOf("d"), MediaFilter(tag = "FOOD").apply(items).map { it.id })
    }

    @Test fun sorting() {
        assertEquals(listOf("d", "a", "b", "c"), MediaFilter().apply(items).map { it.id })
        assertEquals(listOf("c", "b", "a", "d"), MediaFilter(sort = MediaSort.OLDEST).apply(items).map { it.id })
        assertEquals("a", MediaFilter(sort = MediaSort.NAME).apply(items).first().id)
        assertEquals("a", MediaFilter(sort = MediaSort.LARGEST).apply(items).first().id)
    }

    @Test fun duplicateDetectionUsesFingerprintAndKeepsTheOldest() {
        assertEquals(setOf("a", "c"), MediaDuplicates.duplicateIds(items))
        assertEquals(listOf("a", "c"), MediaFilter(duplicatesOnly = true).apply(items).map { it.id }.sorted())
        assertEquals(listOf("a"), MediaDuplicates.removable(items).map { it.id })  // c is older (added=1), so a is the extra
        assertTrue(MediaDuplicates.duplicateIds(listOf(Fx.media("n1", fp = null), Fx.media("n2", fp = null))).isEmpty())
    }

    @Test fun tagsAreNormalised() {
        assertEquals(listOf("travel", "goa", "new-york"), Tags.normalize("#Travel, goa  #GOA New-York!!"))
        assertEquals(30, Tags.normalize("x".repeat(80)).single().length)
        assertEquals("a,b", Tags.join(listOf("a", "b", "a")))
        assertEquals(listOf("a", "b"), Tags.split("a,b"))
        assertEquals(listOf("goa", "food", "travel"), MediaDuplicates.allTags(items))   // by count, then A-Z
    }

    @Test fun formatHelpers() {
        assertEquals("0:05", MediaFormat.duration(5000)); assertEquals("1:01:01", MediaFormat.duration(3_661_000)); assertEquals("", MediaFormat.duration(0))
        assertEquals("1080×1920", MediaFormat.resolution(1080, 1920)); assertEquals("", MediaFormat.resolution(0, 5))
    }

    @Test fun selectionKeepsOrderAndAppendsNewPicks() {
        assertEquals(listOf("c", "a", "d"), SelectionMerge.merge(listOf("c", "a", "z"), setOf("a", "c", "d"), listOf("a", "b", "c", "d")))
        assertEquals(emptyList<String>(), SelectionMerge.merge(listOf("a"), emptySet(), listOf("a")))
    }

    // ---- composer text ----
    @Test fun platformTextUsesOverrideThenSharedAndAppendsSignatureAndTags() {
        val c = ComposerContent("Shared caption", mapOf(Platform.TIKTOK to "TikTok only"), "travel #goa", signature = "— Chai Co")
        assertEquals("Shared caption\n\n— Chai Co\n\n#travel #goa", c.textFor(Platform.YOUTUBE).text)
        assertEquals("TikTok only\n\n— Chai Co\n\n#travel #goa", c.textFor(Platform.TIKTOK).text)
    }

    @Test fun textIsCutToPlatformLimitAndFlagged() {
        val c = ComposerContent("x".repeat(3000))
        assertTrue(c.textFor(Platform.INSTAGRAM).overLimit); assertEquals(2200, c.textFor(Platform.INSTAGRAM).text.length)
        assertFalse(c.textFor(Platform.YOUTUBE).overLimit)
    }

    @Test fun hashtagsAreCappedPerPlatform() {
        val tags = (1..40).joinToString(" ") { "t$it" }
        assertEquals(Platform.TIKTOK.tagLimit, ComposerContent("c", hashtags = tags).textFor(Platform.TIKTOK).tags.size)
    }

    @Test fun suggestionChipsNeverDuplicate() {
        assertEquals(listOf("#B"), ComposerRules.newTags(listOf("a", "#B", "b"), "#a"))
        assertEquals("#a #b #c", ComposerRules.appendTags("#a", listOf("b", "#c", "A")))
    }

    @Test fun composerProblems() {
        val c = ComposerContent("hi")
        assertEquals(listOf(ComposerProblem.NO_MEDIA, ComposerProblem.NO_ACCOUNT), ComposerRules.problems(0, 0, c, emptyList(), false))
        assertTrue(ComposerProblem.ONLY_IMAGES in ComposerRules.problems(2, 1, c, listOf(Platform.YOUTUBE), hasVideo = false))
        assertTrue(ComposerProblem.NO_CAPTION in ComposerRules.problems(1, 1, ComposerContent(""), listOf(Platform.YOUTUBE), true))
        assertTrue(ComposerRules.problems(1, 1, c, listOf(Platform.YOUTUBE), true).isEmpty())
    }
}
