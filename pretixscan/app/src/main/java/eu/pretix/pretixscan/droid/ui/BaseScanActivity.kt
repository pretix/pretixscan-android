package eu.pretix.pretixscan.droid.ui

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Base64
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.check.OnlineCheckProvider
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.Answer
import eu.pretix.libpretixsync.sync.SyncManager
import eu.pretix.libpretixui.android.questions.QuestionsDialog
import eu.pretix.libpretixui.android.questions.QuestionsDialogInterface
import eu.pretix.libpretixui.android.scanning.HardwareScanner
import eu.pretix.libpretixui.android.scanning.ScanReceiver
import eu.pretix.libpretixui.android.scanning.ScannerView
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.AndroidSentryImplementation
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.EventSelection
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.connectivity.ConnectivityChangedListener
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.lang.Integer.max
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale


interface ReloadableActivity {
    fun reload()
}

enum class ResultCardState {
    HIDDEN,
    SHOWN
}

enum class ResultState {
    EMPTY,
    LOADING,
    ERROR,
    DIALOG,
    WARNING,
    SUCCESS,
    SUCCESS_EXIT
}


abstract class BaseScanActivity : AppCompatActivity(), ReloadableActivity, ScannerView.ResultHandler, MediaPlayer.OnCompletionListener, ConnectivityChangedListener {

    protected val REQ_EVENT = 1

    private lateinit var sm: SyncManager
    lateinit var conf: AppConfig
    val bgScope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler()
    var mediaPlayers: MutableMap<Int, MediaPlayer> = mutableMapOf()

    var lastScanTime: Long = 0
    var lastScanCode: String = ""
    var lastIgnoreUnpaid: Boolean = false
    var lastScanResult: TicketCheckProvider.CheckResult? = null
    var keyboardBuffer: String = ""
    var dialog: QuestionsDialogInterface? = null
    private var pdialog: ProgressDialog? = null
    protected var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val dataWedgeHelper = DataWedgeHelper(this)

    var syncMessage = ""

    private var pendingPinAction: ((pin: String) -> Unit)? = null

    private val hardwareScanner = HardwareScanner(object : ScanReceiver {
        override fun scanResult(result: String) {
            if (result == lastScanCode && System.currentTimeMillis() - lastScanTime < 2500) {
                return
            }
            lastScanTime = System.currentTimeMillis()
            lastScanCode = result
            lastIgnoreUnpaid = false
            lastScanResult = null
            handleScan(result, null, !conf.unpaidAsk)
        }
    })

    override fun reload() {
        reloadSyncStatus()
    }

    abstract fun reloadSyncStatus()

    fun syncStatusText(): String {
        var text = ""
        val diff = System.currentTimeMillis() - conf.lastDownload
        if ((application as PretixScan).syncLock.isLocked) {
            if (syncMessage != "") {
                text = syncMessage
            } else {
                text = getString(R.string.sync_status_progress)
            }
        } else if (conf.lastDownload == 0L) {
            text = getString(R.string.sync_status_never)
        } else if (diff > 24 * 3600 * 1000) {
            val days = (diff / (24 * 3600 * 1000)).toInt()
            text = getResources().getQuantityString(R.plurals.sync_status_time_days, days, days)
        } else if (diff > 3600 * 1000) {
            val hours = (diff / (3600 * 1000)).toInt()
            text = getResources().getQuantityString(R.plurals.sync_status_time_hours, hours, hours)
        } else if (diff > 60 * 1000) {
            val mins = (diff / (60 * 1000)).toInt()
            text = getResources().getQuantityString(R.plurals.sync_status_time_minutes, mins, mins)
        } else {
            text = getString(R.string.sync_status_now);
        }

        if (!(application as PretixScan).syncLock.isLocked) {
            val checkins = (application as PretixScan).db.scanQueuedCheckInQueries.count().executeAsOne().toInt()
            val calls = (application as PretixScan).db.scanQueuedCallQueries.count().executeAsOne().toInt()
            text += " (" + resources.getQuantityString(R.plurals.sync_status_pending, checkins + calls, checkins + calls) + ")"
        }

        return text
    }

