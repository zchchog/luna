package tv.luna.player

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IptvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Once per old playlist: encrypt its login and take it out of saved links (no-op afterwards).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.search // created here, not lazily on the main thread (it registers a Room observer)
            runCatching { container.providerRepository.sealOldRows() }.onFailure { android.util.Log.e("IptvApp", "Sealing logins failed", it) }
            // A fresh process runs no recording: alarms are set again (boot and updates clear them) and cut-off rows closed.
            runCatching { container.recordingScheduler.rearm { container.recordings.running.value.keys } }
                .onFailure { android.util.Log.e("IptvApp", "Setting recording alarms failed", it) }
        }
    }
}
