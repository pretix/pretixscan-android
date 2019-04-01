package eu.pretix.pretixscan.droid.ui

import android.app.Activity
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
import org.jetbrains.anko.uiThread


class CheckInListSelectActivity : MorphingDialogActivity() {
    private lateinit var checkInListAdapter: CheckInListAdapter
    private lateinit var listLayoutManager: androidx.recyclerview.widget.LinearLayoutManager
    private lateinit var conf: AppConfig
    private lateinit var mHandler: Handler
    private lateinit var mRunnable: Runnable

    fun syncSync() {
        val api = PretixApi.fromConfig(conf, AndroidHttpClientFactory())
        val sm = SyncManager(
                conf,
                api,
                AndroidSentryImplementation(),
                (application as PretixScan).data,
                (application as PretixScan).fileStorage,
                1000L,
                1000L,
                false
        )
        sm.sync(true)
    }

    fun getAllLists(): List<CheckInList> {
        var lists = (application as PretixScan).data.select(CheckInList::class.java)
                .where(CheckInList.EVENT_SLUG.eq(conf.eventSlug))
        if (conf.subeventId != null && conf.subeventId!! > 0) {
            lists = lists.and(CheckInList.SUBEVENT_ID.eq(conf.subeventId))
        }
        return lists.get().toList();
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_checkinlist_select)

        mHandler = Handler()
        swipe_container.setOnRefreshListener {
            mRunnable = Runnable {
                refresh()
                swipe_container.isRefreshing = false
            }

            mHandler.post(mRunnable)
        }
        refresh()

        listLayoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        checkinlists_list.apply {
            layoutManager = listLayoutManager
        }

        btnOk.setOnClickListener {
            val selectedList = checkInListAdapter.selectedList
            if (selectedList != null) {
                conf.checkinListId = selectedList.getServer_id()

                setResult(Activity.RESULT_OK)
                supportFinishAfterTransition()
            }
        }

        setupTransition(ActivityCompat.getColor(this, R.color.pretix_brand_light))
    }

    fun refresh() {
        conf = AppConfig(this)
        checkInListAdapter = CheckInListAdapter(null)
        progressBar.visibility = View.VISIBLE
        doAsync {
            var listOfLists = getAllLists()
            if (listOfLists.isEmpty()) {

                if ((application as PretixScan).syncLock.tryLock()) {
                    syncSync()
                } else {
                    // A sync is already running – let's not sync, but instead just block until the
                    // sync is done and then continue :)
                    (application as PretixScan).syncLock.lock()
                    (application as PretixScan).syncLock.unlock()
                }

                listOfLists = getAllLists()
            }

            uiThread {
                progressBar.visibility = View.GONE
                checkInListAdapter.selectedList = listOfLists.find { it.server_id == conf.checkinListId }
                if (checkInListAdapter.selectedList == null && listOfLists.size == 1) {
                    checkInListAdapter.selectedList = listOfLists.get(0)
                }
                checkInListAdapter.submitList(listOfLists)
                checkinlists_list.adapter = checkInListAdapter
            }
        }
    }

    override fun onBackPressed() {
        if (conf.checkinListId != 0L) {
            super.onBackPressed()
        }
    }
}