package tv.luna.player.recording

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")

private const val PART_MAX = 45

/** Cuts to at most [max] chars without leaving a lone high surrogate at the end. */
private fun cutSafe(s: String, max: Int): String {
    if (s.length <= max) return s
    val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
    return s.substring(0, end)
}

/** "‹channel› - ‹title› - ‹yyyy-mm-dd HHmm›.ts", safe on FAT/exFAT/NTFS/SMB. Built from names only, never a URL. Max 120 chars. */
fun recordingFileName(channel: String, title: String, start: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    fun clean(s: String) = cutSafe(s.replace(UNSAFE, " ").replace(Regex("\\s+"), " ").trim(' ', '.'), PART_MAX).trim(' ', '.')
    val stamp = STAMP.format(Instant.ofEpochMilli(start).atZone(zone))
    return "${clean(channel).ifEmpty { "Channel" }} - ${clean(title).ifEmpty { "Recording" }} - $stamp.ts"
}

/** [name], or "‹name› (2).ts", "(3)"… — the first that doesn't [exists]. */
fun uniqueName(name: String, exists: (String) -> Boolean): String {
    if (!exists(name)) return name
    val stem = name.removeSuffix(".ts")
    return generateSequence(2) { it + 1 }.map { "$stem ($it).ts" }.first { !exists(it) }
}
