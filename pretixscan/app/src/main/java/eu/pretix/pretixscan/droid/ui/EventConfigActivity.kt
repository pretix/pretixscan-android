package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import eu.pretix.libpretixsync.db.CheckInList
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.EventSelection
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityEventConfigBinding
import eu.pretix.pretixscan.droid.databinding.ItemEventSelectionBinding
import io.requery.BlockingEntityStore
import io.requery.Persistable
import org.joda.time.DateTime


class EventSelectionDiffCallback : DiffUtil.ItemCallback<EventSelection>() {
    override fun areItemsTheSame(oldItem: EventSelection, newItem: EventSelection): Boolean {
        return oldItem.eventSlug == newItem.eventSlug
    }

    override fun areContentsTheSame(oldItem: EventSelection, newItem: EventSelection): Boolean {
        return oldItem.eventSlug == newItem.eventSlug && oldItem.checkInList == newItem.checkInList && oldItem.subEventId == newItem.subEventId
    }
}

internal class EventSelectionAdapter(private val store: BlockingEntityStore<Persistable>, private val config: AppConfig, private val activity: EventConfigActivity) :
        ListAdapter<EventSelection, BindingHolder<ItemEventSelectionBinding>>(EventSelectionDiffCallback()) {
    var list: List<EventSelection>? = null

    override fun onBindViewHolder(holder: BindingHolder<ItemEventSelectionBinding>, position: Int) {
        val event = getItem(position)
        holder.binding.eventSelection = event
        holder.binding.checkinListName = store.select(CheckInList.NAME).from(CheckInList::class.java).where(CheckInList.SERVER_ID.eq(event.checkInList)).get().firstOrNull()?.get(0)
                ?: "list ${event.checkInList}"
        holder.binding.deleteButton.setOnClickListener {
            config.removeEvent(event.eventSlug)
            refresh()
            notifyDataSetChanged()
        }
        holder.binding.editButton.setOnClickListener {
            activity.changeListForEvent(event)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BindingHolder<ItemEventSelectionBinding> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemEventSelectionBinding.inflate(inflater, parent, false)
        return BindingHolder(binding)
    }

    fun refresh() {
        submitList(config.eventSelection)
    }

    override fun submitList(list: List<EventSelection>?) {
        this.list = list
        super.submitList(list)
    }
}

class EventConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEventConfigBinding
    private lateinit var conf: AppConfig
    private lateinit var eventSelectionAdapter: EventSelectionAdapter
    private var eventSelectResult: ActivityResult? = null

    private var eventSelectLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            eventSelectResult = result
            val i = Intent(this, CheckInListSelectActivity::class.java).apply {
                putExtra(CheckInListSelectActivity.EVENT_SLUG, result.data!!.getStringExtra(EventSelectActivity.EVENT_SLUG))
                putExtra(CheckInListSelectActivity.SUBEVENT_ID, result.data!!.getLongExtra(EventSelectActivity.SUBEVENT_ID, 0L))
                val cs = conf.eventSelection
                if (!conf.multiEventMode && cs.size == 1) {
                    putExtra(CheckInListSelectActivity.LIST_ID, cs.first().checkInList)
                }
            }
            checkinListSelectLauncher.launch(i)
        } else {
            if (!conf.multiEventMode) {
                if (conf.synchronizedEvents.isNotEmpty()) {
                    supportFinishAfterTransition()
                } else {
                    startAddEvent()
                }
            }
        }
    }

    private var checkinListSelectLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && eventSelectResult != null) {
            val s = EventSelection(
                    eventSlug = eventSelectResult!!.data!!.getStringExtra(EventSelectActivity.EVENT_SLUG)
                            ?: return@registerForActivityResult,
                    eventName = eventSelectResult!!.data!!.getStringExtra(EventSelectActivity.EVENT_NAME)
                            ?: return@registerForActivityResult,
                    subEventId = if (eventSelectResult!!.data!!.getLongExtra(CheckInListSelectActivity.SUBEVENT_ID, -1) < 1) null else eventSelectResult!!.data!!.getLongExtra(CheckInListSelectActivity.SUBEVENT_ID, -1),
                    dateFrom = eventSelectResult!!.data!!.getSerializableExtra(EventSelectActivity.EVENT_DATE_FROM) as DateTime?,
                    dateTo = eventSelectResult!!.data!!.getStringExtra(EventSelectActivity.EVENT_DATE_TO) as DateTime?,
                    checkInList = result.data!!.getLongExtra(CheckInListSelectActivity.LIST_ID, -1),
            )
            if (conf.multiEventMode) {
                conf.addOrReplaceEvent(s)
                eventSelectionAdapter.refresh()
            } else {
                conf.eventSelection = listOf(s)
                supportFinishAfterTransition()
            }
        } else {
            if (!conf.multiEventMode) {
                if (conf.synchronizedEvents.isNotEmpty()) {
                    supportFinishAfterTransition()
                } else {
                    startAddEvent()
                }
            }
        }
    }

    fun changeListForEvent(event: EventSelection) {
        val intent = Intent().apply {
            putExtra(EventSelectActivity.EVENT_SLUG, event.eventSlug)
            putExtra(EventSelectActivity.EVENT_NAME, event.eventName)
            putExtra(EventSelectActivity.EVENT_DATE_TO, event.dateTo)
            putExtra(EventSelectActivity.EVENT_DATE_FROM, event.dateFrom)
            putExtra(EventSelectActivity.SUBEVENT_ID, event.subEventId)
        }
        eventSelectResult = ActivityResult(Activity.RESULT_OK, intent)
        val i = Intent(this, CheckInListSelectActivity::class.java).apply {
            putExtra(CheckInListSelectActivity.EVENT_SLUG, event.eventSlug)
            putExtra(CheckInListSelectActivity.SUBEVENT_ID, event.subEventId)
            putExtra(CheckInListSelectActivity.LIST_ID, event.checkInList)
        }
        checkinListSelectLauncher.launch(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        conf = AppConfig(this)
        eventSelectionAdapter = EventSelectionAdapter((application as PretixScan).data, conf, this)
        if (conf.requiresPin("switch_event") && (!intent.hasExtra("pin") || !conf.verifyPin(intent.getStringExtra("pin")!!))) {
            // Protect against external calls
            finish()
            return
        }

        binding.rvEventList.apply {
            layoutManager = LinearLayoutManager(this@EventConfigActivity)
            adapter = eventSelectionAdapter
        }

        binding.fabAdd.setOnClickListener {
            startAddEvent()
        }

        if (!conf.multiEventMode || conf.eventSelection.isEmpty()) {
            startAddEvent()
        } else {
            eventSelectionAdapter.refresh()
        }
    }

    private fun startAddEvent() {
        val intent = Intent(this, EventSelectActivity::class.java)
        val cs = conf.eventSelection
        if (!conf.multiEventMode && cs.size == 1) {
            intent.putExtra(EventSelectActivity.EVENT_SLUG, cs.first().eventSlug)
            intent.putExtra(EventSelectActivity.SUBEVENT_ID, cs.first().subEventId)
        }
        eventSelectLauncher.launch(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu to use in the action bar
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_event_config, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_ok -> {
                if (conf.synchronizedEvents.isNotEmpty()) {
                    supportFinishAfterTransition()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (conf.synchronizedEvents.isNotEmpty()) {
            super.onBackPressed()
        }
    }
}