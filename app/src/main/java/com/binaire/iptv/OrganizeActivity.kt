package com.binaire.iptv

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Organiser mes catégories : masquer, renommer, réordonner (TV, Films, Séries). */
class OrganizeActivity : BaseActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
    }

    private val kinds = listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)
    private var kind = Kind.LIVE
    private lateinit var rvCats: RecyclerView
    private lateinit var catAdapter: OrgAdapter
    private lateinit var kindAdapter: ChipAdapter
    private var rows: List<Library.CatInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectIfNotReady()) return
        kind = runCatching { Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "LIVE") }.getOrDefault(Kind.LIVE)
        setContentView(R.layout.activity_organize)
        applyBackground(findViewById(R.id.root))
        setupHeader("ORGANISER")

        kindAdapter = ChipAdapter(accent) { i ->
            if (i >= 0) {
                kindAdapter.select(i)
                kind = kinds[i]
                load()
            }
        }
        val rvKinds: RecyclerView = findViewById(R.id.rvKinds)
        rvKinds.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvKinds.adapter = kindAdapter
        rvKinds.itemAnimator = null
        kindAdapter.submit(listOf("TV", "Films", "Séries"), kinds.indexOf(kind))

        rvCats = findViewById(R.id.rvCats)
        catAdapter = OrgAdapter()
        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter
        rvCats.itemAnimator = null

        val btnReset: Button = findViewById(R.id.btnReset)
        btnReset.background = BTheme.selector(this, 17f, BTheme.WHITE18, accent, BTheme.WHITE, null, 2f)
        btnReset.setTextColor(BTheme.WHITE)
        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Tout réinitialiser ?")
                .setMessage("Les catégories ${label(kind)} retrouvent leur nom, leur ordre et sont toutes affichées.")
                .setPositiveButton("Réinitialiser") { _, _ ->
                    prefs.resetOrganize(kind)
                    apply(null)
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        load()
        rvCats.post { rvCats.getChildAt(0)?.requestFocus() }
    }

    private fun label(k: Kind) = when (k) {
        Kind.LIVE -> "TV"
        Kind.MOVIE -> "Films"
        Kind.SERIES -> "Séries"
    }

    /** Catégories des pays choisis (les autres pays sont gérés dans « Pays affichés »). */
    private fun load() {
        val allowed = prefs.allowedCountries
        rows = Session.catInfo[kind].orEmpty().filter { allowed.isEmpty() || it.country in allowed }
        catAdapter.notifyDataSetChanged()
    }

    /** Reconstruit la liste puis replace le focus sur la catégorie modifiée. */
    private fun apply(focusKey: String?) {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { Library.rebuildKind(prefs, kind) }
            load()
            val i = rows.indexOfFirst { it.key == focusKey }
            if (i >= 0) {
                rvCats.scrollToPosition(i)
                rvCats.post { rvCats.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
            }
        }
    }

    private fun actions(c: Library.CatInfo, position: Int) {
        val hiddenByAdult = c.adult && prefs.hideAdult
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add((if (c.hiddenByUser) "Afficher" else "Masquer") to {
            prefs.setCatHidden(kind, c.key, !c.hiddenByUser)
            if (hiddenByAdult && c.hiddenByUser) toast("Catégorie adulte : désactive « Masquer les catégories adultes » pour la voir")
            apply(c.key)
        })
        items.add("Renommer…" to { rename(c) })
        if (position > 0) {
            items.add("Monter" to { move(c, position - 1) })
            items.add("Mettre en premier" to { move(c, 0) })
        }
        if (position < rows.size - 1) items.add("Descendre" to { move(c, position + 1) })
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun rename(c: Library.CatInfo) {
        val pad = dp(20f)
        val input = EditText(this).apply {
            setText(c.name)
            setSelection(c.name.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply {
            setPadding(pad, dp(8f), pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Renommer la catégorie")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                prefs.setCatRename(kind, c.key, input.text.toString())
                apply(c.key)
            }
            .setNeutralButton("Nom d'origine") { _, _ ->
                prefs.setCatRename(kind, c.key, null)
                apply(c.key)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun move(c: Library.CatInfo, to: Int) {
        // L'ordre enregistré porte sur toutes les catégories du type (y compris les autres pays).
        val keys = rows.map { it.key }.toMutableList()
        val from = keys.indexOf(c.key)
        if (from < 0) return
        keys.removeAt(from)
        keys.add(to.coerceIn(0, keys.size), c.key)
        val others = Session.catInfo[kind].orEmpty().map { it.key }.filter { it !in keys }
        prefs.setCatOrder(kind, keys + others)
        apply(c.key)
    }

    private inner class OrgAdapter : RecyclerView.Adapter<OrgAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val pos: TextView = v.findViewById(R.id.pos)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val state: TextView = v.findViewById(R.id.state)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_organize, parent, false)
            v.background = BTheme.selector(this@OrganizeActivity, 10f, 0, accent, BTheme.WHITE)
            return VH(v)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = rows[position]
            h.pos.text = (position + 1).toString()
            h.name.text = c.name
            val origin = c.rawNames.distinct().take(3).joinToString("  ·  ") + if (c.rawNames.size > 3) "  …" else ""
            h.sub.text = "${c.count} · $origin"
            val shown = c.visible
            h.name.alpha = if (shown) 1f else 0.45f
            h.state.text = when {
                c.hiddenByUser -> "Masquée"
                c.adult && prefs.hideAdult -> "Adulte · masquée"
                c.name != c.cleanName -> "Renommée"
                else -> ""
            }
            h.itemView.setOnClickListener { actions(c, h.bindingAdapterPosition) }
        }
    }
}
