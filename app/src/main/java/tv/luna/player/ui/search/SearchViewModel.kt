package tv.luna.player.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp
import tv.luna.player.data.catalogue.parseStripList
import tv.luna.player.data.search.SearchResults
import tv.luna.player.data.search.queryWords
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.data.settings.LogoKind

/**
 * Results after a 300 ms typing pause; a newer query cancels the running one. Blank = null at once, no search.
 * A [version] change (catalogue write: hide, rename, sync) re-runs the same query so shown results never go stale.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
fun searchFlow(query: Flow<String>, version: Flow<Long> = kotlinx.coroutines.flow.flowOf(0L), search: suspend (String) -> SearchResults): Flow<SearchResults?> =
    query.debounce { if (queryWords(it).isEmpty()) 0L else 300L }
        .distinctUntilChanged()
        .combine(version) { q, v -> q to v }
        .distinctUntilChanged()
        .mapLatest { (q, _) -> if (queryWords(q).isEmpty()) null else search(q) }

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container

    val query = MutableStateFlow("")

    /** null = nothing searched. */
    val results: StateFlow<SearchResults?> = searchFlow(query, c.search.version) { q ->
        val p = c.providerRepository.activeProviderId.first() ?: return@searchFlow SearchResults.EMPTY
        val strip = parseStripList(c.db.providerDao().get(p)?.nameStrip.orEmpty())
        c.search.universal(p, strip, q)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val logoSource: StateFlow<List<LogoKind>> = c.settings.settings.map { it.logoOrder }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings().logoOrder)

    // Observed, so tiles follow an edit of Channel name clean-up while this (activity-long) VM lives.
    val strip: StateFlow<List<String>> = c.providerRepository.activeProviderId
        .flatMapLatest { p -> p?.let { c.db.providerDao().observe(it) } ?: kotlinx.coroutines.flow.flowOf(null) }
        .map { parseStripList(it?.nameStrip.orEmpty()) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Builds the name indexes while the user starts typing (the first build takes seconds on a Chromecast). */
    fun warm() {
        viewModelScope.launch {
            val p = c.providerRepository.activeProviderId.first() ?: return@launch
            c.search.warm(p, parseStripList(c.db.providerDao().get(p)?.nameStrip.orEmpty()))
        }
    }

    /** The result opened last (section 0–2, index), focused again on return from full screen or detail; null = a fresh visit. */
    var returnTo: Pair<Int, Int>? = null
}
