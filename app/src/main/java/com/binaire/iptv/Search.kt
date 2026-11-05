package com.binaire.iptv

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.text.Normalizer
import java.util.Locale

/** Recherche tolérante : accents, majuscules, ponctuation et petites fautes de frappe. */
object SearchIndex {
    private val marks = Regex("""\p{Mn}+""")
    private val nonAlnum = Regex("""[^a-z0-9]+""")

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")
            .lowercase(Locale.ROOT).replace(nonAlnum, " ").trim()

    private fun lev1(a: String, b: String): Boolean {
        // Vrai si a et b diffèrent d'au plus une lettre (ajout, retrait ou remplacement).
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /** Score de pertinence (0 = aucun rapport). */
    fun score(q: String, name: String): Int {
        if (q.isEmpty()) return 0
        if (name == q) return 100
        if (name.startsWith(q)) return 80
        val words = name.split(' ')
        if (words.any { it.startsWith(q) }) return 60
        if (name.contains(q)) return 45
        val compactQ = q.replace(" ", "")
        if (compactQ.length >= 3 && name.replace(" ", "").contains(compactQ)) return 40
        if (q.length >= 4) {
            val first = q.substringBefore(' ')
            if (words.any { w -> w.length >= first.length - 1 && lev1(first, w.take(first.length)) }) return 15
        }
        return 0
    }
}

/**
 * Suggestions sous la barre de recherche, limitées à la rubrique (TV, Films ou Séries).
 * La liste s'ouvre dès la première lettre ; « Bas » sur la télécommande descend dans les suggestions.
 */
class SuggestController(
    private val accent: Int,
    private val kind: Kind,
    private val edit: EditText,
    private val box: View,
    private val title: TextView,
    private val list: RecyclerView,
    private val items: () -> List<Item>,
    private val onPick: (Item) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var indexOf: List<Item>? = null
    private var index: List<Pair<Item, String>> = emptyList()
    private val adapter = SuggestAdapter()

    private val noun = when (kind) {
        Kind.LIVE -> "chaîne"
        Kind.MOVIE -> "film"
        Kind.SERIES -> "série"
    }

    private val update = Runnable { compute() }

    init {
        list.layoutManager = LinearLayoutManager(list.context)
        list.adapter = adapter
        list.itemAnimator = null
        box.visibility = View.GONE
        edit.doAfterTextChanged {
            handler.removeCallbacks(update)
            handler.postDelayed(update, 150)
        }
        edit.setOnFocusChangeListener { _, has ->
            if (has && edit.text.isNotBlank()) compute()
            else if (!has) handler.postDelayed({ hideIfUnfocused() }, 100)
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                box.visibility = View.GONE
                false
            } else false
        }
        edit.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && box.visibility == View.VISIBLE) {
                list.getChildAt(0)?.requestFocus() ?: return@setOnKeyListener false
                true
            } else false
        }
    }

    private fun hideIfUnfocused() {
        if (!edit.hasFocus() && !list.hasFocus()) box.visibility = View.GONE
    }

    private fun ensureIndex() {
        val current = items()
        if (current !== indexOf) {
            indexOf = current
            index = current.map { it to SearchIndex.norm(it.name) }
        }
    }

    private fun compute() {
        val raw = edit.text.toString().trim()
        val q = SearchIndex.norm(raw)
        if (q.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        ensureIndex()
        val results = index.asSequence()
            .map { (item, n) -> Triple(item, n, SearchIndex.score(q, n)) }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<Item, String, Int>> { it.third }.thenBy { it.second.length })
            .take(8)
            .map { it.first }
            .toList()
        val none = if (kind == Kind.MOVIE) "Aucun film" else "Aucune $noun"
        title.text = if (results.isEmpty()) "$none « $raw » dans ton abonnement" else "Suggestions"
        adapter.submit(results)
        box.visibility = View.VISIBLE
    }

    private inner class SuggestAdapter : RecyclerView.Adapter<SuggestAdapter.VH>() {
        var rows: List<Item> = emptyList()

        fun submit(r: List<Item>) {
            rows = r
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.image)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_suggest, parent, false)
            v.background = BTheme.selector(parent.context, 10f, 0, accent, BTheme.WHITE, null, 2f)
            return VH(v)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = rows[position]
            h.name.text = item.name
            val type = when (kind) {
                Kind.LIVE -> "Chaîne"
                Kind.MOVIE -> "Film"
                Kind.SERIES -> "Série"
            }
            h.sub.text = listOfNotNull(type, item.year, item.quality, item.rating?.let { "★ " + formatRating(it) }).joinToString(" · ")
            h.image.load(item.icon) {
                placeholder(R.drawable.ic_poster)
                error(R.drawable.ic_poster)
                fallback(R.drawable.ic_poster)
            }
            h.itemView.setOnClickListener {
                box.visibility = View.GONE
                onPick(item)
            }
            h.itemView.setOnKeyListener { _, keyCode, event ->
                // « Haut » depuis la première suggestion revient dans la barre de recherche.
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && h.bindingAdapterPosition == 0) {
                    edit.requestFocus(); true
                } else false
            }
            h.itemView.setOnFocusChangeListener { _, has -> if (!has) handler.postDelayed({ hideIfUnfocused() }, 100) }
        }
    }
}
