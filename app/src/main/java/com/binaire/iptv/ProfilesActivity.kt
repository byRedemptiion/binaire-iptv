package com.binaire.iptv

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import coil.load
import coil.transform.CircleCropTransformation
import java.util.Locale

/** « Qui regarde ? » : choisir, créer et modifier les profils famille. */
class ProfilesActivity : BaseActivity() {

    companion object {
        /** Ouvert au démarrage : les listes n'ont pas encore été rangées. */
        const val EXTRA_START = "start"
        /** Ouvert depuis les Réglages : on modifie plutôt qu'on choisit. */
        const val EXTRA_MANAGE = "manage"
    }

    override val needsData = false

    private var start = false
    private var manage = false
    private var needRerender = false
    private lateinit var tiles: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start = intent.getBooleanExtra(EXTRA_START, false)
        manage = intent.getBooleanExtra(EXTRA_MANAGE, false)
        if (Session.client == null || Session.rawStreams.isEmpty()) {
            startActivity(Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            return
        }
        setContentView(R.layout.activity_profiles)
        applyBackground(findViewById(R.id.root))
        setupHeader(if (manage) "PROFILS" else null, showBack = !start)
        findViewById<TextView>(R.id.title).text = if (manage) getString(R.string.profiles_manage_title) else getString(R.string.profiles_who)
        findViewById<TextView>(R.id.hint).text =
            if (manage) getString(R.string.profiles_hint_manage_short)
            else getString(R.string.profiles_hint_manage)
        tiles = findViewById(R.id.tiles)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Retour du sélecteur d'avatar : on rafraîchit les tuiles.
        if (needRerender && ::tiles.isInitialized) {
            needRerender = false
            render()
        }
    }

