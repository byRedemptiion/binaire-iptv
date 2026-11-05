package com.binaire.iptv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** FILMS et SÉRIES : un titre à la une, des filtres et des rangées. */
class VodActivity : BaseActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        private const val MAX_ROW = 40
        private const val MAX_CATEGORY_ROWS = 40

        /** Infos détaillées déjà récupérées (partagées avec les fiches). */
        val vodInfoCache = HashMap<String, VodInfo>()
        val seriesInfoCache = HashMap<String, SeriesInfo>()
    }

    private lateinit var kind: Kind
    private lateinit var heroBg: ImageView
    private lateinit var heroTag: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroPlot: TextView
    private lateinit var rvChips: RecyclerView
    private lateinit var rvRows: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var search: EditText

    private lateinit var chipAdapter: ChipAdapter
    private lateinit var rowsAdapter: RowsAdapter
    private lateinit var gridAdapter: CardAdapter
    private lateinit var callbacks: CardCallbacks

    private var chipIds: List<String> = emptyList()
    private var heroJob: Job? = null
    private var searchJob: Job? = null
    private var heroKey: String? = null
    private var firstFocusDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectIfNotReady()) return
        kind = if (intent.getStringExtra(EXTRA_KIND) == Kind.SERIES.name) Kind.SERIES else Kind.MOVIE
        setContentView(R.layout.activity_vod)
        applyBackground(findViewById(R.id.root))
        setupHeader(if (kind == Kind.MOVIE) "FILMS" else "SÉRIES")

        heroBg = findViewById(R.id.heroBg)
        heroTag = findViewById(R.id.heroTag)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroPlot = findViewById(R.id.heroPlot)
        rvChips = findViewById(R.id.rvChips)
        rvRows = findViewById(R.id.rvRows)
        tvEmpty = findViewById(R.id.tvEmpty)
        search = findViewById(R.id.headerSearch)
        if (isShortScreen) {
            // Téléphone : toute la hauteur pour les rangées.
            findViewById<View>(R.id.heroBox).visibility = View.GONE
            heroBg.layoutParams.height = dp(140f)
        }

        search.visibility = View.VISIBLE
        search.hint = if (kind == Kind.MOVIE) "Rechercher un film" else "Rechercher une série"
        search.background = BTheme.selector(this, 19f, BTheme.PANEL, BTheme.PANEL, accent, null, 2f)
        search.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(300)
                refresh()
            }
        }

        callbacks = CardCallbacks(
            onFocus = { card -> card.item?.let { scheduleHero(it, card) } },
            onClick = ::open,
            onLongClick = ::toggleList
        )
        rowsAdapter = RowsAdapter(accent, callbacks)
        gridAdapter = CardAdapter(false, accent, callbacks)

        chipAdapter = ChipAdapter(accent) { i ->
            if (i >= 0) {
                chipAdapter.select(i)
                refresh()
            }
        }
        rvChips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvChips.adapter = chipAdapter
        rvChips.itemAnimator = null

        val cats = Session.cats(kind)
        val counts = Session.items(kind).groupingBy { it.categoryId }.eachCount()
        val usedCats = cats.filter { (counts[it.id] ?: 0) > 0 }
        chipIds = listOf("__home", "__list") + usedCats.map { it.id }
        chipAdapter.submit(listOf("Accueil", "Ma liste") + usedCats.map { it.name }, 0)

        rvRows.itemAnimator = null
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Retour d'une fiche ou du lecteur : « Reprendre » et « Ma liste » ont pu changer.
        if (::rowsAdapter.isInitialized && firstFocusDone) refresh(keepFocus = true)
    }

    private fun currentChip(): String = chipIds.getOrElse(chipAdapter.selected) { "__home" }

    private fun refresh(keepFocus: Boolean = false) {
        val all = Session.items(kind)
        val q = search.text.toString().trim()
        val chip = currentChip()

        if (q.isNotEmpty() || chip != "__home") {
            val list = when {
                q.isNotEmpty() -> all.filter { it.name.contains(q, ignoreCase = true) }
                chip == "__list" -> {
                    val ids = watch.myListIds(kind)
                    all.filter { it.id in ids }
                }
                else -> all.filter { it.categoryId == chip }
            }
            showGrid(list.map(::cardFor))
            if (list.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = if (chip == "__list" && q.isEmpty())
                    "Ta liste est vide.\nMaintiens OK sur une affiche pour l'ajouter."
                else "Aucun résultat"
            } else tvEmpty.visibility = View.GONE
            list.firstOrNull()?.let { if (!keepFocus) scheduleHero(it, null, 0) }
            return
        }

        tvEmpty.visibility = View.GONE
        val rows = buildHomeRows(all)
        if (rvRows.adapter !== rowsAdapter) {
            rvRows.layoutManager = LinearLayoutManager(this)
            rvRows.adapter = rowsAdapter
        }
        rowsAdapter.submit(rows)
        if (!keepFocus) {
            val featured = rows.firstOrNull { !it.wide }?.cards?.firstOrNull()?.item ?: all.firstOrNull()
            if (featured != null) scheduleHero(featured, null, 0)
        }
        if (!firstFocusDone) {
            firstFocusDone = true
            rvRows.post {
                val row = rvRows.findViewHolderForAdapterPosition(0) as? RowsAdapter.VH
                if (row != null) {
                    val list = row.list
                    list.post { list.getChildAt(0)?.findViewById<View>(R.id.card)?.requestFocus() }
                }
            }
        }
    }

    private fun showGrid(cards: List<Card>) {
        if (rvRows.adapter !== gridAdapter) {
            val dm = resources.displayMetrics
            val span = ((dm.widthPixels - dp(64f)) / dp(122f)).coerceIn(3, 10)
            rvRows.layoutManager = GridLayoutManager(this, span)
            rvRows.adapter = gridAdapter
        }
        gridAdapter.submit(cards)
        rvRows.scrollToPosition(0)
    }

    private fun buildHomeRows(all: List<Item>): List<Row> {
        val rows = mutableListOf<Row>()
        val byId = all.associateBy { it.id }

        if (kind == Kind.MOVIE) {
            val resumes = watch.movieResumes().filter { it.progress < 0.95f }
            if (resumes.isNotEmpty()) rows.add(Row("Reprendre", resumes.mapNotNull { r ->
                val item = byId[r.meta.contentId] ?: return@mapNotNull null
                Card(
                    key = "resume:" + r.meta.key, item = item, title = item.name, image = item.icon,
                    sub = "Reste ${formatMinutes(r.remainingMin)}", progress = r.progress, payload = r
                )
            }, wide = true))
        } else {
            val resumes = watch.episodeResumes()
            if (resumes.isNotEmpty()) rows.add(Row("Épisodes à suivre", resumes.mapNotNull { r ->
                val item = byId[r.meta.contentId] ?: return@mapNotNull null
                val label = "S${r.meta.season ?: "?"} · É${r.meta.episode ?: "?"}"
                Card(
                    key = "resume:" + r.meta.key, item = item, title = item.name, image = item.icon,
                    sub = if (r.progress >= 0.95f) "$label vu · épisode suivant" else "$label · reste ${formatMinutes(r.remainingMin)}",
                    progress = r.progress, payload = r
                )
            }, wide = true))
        }

        val myList = watch.myListIds(kind)
        if (myList.isNotEmpty()) rows.add(Row("Ma liste", all.filter { it.id in myList }.take(MAX_ROW).map(::cardFor)))

        val recent = all.filter { it.added > 0 }.sortedByDescending { it.added }.take(MAX_ROW)
        if (recent.isNotEmpty()) rows.add(Row("Ajoutés récemment", recent.map(::cardFor)))

        val top = all.filter { (it.rating ?: 0.0) > 0 }.sortedByDescending { it.rating }.take(MAX_ROW)
        if (top.size >= 5) rows.add(Row("Les mieux notés", top.map(::cardFor)))

        val byCat = all.groupBy { it.categoryId }
        for (c in Session.cats(kind).take(MAX_CATEGORY_ROWS)) {
            val items = byCat[c.id] ?: continue
            rows.add(Row(c.name, items.take(MAX_ROW).map(::cardFor)))
        }
        return rows
    }

    private fun cardFor(item: Item) = Card(key = item.id, item = item, title = item.name, image = item.icon)

    private fun formatMinutes(min: Long): String = if (min >= 60) "${min / 60} h ${String.format(Locale.ROOT, "%02d", min % 60)}" else "$min min"

    // ---------- À la une ----------
    private fun scheduleHero(item: Item, card: Card?, delayMs: Long = 450) {
        heroJob?.cancel()
        heroJob = lifecycleScope.launch {
            delay(delayMs)
            showHero(item, card)
        }
    }

    private suspend fun showHero(item: Item, card: Card?) {
        val key = item.kind.name + item.id
        val changed = heroKey != key
        heroKey = key
        heroTag.text = when {
            card?.payload is WatchStore.Resume -> "REPRENDRE"
            item.added > 0 && System.currentTimeMillis() / 1000 - item.added < 14 * 86400 -> "NOUVEAU"
            else -> if (kind == Kind.MOVIE) "FILM" else "SÉRIE"
        }
        heroTitle.text = item.name
        if (changed) {
            heroMeta.text = item.rating?.let { "★ " + formatRating(it) }.orEmpty()
            heroPlot.text = ""
        }

        try {
            if (kind == Kind.MOVIE) {
                val info = vodInfoCache[item.id] ?: withContext(Dispatchers.IO) { client.vodInfo(item.id) }.also { vodInfoCache[item.id] = it }
                if (heroKey != key) return
                heroMeta.text = listOfNotNull(info.year, info.duration?.let(::shortDuration), info.genre?.let(::firstGenres),
                    (info.rating ?: item.rating)?.let { "★ " + formatRating(it) }).joinToString("   ·   ")
                heroPlot.text = info.plot.orEmpty()
                loadBackdrop(info.backdrop ?: info.poster ?: item.icon, changed)
            } else {
                val info = seriesInfoCache[item.id] ?: withContext(Dispatchers.IO) { client.seriesInfo(item.id) }.also { seriesInfoCache[item.id] = it }
                if (heroKey != key) return
                val seasons = info.seasons.size
                heroMeta.text = listOfNotNull(info.year, if (seasons > 0) "$seasons saison" + (if (seasons > 1) "s" else "") else null,
                    info.genre?.let(::firstGenres), (info.rating ?: item.rating)?.let { "★ " + formatRating(it) }).joinToString("   ·   ")
                heroPlot.text = info.plot.orEmpty()
                loadBackdrop(info.backdrop ?: info.cover ?: item.icon, changed)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (heroKey == key) loadBackdrop(item.icon, changed)
        }
    }

    private fun loadBackdrop(url: String?, changed: Boolean) {
        if (!changed && heroBg.drawable != null) return
        heroBg.load(url) { crossfade(300) }
    }

    // ---------- Actions ----------
    private fun open(card: Card) {
        val item = card.item ?: return
        if (kind == Kind.MOVIE) {
            startActivity(Intent(this, MovieDetailActivity::class.java).putExtra(MovieDetailActivity.EXTRA_ID, item.id))
        } else {
            startActivity(Intent(this, SeriesDetailActivity::class.java).putExtra(SeriesDetailActivity.EXTRA_ID, item.id))
        }
    }

    private fun toggleList(card: Card) {
        val item = card.item ?: return
        val added = watch.toggleMyList(kind, item.id)
        toast(if (added) "${item.name} ajouté à ta liste" else "${item.name} retiré de ta liste")
        if (currentChip() == "__list") refresh(keepFocus = true)
    }
}

internal fun formatRating(r: Double): String {
    val v = if (r > 10) r / 10 else r
    return String.format(Locale.FRANCE, "%.1f", v)
}

internal fun firstGenres(g: String): String =
    g.split(',', '/', '|').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", ")

/** « 01:52:10 » ou « 112 min » → « 1 h 52 ». */
internal fun shortDuration(d: String): String {
    val parts = d.split(":").mapNotNull { it.trim().toIntOrNull() }
    val minutes = when {
        parts.size >= 2 -> parts[0] * 60 + parts[1]
        else -> d.filter(Char::isDigit).toIntOrNull() ?: return d
    }
    return if (minutes >= 60) "${minutes / 60} h ${String.format(Locale.ROOT, "%02d", minutes % 60)}" else "$minutes min"
}
