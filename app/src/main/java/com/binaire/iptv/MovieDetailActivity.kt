package com.binaire.iptv

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fiche d'un film : image, infos, Lecture / Reprendre, Ma liste. */
class MovieDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var item: Item
    private var info: VodInfo? = null
    private lateinit var btnPlay: Button
    private lateinit var btnRestart: Button
    private lateinit var btnList: Button
    private val key get() = "movie:${item.id}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectIfNotReady()) return
        val id = intent.getStringExtra(EXTRA_ID)
        val found = Session.items(Kind.MOVIE).firstOrNull { it.id == id }
        if (found == null) {
            finish()
            return
        }
        item = found
        setContentView(R.layout.activity_detail)
        applyBackground(findViewById(R.id.root))
        setupHeader("FILMS")

        val backdrop: ImageView = findViewById(R.id.backdrop)
        val title: TextView = findViewById(R.id.title)
        if (isShortScreen) title.textSize = 24f
        val meta: TextView = findViewById(R.id.meta)
        val plot: TextView = findViewById(R.id.plot)
        val loading: View = findViewById(R.id.loading)
        btnPlay = findViewById(R.id.btnPlay)
        btnRestart = findViewById(R.id.btnRestart)
        btnList = findViewById(R.id.btnList)

        styleButtons(this, accent, btnPlay, listOf(btnRestart, btnList))

        title.text = item.name
        meta.text = item.rating?.let { "★ " + formatRating(it) }.orEmpty()
        backdrop.load(item.icon) { crossfade(true) }

        btnPlay.setOnClickListener { play(fromStart = false) }
        btnRestart.setOnClickListener { play(fromStart = true) }
        btnList.setOnClickListener {
            val added = watch.toggleMyList(Kind.MOVIE, item.id)
            toast(if (added) "Ajouté à ta liste" else "Retiré de ta liste")
            updateButtons()
        }
        updateButtons()
        btnPlay.requestFocus()

        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            try {
                val i = VodActivity.vodInfoCache[item.id]
                    ?: withContext(Dispatchers.IO) { client.vodInfo(item.id) }.also { VodActivity.vodInfoCache[item.id] = it }
                info = i
                meta.text = listOfNotNull(
                    i.year, i.duration?.let(::shortDuration), i.genre?.let(::firstGenres),
                    (i.rating ?: item.rating)?.let { "★ " + formatRating(it) }
                ).joinToString("   ·   ")
                plot.text = i.plot.orEmpty()
                val img = i.backdrop ?: i.poster
                if (img != null) backdrop.load(img) { crossfade(true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                plot.text = ""
            }
            loading.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (::btnPlay.isInitialized) updateButtons()
    }

    private fun updateButtons() {
        val r = watch.resume(key)
        if (r != null && r.progress in 0.01f..0.95f) {
            btnPlay.text = "Reprendre · reste ${r.remainingMin} min"
            btnRestart.visibility = View.VISIBLE
        } else {
            btnPlay.text = "Lecture"
            btnRestart.visibility = View.GONE
        }
        btnList.text = if (watch.inMyList(Kind.MOVIE, item.id)) "✓ Dans ma liste" else "+ Ma liste"
    }

    private fun play(fromStart: Boolean) {
        if (fromStart) watch.remove(key)
        val ext = info?.ext ?: item.ext ?: "mp4"
        Session.playlist = listOf(
            PlayItem(
                title = item.name,
                url = client.movieUrl(item.id, ext),
                isLive = false,
                watch = WatchMeta(key = key, kind = "movie", title = item.name, image = item.icon, contentId = item.id, ext = ext)
            )
        )
        Session.playIndex = 0
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}

/** Style commun des boutons des fiches. */
internal fun styleButtons(activity: BaseActivity, accent: Int, primary: Button, others: List<Button>) {
    primary.background = BTheme.selector(activity, 20f, accent, accent, BTheme.WHITE, null, 3f)
    primary.setTextColor(BTheme.WHITE)
    TextViewCompat.setCompoundDrawableTintList(primary, ColorStateList.valueOf(BTheme.WHITE))
    val size = BTheme.dp(activity, 14f)
    primary.compoundDrawablesRelative[0]?.setBounds(0, 0, size, size)
    primary.setCompoundDrawablesRelative(primary.compoundDrawablesRelative[0], null, null, null)
    for (b in others) {
        b.background = BTheme.selector(activity, 20f, BTheme.WHITE18, accent, BTheme.WHITE, null, 3f)
        b.setTextColor(BTheme.WHITE)
    }
}
