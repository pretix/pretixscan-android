package eu.pretix.pretixscan.droid.ui.info

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.widget.SearchView
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
import androidx.recyclerview.widget.DividerItemDecoration
import eu.pretix.libpretixsync.check.CheckException
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.check.TicketCheckProvider.CheckInType
import eu.pretix.libpretixsync.db.Answer
import eu.pretix.libpretixsync.db.ReusableMediaType
import eu.pretix.libpretixsync.models.db.toModel
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityInfoModeBinding
import eu.pretix.pretixscan.droid.ui.BaseScanActivity
import eu.pretix.pretixscan.droid.ui.MainActivity
import eu.pretix.pretixscan.droid.ui.SearchListAdapter
import eu.pretix.pretixscan.droid.ui.SearchResultClickedInterface
import eu.pretix.pretixscan.droid.ui.applyDefaultMessage
import eu.pretix.pretixscan.droid.ui.checkPermission
import eu.pretix.pretixscan.droid.ui.reasonExplanationText
import eu.pretix.pretixscan.droid.ui.resultState
import io.sentry.Sentry
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
    val isSearching = ObservableField(false)
    val searchLoading = ObservableField(false)
    val searchEmpty = ObservableField(false)
}

class InfoModeActivity : BaseScanActivity() {

    private lateinit var binding: ActivityInfoModeBinding
    private val viewData = InfoModeViewDataHolder()
    private val historyAdapter = CheckinHistoryAdapter()
    private var activeCheckinListServerId: Long? = null
    private var searchFilter = ""

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
        binding.historyList.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.searchList.layoutManager = LinearLayoutManager(this)
        binding.searchList.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )

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
        hideSearch()
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
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_SCAN_SECRET, secret)
                putExtra(MainActivity.EXTRA_SCAN_SOURCE_TYPE, lastScanSourceType.name)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_info_mode, menu)

        val searchItem = menu.findItem(R.id.action_search)
        searchItem.isVisible = !conf.searchDisabled
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.info_mode_search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                setSearchFilter(query)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                setSearchFilter(newText)
                return true
            }
        })
        searchView.setOnCloseListener {
            hideSearch()
            false
        }

        return super.onCreateOptionsMenu(menu)
    }

    private fun hideSearch() {
        searchFilter = ""
        viewData.isSearching.set(false)
        viewData.searchLoading.set(false)
        viewData.searchEmpty.set(false)
    }

    private fun setSearchFilter(query: String) {
        if (query.isEmpty()) {
            hideSearch()
            return
        }
        searchFilter = query
        viewData.isSearching.set(true)
        viewData.searchLoading.set(true)
        viewData.searchEmpty.set(false)

        bgScope.launch {
            try {
                val results = (application as PretixScan).getCheckProvider(conf)
                    .search(conf.eventSelectionToMap(), query, 1)
                if (query != searchFilter) return@launch
                val adapter = SearchListAdapter(results, object : SearchResultClickedInterface {
                    override fun onSearchResultClicked(res: TicketCheckProvider.SearchResult) {
                        val secret = res.secret ?: return
                        hideSearch()
                        lastScanTime = System.currentTimeMillis()
                        lastScanCode = secret
                        lastScanSourceType = ReusableMediaType.BARCODE
                        lastScanResult = null
                        handleScan(secret, lastScanSourceType.serverName!!, null, true)
                    }
                })
                runOnUiThread {
                    binding.searchList.adapter = adapter
                    viewData.searchLoading.set(false)
                    viewData.searchEmpty.set(results.isEmpty())
                }
            } catch (e: CheckException) {
                runOnUiThread {
                    hideSearch()
                    Toast.makeText(
                        this@InfoModeActivity,
                        e.message ?: getString(R.string.error_unknown_exception),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Sentry.captureException(e)
                runOnUiThread {
                    hideSearch()
                    Toast.makeText(
                        this@InfoModeActivity,
                        R.string.error_unknown_exception,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
