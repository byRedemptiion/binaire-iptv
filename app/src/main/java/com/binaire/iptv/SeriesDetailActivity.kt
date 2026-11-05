package com.binaire.iptv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fiche d'une série : Reprendre, saisons et épisodes. */
class SeriesDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var item: Item
    private var info: SeriesInfo? = null
    private var season: String? = null

    private lateinit var btnPlay: Button
    private lateinit var btnList: Button
    private lateinit var plot: TextView
    private lateinit var rvSeasons: RecyclerView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var seasonAdapter: ChipAdapter
    private lateinit var episodeAdapter: CardAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectIfNotReady()) return
        val id = intent.getStringExtra(EXTRA_ID)
        val found = Session.items(Kind.SERIES).firstOrNull { it.id == id }
        if (found == null) {
            finish()
            return
        }
        item = found
        setContentView(R.layout.activity_detail)
        applyBackground(findViewById(R.id.root))
        setupHeader("SÉRIES")

        val backdrop: ImageView = findViewById(R.id.backdrop)
        val title: TextView = findViewById(R.id.title)
        if (isShortScreen) title.textSize = 24f
        val meta: TextView = findViewById(R.id.meta)
        val loading: View = findViewById(R.id.loading)
        plot = findViewById(R.id.plot)
        plot.maxLines = 3
        btnPlay = findViewById(R.id.btnPlay)
        btnList = findViewById(R.id.btnList)
        rvSeasons = findViewById(R.id.rvSeasons)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        // Laisse plus de place aux épisodes.
        findViewById<View>(R.id.topSpace).layoutParams =
            (findViewById<View>(R.id.topSpace).layoutParams as android.widget.LinearLayout.LayoutParams).apply { weight = 0.35f }

        styleButtons(this, accent, btnPlay, listOf(btnList))
        title.text = item.name
        meta.text = item.rating?.let { "★ " + formatRating(it) }.orEmpty()
        backdrop.load(item.icon) { crossfade(true) }
        btnPlay.text = "Chargement…"
        btnPlay.isEnabled = false

        btnList.setOnClickListener {
            val added = watch.toggleMyList(Kind.SERIES, item.id)
            toast(if (added) "Ajoutée à ta liste" else "Retirée de ta liste")
            updateListButton()
        }
        updateListButton()

        seasonAdapter = ChipAdapter(accent) { i ->
            val s = info?.seasons?.getOrNull(i)
            if (s != null) {
                seasonAdapter.select(i)
                showSeason(s)
            }
        }
        rvSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvSeasons.adapter = seasonAdapter
        rvSeasons.itemAnimator = null

        episodeAdapter = CardAdapter(true, accent, CardCallbacks(
            onFocus = {},
            onClick = { card -> (card.payload as? Int)?.let { idx -> season?.let { play(it, idx) } } },
            onLongClick = {}
        ))
        rvEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvEpisodes.adapter = episodeAdapter
        rvEpisodes.itemAnimator = null

        btnList.requestFocus()

        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            try {
                val i = VodActivity.seriesInfoCache[item.id]
                    ?: withContext(Dispatchers.IO) { client.seriesInfo(item.id) }.also { VodActivity.seriesInfoCache[item.id] = it }
                info = i
                val n = i.seasons.size
                meta.text = listOfNotNull(
                    i.year, if (n > 0) "$n saison" + (if (n > 1) "s" else "") else null,
                    i.genre?.let(::firstGenres), (i.rating ?: item.rating)?.let { "★ " + formatRating(it) }
                ).joinToString("   ·   ")
                plot.text = i.plot.orEmpty()
                val img = i.backdrop ?: i.cover
                if (img != null) backdrop.load(img) { crossfade(true) }

                if (i.seasons.isEmpty()) {
                    btnPlay.text = "Aucun épisode"
                } else {
                    rvSeasons.visibility = View.VISIBLE
                    rvEpisodes.visibility = View.VISIBLE
                    val target = nextTarget()
                    val startSeason = target?.first ?: i.seasons.first()
                    seasonAdapter.submit(i.seasons.map { "Saison $it" }, i.seasons.indexOf(startSeason).coerceAtLeast(0))
                    showSeason(startSeason)
                    updatePlayButton()
                    btnPlay.isEnabled = true
                    btnPlay.requestFocus()
                    target?.let { (_, idx) -> rvEpisodes.scrollToPosition(idx) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                btnPlay.text = "Indisponible"
                plot.text = "Impossible de charger la série : ${e.message}"
            }
            loading.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (info != null) {
            season?.let { showSeason(it) }
            updatePlayButton()
        }
    }

    /** Épisode à lancer : celui en cours, ou le suivant s'il a été vu en entier. */
    private fun nextTarget(): Pair<String, Int>? {
        val i = info ?: return null
        val last = watch.lastEpisodeOf(item.id)
        if (last == null) {
            val s = i.seasons.firstOrNull() ?: return null
            return s to 0
        }
        for (s in i.seasons) {
            val eps = i.episodes[s].orEmpty()
            val idx = eps.indexOfFirst { it.id == last.meta.episodeId }
            if (idx >= 0) {
                if (last.progress < 0.95f) return s to idx
                if (idx + 1 < eps.size) return s to idx + 1
                val nextSeason = i.seasons.getOrNull(i.seasons.indexOf(s) + 1) ?: return s to idx
                return nextSeason to 0
            }
        }
        return i.seasons.first() to 0
    }

    private fun updatePlayButton() {
        val i = info ?: return
        val target = nextTarget() ?: return
        val ep = i.episodes[target.first]?.getOrNull(target.second) ?: return
        val last = watch.lastEpisodeOf(item.id)
        val label = "S${target.first} · É${ep.number.ifEmpty { (target.second + 1).toString() }}"
        btnPlay.text = when {
            last == null -> "Lecture $label"
            last.meta.episodeId == ep.id -> "Reprendre $label"
            else -> "Épisode suivant $label"
        }
        btnPlay.setOnClickListener { play(target.first, target.second) }
    }

    private fun updateListButton() {
        btnList.text = if (watch.inMyList(Kind.SERIES, item.id)) "✓ Dans ma liste" else "+ Ma liste"
    }

    private fun showSeason(s: String) {
        val i = info ?: return
        season = s
        val last = watch.lastEpisodeOf(item.id)
        val eps = i.episodes[s].orEmpty()
        episodeAdapter.submit(eps.mapIndexed { idx, e ->
            val isCurrent = last?.meta?.episodeId == e.id
            val minutes = if (e.durationSecs > 0) "${e.durationSecs / 60} min" else null
            Card(
                key = e.id,
                item = null,
                title = "É${e.number.ifEmpty { (idx + 1).toString() }} · ${e.title}",
                image = e.image ?: i.cover ?: item.icon,
                sub = if (isCurrent && last != null && last.progress < 0.95f) "Reste ${last.remainingMin} min" else minutes,
                progress = when {
                    watch.isWatched(e.id) -> 1f
                    isCurrent && last != null -> last.progress
                    else -> -1f
                },
                watched = watch.isWatched(e.id),
                payload = idx
            )
        })
    }

    private fun play(s: String, index: Int) {
        val i = info ?: return
        val eps = i.episodes[s].orEmpty()
        if (index !in eps.indices) return
        Session.playlist = eps.map { e ->
            PlayItem(
                title = "${item.name} — S$s · É${e.number}  ${e.title}",
                url = client.episodeUrl(e.id, e.ext),
                isLive = false,
                watch = WatchMeta(
                    key = "ep:${e.id}", kind = "episode", title = item.name, image = item.icon,
                    contentId = item.id, episodeId = e.id, season = s, episode = e.number, ext = e.ext
                )
            )
        }
        Session.playIndex = index
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
