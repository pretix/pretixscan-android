package eu.pretix.pretixscan.droid.ui.info

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
import eu.pretix.libpretixui.android.scanning.ScannerView
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityInfoModeBinding
import eu.pretix.pretixscan.droid.ui.checkPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InfoModeViewDataHolder {
    val isScanning = ObservableField(false)
    val hasResult = ObservableField(false)
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
    val resultReason = ObservableField("")
    val hasReason = ObservableField(false)
    val presenceLabel = ObservableField("")
    val hasPresence = ObservableField(false)
    val historyEmpty = ObservableField(true)
}

/**
 * TODO (step 2, later): "Book entry now" / "Book exit now" buttons that turn the currently
 * shown simulated result into a real check-in (re-call with simulate=false).
 */
class InfoModeActivity : AppCompatActivity(), ScannerView.ResultHandler {

    private lateinit var binding: ActivityInfoModeBinding
    private lateinit var config: AppConfig
    private lateinit var checkProvider: TicketCheckProvider
    private val viewData = InfoModeViewDataHolder()
    private val historyAdapter = CheckinHistoryAdapter()
    private var activeCheckinListServerId: Long? = null
    private var lastScanCode: String? = null
    private var lastScanTime: Long = 0L

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

        config = AppConfig(this)
        checkProvider = (application as PretixScan).getCheckProvider(config)

        if (config.requiresPin("info_mode") &&
            (!intent.hasExtra(EXTRA_PIN) || !config.verifyPin(intent.getStringExtra(EXTRA_PIN)!!))
        ) {
            finish()
            return
        }

        activeCheckinListServerId = config.eventSelectionToMap().values.firstOrNull()

        binding = DataBindingUtil.setContentView(this, R.layout.activity_info_mode)
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

        binding.resultCard.setOnClickListener {
            viewData.hasResult.set(false)
        }

        checkPermission(Manifest.permission.CAMERA, PERMISSIONS_REQUEST_CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.scannerView.setResultHandler(this)
            binding.scannerView.startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
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

    override fun handleResult(rawResult: ScannerView.Result) {
        val secret = rawResult.text
        if (secret == lastScanCode && System.currentTimeMillis() - lastScanTime < 5000) {
            return
        }
        lastScanTime = System.currentTimeMillis()
        lastScanCode = secret
        onTicketScanned(secret)
    }

    private fun onTicketScanned(secret: String) {
        viewData.isScanning.set(true)
        lifecycleScope.launch {
            val (result, history) = performSimulatedCheck(secret)
            renderResult(result, history)
            viewData.isScanning.set(false)
        }
    }

    private suspend fun performSimulatedCheck(
        secret: String
    ): Pair<TicketCheckProvider.CheckResult, List<TicketCheckinHistoryEntry>> {
        val result = withContext(Dispatchers.IO) {
            checkProvider.check(
                config.eventSelectionToMap(),
                secret,
                "barcode",
                null,
                false,
                false,
                CheckInType.ENTRY,
                simulate = true,
            )
        }
        val positionServerId = result.position?.optLong("id")
        val history = withContext(Dispatchers.IO) {
            loadCheckinHistory((application as PretixScan).db, positionServerId)
        }
        return result to history
    }

    private fun renderResult(result: TicketCheckProvider.CheckResult, history: List<TicketCheckinHistoryEntry>) {
        val isMultiEvent = config.eventSelectionToMap().size > 1
        viewData.resultEventSlug.set(result.eventSlug.takeIf { isMultiEvent }.orEmpty())
        viewData.hasEventSlug.set(isMultiEvent && !result.eventSlug.isNullOrEmpty())

        val accent = result.type.toInfoModeAccent()
        val resolvedColor = ContextCompat.getColor(this, accent.colorRes)

        binding.resultIcon.setImageResource(accent.iconRes)
        binding.resultIcon.setColorFilter(resolvedColor)
        binding.resultStatusLabel.setTextColor(resolvedColor)

        viewData.resultStatusLabel.set(getString(accent.labelRes))
        viewData.hasAttention.set(result.isRequireAttention)
        viewData.resultAttendeeName.set(
            result.attendee_name ?: getString(R.string.info_mode_no_name)
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

        val seat = result.seat.takeIf { result.scanType == CheckInType.ENTRY }
        viewData.resultSeat.set(seat.orEmpty())
        viewData.hasSeat.set(!seat.isNullOrEmpty())

        val checkinTexts = result.checkinTexts
            ?.filterNot { it.isBlank() }
            ?.takeIf { it.isNotEmpty() && result.scanType == CheckInType.ENTRY }
            ?.joinToString("\n")
        viewData.resultCheckinTexts.set(checkinTexts.orEmpty())
        viewData.hasCheckinTexts.set(!checkinTexts.isNullOrEmpty())

        val shownAnswers = result.shownAnswers
        if (result.scanType == CheckInType.ENTRY && !shownAnswers.isNullOrEmpty()) {
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

        val reason = result.message ?: result.reasonExplanation
        viewData.resultReason.set(reason.orEmpty())
        viewData.hasReason.set(!reason.isNullOrEmpty())
        viewData.hasResult.set(true)

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