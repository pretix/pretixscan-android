package eu.pretix.pretixscan.droid.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowInsets
import androidx.core.content.ContextCompat
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.Answer
import eu.pretix.libpretixsync.db.Event
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityKioskBinding

class KioskActivity : BaseScanActivity() {
    companion object {
        /**
         * Some older devices needs a small delay between UI widget updates
         * and a change of the status and navigation bar.
         */
        private const val UI_ANIMATION_DELAY = 300

        enum class KioskState {
            WaitingForScan,
            Rejected,
            NeedAnswers,
            Greeting,
            GateOpen,
            OutOfOrder,
        }
    }

    private lateinit var binding: ActivityKioskBinding
    private val hideHandler = Handler(Looper.myLooper()!!)
    private val backToStartHandler = Handler(Looper.myLooper()!!)
    var state = KioskState.WaitingForScan

    val backToStart = Runnable {
        state = KioskState.WaitingForScan
        updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        @SuppressLint("SetTextI18n")
        binding.tvAppVersion.text = "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        fullscreen()
        updateUi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            binding.ivKioskAnimation.drawable is AnimatedVectorDrawable) {
            (binding.ivKioskAnimation.drawable as AnimatedVectorDrawable).apply {
                registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(drawable: Drawable?) {
                        start()
                    }
                })
                start()
            }
        }
    }

    fun fullscreen() {
        supportActionBar?.hide()
        hideHandler.postDelayed({
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
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
        updateUi()
    }

    override fun reload() {
        super.reload()
        binding.ivOfflineIcon.visibility = if (conf.offlineMode) View.VISIBLE else View.GONE
    }

    override fun reloadSyncStatus() {
        if (conf.lastFailedSync > conf.lastSync || System.currentTimeMillis() - conf.lastDownload > 5 * 60 * 1000) {
            binding.ivSyncStatusIcon.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_warning_red_24dp))
            binding.ivSyncStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.pretix_brand_red))
        } else {
            binding.ivSyncStatusIcon.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_circle_24dp))
            binding.ivSyncStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.pretix_brand_green))
        }
        binding.tvSyncStatus.visibility = if (conf.proxyMode) View.GONE else View.VISIBLE
        binding.tvSyncStatus.text = syncStatusText()
    }

    override fun displayScanResult(
        result: TicketCheckProvider.CheckResult,
        answers: MutableList<Answer>?,
        ignore_unpaid: Boolean
    ) {
        println(result)
        println(answers)
        println(ignore_unpaid)

        // FIXME: play sound

        when (result.type) {
            TicketCheckProvider.CheckResult.Type.VALID -> when (result.scanType) {
                TicketCheckProvider.CheckInType.ENTRY ->
                    state = KioskState.Greeting
                TicketCheckProvider.CheckInType.EXIT ->
                    state = KioskState.GateOpen
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
            }
            TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED -> {
                state = KioskState.NeedAnswers
            }
            else -> {
                binding.tvOutOfOrderMessage.text = "Unknown Scan Result Type"
                state = KioskState.OutOfOrder
            }
        }

        if (state == KioskState.Greeting) {
            // FIXME: find out language of that order, modify message

            var eventName = ""
            if (result.eventSlug != null) {
                val event = (application as PretixScan).data.select(Event::class.java)
                    .where(Event.SLUG.eq(result.eventSlug))
                    .get().firstOrNull()
                eventName = event?.name ?: ""
            }

            binding.tvWelcomeHeading.text = getString(R.string.scan_result_valid) // FIXME: nicer welcome message
        }
        if (state == KioskState.Rejected) {
            if (result.message == null) {
                result.message = when (result.type!!) {
                    TicketCheckProvider.CheckResult.Type.INVALID -> getString(R.string.scan_result_invalid)
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
            binding.tvRejectedMessage.text = result.message
            backToStartHandler.postDelayed(backToStart, 3_000)
        }

        updateUi()

        // FIXME: start badge print
        // FIXME: after badge print, set state = KioskState.GateOpen
    }


    fun updateUi() {
        binding.flWaitingForScan.visibility = View.GONE
        binding.llWelcome.visibility = View.GONE
        binding.flRejected.visibility = View.GONE
        binding.llGateOpen.visibility = View.GONE
        binding.llOutOfOrder.visibility = View.GONE

        when (state) {
            KioskState.WaitingForScan -> {
                binding.flWaitingForScan.visibility = View.VISIBLE
            }
            KioskState.Rejected -> {
                binding.flRejected.visibility = View.VISIBLE
            }
            KioskState.NeedAnswers -> TODO()
            KioskState.Greeting -> {
                binding.llWelcome.visibility = View.VISIBLE
            }
            KioskState.GateOpen -> {
                binding.llGateOpen.visibility = View.VISIBLE
            }
            KioskState.OutOfOrder -> {
                binding.llOutOfOrder.visibility = View.VISIBLE
            }
        }
    }

    override fun handleScan(raw_result: String, answers: MutableList<Answer>?, ignore_unpaid: Boolean) {
        if (conf.requiresPin("settings") && conf.verifyPin(raw_result)) {
            val intent = Intent(this, SettingsActivity::class.java)
            // startWithPIN(intent, "settings") // we've already verified the pin
            intent.putExtra("pin", raw_result)
            startActivity(intent)
            return
        }

        when (state) {
            KioskState.WaitingForScan,
            KioskState.Rejected -> {
                // that's fine, handle it
            }
            KioskState.NeedAnswers,
            KioskState.Greeting,
            KioskState.GateOpen,
            KioskState.OutOfOrder -> {
                // waiting for user, for printer, gate or administrative action. ignoring scan.
                return
            }
        }

        backToStartHandler.removeCallbacks(backToStart)
        super.handleScan(raw_result, answers, ignore_unpaid)
    }

}