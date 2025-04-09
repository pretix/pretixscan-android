package eu.pretix.pretixscan.droid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.ResultReceiver
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.Answer
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityKioskBinding
import eu.pretix.pretixscan.droid.hardware.LED
import java.util.IllformedLocaleException
import java.util.Locale


class KioskActivity : BaseScanActivity() {
    companion object {
        /**
         * Some older devices needs a small delay between UI widget updates
         * and a change of the status and navigation bar.
         */
        private const val UI_ANIMATION_DELAY = 300

        enum class KioskState {
            WaitingForScan,
            ReadingBarcode,
            Checking,
            Rejected,
            NeedAnswers,
            Printing,
            GateOpen,
            OutOfOrder,
        }

        enum class NetworkType {
            Unknown,
            None,
            Cellular,
            Wifi,
            Ethernet
        }
    }

    private lateinit var binding: ActivityKioskBinding
    private val hideHandler = Handler(Looper.myLooper()!!)
    private val backToStartHandler = Handler(Looper.myLooper()!!)
    private val printTimeoutHandler = Handler(Looper.myLooper()!!)
    private val gateTimeoutHandler = Handler(Looper.myLooper()!!)
    var networkType = NetworkType.Unknown
    var state = KioskState.WaitingForScan
        set(value) {
            if (BuildConfig.DEBUG) {
                println("Setting state from $state to $value")
            }
            field = value
        }
    var lastTicketRequireAttention = false
    var localizedContext: Context? = null

    fun setTemporaryLocale(overrideLocale: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val localeBuilder = Locale.Builder()
        try {
            localeBuilder.setLanguage(overrideLocale.split("-").first())
        } catch (e: IllformedLocaleException) {
            return
        }
        if (overrideLocale.contains("-")) {
            try {
                // Is this a region, like pt-br?
                localeBuilder.setRegion(overrideLocale.split("-").last().uppercase())
            } catch (e: IllformedLocaleException) {
                try {
                    // Is this a variant, like zh-hans?
                    localeBuilder.setVariant(overrideLocale.split("-").last())
                } catch (e: IllformedLocaleException) {
                    // Is it neither, like de-informal? Ignore!
                }
            }
        }

        val conf = Configuration(resources.configuration)
        conf.setLocales(LocaleList(localeBuilder.build(), Locale.getDefault()))
        localizedContext = createConfigurationContext(conf)
    }

    private fun localizedString(stringId: Int): String {
        return (localizedContext ?: this).getString(stringId)
    }

    val backToStart = Runnable {
        when (state) {
            KioskState.GateOpen,
            KioskState.NeedAnswers,
            KioskState.Checking,
            KioskState.ReadingBarcode,
            KioskState.Rejected -> {
                state = KioskState.WaitingForScan
                localizedContext = null
                updateUi()
            }
            else -> {}
        }
    }

    val printTimeout = Runnable {
        if (state == KioskState.Printing) {
            binding.tvOutOfOrderMessage.text = "Printing failed by timeout"
            state = KioskState.OutOfOrder
            updateUi()
        }
    }

