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
        findViewById<TextView>(R.id.title).text = if (manage) "Gérer les profils" else "Qui regarde ?"
        findViewById<TextView>(R.id.hint).text =
            if (manage) "OK sur un profil pour le modifier : nom, couleur, profil Enfant, code."
            else "Maintiens OK sur un profil pour le modifier (nom, couleur, code)."
        tiles = findViewById(R.id.tiles)
        render()
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
            avatar.background = BTheme.rounded(this, 26f, color)
            avatar.foreground = focusRingAvatar()
            v.findViewById<TextView>(R.id.initial).text = p.name.trim().take(1).uppercase(Locale.FRANCE).ifEmpty { "?" }
            v.findViewById<ImageView>(R.id.lock).visibility = if (p.pin != null) View.VISIBLE else View.GONE
            v.findViewById<TextView>(R.id.name).text = p.name
            v.findViewById<TextView>(R.id.info).text = when {
                p.kid -> "Profil Enfant"
                p.id == currentId && !start -> "Profil actuel"
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
            avatar.background = BTheme.rounded(this, 26f, 0, 0x55FFFFFF, 3f)
            avatar.foreground = focusRingAvatar()
            v.findViewById<TextView>(R.id.initial).apply { text = "+"; textSize = 50f; alpha = 0.8f }
            v.findViewById<TextView>(R.id.name).text = "Ajouter"
            avatar.setOnFocusChangeListener { view, has -> BTheme.zoom(view, has, 1.1f) }
            avatar.setOnClickListener { create() }
            tiles.addView(v)
        }
        (focus ?: tiles.getChildAt(0)?.findViewById<View>(R.id.avatar))?.let { f -> tiles.post { f.requestFocus() } }
    }

    private fun focusRingAvatar() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), BTheme.rounded(this@ProfilesActivity, 26f, 0, BTheme.WHITE, 4f))
        addState(intArrayOf(), BTheme.rounded(this@ProfilesActivity, 26f, 0))
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
                toast("Pense à définir un code parental pour protéger les Réglages du profil Enfant")
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
            p.kid && parental != null -> askPin("Code parental", parental, open)
            Profiles.current(this).kid && parental != null -> askPin("Code parental", parental, open)
            else -> open()
        }
    }

    private fun actions(p: Profiles.Profile) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add("Modifier le nom, la couleur…" to {
            editDialog(p, p.name, p.color, p.kid) { name, c, kid ->
                Profiles.update(this, p.copy(name = name.trim().ifBlank { p.name }, color = c, kid = kid))
                if (kid) Prefs.forProfile(this, p.id).hideAdult = true
                if (p.id == Profiles.currentId(this) && kid != p.kid) rebuildWithProgress { render() } else render()
            }
        })
        items.add((if (p.pin == null) "Ajouter un code" else "Retirer le code") to {
            if (p.pin == null) newPin("Code de « ${p.name} »") { pin ->
                Profiles.update(this, p.copy(pin = pin)); render(); toast("Code enregistré")
            } else {
                Profiles.update(this, p.copy(pin = null)); render(); toast("Code retiré")
            }
        })
        if (Profiles.all(this).size > 1) items.add("Supprimer ce profil" to {
            AlertDialog.Builder(this)
                .setTitle("Supprimer « ${p.name} » ?")
                .setMessage("Ses favoris, sa liste rangée et ses reprises de lecture seront effacés.")
                .setPositiveButton("Supprimer") { _, _ ->
                    val wasCurrent = p.id == Profiles.currentId(this)
                    Profiles.remove(this, p.id)
                    if (wasCurrent) rebuildWithProgress { render() } else render()
                }
                .setNegativeButton("Annuler", null)
                .show()
        })
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setNegativeButton("Fermer", null)
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
            hint = "Prénom ou surnom"
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
            colorBtn.text = "Couleur : ${BTheme.accents[chosen].first}  ●"
            colorBtn.setTextColor(BTheme.accents[chosen].second)
        }
        paintColor()
        colorBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Couleur du profil")
                .setSingleChoiceItems(BTheme.accents.map { it.first }.toTypedArray(), chosen) { d, w ->
                    chosen = w; paintColor(); d.dismiss()
                }
                .show()
        }
        val kidBox = CheckBox(this).apply {
            text = "Profil Enfant (catégories adultes toujours masquées)"
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
            .setTitle(if (p == null) "Nouveau profil" else "Modifier le profil")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() && p == null) toast("Donne un nom au profil")
                else onSave(n, chosen, kidBox.isChecked)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
