package eu.pretix.pretixscan.droid.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import eu.pretix.libpretixsync.api.DeviceAccessRevokedException
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.setup.EventManager
import eu.pretix.libpretixsync.setup.RemoteEvent
import eu.pretix.pretixpos.anim.MorphingDialogActivity
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityEventSelectBinding
import eu.pretix.pretixscan.droid.databinding.EventSelectCalendarDayBinding
import eu.pretix.pretixscan.droid.databinding.EventSelectCalendarHeaderBinding
import eu.pretix.pretixscan.utils.Material3
import eu.pretix.pretixscan.utils.daysOfWeekFromLocale
import org.jetbrains.anko.*
import org.joda.time.DateTimeZone
import org.joda.time.LocalDateTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset


class EventSelectActivity : MorphingDialogActivity() {
    private lateinit var binding: ActivityEventSelectBinding
    private lateinit var eventsAdapter: EventAdapter
    private lateinit var eventsLayoutManager: androidx.recyclerview.widget.LinearLayoutManager
    private lateinit var eventManager: EventManager
    private lateinit var conf: AppConfig
    private lateinit var mHandler: Handler
    private lateinit var mRunnable: Runnable
    private val today = LocalDate.now()
    private var selectedDate: LocalDate = today

    companion object {
        const val EVENT_SLUG = "event_slug"
        const val EVENT_NAME = "event_name"
        const val EVENT_DATE_FROM = "event_date_from"
        const val EVENT_DATE_TO = "event_date_to"
        const val SUBEVENT_ID = "subevent_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        conf = AppConfig(this)

        eventsLayoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.eventSelectList!!.eventsList.apply {
            layoutManager = eventsLayoutManager
        }

        binding.btnOk.setOnClickListener {
            val selectedEvent = eventsAdapter.selectedEvent
            if (selectedEvent != null) {
                val i = Intent()
                i.putExtra(EVENT_SLUG, selectedEvent.slug)
                i.putExtra(EVENT_NAME, selectedEvent.name)
                i.putExtra(EVENT_DATE_FROM, selectedEvent.date_from)
                i.putExtra(EVENT_DATE_TO, selectedEvent.date_to)
                i.putExtra(SUBEVENT_ID, selectedEvent.subevent_id)
                setResult(Activity.RESULT_OK, i)
                supportFinishAfterTransition()
            }
        }

        mHandler = Handler()
        binding.eventSelectList!!.swipeContainer.setOnRefreshListener {
            mRunnable = Runnable {
                refreshEvents()
                binding.eventSelectList!!.swipeContainer.isRefreshing = false
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
                            binding.btnOk.visibility = View.VISIBLE
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
                    binding.btnOk.visibility = View.GONE
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
        binding.eventSelectList!!.tvError.visibility = View.GONE
        binding.eventSelectList!!.progressBar.visibility = View.VISIBLE
        binding.eventSelectList!!.noEventsMessage.visibility = View.GONE
        eventsAdapter.submitList(emptyList())
        binding.eventSelectList!!.eventsList.adapter = eventsAdapter

        doAsync {
            val events: List<RemoteEvent>
            try {
                val selectedAsJodaTime = LocalDateTime(selectedDate.atStartOfDay().atZone(ZoneOffset.systemDefault()).toInstant().toEpochMilli()).toDateTime(DateTimeZone.getDefault())
                events = eventManager.getAvailableEvents(selectedAsJodaTime, 5, null, null, null)
            } catch (e: DeviceAccessRevokedException) {
                runOnUiThread {
                    alert(Material3, R.string.error_access_revoked) {
                        okButton {
                            wipeApp(this@EventSelectActivity)
                        }
                    }.show()
                }
                return@doAsync
            } catch (e: Exception) {
                binding.eventSelectList!!.swipeContainer.isRefreshing = false
                uiThread {
                    binding.eventSelectList!!.tvError.text = e.toString()
                    binding.eventSelectList!!.tvError.visibility = View.VISIBLE
                    binding.eventSelectList!!.progressBar.visibility = View.GONE
                    eventsAdapter.submitList(emptyList())
                }
                return@doAsync
            }
            uiThread {
                binding.eventSelectList!!.progressBar.visibility = View.GONE
                binding.eventSelectList!!.noEventsMessage.visibility = if (events.isEmpty()) { View.VISIBLE } else { View.GONE }

                if (intent.extras?.containsKey(EVENT_SLUG) == true) {
                    eventsAdapter.selectedEvent = events.find {
                        it.slug == intent.extras!!.getString(EVENT_SLUG) && (it.subevent_id == intent.extras!!.getLong(SUBEVENT_ID, 0L) || it.subevent_id == null && intent.extras!!.getLong(SUBEVENT_ID, 0) < 1)
                    }
                }

                eventsAdapter.submitList(events)
                binding.eventSelectList!!.eventsList.adapter = eventsAdapter

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
        if (conf.synchronizedEvents.isNotEmpty()) {
            super.onBackPressed()
        }
    }
}