package eu.pretix.pretixscan.droid.ui


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.pretixscan.droid.databinding.ItemSearchresultBinding


interface SearchResultClickedInterface {
    fun onSearchResultClicked(res: TicketCheckProvider.SearchResult);
}

class SearchListAdapter(private val results: List<TicketCheckProvider.SearchResult>, private val cb: SearchResultClickedInterface) : RecyclerView.Adapter<BindingHolder<ItemSearchresultBinding>>(), View.OnClickListener {
    override fun onBindViewHolder(holder: BindingHolder<ItemSearchresultBinding>,
                                  position: Int) {
        val item = results.get(position)
        holder.binding.res = item
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BindingHolder<ItemSearchresultBinding> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemSearchresultBinding.inflate(inflater, parent, false)
        binding.getRoot().tag = binding
        binding.getRoot().setOnClickListener(this)
        return BindingHolder(binding)
    }

    override fun onClick(v: View) {
        val binding = v.tag as eu.pretix.pretixscan.droid.databinding.ItemSearchresultBinding
        if (binding.res != null) {
            cb.onSearchResultClicked(binding.res!!)
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }
}
