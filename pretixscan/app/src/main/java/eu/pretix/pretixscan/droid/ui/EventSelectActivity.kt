package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.view.View
import androidx.core.app.ActivityCompat
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.setup.EventManager
import eu.pretix.pretixpos.anim.MorphingDialogActivity
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import kotlinx.android.synthetic.main.activity_event_select.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class EventSelectActivity : MorphingDialogActivity() {
    private lateinit var eventsAdapter: EventAdapter
    private lateinit var eventsLayoutManager: androidx.recyclerview.widget.LinearLayoutManager
    private lateinit var eventManager: EventManager
    private lateinit var conf: AppConfig
    private lateinit var mHandler: Handler
    private lateinit var mRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_select)


        eventsLayoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        events_list.apply {
            layoutManager = eventsLayoutManager
        }

        btnOk.setOnClickListener {
            val selectedEvent = eventsAdapter.selectedEvent
            if (selectedEvent != null) {

                conf.eventSlug = selectedEvent.slug
                conf.subeventId = selectedEvent.subevent_id
                conf.eventName = selectedEvent.name
                conf.checkinListId = 0

                setResult(Activity.RESULT_OK)
                supportFinishAfterTransition()
            }
        }

        mHandler = Handler()
        swipe_container.setOnRefreshListener {
            mRunnable = Runnable {
                refreshEvents()
                swipe_container.isRefreshing = false
            }

            mHandler.post(mRunnable)
        }

        setupTransition(ActivityCompat.getColor(this, R.color.pretix_brand_light))
        refreshEvents()
    }

    fun refreshEvents() {
        conf = AppConfig(this)
        val api = PretixApi.fromConfig(conf, AndroidHttpClientFactory())
        eventManager = EventManager((application as PretixScan).data, api, conf)
        eventsAdapter = EventAdapter(null)
        progressBar.visibility = View.VISIBLE
        doAsync {
            val events = eventManager.getAvailableEvents()
            uiThread {
                progressBar.visibility = View.GONE
                eventsAdapter.selectedEvent = events.find { it.slug == conf.eventSlug && it.subevent_id == conf.subeventId }
                eventsAdapter.submitList(events)
                events_list.adapter = eventsAdapter
            }
        }
    }

    override fun onBackPressed() {
        if (conf.eventSlug != null) {
            super.onBackPressed()
        }
    }
}