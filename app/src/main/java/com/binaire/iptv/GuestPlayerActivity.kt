package com.binaire.iptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import kotlin.math.abs

/** Multi-TV : cette TV suit la TV principale (même flux, même position, pause partagée). */
@OptIn(UnstableApi::class)
class GuestPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
    }

    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySub: TextView

    private var exo: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private lateinit var host: String
    private var port = 0
    private var hostName = "TV principale"

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closing = false

    // Horloge : décalage entre l'horloge de la TV principale et la nôtre (meilleur aller-retour).
    private var clockOffset = 0L
    private var bestRtt = Long.MAX_VALUE

    // Dernier état connu de la TV principale.
    private var live = false
    private var mediaId: String? = null
    private var hostPos = 0L
    private var hostPlaying = false
    private var hostAt = 0L
    private var hasState = false
    private var lastSeek = 0L
    private var correcting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra(EXTRA_HOST) ?: return finish()
        port = intent.getIntExtra(EXTRA_PORT, MultiTv.DEFAULT_PORT)
        intent.getStringExtra(EXTRA_NAME)?.let { hostName = it }
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        overlayTitle = findViewById(R.id.overlayTitle)
        overlaySub = findViewById(R.id.overlaySub)
        playerView.resizeMode = when (Prefs(this).resizeMode) {
            "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        BTheme.stylePlayerControls(playerView, BTheme.accent(this))
        findViewById<TextView>(R.id.multiTvBadge).apply {
            val size = (16 * resources.displayMetrics.density).toInt()
            compoundDrawablesRelative[0]?.setBounds(0, 0, size, size)
            setCompoundDrawablesRelative(compoundDrawablesRelative[0], null, null, null)
            text = "Multi-TV · avec $hostName"
            visibility = View.VISIBLE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        message("Multi-TV", "Connexion à $hostName…", 0)
    }

    override fun onStart() {
        super.onStart()
        closing = false
        initPlayer()
        connect()
        main.post(syncTick)
        main.post(pingTick)
    }

    override fun onStop() {
        super.onStop()
        closing = true
        main.removeCallbacksAndMessages(null)
        runCatching { socket?.close() }
        socket = null
        out = null
        exo?.release()
        exo = null
        playerView.player = null
        mediaId = null
        hasState = false
    }

    // ---------- Lecteur ----------
    private fun initPlayer() {
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(
                    DefaultHttpDataSource.Factory().setConnectTimeoutMs(8000).setReadTimeoutMs(60_000)
                )
            )
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("fr", "fra", "fre")
            .setPreferredTextLanguages()
            .setSelectUndeterminedTextLanguage(false)
            .setIgnoredTextSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // On redemande le flux là où en est la TV principale.
                message("Multi-TV", "Petite coupure, on se recale…", 3000)
                main.postDelayed({ reloadAtHost() }, 800)
            }
        })
        exo = p
        // Les commandes à l'écran et la télécommande agissent sur les deux TV.
        playerView.player = object : ForwardingPlayer(p) {
            override fun play() { send("play"); p.play() }
            override fun pause() { send("pause"); p.pause() }
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }
            override fun seekTo(positionMs: Long) { send("seek", positionMs); p.seekTo(positionMs); lastSeek = SystemClock.elapsedRealtime() }
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seekTo(positionMs)
            override fun seekForward() = seekTo(p.currentPosition + p.seekForwardIncrement)
            override fun seekBack() = seekTo((p.currentPosition - p.seekBackIncrement).coerceAtLeast(0))
        }
    }

    private var lastLoad: JSONObject? = null

    private fun load(o: JSONObject) {
        val p = exo ?: return
        lastLoad = o
        live = o.optBoolean("live")
        mediaId = o.optString("id")
        val url = "http://$host:$port" + o.optString("path")
        val pos = o.optLong("pos")
        val item = MediaItem.fromUri(url)
        if (!live && pos > 0) p.setMediaItem(item, estimateHostPos(pos, o.optBoolean("playing"), o.optLong("h"))) else p.setMediaItem(item)
        p.setPlaybackParameters(PlaybackParameters.DEFAULT)
        p.playWhenReady = o.optBoolean("pwr", true)
        p.prepare()
        message(o.optString("title"), "En direct avec $hostName", 4500)
    }

    private fun reloadAtHost() {
        val o = lastLoad ?: return
        if (live) send("resync") else load(JSONObject(o.toString()).put("pos", hostPos).put("h", hostAt).put("playing", hostPlaying))
    }

    private fun estimateHostPos(pos: Long, playing: Boolean, at: Long): Long {
        if (!playing) return pos
        val hostNow = SystemClock.elapsedRealtime() + clockOffset
        return pos + (hostNow - at).coerceAtLeast(0)
    }

    /** Toutes les 250 ms : on compare notre position à celle de la TV principale et on rattrape. */
    private val syncTick = object : Runnable {
        override fun run() {
            main.postDelayed(this, 250)
            val p = exo ?: return
            if (!hasState || p.playbackState != Player.STATE_READY) return
            if (p.playWhenReady != hostPlaying && !hostPlaying) p.pause()
            if (!hostPlaying) {
                if (!live && abs(p.currentPosition - hostPos) > 300 && SystemClock.elapsedRealtime() - lastSeek > 1500) {
                    p.seekTo(hostPos); lastSeek = SystemClock.elapsedRealtime()
                }
                setSpeed(p, 1f)
                return
            }
            if (!p.playWhenReady) p.play()
            val target = estimateHostPos(hostPos, true, hostAt)
            val d = p.currentPosition - target // > 0 : on est en avance
            val ad = abs(d)
            when {
                !live && ad > 1500 && SystemClock.elapsedRealtime() - lastSeek > 4000 -> {
                    p.seekTo(target + 400) // le temps de recharger
                    lastSeek = SystemClock.elapsedRealtime()
                    setSpeed(p, 1f)
                }
                ad > 40 || (correcting && ad > 12) -> {
                    correcting = true
                    val limit = if (live) 0.12f else 0.08f
                    val adj = (d / 2500f).coerceIn(-limit, limit)
                    setSpeed(p, 1f - adj)
                }
                else -> {
                    correcting = false
                    setSpeed(p, 1f)
                }
            }
        }
    }

    private fun setSpeed(p: ExoPlayer, s: Float) {
        if (abs(p.playbackParameters.speed - s) > 0.004f) p.setPlaybackParameters(PlaybackParameters(s))
    }

    // ---------- Canal de contrôle ----------
    private fun connect() {
        Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 6000)
                s.tcpNoDelay = true
                socket = s
                val o = s.getOutputStream()
                val me = URLEncoder.encode(Cloud.deviceName(), "UTF-8")
                o.write("GET /control?name=$me HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
                o.flush()
                out = o
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                // En-tête HTTP
                while (true) {
                    val l = reader.readLine() ?: throw IOException("fermé")
                    if (l.isEmpty()) break
                }
                repeat(5) { sendRaw(JSONObject().put("t", "ping").put("c", SystemClock.elapsedRealtime())) }
                while (true) {
                    val line = reader.readLine() ?: break
                    val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    main.post { onMessage(msg) }
                }
                main.post { lost("La TV principale ne répond plus.") }
            } catch (e: Exception) {
                main.post { lost("Impossible de joindre $hostName ($host). Vérifie qu'elle partage toujours et que les deux TV sont sur le même Wi-Fi.") }
            }
        }.start()
    }

    private fun onMessage(o: JSONObject) {
        when (o.optString("t")) {
            "hello" -> o.optString("name").takeIf { it.isNotBlank() }?.let {
                hostName = it
                findViewById<TextView>(R.id.multiTvBadge).text = "Multi-TV · avec $hostName"
            }
            "pong" -> {
                val now = SystemClock.elapsedRealtime()
                val c = o.optLong("c")
                val rtt = now - c
                // On garde la mesure la plus précise (aller-retour le plus court), en la laissant vieillir doucement.
                if (rtt <= bestRtt + 4) {
                    bestRtt = rtt
                    clockOffset = o.optLong("h") - (c + rtt / 2)
                } else bestRtt += 1
            }
            "load" -> load(o)
            "state" -> {
                if (o.optString("id") != mediaId) return
                hostPos = o.optLong("pos")
                hostPlaying = o.optBoolean("playing")
                hostAt = o.optLong("h")
                hasState = true
            }
            "end" -> lost("$hostName a arrêté le partage.")
        }
    }

    private val pingTick = object : Runnable {
        override fun run() {
            main.postDelayed(this, 2000)
            send(null)
        }
    }

    private fun send(action: String?, pos: Long = 0) {
        val o = if (action == null) JSONObject().put("t", "ping").put("c", SystemClock.elapsedRealtime())
        else JSONObject().put("t", "cmd").put("a", action).put("pos", pos)
        Thread { sendRaw(o) }.start()
    }

    private fun sendRaw(o: JSONObject) {
        val stream = out ?: return
        try {
            synchronized(stream) {
                // La mesure d'horloge doit partir au moment exact de l'envoi.
                if (o.optString("t") == "ping") o.put("c", SystemClock.elapsedRealtime())
                stream.write((o.toString() + "\n").toByteArray())
                stream.flush()
            }
        } catch (e: IOException) {
        }
    }

    private fun lost(why: String) {
        if (closing || isFinishing) return
        exo?.pause()
        message("Multi-TV terminé", why, 0)
        main.postDelayed({ if (!isFinishing) finish() }, 4000)
    }

    private fun message(title: String, sub: String, hideAfter: Long) {
        overlayTitle.text = title
        overlaySub.text = sub
        overlaySub.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        overlay.visibility = View.VISIBLE
        main.removeCallbacks(hideOverlay)
        if (hideAfter > 0) main.postDelayed(hideOverlay, hideAfter)
    }
}
