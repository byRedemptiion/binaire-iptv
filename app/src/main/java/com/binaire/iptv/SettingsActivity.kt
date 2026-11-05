package com.binaire.iptv

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** RÉGLAGES : Apparence, Lecture, Abonnement, Mises à jour, À propos. */
class SettingsActivity : BaseActivity() {

    private class Section(val label: String, val icon: Int, val title: String, val sub: String)

    private class SettingRow(
        val label: String,
        val desc: String,
        val value: String? = null,
        val switchOn: Boolean? = null,
        val swatch: Int? = null,
        val onClick: (() -> Unit)? = null
    )

    private val sections = listOf(
        Section("Apparence", R.drawable.ic_sec_look, "Apparence", "Les couleurs et le style de l'appli."),
        Section("Chaînes & catégories", R.drawable.ic_sec_list, "Chaînes & catégories", "Remettre en ordre la liste de ton fournisseur."),
        Section("Lecture", R.drawable.ic_sec_play, "Lecture", "Comment les chaînes, films et séries se lancent."),
        Section("Abonnement", R.drawable.ic_sec_account, "Abonnement", "Ton abonnement IPTV sur cet appareil."),
        Section("Mises à jour", R.drawable.ic_sec_update, "Mises à jour", "Garder Binaire IPTV à jour."),
        Section("À propos", R.drawable.ic_sec_info, "À propos", "Binaire IPTV · Simple. Numérique. Intuitif.")
    )

