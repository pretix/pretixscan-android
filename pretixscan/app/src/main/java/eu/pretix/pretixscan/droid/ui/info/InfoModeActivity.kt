package eu.pretix.pretixscan.droid.ui.info

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableField
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.check.TicketCheckProvider.CheckInType
import eu.pretix.libpretixsync.db.Answer
import eu.pretix.libpretixsync.models.db.toModel
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityInfoModeBinding
import eu.pretix.pretixscan.droid.ui.BaseScanActivity
import eu.pretix.pretixscan.droid.ui.applyDefaultMessage
import eu.pretix.pretixscan.droid.ui.checkPermission
import eu.pretix.pretixscan.droid.ui.reasonExplanationText
import eu.pretix.pretixscan.droid.ui.resultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InfoModeViewDataHolder {
    val isScanning = ObservableField(false)
    val hasResult = ObservableField(false)
    val hardwareScan = ObservableField(false)
    val scanType = ObservableField("entry")
    val resultEventSlug = ObservableField("")
    val hasEventSlug = ObservableField(false)
    val resultStatusLabel = ObservableField("")
    val hasAttention = ObservableField(false)
    val resultAttendeeName = ObservableField("")
    val resultTicketName = ObservableField("")
    val resultAddonText = ObservableField("")
    val hasAddonText = ObservableField(false)
    val resultDetails = ObservableField("")
    val resultSeat = ObservableField("")
    val hasSeat = ObservableField(false)
    val resultCheckinTexts = ObservableField("")
    val hasCheckinTexts = ObservableField(false)
    val resultQuestionAnswers = ObservableField<CharSequence>("")
    val hasQuestionAnswers = ObservableField(false)
    val resultMessage = ObservableField("")
    val hasMessage = ObservableField(false)
    val resultReason = ObservableField("")
    val hasReason = ObservableField(false)
    val presenceLabel = ObservableField("")
    val hasPresence = ObservableField(false)
    val historyEmpty = ObservableField(true)
}

class InfoModeActivity : BaseScanActivity() {

    private lateinit var binding: ActivityInfoModeBinding
    private val viewData = InfoModeViewDataHolder()
    private val historyAdapter = CheckinHistoryAdapter()
    private var activeCheckinListServerId: Long? = null

    override val simulateChecks = true

    companion object {
        private const val EXTRA_PIN = "pin"
        private const val PERMISSIONS_REQUEST_CAMERA = 1338

        fun newIntent(context: Context, pin: String): Intent {
            return Intent(context, InfoModeActivity::class.java).apply {
                putExtra(EXTRA_PIN, pin)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (conf.requiresPin("info_mode") &&
            (!intent.hasExtra(EXTRA_PIN) || !conf.verifyPin(intent.getStringExtra(EXTRA_PIN)!!))
        ) {
            finish()
            return
        }

        activeCheckinListServerId = conf.eventSelectionToMap().values.firstOrNull()

        binding = DataBindingUtil.setContentView(this, R.layout.activity_info_mode)
        viewData.scanType.set(conf.scanType)
        viewData.hardwareScan.set(!conf.useCamera)
        binding.data = viewData

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = historyAdapter

        binding.resultCard.setOnClickListener { clearResult() }
        binding.rescanButton.setOnClickListener { clearResult() }
        binding.checkInButton.setOnClickListener { onCheckInClicked() }

        if (conf.useCamera) {
            checkPermission(Manifest.permission.CAMERA, PERMISSIONS_REQUEST_CAMERA)
        }
    }

    override fun reloadSyncStatus() {}

    override fun onResume() {
        super.onResume()
        if (!this::binding.isInitialized) return
        viewData.scanType.set(conf.scanType)
        viewData.hardwareScan.set(!conf.useCamera)
        if (conf.useCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.scannerView.setResultHandler(this)
            binding.scannerView.startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!this::binding.isInitialized) return
        binding.scannerView.stopCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == PERMISSIONS_REQUEST_CAMERA) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Please grant camera permission to use the QR Scanner", Toast.LENGTH_SHORT).show()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun handleScan(
        raw_result: String,
        source_type: String,
        answers: MutableList<Answer>?,
        ignore_unpaid: Boolean,
        exchange_medium_type: String?,
        exchange_medium_identifier: String?,
    ) {
        showLoadingCard()
        super.handleScan(
            raw_result,
            lastScanSourceType.serverName!!,
            answers,
            ignore_unpaid,
            exchange_medium_type,
            exchange_medium_identifier
        )
    }

    override fun showLoadingCard() {
        viewData.isScanning.set(true)
        viewData.hasResult.set(false)
    }

    private fun clearResult() {
        viewData.hasResult.set(false)
        viewData.isScanning.set(false)
        lastScanCode = ""
        lastScanResult = null
    }

    private fun onCheckInClicked() {
        val secret = lastScanCode.takeIf { it.isNotEmpty() } ?: return
        val sourceType = lastScanSourceType
        viewData.isScanning.set(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                (application as PretixScan).getCheckProvider(conf).check(
                    conf.eventSelectionToMap(),
                    secret,
                    sourceType.serverName!!,
                    null,
                    false,
                    false,
                    CheckInType.ENTRY,
                    simulate = false,
                )
            }
            viewData.isScanning.set(false)
            result.applyDefaultMessage(this@InfoModeActivity)
            val message = result.message
                ?: result.reasonExplanation
                ?: if (result.type == TicketCheckProvider.CheckResult.Type.VALID) {
                    getString(R.string.info_mode_checkin_success)
                } else {
                    getString(R.string.info_mode_checkin_failed)
                }
            Toast.makeText(this@InfoModeActivity, message, Toast.LENGTH_SHORT).show()
            clearResult()
        }
    }

