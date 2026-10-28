package io.github.twinsen81.lustro.sample

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * Owns the sample's actions and their results. It outlives a rotation, so a
 * running sync or a request in flight keeps its place in the result bar.
 */
internal class SampleViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SampleApplication
    private val io = Executors.newFixedThreadPool(IO_THREADS)
    private val main = Handler(Looper.getMainLooper())
    private val requests = DemoRequests(application, { app.httpClient }, io)
    private val demoLogs = DemoLogs()
    private val demoPrefs = DemoPrefs(application)
    private val demoDataStore = DemoDataStore(application, viewModelScope)
    private val demoSqlite = DemoSqlite(application, io)
    private val demoWork = DemoWork(application)

    val events = EventLog()

    private val webSocket = WebSocketDemo(app.webSockets, events)

    private val syncLabel = MutableStateFlow<String?>(null)
    private var syncRun: Feedback? = null
    private var syncCount = 0
    private val syncTick =
        object : Runnable {
            override fun run() {
                val feedback = syncRun ?: return
                syncCount++
                syncLabel.value = "Stop sync · $syncCount"
                // Same method+path every tick, so with the Network tab's "Overwrite"
                // toggle on the repeats collapse into a single row (request
                // compaction). A production app would schedule periodic sync with
                // WorkManager; a foreground ticker just keeps the demo observable
                // within seconds.
                requests.send(Request.Builder().url("${DemoRequests.BASE}/anything/sync").get().build(), feedback.quiet(), suffix = "#$syncCount")
                main.postDelayed(this, SYNC_INTERVAL_MS)
            }
        }

    private val tickerOn = MutableStateFlow(true)
    private var foreground = false
    private val serverOn = MutableStateFlow(LustroBootstrap.isRunning())

    val consoleAvailable: Boolean = LustroBootstrap.CONSOLE_AVAILABLE

    /** Whether the log ticker writes its lines while the app is in the foreground. */
    val ticker: StateFlow<Boolean> = tickerOn.asStateFlow()

    val serverRunning: StateFlow<Boolean> = serverOn.asStateFlow()

    /** Null in the release build, which has no Feature Flags tab. */
    val flags: StateFlow<List<SampleFlag>>? = LustroBootstrap.flags()

    val network: List<ActionGroup> =
        DemoCatalog.network(
            requests = requests,
            webSocket = webSocket,
            sync =
                DemoAction(
                    START_SYNC_LABEL,
                    chip = "Start sync",
                    detail = "Sends GET /anything/sync every 2 s until you stop it. Turn on Overwrite in the Network tab to keep only the newest copy.",
                    live = syncLabel.asStateFlow(),
                ) { toggleSync(it) },
            extras = LustroBootstrap.extraDemoRequests(DemoRequests.BASE),
        )

    val logActions: List<DemoAction> = DemoCatalog.logs(demoLogs)

    val prefsActions: List<DemoAction> = DemoCatalog.prefs(demoPrefs, demoDataStore)

    /** The settings file as the app reads it: an edit from the console shows here. */
    val prefsValues: StateFlow<List<Pair<String, String>>> = demoPrefs.settingsValues

    val sqliteActions: List<DemoAction> = DemoCatalog.sqlite(demoSqlite)

    /** How many notes and products the app reads, after its last write. */
    val sqliteCounts: StateFlow<Pair<Long, Long>?> = demoSqlite.rowCounts

    val workActions: List<DemoAction> = DemoCatalog.work(demoWork)

    /** How many work requests WorkManager has in each state. */
    val workCounts: Flow<Map<WorkInfo.State, Int>> = demoWork.counts

    /** The registered DataStore as the app reads it: an edit from the console shows here too. */
    val dataStoreValues: StateFlow<List<Pair<String, String>>> = demoDataStore.appStateValues

    // Lustro.stop() ends the debug server and what its tabs run, such as the Logs
    // tab's logcat process; Lustro.start() starts them again.
    val stopServer =
        DemoAction("Stop the Lustro server", chip = "Stop") {
            LustroBootstrap.stop()
            serverOn.value = LustroBootstrap.isRunning()
            it.ok("Lustro server stopped")
        }

    val startServer =
        DemoAction("Start the Lustro server", chip = "Start") {
            LustroBootstrap.restart()
            serverOn.value = LustroBootstrap.isRunning()
            if (serverOn.value) it.ok("Lustro server started on :$CONSOLE_PORT") else it.failed("Lustro server did not start")
        }

    // By label, the sync starts or stops; only its chip toggles.
    private val startSync = DemoAction(START_SYNC_LABEL) { if (syncRun == null) toggleSync(it) else it.info("Periodic sync is already running") }
    private val stopSync = DemoAction(STOP_SYNC_LABEL) { stopSync(it) }

    /** Every action, by the label that the `request` launch extra names. */
    val byLabel: Map<String, DemoAction> =
        (network.flatMap { it.actions } + logActions + prefsActions + sqliteActions + workActions + listOf(stopServer, startServer, startSync, stopSync))
            .associateBy { it.label }

    init {
        demoSqlite.refresh()
        flags?.let { source ->
            viewModelScope.launch {
                var previous = source.value.associate { it.id to it.enabled }
                source.collect { current ->
                    current.filter { previous[it.id] != null && previous[it.id] != it.enabled }.forEach { flag ->
                        events.start(null).info("Flag ${flag.id} is ${if (flag.enabled) "ON" else "OFF"}")
                    }
                    previous = current.associate { it.id to it.enabled }
                }
            }
        }
    }

    fun run(action: DemoAction) {
        val feedback = events.start(action.label)
        try {
            action.run(feedback)
        } catch (e: RuntimeException) {
            feedback.failed(action.chip, e.message)
        }
    }

    /** Runs the action that the `request` launch extra names. */
    fun runByLabel(label: String) {
        val action = byLabel[label]
        if (action == null) events.start(null).failed("No action is labelled \"$label\"") else run(action)
    }

    fun toggleTicker() {
        val feedback = events.start(TICKER_LABEL)
        if (tickerOn.value) {
            tickerOn.value = false
            demoLogs.stop()
            feedback.info("Log ticker paused")
        } else {
            tickerOn.value = true
            if (foreground) demoLogs.start()
            feedback.ok("Log ticker running")
        }
    }

    fun setFlag(id: String, enabled: Boolean) {
        LustroBootstrap.setFlag(id, enabled)
    }

    fun copy(command: String) {
        val feedback = events.start(copyLabel(command))
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("command", command))
        feedback.ok("Copied: $command")
    }

    fun onForeground() {
        foreground = true
        if (tickerOn.value) demoLogs.start()
    }

    fun onBackground() {
        foreground = false
        demoLogs.stop()
    }

    override fun onCleared() {
        syncRun = null
        main.removeCallbacks(syncTick)
        demoLogs.stop()
        demoPrefs.close()
        io.shutdownNow()
    }

    private fun toggleSync(feedback: Feedback) {
        if (syncRun == null) {
            syncRun = feedback
            syncCount = 0
            syncLabel.value = "Stop sync · 0"
            feedback.ok("Periodic sync started")
            main.post(syncTick)
        } else {
            stopSync(feedback)
        }
    }

    private fun stopSync(feedback: Feedback) {
        if (syncRun == null) return feedback.info("Periodic sync is not running")
        syncRun = null
        main.removeCallbacks(syncTick)
        syncLabel.value = null
        feedback.ok("Periodic sync stopped after $syncCount requests")
    }

    companion object {
        const val TICKER_LABEL: String = "Log ticker"
        private const val START_SYNC_LABEL = "Start periodic sync"
        private const val STOP_SYNC_LABEL = "Stop periodic sync"
        private const val IO_THREADS = 4
        private const val SYNC_INTERVAL_MS = 2_000L

        fun copyLabel(command: String): String = "Copy $command"
    }
}
