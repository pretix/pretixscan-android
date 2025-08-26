package eu.pretix.pretixscan.droid.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import eu.pretix.libpretixsync.db.CheckInList
import eu.pretix.pretixscan.droid.databinding.ItemCheckinlistBinding

class CheckInListTaskDiffCallback : DiffUtil.ItemCallback<CheckInList>() {
    override fun areItemsTheSame(oldItem: CheckInList, newItem: CheckInList): Boolean {
        return oldItem.server_id == newItem.server_id
    }

    override fun areContentsTheSame(oldItem: CheckInList, newItem: CheckInList): Boolean {
        return oldItem == newItem
    }
}

internal class CheckInListAdapter(var selectedList: CheckInList?) :
        ListAdapter<CheckInList, BindingHolder<ItemCheckinlistBinding>>(CheckInListTaskDiffCallback()),
        View.OnClickListener, CompoundButton.OnCheckedChangeListener {
    var list: List<CheckInList>? = null
    private val CHECKED_CHANGE = 1

    override fun onBindViewHolder(holder: BindingHolder<ItemCheckinlistBinding>, position: Int) {
        val checkInList = getItem(position)
        holder.binding.item = checkInList
        holder.binding.radioButton.setOnCheckedChangeListener(null)
        holder.binding.radioButton.isChecked = checkInList.getServer_id() == selectedList?.getServer_id()
        holder.binding.radioButton.setOnCheckedChangeListener(this)
    }

    override fun onBindViewHolder(holder: BindingHolder<ItemCheckinlistBinding>, position: Int, payloads: MutableList<Any>) {
        if (payloads.size > 0 && payloads.all { it == CHECKED_CHANGE }) {
            val checkInList = getItem(position)
            holder.binding.radioButton.setOnCheckedChangeListener(null)
            holder.binding.radioButton.isChecked = checkInList.getServer_id() == selectedList?.getServer_id()
            holder.binding.radioButton.setOnCheckedChangeListener(this)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BindingHolder<ItemCheckinlistBinding> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemCheckinlistBinding.inflate(inflater, parent, false)
        binding.root.tag = binding
        binding.root.setOnClickListener(this)
        return BindingHolder(binding)
    }

    override fun onClick(v: View) {
        val binding = v.tag as ItemCheckinlistBinding
        if (binding.item != null) {
            val checkInList = binding.item!!
            val previousCheckInList = selectedList
            selectedList = checkInList

            if (list != null) {
                notifyItemChanged(list!!.indexOf(previousCheckInList), CHECKED_CHANGE)
                notifyItemChanged(list!!.indexOf(checkInList), CHECKED_CHANGE)
            }
        }
    }

    override fun onCheckedChanged(v: CompoundButton, checked: Boolean) {
        onClick(v.parent as View)
    }

    override fun submitList(list: List<CheckInList>?) {
        this.list = list
        super.submitList(list)
    }
}