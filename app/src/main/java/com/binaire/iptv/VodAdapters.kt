package com.binaire.iptv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation

/** Une vignette : film, série, épisode à reprendre… */
data class Card(
    val key: String,
    val item: Item?,
    val title: String,
    val image: String?,
    val sub: String? = null,
    val progress: Float = -1f,
    val watched: Boolean = false,
    val payload: Any? = null
)

data class Row(val title: String, val cards: List<Card>, val wide: Boolean = false)

class CardCallbacks(
    val onFocus: (Card) -> Unit,
    val onClick: (Card) -> Unit,
    val onLongClick: (Card) -> Unit
)

internal fun focusRing(ctx: Context): StateListDrawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), BTheme.rounded(ctx, 12f, 0, BTheme.WHITE, 3f))
    addState(intArrayOf(), BTheme.rounded(ctx, 12f, 0))
}

class CardAdapter(
    private val wide: Boolean,
    private val accent: Int,
    private val cb: CardCallbacks
) : RecyclerView.Adapter<CardAdapter.VH>() {

    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = cards[position].key.hashCode().toLong()

    var cards: List<Card> = emptyList()
        private set

    fun submit(list: List<Card>) {
        cards = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: FrameLayout = v.findViewById(R.id.card)
        val image: ImageView = v.findViewById(R.id.image)
        val placeholder: TextView = v.findViewById(R.id.placeholderTitle)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView? = v.findViewById(R.id.sub)
        val progress: ProgressBar? = v.findViewById(R.id.progress)
        val check: ImageView? = v.findViewById(R.id.check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val v = LayoutInflater.from(ctx).inflate(if (wide) R.layout.item_wide else R.layout.item_poster, parent, false)
        val h = VH(v)
        h.card.foreground = focusRing(ctx)
        h.image.background = BTheme.rounded(ctx, 10f, 0xFF1A1E29.toInt())
        h.progress?.progressTintList = ColorStateList.valueOf(accent)
        return h
    }

    override fun getItemCount() = cards.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = cards[position]
        h.title.text = c.title
        h.placeholder.text = c.title
        h.placeholder.visibility = if (c.image.isNullOrBlank()) View.VISIBLE else View.GONE
        val radius = BTheme.dp(h.itemView.context, 10f).toFloat()
        h.image.load(c.image) {
            crossfade(true)
            transformations(RoundedCornersTransformation(radius))
            listener(
                onError = { _, _ -> h.placeholder.visibility = View.VISIBLE },
                onSuccess = { _, _ -> h.placeholder.visibility = View.GONE }
            )
        }
        h.sub?.text = c.sub.orEmpty()
        h.sub?.visibility = if (c.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        h.progress?.let {
            it.visibility = if (c.progress >= 0f) View.VISIBLE else View.INVISIBLE
            it.progress = (c.progress.coerceIn(0f, 1f) * 1000).toInt()
        }
        h.check?.visibility = if (c.watched) View.VISIBLE else View.GONE

        h.card.setOnFocusChangeListener { v, has ->
            BTheme.zoom(v, has, 1.07f)
            if (has) cb.onFocus(c)
        }
        h.card.setOnClickListener { cb.onClick(c) }
        h.card.setOnLongClickListener { cb.onLongClick(c); true }
    }
}

/** Rangées horizontales (« Reprendre », « Ajoutés récemment », un genre…). */
class RowsAdapter(
    private val accent: Int,
    private val cb: CardCallbacks
) : RecyclerView.Adapter<RowsAdapter.VH>() {

    private val pool = RecyclerView.RecycledViewPool()

    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = rows[position].title.hashCode().toLong() * 31 + position
    var rows: List<Row> = emptyList()
        private set

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (rows[position].wide) 1 else 0

    inner class VH(v: View, wide: Boolean) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val adapter = CardAdapter(wide, accent, cb)

        init {
            list.layoutManager = LinearLayoutManager(v.context, LinearLayoutManager.HORIZONTAL, false)
            list.adapter = adapter
            list.itemAnimator = null
            if (!wide) list.setRecycledViewPool(pool)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false), viewType == 1)

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        h.title.text = r.title
        h.adapter.submit(r.cards)
        h.list.scrollToPosition(0)
    }
}

/** Filtres en haut (Accueil, Ma liste, catégories). */
class ChipAdapter(
    private val accent: Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ChipAdapter.VH>() {

    var labels: List<String> = emptyList()
        private set
    var selected = 0
        private set

    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = position.toLong()

    fun submit(list: List<String>, sel: Int) {
        labels = list
        selected = sel
        notifyDataSetChanged()
    }

    fun select(i: Int) {
        val old = selected
        selected = i
        notifyItemChanged(old)
        notifyItemChanged(i)
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_chip, parent, false) as TextView
        tv.background = BTheme.selector(parent.context, 16f, BTheme.PANEL, accent, BTheme.WHITE, BTheme.WHITE)
        return VH(tv)
    }

    override fun getItemCount() = labels.size

    override fun onBindViewHolder(h: VH, position: Int) {
        h.text.text = labels[position]
        val sel = position == selected
        h.text.isSelected = sel
        h.text.setOnFocusChangeListener { v, has ->
            (v as TextView).setTextColor(
                when {
                    has -> BTheme.WHITE
                    v.isSelected -> 0xFF070912.toInt()
                    else -> 0xFFE2E7EE.toInt()
                }
            )
        }
        h.text.setTextColor(if (sel && !h.text.hasFocus()) 0xFF070912.toInt() else 0xFFE2E7EE.toInt())
        h.text.paint.isFakeBoldText = sel
        h.text.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}
