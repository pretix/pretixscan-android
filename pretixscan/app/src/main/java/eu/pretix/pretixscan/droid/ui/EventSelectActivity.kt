package eu.pretix.pretixscan.droid.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.Button
import android.widget.CalendarView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kizitonwose.calendarview.model.CalendarDay
import com.kizitonwose.calendarview.model.CalendarMonth
import com.kizitonwose.calendarview.model.DayOwner
import com.kizitonwose.calendarview.ui.DayBinder
import com.kizitonwose.calendarview.ui.MonthHeaderFooterBinder
import com.kizitonwose.calendarview.ui.ViewContainer
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.setup.EventManager
import eu.pretix.libpretixsync.setup.RemoteEvent
import eu.pretix.pretixpos.anim.MorphingDialogActivity
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.EventSelectCalendarDayBinding
import eu.pretix.pretixscan.droid.databinding.EventSelectCalendarHeaderBinding
import eu.pretix.pretixscan.utils.daysOfWeekFromLocale
import kotlinx.android.synthetic.main.activity_event_select.*
import kotlinx.android.synthetic.main.include_event_select_list.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import org.joda.time.DateTimeZone
import org.joda.time.LocalDateTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.*


class EventSelectActivity : MorphingDialogActivity() {
    private lateinit var eventsAdapter: EventAdapter
    private lateinit var eventsLayoutManager: androidx.recyclerview.widget.LinearLayoutManager
    private lateinit var eventManager: EventManager
    private lateinit var conf: AppConfig
    private lateinit var mHandler: Handler
    private lateinit var mRunnable: Runnable
    private val today = LocalDate.now()
    private var selectedDate: LocalDate = today

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_select)

        conf = AppConfig(this)
        if (conf.requiresPin("switch_event") && (!intent.hasExtra("pin") || !conf.verifyPin(intent.getStringExtra("pin")!!))) {
            // Protect against external calls
            finish();
            return
        }

        eventsLayoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        events_list.apply {
            layoutManager = eventsLayoutManager
        }

        btnOk.setOnClickListener {
            val selectedEvent = eventsAdapter.selectedEvent
            if (selectedEvent != null) {

                conf.setEventSlug(selectedEvent.slug)
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

        if (findViewById<CalendarView>(R.id.calendarView) != null) {
            val cv = findViewById<com.kizitonwose.calendarview.CalendarView>(R.id.calendarView)
            val daysOfWeek = daysOfWeekFromLocale()
            cv.setup(YearMonth.now().minusMonths(24), YearMonth.now().plusMonths(36), daysOfWeek.first())
            cv.scrollToDate(today)

            class DayViewContainer(view: View) : ViewContainer(view) {
                // Will be set when this container is bound. See the dayBinder.
                lateinit var day: CalendarDay
                val textView = EventSelectCalendarDayBinding.bind(view).dayText

                init {
                    textView.setOnClickListener {
                        if (day.owner == DayOwner.THIS_MONTH) {
                            if (selectedDate == day.date) {
                                selectedDate = day.date
                                cv.notifyDayChanged(day)
                            } else {
                                val oldDate = selectedDate
                                selectedDate = day.date
                                cv.notifyDateChanged(day.date)
                                oldDate?.let { cv.notifyDateChanged(oldDate) }
                            }
                        }
                        refreshEvents()

                        val btnCalendar = findViewById<Button>(R.id.btnCalendar)
                        if (btnCalendar != null) {
                            cv.visibility = View.GONE
                            findViewById<View>(R.id.eventListContainer).visibility = View.VISIBLE
                            btnCalendar.visibility = View.VISIBLE
                            btnOk.visibility = View.VISIBLE
                        }
                    }
                }
            }

            cv.dayBinder = object : DayBinder<DayViewContainer> {
                override fun create(view: View) = DayViewContainer(view)
                override fun bind(container: DayViewContainer, day: CalendarDay) {
                    container.day = day
                    val textView = container.textView
                    textView.text = day.date.dayOfMonth.toString()

                    if (day.owner == DayOwner.THIS_MONTH) {
                        textView.visibility = View.VISIBLE
                        when (day.date) {
                            selectedDate -> {
                                textView.setTextColor(ContextCompat.getColor(this@EventSelectActivity, R.color.white))
                                textView.setBackgroundResource(R.drawable.calendar_selected_day)
                            }
                            today -> {
                                textView.setTextColor(ContextCompat.getColor(this@EventSelectActivity, R.color.pretix_brand_green))
                                textView.background = null
                            }
                            else -> {
                                textView.setTextColor(ContextCompat.getColor(this@EventSelectActivity, R.color.text_color))
                                textView.background = null
                            }
                        }
                    } else {
                        textView.visibility = View.INVISIBLE
                    }
                }
            }

            class MonthViewContainer(view: View) : ViewContainer(view) {
                val textView = EventSelectCalendarHeaderBinding.bind(view).headerText
            }
            cv.monthHeaderBinder = object : MonthHeaderFooterBinder<MonthViewContainer> {
                override fun create(view: View) = MonthViewContainer(view)
                override fun bind(container: MonthViewContainer, month: CalendarMonth) {
                    @SuppressLint("SetTextI18n") // Concatenation warning for `setText` call.
                    container.textView.text = "${month.yearMonth.month.name.toLowerCase().capitalize()} ${month.year}"
                }
            }
            val btnCalendar = findViewById<Button>(R.id.btnCalendar)
            if (btnCalendar != null) {
                cv.visibility = View.GONE
                findViewById<Button>(R.id.btnCalendar).setOnClickListener {
                    cv.visibility = View.VISIBLE
                    findViewById<View>(R.id.eventListContainer).visibility = View.GONE
                    btnCalendar.visibility = View.GONE
                    btnOk.visibility = View.GONE
                }
            }
        }

        refreshEvents()
    }

    fun refreshEvents() {
        conf = AppConfig(this)
        val api = PretixApi.fromConfig(conf, AndroidHttpClientFactory(application as PretixScan))
        eventManager = EventManager((application as PretixScan).data, api, conf, false)
        eventsAdapter = EventAdapter(null)
        tvError.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        noEventsMessage.visibility = View.GONE
        eventsAdapter.submitList(emptyList())
        events_list.adapter = eventsAdapter

        doAsync {
            val events: List<RemoteEvent>
            try {
                val selectedAsJodaTime = LocalDateTime(selectedDate.atStartOfDay().atZone(ZoneOffset.systemDefault()).toInstant().toEpochMilli()).toDateTime(DateTimeZone.getDefault())
                events = eventManager.getAvailableEvents(selectedAsJodaTime, 5, null, null)
            } catch (e: Exception) {
                swipe_container.isRefreshing = false
                uiThread {
                    tvError.text = e.toString()
                    tvError.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    eventsAdapter.submitList(emptyList())
                }
                return@doAsync
            }
            uiThread {
                progressBar.visibility = View.GONE
                noEventsMessage.visibility = if (events.isEmpty()) { View.VISIBLE } else { View.GONE }
                eventsAdapter.selectedEvent = events.find { it.slug == conf.getEventSlug() && it.subevent_id == conf.subeventId }
                eventsAdapter.submitList(events)
                events_list.adapter = eventsAdapter

                val last = events.findLast { it.date_from.toLocalDate() == org.joda.time.LocalDate.now() && it.date_from.isBeforeNow }
                if (last != null) {
                    Handler().postDelayed({
                        eventsLayoutManager.scrollToPositionWithOffset(events.indexOf(last), 10)
                    }, 100)
                }
            }
        }
    }

    override fun onBackPressed() {
        if (conf.getEventSlug() != null) {
            super.onBackPressed()
        }
    }
}