    val gateTimeout = Runnable {
        if (state == KioskState.GateOpen) {
            binding.tvOutOfOrderMessage.text = "Gate opening failed by timeout"
            state = KioskState.OutOfOrder
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        @SuppressLint("SetTextI18n")
        binding.tvDeviceInfo.text = "#${conf.devicePosId}"
    }

    val loopCallback =
        @RequiresApi(Build.VERSION_CODES.M) object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable) {
                (drawable as AnimatedVectorDrawable).start()
            }
        }

    fun resetAnimations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (binding.ivKioskAnimation.drawable as? AnimatedVectorDrawable)?.apply {
                unregisterAnimationCallback(loopCallback)
                registerAnimationCallback(loopCallback)
                start()
            }
            (binding.ivKioskAnimation2.drawable as? AnimatedVectorDrawable)?.apply {
                unregisterAnimationCallback(loopCallback)
                registerAnimationCallback(loopCallback)
                start()
            }
            (binding.ivKioskAnimation3.drawable as? AnimatedVectorDrawable)?.apply {
                unregisterAnimationCallback(loopCallback)
                registerAnimationCallback(loopCallback)
                start()
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        fullscreen()
        updateUi()
        resetAnimations()
    }

    override fun onStop() {
        super.onStop()
        LED(this).off()
    }

    fun fullscreen() {
        supportActionBar?.hide()
        hideHandler.postDelayed({
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                // Note that some of these constants are new as of API 16 (Jelly Bean)
                // and API 19 (KitKat). It is safe to use them, as they are inlined
                // at compile-time and do nothing on earlier devices.
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LOW_PROFILE or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            }
        }, UI_ANIMATION_DELAY.toLong())
    }

    override fun onResume() {
        super.onResume()
        fullscreen()
        if (conf.kioskOutOfOrder) {
            conf.kioskOutOfOrder = true
            state = KioskState.OutOfOrder
            binding.tvOutOfOrderMessage.text = ""
        }
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        updateNetworkType(connectivityManager)
        updateUi()
    }

    private fun updateNetworkType(connectivityManager: ConnectivityManager) {
        fun setNetworkType(nt: NetworkType) {
            if (nt != networkType) {
                networkType = nt
                runOnUiThread {
                    updateUi()
                }
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            setNetworkType(NetworkType.Unknown)
        } else if (connectivityManager.activeNetwork == null) {
            setNetworkType(NetworkType.None)
        } else {
            val netCaps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (netCaps == null) {
                setNetworkType(NetworkType.Unknown)
            } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                setNetworkType(NetworkType.Ethernet)
            } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                setNetworkType(NetworkType.Wifi)
            } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                setNetworkType(NetworkType.Cellular)
            } else {
                setNetworkType(NetworkType.Unknown)
            }
        }
    }

    override fun onNetworkAvailable(connectivityManager: ConnectivityManager, network: Network) {
        super.onNetworkAvailable(connectivityManager, network)
        updateNetworkType(connectivityManager)
    }

    override fun onNetworkLost(connectivityManager: ConnectivityManager, network: Network?) {
        super.onNetworkLost(connectivityManager, network)
        updateNetworkType(connectivityManager)
    }

    override fun onNetworkChanged(connectivityManager: ConnectivityManager, network: Network) {
        super.onNetworkChanged(connectivityManager, network)
        updateNetworkType(connectivityManager)
    }

    override fun reload() {
        super.reload()
        binding.ivOfflineIcon.visibility = if (conf.offlineMode) View.VISIBLE else View.GONE
    }

    override fun reloadSyncStatus() {
        if (conf.lastFailedSync > conf.lastSync || System.currentTimeMillis() - conf.lastDownload > 5 * 60 * 1000) {
            binding.ivSyncStatusIcon.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    R.drawable.ic_warning_red_24dp
                )
            )
            binding.ivSyncStatusIcon.setColorFilter(
                ContextCompat.getColor(
                    this,
                    R.color.pretix_brand_red
                )
            )
            binding.tvSyncStatus.visibility = if (conf.proxyMode) View.GONE else View.VISIBLE
            binding.tvSyncStatus.text = syncStatusText()
        } else {
            binding.ivSyncStatusIcon.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    R.drawable.ic_baseline_check_circle_24
                )
            )
            binding.ivSyncStatusIcon.setColorFilter(
                ContextCompat.getColor(
                    this,
                    R.color.pretix_brand_green
                )
            )
            binding.tvSyncStatus.visibility = View.GONE
        }
    }

    override fun displayScanResult(
        result: TicketCheckProvider.CheckResult,
        answers: MutableList<Answer>?,
        ignore_unpaid: Boolean
    ) {
        if (result.locale != null) {
            setTemporaryLocale(result.locale!!)
        }
        lastTicketRequireAttention = result.isRequireAttention
        var isPrintable = false
        val mayBePrintable = (conf.printBadges &&
                result.scanType != TicketCheckProvider.CheckInType.EXIT &&
                result.position != null)
        if (mayBePrintable) {
            val badgeLayout =
                getBadgeLayout((application as PretixScan).db, result.position!!, result.eventSlug!!)
            if (badgeLayout != null) {
                isPrintable = true
            }
        }
        val shouldAutoPrint = isPrintable && when (conf.autoPrintBadges) {
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

        when (result.type) {
            TicketCheckProvider.CheckResult.Type.VALID -> when (result.scanType) {
                TicketCheckProvider.CheckInType.ENTRY -> {
                    if (shouldAutoPrint) {
                        state = KioskState.Printing
                    } else {
                        state = KioskState.GateOpen
                    }
                    if (conf.sounds) {
                        if (result.isRequireAttention) {
                            mediaPlayers[R.raw.attention]?.start()
                        } else {
                            mediaPlayers[R.raw.enter]?.start()
                        }
                    }
                }

                TicketCheckProvider.CheckInType.EXIT -> {
                    state = KioskState.GateOpen
                    if (conf.sounds) {
                        mediaPlayers[R.raw.exit]?.start()
                    }
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
                state = KioskState.Rejected
                if (conf.sounds) {
                    mediaPlayers[R.raw.error]?.start()
                }
            }

            TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED -> {
                state = KioskState.NeedAnswers
                if (conf.sounds) {
                    mediaPlayers[R.raw.error]?.start()
                }
            }

            else -> {
                binding.tvOutOfOrderMessage.text = "Unknown Scan Result Type"
                state = KioskState.OutOfOrder
                if (conf.sounds) {
                    mediaPlayers[R.raw.error]?.start()
                }
            }
        }

        if (state == KioskState.Rejected) {
            if (result.message == null) {
                result.message = when (result.type!!) {
                    TicketCheckProvider.CheckResult.Type.INVALID -> localizedString(R.string.scan_result_invalid)
                    TicketCheckProvider.CheckResult.Type.USED -> localizedString(R.string.scan_result_used)
                    TicketCheckProvider.CheckResult.Type.RULES -> localizedString(R.string.scan_result_rules)
                    TicketCheckProvider.CheckResult.Type.AMBIGUOUS -> localizedString(R.string.scan_result_ambiguous)
                    TicketCheckProvider.CheckResult.Type.REVOKED -> localizedString(R.string.scan_result_revoked)
                    TicketCheckProvider.CheckResult.Type.UNAPPROVED -> localizedString(R.string.scan_result_unapproved)
                    TicketCheckProvider.CheckResult.Type.INVALID_TIME -> localizedString(R.string.scan_result_invalid_time)
                    TicketCheckProvider.CheckResult.Type.BLOCKED -> localizedString(R.string.scan_result_blocked)
                    TicketCheckProvider.CheckResult.Type.UNPAID -> localizedString(R.string.scan_result_unpaid)
                    TicketCheckProvider.CheckResult.Type.CANCELED -> localizedString(R.string.scan_result_canceled)
                    TicketCheckProvider.CheckResult.Type.PRODUCT -> localizedString(R.string.scan_result_product)
                    else -> null
                }
            }
            binding.tvRejectedMessage.text = result.message
            binding.tvRejectedReason.visibility =
                if (result.reasonExplanation.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvRejectedReason.text = result.reasonExplanation
        }

        updateUi()

        if (state == KioskState.Printing) {
            val recv = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    super.onReceiveResult(resultCode, resultData)
                    printTimeoutHandler.removeCallbacks(printTimeout)
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
                        runOnUiThread {
                            state = KioskState.GateOpen
                            updateUi()
                            openGate()
                        }
                    } else {
                        // printing failed
                        runOnUiThread {
                            binding.tvOutOfOrderMessage.text = "Printing failed"
                            state = KioskState.OutOfOrder
                            updateUi()
                        }
                    }
                }
            }
            if (shouldAutoPrint) {
                printTimeoutHandler.postDelayed(printTimeout, 15_000)
                printBadge(
                    this,
                    (application as PretixScan).db,
                    (application as PretixScan).fileStorage,
                    result.position!!,
                    result.eventSlug!!,
                    recv
                )
            }
        } else if (state == KioskState.GateOpen) {
            openGate()
        } else if (state == KioskState.NeedAnswers || state == KioskState.Rejected) {
            backToStartHandler.postDelayed(backToStart, 5_000)
        }
    }

    fun openGate() {
        openGate(this, object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                super.onReceiveResult(resultCode, resultData)
                gateTimeoutHandler.removeCallbacks(gateTimeout)
                if (resultCode == 0) {
                    runOnUiThread {
                        backToStartHandler.postDelayed(backToStart, conf.timeAfterGateOpen.toLong())
                    }
                } else {
                    // gate opening failed
                    runOnUiThread {
                        binding.tvOutOfOrderMessage.text = "Gate opening failed"
                        state = KioskState.OutOfOrder
                        updateUi()
                    }
                }
            }
        })
        gateTimeoutHandler.postDelayed(gateTimeout, 30_000)
    }


    fun updateUi() {
        binding.clWaitingForScan.visibility = View.GONE
        binding.clPrinting.visibility = View.GONE
        binding.clRejected.visibility = View.GONE
        binding.clGate.visibility = View.GONE
        binding.clChecking.visibility = View.GONE
        binding.llOutOfOrder.visibility = View.GONE
        val led = LED(this)

        when (state) {
            KioskState.WaitingForScan -> {
                binding.clWaitingForScan.visibility = View.VISIBLE
                led.success(blink = false)
            }

            KioskState.Rejected -> {
                binding.clRejected.visibility = View.VISIBLE
                led.error(blink = true)
            }

            KioskState.NeedAnswers -> {
                binding.tvRejectedMessage.text = localizedString(R.string.kiosk_text_questions_reject)
                binding.tvRejectedReason.visibility = View.VISIBLE
                binding.tvRejectedReason.text = localizedString(R.string.kiosk_text_questions_reject_sub)
                binding.clRejected.visibility = View.VISIBLE
                backToStartHandler.postDelayed(backToStart, 3_000)
                led.error(blink = true)
            }

            KioskState.Printing -> {
                binding.clPrinting.visibility = View.VISIBLE
                binding.tvPrinting.text = localizedString(R.string.kiosk_text_print)
                if (lastTicketRequireAttention) {
                    led.attention(blink = true)
                } else {
                    led.success(blink = true)
                }
            }

            KioskState.Checking -> {
                binding.clChecking.visibility = View.VISIBLE
                binding.tvChecking.text = getString(R.string.kiosk_text_checking)
                led.progress(blink = false)
            }

            KioskState.ReadingBarcode -> {
                binding.clChecking.visibility = View.VISIBLE
                binding.tvChecking.text = getString(R.string.kiosk_text_reading)
                led.progress(blink = false)
            }

            KioskState.GateOpen -> {
                binding.clGate.visibility = View.VISIBLE
                binding.tvGate.text = localizedString(R.string.kiosk_text_gate)
                if (lastTicketRequireAttention) {
                    led.attention()
                } else {
                    led.success()
                }
            }

            KioskState.OutOfOrder -> {
                binding.llOutOfOrder.visibility = View.VISIBLE
                led.error(blink = false)
            }
        }
        if (conf.scanType == "exit") {
            binding.ivDirectionIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.ic_exit_white_24dp))
        } else {
            binding.ivDirectionIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.ic_entry_gray_24dp))
        }

        binding.ivNetworkIcon.visibility = if (networkType == NetworkType.Unknown) {
            View.INVISIBLE
        } else {
            View.VISIBLE
        }
        binding.ivNetworkIcon.setImageDrawable(AppCompatResources.getDrawable(this, when (networkType) {
            NetworkType.Unknown -> R.drawable.ic_wifi_unknown
            NetworkType.None -> R.drawable.ic_wifi_off
            NetworkType.Cellular -> R.drawable.ic_cell_tower_24px
            NetworkType.Wifi -> R.drawable.ic_wifi_24px
            NetworkType.Ethernet -> R.drawable.ic_ethernet_24px
        }))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val r = super.dispatchKeyEvent(event)
        if (state == KioskState.WaitingForScan && keyboardBuffer.isNotBlank()) {
            state = KioskState.ReadingBarcode
            if (conf.sounds) {
                mediaPlayers[R.raw.beep]?.start()
            }
            updateUi()
            backToStartHandler.postDelayed(backToStart, 3_000)
        }
        return r
    }


    fun openMenu(pin: String) {
        val optstrings = arrayOf(
            getString(R.string.action_label_settings),
            getString(R.string.action_sync),
            getString(R.string.operation_select_event),
            // TODO: Change direction
            if (conf.kioskOutOfOrder)
                getString(R.string.action_label_remove_out_of_order)
            else
                getString(R.string.action_label_out_of_order)
        )
        MaterialAlertDialogBuilder(this)
            .setItems(optstrings) { _, i ->
                when (optstrings[i]) {
                    getString(R.string.action_label_settings) -> {
                        val intent = Intent(this, SettingsActivity::class.java)
                        intent.putExtra("pin", pin)
                        startActivity(intent)
                    }
                    getString(R.string.action_sync) -> {
                        syncNow()
                    }
                    getString(R.string.operation_select_event) -> {
                        val intent = Intent(this, EventConfigActivity::class.java)
                        startActivityForResult(intent, REQ_EVENT, null)
                    }
                    getString(R.string.action_label_out_of_order) -> {
                        conf.kioskOutOfOrder = true
                        state = KioskState.OutOfOrder
                        binding.tvOutOfOrderMessage.text = ""
                        updateUi()
                    }
                    getString(R.string.action_label_remove_out_of_order) -> {
                        conf.kioskOutOfOrder = false
                        localizedContext = null
                        state = KioskState.WaitingForScan
                        updateUi()
                    }
                }
            }
            .show()
    }

    override fun handleScan(
        raw_result: String,
        answers: MutableList<Answer>?,
        ignore_unpaid: Boolean
    ) {
        if (conf.requiresPin("settings") && conf.verifyPin(raw_result)) {
            openMenu(raw_result)
            return
        }

        when (state) {
            KioskState.WaitingForScan,
            KioskState.ReadingBarcode,
            KioskState.GateOpen,
            KioskState.Rejected -> {
                // that's fine, handle it
            }

            KioskState.Checking,
            KioskState.NeedAnswers,
            KioskState.Printing,
            KioskState.OutOfOrder -> {
                // waiting for user, for printer, gate or administrative action. ignoring scan.
                return
            }
        }

        // ignore scan if same ticket was scanned two times, but gate is already open
        if (raw_result == lastScanCode && state == KioskState.GateOpen) {
            return
        }

        backToStartHandler.removeCallbacks(backToStart)
        printTimeoutHandler.removeCallbacks(printTimeout)
        gateTimeoutHandler.removeCallbacks(gateTimeout)
        state = KioskState.Checking
        updateUi()
        super.handleScan(raw_result, answers, ignore_unpaid)
    }


    val pointerDownPositions = mutableMapOf<Int, PointF>();
    val pointerUpPositions = mutableMapOf<Int, PointF>();
    var lowestPoint = PointF();

    override fun onTouchEvent(event: MotionEvent): Boolean {
        /*
        We support the following gestures.

        1.) Open menu with PIN, two finger gesture that looks like this:

            <-------------X
            X------------->

       2.) Forget current out-of-order state (e.g. after printer was fixed):

            X          ^
             \        /
              \      /
               \    /
                \  /
                 \/

        */
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerDownPositions.clear()
                pointerUpPositions.clear()
                lowestPoint = PointF(0f, 0f)
                pointerDownPositions[event.getPointerId(0)] = PointF(event.getX(0), event.getY(0))
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerDownPositions[event.getPointerId(event.actionIndex)] =
                    PointF(event.getX(event.actionIndex), event.getY(event.actionIndex))
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pointerUpPositions[event.getPointerId(event.actionIndex)] =
                    PointF(event.getX(event.actionIndex), event.getY(event.actionIndex))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.getY(event.actionIndex) > lowestPoint.y) {
                    lowestPoint =
                        PointF(event.getX(event.actionIndex), event.getY(event.actionIndex))
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                pointerUpPositions[event.getPointerId(0)] = PointF(event.getX(0), event.getY(0))

                val displaymetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displaymetrics)
                val height: Int = displaymetrics.heightPixels
                val width: Int = displaymetrics.widthPixels

                if (pointerUpPositions.size == 2 && pointerDownPositions.size == 2) {
                    val fingerIds = pointerDownPositions.keys.toList()
                    val upperFingerId =
                        if (pointerDownPositions[fingerIds[0]]!!.y < pointerDownPositions[fingerIds[1]]!!.y) {
                            fingerIds[0]
                        } else {
                            fingerIds[1]
                        }
                    val lowerFingerId = pointerDownPositions.keys.first { it != upperFingerId }

                    val gestureDetected =
                        (pointerUpPositions[upperFingerId]!!.x - pointerDownPositions[upperFingerId]!!.x < -0.5 * width) &&
                                (pointerUpPositions[lowerFingerId]!!.x - pointerDownPositions[lowerFingerId]!!.x > 0.5 * width) &&
                                (pointerUpPositions[upperFingerId]!!.y < pointerUpPositions[lowerFingerId]!!.y)
                    if (gestureDetected) {
                        pinProtect("settings") { pin ->
                            openMenu(pin)
                        }
                    }
                } else if (pointerUpPositions.size == 1 && pointerDownPositions.size == 1) {
                    val fingerId = pointerDownPositions.keys.first()
                    val gestureDetected =
                        (pointerDownPositions[fingerId]!!.x - lowestPoint.x < -0.2 * width) &&
                                (pointerUpPositions[fingerId]!!.x - lowestPoint.x > 0.2 * width) &&
                                (lowestPoint.y - pointerUpPositions[fingerId]!!.y > 0.2 * height) &&
                                (lowestPoint.y - pointerDownPositions[fingerId]!!.y > 0.2 * height)
                    if (gestureDetected && state == KioskState.OutOfOrder && !conf.kioskOutOfOrder) {
                        localizedContext = null
                        state = KioskState.WaitingForScan
                        updateUi()
                    }
                }
                return true
            }

            else -> return super.onTouchEvent(event)
        }
    }

}