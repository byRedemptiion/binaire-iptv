package com.binaire.iptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import java.util.Locale
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Lecteur plein écran : direct (avec zapping), films et épisodes (avec reprise). */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var watch: WatchStore
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySub: TextView
    private lateinit var btnOptions: TextView
    private lateinit var btnMultiTv: TextView
    private lateinit var topBar: View
    private lateinit var multiTvBadge: TextView
    private var tracksDecided = false

    private var player: ExoPlayer? = null
    private var index = 0
    private var attempt = 0
    private var epgJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private val playlist: List<PlayItem> get() = Session.playlist
    private val current: PlayItem? get() = playlist.getOrNull(index)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushState()

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (!tracksDecided && !tracks.isEmpty) {
                tracksDecided = true
                applyLanguageDefaults(tracks)
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = pushState()

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val item = current ?: return
            if (MultiTvHost.running) {
                if (item.isLive && shareErrors++ < 6) handler.postDelayed({ newLiveEpoch() }, 1000)
                else showMessage("Lecture impossible", "Le flux partagé s'est arrêté. (${error.errorCodeName})")
                return
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (attempt + 1 < item.attempts.size) {
                play(index, attempt + 1) // autre format ou autre version de la chaîne
                return
            }
            showMessage(
                "Lecture impossible",
                "La chaîne ou la vidéo est peut-être hors ligne, ou ton nombre de connexions est atteint. (${error.errorCodeName})"
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val item = current ?: return
            if (item.isLive) return
            item.watch?.let { m ->
                if (m.kind == "episode") {
                    m.episodeId?.let(watch::markWatched)
                    // Garde l'épisode (marqué fini) pour proposer le suivant.
                    watch.save(m, item.url, 1, 1)
                } else {
                    watch.remove(m.key)
                }
            }
            if (prefs.autoNext && index < playlist.size - 1) play(index + 1, 0) else finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (playlist.isEmpty() || Session.client == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        watch = WatchStore(this)
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        overlayTitle = findViewById(R.id.overlayTitle)
        overlaySub = findViewById(R.id.overlaySub)
        btnOptions = findViewById(R.id.btnOptions)
        btnMultiTv = findViewById(R.id.btnMultiTv)
        topBar = findViewById(R.id.topBar)
        multiTvBadge = findViewById(R.id.multiTvBadge)
        val size = (16 * resources.displayMetrics.density).toInt()
        val accent = BTheme.accent(this)
        for (b in listOf(btnOptions, btnMultiTv, multiTvBadge)) {
            b.compoundDrawablesRelative[0]?.setBounds(0, 0, size, size)
            b.setCompoundDrawablesRelative(b.compoundDrawablesRelative[0], null, null, null)
        }
        for (b in listOf(btnOptions, btnMultiTv)) {
            b.background = BTheme.selector(this, 12f, 0xCC000000.toInt(), accent, BTheme.WHITE, null, 2.5f)
            BTheme.zoomOnFocus(b, 1.08f)
        }
        btnOptions.setOnClickListener { showOptions() }
        btnMultiTv.setOnClickListener { multiTvClicked() }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> topBar.visibility = v })
        BTheme.stylePlayerControls(playerView, accent)
        applyResizeMode()
        index = Session.playIndex.coerceIn(0, playlist.size - 1)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null && ::playerView.isInitialized) initPlayer()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        if (MultiTvHost.running) stopSharing(false)
        releasePlayer()
        Sync.pushLater(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun initPlayer() {
        val loadControl = if (prefs.largeBuffer) {
            DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 3_000, 6_000).build()
        } else {
            DefaultLoadControl.Builder().build()
        }
        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(OkHttpDataSource.Factory(XtreamClient.http)))
            .build()
        // Par défaut : audio en français, sous-titres coupés (sauf sous-titres « forcés »).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("fr", "fra", "fre")
            .setPreferredTextLanguages()
            .setSelectUndeterminedTextLanguage(false)
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        p.addListener(listener)
        p.playWhenReady = true
        playerView.player = p
        player = p
        play(index, 0)
    }

    private fun play(i: Int, tryIndex: Int, at: Long? = null) {
        val p = player ?: return
        // On enregistre la progression de l'élément quitté (épisode précédent…).
        if (i != index) savePosition()
        val item = playlist.getOrNull(i) ?: return
        index = i
        Session.playIndex = i
        attempt = tryIndex
        tracksDecided = false
        val url = item.attempts.getOrElse(tryIndex) { item.url }
        val media = MediaItem.Builder()
            .setUri(url)
            .apply { if (url.substringBefore('?').endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()

        val resumeAt = at ?: item.watch?.let { m ->
            watch.resume(m.key)?.takeIf { it.progress in 0.01f..0.95f }?.position
        } ?: 0L
        if (MultiTvHost.running) {
            openShared(item, if (item.isLive) 0 else resumeAt)
            showOverlay(item, resumeAt)
            return
        }
        if (resumeAt > 0) p.setMediaItem(media, resumeAt) else p.setMediaItem(media)
        p.prepare()
        if (tryIndex == 0) showOverlay(item, resumeAt)
    }

    private fun showOverlay(item: PlayItem, resumeAt: Long) {
        val prefix = if (item.isLive && playlist.size > 1) "${index + 1}/${playlist.size}   " else ""
        overlayTitle.text = prefix + item.title
        overlaySub.visibility = View.GONE
        if (resumeAt > 0) {
            overlaySub.text = "Reprise à ${resumeAt / 60000} min"
            overlaySub.visibility = View.VISIBLE
        }
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 4500)

        epgJob?.cancel()
        val liveId = item.liveId ?: return
        val c = Session.client ?: return
        epgJob = lifecycleScope.launch {
            val epg = EpgCache.get(c, liveId)
            val now = epg.firstOrNull() ?: return@launch
            if (current != item) return@launch
            val next = epg.getOrNull(1)?.let { "\nEnsuite ${EpgCache.time(it.start)}  ${it.title}" }.orEmpty()
            overlaySub.text = "${EpgCache.time(now.start)}–${EpgCache.time(now.end)}  ${now.title}$next"
            overlaySub.visibility = View.VISIBLE
        }
    }

    private fun showMessage(title: String, msg: String) {
        overlayTitle.text = title
        overlaySub.text = msg
        overlaySub.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 9000)
    }

    // ---------- Options : format d'image, audio, sous-titres ----------
    private fun applyResizeMode() = BTheme.applyImageFormat(playerView, prefs.resizeMode)

    private class TrackChoice(val label: String, val apply: () -> Unit)

    private fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        return Locale(code).getDisplayLanguage(Locale.FRANCE).replaceFirstChar { it.titlecase(Locale.FRANCE) }.ifBlank { code }
    }

    private fun trackChoices(type: Int): List<TrackChoice> {
        val p = player ?: return emptyList()
        val out = mutableListOf<TrackChoice>()
        for (group in p.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val name = listOfNotNull(f.label, languageName(f.language)).distinct().joinToString(" · ")
                    .ifBlank { "Piste ${out.size + 1}" }
                val selected = group.isTrackSelected(i)
                out.add(TrackChoice((if (selected) "✓  " else "     ") + name) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                })
            }
        }
        return out
    }

    private fun showOptions() {
        val audio = trackChoices(C.TRACK_TYPE_AUDIO)
        val subs = trackChoices(C.TRACK_TYPE_TEXT)
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        val current = BTheme.imageFormatName(prefs.resizeMode)
        entries.add("Format d'image : $current" to { pickResize() })
        if (audio.size > 1) entries.add("Langue audio" to { pickTrack("Langue audio", audio, null) })
        if (subs.isNotEmpty()) entries.add("Sous-titres" to {
            pickTrack("Sous-titres", subs) {
                val p = player
                if (p != null) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
        })
        AlertDialog.Builder(this)
            .setTitle("Options de lecture")
            .setItems(entries.map { it.first }.toTypedArray()) { _, w -> entries[w].second() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun pickResize() {
        val resizeModes = BTheme.imageFormats
        val idx = resizeModes.indexOfFirst { it.first == prefs.resizeMode }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Format d'image")
            .setSingleChoiceItems(resizeModes.map { it.second }.toTypedArray(), idx) { d, w ->
                prefs.resizeMode = resizeModes[w].first
                applyResizeMode()
                d.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pickTrack(title: String, choices: List<TrackChoice>, disable: (() -> Unit)?) {
        val labels = (if (disable != null) listOf("     Désactivés") else emptyList()) + choices.map { it.label }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, w ->
                if (disable != null && w == 0) disable() else choices[if (disable != null) w - 1 else w].apply()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Zapping : CH+/CH− de la télécommande, ou haut/bas quand les commandes sont masquées. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val item = current
        // Touche Menu / Réglages de la télécommande : options de lecture.
        if (::playerView.isInitialized && (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS)) {
            if (event.action == KeyEvent.ACTION_UP) showOptions()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && item?.isLive == true && playlist.size > 1 && ::playerView.isInitialized) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (!playerView.isControllerFullyVisible) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!playerView.isControllerFullyVisible) { zap(1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun zap(delta: Int) {
        val n = playlist.size
        play(((index + delta) % n + n) % n, 0)
    }

    private fun savePosition() {
        val p = player ?: return
        val item = current ?: return
        val m = item.watch ?: return
        val duration = p.duration
        val position = p.currentPosition
        if (duration <= 0 || position < 10_000) return
        if (position > duration * 0.95) {
            if (m.kind == "episode") {
                m.episodeId?.let(watch::markWatched)
                watch.save(m, item.url, duration, duration)
            } else {
                watch.remove(m.key)
            }
        } else {
            watch.save(m, item.url, position, duration)
        }
    }

    // ---------- Multi-TV : cette TV partage ce qu'elle lit ----------
    private var shareErrors = 0

    private val hostListener = object : MultiTvHost.Listener {
        override fun onCommand(action: String, pos: Long) {
            val p = player ?: return
            when (action) {
                "play" -> p.play()
                "pause" -> p.pause()
                "seek" -> if (current?.isLive != true) p.seekTo(pos)
                "resync" -> if (current?.isLive == true) newLiveEpoch()
            }
            pushState()
        }

        override fun onGuestJoined() {
            // En direct, tout le monde repart du même point du flux : les positions deviennent comparables.
            if (current?.isLive == true) newLiveEpoch() else pushState()
        }

        override fun onGuestsChanged(count: Int) = updateBadge()
    }

    private val stateTick = object : Runnable {
        override fun run() {
            if (!MultiTvHost.running) return
            pushState()
            handler.postDelayed(this, 500)
        }
    }

    private fun startSharing() {
        val p = player ?: return
        val item = current ?: return
        val pos = if (item.isLive) 0 else p.currentPosition
        p.stop() // on ferme la connexion directe avant d'ouvrir celle du relais
        MultiTvHost.listener = hostListener
        MultiTvHost.start(this, Cloud.deviceName())
        shareErrors = 0
        openShared(item, pos)
        handler.removeCallbacks(stateTick)
        handler.post(stateTick)
        updateBadge()
    }

    /** Pastille Multi-TV : reste affichée tant que le partage est actif. */
    private fun updateBadge() {
        if (!::multiTvBadge.isInitialized) return
        if (!MultiTvHost.running) {
            multiTvBadge.visibility = View.GONE
            btnMultiTv.text = "Multi-TV"
            return
        }
        val n = MultiTvHost.guestCount
        val who = when (n) {
            0 -> "en attente de l'autre TV (accueil → Multi-TV)"
            1 -> "1 autre TV connectée"
            else -> "$n autres TV connectées"
        }
        multiTvBadge.text = "Multi-TV · $who · adresse ${MultiTv.localIp() ?: "?"}"
        multiTvBadge.visibility = View.VISIBLE
        btnMultiTv.text = if (n > 0) "Multi-TV · $n" else "Multi-TV · actif"
    }

    private fun multiTvClicked() {
        if (!MultiTvHost.running) {
            startSharing()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Multi-TV actif")
            .setMessage(
                "Sur l'autre TV : accueil → bouton Multi-TV (en haut).\n" +
                    "Adresse si elle n'apparaît pas : ${MultiTv.localIp() ?: "?"}\n\n" +
                    "${MultiTvHost.guestCount} autre(s) TV connectée(s). Pause et avance sont partagées."
            )
            .setPositiveButton("Continuer", null)
            .setNegativeButton("Arrêter le partage") { _, _ -> stopSharing(true) }
            .show()
    }

    /**
     * Langues par défaut : VF si elle existe (sous-titres coupés) ;
     * film sans piste française (VOSTFR) : sous-titres français activés.
     */
    private fun applyLanguageDefaults(tracks: androidx.media3.common.Tracks) {
        val p = player ?: return
        fun isFrench(f: androidx.media3.common.Format): Boolean {
            val lang = f.language?.lowercase().orEmpty()
            if (lang.startsWith("fr") || lang == "fre" || lang == "fra") return true
            val label = f.label?.lowercase().orEmpty()
            return Regex("\\b(vf|vff|vfq|vfi|french|fran[cç]ais)\\b").containsMatchIn(label)
        }
        var frAudio: Pair<androidx.media3.common.TrackGroup, Int>? = null
        var audioCount = 0
        var frAudioSelected = false
        var frText: Pair<androidx.media3.common.TrackGroup, Int>? = null
        for (g in tracks.groups) {
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        audioCount++
                        if (isFrench(f)) {
                            if (frAudio == null) frAudio = g.mediaTrackGroup to i
                            if (g.isTrackSelected(i)) frAudioSelected = true
                        }
                    }
                    C.TRACK_TYPE_TEXT -> if (isFrench(f) && frText == null &&
                        (f.selectionFlags and C.SELECTION_FLAG_FORCED) == 0
                    ) frText = g.mediaTrackGroup to i
                }
            }
        }
        val b = p.trackSelectionParameters.buildUpon()
        var changed = false
        val fa = frAudio
        if (fa != null && !frAudioSelected) {
            b.setOverrideForType(TrackSelectionOverride(fa.first, fa.second)); changed = true
        }
        val ft = frText
        if (fa == null && audioCount > 0 && ft != null) {
            // VOSTFR : pas de piste française, on affiche les sous-titres français.
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            b.setOverrideForType(TrackSelectionOverride(ft.first, ft.second)); changed = true
        }
        if (changed) p.trackSelectionParameters = b.build()
    }

    private fun stopSharing(resume: Boolean) {
        handler.removeCallbacks(stateTick)
        val p = player
        val pos = p?.currentPosition ?: 0L
        p?.stop()
        MultiTvHost.listener = null
        MultiTvHost.stop()
        updateBadge()
        if (resume && p != null && current != null) {
            play(index, 0, if (current?.isLive == true) 0L else pos)
            showMessage("Multi-TV", "Partage arrêté.")
        }
    }

    private fun loadMsg(item: PlayItem, cache: RelayCache, path: String, pos: Long): JSONObject {
        val p = player
        return JSONObject().put("t", "load").put("id", cache.id).put("live", item.isLive).put("title", item.title)
            .put("path", path).put("pos", pos).put("playing", p?.isPlaying == true)
            .put("pwr", p?.playWhenReady ?: true).put("h", SystemClock.elapsedRealtime())
    }

    private fun openShared(item: PlayItem, pos: Long) {
        val p = player ?: return
        p.stop()
        MultiTvHost.currentCache()?.close() // une seule connexion au fournisseur à la fois
        val cache = RelayCache(File(cacheDir, "multitv.bin"), MultiTv.relayUrl(item), item.isLive)
        val path = "/media?id=${cache.id}&from=${if (item.isLive) cache.head() else 0}"
        MultiTvHost.setMedia(cache, loadMsg(item, cache, path, pos))
        val media = MediaItem.fromUri(MultiTvHost.localUrl(path))
        if (!item.isLive && pos > 0) p.setMediaItem(media, pos) else p.setMediaItem(media)
        p.prepare()
    }

    private fun newLiveEpoch() {
        val p = player ?: return
        val item = current ?: return
        val cache = MultiTvHost.currentCache() ?: return
        val path = "/media?id=${cache.id}&from=${cache.head()}"
        MultiTvHost.setMedia(cache, loadMsg(item, cache, path, 0))
        p.setMediaItem(MediaItem.fromUri(MultiTvHost.localUrl(path)))
        p.prepare()
    }

    private fun pushState() {
        if (!MultiTvHost.running) return
        val p = player ?: return
        val cache = MultiTvHost.currentCache() ?: return
        MultiTvHost.broadcast(
            JSONObject().put("t", "state").put("id", cache.id).put("pos", p.currentPosition)
                .put("playing", p.isPlaying).put("pwr", p.playWhenReady).put("h", SystemClock.elapsedRealtime())
        )
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
            playerView.player = null
        }
        player = null
    }
}
