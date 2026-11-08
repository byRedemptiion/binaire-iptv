package com.binaire.iptv

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** TV : catégories, chaînes, aperçu en direct et programme en cours. */
@OptIn(UnstableApi::class)
class LiveActivity : BaseActivity() {

    companion object {
        private const val FAV = "__fav"
        private const val ALL = "__all"
    }

    private lateinit var rvCats: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var search: EditText
    private lateinit var previewView: PlayerView
    private lateinit var previewHint: TextView
    private lateinit var liveBadge: TextView
    private lateinit var epgTime: TextView
    private lateinit var epgTitle: TextView
    private lateinit var epgBar: ProgressBar
    private lateinit var epgDesc: TextView
    private lateinit var epgNext: TextView

    private lateinit var catAdapter: CatAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var favorites: Set<String> = emptySet()
    private var currentCat = ALL
    private var preview: ExoPlayer? = null
    private var previewId: String? = null
    private var previewFallbackTried = false
    /** Un autre appareil du compte lance une lecture : l'aperçu libère l'abonnement. */
    private val previewGuard by lazy {
        PlaybackGuard(this) { name ->
            stopPreview()
            previewHint.visibility = View.VISIBLE
            previewHint.text = "Aperçu coupé : « $name » regarde avec ton abonnement"
            liveBadge.visibility = View.GONE
        }
    }
    private var focusedItem: Item? = null

