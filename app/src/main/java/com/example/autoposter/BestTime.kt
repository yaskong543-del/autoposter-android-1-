package com.example.autoposter

import java.util.Calendar

/**
 * Suggests start times. YouTube uses your own best-performing hours once you have enough view data;
 * everything else uses common evening/midday starting points. These are starting points, not guarantees.
 */
object BestTime {
    private val defaults = mapOf(
        Platform.YOUTUBE to listOf(17, 20), Platform.INSTAGRAM to listOf(11, 19), Platform.TIKTOK to listOf(19, 21)
    )

    fun hours(p: Platform, items: List<PostItem>): List<Int> {
        if (p == Platform.YOUTUBE) {
            val withViews = items.filter { it.platform == p && it.views >= 0 }
            if (withViews.size >= 8) {
                val byHour = withViews.groupBy { hourOf(it.scheduledAt) }.mapValues { e -> e.value.map { it.views }.average() }
                return byHour.entries.sortedByDescending { it.value }.take(2).map { it.key }
            }
        }
        return defaults.getValue(p)
    }

    private fun hourOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)

    /** Next occurrence of [hour]:00 that is at least [dayOffset] days after the first available one. */
    fun nextAt(hour: Int, dayOffset: Int = 0): Long {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis() + 5 * 60_000) c.add(Calendar.DAY_OF_YEAR, 1)
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return c.timeInMillis
    }
}
