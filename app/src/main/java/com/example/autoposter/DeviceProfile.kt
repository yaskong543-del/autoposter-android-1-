package com.example.autoposter

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs

/** LITE = rules only. MID = ~1B model. HIGH = ~3B model. */
enum class Tier { LITE, MID, HIGH }

object DeviceProfile {
    fun detect(ctx: Context): Tier {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramGb = mi.totalMem / 1_073_741_824.0
        val freeGb = StatFs(ctx.filesDir.path).availableBytes / 1_000_000_000.0
        return when {
            ramGb < 4 || freeGb < 3 -> Tier.LITE
            ramGb < 7 -> Tier.MID
            else -> Tier.HIGH
        }
    }
}
