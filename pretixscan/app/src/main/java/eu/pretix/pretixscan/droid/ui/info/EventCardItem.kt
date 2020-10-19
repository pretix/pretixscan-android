package eu.pretix.pretixscan.droid.ui.info

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import org.json.JSONException

import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.pretixscan.droid.R

/**
 * is the handler of a card that displays basic information about the event
 */
class EventCardItem @Throws(JSONException::class)
internal constructor(private val statusResult: TicketCheckProvider.StatusResult) : EventinfoListItem {

    // --- used for the adapter --- //
    override fun getType(): Int {
        return EventinfoActivity.TYPE_EVENTCARD
    }

    override fun getCard(inflater: LayoutInflater, parent: ViewGroup): View {
        val v = inflater.inflate(R.layout.listitem_eventcard, parent, false)
        fillView(v, inflater, parent)
        v.tag = this
        return v
    }

    override fun fillView(view: View, inflater: LayoutInflater, parent: ViewGroup) {
        (view.findViewById<View>(R.id.eventTitle) as TextView).text = statusResult.eventName
        (view.findViewById<View>(R.id.tickets_sold) as TextView).text = statusResult.totalTickets.toString()
        (view.findViewById<View>(R.id.total_scanned) as TextView).text = statusResult.alreadyScanned.toString()
        if (statusResult.currentlyInside != null) {
            (view.findViewById<View>(R.id.inside_number) as TextView).text = statusResult.alreadyScanned.toString()
            view.findViewById<View>(R.id.inside_number).visibility = View.VISIBLE
            view.findViewById<View>(R.id.inside_label).visibility = View.VISIBLE
        } else {
            view.findViewById<View>(R.id.inside_number).visibility = View.GONE
            view.findViewById<View>(R.id.inside_label).visibility = View.GONE
        }
    }

}
