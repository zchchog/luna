package tv.luna.player.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.logos.shownLogo
import tv.luna.player.data.repo.GuideProgramme
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.ui.clockTime
import tv.luna.player.ui.dayDate

/**
 * Recording actions for the UI, the alarms and the player. The service does the recording; this decides whether a
 * recording may start (connection limit, decisions 8–10) and keeps the rows.
 */
class RecordingRepository(
    private val db: AppDatabase,
    private val settings: suspend () -> AppSettings,
    private val arm: (RecordingEntity) -> Unit,
    private val disarm: (Long) -> Unit,
    /** The login's stream limit, refreshed from the provider when it can be; null = unknown. */
    private val limitOf: suspend (providerId: Long) -> Int?,
    /** The last known limit, without a network call (the player guard runs on every play). */
    private val storedLimit: (providerId: Long) -> Int?,
    private val activeProviderId: () -> Long?,
    private val startService: (Long) -> Unit,
    private val stopService: (Long) -> Unit,
    private val stopPlayer: () -> Unit,
    /** The player is showing a provider stream (not a recording). */
    private val watching: () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.recordingDao()
    val prompt = MutableStateFlow<Prompt?>(null)
    /** Recordings the service is running now, by id (set by the service). */
    val running = MutableStateFlow<Map<Long, RecordingEntity>>(emptyMap())
    /** "Keep recording" was picked: further plays are refused with a notice, not asked again, until recordings end. */
    private var declined = false

    fun observe(): Flow<List<RecordingEntity>> = dao.observeAll()

    /** (channelId, programmeStart) of programmes scheduled or recording: the guide's red dot and the menus' mark. */
    fun marks(): Flow<Set<Pair<Long, Long>>> = dao.observeAll().map { l ->
        l.filter { it.status == RecStatus.SCHEDULED || it.status == RecStatus.RECORDING }
            .mapNotNullTo(HashSet()) { r -> r.channelId?.let { c -> r.programmeStart?.let { c to it } } }
    }

    private fun row(ch: ChannelEntity, name: String, s: AppSettings, title: String, desc: String?, start: Long, end: Long, programmeStart: Long?) =
        RecordingEntity(providerId = ch.providerId, channelId = ch.id, channelName = name, channelLogo = shownLogo(ch, s.logoOrder),
            title = title, description = desc, scheduledStart = start, scheduledEnd = end, programmeStart = programmeStart)

    /** From now to the end of [current] (plus End late), or 4 hours without a guide. */
    suspend fun recordNow(ch: ChannelEntity, displayName: String, current: GuideProgramme?) {
        val s = settings()
        val t = now()
        val r = row(ch, displayName, s, current?.title ?: "Live recording", current?.desc, t,
            current?.let { it.stop + s.endLateMin * MIN } ?: (t + NO_GUIDE_CAP), null)
        val others = runningOn(ch.providerId)
        val w = watchingOn(ch.providerId)
        suspend fun go() { val id = dao.insert(r); if (started(r.copy(id = id))) prompt.value = Prompt.Notice("Recording ${r.title}") }
        when (gate(limitFor(ch.providerId), others.size + w, w)) {
            Gate.GO -> go()
            Gate.ASK -> prompt.value = Prompt.RecordNowStopsWatching(displayName) { stopPlayer(); scope.launch { go() } }
            Gate.NO_FREE -> prompt.value = Prompt.Notice("No free connection: ${others.first().title} is recording")
        }
    }

    suspend fun schedule(ch: ChannelEntity, displayName: String, p: GuideProgramme) {
        val s = settings()
        val new = row(ch, displayName, s, p.title, p.desc, maxOf(p.start - s.startEarlyMin * MIN, now()), p.stop + s.endLateMin * MIN, p.start)
        val (r, changed) = trimBackToBack(new, dao.onChannel(ch.id, listOf(RecStatus.SCHEDULED, RecStatus.RECORDING)))
        changed.forEach { dao.update(it) }
        arm(r.copy(id = dao.insert(r)))
        prompt.value = Prompt.Notice("Scheduled ${p.title}, ${dayDate(p.start)} ${clockTime(p.start)}")
    }

    /** A scheduled recording: forgotten (row and alarms). */
    suspend fun cancel(id: Long) { disarm(id); dao.delete(id) }

    /** A running recording: the service stops it and keeps what was saved. */
    fun stop(id: Long) = stopService(id)

    /** A finished one: its file (via [deleteFile]) and row. Running ones are stopped first by the UI (Stop recording). */
    suspend fun delete(id: Long, deleteFile: suspend (RecordingEntity) -> Unit) {
        val r = dao.get(id) ?: return
        if (r.status == RecStatus.SCHEDULED) return cancel(id)
        if (r.location != null) runCatching { deleteFile(r) }
        dao.delete(id)
    }

    suspend fun setPosition(id: Long, ms: Long) = dao.setPosition(id, ms)

    // Connection limits are per login, so only recordings and the player on the same playlist count.
    private fun runningOn(providerId: Long) = running.value.values.filter { it.providerId == providerId }
    private fun watchingOn(providerId: Long) = if (watching() && activeProviderId() == providerId) 1 else 0

    /** The provider's limit, or the last known one when it doesn't answer in time (an alarm must start promptly). */
    private suspend fun limitFor(providerId: Long) = withTimeoutOrNull(LIMIT_WAIT) { limitOf(providerId) } ?: storedLimit(providerId)

    private fun inUseFor(r: RecordingEntity): Pair<Int, Int> {
        // A back-to-back recording on the same channel hands its connection over at this start.
        val others = runningOn(r.providerId).count { it.channelId != r.channelId && it.id != r.id }
        val w = watchingOn(r.providerId)
        return others + w to w
    }

    /** One minute before a scheduled start (decision 8). */
    suspend fun onWarn(id: Long) {
        val r = dao.get(id)?.takeIf { it.status == RecStatus.SCHEDULED } ?: return
        if (!settings().recordingOn) return
        val (inUse, canFree) = inUseFor(r)
        if (gate(limitFor(r.providerId), inUse, canFree) == Gate.ASK) prompt.value = Prompt.StartsSoon(id, r.title, r.channelName, r.scheduledStart)
    }

    fun keepWatching(id: Long) {
        scope.launch { dao.get(id)?.let { dao.update(it.copy(skipAsked = true)) } }
        if ((prompt.value as? Prompt.StartsSoon)?.id == id) prompt.value = null
    }

    /** A scheduled start: record, skip or fail, by the answer at the warning and the connection limit. */
    suspend fun onStart(id: Long) {
        if (id in running.value) return // the alarm and the app's start-up re-arm can both start it
        val r = dao.get(id)?.takeIf { it.status == RecStatus.SCHEDULED } ?: return
        if ((prompt.value as? Prompt.StartsSoon)?.id == id) prompt.value = null
        fun end(status: RecStatus, reason: String) = scope.launch { dao.update(r.copy(status = status, statusReason = reason)) }
        if (r.skipAsked) { dao.update(r.copy(status = RecStatus.SKIPPED, statusReason = "Skipped — you were watching")); return }
        if (r.channelId == null || db.channelDao().get(r.channelId) == null) {
            dao.update(r.copy(status = RecStatus.FAILED, statusReason = "Channel no longer in the playlist")); return
        }
        val (inUse, canFree) = inUseFor(r)
        when (gate(limitFor(r.providerId), inUse, canFree)) {
            Gate.NO_FREE -> { dao.update(r.copy(status = RecStatus.FAILED, statusReason = "No free connection")); return }
            Gate.ASK -> { stopPlayer(); prompt.value = Prompt.Notice("Recording ${r.title} — watching stopped", leavePlayer = true) }
            Gate.GO -> Unit
        }
        running.value.values.filter { it.channelId == r.channelId && it.id != r.id }.forEach { stopService(it.id) }
        started(r)
    }

    /** Counted as running at once, so a second start in the same moment sees the connection taken. False = it couldn't start. */
    private suspend fun started(r: RecordingEntity): Boolean {
        running.update { it + (r.id to r.copy(status = RecStatus.RECORDING, actualStart = now())) }
        return try { startService(r.id); true } catch (e: Exception) { // e.g. Android refusing a foreground service start
            running.update { it - r.id }
            dao.update(r.copy(status = RecStatus.FAILED, statusReason = "Failed — the recording couldn't start"))
            false
        }
    }

    /** The player's guard (decision 9): true = don't start this stream now. */
    fun blocksWatching(retry: () -> Unit): Boolean {
        if (running.value.isEmpty()) declined = false
        val rec = activeProviderId()?.let(::runningOn).orEmpty()
        if (rec.isEmpty()) return false
        if (gate(activeProviderId()?.let(storedLimit), rec.size, rec.size) == Gate.GO) return false
        val newest = rec.maxBy { it.actualStart ?: it.scheduledStart }
        prompt.value = if (declined) Prompt.Notice("Recording ${newest.title} is using your only connection")
            else Prompt.StopRecordingToWatch(newest.title) {
                stop(newest.id)
                // Retry once the recording has let go of the connection (or after a while, if it hangs).
                scope.launch { withTimeoutOrNull(RELEASE_WAIT) { running.first { newest.id !in it } }; retry() }
            }
        return true
    }

    fun keepRecording() { declined = true; prompt.value = null }

    /** Recording switched off: scheduled ones are forgotten, running ones stop and keep what they saved. */
    suspend fun turnOff() {
        dao.withStatus(RecStatus.SCHEDULED).forEach { cancel(it.id) }
        running.value.keys.forEach(stopService)
    }

    private companion object {
        const val MIN = 60_000L
        const val NO_GUIDE_CAP = 4 * 60 * MIN
        const val LIMIT_WAIT = 3_000L
        const val RELEASE_WAIT = 5_000L
    }
}
