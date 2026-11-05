package com.binaire.iptv

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class CatRow(val id: String, val name: String, val count: Int)

/** Colonne des catégories (avec nombre de chaînes). */
class CatAdapter(
    private val accent: Int,
    private val onFocus: (CatRow) -> Unit,
    private val onClick: (CatRow) -> Unit
) : RecyclerView.Adapter<CatAdapter.VH>() {

    var rows: List<CatRow> = emptyList()
        private set
    var selectedId: String? = null
        private set

    fun submit(list: List<CatRow>, selected: String?) {
        rows = list
        selectedId = selected
        notifyDataSetChanged()
    }

    fun select(id: String) {
        val old = rows.indexOfFirst { it.id == selectedId }
        selectedId = id
        val now = rows.indexOfFirst { it.id == id }
        if (old >= 0) notifyItemChanged(old)
        if (now >= 0) notifyItemChanged(now)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cat, parent, false)
        v.background = BTheme.selector(parent.context, 10f, 0, accent, BTheme.WHITE, BTheme.WHITE12)
        return VH(v)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.name.text = row.name
        h.count.text = row.count.toString()
        val sel = row.id == selectedId
        h.itemView.isSelected = sel
        h.name.paint.isFakeBoldText = sel
        h.itemView.setOnFocusChangeListener { _, has -> if (has) onFocus(row) }
        h.itemView.setOnClickListener { onClick(row) }
    }
}

/** Liste des chaînes, avec le programme en cours chargé à l'affichage. */
class ChannelAdapter(
    private val scope: CoroutineScope,
    private val accent: Int,
    private val isFavorite: (String) -> Boolean,
    private val onFocus: (Item) -> Unit,
    private val onClick: (Item) -> Unit,
    private val onLongClick: (Item) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Item> = emptyList()
        private set

    fun submit(list: List<Item>) {
        items = list
        notifyDataSetChanged()
    }

    fun indexOf(id: String) = items.indexOfFirst { it.id == id }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val star: ImageView = v.findViewById(R.id.star)
        var boundId: String? = null
        var epgJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        v.background = BTheme.selector(parent.context, 10f, 0, accent, BTheme.WHITE)
        val h = VH(v)
        h.star.imageTintList = ColorStateList.valueOf(accent)
        return h
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.boundId = item.id
        h.num.text = item.num.orEmpty()
        h.name.text = item.name
        h.star.visibility = if (isFavorite(item.id)) View.VISIBLE else View.GONE
        h.logo.load(item.icon) {
            placeholder(R.drawable.ic_tile_tv)
            error(R.drawable.ic_tile_tv)
            fallback(R.drawable.ic_tile_tv)
        }

        h.epgJob?.cancel()
        val cached = EpgCache.peek(item.id)
        if (cached != null) {
            h.now.text = cached.firstOrNull()?.title.orEmpty()
        } else {
            h.now.text = ""
            val c = Session.client
            if (c != null) {
                h.epgJob = scope.launch {
                    val epg = EpgCache.get(c, item.id)
                    if (h.boundId == item.id) h.now.text = epg.firstOrNull()?.title.orEmpty()
                }
            }
        }

        h.itemView.setOnFocusChangeListener { _, has ->
            h.now.setTextColor(if (has) BTheme.WHITE else 0xFF9AA3B2.toInt())
            h.star.imageTintList = ColorStateList.valueOf(if (has) BTheme.WHITE else accent)
            if (has) onFocus(item)
        }
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    override fun onViewRecycled(h: VH) {
        h.epgJob?.cancel()
        h.boundId = null
    }
}
