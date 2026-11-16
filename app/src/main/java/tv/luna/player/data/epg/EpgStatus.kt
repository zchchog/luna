package tv.luna.player.data.epg

import androidx.room.withTransaction
import tv.luna.player.data.db.AppDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Source status after a good import (spec §2.2), e.g. `OK · 12:04 · 229k programmes`. */
fun epgOkStatus(at: Long, programmes: Int, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    val n = if (programmes < 1000) "$programmes" else "${programmes / 1000}k"
    return "OK · $time · $n programmes"
}

fun epgErrorStatus(e: Throwable): String = (e.message ?: e::class.simpleName ?: "Error").take(120)

/** `+1:30`, `-0:15`, `0:00`. */
fun formatOffset(minutes: Int): String {
    val sign = if (minutes > 0) "+" else if (minutes < 0) "-" else ""
    val m = kotlin.math.abs(minutes)
    return "$sign${m / 60}:${"%02d".format(m % 60)}"
}

/** −12:00 to +12:00 in 15-minute steps (spec §5). */
val OFFSET_STEPS = (-720..720 step 15).toList()

/** Clear guide data: programmes, XMLTV channels and automatic links go; manual links stay. */
suspend fun clearGuide(db: AppDatabase) = db.withTransaction {
    db.programmeDao().deleteAll()
    db.epgDao().deleteAllChannels()
    db.epgDao().deleteAllAutoLinks()
    db.epgDao().clearAllStatus()
}
