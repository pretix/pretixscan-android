package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.View
import androidx.core.app.ActivityCompat
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.db.CheckInList
import eu.pretix.libpretixsync.sync.SyncManager
import eu.pretix.pretixpos.anim.MorphingDialogActivity
import eu.pretix.pretixscan.droid.*
import kotlinx.android.synthetic.main.activity_checkinlist_select.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.indeterminateProgressDialog
import org.json.JSONObject
import kotlin.concurrent.withLock


class CheckInListSelectActivity : MorphingDialogActivity() {
    private lateinit var checkInListAdapter: CheckInListAdapter
    private lateinit var listLayoutManager: androidx.recyclerview.widget.LinearLayoutManager
    private lateinit var conf: AppConfig
    private lateinit var mHandler: Handler
    private lateinit var mRunnable: Runnable

    companion object {
        const val EVENT_SLUG = "event_slug"
        const val SUBEVENT_ID = "subevent_id"
        const val LIST_ID = "list_id"
    }

    private fun syncSync() {
        val pdialog = indeterminateProgressDialog(title = R.string.progress_syncing_first, message = R.string.progress_syncing_first)
        pdialog.setCanceledOnTouchOutside(false)
        pdialog.setCancelable(false)
        doAsync {
            val api = PretixApi.fromConfig(conf, AndroidHttpClientFactory(application as PretixScan))
            val event = intent!!.getStringExtra(EVENT_SLUG)
            val subevent = intent!!.getLongExtra(SUBEVENT_ID, 0L)
            val sm = SyncManager(
                    conf,
                    api,
                    AndroidSentryImplementation(),
                    (application as PretixScan).data,
                    (application as PretixScan).fileStorage,
                    1000L,
                    1000L,
                    SyncManager.Profile.PRETIXSCAN,
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
                    null
            )
            sm.syncMinimalEventSet(event, subevent) { current_action ->
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    pdialog.setMessage(current_action)
                }
            }
            runOnUiThread {
                pdialog.dismiss()
                refreshList(getAllLists())
            }
        }
    }

    private fun getAllLists(): List<CheckInList> {
        val event = intent!!.getStringExtra(EVENT_SLUG)
        val subeventId = intent!!.getLongExtra(SUBEVENT_ID, 0)
        var lists = (application as PretixScan).data.select(CheckInList::class.java)
                .where(CheckInList.EVENT_SLUG.eq(event))
        if (subeventId > 0) {
            lists = lists.and(CheckInList.SUBEVENT_ID.eq(subeventId).or(CheckInList.SUBEVENT_ID.eq(0)))
        }
        return lists.orderBy(CheckInList.SUBEVENT_ID.asc(), CheckInList.NAME.asc()).get().toList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_checkinlist_select)

        mHandler = Handler()
        swipe_container.setOnRefreshListener {
            mRunnable = Runnable {
                refresh(true)
                swipe_container.isRefreshing = false
            }

            mHandler.post(mRunnable)
        }
        refresh()

        if (conf.multiEventMode || conf.knownPretixVersion < 40120001001) { // 4.12.0.dev1
            cbMultievent.visibility = View.GONE
        }

        listLayoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        checkinlists_list.apply {
            layoutManager = listLayoutManager
        }

        btnOk.setOnClickListener {
            val selectedList = checkInListAdapter.selectedList
            if (selectedList != null) {
                val i = Intent()
                i.putExtra(LIST_ID, selectedList.getServer_id())
                setResult(Activity.RESULT_OK, i)
                supportFinishAfterTransition()
            }
            if (!conf.multiEventMode && cbMultievent.isChecked) {
                conf.multiEventMode = true
                conf.autoSwitchRequested = false
            }
        }

        setupTransition(ActivityCompat.getColor(this, R.color.pretix_brand_light))
    }

    private fun refresh(forceSync: Boolean = false) {
        conf = AppConfig(this)
        checkInListAdapter = CheckInListAdapter(null)
        progressBar.visibility = View.VISIBLE
        val listOfLists = getAllLists()
        val subeventChanged = !conf.eventSelection.map { it.subEventId ?: 0 }.contains(intent!!.getLongExtra(SUBEVENT_ID, 0))
        if (forceSync || listOfLists.isEmpty() || subeventChanged) {
            (application as PretixScan).syncLock.withLock {
                syncSync()
            }
        } else {
            refreshList(listOfLists)
        }
    }

    private fun refreshList(listOfLists: List<CheckInList>) {
        progressBar.visibility = View.GONE
        checkInListAdapter.selectedList = listOfLists.find { it.server_id == intent.getLongExtra(LIST_ID, 0) }
        if (checkInListAdapter.selectedList == null && listOfLists.size == 1) {
            checkInListAdapter.selectedList = listOfLists[0]
        }
        checkInListAdapter.submitList(listOfLists)
        checkinlists_list.adapter = checkInListAdapter
        if (conf.multiEventMode || conf.knownPretixVersion < 40120001001) { // 4.12.0.dev1
            cbMultievent.visibility = View.GONE
        } else {
            cbMultievent.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        supportFinishAfterTransition()
    }
}