    override fun onCompletion(p0: MediaPlayer?) {
        p0?.seekTo(0)
    }

    @SuppressWarnings("ResourceType")
    private fun buildMediaPlayer() {
        val resourceIds = listOf(R.raw.enter, R.raw.exit, R.raw.error, R.raw.beep, R.raw.attention)
        for (r in resourceIds) {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mediaPlayer.setOnCompletionListener(this)
            // mediaPlayer.setOnErrorListener(this)
            try {
                val file = resources.openRawResourceFd(r)
                try {
                    mediaPlayer.setDataSource(file.fileDescriptor, file.startOffset, file.length)
                } finally {
                    file.close();
                }
                mediaPlayer.setVolume(0.2f, 0.2f)
                mediaPlayer.prepare()
                mediaPlayers[r] = mediaPlayer
            } catch (ioe: IOException) {
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conf = AppConfig(this)

        getRestrictions(this)

        volumeControlStream = AudioManager.STREAM_MUSIC
        buildMediaPlayer()

        if (!conf.deviceRegistered) {
            registerDevice()
            return
        }
        try {
            setupApi()
        } catch (e: IllegalStateException) {
            // IllegalStateException is thrown by db access -> Migrations.minVersionCallback
            Sentry.captureException(e)
            panicPleaseReinstall()
            return
        }

        if (conf.synchronizedEvents.isEmpty()) {
            selectEvent()
        } else if (conf.lastDownload < 1) {
            syncNow()
        }
        scheduleSync()

        if (dataWedgeHelper.isInstalled) {
            try {
                dataWedgeHelper.install()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        supportFragmentManager.setFragmentResultListener(PinDialog.RESULT_PIN, this) { _, bundle ->
            val pin = bundle.getString(PinDialog.RESULT_PIN)
            if (pin != null && conf.verifyPin(pin)) {
                (supportFragmentManager.findFragmentByTag(PinDialog.TAG) as? PinDialog)?.dismiss()
                pendingPinAction?.let { it(pin) }
            }
        }
    }

    open fun setupApi() {
        val api = PretixApi.fromConfig(conf, AndroidHttpClientFactory(application as PretixScan))

        sm = SyncManager(
                conf,
                api,
                AndroidSentryImplementation(),
                (application as PretixScan).db,
                (application as PretixScan).fileStorage,
                60000L,
                5 * 60000L,
                if (conf.syncOrders) SyncManager.Profile.PRETIXSCAN else SyncManager.Profile.PRETIXSCAN_ONLINE,
                conf.printBadges,
                BuildConfig.VERSION_CODE,
                JSONObject(),
                Build.BRAND,
                Build.MODEL,
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.BASE_OS else "").ifEmpty { "Android" },
                Build.VERSION.RELEASE,
                "pretixSCAN Android",
                BuildConfig.VERSION_NAME,
                null,
                null,
                (application as PretixScan).connectivityHelper
        )
    }

    open fun selectEvent() {
        val intent = Intent(this, EventConfigActivity::class.java)
        startWithPIN(intent, "switch_event", REQ_EVENT, null)
    }

    private fun registerDevice() {
        val intent = Intent(this, WelcomeActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun panicPleaseReinstall() {
        val intent = Intent(this, PleaseReinstallActivity::class.java)
        startActivity(intent)
        finish()
    }

    private val syncRunnable = Runnable {
        syncMessage = ""
        val activity = this
        bgScope.launch {
            if (!(application as PretixScan).syncLock.tryLock()) {
                runOnUiThread {
                    reloadSyncStatus()
                }
                scheduleSync()
                return@launch
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            try {
                if (prefs.getBoolean("pref_sync_auto", true)) {
                    val result = sm.sync(false) {
                        runOnUiThread {
                            syncMessage = it
                            reloadSyncStatus()
                        }
                    }
                    if (result.isDataDownloaded) {
                        runOnUiThread {
                            reload()
                        }
                    }
                }
                runOnUiThread {
                    reloadSyncStatus()
                    scheduleSync()
                }
            } catch (e: SyncManager.EventSwitchRequested) {
                runOnUiThread {
                    conf.eventSelection = listOf(EventSelection(
                            eventSlug = e.eventSlug,
                            eventName = e.eventName,
                            subEventId = e.subeventId,
                            checkInList = e.checkinlistId,
                            dateFrom = null,
                            dateTo = null
                    ))
                    setupApi()
                    syncNow()
                    reload()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    reload()
                }
            } finally {
                (application as PretixScan).syncLock.unlock()
                runOnUiThread {
                    reloadSyncStatus()
                }
            }
        }
    }

    fun scheduleSync() {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, 1000)
    }

    fun syncNow() {
        if (isDestroyed) return
        syncMessage = ""
        pdialog = ProgressDialog(this).apply {
            isIndeterminate = true
            setMessage(getString(R.string.progress_syncing))
            setTitle(R.string.progress_syncing)
            setCanceledOnTouchOutside(false)
            setCancelable(false)
            show()
        }
        val activity = this
        bgScope.launch {
            if (!(application as PretixScan).syncLock.tryLock()) {
                runOnUiThread {
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.error_sync_in_background).create().show()
                    pdialog?.dismiss()
                }
                return@launch
            }
            try {
                sm.sync(true) { current_action ->
                    runOnUiThread {
                        if (isDestroyed) {
                            return@runOnUiThread
                        }
                        reloadSyncStatus()
                        syncMessage = current_action
                        pdialog?.setMessage(current_action)
                    }
                }
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    reload()
                    pdialog?.dismiss()
                    if (conf.lastFailedSync > 0) {
                        MaterialAlertDialogBuilder(activity).setMessage(conf.lastFailedSyncMsg).create().show()
                    }
                }
            } catch (e: SyncManager.EventSwitchRequested) {
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    pdialog?.dismiss()
                    conf.eventSelection = listOf(EventSelection(
                            eventSlug = e.eventSlug,
                            eventName = e.eventName,
                            subEventId = e.subeventId,
                            checkInList = e.checkinlistId,
                            dateFrom = null,
                            dateTo = null
                    ))
                    setupApi()
                    syncNow()
                    reload()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    Sentry.captureException(e)
                    pdialog?.dismiss()
                    MaterialAlertDialogBuilder(activity)
                        .setMessage(e.message ?: getString(R.string.error_unknown_exception))
                        .create().show()
                }
            } finally {
                (application as PretixScan).syncLock.unlock()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            reload()
            setupApi()
        } catch (e: IllegalStateException) {
            // IllegalStateException is thrown by db access -> Migrations.minVersionCallback
            Sentry.captureException(e)
            panicPleaseReinstall()
            return
        }
        getRestrictions(this)

        scheduleSync()

        hardwareScanner.start(this)

        (application as PretixScan).connectivityHelper.addListener(this)

        val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkAvailable(connectivityManager, network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                onNetworkChanged(connectivityManager, network)
            }

            override fun onLost(network: Network) {
                onNetworkLost(connectivityManager, network)
            }
        }
        onNetworkLost(connectivityManager, null)
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    protected open fun onNetworkAvailable(connectivityManager: ConnectivityManager, network: Network) {
        (application as PretixScan).connectivityHelper.setHardOffline(false)
    }

    protected open fun onNetworkChanged(connectivityManager: ConnectivityManager, network: Network) {
    }

    protected open fun onNetworkLost(connectivityManager: ConnectivityManager, network: Network?) {
        (application as PretixScan).connectivityHelper.setHardOffline(connectivityManager.activeNetworkInfo?.isConnectedOrConnecting != true)
    }

    override fun onPause() {
        handler.removeCallbacks(syncRunnable)
        (application as PretixScan).connectivityHelper.removeListener(this)
        super.onPause()
        hardwareScanner.stop(this)

        if (networkCallback != null) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback!!)
        }
    }

    open fun handleScan(raw_result: String, answers: MutableList<Answer>?, ignore_unpaid: Boolean = false) {
        if (dialog?.isShowing() == true) {
            /*
             * Skip scan if a dialog is still in front. This forces users to answer the questions asked.
             */
            return
        }
        hardwareScanner.stop(this)

        val result = if (Regex("^HC1:[0-9A-Z $%*+-./:]+$").matches(raw_result.uppercase(Locale.getDefault()))) {
            /*
             * This is a bit of a hack. pretixSCAN 1.11-2.8.2 supports checking digital COVID vaccination
             * certificates. When scanning them at the correct time, we have a high level of privacy
             * since we do not store any personal data contained in the certificate. However, if you
             * accidentally scan the certificate when you are supposed to scan a ticket, our fancy
             * error log will cause the verbatim vaccination certificate to be stored on the server.
             * Not really our fault, but also not really nice to store that sensitive health info.
             * However, it's still helpful for debugging to see how often an invalid code was scanned.
             * So if we encounter something that looks like an EU DGC, we'll just transform it into
             * a hashed version.
             *
             * This hack is safe for pretix' default signature schemes, as they would never generate
             * a QR code starting with ``HC1:``, but it could theoretically be unsafe for third-party
             * plugins.
             */
            val md = MessageDigest.getInstance("SHA-256")
            md.update(raw_result.toByteArray(Charset.defaultCharset()))
            "HC1:hashed:" + Base64.encodeToString(md.digest(), Base64.URL_SAFE)
        } else {
            raw_result
        }

        if (answers == null && !ignore_unpaid && !conf.offlineMode && conf.sounds) {
            mediaPlayers[R.raw.beep]?.start()
        }

        bgScope.launch {
            var checkResult: TicketCheckProvider.CheckResult? = null
            val provider = (application as PretixScan).getCheckProvider(conf)
            val startedAt = System.currentTimeMillis()
            try {
                checkResult = provider.check(conf.eventSelectionToMap(), result, "barcode", answers, ignore_unpaid, conf.printBadges, when (conf.scanType) {
                    "exit" -> TicketCheckProvider.CheckInType.EXIT
                    else -> TicketCheckProvider.CheckInType.ENTRY
                }, allowQuestions = !conf.ignoreQuestions, useOrderLocale = true)
                if (provider is OnlineCheckProvider) {
                    if (checkResult?.type == TicketCheckProvider.CheckResult.Type.ERROR) {
                        (application as PretixScan).connectivityHelper.recordError()
                    } else {
                        (application as PretixScan).connectivityHelper.recordSuccess(System.currentTimeMillis() - startedAt)
                    }
                }
            } catch (e: Exception) {
                Sentry.captureException(e)
                if (BuildConfig.DEBUG) {
                    e.printStackTrace()
                }
                (application as PretixScan).connectivityHelper.recordError()
                checkResult = TicketCheckProvider.CheckResult(TicketCheckProvider.CheckResult.Type.INVALID, getString(R.string.error_unknown_exception))
            }
            runOnUiThread {
                lastScanResult = checkResult!!
                lastIgnoreUnpaid = ignore_unpaid
                displayScanResult(checkResult, answers, ignore_unpaid)
            }
        }
    }

    abstract fun displayScanResult(result: TicketCheckProvider.CheckResult, answers: MutableList<Answer>?, ignore_unpaid: Boolean = false)

    fun showQuestionsDialog(res: TicketCheckProvider.CheckResult, secret: String, ignore_unpaid: Boolean,
                            values: Map<String, String>?, isResumed: Boolean,
                            retryHandler: ((String, MutableList<Answer>, Boolean) -> Unit)): QuestionsDialogInterface {
        val questions = res.requiredAnswers!!.map { it.question.toModel() }
        for (q in questions) {
            q.resolveDependency(questions)
        }
        val values_ = if (values == null) {
            val v = mutableMapOf<String, String>()
            res.requiredAnswers!!.forEach {
                if (!it.currentValue.isNullOrBlank()) {
                    v[it.question.toModel().identifier] = it.currentValue!!
                }
            }
            v
        } else {
            values
        }
        val attendeeName = if (conf.hideNames || res.position?.isNull("attendee_name") != false) {
            ""
        } else {
            res.position?.optString("attendee_name")
        }

        var attendeeDOB: String? = null
        if (!conf.hideNames) {
            val qlen = res.position?.getJSONArray("answers")?.length() ?: 0
            for (i in 0 until qlen) {
                val answ = res.position!!.getJSONArray("answers")!!.getJSONObject(i)
                if (answ.getString("question_identifier") == "dob") {
                    attendeeDOB = answ.getString("answer")
                }
            }
        }

        return eu.pretix.libpretixui.android.questions.showQuestionsDialog(
                QuestionsDialog.Companion.QuestionsType.LINE_ITEM_QUESTIONS,
                this,
                questions,
                values_,
                null,
                null,
                { answers -> retryHandler(secret, answers, ignore_unpaid) },
                null,
                attendeeName,
                attendeeDOB,
                res.orderCodeAndPositionId(),
                if (res.ticket != null) {
                    if (res.variation != null) {
                        res.ticket + " – " + res.variation
                    } else {
                        res.ticket
                    }
                } else {
                    null
                },
                !conf.useCamera,
                isResumed
        )
    }

    override fun handleResult(rawResult: ScannerView.Result) {
        val s = rawResult.text
        if (s == lastScanCode && System.currentTimeMillis() - lastScanTime < 5000) {
            return
        }
        lastScanTime = System.currentTimeMillis()
        lastScanCode = s
        lastScanResult = null
        lastIgnoreUnpaid = false
        handleScan(s, null, !conf.unpaidAsk)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_MULTIPLE) || (currentFocus is TextView && currentFocus !is AppCompatButton)) {
            return super.dispatchKeyEvent(event)
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB -> {
                if (keyboardBuffer.isEmpty()) {
                    return false
                }
                lastScanTime = System.currentTimeMillis()
                lastScanCode = keyboardBuffer
                lastScanResult = null
                lastIgnoreUnpaid = false
                handleScan(keyboardBuffer, null, !conf.unpaidAsk)
                keyboardBuffer = ""
                true
            }
            KeyEvent.KEYCODE_UNKNOWN -> {
                keyboardBuffer += event.characters
                true
            }
            else -> {
                val codepoint = event.keyCharacterMap.get(event.keyCode, event.metaState)
                if (codepoint > 0) {
                    keyboardBuffer += codepoint.toChar().toString().repeat(max(event.repeatCount, 1))
                    true
                } else {
                    super.dispatchKeyEvent(event)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_EVENT) {
            if (resultCode == RESULT_OK) {
                setupApi()
                scheduleSync()
                reload()
            }
        } else if (dialog?.handleActivityResult(requestCode, resultCode, data) == true) {
            return
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun pinProtect(key: String, valid: ((pin: String) -> Unit)) {
        if (!conf.requiresPin(key)) {
            valid("")
            return
        }
        pendingPinAction = valid
        PinDialog().show(supportFragmentManager)
    }

    fun startWithPIN(intent: Intent, key: String, resultCode: Int? = null, bundle: Bundle? = null) {
        pinProtect(key) { pin ->
            intent.putExtra("pin", pin)
            if (resultCode != null) {
                startActivityForResult(intent, resultCode, bundle)
            } else {
                startActivity(intent)
            }
        }
    }

    fun getRestrictions(ctx: Context) {
        val myRestrictionsMgr = ctx.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager?
                ?: return
        val restrictions = myRestrictionsMgr.applicationRestrictions
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        for (key in restrictions.keySet()) {
            if (key == "pref_auto_print_badges_option") {
                prefs.edit().putString(key, restrictions.getString(key)).apply()
            } else {
                prefs.edit().putBoolean(key, restrictions.getBoolean(key)).apply()
            }
        }
    }

    override fun onConnectivityChanged() {
        runOnUiThread {
            reload()
        }
    }
}
