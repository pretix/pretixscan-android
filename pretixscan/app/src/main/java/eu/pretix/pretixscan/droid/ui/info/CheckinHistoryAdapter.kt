package eu.pretix.pretixscan.droid.ui.info

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ItemInfoCheckinHistoryBinding
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CheckinHistoryAdapter : ListAdapter<TicketCheckinHistoryEntry, CheckinHistoryAdapter.ViewHolder>(DIFF) {

    private var timeFormat: DateTimeFormatter? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemInfoCheckinHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val format = timeFormat ?: DateTimeFormatter.ofPattern(
            holder.itemView.context.getString(R.string.short_datetime_format_seconds)
        ).also { timeFormat = it }
        holder.bind(getItem(position), format)
    }

    class ViewHolder(private val binding: ItemInfoCheckinHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: TicketCheckinHistoryEntry, timeFormat: DateTimeFormatter) {
            val context = binding.root.context
            val isExit = entry.type == "exit"

            binding.icon.setImageResource(
                if (isExit) R.drawable.ic_exit_orange_24dp else R.drawable.ic_entry_gray_24dp
            )
            binding.icon.setColorFilter(
                ContextCompat.getColor(
                    context,
                    if (isExit) R.color.pretix_brand_orange else R.color.pretix_brand_green
                )
            )
            binding.typeLabel.text = context.getString(
                if (isExit) R.string.info_mode_checkin_type_exit else R.string.info_mode_checkin_type_entry
            )
            binding.listName.text = entry.listName
            binding.timestamp.text = entry.dateTime
                ?.atZoneSameInstant(ZoneId.systemDefault())
                ?.format(timeFormat)
                .orEmpty()
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TicketCheckinHistoryEntry>() {
            override fun areItemsTheSame(old: TicketCheckinHistoryEntry, new: TicketCheckinHistoryEntry) =
                old.listServerId == new.listServerId && old.dateTime == new.dateTime

            override fun areContentsTheSame(old: TicketCheckinHistoryEntry, new: TicketCheckinHistoryEntry) =
                old == new
        }
    }
}