    private fun render() {
        tiles.removeAllViews()
        val list = Profiles.all(this)
        val currentId = Profiles.currentId(this)
        var focus: View? = null
        for (p in list) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_profile, tiles, false)
            val avatar: FrameLayout = v.findViewById(R.id.avatar)
            val color = BTheme.accents.getOrElse(p.color) { BTheme.accents[0] }.second
            avatar.background = BTheme.rounded(this, 55f, color)
            avatar.foreground = focusRingAvatar()
            val initial = v.findViewById<TextView>(R.id.initial)
            val photo = v.findViewById<ImageView>(R.id.photo)
            initial.text = p.name.trim().take(1).uppercase(Locale.FRANCE).ifEmpty { "?" }
            if (Avatars.exists(this, p.avatar)) {
                initial.visibility = View.GONE
                photo.visibility = View.VISIBLE
                photo.load(Avatars.uri(p.avatar!!)) { transformations(CircleCropTransformation()) }
            } else {
                photo.visibility = View.GONE
                initial.visibility = View.VISIBLE
            }
            v.findViewById<ImageView>(R.id.lock).visibility = if (p.pin != null) View.VISIBLE else View.GONE
            v.findViewById<TextView>(R.id.name).text = p.name
            v.findViewById<TextView>(R.id.info).text = when {
                p.kid -> getString(R.string.profiles_kid)
                p.id == currentId && !start -> getString(R.string.profiles_current)
                else -> ""
            }
            avatar.setOnFocusChangeListener { view, has -> BTheme.zoom(view, has, 1.1f) }
            avatar.setOnClickListener { if (manage) edit(p) else choose(p) }
            avatar.setOnLongClickListener { edit(p); true }
            tiles.addView(v)
            if (p.id == currentId) focus = avatar
        }
        if (list.size < Profiles.MAX) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_profile, tiles, false)
            val avatar: FrameLayout = v.findViewById(R.id.avatar)
            avatar.background = BTheme.rounded(this, 55f, 0, 0x55FFFFFF, 3f)
            avatar.foreground = focusRingAvatar()
            v.findViewById<TextView>(R.id.initial).apply { text = "+"; textSize = 50f; alpha = 0.8f }
            v.findViewById<TextView>(R.id.name).text = getString(R.string.common_add)
            avatar.setOnFocusChangeListener { view, has -> BTheme.zoom(view, has, 1.1f) }
            avatar.setOnClickListener { create() }
            tiles.addView(v)
        }
        (focus ?: tiles.getChildAt(0)?.findViewById<View>(R.id.avatar))?.let { f -> tiles.post { f.requestFocus() } }
    }

    private fun focusRingAvatar() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), BTheme.rounded(this@ProfilesActivity, 55f, 0, BTheme.WHITE, 4f))
        addState(intArrayOf(), BTheme.rounded(this@ProfilesActivity, 55f, 0))
    }

    // ---------- Choisir ----------
    private fun choose(p: Profiles.Profile) {
        val go = { switchTo(p) }
        if (p.pin != null && !(p.id == Profiles.currentId(this) && !start)) askPin("Code de « ${p.name} »", p.pin, go) else go()
    }

    private fun switchTo(p: Profiles.Profile) {
        val changed = p.id != Profiles.currentId(this)
        Profiles.setCurrent(this, p.id)
        if (!changed && !start) {
            finish()
            return
        }
        rebuildWithProgress {
            val np = Prefs(this)
            val needCountries = !np.countriesConfigured && Session.countries.count { it.code != Library.UNKNOWN } >= 2
            val next = if (needCountries) Intent(this, CountriesActivity::class.java).putExtra(CountriesActivity.EXTRA_FIRST, true)
            else Intent(this, HomeActivity::class.java)
            startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }

    // ---------- Créer / modifier ----------
    private fun create() {
        val used = Profiles.all(this).map { it.color }.toSet()
        val color = BTheme.accents.indices.firstOrNull { it !in used } ?: 0
        editDialog(null, "", color, false) { name, c, kid ->
            val p = Profiles.add(this, name, c, kid)
            if (kid && Profiles.parentalPin(this) == null) {
                toast(getString(R.string.profiles_set_parental_hint))
            }
            render()
            tiles.post { tiles.getChildAt(Profiles.all(this).indexOfFirst { it.id == p.id })?.findViewById<View>(R.id.avatar)?.requestFocus() }
        }
    }

    /** Modifier un profil : demande son code (ou le code parental pour un profil Enfant). */
    private fun edit(p: Profiles.Profile) {
        val open = { actions(p) }
        val parental = Profiles.parentalPin(this)
        when {
            p.pin != null -> askPin("Code de « ${p.name} »", p.pin, open)
            p.kid && parental != null -> askPin(getString(R.string.profiles_parental_code), parental, open)
            Profiles.current(this).kid && parental != null -> askPin(getString(R.string.profiles_parental_code), parental, open)
            else -> open()
        }
    }

    private fun actions(p: Profiles.Profile) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add(getString(R.string.profiles_change_avatar) to {
            needRerender = true
            startActivity(Intent(this, AvatarPickerActivity::class.java).putExtra(AvatarPickerActivity.EXTRA_PROFILE_ID, p.id))
        })
        // Réordonner les profils (ordre affiché dans « Qui regarde ? »).
        val ordered = Profiles.all(this)
        val idx = ordered.indexOfFirst { it.id == p.id }
        if (idx > 0) items.add(getString(R.string.profiles_move_left) to {
            Profiles.move(this, p.id, -1); Sync.pushLater(this); render()
        })
        if (idx in 0 until ordered.size - 1) items.add(getString(R.string.profiles_move_right) to {
            Profiles.move(this, p.id, +1); Sync.pushLater(this); render()
        })
        items.add(getString(R.string.profiles_edit_name_color) to {
            editDialog(p, p.name, p.color, p.kid) { name, c, kid ->
                Profiles.update(this, p.copy(name = name.trim().ifBlank { p.name }, color = c, kid = kid))
                if (kid) Prefs.forProfile(this, p.id).hideAdult = true
                if (p.id == Profiles.currentId(this) && kid != p.kid) rebuildWithProgress { render() } else render()
            }
        })
        items.add((if (p.pin == null) getString(R.string.profiles_add_code) else getString(R.string.profiles_remove_code)) to {
            if (p.pin == null) newPin("Code de « ${p.name} »") { pin ->
                Profiles.update(this, p.copy(pin = pin)); render(); toast(getString(R.string.profiles_code_saved))
            } else {
                Profiles.update(this, p.copy(pin = null)); render(); toast(getString(R.string.profiles_code_removed))
            }
        })
        if (Profiles.all(this).size > 1) items.add(getString(R.string.profiles_delete) to {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.profiles_delete_title, p.name))
                .setMessage(getString(R.string.profiles_delete_confirm))
                .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                    val wasCurrent = p.id == Profiles.currentId(this)
                    Profiles.remove(this, p.id)
                    if (wasCurrent) rebuildWithProgress { render() } else render()
                }
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show()
        })
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setNegativeButton(getString(R.string.common_close), null)
            .show()
    }

    private fun editDialog(
        p: Profiles.Profile?, name: String, color: Int, kid: Boolean,
        onSave: (String, Int, Boolean) -> Unit
    ) {
        var chosen = color
        val pad = dp(20f)
        val input = EditText(this).apply {
            setText(name)
            setSelection(name.length)
            hint = getString(R.string.profiles_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(16))
        }
        val colorBtn = TextView(this).apply {
            setPadding(0, dp(14f), 0, dp(6f))
            isFocusable = true
            isClickable = true
            textSize = 15f
        }
        fun paintColor() {
            colorBtn.text = getString(R.string.profiles_color, BTheme.accents[chosen].first)
            colorBtn.setTextColor(BTheme.accents[chosen].second)
        }
        paintColor()
        colorBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.profiles_color_title))
                .setSingleChoiceItems(BTheme.accents.map { it.first }.toTypedArray(), chosen) { d, w ->
                    chosen = w; paintColor(); d.dismiss()
                }
                .show()
        }
        val kidBox = CheckBox(this).apply {
            text = getString(R.string.profiles_kid_option)
            isChecked = kid
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8f), pad, 0)
            addView(input)
            addView(colorBtn)
            addView(kidBox)
        }
        AlertDialog.Builder(this)
            .setTitle(if (p == null) getString(R.string.profiles_new) else getString(R.string.profiles_edit))
            .setView(box)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() && p == null) toast(getString(R.string.profiles_give_name))
                else onSave(n, chosen, kidBox.isChecked)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }
}
