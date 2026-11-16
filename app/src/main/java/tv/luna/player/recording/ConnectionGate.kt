package tv.luna.player.recording

enum class Gate { GO, ASK, NO_FREE }

/**
 * Can one more stream start? [limit] is the login's stream limit (null = unknown: just try), [inUse] how many this app
 * holds now, [canFree] how many of those the user could give up (the player). ASK = only by stopping one of those.
 */
fun gate(limit: Int?, inUse: Int, canFree: Int): Gate = when {
    limit == null || inUse < limit -> Gate.GO
    inUse - canFree < limit -> Gate.ASK
    else -> Gate.NO_FREE
}
