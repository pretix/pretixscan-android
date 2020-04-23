package eu.pretix.pretixscan.droid.ui.info

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import org.json.JSONException

import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.pretixscan.droid.R

/**
 * is the handler of a card that displays information about each item of an event
 */
class EventItemCardItem @Throws(JSONException::class)
internal constructor(private val resultItem: TicketCheckProvider.StatusResultItem) : EventinfoListItem {

    // --- used for the adapter --- //
    override fun getType(): Int {
        return EventinfoActivity.TYPE_EVENTITEMCARD;
    }

    override fun getCard(inflater: LayoutInflater, parent: ViewGroup): View {
        val v = inflater.inflate(R.layout.listitem_eventitemcard, parent, false)
        fillView(v, inflater, parent)
        v.tag = this
        return v
    }

    override fun fillView(view: View, inflater: LayoutInflater, parent: ViewGroup) {
        (view.findViewById<View>(R.id.itemTitle) as TextView).text = resultItem.name
        (view.findViewById<View>(R.id.itemQuantity) as TextView).text = resultItem.checkins.toString() + "/" + resultItem.total.toString()

        val variationList = view.findViewById<View>(R.id.variationList) as ViewGroup
        variationList.removeAllViews()

        for (current in resultItem.variations!!) {
            val variationLine = inflater.inflate(R.layout.listitem_eventitemvariation, parent, false)
            (variationLine.findViewById<View>(R.id.itemVariationTitle) as TextView).text = current.name
            (variationLine.findViewById<View>(R.id.itemVariationQuantity) as TextView).text = current.checkins.toString() + "/" + current.total.toString()

            variationList.addView(variationLine)
        }
    }
}
