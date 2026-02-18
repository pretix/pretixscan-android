package eu.pretix.pretixscan.droid.ui

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.util.Base64
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SearchView
import androidx.core.animation.doOnEnd
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableField
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.check.CheckException
import eu.pretix.libpretixsync.check.OnlineCheckProvider
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.Answer
import eu.pretix.libpretixsync.models.db.toModel
import eu.pretix.libpretixsync.serialization.JSONArrayDeserializer
import eu.pretix.libpretixsync.serialization.JSONArraySerializer
import eu.pretix.libpretixsync.serialization.JSONObjectDeserializer
import eu.pretix.libpretixsync.serialization.JSONObjectSerializer
import eu.pretix.libpretixsync.sync.SyncManager
import eu.pretix.libpretixui.android.questions.QuestionsDialog
import eu.pretix.libpretixui.android.questions.QuestionsDialogInterface
import eu.pretix.libpretixui.android.scanning.HardwareScanner
import eu.pretix.libpretixui.android.scanning.ScanReceiver
import eu.pretix.libpretixui.android.scanning.ScannerView
import eu.pretix.pretixscan.droid.*
import eu.pretix.pretixscan.droid.connectivity.ConnectivityChangedListener
import eu.pretix.pretixscan.droid.databinding.ActivityMainBinding
import eu.pretix.pretixscan.droid.hardware.LED
import eu.pretix.pretixscan.droid.ui.ResultState.*
import eu.pretix.pretixscan.droid.ui.info.EventinfoActivity
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import splitties.toast.toast
import java.io.IOException
import java.lang.Integer.max
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.*


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

class ViewDataHolder(private val ctx: Context) {
    val resultState = ObservableField<ResultState>()
    val searchState = ObservableField<ResultState>()
    val resultText = ObservableField<String>()
    val resultOffline = ObservableField<Boolean>()
    val showPrint = ObservableField<Boolean>()
    val eventName = ObservableField<String>()
    val ticketAndVariationName = ObservableField<String>()
    val orderCodeAndPositionId = ObservableField<String>()
    val attendeeName = ObservableField<String>()
    val seat = ObservableField<String>()
    val reasonExplanation = ObservableField<String>()
    val questionAndAnswers = ObservableField<SpannableString>()
    val checkInTexts = ObservableField<String>()
    val firstScanned = ObservableField<String>()
    val attention = ObservableField<Boolean>()
    val hardwareScan = ObservableField<Boolean>()
    val kioskMode = ObservableField<Boolean>()
    val kioskWithAnimation = ObservableField<Boolean>()
    val badgePrintEnabled = ObservableField<Boolean>()
    val scanType = ObservableField<String>()
    val configDetails = ObservableField<String>()
    val isOffline = ObservableField<Boolean>()
    val hideTimerVisible = ObservableField<Boolean>()
    val hideTimerProgress = ObservableField<Int>()

    fun getColor(state: ResultState): Int {
        return ctx.resources.getColor(when (state) {
            EMPTY, DIALOG, LOADING -> R.color.pretix_brand_lightgrey
            ERROR -> R.color.pretix_brand_red
            WARNING -> R.color.pretix_brand_orange
            SUCCESS, SUCCESS_EXIT -> R.color.pretix_brand_green
        })
    }

    fun setLed(context: Context, state: ResultState, needsAttention: Boolean) {
        val led = LED(context)
        when (state) {
            EMPTY -> led.off()
            LOADING -> led.progress()
            DIALOG, WARNING -> led.attention(blink = needsAttention)
            ERROR -> led.error()
            SUCCESS, SUCCESS_EXIT -> led.success(blink = needsAttention)
        }
    }
}

class MainActivity : AppCompatActivity(), ReloadableActivity, ScannerView.ResultHandler, MediaPlayer.OnCompletionListener, ConnectivityChangedListener {

    private val REQ_EVENT = 1

    private lateinit var binding: ActivityMainBinding
    private lateinit var sm: SyncManager
    private lateinit var conf: AppConfig
    private val bgScope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler()
    private val hideHandler = Handler()
    private var hideAnimation: ValueAnimator? = null
    private var card_state = ResultCardState.HIDDEN
    private var view_data = ViewDataHolder(this)
    private var mediaPlayers: MutableMap<Int, MediaPlayer> = mutableMapOf()

    private var lastScanTime: Long = 0
    private var lastScanCode: String = ""
    private var lastIgnoreUnpaid: Boolean = false
    private var lastScanResult: TicketCheckProvider.CheckResult? = null
    private var keyboardBuffer: String = ""
    private var dialog: QuestionsDialogInterface? = null
    private var pdialog: ProgressDialog? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val dataWedgeHelper = DataWedgeHelper(this)

    private var searchAdapter: SearchListAdapter? = null
    private var searchFilter = ""

    private var syncMessage = ""

    private var pendingPinAction: ((pin: String) -> Unit)? = null