    private var catJob: Job? = null
    private var focusJob: Job? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectIfNotReady()) return
        setContentView(R.layout.activity_live)
        applyBackground(findViewById(R.id.root))
        setupHeader("TV")

        rvCats = findViewById(R.id.rvCats)
        rvChannels = findViewById(R.id.rvChannels)
        tvEmpty = findViewById(R.id.tvEmpty)
        search = findViewById(R.id.headerSearch)
        previewView = findViewById(R.id.preview)
        previewHint = findViewById(R.id.previewHint)
        liveBadge = findViewById(R.id.liveBadge)
        epgTime = findViewById(R.id.epgTime)
        epgTitle = findViewById(R.id.epgTitle)
        epgBar = findViewById(R.id.epgBar)
        epgDesc = findViewById(R.id.epgDesc)
        epgNext = findViewById(R.id.epgNext)

        liveBadge.background = BTheme.rounded(this, 5f, accent)
        epgBar.progressTintList = ColorStateList.valueOf(accent)
        findViewById<FrameLayout>(R.id.previewBox).foreground = BTheme.rounded(this, 14f, 0, accent, 2.5f)

        search.visibility = View.VISIBLE
        search.hint = "Rechercher une chaîne"
        search.background = BTheme.selector(this, 19f, BTheme.PANEL, BTheme.PANEL, accent, null, 2f)
        search.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(250)
                showChannels()
            }
        }

        favorites = prefs.favorites()
        SuggestController(
            accent, Kind.LIVE, search,
            findViewById(R.id.suggestBox), findViewById(R.id.suggestTitle), findViewById(R.id.suggestList),
            items = { Session.items(Kind.LIVE) },
            onPick = ::openFullscreen
        )

        catAdapter = CatAdapter(
            accent,
            onFocus = { row ->
                catJob?.cancel()
                catJob = lifecycleScope.launch {
                    delay(350)
                    selectCategory(row.id)
                }
            },
            onClick = { row ->
                catJob?.cancel()
                selectCategory(row.id)
                rvChannels.post { rvChannels.getChildAt(0)?.requestFocus() }
            }
        )
        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter
        rvCats.itemAnimator = null

        channelAdapter = ChannelAdapter(
            lifecycleScope, accent,
            isFavorite = ::isFav,
            onFocus = ::onChannelFocused,
            onClick = ::openFullscreen,
            onLongClick = ::toggleFavorite
        )
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = channelAdapter
        rvChannels.itemAnimator = null

        // Par défaut : les favoris s'il y en a, sinon toutes les chaînes.
        currentCat = if (favorites.isNotEmpty()) FAV else ALL
        buildCategories()
        showChannels()
        rvCats.post {
            val i = catAdapter.rows.indexOfFirst { it.id == currentCat }
            rvCats.findViewHolderForAdapterPosition(i.coerceAtLeast(0))?.itemView?.requestFocus()
        }
    }

    private fun buildCategories() {
        val all = Session.items(Kind.LIVE)
        val counts = HashMap<String, Int>()
        for (i in all) {
            if (i.cats.isEmpty()) counts[i.categoryId] = (counts[i.categoryId] ?: 0) + 1
            else for (c in i.cats) counts[c] = (counts[c] ?: 0) + 1
        }
        val rows = mutableListOf(
            CatRow(FAV, "★ Favoris", all.count(::isFav)),
            CatRow(ALL, "Toutes", all.size)
        )
        for (c in Session.cats(Kind.LIVE)) {
            val n = counts[c.id] ?: 0
            if (n > 0) rows.add(CatRow(c.id, c.name, n))
        }
        catAdapter.submit(rows, currentCat)
    }

    private fun selectCategory(id: String) {
        if (id == currentCat) return
        currentCat = id
        catAdapter.select(id)
        showChannels()
    }

    private fun showChannels() {
        val all = Session.items(Kind.LIVE)
        val q = search.text.toString().trim()
        val list = when {
            q.isNotEmpty() -> all.filter { it.name.contains(q, ignoreCase = true) }
            currentCat == FAV -> all.filter(::isFav)
            currentCat == ALL -> all
            else -> all.filter { it.inCat(currentCat) }
        }
        channelAdapter.submit(list)
        rvChannels.scrollToPosition(0)
        if (list.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (currentCat == FAV && q.isEmpty())
                "Aucun favori pour l'instant.\nMaintiens OK sur une chaîne pour l'ajouter."
            else "Aucune chaîne"
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    private fun onChannelFocused(item: Item) {
        focusedItem = item
        focusJob?.cancel()
        epgTitle.text = item.name
        epgTime.text = ""
        epgDesc.text = ""
        epgNext.text = ""
        epgBar.visibility = View.INVISIBLE
        focusJob = lifecycleScope.launch {
            delay(500)
            if (prefs.livePreview) startPreview(item)
            val epg = EpgCache.get(client, item.id)
            if (focusedItem?.id == item.id) showEpg(item, epg)
        }
    }

    private fun showEpg(item: Item, epg: List<EpgEntry>) {
        val now = epg.firstOrNull()
        if (now == null) {
            epgTime.text = item.name.uppercase()
            epgTitle.text = "Pas de guide des programmes"
            epgDesc.text = ""
            epgBar.visibility = View.INVISIBLE
            epgNext.text = ""
            return
        }
        epgTime.text = "EN CE MOMENT · ${EpgCache.time(now.start)} – ${EpgCache.time(now.end)}"
        epgTitle.text = now.title
        epgDesc.text = now.description
        epgBar.visibility = View.VISIBLE
        epgBar.progress = (EpgCache.progress(now) * 1000).toInt()
        epgNext.text = epg.getOrNull(1)?.let { "Ensuite · ${EpgCache.time(it.start)}  ${it.title}" }.orEmpty()
    }

    // ---------- Aperçu ----------
    private fun ensurePreview(): ExoPlayer {
        preview?.let { return it }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(OkHttpDataSource.Factory(XtreamClient.http)))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val item = focusedItem ?: return
                if (previewId != item.id) return
                val pi = Session.livePlayItem(client, item, true)
                if (!previewFallbackTried && pi.fallbackUrl != null) {
                    previewFallbackTried = true
                    p.setMediaItem(mediaItem(pi.fallbackUrl))
                    p.prepare()
                } else {
                    previewHint.visibility = View.VISIBLE
                    previewHint.text = "Aperçu indisponible pour cette chaîne"
                    liveBadge.visibility = View.GONE
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    previewHint.visibility = View.GONE
                    liveBadge.visibility = View.VISIBLE
                }
            }
        })
        p.playWhenReady = true
        previewView.player = p
        preview = p
        return p
    }

    private fun mediaItem(url: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .apply { if (url.substringBefore('?').endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .build()

    private fun startPreview(item: Item) {
        if (previewId == item.id && preview != null) return
        val p = ensurePreview()
        previewId = item.id
        previewFallbackTried = false
        previewHint.visibility = View.VISIBLE
        previewHint.text = "Chargement…"
        liveBadge.visibility = View.GONE
        p.setMediaItem(mediaItem(Session.livePlayItem(client, item, false).url))
        p.prepare()
        previewGuard.start(claim = false)
    }

    private fun stopPreview() {
        previewGuard.stop()
        preview?.let {
            it.release()
            previewView.player = null
        }
        preview = null
        previewId = null
    }

    // ---------- Actions ----------
    private fun openFullscreen(item: Item) {
        stopPreview() // libère la connexion avant d'ouvrir le plein écran
        val list = channelAdapter.items
        Session.playlist = list.map { Session.livePlayItem(client, it, prefs.autoFallback) }
        Session.playIndex = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** Une chaîne fusionnée est favorite si l'une de ses versions l'est. */
    private fun isFav(item: Item) = item.allIds.any { it in favorites }

    private fun toggleFavorite(item: Item) {
        val added = if (isFav(item)) {
            prefs.removeFavorites(item.allIds); false
        } else prefs.toggleFavorite(item.id)
        favorites = prefs.favorites()
        toast(if (added) "★ ${item.name} ajoutée aux favoris" else "${item.name} retirée des favoris")
        buildCategories()
        if (currentCat == FAV) showChannels()
        else channelAdapter.indexOf(item.id).takeIf { it >= 0 }?.let { channelAdapter.notifyItemChanged(it) }
    }

    override fun onResume() {
        super.onResume()
        if (!::channelAdapter.isInitialized) return
        val f = prefs.favorites()
        if (f != favorites) {
            favorites = f
            buildCategories()
            showChannels()
        }
        // Après le plein écran, on se replace sur la dernière chaîne regardée.
        val last = Session.playlist.getOrNull(Session.playIndex)
        if (last?.isLive == true) {
            val i = channelAdapter.indexOf(last.liveId.orEmpty())
            if (i >= 0) {
                rvChannels.scrollToPosition(i)
                rvChannels.post { rvChannels.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopPreview()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Le bouton « Retour » depuis la liste des chaînes ramène d'abord aux catégories.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && ::rvChannels.isInitialized && rvChannels.hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val i = catAdapter.rows.indexOfFirst { it.id == currentCat }.coerceAtLeast(0)
                val v = rvCats.findViewHolderForAdapterPosition(i)?.itemView
                if (v != null) v.requestFocus()
                else {
                    rvCats.scrollToPosition(i)
                    rvCats.post { rvCats.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
