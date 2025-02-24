package eu.pretix.pretixscan.droid.ui

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Toast
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.check.CheckException
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
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityMainBinding
import eu.pretix.pretixscan.droid.hardware.LED
import eu.pretix.pretixscan.droid.ui.ResultState.DIALOG
import eu.pretix.pretixscan.droid.ui.ResultState.EMPTY
import eu.pretix.pretixscan.droid.ui.ResultState.ERROR
import eu.pretix.pretixscan.droid.ui.ResultState.LOADING
import eu.pretix.pretixscan.droid.ui.ResultState.SUCCESS
import eu.pretix.pretixscan.droid.ui.ResultState.SUCCESS_EXIT
import eu.pretix.pretixscan.droid.ui.ResultState.WARNING
import eu.pretix.pretixscan.droid.ui.info.EventinfoActivity
import io.sentry.Sentry
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import splitties.toast.toast
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter



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
        // FIXME
    }
}

class MainActivity : BaseScanActivity() {

    private val REQ_EVENT = 1

    private lateinit var binding: ActivityMainBinding
    private val hideHandler = Handler()
    private var hideAnimation: ValueAnimator? = null
    private var card_state = ResultCardState.HIDDEN
    private var view_data = ViewDataHolder(this)

    private var searchAdapter: SearchListAdapter? = null
    private var searchFilter = ""

    companion object {
        const val PERMISSIONS_REQUEST_CAMERA = 1337
    }

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

    override fun reloadSyncStatus() {
        if (conf.lastFailedSync > conf.lastSync || System.currentTimeMillis() - conf.lastDownload > 5 * 60 * 1000) {
            binding.textViewStatus.setTextColor(ContextCompat.getColor(this, R.color.pretix_brand_red))
        } else {
            binding.textViewStatus.setTextColor(ContextCompat.getColor(this, R.color.pretix_brand_green))
        }
        binding.textViewStatus.visibility = if (conf.proxyMode) View.GONE else View.VISIBLE
        binding.textViewStatus.setText(syncStatusText())
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

    override fun onCreate(savedInstanceState: Bundle?) {
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        super.onCreate(savedInstanceState)

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

        setUpEventListeners()

        checkPermission(Manifest.permission.CAMERA, PERMISSIONS_REQUEST_CAMERA)

        hideCard()
        hideSearchCard()
        binding.cardResult.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)

        binding.recyclerViewSearch.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSearch.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(binding.recyclerViewSearch.context, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL))
    }

    private fun eventButtonText(): String {
        val s = conf.eventSelection
        if (s.size == 1) {
            return s[0].eventName
        } else {
            return getString(R.string.events_selected, s.size.toString())
        }
    }

    override fun setupApi() {
        val ebt = eventButtonText()
        if (binding.mainToolbar.event != null && binding.mainToolbar.event.text != ebt) {  // can be null if search bar is open
            binding.mainToolbar.event.text = ebt
            (binding.mainToolbar.event.parent as View?)?.forceLayout()
        }
        super.setupApi()
    }

    override fun selectEvent() {
        val intent = Intent(this, EventConfigActivity::class.java)
        if (binding.mainToolbar.event != null && ViewCompat.isLaidOut(binding.mainToolbar.event)) {
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    this@MainActivity, binding.mainToolbar.event, "morph_transition")
            startWithPIN(intent, "switch_event", REQ_EVENT, options.toBundle())
        } else {
            startWithPIN(intent, "switch_event", REQ_EVENT, null)
        }
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

    override fun onResume() {
        super.onResume()

        view_data.kioskMode.set(conf.kioskMode)
        if (conf.kioskMode) {
            supportActionBar?.hide()
            window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            }
            launchKiosk() // FIXME: kiosk hardware detection
            return
        } else {
            supportActionBar?.show()
        }

        scheduleSync()

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
    }

    fun launchKiosk() {
        val intent = Intent(this, KioskActivity::class.java)
        startActivity(intent)
        finish()
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
        super.onPause()
        if (conf.useCamera) {
            binding.scannerView.stopCamera()
        }
        LED(this).off()
    }

    override fun handleScan(raw_result: String, answers: MutableList<Answer>?, ignore_unpaid: Boolean) {
        if (dialog?.isShowing() == true) {
            /*
             * Skip scan if a dialog is still in front. This forces users to answer the questions asked.
             */
            return
        }
        LED(this).off()

        if (conf.kioskMode && conf.requiresPin("settings") && conf.verifyPin(raw_result)) {
            supportActionBar?.show()
            return
        }
        showLoadingCard()
        hideSearchCard()
        super.handleScan(raw_result, answers, ignore_unpaid)
    }

    override fun displayScanResult(result: TicketCheckProvider.CheckResult, answers: MutableList<Answer>?, ignore_unpaid: Boolean) {
        if (conf.sounds)
            when (result.type) {
                TicketCheckProvider.CheckResult.Type.VALID -> when (result.scanType) {
                    TicketCheckProvider.CheckInType.ENTRY ->
                        if (result.isRequireAttention) {
                            mediaPlayers[R.raw.attention]?.start()
                        } else {
                            mediaPlayers[R.raw.enter]?.start()
                        }
                    TicketCheckProvider.CheckInType.EXIT -> mediaPlayers[R.raw.exit]?.start()
                }
                TicketCheckProvider.CheckResult.Type.INVALID -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.ERROR -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.UNPAID -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.CANCELED -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.PRODUCT -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.RULES -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.AMBIGUOUS -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.REVOKED -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.UNAPPROVED -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.BLOCKED -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.INVALID_TIME -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.USED -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED -> mediaPlayers[R.raw.attention]?.start()
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
        super.handleResult(rawResult)
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
