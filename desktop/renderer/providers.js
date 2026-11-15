// Couche d'accès aux abonnements : Xtream, lien M3U, portail Stalker/MAG.
// Toutes les requêtes passent par le processus principal (window.binaire.httpGet) :
// en-têtes libres (Cookie/User-Agent pour Stalker) et pas de blocage CORS.

const KIND = { LIVE: "live", MOVIE: "movie", SERIES: "series" };
const M3U_PREFIX = "m3u:", STALKER_PREFIX = "stalker:";

async function getJson(url, headers) {
  const r = await window.binaire.httpGet(url, headers);
  if (!r.ok) throw new Error(r.error || ("HTTP " + r.status));
  try { return JSON.parse(r.body); } catch (e) { throw new Error("Réponse illisible"); }
}
async function getText(url, headers) {
  const r = await window.binaire.httpGet(url, headers);
  if (!r.ok) throw new Error(r.error || ("HTTP " + r.status));
  return r.body;
}

// ---------- Xtream ----------
class XtreamProvider {
  constructor(sub) {
    this.base = (sub.server || "").replace(/\/+$/, "");
    this.user = sub.user; this.pass = sub.pass;
  }
  api(params) {
    const u = encodeURIComponent(this.user), p = encodeURIComponent(this.pass);
    return getJson(`${this.base}/player_api.php?username=${u}&password=${p}&${params}`);
  }
  async login() { await this.api("action=get_live_categories"); return { ok: true }; }

  async categories(kind) {
    const action = kind === KIND.LIVE ? "get_live_categories" : kind === KIND.MOVIE ? "get_vod_categories" : "get_series_categories";
    const arr = await this.api("action=" + action);
    return (Array.isArray(arr) ? arr : []).map((c) => ({ id: String(c.category_id), name: c.category_name || "Catégorie" }));
  }
  async streams(kind, catId) {
    if (kind === KIND.LIVE) {
      const a = await this.api("action=get_live_streams&category_id=" + encodeURIComponent(catId));
      return a.map((s) => ({ kind, id: String(s.stream_id), name: s.name, icon: s.stream_icon, num: s.num }));
    }
    if (kind === KIND.MOVIE) {
      const a = await this.api("action=get_vod_streams&category_id=" + encodeURIComponent(catId));
      return a.map((s) => ({ kind, id: String(s.stream_id), name: s.name, icon: s.stream_icon, ext: s.container_extension || "mp4", rating: s.rating }));
    }
    const a = await this.api("action=get_series&category_id=" + encodeURIComponent(catId));
    return a.map((s) => ({ kind, id: String(s.series_id), name: s.name, icon: s.cover, rating: s.rating }));
  }
  // Tout le contenu de la section (sans filtrer par catégorie) pour la recherche globale.
  async allStreams(kind) {
    if (kind === KIND.LIVE) { const a = await this.api("action=get_live_streams"); return a.map((s) => ({ kind, id: String(s.stream_id), name: s.name, icon: s.stream_icon })); }
    if (kind === KIND.MOVIE) { const a = await this.api("action=get_vod_streams"); return a.map((s) => ({ kind, id: String(s.stream_id), name: s.name, icon: s.stream_icon, ext: s.container_extension || "mp4" })); }
    const a = await this.api("action=get_series"); return a.map((s) => ({ kind, id: String(s.series_id), name: s.name, icon: s.cover }));
  }
  async seriesInfo(id) {
    const info = await this.api("action=get_series_info&series_id=" + encodeURIComponent(id));
    const eps = info.episodes || {};
    const seasons = Object.keys(eps).sort((a, b) => (+a) - (+b)).map((season) => ({
      season,
      episodes: (eps[season] || []).map((e) => ({
        kind: KIND.SERIES, id: String(e.id), name: e.title || ("Épisode " + e.episode_num),
        ext: e.container_extension || "mp4", image: (e.info && e.info.movie_image) || null,
        number: e.episode_num
      }))
    }));
    return { cover: (info.info && info.info.cover) || null, plot: info.info && info.info.plot, seasons };
  }
  resolve(kind, item) {
    const u = `${encodeURIComponent(this.user)}/${encodeURIComponent(this.pass)}`;
    if (kind === KIND.LIVE) return { ts: `${this.base}/live/${u}/${item.id}.ts`, hls: `${this.base}/live/${u}/${item.id}.m3u8`, live: true };
    if (kind === KIND.MOVIE) return { url: `${this.base}/movie/${u}/${item.id}.${item.ext || "mp4"}`, live: false };
    return { url: `${this.base}/series/${u}/${item.id}.${item.ext || "mp4"}`, live: false };
  }
}