    private var current = 0
    private lateinit var secTitle: TextView
    private lateinit var secSub: TextView
    private lateinit var sectionAdapter: SectionAdapter
    private lateinit var rowAdapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectIfNotReady()) return
        current = savedInstanceState?.getInt("section") ?: 0
        setContentView(R.layout.activity_settings)
        applyBackground(findViewById(R.id.root))
        setupHeader("RÉGLAGES")
        secTitle = findViewById(R.id.secTitle)
        secSub = findViewById(R.id.secSub)

        val rvSections: RecyclerView = findViewById(R.id.rvSections)
        sectionAdapter = SectionAdapter()
        rvSections.layoutManager = LinearLayoutManager(this)
        rvSections.adapter = sectionAdapter
        rvSections.itemAnimator = null

        val rvRows: RecyclerView = findViewById(R.id.rvRows)
        rowAdapter = RowAdapter()
        rvRows.layoutManager = LinearLayoutManager(this)
        rvRows.adapter = rowAdapter
        rvRows.itemAnimator = null

        showSection(current)
        rvSections.post { rvSections.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        if (::rowAdapter.isInitialized) refreshRows()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("section", current)
    }

    private fun showSection(i: Int) {
        current = i
        val s = sections[i]
        secTitle.text = s.title
        secSub.text = s.sub
        sectionAdapter.notifyDataSetChanged()
        rowAdapter.rows = buildRows(i)
        rowAdapter.notifyDataSetChanged()
    }

    private fun refreshRows() {
        rowAdapter.rows = buildRows(current)
        rowAdapter.notifyDataSetChanged()
    }

    private fun buildRows(i: Int): List<SettingRow> = when (i) {
        0 -> listOf(
            SettingRow("Couleur principale", "Sélection, boutons et logo texte", BTheme.accentName(this), swatch = accent) { pickAccent() },
            SettingRow("Fond d'écran", "Thème Binaire, Aurore, Noir ou image floutée", BTheme.backgroundName(this)) { pickBackground() },
            SettingRow("Afficher l'heure", "En haut de chaque écran", switchOn = prefs.showClock) {
                prefs.showClock = !prefs.showClock
                recreate()
            }
        )
        1 -> {
            val countries = prefs.allowedCountries
            val countriesLabel = when {
                countries.isEmpty() -> "Tous"
                countries.size <= 2 -> countries.joinToString(", ") { Library.countryName(it) }
                else -> "${countries.size} pays"
            }
            listOf(
                SettingRow("Pays affichés", "Les catégories des autres pays sont masquées", countriesLabel) {
                    startActivity(Intent(this, CountriesActivity::class.java))
                },
                SettingRow("Organiser les catégories TV", "Masquer, renommer, réordonner", "") { organize(Kind.LIVE) },
                SettingRow("Organiser les catégories Films", "Masquer, renommer, réordonner", "") { organize(Kind.MOVIE) },
                SettingRow("Organiser les catégories Séries", "Masquer, renommer, réordonner", "") { organize(Kind.SERIES) },
                SettingRow("Nettoyer les noms", "« FR | CHAÎNE UNE FHD ✦ VIP » devient « CHAÎNE UNE »", switchOn = prefs.cleanNames) {
                    prefs.cleanNames = !prefs.cleanNames; rebuildLists()
                },
                SettingRow("Regrouper les doublons", "Une seule ligne par chaîne, les autres versions servent de secours", switchOn = prefs.mergeDuplicates) {
                    prefs.mergeDuplicates = !prefs.mergeDuplicates; rebuildLists()
                },
                SettingRow("Masquer les catégories adultes", "Détectées automatiquement", switchOn = prefs.hideAdult) {
                    prefs.hideAdult = !prefs.hideAdult; rebuildLists()
                }
            )
        }
        2 -> listOf(
            SettingRow(
                "Qualité préférée", "Pour les chaînes disponibles en plusieurs qualités",
                Library.qualityChoices.firstOrNull { it.first == prefs.preferredQuality }?.second ?: "Automatique"
            ) { pickQuality() },
            SettingRow("Aperçu des chaînes", "Lance la chaîne en petit dans l'écran TV", switchOn = prefs.livePreview) {
                prefs.livePreview = !prefs.livePreview; refreshRows()
            },
            SettingRow("Basculer si ça coupe", "Essaie automatiquement l'autre format de flux", switchOn = prefs.autoFallback) {
                prefs.autoFallback = !prefs.autoFallback; refreshRows()
            },
            SettingRow("Épisode suivant automatique", "À la fin d'un épisode de série", switchOn = prefs.autoNext) {
                prefs.autoNext = !prefs.autoNext; refreshRows()
            },
            SettingRow(
                "Mémoire tampon", "Plus grande = moins de coupures, démarrage un peu plus lent",
                if (prefs.largeBuffer) "Grande" else "Normale"
            ) { prefs.largeBuffer = !prefs.largeBuffer; refreshRows() }
        )
        3 -> {
            val acc = Session.account
            val exp = acc?.expDate?.takeIf { it > 0 }?.let { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it * 1000)) }
            listOf(
                SettingRow("Serveur", "Adresse de ton fournisseur", Uri.parse(prefs.server.orEmpty()).host ?: prefs.server.orEmpty()),
                SettingRow("Statut", "Selon ton fournisseur", acc?.status?.let { statusFr(it).replaceFirstChar(Char::uppercase) } ?: "—"),
                SettingRow("Expiration", "Date de fin de ton abonnement", exp ?: "Sans date"),
                SettingRow("Connexions simultanées", "Nombre d'écrans en même temps", acc?.maxConnections ?: "—"),
                SettingRow("Actualiser les listes", "Recharge les chaînes, films et séries", "Maintenant") {
                    Session.ready = false
                    EpgCache.clear()
                    startActivity(Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                },
                SettingRow("Changer d'abonnement", "Se déconnecter de cet appareil (favoris et reprises conservés)", "") { confirmLogout() }
            )
        }
        4 -> listOf(
            SettingRow("Version installée", if (Updater.enabled) "Vérifiée à chaque ouverture de l'appli" else "Mises à jour automatiques non configurées pour cette version", BuildConfig.VERSION_NAME),
            SettingRow("Vérifier maintenant", "Cherche une nouvelle version", "") { checkUpdate() }
        )
        else -> listOf(
            SettingRow("Binaire IPTV", "Lecteur pour abonnements Xtream Codes. L'appli ne fournit aucune chaîne.", BuildConfig.VERSION_NAME),
            SettingRow("Vider le cache des images", "Logos, affiches et images de fond enregistrés", "") {
                imageLoader.memoryCache?.clear()
                EpgCache.clear()
                toast("Cache vidé")
            },
            SettingRow("Police Outfit", "SIL Open Font License 1.1", "Voir") { showLicense() }
        )
    }

    // ---------- Choix ----------
    private fun organize(kind: Kind) {
        startActivity(Intent(this, OrganizeActivity::class.java).putExtra(OrganizeActivity.EXTRA_KIND, kind.name))
    }

    /** Applique un réglage de rangement : reconstruit les listes puis rafraîchit l'écran. */
    private fun rebuildLists() {
        refreshRows()
        lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Library.rebuild(prefs) }
            toast("Liste mise à jour")
        }
    }

    private fun pickQuality() {
        val ids = Library.qualityChoices.map { it.first }
        val labels = Library.qualityChoices.map { it.second }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Qualité préférée")
            .setSingleChoiceItems(labels, ids.indexOf(prefs.preferredQuality).coerceAtLeast(0)) { d, which ->
                prefs.preferredQuality = ids[which]
                d.dismiss()
                rebuildLists()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pickAccent() {
        val labels = BTheme.accents.map { (name, color) ->
            SpannableString("●   $name").apply { setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Couleur principale")
            .setSingleChoiceItems(labels, prefs.accentIndex) { d, which ->
                prefs.accentIndex = which
                d.dismiss()
                recreate()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pickBackground() {
        val ids = BTheme.backgrounds.map { it.first }
        val labels = BTheme.backgrounds.map { it.second }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Fond d'écran")
            .setSingleChoiceItems(labels, ids.indexOf(prefs.background).coerceAtLeast(0)) { d, which ->
                prefs.background = ids[which]
                d.dismiss()
                recreate()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun checkUpdate() {
        if (!Updater.enabled) {
            toast("Les mises à jour ne sont pas configurées pour cette version")
            return
        }
        toast("Recherche d'une mise à jour…")
        lifecycleScope.launch {
            val r = runCatching { Updater.check() }
            val release = r.getOrNull()
            when {
                r.isFailure -> toast("Impossible de vérifier pour le moment")
                release == null -> toast("Tu as déjà la dernière version (${BuildConfig.VERSION_NAME})")
                else -> Updater.offer(this@SettingsActivity, lifecycleScope, release)
            }
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Changer d'abonnement")
            .setMessage("Se déconnecter de cet abonnement sur cet appareil ?")
            .setPositiveButton("Oui") { _, _ ->
                prefs.clearLogin()
                Session.clear()
                EpgCache.clear()
                startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showLicense() {
        val text = runCatching { assets.open("OFL-Outfit.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        AlertDialog.Builder(this)
            .setTitle("Police Outfit")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- Adaptateurs ----------
    private inner class SectionAdapter : RecyclerView.Adapter<SectionAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val label: TextView = v.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_section, parent, false)
            v.background = BTheme.selector(this@SettingsActivity, 10f, 0, accent, BTheme.WHITE, BTheme.WHITE12)
            return VH(v)
        }

        override fun getItemCount() = sections.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = sections[position]
            h.icon.setImageResource(s.icon)
            h.icon.imageTintList = ColorStateList.valueOf(BTheme.WHITE)
            h.label.text = s.label
            h.itemView.isSelected = position == current
            h.itemView.setOnFocusChangeListener { _, has -> if (has && current != position) showSection(position) }
            h.itemView.setOnClickListener { showSection(position) }
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        var rows: List<SettingRow> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.label)
            val desc: TextView = v.findViewById(R.id.desc)
            val value: TextView = v.findViewById(R.id.value)
            val swatch: View = v.findViewById(R.id.swatch)
            val track: FrameLayout = v.findViewById(R.id.switchTrack)
            val knob: View = v.findViewById(R.id.switchKnob)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
            v.background = BTheme.selector(this@SettingsActivity, 10f, 0, BTheme.WHITE12, accent, null, 2.5f)
            return VH(v)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.label.text = r.label
            h.desc.text = r.desc
            h.desc.visibility = if (r.desc.isEmpty()) View.GONE else View.VISIBLE
            h.value.text = when {
                r.value == null -> ""
                r.onClick != null && r.value.isNotEmpty() -> "${r.value}  ›"
                r.onClick != null -> "›"
                else -> r.value
            }
            h.value.visibility = if (r.switchOn != null) View.GONE else View.VISIBLE
            if (r.swatch != null) {
                h.swatch.visibility = View.VISIBLE
                h.swatch.background = BTheme.rounded(this@SettingsActivity, 8f, r.swatch)
            } else h.swatch.visibility = View.GONE

            if (r.switchOn != null) {
                val on = r.switchOn == true
                h.track.visibility = View.VISIBLE
                h.track.background = BTheme.rounded(this@SettingsActivity, 12f, if (on) accent else BTheme.WHITE18)
                h.knob.background = BTheme.rounded(this@SettingsActivity, 9f, BTheme.WHITE)
                (h.knob.layoutParams as FrameLayout.LayoutParams).gravity =
                    (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                h.knob.requestLayout()
            } else h.track.visibility = View.GONE

            h.itemView.isFocusable = true
            h.itemView.setOnClickListener { r.onClick?.invoke() }
        }
    }
}
