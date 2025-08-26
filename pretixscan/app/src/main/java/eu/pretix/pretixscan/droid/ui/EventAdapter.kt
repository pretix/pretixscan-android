package eu.pretix.pretixscan.droid.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import eu.pretix.libpretixsync.setup.RemoteEvent
import eu.pretix.pretixscan.droid.databinding.ItemEventBinding

class RemoteEventDiffCallback : DiffUtil.ItemCallback<RemoteEvent>() {
    override fun areItemsTheSame(oldItem: RemoteEvent, newItem: RemoteEvent): Boolean {
        return oldItem.slug == newItem.slug
    }

    override fun areContentsTheSame(oldItem: RemoteEvent, newItem: RemoteEvent): Boolean {
        return oldItem == newItem
    }
}

internal class EventAdapter(var selectedEvent: RemoteEvent?) :
        ListAdapter<RemoteEvent, BindingHolder<ItemEventBinding>>(RemoteEventDiffCallback()),
        View.OnClickListener, CompoundButton.OnCheckedChangeListener {
    var list: List<RemoteEvent>? = null
    private val CHECKED_CHANGE = 1

    override fun onBindViewHolder(holder: BindingHolder<ItemEventBinding>, position: Int) {
        val event = getItem(position)
        holder.binding.item = event
        holder.binding.radioButton.setOnCheckedChangeListener(null)
        holder.binding.radioButton.isChecked = event.slug == selectedEvent?.slug
                && event.subevent_id == selectedEvent?.subevent_id
        holder.binding.radioButton.setOnCheckedChangeListener(this)
    }

    override fun onBindViewHolder(holder: BindingHolder<ItemEventBinding>, position: Int, payloads: MutableList<Any>) {
        if (payloads.size > 0 && payloads.all { it == CHECKED_CHANGE }) {
            val event = getItem(position)
            holder.binding.radioButton.setOnCheckedChangeListener(null)
            holder.binding.radioButton.isChecked = event.slug == selectedEvent?.slug
                    && event.subevent_id == selectedEvent?.subevent_id
            holder.binding.radioButton.setOnCheckedChangeListener(this)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BindingHolder<ItemEventBinding> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemEventBinding.inflate(inflater, parent, false)
        binding.root.tag = binding
        binding.root.setOnClickListener(this)
        return BindingHolder(binding)
    }

    override fun onClick(v: View) {
        val binding = v.tag as ItemEventBinding
        if (binding.item != null) {
            val event = binding.item!!
            val previousEvent = selectedEvent
            selectedEvent = event

            if (list != null) {
                notifyItemChanged(list!!.indexOf(previousEvent), CHECKED_CHANGE)
                notifyItemChanged(list!!.indexOf(event), CHECKED_CHANGE)
            }
        }
    }

    override fun onCheckedChanged(v: CompoundButton, checked: Boolean) {
        onClick(v.parent as View)
    }

    override fun submitList(list: List<RemoteEvent>?) {
        this.list = list
        super.submitList(list)
    }
}