// ---------- Lien M3U ----------
class M3uProvider {
  constructor(sub) { this.link = (sub.server || "").replace(M3U_PREFIX, ""); this.loaded = false; }
  async login() { await this.load(); return { ok: true }; }

  async load() {
    if (this.loaded) return;
    const text = await getText(this.link);
    const cats = { live: new Map(), movie: new Map(), series: new Map() };
    const items = { live: [], movie: [], series: [] };
    const seriesEntries = new Map(); // seriesKey -> {item, episodes:[]}
    const lines = text.split(/\r?\n/);
    let name = null, logo = null, group = null, n = 0;
    const SXXEXX = /(?:^|[\s._\-|(\[])S(\d{1,2})\s*[ ._-]?\s*E(\d{1,3})(?![0-9])/i;
    const VIDEO = /\.(mp4|mkv|avi|mov|m4v|wmv|webm|flv|mpg|mpeg)(\?|$)/i;
    for (const raw of lines) {
      const line = raw.trim();
      if (!line) continue;
      if (/^#EXTINF/i.test(line)) {
        const attrs = {}; const re = /([\w-]+)="([^"]*)"/g; let m;
        while ((m = re.exec(line))) attrs[m[1].toLowerCase()] = m[2];
        const comma = line.indexOf(",", line.lastIndexOf('"'));
        name = (comma >= 0 ? line.slice(comma + 1) : "").trim() || attrs["tvg-name"] || "Sans nom";
        logo = attrs["tvg-logo"] || null; group = attrs["group-title"] || "Sans catégorie";
      } else if (!line.startsWith("#")) {
        n++;
        if (!name) name = "Sans nom";
        if (!group) group = "Sans catégorie";
        const url = line, path = url.split("?")[0].toLowerCase();
        const ep = SXXEXX.exec(name || "");
        const kind = (path.includes("/series/") || (ep && !path.includes("/live/"))) ? "series"
          : (path.includes("/movie/") || VIDEO.test(path) || /\b(vod|films?|movies?)\b/i.test(group)) && !path.includes("/live/") ? "movie"
          : "live";
        const g = group || "Sans catégorie";
        if (kind === "series") {
          const show = (ep ? (name || "").slice(0, ep.index) : name).trim().replace(/[-|:._([]+$/, "").trim() || name;
          const key = g + "|" + show.toLowerCase();
          if (!cats.series.has(g)) cats.series.set(g, "s" + cats.series.size);
          let entry = seriesEntries.get(key);
          if (!entry) {
            entry = { item: { kind: "series", id: "s" + n, name: show, icon: logo, catId: cats.series.get(g) }, episodes: [] };
            seriesEntries.set(key, entry); items.series.push(entry.item);
          }
          entry.episodes.push({ kind: "series", id: "e" + n, number: ep ? ep[2] : String(entry.episodes.length + 1), season: ep ? ep[1] : "1", name: name, url });
        } else {
          if (!cats[kind].has(g)) cats[kind].set(g, (kind[0]) + cats[kind].size);
          items[kind].push({ kind, id: (kind === "live" ? "l" : "m") + n, name, icon: logo, catId: cats[kind].get(g), url });
        }
      }
    }
    this._cats = { live: [...cats.live].map(([name, id]) => ({ id, name })), movie: [...cats.movie].map(([name, id]) => ({ id, name })), series: [...cats.series].map(([name, id]) => ({ id, name })) };
    this._items = items; this._series = seriesEntries;
    this.loaded = true;
  }
  async categories(kind) { await this.load(); return this._cats[kind]; }
  async streams(kind, catId) { await this.load(); return this._items[kind].filter((i) => i.catId === catId); }
  async allStreams(kind) { await this.load(); return this._items[kind].slice(); }
  async seriesInfo(id) {
    await this.load();
    let eps = [];
    for (const e of this._series.values()) if (e.item.id === id) { eps = e.episodes; break; }
    const bySeason = {};
    for (const e of eps) (bySeason[e.season] = bySeason[e.season] || []).push(e);
    return { seasons: Object.keys(bySeason).sort((a, b) => (+a) - (+b)).map((s) => ({ season: s, episodes: bySeason[s] })) };
  }
  resolve(kind, item) { return kind === KIND.LIVE ? { ts: item.url, hls: item.url, live: true } : { url: item.url, live: false }; }
}

// ---------- Portail Stalker / MAG ----------
const STB_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";
const KNOWN_PREFIXES = new Set(["ffmpeg", "auto", "mpegts", "varied", "vlc", "play", "streamer"]);

function normalizePlayUrl(cmd) {
  if (!cmd || !cmd.trim()) return null;
  let s = cmd.trim();
  const i = s.indexOf(" ");
  if (i > 0 && KNOWN_PREFIXES.has(s.slice(0, i).toLowerCase())) s = s.slice(i + 1).trim();
  const tok = s.split(" ").find((t) => /^https?:\/\//i.test(t)) || s;
  return /^https?:\/\//i.test(tok) ? tok.trim() : null;
}
function paginationDone(cnt, per, loaded, total, idx) {
  if (cnt === 0 || idx >= 60) return true;
  if (!(per >= 1 && per <= cnt - 1) && per > 0 && cnt < per) return true;
  if (total >= 1 && total <= loaded) return true;
  return false;
}

class StalkerProvider {
  constructor(sub) {
    this.portal = (sub.server || "").replace(STALKER_PREFIX, "").replace(/\/+$/, "");
    this.mac = sub.user; this.token = null; this.api = null; this.cmds = new Map();
  }
  origin() { const u = new URL(this.portal); return `${u.protocol}//${u.host}`; }
  headers() {
    const h = { "User-Agent": STB_UA, "Cookie": `mac=${encodeURIComponent(this.mac)}; stb_lang=fr; timezone=Europe/Paris`, "Referer": this.origin() + "/c/", "Accept": "*/*" };
    if (this.token) h["Authorization"] = "Bearer " + this.token;
    return h;
  }
  async raw(params) {
    const r = await window.binaire.httpGet(`${this.api}?${params}&JsHttpRequest=1-xml`, this.headers());
    if (r.status === 401 || r.status === 403) return { kind: "expired" };
    if (!r.ok) throw new Error("Portail : HTTP " + r.status);
    let j; try { j = JSON.parse(r.body); } catch (e) { return { kind: "error", msg: "illisible" }; }
    const js = j.js;
    if (js && typeof js === "object" && !Array.isArray(js)) {
      if (js.block === 1) return { kind: "blocked" };
      const err = (js.error || js.msg || "").toString();
      if (err) {
        const m = err.toLowerCase();
        if (m.includes("token") && (m.includes("valid") || m.includes("expire"))) return { kind: "expired" };
        if (m.includes("block") || m.includes("expired") || m.includes("ban")) return { kind: "blocked" };
        return { kind: "error", msg: err };
      }
    }
    if (typeof js === "string" && js) return { kind: "error", msg: js };
    return { kind: "ok", js };
  }
  async ensure() {
    if (this.token && this.api) return;
    const o = this.origin();
    const candidates = [`${o}/portal.php`, `${o}/server/load.php`, `${o}/stalker_portal/server/load.php`, `${this.portal}/server/load.php`];
    let last = null;
    for (const c of candidates) {
      this.api = c;
      try {
        const hs = await this.raw("type=stb&action=handshake&token=");
        if (hs.kind === "blocked") throw new Error("Appareil bloqué ou abonnement expiré.");
        const t = hs.js && hs.js.token;
        if (!t) { last = "pas de jeton"; this.token = null; continue; }
        this.token = t;
        const prof = await this.raw("type=stb&action=get_profile&hd=1&stb_type=MAG250&client_type=STB&not_valid_token=0");
        if (prof.kind === "blocked") throw new Error("Appareil bloqué ou abonnement expiré.");
        if (prof.kind === "expired") { this.token = null; last = "profil refusé"; continue; }
        return;
      } catch (e) { last = e.message; this.token = null; if ((e.message || "").includes("bloqué")) throw e; }
    }
    throw new Error(last || "Connexion au portail impossible.");
  }
  async call(params, reauth = true) {
    await this.ensure();
    let r = await this.raw(params);
    if (r.kind === "expired" && reauth) { this.token = null; this.api = null; await this.ensure(); r = await this.raw(params); }
    if (r.kind === "ok") return r.js;
    if (r.kind === "blocked") throw new Error("Portail : appareil bloqué ou abonnement expiré.");
    throw new Error("Portail : " + (r.msg || "réponse inattendue"));
  }
  async login() { await this.ensure(); return { ok: true }; }

  async categories(kind) {
    const a = kind === KIND.LIVE ? ["itv", "get_genres"] : kind === KIND.MOVIE ? ["vod", "get_categories"] : ["series", "get_categories"];
    const js = await this.call(`type=${a[0]}&action=${a[1]}`);
    const arr = Array.isArray(js) ? js : (js && js.data) || [];
    const seen = new Set(), out = [];
    for (const c of arr) { const id = String(c.id || ""); if (!id || id === "*" || id === "0" || seen.has(id)) continue; seen.add(id); out.push({ id, name: c.title || c.name || "Catégorie" }); }
    return out;
  }
  async paginate(baseParams, onItem) {
    let page = 1, loaded = 0;
    while (true) {
      const js = await this.call(`${baseParams}&p=${page}`);
      const arr = (js && js.data) || null;
      if (!arr) break;
      for (const o of arr) onItem(o);
      loaded += arr.length;
      const total = (js.total_items | 0), per = (js.max_page_items | 0) || arr.length;
      if (paginationDone(arr.length, per, loaded, total, page)) break;
      page++;
    }
  }
  async streams(kind, catId) {
    if (kind === KIND.SERIES) {
      const out = [], seen = new Set();
      await this.paginate(`type=series&action=get_ordered_list&category=${catId}`, (o) => {
        const id = String(o.id || ""); if (!id || seen.has(id)) return; seen.add(id);
        if (o.cmd) this.cmds.set("series:" + id, o.cmd);
        out.push({ kind, id, name: o.name || "Série", icon: this.abs(o.screenshot_uri || o.logo) });
      });
      return out;
    }
    const type = kind === KIND.LIVE ? "itv" : "vod";
    const out = [], seen = new Set();
    await this.paginate(`type=${type}&action=get_ordered_list&genre=${catId}&category=${catId}`, (o) => {
      const id = String(o.id || ""); if (!id || seen.has(id)) return; seen.add(id);
      if (o.cmd) this.cmds.set(kind + ":" + id, o.cmd);
      out.push({ kind, id, name: o.name || o.o_name || "Sans nom", icon: this.abs(o.logo || o.screenshot_uri), ext: kind === KIND.MOVIE ? "mp4" : null });
    });
    return out;
  }
  async allStreams(kind) {
    if (kind === KIND.SERIES) {
      const out = [], seen = new Set();
      await this.paginate("type=series&action=get_ordered_list&category=*", (o) => {
        const id = String(o.id || ""); if (!id || seen.has(id)) return; seen.add(id);
        if (o.cmd) this.cmds.set("series:" + id, o.cmd);
        out.push({ kind, id, name: o.name || "Série", icon: this.abs(o.screenshot_uri || o.logo) });
      });
      return out;
    }
    const type = kind === KIND.LIVE ? "itv" : "vod";
    const out = [], seen = new Set();
    await this.paginate(`type=${type}&action=get_ordered_list&genre=*&category=*`, (o) => {
      const id = String(o.id || ""); if (!id || seen.has(id)) return; seen.add(id);
      if (o.cmd) this.cmds.set(kind + ":" + id, o.cmd);
      out.push({ kind, id, name: o.name || o.o_name || "Sans nom", icon: this.abs(o.logo || o.screenshot_uri), ext: kind === KIND.MOVIE ? "mp4" : null });
    });
    return out;
  }
  async seriesInfo(seriesId) {
    // Structure plate : entrées avec tableau "series" (numéros d'épisodes).
    const flat = await this.call(`type=series&action=get_ordered_list&movie_id=${seriesId}&p=1`);
    const bySeason = {};
    let foundFlat = false;
    for (const o of (flat && flat.data) || []) {
      if (!Array.isArray(o.series)) continue;
      foundFlat = true;
      const season = String(o.season_number || "1"), cmd = o.cmd || null;
      for (const num of o.series) {
        const id = `${seriesId}:s${season}:e${num}`;
        if (cmd) this.cmds.set("series:" + id, cmd + "|series=" + num);
        (bySeason[season] = bySeason[season] || []).push({ kind: KIND.SERIES, id, number: String(num), name: "Épisode " + num });
      }
    }
    if (!foundFlat) {
      await this.paginate(`type=series&action=get_ordered_list&movie_id=${seriesId}&season_id=0&episode_id=0`, (o) => {
        const id = String(o.id || ""); if (!id) return;
        if (o.cmd) this.cmds.set("series:" + id, o.cmd);
        const season = String(o.season_number || "1");
        (bySeason[season] = bySeason[season] || []).push({ kind: KIND.SERIES, id, number: String(o.episode_number || o.number || ((bySeason[season] || []).length + 1)), name: o.name || "Épisode", image: this.abs(o.screenshot_uri) });
      });
    }
    return { seasons: Object.keys(bySeason).sort((a, b) => (+a) - (+b)).map((s) => ({ season: s, episodes: bySeason[s] })) };
  }
  async resolve(kind, item) {
    const stored = this.cmds.get((kind === KIND.LIVE ? "live" : kind === KIND.MOVIE ? "movie" : "series") + ":" + item.id);
    if (!stored) throw new Error("Élément introuvable dans le portail");
    const cmd = stored.split("|series=")[0];
    const seriesParam = stored.includes("|series=") ? "&series=" + stored.split("|series=")[1] : "";
    const type = kind === KIND.LIVE ? "itv" : "vod";
    const js = await this.call(`type=${type}&action=create_link&cmd=${encodeURIComponent(cmd)}${seriesParam}&forced_storage=0&disable_ad=0`);
    const url = normalizePlayUrl(js && js.cmd) || normalizePlayUrl(cmd);
    if (!url) throw new Error("Le portail n'a pas renvoyé de lien de lecture valide.");
    return kind === KIND.LIVE ? { ts: url, hls: url, live: true } : { url, live: false };
  }
  abs(u) { if (!u) return null; return /^https?:\/\//i.test(u) ? u : this.origin() + "/" + String(u).replace(/^\/+/, ""); }
}

function makeProvider(sub) {
  const s = sub.server || "";
  if (s.startsWith(M3U_PREFIX)) return new M3uProvider(sub);
  if (s.startsWith(STALKER_PREFIX)) return new StalkerProvider(sub);
  return new XtreamProvider(sub);
}

function subTypeLabel(sub) {
  const s = sub.server || "";
  if (s.startsWith(M3U_PREFIX)) return "Lien M3U";
  if (s.startsWith(STALKER_PREFIX)) return "Portail MAC";
  return "Xtream";
}

export { KIND, makeProvider, subTypeLabel, normalizePlayUrl, paginationDone };
