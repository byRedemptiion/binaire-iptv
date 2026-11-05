package com.binaire.iptv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Choix des pays (premier lancement, puis depuis les Réglages). */
class CountriesActivity : BaseActivity() {

    companion object {
        const val EXTRA_FIRST = "first"
    }

    private val selected = HashSet<String>()
    private lateinit var summary: TextView
    private lateinit var adapter: CountryAdapter
    private var first = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectIfNotReady()) return
        first = intent.getBooleanExtra(EXTRA_FIRST, false)
        setContentView(R.layout.activity_countries)
        applyBackground(findViewById(R.id.root))
        setupHeader(if (first) null else "PAYS", showBack = !first)

        val countries = Session.countries
        val totalCats = countries.sumOf { it.categories }
        findViewById<TextView>(R.id.subtitle).text =
            "Ton abonnement contient $totalCats catégories de ${countries.count { it.code != Library.UNKNOWN }} pays. " +
                "Coche ceux qui t'intéressent, le reste sera masqué (modifiable à tout moment dans les Réglages)."

        val saved = prefs.allowedCountries
        if (saved.isNotEmpty()) selected.addAll(saved)
        else {
            val local = Locale.getDefault().country.uppercase(Locale.ROOT).let { if (it == "GB") "UK" else it }
            val codes = countries.map { it.code }.toSet()
            selected.add(if (local in codes) local else "FR")
            if (Library.UNKNOWN in codes) selected.add(Library.UNKNOWN)
        }

        summary = findViewById(R.id.summary)
        val rv: RecyclerView = findViewById(R.id.rvCountries)
        val dm = resources.displayMetrics
        val span = ((dm.widthPixels / dm.density - 80f) / 210f).toInt().coerceIn(2, 5)
        rv.layoutManager = GridLayoutManager(this, span)
        adapter = CountryAdapter(countries)
        rv.adapter = adapter
        rv.itemAnimator = null

        val btnAll: Button = findViewById(R.id.btnAll)
        val btnContinue: Button = findViewById(R.id.btnContinue)
        styleButtons(this, accent, btnContinue, listOf(btnAll))
        btnAll.setOnClickListener { save(emptySet()) }
        btnContinue.setOnClickListener {
            if (selected.isEmpty()) toast("Choisis au moins un pays") else save(selected)
        }
        updateSummary()
        rv.post { rv.getChildAt(0)?.requestFocus() }
    }

    private fun updateSummary() {
        val chosen = Session.countries.filter { it.code in selected }
        summary.text = "Tu garderas ${chosen.sumOf { it.categories }} catégories et ${chosen.sumOf { it.channels }} chaînes"
    }

    private fun save(set: Set<String>) {
        prefs.allowedCountries = set
        prefs.countriesConfigured = true
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { Library.rebuild(prefs) }
            if (first) startActivity(Intent(this@CountriesActivity, HomeActivity::class.java))
            finish()
        }
    }

    private inner class CountryAdapter(val list: List<Library.CountryInfo>) : RecyclerView.Adapter<CountryAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val box: FrameLayout = v.findViewById(R.id.box)
            val check: ImageView = v.findViewById(R.id.check)
            val name: TextView = v.findViewById(R.id.name)
            val info: TextView = v.findViewById(R.id.info)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_country, parent, false))

        override fun getItemCount() = list.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = list[position]
            val on = c.code in selected
            h.name.text = c.name
            h.info.text = "${c.categories} catégories" + if (c.channels > 0) " · ${c.channels} chaînes" else ""
            h.itemView.background = BTheme.selector(
                this@CountriesActivity, 14f,
                if (on) BTheme.withAlpha(accent, 0x47) else BTheme.PANEL,
                if (on) BTheme.withAlpha(accent, 0x80) else 0xFF2A3342.toInt(),
                BTheme.WHITE
            )
            h.box.background = if (on) BTheme.rounded(this@CountriesActivity, 7f, accent)
            else BTheme.rounded(this@CountriesActivity, 7f, 0, 0xFF9AA3B2.toInt(), 2f)
            h.check.visibility = if (on) View.VISIBLE else View.INVISIBLE
            h.itemView.setOnClickListener {
                if (on) selected.remove(c.code) else selected.add(c.code)
                notifyItemChanged(position)
                updateSummary()
            }
        }
    }
}