    override fun displayScanResult(
        result: TicketCheckProvider.CheckResult,
        answers: MutableList<Answer>?,
        ignore_unpaid: Boolean
    ) {
        lastScanResult = result
        renderResult(result)
        viewData.isScanning.set(false)

        val positionServerId = result.position?.optLong("id")
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                val dbHistory = loadCheckinHistory((application as PretixScan).db, positionServerId)
                mergeImmediateCheckin((application as PretixScan).db, dbHistory, result, activeCheckinListServerId)
            }
            renderHistory(history)
        }
    }

    private fun renderResult(result: TicketCheckProvider.CheckResult) {
        val isMultiEvent = conf.eventSelectionToMap().size > 1
        viewData.resultEventSlug.set(result.eventSlug.takeIf { isMultiEvent }.orEmpty())
        viewData.hasEventSlug.set(isMultiEvent && !result.eventSlug.isNullOrEmpty())

        val accent = result.resultState().toInfoModeAccent(result.isRequireAttention)
        val resolvedColor = ContextCompat.getColor(this, accent.colorRes)

        binding.resultIcon.setImageResource(accent.iconRes)
        binding.resultIcon.setColorFilter(resolvedColor)
        binding.resultStatusLabel.setTextColor(resolvedColor)

        viewData.resultStatusLabel.set(getString(accent.labelRes))
        viewData.hasAttention.set(result.isRequireAttention)
        viewData.resultAttendeeName.set(
            result.attendee_name?.takeIf { !conf.hideNames } ?: getString(R.string.info_mode_no_name)
        )
        viewData.resultTicketName.set(
            when {
                result.ticket != null && result.variation != null -> "${result.ticket} – ${result.variation}"
                result.ticket != null -> result.ticket
                else -> ""
            }
        )
        viewData.resultDetails.set(result.orderCodeAndPositionId().orEmpty())
        viewData.resultAddonText.set(result.addonText.orEmpty())
        viewData.hasAddonText.set(!result.addonText.isNullOrEmpty())

        val isExit = result.scanType == CheckInType.EXIT

        val seat = result.seat.takeIf { !isExit }
        viewData.resultSeat.set(seat.orEmpty())
        viewData.hasSeat.set(!seat.isNullOrEmpty())

        val checkinTexts = result.checkinTexts
            ?.filterNot { it.isBlank() }
            ?.takeIf { it.isNotEmpty() && !isExit }
            ?.joinToString("\n")
        viewData.resultCheckinTexts.set(checkinTexts.orEmpty())
        viewData.hasCheckinTexts.set(!checkinTexts.isNullOrEmpty())

        val shownAnswers = result.shownAnswers
        if (!isExit && !shownAnswers.isNullOrEmpty()) {
            val qanda = SpannableStringBuilder()
            shownAnswers.forEachIndexed { index, questionAnswer ->
                val question = questionAnswer.question.toModel().question
                qanda.bold { append("$question:") }
                qanda.append(" ")
                qanda.append(questionAnswer.currentValue)
                if (index != shownAnswers.lastIndex) {
                    qanda.append("\n")
                }
            }
            viewData.resultQuestionAnswers.set(qanda)
            viewData.hasQuestionAnswers.set(true)
        } else {
            viewData.resultQuestionAnswers.set("")
            viewData.hasQuestionAnswers.set(false)
        }

        result.applyDefaultMessage(this)
        viewData.resultMessage.set(result.message.orEmpty())
        viewData.hasMessage.set(!result.message.isNullOrEmpty())

        val reason = result.reasonExplanationText(this)
        viewData.resultReason.set(reason.orEmpty())
        viewData.hasReason.set(!reason.isNullOrEmpty())

        viewData.hasResult.set(true)
    }

    private fun renderHistory(history: List<TicketCheckinHistoryEntry>) {
        val presence = currentPresenceStatus(history, activeCheckinListServerId)
        viewData.presenceLabel.set(
            when (presence) {
                PresenceStatus.PRESENT -> getString(R.string.info_mode_presence_present)
                PresenceStatus.NOT_PRESENT -> getString(R.string.info_mode_presence_not_present)
                PresenceStatus.NOT_SCANNED_YET -> getString(R.string.info_mode_presence_not_scanned_yet)
            }
        )
        viewData.hasPresence.set(true)

        historyAdapter.submitList(history)
        viewData.historyEmpty.set(history.isEmpty())
    }
}
