package com.binaire.iptv

import java.text.Normalizer
import java.util.Locale

/**
 * Le « grand ménage » : à partir des listes brutes du fournisseur, construit des listes propres.
 * - détecte le pays de chaque catégorie (« FR | SPORT », « |UK| NEWS », drapeau…) ;
 * - nettoie les noms (« FR | CHAÎNE UNE FHD ✦ VIP » → « CHAÎNE UNE » + badge FHD) ;
 * - fusionne les catégories et les chaînes en double (SD/HD/FHD/backup) ;
 * - masque les pays non choisis, les catégories adultes et celles masquées à la main ;
 * - applique les noms et l'ordre personnalisés.
 */
object Library {

    const val UNKNOWN = "??"

    data class CountryInfo(val code: String, val name: String, val categories: Int, val channels: Int)

    /** Catégorie fusionnée, avec ce qu'il faut pour l'écran « Organiser ». */
    data class CatInfo(
        val key: String,
        val id: String,
        val name: String,
        val cleanName: String,
        val rawNames: List<String>,
        val count: Int,
        val country: String,
        val adult: Boolean,
        val hiddenByUser: Boolean,
        val visible: Boolean
    )

    // ------------------------------------------------------------------
    // Pays
    // ------------------------------------------------------------------

    private val countryNames = mapOf(
        "FR" to "France", "BE" to "Belgique", "CH" to "Suisse", "CA" to "Canada / Québec", "LU" to "Luxembourg",
        "UK" to "Royaume-Uni", "IE" to "Irlande", "US" to "États-Unis", "EN" to "Anglais", "AU" to "Australie",
        "ES" to "Espagne", "IT" to "Italie", "DE" to "Allemagne", "AT" to "Autriche", "PT" to "Portugal",
        "BR" to "Brésil", "NL" to "Pays-Bas", "PL" to "Pologne", "RO" to "Roumanie", "GR" to "Grèce",
        "TR" to "Turquie", "RU" to "Russie", "UA" to "Ukraine", "AL" to "Albanie", "EXYU" to "Ex-Yougoslavie",
        "AR" to "Pays arabes", "MA" to "Maroc", "DZ" to "Algérie", "TN" to "Tunisie", "EG" to "Égypte",
        "AF" to "Afrique", "IN" to "Inde", "PK" to "Pakistan", "IR" to "Iran", "KU" to "Kurde",
        "CN" to "Chine", "JP" to "Japon", "KR" to "Corée", "SE" to "Suède", "NO" to "Norvège",
        "DK" to "Danemark", "FI" to "Finlande", "HU" to "Hongrie", "CZ" to "Tchéquie", "SK" to "Slovaquie",
        "BG" to "Bulgarie", "HR" to "Croatie", "RS" to "Serbie", "LAT" to "Amérique latine", "MX" to "Mexique",
        "CARIB" to "Caraïbes", "IL" to "Israël", "AM" to "Arménie", "VN" to "Vietnam", "TH" to "Thaïlande",
        "PH" to "Philippines", UNKNOWN to "Autres / sans pays"
    )

    private val aliases = mapOf(
        "GB" to "UK", "USA" to "US", "QC" to "CA", "AFR" to "AF", "ARA" to "AR", "ARB" to "AR", "ENG" to "EN",
        "FRA" to "FR", "ESP" to "ES", "ITA" to "IT", "GER" to "DE", "DEU" to "DE", "POR" to "PT", "BRA" to "BR",
        "TUR" to "TR", "NED" to "NL", "POL" to "PL", "ALB" to "AL", "EX-YU" to "EXYU", "YU" to "EXYU", "KR" to "KR",
        "LATAM" to "LAT", "LATINO" to "LAT", "IND" to "IN"
    )

