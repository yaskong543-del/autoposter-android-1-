package com.example.autoposter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the v0.5 quick-publish parser against regressions. */
class CommandParserTest {
    private val engine = RuleEngine()

    @Test fun countCaptionAndPlatform() = runTest {
        val c = engine.parse("Post 3 videos caption \"Summer\" on youtube")!!
        assertEquals(3, c.count); assertEquals(listOf("Summer"), c.captions); assertEquals(setOf(Platform.YOUTUBE), c.platforms)
    }

    @Test fun allVideosAllAccountsEveryFiveMinutesToday() = runTest {
        val c = engine.parse("upload all videos from Camera folder today on all accounts every 5 minutes")!!
        assertEquals(100, c.count); assertEquals(listOf("*"), c.accounts); assertEquals(0, c.sinceDays); assertEquals(300, c.gapSeconds)
    }

    @Test fun wordNumbersAndHashtags() = runTest {
        assertEquals(5, engine.parse("post five videos")!!.count)
        val c = engine.parse("post 2 videos caption \"Hi\" #travel #fun")!!
        assertEquals(listOf("#travel", "#fun"), c.hashtags); assertEquals(listOf("Hi"), c.captions)
    }

    @Test fun relativeStart() = runTest {
        val before = System.currentTimeMillis()
        val c = engine.parse("post 1 video in 2 hours")!!
        assertNotNull(c.startAt)
        assertTrue(c.startAt!! in (before + 7_200_000L - 5_000)..(before + 7_200_000L + 60_000))
    }

    @Test fun ignoresUnrelatedText() = runTest { assertNull(engine.parse("hello there")) }
}
