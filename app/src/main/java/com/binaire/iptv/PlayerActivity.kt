package com.binaire.iptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
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

/** Lecteur plein écran : direct (avec zapping), films et épisodes (avec reprise). */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var watch: WatchStore
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySub: TextView

    private var player: ExoPlayer? = null
    private var index = 0
    private var usingFallback = false
    private var epgJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private val playlist: List<PlayItem> get() = Session.playlist
    private val current: PlayItem? get() = playlist.getOrNull(index)

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val item = current ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (!usingFallback && item.fallbackUrl != null) {
                play(index, true) // autre format (ts ↔ m3u8)
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
            if (prefs.autoNext && index < playlist.size - 1) play(index + 1, false) else finish()
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
        releasePlayer()
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
        p.addListener(listener)
        p.playWhenReady = true
        playerView.player = p
        player = p
        play(index, false)
    }

    private fun play(i: Int, fallback: Boolean) {
        val p = player ?: return
        // On enregistre la progression de l'élément quitté (épisode précédent…).
        if (i != index) savePosition()
        val item = playlist.getOrNull(i) ?: return
        index = i
        Session.playIndex = i
        usingFallback = fallback

        val url = if (fallback) item.fallbackUrl ?: item.url else item.url
        val media = MediaItem.Builder()
            .setUri(url)
            .apply { if (url.substringBefore('?').endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()

        val resumeAt = item.watch?.let { m ->
            watch.resume(m.key)?.takeIf { it.progress in 0.01f..0.95f }?.position
        } ?: 0L
        if (resumeAt > 0) p.setMediaItem(media, resumeAt) else p.setMediaItem(media)
        p.prepare()
        if (!fallback) showOverlay(item, resumeAt)
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

    /** Zapping : CH+/CH− de la télécommande, ou haut/bas quand les commandes sont masquées. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val item = current
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
        play(((index + delta) % n + n) % n, false)
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

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
            playerView.player = null
        }
        player = null
    }
}
