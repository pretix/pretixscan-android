package eu.pretix.pretixscan.droid.ui.info

import android.content.Context
import android.os.AsyncTask
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge

import org.json.JSONException

import java.util.ArrayList

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import eu.pretix.libpretixsync.check.CheckException
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R

/**
 * This class is the activity for the Eventinfo page to let the user see statistics about their
 * event.
 *
 * @author jfwiebe
 */
class EventinfoActivity : AppCompatActivity() {
    private lateinit var config: AppConfig
    private lateinit var mListView: ListView
    private lateinit var mAdapter: EventItemAdapter
    private lateinit var checkProvider: TicketCheckProvider
    private lateinit var mSwipeRefreshLayout: SwipeRefreshLayout

    companion object {
        val TYPE_EVENTCARD = 0
        val TYPE_EVENTITEMCARD = 1
        val MAX_TYPES = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eventinfo)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.content)
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = insets.left,
                right = insets.right,
                top = 0, // handled by AppBar
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        mListView = findViewById(R.id.eventinfo_list)
        mAdapter = EventItemAdapter(this)
        mListView.adapter = mAdapter

        mSwipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        mSwipeRefreshLayout.setOnRefreshListener { StatusTask().execute() }

        config = AppConfig(this)

        if (config.requiresPin("statistics") && (!intent.hasExtra("pin") || !this.config.verifyPin(intent.getStringExtra("pin")!!))) {
            // Protect against external calls
            finish()
            return
        }

        this.checkProvider = (application as PretixScan).getCheckProvider(config)

        StatusTask().execute()
    }

    inner class StatusTask : AsyncTask<String, Int, List<TicketCheckProvider.StatusResult>>() {

        internal var e: Exception? = null

        /**
         * exexutes an asyncron request to obtain status information from the pretix instance
         *
         * @param params are ignored
         * @return the associated json object recieved from the pretix-endpoint or null if the request was not successful
         */
        override fun doInBackground(vararg params: String): List<TicketCheckProvider.StatusResult>? {
            try {
                val res = mutableListOf<TicketCheckProvider.StatusResult>()
                for (e in config.synchronizedEvents) {
                    res.add(checkProvider.status(e, config.getSelectedCheckinListForEvent(e) ?: 0)
                            ?: return null)
                }
                return res
            } catch (e: CheckException) {
                e.printStackTrace()
                this.e = e
            }

            return null
        }

        /**
         * it parses the answer of the pretix endpoint into objects and adds them to the list
         *
         * @param result the answer of the pretix status endpoint
         */
        override fun onPostExecute(results: List<TicketCheckProvider.StatusResult>?) {
            this@EventinfoActivity.mSwipeRefreshLayout.isRefreshing = false
            if (this.e != null || results == null) {
                Toast.makeText(this@EventinfoActivity, R.string.error_unknown_exception, Toast.LENGTH_LONG).show()
                finish()
                return
            }

            findViewById<View>(R.id.progressBar).visibility = ProgressBar.GONE
            val eia = this@EventinfoActivity.mAdapter
            eia.clear()
            try {
                for (result in results) {
                    val ici = EventCardItem(result)
                    eia.addItem(ici)

                    val items = result.items
                    for (item in items!!) {
                        val eici = EventItemCardItem(item)
                        eia.addItem(eici)
                    }
                }
            } catch (e: JSONException) {
                Toast.makeText(this@EventinfoActivity, R.string.error_unknown_exception, Toast.LENGTH_LONG).show()
                finish()
                return
            }

        }
    }

    /**
     * implementation of an adapter for a listview to hold EventCards and EventItemCards
     */
    inner class EventItemAdapter(ctx: Context) : BaseAdapter() {

        private val mData = ArrayList<EventinfoListItem>()
        val mInflater: LayoutInflater

        init {
            mInflater = ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        }

        fun addItem(item: EventinfoListItem) {
            this.mData.add(item)
            notifyDataSetChanged()
        }

        fun clear() {
            this.mData.clear()
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return this.mData[position].type
        }

        override fun getViewTypeCount(): Int {
            return MAX_TYPES
        }

        override fun getCount(): Int {
            return mData.size
        }

        override fun getItem(position: Int): EventinfoListItem {
            return mData[position]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (convertView == null) {
                val item = this.getItem(position)
                return item.getCard(mInflater, parent)
            } else {
                this.getItem(position).fillView(convertView, mInflater, parent)
                return convertView
            }

        }

    }

}