    companion object {
        const val PERMISSIONS_REQUEST_CAMERA = 1337
    }

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

        var confdetails = ""
        if (conf.deviceKnownGateName.isNotBlank()) {
            confdetails += getString(R.string.debug_info_gate, conf.deviceKnownGateName)
            confdetails += "\n"
        }
        if (!conf.kioskMode) {
            confdetails += getString(R.string.debug_info_device, conf.deviceKnownName)
            confdetails += "\n"
        }
        if (conf.synchronizedEvents.isNotEmpty()) {
            val events = (application as PretixScan).db.eventQueries.selectBySlugList(conf.synchronizedEvents)
                .executeAsList()
                .map { it.toModel() }

            for (event in events) {
                confdetails += getString(R.string.debug_info_event, event.name)
                confdetails += "\n"

                val subeventId = conf.getSelectedSubeventForEvent(event.slug)
                if (subeventId != null && subeventId > 0) {
                    val subevent = (application as PretixScan).db.subEventQueries.selectByServerId(subeventId)
                        .executeAsOneOrNull()
                        ?.toModel()

                    if (subevent != null) {
                        val df = DateTimeFormatter.ofPattern(getString(R.string.short_datetime_format))
                        confdetails += getString(R.string.debug_info_subevent, subevent.name, df.format(subevent.dateFrom))
                        confdetails += "\n"
                    }
                }

                val checkinListId = conf.getSelectedCheckinListForEvent(event.slug)
                if (checkinListId != null && checkinListId > 0) {
                    val cl = (application as PretixScan).db.checkInListQueries.selectByServerId(checkinListId)
                        .executeAsOneOrNull()
                        ?.toModel()
                    if (cl != null) {
                        confdetails += getString(R.string.debug_info_list, cl.name)
                        confdetails += "\n"
                    }
                }
            }
            if (!conf.kioskMode) {
                if (conf.proxyMode) {
                    confdetails += getString(R.string.checktype_proxy)
                } else if (conf.offlineMode) {
                    confdetails += getString(R.string.checktype_offline)
                } else {
                    confdetails += getString(R.string.checktype_online)
                }
                confdetails += "\n"
            }

        }
        view_data.configDetails.set(confdetails.trim())
        view_data.isOffline.set(conf.offlineMode)
    }

    private fun vibrate(success: Boolean) {
        val vibrator: Vibrator
        if (Build.VERSION.SDK_INT >= 31) {
            val vb = this.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vb.defaultVibrator
        } else {
            vibrator = this.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(if (success) 50L else 200L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(if (success) 50L else 200L)
        }
    }

    private fun setSearchFilter(f: String) {
        binding.cardSearch.visibility = View.VISIBLE
        view_data.searchState.set(LOADING)

        searchFilter = f
        bgScope.launch {
            val provider = (application as PretixScan).getCheckProvider(conf)
            try {
                val sr = provider.search(conf.eventSelectionToMap(), f, 1)
                if (f != searchFilter) {
                    // we lost a race! Abort this.
                    return@launch
                }
                searchAdapter = SearchListAdapter(sr, object : SearchResultClickedInterface {
                    override fun onSearchResultClicked(res: TicketCheckProvider.SearchResult) {
                        lastScanTime = System.currentTimeMillis()
                        lastScanCode = res.secret!!
                        lastScanResult = null
                        lastIgnoreUnpaid = false
                        hideSearchCard()
                        handleScan(res.secret!!, null, !conf.unpaidAsk)
                    }
                })
                runOnUiThread {
                    binding.recyclerViewSearch.adapter = searchAdapter
                    if (sr.size == 0) {
                        view_data.searchState.set(WARNING)
                    } else {
                        view_data.searchState.set(SUCCESS)
                    }
                }
            } catch (e: CheckException) {
                e.printStackTrace()
                runOnUiThread {
                    hideSearchCard()
                    toast(e.message ?: getString(R.string.error_unknown_exception))
                }
            } catch (e: Exception) {
                Sentry.captureException(e)
                if (BuildConfig.DEBUG) {
                    e.printStackTrace()
                }
                runOnUiThread {
                    hideSearchCard()
                    toast(R.string.error_unknown_exception)
                }
            }
        }
    }

    fun reloadSyncStatus() {
        if (conf.lastFailedSync > conf.lastSync || System.currentTimeMillis() - conf.lastDownload > 5 * 60 * 1000) {
            binding.textViewStatus.setTextColor(ContextCompat.getColor(this, R.color.pretix_brand_red))
        } else {
            binding.textViewStatus.setTextColor(ContextCompat.getColor(this, R.color.pretix_brand_green))
        }
        binding.textViewStatus.visibility = if (conf.proxyMode) View.GONE else View.VISIBLE
        var text = ""
        val diff = System.currentTimeMillis() - conf.lastDownload
        if ((application as PretixScan).syncLock.isLocked) {
            if (syncMessage != "") {
                text = syncMessage
            } else {
                text = getString(R.string.sync_status_progress);
            }
        } else if (conf.lastDownload == 0L) {
            text = getString(R.string.sync_status_never);
        } else if (diff > 24 * 3600 * 1000) {
            val days = (diff / (24 * 3600 * 1000)).toInt()
            text = getResources().getQuantityString(R.plurals.sync_status_time_days, days, days);
        } else if (diff > 3600 * 1000) {
            val hours = (diff / (3600 * 1000)).toInt()
            text = getResources().getQuantityString(R.plurals.sync_status_time_hours, hours, hours);
        } else if (diff > 60 * 1000) {
            val mins = (diff / (60 * 1000)).toInt()
            text = getResources().getQuantityString(R.plurals.sync_status_time_minutes, mins, mins);
        } else {
            text = getString(R.string.sync_status_now);
        }

        if (!(application as PretixScan).syncLock.isLocked) {
            val checkins = (application as PretixScan).db.scanQueuedCheckInQueries.count().executeAsOne().toInt()
            val calls = (application as PretixScan).db.scanQueuedCallQueries.count().executeAsOne().toInt()
            text += " (" + resources.getQuantityString(R.plurals.sync_status_pending, checkins + calls, checkins + calls) + ")"
        }

        binding.textViewStatus.setText(text)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_CAMERA -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,"Please grant camera permission to use the QR Scanner", Toast.LENGTH_SHORT).show()
                }
                return
            }
            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }

    private fun setUpEventListeners() {
        binding.mainToolbar.event.setOnClickListener {
            selectEvent()
        }

        binding.fabFocus.setOnClickListener {
            conf.scanFocus = !conf.scanFocus
            reloadCameraState()
        }

        binding.fabFlash.setOnClickListener {
            conf.scanFlash = !conf.scanFlash
            reloadCameraState()
        }

        binding.cardResult.setOnTouchListener(object : OnSwipeTouchListener(this) {
            override fun onSwipeLeft() {
                stopHidingTimer()
                card_state = ResultCardState.HIDDEN
                binding.cardResult.clearAnimation()
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                binding.cardResult.animate().translationX(-(displayMetrics.widthPixels + binding.cardResult.width) / 2f).setDuration(250).setInterpolator(DecelerateInterpolator()).alpha(0f).start()
                hideHandler.postDelayed(hideRunnable, 250)
            }

            override fun onSwipeRight() {
                stopHidingTimer()
                card_state = ResultCardState.HIDDEN
                binding.cardResult.clearAnimation()
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                binding.cardResult.animate().translationX((displayMetrics.widthPixels + binding.cardResult.width) / 2f).setDuration(250).setInterpolator(DecelerateInterpolator()).alpha(0f).start()
                hideHandler.postDelayed(hideRunnable, 250)
            }
        })

        binding.svCardOverflow.viewTreeObserver.addOnScrollChangedListener {
            stopHidingTimer()
        }
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
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        view_data.resultState.set(EMPTY)
        view_data.scanType.set(conf.scanType)
        view_data.hardwareScan.set(!conf.useCamera)
        binding.data = view_data

        setSupportActionBar(binding.mainToolbar.toolbar)
        // the toolbar should show/hide the title based on displayOptions in the different style variations
        // the used ToolbarActionBar class is explicitly *not* interpreting the style definitions
        // so we're doing that ourselves here:
        val ta = theme.obtainStyledAttributes(R.style.AppTheme_Actionbar, intArrayOf(R.attr.displayOptions))
        supportActionBar?.displayOptions = ta.getInt(0, 0)
        ta.recycle()

        ViewCompat.setOnApplyWindowInsetsListener(
            binding.content
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = insets.left,
                right = insets.right,
                top = 0, // handled by AppBar
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

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
        setUpEventListeners()

        if (conf.synchronizedEvents.isEmpty()) {
            selectEvent()
        } else if (conf.lastDownload < 1) {
            syncNow()
        }
        scheduleSync()
        checkPermission(Manifest.permission.CAMERA, PERMISSIONS_REQUEST_CAMERA)

        hideCard()
        hideSearchCard()
        binding.cardResult.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)

        if (dataWedgeHelper.isInstalled) {
            try {
                dataWedgeHelper.install()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        binding.recyclerViewSearch.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSearch.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(binding.recyclerViewSearch.context, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL))

        supportFragmentManager.setFragmentResultListener(PinDialog.RESULT_PIN, this) { _, bundle ->
            val pin = bundle.getString(PinDialog.RESULT_PIN)
            if (pin != null && conf.verifyPin(pin)) {
                (supportFragmentManager.findFragmentByTag(PinDialog.TAG) as? PinDialog)?.dismiss()
                pendingPinAction?.let { it(pin) }
            }
        }
    }

    private fun eventButtonText(): String {
        val s = conf.eventSelection
        if (s.size == 1) {
            return s[0].eventName
        } else {
            return getString(R.string.events_selected, s.size.toString())
        }
    }

    private fun setupApi() {
        val ebt = eventButtonText()
        if (binding.mainToolbar.event != null && binding.mainToolbar.event.text != ebt) {  // can be null if search bar is open
            binding.mainToolbar.event.text = ebt
            (binding.mainToolbar.event.parent as View?)?.forceLayout()
        }
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

    private fun selectEvent() {
        val intent = Intent(this, EventConfigActivity::class.java)
        if (binding.mainToolbar.event != null && ViewCompat.isLaidOut(binding.mainToolbar.event)) {
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    this@MainActivity, binding.mainToolbar.event, "morph_transition")
            startWithPIN(intent, "switch_event", REQ_EVENT, options.toBundle())
        } else {
            startWithPIN(intent, "switch_event", REQ_EVENT, null)
        }
    }

    private fun snackbar(message: String) {
        Snackbar.make(findViewById(R.id.root_layout), message, Snackbar.LENGTH_LONG).show();
    }

    private fun snackbar(message: Int) {
        Snackbar.make(findViewById(R.id.root_layout), message, Snackbar.LENGTH_LONG).show();
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

    private val hideRunnable = Runnable {
        runOnUiThread {
            if (dialog != null && dialog!!.isShowing()) {
                return@runOnUiThread
            }
            hideCard()
        }
    }

    fun stopHidingTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        hideAnimation?.cancel()
        view_data.hideTimerVisible.set(false)
    }

    fun startHidingTimer() {
        val HIDING_TIME_MILLIS = 30000L
        view_data.hideTimerVisible.set(true)
        view_data.hideTimerProgress.set(100)

        hideAnimation = ValueAnimator.ofInt(100, 0).apply {
            duration = HIDING_TIME_MILLIS
            interpolator = LinearInterpolator()
            addUpdateListener {
                view_data.hideTimerProgress.set(it.animatedValue as Int)
            }
            doOnEnd {
                view_data.hideTimerVisible.set(false)
            }
        }

        hideHandler.postDelayed(hideRunnable, HIDING_TIME_MILLIS)
        hideAnimation!!.start()
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

        view_data.kioskMode.set(conf.kioskMode)
        if (conf.kioskMode) {
            supportActionBar?.hide()
            window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        } else {
            supportActionBar?.show()
        }

        scheduleSync()

        hardwareScanner.start(this)
        LED(this).off()

        if (conf.useCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.scannerView.setResultHandler(this)
            binding.scannerView.startCamera()
        }
        view_data.scanType.set(conf.scanType)
        view_data.hardwareScan.set(!conf.useCamera)
        view_data.badgePrintEnabled.set(conf.printBadges && conf.autoPrintBadges != "false")

        setKioskAnimation()

        reloadCameraState()

        (application as PretixScan).connectivityHelper.addListener(this)

        val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun check() {
            (application as PretixScan).connectivityHelper.setHardOffline(connectivityManager.activeNetworkInfo?.isConnectedOrConnecting != true)
        }

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                (application as PretixScan).connectivityHelper.setHardOffline(false)
            }

            override fun onLost(network: Network) {
                check()
            }
        }
        check()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    private fun setKioskAnimation() {
        if (!conf.kioskMode) return

        val drawable = when (Build.BRAND) {
            "Zebra" -> if (Build.MODEL.startsWith("CC6")) {
                R.drawable.avd_kiosk_widescreen_barcode_bottom
            } else {
                null
            }
            "NewLand" -> if (Build.MODEL.startsWith("NQ")) {
                R.drawable.avd_kiosk_widescreen_barcode_bottom
            } else {
                null
            }
            "Newland" -> if (Build.MODEL.startsWith("NLS-NQ")) {
                R.drawable.avd_kiosk_widescreen_barcode_bottom
            } else {
                null
            }
            "SEUIC" -> if (Build.MODEL.startsWith("AUTOID Pad Air")) {
                R.drawable.avd_kiosk_widescreen_barcode_bottom
            } else {
                null
            }
            "M3" -> if (Build.MODEL.startsWith("M3PC")) {
                R.drawable.avd_kiosk_widescreen_barcode_bottom
            } else {
                null
            }
            else -> null
        } ?: return

        view_data.kioskWithAnimation.set(true)
        val animated = AnimatedVectorDrawableCompat.create(this, drawable)
        animated?.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                animated.start()
            }

        })
        binding.ivKioskAnimation.setImageDrawable(animated)
        animated?.start()
    }

    fun hideSearchCard() {
        binding.cardSearch.visibility = View.GONE
    }

    fun hideCard() {
        card_state = ResultCardState.HIDDEN
        binding.cardResult.clearAnimation()
        binding.cardResult.visibility = View.GONE
        view_data.resultState.set(EMPTY)
        view_data.resultText.set(null)
        view_data.resultOffline.set(false)
        LED(this).off()
    }

    fun showLoadingCard() {
        stopHidingTimer()
        binding.cardResult.clearAnimation()
        view_data.resultState.set(LOADING)
        view_data.resultText.set(null)
        view_data.resultOffline.set(false)
        view_data.showPrint.set(false)
        view_data.eventName.set(null)
        view_data.ticketAndVariationName.set(null)
        view_data.orderCodeAndPositionId.set(null)
        view_data.attendeeName.set(null)
        view_data.seat.set(null)
        view_data.reasonExplanation.set(null)
        view_data.questionAndAnswers.set(null)
        view_data.checkInTexts.set(null)
        view_data.firstScanned.set(null)
        view_data.attention.set(false)
        if (card_state == ResultCardState.HIDDEN) {
            card_state = ResultCardState.SHOWN
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            binding.cardResult.translationX = (displayMetrics.widthPixels + binding.cardResult.width) / 2f
            binding.cardResult.alpha = 0f
            binding.cardResult.visibility = View.VISIBLE
            binding.cardResult.animate().translationX(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).alpha(1f).start()
        } else {
            // bounce
            binding.cardResult.alpha = 1f
            binding.cardResult.translationX = 1f
            ObjectAnimator.ofFloat(binding.cardResult, "translationX", 0f, 50f, -50f, 0f).apply {
                duration = 250
                interpolator = BounceInterpolator()
                start()
            }
            binding.cardResult.animate().start()
        }
    }

    fun reloadCameraState() {
        try {
            binding.scannerView.torch = conf.scanFlash
            if (conf.scanFlash) {
                binding.fabFlash.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.pretix_brand_green))
            } else {
                binding.fabFlash.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.fab_disable))
            }
            binding.scannerView.autofocus = conf.scanFocus
            if (conf.scanFocus) {
                binding.fabFocus.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.pretix_brand_green))
            } else {
                binding.fabFocus.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.fab_disable))
            }
            if (conf.useCamera) {
                binding.fabFocus.show()
                binding.fabFlash.show()
            } else {
                binding.fabFocus.hide()
                binding.fabFlash.hide()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(syncRunnable)
        (application as PretixScan).connectivityHelper.removeListener(this)
        super.onPause()
        if (conf.useCamera) {
            binding.scannerView.stopCamera()
        }
        hardwareScanner.stop(this)
        LED(this).off()

        if (networkCallback != null) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback!!)
        }
    }

    override fun onStop() {
        LED(this).off()
        super.onStop()
    }

    fun handleScan(raw_result: String, answers: MutableList<Answer>?, ignore_unpaid: Boolean = false) {
        if (conf.kioskMode && conf.requiresPin("settings") && conf.verifyPin(raw_result)) {
            supportActionBar?.show()
            return
        }

        if (dialog?.isShowing() == true) {
            /*
             * Skip scan if a dialog is still in front. This forces users to answer the questions asked.
             */
            return
        }

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

        showLoadingCard()
        hideSearchCard()

        if (answers == null && !ignore_unpaid && !conf.offlineMode) {
            if (conf.sounds) mediaPlayers[R.raw.beep]?.start()
            if (conf.haptics) vibrate(true)
        }

        bgScope.launch {
            var checkResult: TicketCheckProvider.CheckResult? = null
            val provider = (application as PretixScan).getCheckProvider(conf)
            val startedAt = System.currentTimeMillis()
            try {
                checkResult = provider.check(conf.eventSelectionToMap(), result, "barcode", answers, ignore_unpaid, conf.printBadges, when (conf.scanType) {
                    "exit" -> TicketCheckProvider.CheckInType.EXIT
                    else -> TicketCheckProvider.CheckInType.ENTRY
                }, allowQuestions = !conf.ignoreQuestions)
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
                displayScanResult(checkResult!!, answers, ignore_unpaid)
            }
        }
    }

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

    fun displayScanResult(result: TicketCheckProvider.CheckResult, answers: MutableList<Answer>?, ignore_unpaid: Boolean = false) {
        lastScanResult = result
        lastIgnoreUnpaid = ignore_unpaid

        when (result.type) {
                TicketCheckProvider.CheckResult.Type.VALID -> when (result.scanType) {
                    TicketCheckProvider.CheckInType.ENTRY ->
                        if (result.isRequireAttention) {
                            mediaPlayers[R.raw.attention]?.start()
                            if (conf.haptics) vibrate(false)
                        } else {
                            if (conf.sounds) mediaPlayers[R.raw.enter]?.start()
                            if (conf.haptics) vibrate(true)
                        }
                    TicketCheckProvider.CheckInType.EXIT -> {
                        if (conf.sounds) mediaPlayers[R.raw.exit]?.start()
                        if (conf.haptics) vibrate(true)
                    }
                }
                TicketCheckProvider.CheckResult.Type.INVALID,
                TicketCheckProvider.CheckResult.Type.ERROR,
                TicketCheckProvider.CheckResult.Type.UNPAID,
                TicketCheckProvider.CheckResult.Type.CANCELED,
                TicketCheckProvider.CheckResult.Type.PRODUCT,
                TicketCheckProvider.CheckResult.Type.RULES,
                TicketCheckProvider.CheckResult.Type.AMBIGUOUS,
                TicketCheckProvider.CheckResult.Type.REVOKED,
                TicketCheckProvider.CheckResult.Type.UNAPPROVED,
                TicketCheckProvider.CheckResult.Type.BLOCKED,
                TicketCheckProvider.CheckResult.Type.INVALID_TIME,
                TicketCheckProvider.CheckResult.Type.USED -> {
                    if (conf.sounds) mediaPlayers[R.raw.error]?.start()
                    if (conf.haptics) vibrate(false)
                }
                TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED -> {
                    if (conf.sounds) mediaPlayers[R.raw.attention]?.start()
                    if (conf.haptics) vibrate(false)
                }
                else -> {
                }
            }

        stopHidingTimer()
        startHidingTimer()
        if (result.type == TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED) {
            view_data.resultState.set(DIALOG)
            dialog = showQuestionsDialog(result, lastScanCode, ignore_unpaid, null, false) { secret, answers, ignore_unpaid ->
                stopHidingTimer()
                handleScan(secret, answers, ignore_unpaid)
            }
            dialog!!.setOnCancelListener(DialogInterface.OnCancelListener { hideCard() })
            view_data.setLed(this, view_data.resultState.get()!!, true)
            return
        }
        if (result.type == TicketCheckProvider.CheckResult.Type.UNPAID && result.isCheckinAllowed) {
            view_data.resultState.set(DIALOG)
            dialog = showUnpaidDialog(this, result, lastScanCode, answers) { secret, answers, ignore_unpaid ->
                stopHidingTimer()
                handleScan(secret, answers, ignore_unpaid)
            }
            dialog!!.setOnCancelListener(DialogInterface.OnCancelListener { hideCard() })
            view_data.setLed(this, view_data.resultState.get()!!, true)
            return
        }
        if (result.message == null) {
            result.message = when (result.type!!) {
                TicketCheckProvider.CheckResult.Type.INVALID -> getString(R.string.scan_result_invalid)
                TicketCheckProvider.CheckResult.Type.VALID -> when (result.scanType) {
                    TicketCheckProvider.CheckInType.EXIT -> getString(R.string.scan_result_exit)
                    TicketCheckProvider.CheckInType.ENTRY -> getString(R.string.scan_result_valid)
                }
                TicketCheckProvider.CheckResult.Type.USED -> getString(R.string.scan_result_used)
                TicketCheckProvider.CheckResult.Type.RULES -> getString(R.string.scan_result_rules)
                TicketCheckProvider.CheckResult.Type.AMBIGUOUS -> getString(R.string.scan_result_ambiguous)
                TicketCheckProvider.CheckResult.Type.REVOKED -> getString(R.string.scan_result_revoked)
                TicketCheckProvider.CheckResult.Type.UNAPPROVED -> getString(R.string.scan_result_unapproved)
                TicketCheckProvider.CheckResult.Type.INVALID_TIME -> getString(R.string.scan_result_invalid_time)
                TicketCheckProvider.CheckResult.Type.BLOCKED -> getString(R.string.scan_result_blocked)
                TicketCheckProvider.CheckResult.Type.UNPAID -> getString(R.string.scan_result_unpaid)
                TicketCheckProvider.CheckResult.Type.CANCELED -> getString(R.string.scan_result_canceled)
                TicketCheckProvider.CheckResult.Type.PRODUCT -> getString(R.string.scan_result_product)
                else -> null
            }
        }
        view_data.resultText.set(result.message)
        view_data.resultOffline.set(result.offline)
        view_data.resultState.set(when (result.type!!) {
            TicketCheckProvider.CheckResult.Type.INVALID -> ERROR
            TicketCheckProvider.CheckResult.Type.VALID -> {
                when (result.scanType) {
                    TicketCheckProvider.CheckInType.EXIT -> SUCCESS_EXIT
                    TicketCheckProvider.CheckInType.ENTRY -> SUCCESS
                }
            }
            TicketCheckProvider.CheckResult.Type.USED -> WARNING
            TicketCheckProvider.CheckResult.Type.ERROR -> ERROR
            TicketCheckProvider.CheckResult.Type.RULES -> ERROR
            TicketCheckProvider.CheckResult.Type.AMBIGUOUS -> ERROR
            TicketCheckProvider.CheckResult.Type.REVOKED -> ERROR
            TicketCheckProvider.CheckResult.Type.UNAPPROVED -> ERROR
            TicketCheckProvider.CheckResult.Type.INVALID_TIME -> ERROR
            TicketCheckProvider.CheckResult.Type.BLOCKED -> ERROR
            TicketCheckProvider.CheckResult.Type.UNPAID -> ERROR
            TicketCheckProvider.CheckResult.Type.CANCELED -> ERROR
            TicketCheckProvider.CheckResult.Type.PRODUCT -> ERROR
            TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED -> ERROR
        })
        view_data.setLed(this, view_data.resultState.get()!!, result.isRequireAttention)

        val isExit = (result.scanType == TicketCheckProvider.CheckInType.EXIT)
        if (result.ticket != null) {
            if (result.variation != null) {
                view_data.ticketAndVariationName.set(result.ticket + " – " + result.variation)
            } else {
                view_data.ticketAndVariationName.set(result.ticket)
            }
        } else {
            view_data.ticketAndVariationName.set(null)
        }
        if (!result.reasonExplanation.isNullOrBlank()) {
            view_data.reasonExplanation.set(result.reasonExplanation)
        } else {
            view_data.reasonExplanation.set(null)
        }
        if (result.firstScanned != null) {
            val df = SimpleDateFormat(getString(R.string.short_datetime_format))
            view_data.firstScanned.set(getString(R.string.first_scanned, df.format(result.firstScanned)))
        } else {
            view_data.firstScanned.set(null)
        }
        if (result.attendee_name != null && !conf.hideNames) {
            view_data.attendeeName.set(result.attendee_name)
        } else {
            view_data.attendeeName.set(null)
        }
        if (result.orderCodeAndPositionId() != null) {
            view_data.orderCodeAndPositionId.set(result.orderCodeAndPositionId())
        } else {
            view_data.orderCodeAndPositionId.set(null)
        }
        if (!isExit && result.seat != null) {
            view_data.seat.set(result.seat)
        } else {
            view_data.seat.set(null)
        }
        if (!isExit && !result.shownAnswers.isNullOrEmpty()) {
            val qanda = SpannableStringBuilder()
            result.shownAnswers!!.forEachIndexed { index, questionAnswer ->
                val question = questionAnswer.question.toModel().question
                qanda.bold { append(question + ":") }
                qanda.append(" ")
                qanda.append(questionAnswer.currentValue) // FIXME: yes/no is written here as true/false
                if (index != result.shownAnswers!!.lastIndex) {
                    qanda.append("\n")
                }
            }
            view_data.questionAndAnswers.set(SpannableString.valueOf(qanda))
        } else {
            view_data.questionAndAnswers.set(null)
        }
        if (!isExit && !result.checkinTexts.isNullOrEmpty()) {
            view_data.checkInTexts.set(result.checkinTexts!!.filterNot { it.isBlank() }.joinToString("\n").trim())
        } else {
            view_data.checkInTexts.set(null)
        }

        if (result.eventSlug != null && conf.eventSelection.size > 1) {
            val event = (application as PretixScan).db.eventQueries.selectBySlug(result.eventSlug)
                .executeAsOneOrNull()
                ?.toModel()
            view_data.eventName.set(event?.name)
        }

        view_data.attention.set(result.isRequireAttention)

        val isPrintable = (conf.printBadges &&
                result.scanType != TicketCheckProvider.CheckInType.EXIT &&
                result.position != null &&
                getBadgeLayout((application as PretixScan).db, result.position!!, result.eventSlug!!) != null)

        if (isPrintable) {

            val recv = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    super.onReceiveResult(resultCode, resultData)
                    if (resultCode == 0) {
                        val api = PretixApi.fromConfig(
                            conf,
                            AndroidHttpClientFactory(application as PretixScan)
                        )
                        logSuccessfulPrint(
                            api,
                            (application as PretixScan).db,
                            result.eventSlug!!,
                            result.position!!.getLong("id"),
                            "badge"
                        )
                    }
                }
            }

            val shouldAutoPrint = when(conf.autoPrintBadges) {
                "false" -> false
                "true" -> {
                    result.type == TicketCheckProvider.CheckResult.Type.VALID
                }
                "once" -> {
                    result.type == TicketCheckProvider.CheckResult.Type.VALID &&
                    !isPreviouslyPrinted((application as PretixScan).db, result.position!!)
                }
                else -> false
            }

            if (shouldAutoPrint) {
                printBadge(
                    this@MainActivity,
                    (application as PretixScan).db,
                    (application as PretixScan).fileStorage,
                    result.position!!,
                    result.eventSlug!!,
                    recv
                )
            }
            view_data.showPrint.set(true)
            binding.ibPrint.setOnClickListener {
                printBadge(
                    this@MainActivity,
                    (application as PretixScan).db,
                    (application as PretixScan).fileStorage,
                    result.position!!,
                    result.eventSlug!!,
                    recv
                )
            }
        } else {
            view_data.showPrint.set(false)
        }

        binding.attentionFlag.clearAnimation()
        if (result.isRequireAttention) {
            binding.attentionFlag.animation = AlphaAnimation(1f, 0.3f).apply {
                duration = 350
                interpolator = LinearInterpolator()
                repeatCount = 6
                repeatMode = Animation.REVERSE
            }
        }

    }

    override fun handleResult(rawResult: ScannerView.Result) {
        if ((dialog != null && dialog!!.isShowing()) || view_data.resultState.get() == LOADING) {
            return
        }

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
            KeyEvent.KEYCODE_ENTER -> {
                if (keyboardBuffer.isEmpty()) {
                    false
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


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu to use in the action bar
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_main, menu)

        menu.findItem(R.id.action_scantype).title = if (conf.scanType == "exit") {
            getString(R.string.action_label_scantype_entry)
        } else {
            getString(R.string.action_label_scantype_exit)
        }
        menu.findItem(R.id.action_scantype).isVisible = conf.knownPretixVersion >= 30090001000
        menu.findItem(R.id.action_stats).isVisible = !conf.offlineMode || conf.syncOrders

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as SearchView
        searchItem.isVisible = !conf.searchDisabled
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                if (query.isEmpty()) {
                    hideSearchCard()
                } else {
                    setSearchFilter(query)
                }
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                if (newText.isEmpty()) {
                    hideSearchCard()
                } else {
                    setSearchFilter(newText)
                }
                return true
            }
        })
        searchView.setOnCloseListener {
            hideSearchCard()
            return@setOnCloseListener true
        }

        return super.onCreateOptionsMenu(menu)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                startWithPIN(intent, "settings")
                return true
            }
            R.id.action_stats -> {
                val intent = Intent(this@MainActivity, EventinfoActivity::class.java)
                startWithPIN(intent, "statistics")
                return true
            }
            R.id.action_scantype -> {
                pinProtect("switch_mode") {
                    if (conf.scanType == "entry") {
                        conf.scanType = "exit"
                        item.title = getString(R.string.action_label_scantype_entry)
                    } else {
                        conf.scanType = "entry"
                        item.title = getString(R.string.action_label_scantype_exit)
                    }
                    view_data.scanType.set(conf.scanType)
                }
            }
            R.id.action_sync -> {
                syncNow()
            }
        }
        return super.onOptionsItemSelected(item)
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

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        if (savedInstanceState.getString("result_state", "") == "DIALOG") {
            val module = SimpleModule()
            module.addDeserializer(JSONObject::class.java, JSONObjectDeserializer())
            module.addDeserializer(JSONArray::class.java, JSONArrayDeserializer())
            val om = ObjectMapper()
            om.registerModule(module)
            om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

            view_data.resultState.set(DIALOG)
            lastScanCode = savedInstanceState.getString("lastScanCode", null)
            lastIgnoreUnpaid = savedInstanceState.getBoolean("ignore_unpaid")
            lastScanResult = om.readValue(savedInstanceState.getString("result"), TicketCheckProvider.CheckResult::class.java)

            val answers = savedInstanceState.getBundle("answers")!!
            val values = mutableMapOf<String, String>()

            val questionServerIds = lastScanResult!!.requiredAnswers!!.map { it.question.server_id }
            val questionIdentifiers = (application as PretixScan).db.questionQueries.selectByServerIdList(questionServerIds)
                    .executeAsList()
                    .map { it.toModel().identifier }
            questionIdentifiers.forEach { identifier ->
                val v = answers.getString(identifier, "")
                if (v.isNotBlank()) {
                    values[identifier] = v
                }
            }

            dialog = showQuestionsDialog(lastScanResult!!, lastScanCode, lastIgnoreUnpaid, values, true) { secret, answers, ignore_unpaid ->
                stopHidingTimer()
                handleScan(secret, answers, ignore_unpaid)
            }
            dialog!!.onRestoreInstanceState(answers)
            dialog!!.setOnCancelListener(DialogInterface.OnCancelListener { hideCard() })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Currently, in most cases we don't need to care if Android kills MainActivity, since it
        // does not carry much state when you don't actively use it. The prominent exception is
        // if the questions dialog starts sub-activities, e.g. for taking photos. In these case,
        // we try to serialize all state required to re-create the dialog if the user returns.

        if (view_data.resultState.get() == DIALOG && dialog != null && lastScanResult != null) {
            val module = SimpleModule()
            module.addSerializer(JSONObject::class.java, JSONObjectSerializer())
            module.addSerializer(JSONArray::class.java, JSONArraySerializer())
            val om = ObjectMapper()
            om.registerModule(module)
            om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

            outState.putString("result_state", "DIALOG")
            outState.putString("lastScanCode", lastScanCode)
            outState.putBoolean("ignore_unpaid", lastIgnoreUnpaid)
            outState.putBundle("answers", dialog!!.onSaveInstanceState())
            outState.putString("result", om.writeValueAsString(lastScanResult))
        }
    }
}
