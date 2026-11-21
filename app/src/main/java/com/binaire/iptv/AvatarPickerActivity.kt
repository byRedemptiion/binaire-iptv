package com.binaire.iptv

import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

/**
 * Choix de l'avatar d'un profil parmi la bibliothèque embarquée.
 * On passe l'id du profil ; la sélection est enregistrée directement sur ce profil.
 */
class AvatarPickerActivity : BaseActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "pid"
    }

    override val needsData = false

    private lateinit var pid: String
    private var currentAvatar: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra(EXTRA_PROFILE_ID) ?: run { finish(); return }
        val profile = Profiles.get(this, pid) ?: run { finish(); return }
        currentAvatar = profile.avatar

        setContentView(R.layout.activity_avatar_picker)
        applyBackground(findViewById(R.id.root))
        setupHeader("AVATAR", showBack = true)
        findViewById<TextView>(R.id.title).text = "Avatar de « ${profile.name} »"

        // Première entrée = « aucun » (garde l'initiale colorée), puis tous les avatars.
        val items = listOf<String?>(null) + Avatars.all(this)
        val grid: RecyclerView = findViewById(R.id.grid)
        val span = resources.configuration.screenWidthDp / 132 + 1  // ~vignettes de 120 dp
        grid.layoutManager = GridLayoutManager(this, span.coerceIn(4, 8))
        grid.adapter = Adapter(items)
        grid.setHasFixedSize(true)
    }

    private fun pick(path: String?) {
        Profiles.get(this, pid)?.let { Profiles.update(this, it.copy(avatar = path)) }
        Sync.pushLater(this)
        finish()
    }

    private fun focusRing(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), BTheme.rounded(this@AvatarPickerActivity, 62f, 0, BTheme.WHITE, 4f))
        addState(intArrayOf(), BTheme.rounded(this@AvatarPickerActivity, 62f, 0))
    }

    private inner class Adapter(val items: List<String?>) : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cell: FrameLayout = v.findViewById(R.id.cell)
            val photo: ImageView = v.findViewById(R.id.photo)
            val check: ImageView = v.findViewById(R.id.check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_avatar, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val path = items[position]
            h.cell.foreground = focusRing()
            h.cell.setOnFocusChangeListener { view, has -> BTheme.zoom(view, has, 1.12f) }
            h.check.visibility = if (path == currentAvatar) View.VISIBLE else View.GONE
            if (path == null) {
                // Vignette « aucun » : pastille colorée avec l'initiale du profil.
                val color = BTheme.accents.getOrElse(Profiles.get(this@AvatarPickerActivity, pid)?.color ?: 0) { BTheme.accents[0] }.second
                h.photo.setImageDrawable(BTheme.rounded(this@AvatarPickerActivity, 58f, color))
            } else {
                h.photo.load(Avatars.uri(path)) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
            }
            h.cell.setOnClickListener { pick(path) }
            if (position == (items.indexOf(currentAvatar)).coerceAtLeast(0)) {
                h.cell.post { h.cell.requestFocus() }
            }
        }
    }
}
