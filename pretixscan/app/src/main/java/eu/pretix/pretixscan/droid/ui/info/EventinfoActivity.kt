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

import org.json.JSONException

import java.util.ArrayList

import androidx.appcompat.app.AppCompatActivity
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
    private var config: AppConfig? = null

    private lateinit var mListView: ListView
    private lateinit var mAdapter: EventItemAdapter
    private lateinit var checkProvider: TicketCheckProvider
    private lateinit var mSwipeRefreshLayout: SwipeRefreshLayout

    companion object {
        public val TYPE_EVENTCARD = 0
        public val TYPE_EVENTITEMCARD = 1
        public val MAX_TYPES = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eventinfo)

        mListView = findViewById(R.id.eventinfo_list)
        mAdapter = EventItemAdapter(baseContext)
        mListView.adapter = mAdapter

        this.mSwipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        this.mSwipeRefreshLayout.setOnRefreshListener { StatusTask().execute() }

        this.config = AppConfig(this)

        if (this.config!!.requiresPin("statistics") && (!intent.hasExtra("pin") || !this.config!!.verifyPin(intent.getStringExtra("pin")))) {
            // Protect against external calls
            finish();
            return
        }

        this.checkProvider = (application as PretixScan).getCheckProvider(config!!)

        StatusTask().execute()
    }

    inner class StatusTask : AsyncTask<String, Int, TicketCheckProvider.StatusResult>() {

        internal var e: Exception? = null

        /**
         * exexutes an asyncron request to obtain status information from the pretix instance
         *
         * @param params are ignored
         * @return the associated json object recieved from the pretix-endpoint or null if the request was not successful
         */
        override fun doInBackground(vararg params: String): TicketCheckProvider.StatusResult? {
            try {
                return this@EventinfoActivity.checkProvider.status()
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
        override fun onPostExecute(result: TicketCheckProvider.StatusResult?) {
            this@EventinfoActivity.mSwipeRefreshLayout.isRefreshing = false
            if (this.e != null || result == null) {
                Toast.makeText(this@EventinfoActivity, R.string.error_unknown_exception, Toast.LENGTH_LONG).show()
                finish()
                return
            }

            findViewById<View>(R.id.progressBar).visibility = ProgressBar.GONE
            val eia = this@EventinfoActivity.mAdapter
            eia.clear()
            try {
                val ici = EventCardItem(result)
                eia.addItem(ici)

                val items = result.items
                for (item in items!!) {
                    val eici = EventItemCardItem(item)
                    eia.addItem(eici)
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
            mInflater = LayoutInflater.from(ctx)
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