    private val countryWords = mapOf(
        "FRANCE" to "FR", "FRENCH" to "FR", "BELGIQUE" to "BE", "BELGIUM" to "BE", "SUISSE" to "CH",
        "SWITZERLAND" to "CH", "QUEBEC" to "CA", "CANADA" to "CA", "USA" to "US", "ARABIC" to "AR", "ARAB" to "AR",
        "ARABE" to "AR", "SPAIN" to "ES", "ESPANA" to "ES", "ITALY" to "IT", "ITALIA" to "IT", "GERMANY" to "DE",
        "DEUTSCHLAND" to "DE", "PORTUGAL" to "PT", "AFRICA" to "AF", "AFRIQUE" to "AF", "TURKEY" to "TR",
        "TURKIYE" to "TR", "MAROC" to "MA", "MOROCCO" to "MA", "ALGERIE" to "DZ", "ALGERIA" to "DZ",
        "TUNISIE" to "TN", "TUNISIA" to "TN", "BRAZIL" to "BR", "BRASIL" to "BR", "NETHERLANDS" to "NL",
        "HOLLAND" to "NL", "POLAND" to "PL", "POLSKA" to "PL", "INDIA" to "IN", "LATINO" to "LAT"
    )

    /** Préfixes qui ne sont pas des pays. */
    private val notCountry = setOf(
        "VIP", "HD", "FHD", "UHD", "SD", "TV", "VOD", "PPV", "ALL", "TOP", "HOT", "XXX", "NEW", "MY", "THE",
        "LA", "LE", "LES", "UN", "UNE", "DES", "BEIN", "RMC", "BFM", "TF", "M6", "W9", "LCI", "TMC", "CNN",
        "BBC", "HBO", "MBC", "OSN", "ART", "ESPN", "FOX", "SKY", "NBA", "NFL", "NHL", "MLB", "UFC", "WWE",
        "MOVIE", "MOVIES", "FILM", "FILMS", "KIDS", "NEWS", "SPORT", "LIVE", "EPL", "DAZN", "MAX", "PLUS", "CINE"
    )

    fun countryName(code: String): String = countryNames[code] ?: code

    private val prefixRegex = Regex("""^[\s|\[({]*([A-Za-z]{2,5}(?:-[A-Za-z]{2})?)\s*([|:\])}\-–—»>#/•●▶★✦◉]+|\s)""")

