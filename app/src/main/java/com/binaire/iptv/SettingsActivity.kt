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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
        Section("Apparence", R.drawable.ic_sec_look, getString(R.string.set_sec_look), getString(R.string.set_sec_look_sub)),
        Section("Profils", R.drawable.ic_sec_account, getString(R.string.set_sec_profiles), getString(R.string.set_sec_profiles_sub)),
        Section("Compte Binaire", R.drawable.ic_sec_account, getString(R.string.set_sec_account), getString(R.string.set_sec_account_sub)),
        Section("Chaînes & catégories", R.drawable.ic_sec_list, getString(R.string.set_sec_channels), getString(R.string.set_sec_channels_sub)),
        Section("Lecture", R.drawable.ic_sec_play, getString(R.string.set_sec_play), getString(R.string.set_sec_play_sub)),
        Section("Multi-TV", R.drawable.ic_tile_tv, getString(R.string.set_sec_multitv), getString(R.string.set_sec_multitv_sub)),
        Section("Abonnement", R.drawable.ic_sec_info, getString(R.string.set_sec_sub), getString(R.string.set_sec_sub_sub)),
        Section("Mises à jour", R.drawable.ic_sec_update, getString(R.string.set_sec_updates), getString(R.string.set_sec_updates_sub)),
        Section("À propos", R.drawable.ic_sec_info, getString(R.string.set_sec_about), getString(R.string.set_sec_about_sub))
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
        setupHeader(getString(R.string.home_settings))
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
        loadSubs()
        if (::rowAdapter.isInitialized) refreshRows()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("section", current)
    }

    private fun showSection(i: Int) {
        val old = current
        current = i
        val s = sections[i]
        secTitle.text = s.title
        secSub.text = s.sub
        // Mise à jour ciblée : la ligne sélectionnée à la télécommande garde le focus.
        if (old != i) {
            sectionAdapter.notifyItemChanged(old)
            sectionAdapter.notifyItemChanged(i)
        }
        rowAdapter.rows = buildRows(i)
        rowAdapter.notifyDataSetChanged()
    }

    private fun refreshRows() {
        val newRows = buildRows(current)
        val same = newRows.size == rowAdapter.rows.size
        rowAdapter.rows = newRows
        if (same) rowAdapter.notifyItemRangeChanged(0, newRows.size) else rowAdapter.notifyDataSetChanged()
    }

    private fun buildRows(i: Int): List<SettingRow> = when (sections[i].label) {
        "Compte Binaire" -> {
            if (Cloud.isLinked(this)) {
                val subs = cachedSubs
                listOf(
                    SettingRow(getString(R.string.set_account_row), getString(R.string.set_account_row_sub), Cloud.ownerEmail(this) ?: getString(R.string.set_account_connected)),
                    SettingRow(
                        getString(R.string.set_sub_used), getString(R.string.set_sub_used_sub),
                        subs?.firstOrNull { it.id == Cloud.subId(this) }?.name ?: if (subs == null) "…" else "—"
                    ) { pickSub() },
                    SettingRow(getString(R.string.set_sync_now), getString(R.string.set_sync_now_sub), "") { syncNow() },
                    SettingRow(getString(R.string.set_manage_account), getString(R.string.set_manage_account_sub), Cloud.WEB_URL.removePrefix("https://")),
                    SettingRow(getString(R.string.set_disconnect), getString(R.string.set_disconnect_sub), "") { unlinkDevice() }
                )
            } else listOf(
                SettingRow(
                    getString(R.string.set_connect_device), getString(R.string.set_connect_device_sub), ""
                ) { startActivity(Intent(this, ActivateActivity::class.java)) }
            )
        }
        "Apparence" -> listOf(
            SettingRow(getString(R.string.lang_setting_title), getString(R.string.lang_setting_sub), currentLanguageName()) { pickLanguage() },
            SettingRow(getString(R.string.set_accent), getString(R.string.set_accent_sub), BTheme.accentName(this), swatch = accent) { pickAccent() },
            SettingRow(getString(R.string.set_background), getString(R.string.set_background_sub), BTheme.backgroundName(this)) { pickBackground() },
            SettingRow(getString(R.string.set_clock), getString(R.string.set_clock_sub), switchOn = prefs.showClock) {
                prefs.showClock = !prefs.showClock
                recreate()
            }
        )
        "Profils" -> {
            val p = Profiles.current(this)
            val n = Profiles.all(this).size
            listOf(
                SettingRow(getString(R.string.profiles_current), getString(R.string.set_current_profile_sub), p.name) {
                    startActivity(Intent(this, ProfilesActivity::class.java))
                },
                SettingRow(getString(R.string.profiles_manage_title), getString(R.string.set_manage_profiles_sub),
                    if (n > 1) getString(R.string.set_profile_count_plural, n) else getString(R.string.set_profile_count, n)) {
                    startActivity(Intent(this, ProfilesActivity::class.java).putExtra(ProfilesActivity.EXTRA_MANAGE, true))
                },
                SettingRow(getString(R.string.set_ask_who), getString(R.string.set_ask_who_sub),
                    switchOn = Profiles.askOnStart(this)) {
                    Profiles.setAskOnStart(this, !Profiles.askOnStart(this)); refreshRows()
                },
                SettingRow("Code parental", getString(R.string.set_parental_sub),
                    if (Profiles.parentalPin(this) != null) getString(R.string.set_defined) else getString(R.string.set_none)) { parentalCode() },
                SettingRow(getString(R.string.set_notify_episodes), getString(R.string.set_notify_episodes_sub),
                    switchOn = SeriesNotifier.enabled(this)) {
                    SeriesNotifier.setEnabled(this, !SeriesNotifier.enabled(this)); refreshRows()
                }
            )
        }
        "Chaînes & catégories" -> {
            val countries = prefs.allowedCountries
            val countriesLabel = when {
                countries.isEmpty() -> getString(R.string.set_all)
                countries.size <= 2 -> countries.joinToString(", ") { Library.countryName(it) }
                else -> getString(R.string.set_countries_count, countries.size)
            }
            listOf(
                SettingRow(getString(R.string.set_countries), getString(R.string.set_countries_sub), countriesLabel) {
                    startActivity(Intent(this, CountriesActivity::class.java))
                },
                SettingRow(getString(R.string.set_organize_tv), getString(R.string.set_organize_sub), "") { organize(Kind.LIVE) },
                SettingRow(getString(R.string.set_organize_movies), getString(R.string.set_organize_sub), "") { organize(Kind.MOVIE) },
                SettingRow(getString(R.string.set_organize_series), getString(R.string.set_organize_sub), "") { organize(Kind.SERIES) },
                SettingRow(getString(R.string.set_clean_names), getString(R.string.set_clean_names_sub), switchOn = prefs.cleanNames) {
                    prefs.cleanNames = !prefs.cleanNames; rebuildLists()
                },
                SettingRow(getString(R.string.set_merge), getString(R.string.set_merge_sub), switchOn = prefs.mergeDuplicates) {
                    prefs.mergeDuplicates = !prefs.mergeDuplicates; rebuildLists()
                },
                SettingRow(getString(R.string.set_hide_adult), getString(R.string.set_hide_adult_sub), switchOn = prefs.hideAdult) {
                    prefs.hideAdult = !prefs.hideAdult; rebuildLists()
                }
            )
        }
        "Multi-TV" -> listOf(
            SettingRow(getString(R.string.set_join_tv), getString(R.string.set_join_tv_sub), "") { joinMultiTv() },
            SettingRow(
                getString(R.string.set_share_tv),
                getString(R.string.set_share_tv_sub), ""
            ),
            SettingRow(getString(R.string.set_tv_address), getString(R.string.set_tv_address_sub), MultiTv.localIp() ?: getString(R.string.set_no_wifi))
        )
        "Lecture" -> listOf(
            SettingRow(
                getString(R.string.set_quality), getString(R.string.set_quality_sub),
                Library.qualityChoices.firstOrNull { it.first == prefs.preferredQuality }?.second ?: getString(R.string.set_auto)
            ) { pickQuality() },
            SettingRow(
                getString(R.string.set_image_format), getString(R.string.set_image_format_sub),
                BTheme.imageFormatName(prefs.resizeMode)
            ) {
                val keys = BTheme.imageFormats.map { it.first }
                prefs.resizeMode = keys[(keys.indexOf(prefs.resizeMode) + 1) % keys.size]
                refreshRows()
            },
            SettingRow(getString(R.string.set_preview), getString(R.string.set_preview_sub), switchOn = prefs.livePreview) {
                prefs.livePreview = !prefs.livePreview; refreshRows()
            },
            SettingRow(getString(R.string.set_fallback), getString(R.string.set_fallback_sub), switchOn = prefs.autoFallback) {
                prefs.autoFallback = !prefs.autoFallback; refreshRows()
            },
            SettingRow(getString(R.string.set_autonext), getString(R.string.set_autonext_sub), switchOn = prefs.autoNext) {
                prefs.autoNext = !prefs.autoNext; refreshRows()
            },
            SettingRow(
                getString(R.string.set_buffer), getString(R.string.set_buffer_sub),
                if (prefs.largeBuffer) getString(R.string.set_buffer_large) else getString(R.string.set_buffer_normal)
            ) { prefs.largeBuffer = !prefs.largeBuffer; refreshRows() }
        )
        "Abonnement" -> {
            val acc = Session.account
            val exp = acc?.expDate?.takeIf { it > 0 }?.let { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it * 1000)) }
            listOf(
                when {
                    XtreamClient.isM3u(prefs.server) -> SettingRow(getString(R.string.set_type), getString(R.string.set_type_m3u), "Lien M3U · " + (Uri.parse(prefs.server.orEmpty().removePrefix(XtreamClient.M3U_PREFIX)).host ?: ""))
                    StalkerClient.isStalker(prefs.server) -> SettingRow(getString(R.string.set_type), getString(R.string.set_type_stalker), "Portail · " + (Uri.parse(prefs.server.orEmpty().removePrefix(StalkerClient.STALKER_PREFIX)).host ?: ""))
                    else -> SettingRow(getString(R.string.set_server), getString(R.string.set_server_sub), Uri.parse(prefs.server.orEmpty()).host ?: prefs.server.orEmpty())
                },
                SettingRow(getString(R.string.set_status), getString(R.string.set_status_sub), acc?.status?.let { statusFr(it).replaceFirstChar(Char::uppercase) } ?: "—"),
                SettingRow(getString(R.string.set_expiry), getString(R.string.set_expiry_sub), exp ?: getString(R.string.set_no_date)),
                SettingRow(getString(R.string.set_max_conn), getString(R.string.set_max_conn_sub), acc?.maxConnections ?: "—"),
                SettingRow(getString(R.string.set_refresh), getString(R.string.set_refresh_sub), getString(R.string.set_now)) {
                    Session.ready = false
                    EpgCache.clear()
                    startActivity(Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                },
                SettingRow(getString(R.string.splash_change_sub), getString(R.string.set_change_sub_desc), "") { confirmLogout() }
            )
        }
        "Mises à jour" -> listOf(
            SettingRow(getString(R.string.set_version), if (Updater.enabled) getString(R.string.set_version_checked) else getString(R.string.set_version_manual), BuildConfig.VERSION_NAME),
            SettingRow(getString(R.string.set_check_now), getString(R.string.set_check_now_sub), "") { checkUpdate() }
        )
        else -> listOf(
            SettingRow("Binaire IPTV", getString(R.string.set_about_app_sub), BuildConfig.VERSION_NAME),
            SettingRow(getString(R.string.set_clear_cache), getString(R.string.set_clear_cache_sub), "") {
                imageLoader.memoryCache?.clear()
                EpgCache.clear()
                toast(getString(R.string.set_cache_cleared))
            },
            SettingRow(getString(R.string.set_font), getString(R.string.set_font_sub), getString(R.string.set_see)) { showLicense() }
        )
    }

    // ---------- Compte Binaire ----------
    private var cachedSubs: List<Cloud.Sub>? = null

    // Langues proposées : code BCP-47 ("" = langue de l'appareil) et nom affiché (non traduit).
    private val langTags = listOf("", "fr", "de", "es", "it")
    private fun langNames() = listOf(getString(R.string.lang_system), "Français", "Deutsch", "Español", "Italiano")

    private fun currentLanguageName(): String {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').lowercase()
        val i = langTags.indexOf(tag).let { if (it >= 0) it else 0 }
        return langNames()[i]
    }

    private fun pickLanguage() {
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').lowercase()
        val checked = langTags.indexOf(cur).let { if (it >= 0) it else 0 }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_dialog_title))
            .setSingleChoiceItems(langNames().toTypedArray(), checked) { d, w ->
                val tag = langTags[w]
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(tag)
                )
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun loadSubs() {
        if (!Cloud.isLinked(this)) return
        lifecycleScope.launch {
            cachedSubs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { Cloud.subs(this@SettingsActivity) }.getOrNull()
            } ?: emptyList()
            if (::rowAdapter.isInitialized && sections[current].label == "Compte Binaire") refreshRows()
        }
    }

    private fun pickSub() {
        val subs = cachedSubs ?: return
        if (subs.isEmpty()) {
            toast(getString(R.string.set_add_sub_phone, Cloud.WEB_URL.removePrefix("https://")))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_sub_used))
            .setSingleChoiceItems(subs.map { it.name }.toTypedArray(), subs.indexOfFirst { it.id == Cloud.subId(this) }) { d, w ->
                d.dismiss()
                val s = subs[w]
                prefs.saveLogin(XtreamClient.normalize(s.server), s.user, s.pass)
                Cloud.setSubId(this, s.id)
                Session.clear()
                EpgCache.clear()
                startActivity(Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun syncNow() {
        toast(getString(R.string.set_syncing))
        lifecycleScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Sync.pull(this@SettingsActivity) } }
            if (r.isSuccess) {
                toast(getString(R.string.set_synced))
                rebuildWithProgress { recreate() }
            } else toast(getString(R.string.set_sync_failed, r.exceptionOrNull()?.message ?: ""))
        }
    }

    private fun unlinkDevice() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_disconnect_q))
            .setMessage(getString(R.string.set_disconnect_msg))
            .setPositiveButton(getString(R.string.set_disconnect_btn)) { _, _ ->
                lifecycleScope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Cloud.unlink(this@SettingsActivity) }
                    toast(getString(R.string.set_device_disconnected))
                    refreshRows()
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    // ---------- Choix ----------
    private fun parentalCode() {
        val current = Profiles.parentalPin(this)
        if (current == null) {
            newPin(getString(R.string.profiles_parental_code)) { Profiles.setParentalPin(this, it); refreshRows(); toast(getString(R.string.set_parental_saved)) }
            return
        }
        askPin(getString(R.string.set_parental_current), current) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.profiles_parental_code))
                .setItems(arrayOf(getString(R.string.set_change_code), getString(R.string.set_remove_code))) { _, w ->
                    if (w == 0) newPin(getString(R.string.set_new_parental)) { Profiles.setParentalPin(this, it); refreshRows(); toast(getString(R.string.set_code_changed)) }
                    else { Profiles.setParentalPin(this, null); refreshRows(); toast(getString(R.string.set_parental_removed)) }
                }
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show()
        }
    }

    private fun organize(kind: Kind) {
        startActivity(Intent(this, OrganizeActivity::class.java).putExtra(OrganizeActivity.EXTRA_KIND, kind.name))
    }

    /** Applique un réglage de rangement : reconstruit les listes puis rafraîchit l'écran. */
    private fun rebuildLists() {
        refreshRows()
        rebuildWithProgress { toast(getString(R.string.set_list_updated)) }
    }

    private fun pickQuality() {
        val ids = Library.qualityChoices.map { it.first }
        val labels = Library.qualityChoices.map { it.second }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_quality))
            .setSingleChoiceItems(labels, ids.indexOf(prefs.preferredQuality).coerceAtLeast(0)) { d, which ->
                prefs.preferredQuality = ids[which]
                d.dismiss()
                rebuildLists()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun pickAccent() {
        val labels = BTheme.accents.map { (name, color) ->
            SpannableString("●   $name").apply { setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_accent))
            .setSingleChoiceItems(labels, prefs.accentIndex) { d, which ->
                prefs.accentIndex = which
                d.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun pickBackground() {
        val ids = BTheme.backgrounds.map { it.first }
        val labels = BTheme.backgrounds.map { it.second }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_background))
            .setSingleChoiceItems(labels, ids.indexOf(prefs.background).coerceAtLeast(0)) { d, which ->
                prefs.background = ids[which]
                d.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun checkUpdate() {
        if (!Updater.enabled) {
            toast(getString(R.string.set_updates_not_configured))
            return
        }
        toast(getString(R.string.set_searching_update))
        lifecycleScope.launch {
            val r = runCatching { Updater.check() }
            val release = r.getOrNull()
            when {
                r.isFailure -> toast(getString(R.string.set_check_failed))
                release == null -> toast(getString(R.string.set_already_latest, BuildConfig.VERSION_NAME))
                else -> Updater.offer(this@SettingsActivity, lifecycleScope, release)
            }
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.splash_change_sub))
            .setMessage(getString(R.string.set_change_sub_q))
            .setPositiveButton(getString(R.string.set_yes)) { _, _ ->
                prefs.clearLogin()
                Session.clear()
                EpgCache.clear()
                startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showLicense() {
        val text = runCatching { assets.open("OFL-Outfit.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_font))
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- Adaptateurs ----------
    private inner class SectionAdapter : RecyclerView.Adapter<SectionAdapter.VH>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = position.toLong()

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
            h.label.text = s.title
            h.itemView.isSelected = position == current
            h.itemView.setOnFocusChangeListener { _, has -> if (has && current != position) showSection(position) }
            h.itemView.setOnClickListener { showSection(position) }
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        var rows: List<SettingRow> = emptyList()
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = (current * 1000 + position).toLong()

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