    /** Code pays d'un nom de catégorie (ou de chaîne), UNKNOWN si introuvable. */
    fun detectCountry(raw: String): String {
        val s = raw.trim()
        // Drapeau emoji (deux « indicateurs régionaux »).
        if (s.length >= 4) {
            val a = Character.codePointAt(s, 0)
            val b = Character.codePointAt(s, Character.charCount(a))
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val code = "${'A' + (a - 0x1F1E6)}${'A' + (b - 0x1F1E6)}"
                return aliases[code] ?: code
            }
        }
        val m = prefixRegex.find(s)
        if (m != null) {
            val token = m.groupValues[1].uppercase(Locale.ROOT)
            val sep = m.groupValues[2]
            val code = aliases[token] ?: token
            val known = countryNames.containsKey(code)
            if (known) return code
            if (sep.isNotBlank() && token !in notCountry && token.length <= 4 && m.groupValues[1].all { it.isUpperCase() || it == '-' }) {
                return code
            }
        }
        val firstWord = s.split(Regex("""[\s|:\-]+""")).firstOrNull { it.isNotBlank() }?.let { stripAccents(it).uppercase(Locale.ROOT) }
        if (firstWord != null) countryWords[firstWord]?.let { return it }
        return UNKNOWN
    }

    // ------------------------------------------------------------------
    // Nettoyage des noms
    // ------------------------------------------------------------------

    private val qualityRegex = Regex(
        """(?i)(?<![\p{L}\d])(4K|UHD|FHD|HD|SD|HQ|LQ|HEVC|H\.?26[45]|2160[PI]?|1080[PI]?|720[PI]?|576[PI]?|50\s?FPS|60\s?FPS|HDR10?|DOLBY|ATMOS|RAW)(?![\p{L}\d])"""
    )
    private val tagRegex = Regex(
        """(?i)(?<![\p{L}\d])(VIP|BACK\s?UP|BKP|BK|PREMIUM|PRIME|LOW|MULTI\s?SUB|MULTI|VOSTFR|VOST|VFF|VFQ|VF|VO|DUAL)(?![\p{L}\d])"""
    )
    private val superscript = Regex("""[ᴬ-ᵪʰ-˿⁰-₟ᶜ-ᶿ]+""")
    private val symbols = Regex("""[\p{So}\p{Cn}•●○■□▶►★☆✦✧◉]+""")
    private val yearRegex = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val trailingYear = Regex("""\s[-–]\s*((?:19|20)\d{2})\s*$""")
    private val parenTag = Regex("""\s*[(\[]\s*([A-Z]{2,5}|MULTI|VOSTFR|VF|VO|4K|FHD|HD)\s*[)\]]""")
    private val adultRegex = Regex("""(?i)(xxx|adult|adulte|\+\s?18|18\s?\+|porn|for\s?adults|sexy|hot\s?girls|erotic|érotique)""")

    data class Cleaned(val name: String, val quality: String?, val year: String?, val backup: Boolean)

    fun isAdult(raw: String) = adultRegex.containsMatchIn(raw)

    private fun superscriptQuality(s: String): String? {
        val m = superscript.find(s)?.value ?: return null
        return when {
            m.contains("ᵁ") -> "UHD"
            m.contains("ᶠ") -> "FHD"
            m.contains("⁴") -> "4K"
            m.contains("ˢ") -> "SD"
            m.contains("ᴴ") -> "HD"
            else -> null
        }
    }

    private fun normalizeQuality(q: String): String {
        val u = q.uppercase(Locale.ROOT).replace(" ", "")
        return when {
            u == "UHD" || u.startsWith("2160") -> "4K"
            u.startsWith("1080") -> "FHD"
            u.startsWith("720") -> "HD"
            u.startsWith("576") -> "SD"
            u.startsWith("H26") || u == "HEVC" -> "HEVC"
            else -> u
        }
    }

    private fun stripPrefix(s: String): String {
        val m = prefixRegex.find(s) ?: return s
        val token = m.groupValues[1].uppercase(Locale.ROOT)
        val code = aliases[token] ?: token
        val isCountry = countryNames.containsKey(code) ||
            (m.groupValues[2].isNotBlank() && token !in notCountry && token.length <= 4 && m.groupValues[1].all { it.isUpperCase() || it == '-' })
        return if (isCountry) s.substring(m.range.last + 1) else s
    }

    private fun tidy(s: String): String =
        s.replace(Regex("""\s{2,}"""), " ")
            .replace(Regex("""\(\s*\)|\[\s*]"""), "")
            .trim()
            .trim('-', '–', '—', '|', ':', '_', '.', ',', '/', '#', '*', '+', ' ')
            .trim()

    /** Nettoie un nom de chaîne, de film ou de série. */
    fun cleanTitle(raw: String, kind: Kind): Cleaned {
        var s = raw
        val qualities = mutableListOf<String>()
        superscriptQuality(s)?.let { qualities.add(it) }
        s = superscript.replace(s, " ")
        s = symbols.replace(s, " ")
        s = stripPrefix(s.trim())
        // Deuxième préfixe éventuel (« FR | VIP | … »).
        s = stripPrefix(s.trim())

        var year: String? = null
        if (kind != Kind.LIVE) {
            yearRegex.find(s)?.let { year = it.groupValues[1]; s = s.replace(it.value, " ") }
            if (year == null) trailingYear.find(s)?.let { year = it.groupValues[1]; s = s.substring(0, it.range.first) }
        }
        val backup = Regex("""(?i)(?<![\p{L}])(BACK\s?UP|BKP|BK)(?![\p{L}])""").containsMatchIn(s)
        val lang = Regex("""(?i)(?<![\p{L}])(VOSTFR|MULTI)(?![\p{L}])""").find(s)?.value?.uppercase(Locale.ROOT)

        s = parenTag.replace(s, " ")
        qualityRegex.findAll(s).forEach { qualities.add(normalizeQuality(it.value)) }
        s = qualityRegex.replace(s, " ")
        s = tagRegex.replace(s, " ")
        s = tidy(s)
        if (s.length < 2) s = tidy(symbols.replace(raw, " "))

        val quality = when {
            kind != Kind.LIVE && lang != null -> lang
            "4K" in qualities -> "4K"
            "FHD" in qualities -> "FHD"
            "HD" in qualities -> "HD"
            "SD" in qualities -> "SD"
            "HEVC" in qualities -> "HEVC"
            else -> null
        }
        return Cleaned(s, quality, year, backup)
    }

    /** Nettoie un nom de catégorie (« FR | SPORT HD VIP » → « Sport »). */
    fun cleanCategory(raw: String): String {
        var s = symbols.replace(superscript.replace(raw, " "), " ")
        s = stripPrefix(s.trim())
        s = stripPrefix(s.trim())
        s = qualityRegex.replace(s, " ")
        s = tagRegex.replace(s, " ")
        s = tidy(s)
        if (s.length < 2) return tidy(raw)
        // TOUT EN MAJUSCULES → Une Majuscule Par Mot (sauf les sigles courts).
        val letters = s.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) {
            s = s.split(" ").joinToString(" ") { w ->
                if (w.length <= 3 && w.any { it.isLetter() }) w
                else w.lowercase(Locale.FRANCE).replaceFirstChar { it.titlecase(Locale.FRANCE) }
            }
        }
        return s
    }

    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")

    /** Clé de comparaison : « Chaîne Une » et « CHAINE  UNE » donnent la même. */
    fun normKey(s: String): String =
        stripAccents(s).lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9+]"""), "")

    val qualityChoices = listOf("auto" to "Automatique (la meilleure)", "4K" to "4K / UHD", "FHD" to "Full HD (1080p)", "HD" to "HD (720p)", "SD" to "SD (économie de données)")

    /** Score d'une version selon la qualité préférée : identique > juste en dessous > au-dessus. */
    private fun qualityScore(q: String?, backup: Boolean, preferred: String): Double {
        val v = qualityRank(q, false)
        val base = if (preferred == "auto") v else {
            val t = qualityRank(preferred, false)
            when {
                v == t -> 100.0
                v < t -> 50.0 + v
                else -> 10.0 - v
            }
        }
        return if (backup) base - 0.7 else base
    }

    private fun qualityRank(q: String?, backup: Boolean): Double {
        val base = when (q) {
            "4K" -> 4.0
            "FHD" -> 3.0
            "HD" -> 2.0
            null, "HEVC" -> 1.5
            "SD" -> 1.0
            else -> 1.5
        }
        return if (backup) base - 0.7 else base
    }

    // ------------------------------------------------------------------
    // Construction des listes
    // ------------------------------------------------------------------

    /** Reconstruit toutes les listes affichées à partir des listes brutes et des réglages. */
    fun rebuild(prefs: Prefs) {
        val allowed = prefs.allowedCountries
        val clean = prefs.cleanNames
        val merge = prefs.mergeDuplicates
        val hideAdult = prefs.hideAdult
        val preferred = prefs.preferredQuality

        // Pays présents (pour l'écran de choix).
        val catCount = HashMap<String, Int>()
        val chanCount = HashMap<String, Int>()
        for (kind in Kind.values()) {
            val countryOf = HashMap<String, String>()
            for (c in Session.rawCategories[kind].orEmpty()) {
                val code = detectCountry(c.name)
                countryOf[c.id] = code
                catCount[code] = (catCount[code] ?: 0) + 1
            }
            if (kind == Kind.LIVE) {
                for (it in Session.rawStreams[kind].orEmpty()) {
                    val code = countryOf[it.categoryId] ?: UNKNOWN
                    chanCount[code] = (chanCount[code] ?: 0) + 1
                }
            }
        }
        Session.countries = catCount.keys
            .map { CountryInfo(it, countryName(it), catCount[it] ?: 0, chanCount[it] ?: 0) }
            .sortedWith(compareBy<CountryInfo> { it.code == UNKNOWN }.thenByDescending { it.categories + it.channels / 20 })

        for (kind in Kind.values()) build(kind, prefs, allowed, clean, merge, hideAdult, preferred)
    }

    /** Reconstruit un seul type (plus rapide, après une modification dans « Organiser »). */
    fun rebuildKind(prefs: Prefs, kind: Kind) {
        build(kind, prefs, prefs.allowedCountries, prefs.cleanNames, prefs.mergeDuplicates, prefs.hideAdult, prefs.preferredQuality)
    }

    private class MCat(
        val key: String,
        val id: String,
        val cleanName: String,
        val country: String,
        val adult: Boolean,
        val rawIds: MutableList<String> = mutableListOf(),
        val rawNames: MutableList<String> = mutableListOf()
    )

    private fun build(kind: Kind, prefs: Prefs, allowed: Set<String>, clean: Boolean, merge: Boolean, hideAdult: Boolean, preferred: String) {
        val rawCats = Session.rawCategories[kind].orEmpty()
        val rawItems = Session.rawStreams[kind].orEmpty()
        val hidden = prefs.hiddenCats(kind)
        val renames = prefs.catRenames(kind)
        val order = prefs.catOrder(kind)

        // 1. Catégories fusionnées (même pays + même nom nettoyé).
        val merged = LinkedHashMap<String, MCat>()
        val rawToKey = HashMap<String, String>()
        for (c in rawCats) {
            val country = detectCountry(c.name)
            val cleanName = if (clean) cleanCategory(c.name) else c.name.trim()
            val key = "$country|" + (if (clean && merge) normKey(cleanName) else normKey(c.name) + "#" + c.id)
            val m = merged.getOrPut(key) { MCat(key, c.id, cleanName, country, isAdult(c.name)) }
            m.rawIds.add(c.id)
            m.rawNames.add(c.name)
            rawToKey[c.id] = key
        }

        fun visible(m: MCat) =
            (allowed.isEmpty() || m.country in allowed) && !(hideAdult && m.adult) && m.key !in hidden

        val visibleKeys = merged.values.filter(::visible).map { it.key }.toHashSet()
        val orphanVisible = allowed.isEmpty() || UNKNOWN in allowed

        // 2. Éléments : on garde ceux d'une catégorie visible, nom nettoyé.
        val counts = HashMap<String, Int>()
        val kept = ArrayList<Pair<Item, Cleaned>>(rawItems.size)
        val countryOfKept = ArrayList<String>(rawItems.size)
        for (it in rawItems) {
            val key = rawToKey[it.categoryId]
            if (key == null) {
                if (!orphanVisible) continue
            } else if (key !in visibleKeys) continue
            val cl = if (clean) cleanTitle(it.name, kind) else Cleaned(it.name.trim(), null, null, false)
            val catId = key?.let { k -> merged[k]?.id } ?: ""
            kept.add(it.copy(name = cl.name, categoryId = catId, quality = cl.quality, year = cl.year) to cl)
            countryOfKept.add(key?.let { k -> merged[k]?.country } ?: UNKNOWN)
            if (key != null) counts[key] = (counts[key] ?: 0) + 1
        }

        // 3. Chaînes en double : une seule ligne, la meilleure version en premier.
        val items: List<Item> = if (kind == Kind.LIVE && merge && clean) {
            val groups = LinkedHashMap<String, MutableList<Pair<Item, Cleaned>>>()
            for ((i, p) in kept.withIndex()) {
                val k = countryOfKept[i] + "|" + normKey(p.second.name)
                groups.getOrPut(k) { mutableListOf() }.add(p)
            }
            groups.values.map { g ->
                val sorted = g.sortedByDescending { qualityScore(it.second.quality, it.second.backup, preferred) }
                val best = sorted.first().first
                if (sorted.size == 1) best
                else best.copy(
                    cats = sorted.map { it.first.categoryId }.filter { it.isNotEmpty() }.toSet(),
                    variants = sorted.drop(1).map { it.first.id }.distinct()
                )
            }
        } else kept.map { it.first }

        // 4. Infos de catégories (pour « Organiser ») et liste ordonnée des visibles.
        val idToKey = merged.values.associate { it.id to it.key }
        val liveCounts = HashMap<String, Int>()
        if (kind == Kind.LIVE) {
            for (i in items) {
                val ids = if (i.cats.isEmpty()) setOf(i.categoryId) else i.cats
                for (id in ids) idToKey[id]?.let { k -> liveCounts[k] = (liveCounts[k] ?: 0) + 1 }
            }
        }
        val rank = order.withIndex().associate { it.value to it.index }
        val infos = merged.values.map { m ->
            CatInfo(
                key = m.key,
                id = m.id,
                name = renames[m.key] ?: m.cleanName,
                cleanName = m.cleanName,
                rawNames = m.rawNames,
                count = if (kind == Kind.LIVE) liveCounts[m.key] ?: 0 else counts[m.key] ?: 0,
                country = m.country,
                adult = m.adult,
                hiddenByUser = m.key in hidden,
                visible = m.key in visibleKeys
            )
        }.withIndex().sortedWith(compareBy({ rank[it.value.key] ?: (100000 + it.index) })).map { it.value }

        Session.catInfo[kind] = infos
        Session.categories[kind] = infos.filter { it.visible && it.count > 0 }.map { Category(it.id, it.name) }
        Session.streams[kind] = items
    }
}
