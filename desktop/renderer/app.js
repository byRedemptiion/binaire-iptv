import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut,
  createUserWithEmailAndPassword, sendEmailVerification, sendPasswordResetEmail }
  from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import { getFirestore, collection, getDocs } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { KIND, makeProvider, subTypeLabel } from "./providers.js";

const fb = initializeApp({
  apiKey: "AIzaSyD4AGPutgrGhmttoeeVuHhJQs8rzqJuQac",
  authDomain: "binaire-iptv.firebaseapp.com",
  projectId: "binaire-iptv",
  storageBucket: "binaire-iptv.firebasestorage.app",
  messagingSenderId: "712562892693",
  appId: "1:712562892693:web:3c4b359ea30a0fff9b209c"
});
const auth = getAuth(fb);
const db = getFirestore(fb);
const $ = (id) => document.getElementById(id);
const ACCENTS = ["#E5203A", "#3B7BF6", "#16A34A", "#EA7A0C", "#8B5CF6", "#DB2777", "#0E9F9A", "#C9A227"];

// Réglages de nettoyage/affichage (locaux au PC)
function getCfg() { try { return Object.assign({ clean: true, merge: true, adult: false }, JSON.parse(localStorage.getItem("cfg") || "{}")); } catch (e) { return { clean: true, merge: true, adult: false }; } }
function setCfg(c) { localStorage.setItem("cfg", JSON.stringify(c)); }
function cleanName(s) {
  let n = (s || "").trim();
  n = n.replace(/^[[(|]*\s*[A-Z]{2,4}\s*[\])|:\-–>]+\s*/, "");
  n = n.replace(/\b(FHD|UHD|HD|SD|4K|8K|1080p?|720p?|H\.?265|H\.?264|HEVC|MULTI)\b/gi, " ");
  n = n.replace(/\s{2,}/g, " ").replace(/[\s\-|:]+$/, "").replace(/^[\s\-|:]+/, "").trim();
  return n || s;
}
function isAdultName(s) { return /(adult|xxx|porn|\bsex\b|\+?18\+?|hot\b)/i.test(s || ""); }
function applyCleanup(kind, items) {
  const cfg = getCfg();
  let out = items;
  if (cfg.clean) out = out.map((it) => Object.assign({}, it, { name: cleanName(it.name) }));
  if (cfg.merge && kind === KIND.LIVE) {
    const seen = new Set();
    out = out.filter((it) => { const k = (it.name || "").toLowerCase(); if (seen.has(k)) return false; seen.add(k); return true; });
  }
  return out;
}
const ASPECTS = [["contain", "Ajusté"], ["cover", "Plein écran"], ["fill", "Étiré"], ["z15", "Zoom +15%"], ["z33", "Zoom cinéma"]];
function applyAspect() {
  const v = $("video"); const m = localStorage.getItem("aspect") || "contain";
  v.style.objectFit = (m === "z15" || m === "z33") ? "contain" : m;
  v.style.transform = "scale(" + (m === "z15" ? 1.15 : m === "z33" ? 1.34 : 1) + ")";
}

let uid = null, subs = [], profiles = [], profile = null;
let sub = null, prov = null;
let curKind = null, curItems = [], player = null, curCatId = null;
const allCache = {}; // "subId:kind" -> tous les éléments (pour la recherche globale)

// Lecteur mpv (moteur VLC) : détecté au démarrage, repli sur le lecteur intégré sinon.
const STB_UA_PLAYER = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";
let mpvOk = false;
if (window.binaire && window.binaire.mpvAvailable) window.binaire.mpvAvailable().then((v) => { mpvOk = v; });
if (window.binaire && window.binaire.onMpvClosed) window.binaire.onMpvClosed(() => { /* mpv fermé : l'interface réapparaît */ });
function useMpv() { return mpvOk && localStorage.getItem("mpv") !== "0"; }
function stalkerExtras() {
  if (!sub || !(sub.server || "").startsWith("stalker:")) return {};
  return { userAgent: STB_UA_PLAYER, headers: ["Cookie: mac=" + encodeURIComponent(sub.user)] };
}
async function tryMpv(opts) {
  try { const r = await window.binaire.mpvPlay(opts); if (r && r.ok) { toast("Lecture mpv — Échap pour revenir"); return true; } } catch (e) {}
  return false;
}

const views = ["login", "who", "home", "browse", "settings"];
const show = (v) => views.forEach((x) => $(x).classList.toggle("hidden", x !== v));
function toast(t) { const el = $("toast"); el.textContent = t; el.classList.add("show"); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.remove("show"), 2400); }
function setAccent(i) { document.documentElement.style.setProperty("--accent", ACCENTS[(+i) || 0] || ACCENTS[0]); }
const esc = (s) => String(s ?? "");

// Fond d'écran (comme l'appli) + horloge
function applyBackground() { document.body.className = "bg-" + (localStorage.getItem("bg") || "binaire"); }
function tickClock() { const el = $("clock"); if (el) { const d = new Date(); el.textContent = String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0"); } }
applyBackground();
tickClock(); setInterval(tickClock, 15000);

// ---------- Connexion ----------
const DM3U = "m3u:", DSTALKER = "stalker:";
function lmsg(t, ok) { $("l-msg").textContent = t || ""; $("l-msg").className = "msg" + (t ? (ok ? " ok" : " err") : ""); }
function dmsg(t, ok) { $("d-msg").textContent = t || ""; $("d-msg").className = "msg" + (t ? (ok ? " ok" : " err") : ""); }

// Bascule compte / abonnement direct
$("mAccount").onclick = () => setLoginMode("account");
$("mDirect").onclick = () => setLoginMode("direct");
function setLoginMode(m) {
  $("mAccount").setAttribute("aria-selected", m === "account");
  $("mDirect").setAttribute("aria-selected", m === "direct");
  $("f-login").classList.toggle("hidden", m !== "account");
  $("f-direct").classList.toggle("hidden", m !== "direct");
}

// Compte : connexion / création / mot de passe oublié
let accMode = "login";
$("accToggle").onclick = () => {
  accMode = accMode === "login" ? "signup" : "login";
  $("l-go").textContent = accMode === "signup" ? "Créer mon compte" : "Se connecter";
  $("accToggle").textContent = accMode === "signup" ? "Déjà un compte ? Se connecter" : "Pas de compte ? En créer un";
  $("accForgot").classList.toggle("hidden", accMode === "signup");
  $("accLead").textContent = accMode === "signup"
    ? "Crée ton compte : un e-mail de confirmation te sera envoyé."
    : "Connecte-toi pour retrouver tes abonnements et profils sur tous tes appareils.";
  lmsg("");
};
$("f-login").onsubmit = async (e) => {
  e.preventDefault();
  $("l-go").disabled = true; lmsg("");
  try {
    if (accMode === "signup") {
      const cred = await createUserWithEmailAndPassword(auth, $("email").value.trim(), $("pass").value);
      try { await sendEmailVerification(cred.user); } catch (x) {}
      lmsg("Compte créé ! Clique sur le lien reçu par mail, puis connecte-toi.", true);
    } else {
      await signInWithEmailAndPassword(auth, $("email").value.trim(), $("pass").value);
    }
  } catch (err) {
    const c = (err && err.code) || "";
    lmsg(c.includes("email-already-in-use") ? "Un compte existe déjà avec cet e-mail."
      : c.includes("weak-password") ? "Mot de passe trop court (6 caractères min)."
      : c.includes("invalid-email") ? "Adresse e-mail invalide."
      : "E-mail ou mot de passe incorrect.");
  } finally { $("l-go").disabled = false; }
};
$("accForgot").onclick = async () => {
  const em = $("email").value.trim();
  if (!em) return lmsg("Écris d'abord ton e-mail.");
  try { await sendPasswordResetEmail(auth, em); lmsg("E-mail envoyé : suis le lien pour changer ton mot de passe.", true); }
  catch (err) { lmsg("Envoi impossible. Vérifie l'adresse."); }
};

// Abonnement direct (sans compte)
let dType = "x";
for (const [b, t] of [["dX", "x"], ["dM", "m"], ["dS", "s"]]) {
  $(b).onclick = () => {
    dType = t;
    $("dX").setAttribute("aria-selected", t === "x"); $("dM").setAttribute("aria-selected", t === "m"); $("dS").setAttribute("aria-selected", t === "s");
    $("dXfields").classList.toggle("hidden", t !== "x");
    $("dMfields").classList.toggle("hidden", t !== "m");
    $("dSfields").classList.toggle("hidden", t !== "s");
    dmsg("");
  };
}
$("f-direct").onsubmit = (e) => {
  e.preventDefault();
  let server, user, pass, name = $("dName").value.trim() || "Mon abonnement";
  if (dType === "x") {
    server = $("dServer").value.trim(); user = $("dUser").value.trim(); pass = $("dPass").value.trim();
    if (!server || !user || !pass) return dmsg("Remplis le serveur, l'identifiant et le mot de passe.");
    if (!/^https?:\/\//i.test(server)) server = "http://" + server;
    server = server.replace(/\/(player_api\.php|get\.php|c)\/?$/i, "").replace(/\/+$/, "");
  } else if (dType === "m") {
    let link = $("dM3u").value.trim();
    if (!link) return dmsg("Colle le lien de ta liste M3U.");
    if (!/^https?:\/\//i.test(link)) link = "http://" + link;
    server = DM3U + link; user = "m3u"; pass = "m3u";
  } else {
    let portal = $("dPortal").value.trim();
    const mac = $("dMac").value.replace(/[^0-9a-fA-F]/g, "").toUpperCase();
    if (!portal) return dmsg("Entre l'adresse du portail.");
    if (!/^https?:\/\//i.test(portal)) portal = "http://" + portal;
    if (mac.length !== 12) return dmsg("La MAC doit faire 12 caractères (ex. 00:1A:79:AA:BB:CC).");
    server = DSTALKER + portal.replace(/\/+$/, ""); user = mac.match(/.{2}/g).join(":"); pass = "";
  }
  const ls = { id: "local", name, server, user, pass };
  localStorage.setItem("localSub", JSON.stringify(ls));
  localStorage.setItem("useLocal", "1");
  startLocal();
};

function startLocal() {
  let ls; try { ls = JSON.parse(localStorage.getItem("localSub") || "null"); } catch (e) { ls = null; }
  if (!ls) return show("login");
  uid = null; subs = [ls]; profiles = [{ id: "p1", name: "Moi", color: 0 }];
  setProfile(profiles[0]); afterProfile();
}

$("logout").onclick = () => { stopPlayback(); localStorage.removeItem("useLocal"); if (auth.currentUser) signOut(auth); else show("login"); };

onAuthStateChanged(auth, async (user) => {
  if (user) {
    if (!user.emailVerified) { setLoginMode("account"); lmsg("Confirme d'abord ton e-mail (lien reçu par mail), puis reconnecte-toi."); return show("login"); }
    localStorage.removeItem("useLocal");
    uid = user.uid;
    return loadAccount();
  }
  if (localStorage.getItem("useLocal") === "1" && localStorage.getItem("localSub")) return startLocal();
  show("login");
});

async function loadAccount() {
  try {
    const sSnap = await getDocs(collection(db, "users", uid, "subs"));
    subs = sSnap.docs.map((d) => ({ id: d.id, ...d.data() }));
    const pSnap = await getDocs(collection(db, "users", uid, "profiles"));
    profiles = pSnap.docs.map((d) => ({ id: d.id, ...d.data() }));
  } catch (err) { subs = []; profiles = []; }
  if (!profiles.length) profiles = [{ id: "p1", name: "Moi", color: 0 }];
  // Choix du profil
  if (profiles.length === 1) { setProfile(profiles[0]); return afterProfile(); }
  renderWho();
}

// ---------- Qui regarde ----------
function renderWho() {
  show("who");
  const box = $("profList"); box.innerHTML = "";
  for (const p of profiles) {
    const el = document.createElement("div"); el.className = "prof";
    const color = ACCENTS[(+p.color) || 0] || ACCENTS[0];
    el.innerHTML = `<div class="av" style="background:${color}">${esc((p.name || "?").trim().charAt(0).toUpperCase())}</div><div class="nm">${esc(p.name || "Profil")}</div>`;
    el.onclick = () => { setProfile(p); afterProfile(); };
    box.appendChild(el);
  }
}
function setProfile(p) { profile = p; const saved = localStorage.getItem("accent:" + p.id); if (saved) document.documentElement.style.setProperty("--accent", saved); else setAccent(p.color); $("profName").textContent = p.name || "Moi"; }

function afterProfile() {
  // Abonnement : dernier utilisé ou le premier
  const lastSub = localStorage.getItem("lastSub");
  sub = subs.find((s) => s.id === lastSub) || subs[0] || null;
  fillSubPickers();
  openProvider();
  show("home");
}

function fillSubPickers() {
  const opts = subs.map((s) => `<option value="${esc(s.id)}">${esc(s.name || "Abonnement")} · ${subTypeLabel(s)}</option>`).join("");
  for (const sel of [$("subPick"), $("setSub")]) {
    sel.innerHTML = opts || `<option>Aucun abonnement</option>`;
    if (sub) sel.value = sub.id;
  }
  $("setProf").innerHTML = profiles.map((p) => `<option value="${esc(p.id)}">${esc(p.name)}</option>`).join("");
  if (profile) $("setProf").value = profile.id;
}
$("subPick").onchange = (e) => switchSub(e.target.value);
$("setSub").onchange = (e) => { switchSub(e.target.value); $("subPick").value = e.target.value; };
function switchSub(id) { sub = subs.find((s) => s.id === id); if (sub) localStorage.setItem("lastSub", sub.id); openProvider(); toast("Abonnement : " + (sub?.name || "")); }
$("setProf").onchange = (e) => { const p = profiles.find((x) => x.id === e.target.value); if (p) { setProfile(p); } };

function openProvider() { prov = sub ? makeProvider(sub) : null; for (const k in allCache) delete allCache[k]; }

// ---------- Accueil ----------
document.querySelectorAll(".tile[data-kind]").forEach((t) => t.onclick = () => openBrowse(t.dataset.kind));
$("tileSettings").onclick = () => openSettings();
$("profChip").onclick = () => renderWho();
$("backHome").onclick = () => show("home");
$("setBack").onclick = () => show("home");
$("tileSettings");

// ---------- Réglages ----------
function openSettings() {
  fillSubPickers();
  const sw = $("swatches"); sw.innerHTML = "";
  const curAccent = localStorage.getItem("accent:" + (profile?.id || "p1"));
  ACCENTS.forEach((c, i) => {
    const d = document.createElement("div");
    d.className = "swatch" + ((curAccent ? curAccent === c : (+(profile?.color) || 0) === i) ? " on" : "");
    d.style.background = c;
    d.onclick = () => { document.documentElement.style.setProperty("--accent", c); localStorage.setItem("accent:" + (profile?.id || "p1"), c); sw.querySelectorAll(".swatch").forEach((x) => x.classList.remove("on")); d.classList.add("on"); };
    sw.appendChild(d);
  });
  $("setBg").value = localStorage.getItem("bg") || "binaire";
  $("setBg").onchange = () => { localStorage.setItem("bg", $("setBg").value); applyBackground(); };
  // Lecteur mpv (affiché seulement s'il est disponible)
  $("mpvRow").style.display = mpvOk ? "" : "none";
  $("mpvChk").style.display = mpvOk ? "" : "none";
  $("cfgMpv").checked = localStorage.getItem("mpv") !== "0";
  $("cfgMpv").onchange = () => localStorage.setItem("mpv", $("cfgMpv").checked ? "1" : "0");
  const cfg = getCfg();
  $("cfgClean").checked = cfg.clean; $("cfgMerge").checked = cfg.merge; $("cfgAdult").checked = cfg.adult;
  const saveCfg = () => setCfg({ clean: $("cfgClean").checked, merge: $("cfgMerge").checked, adult: $("cfgAdult").checked });
  $("cfgClean").onchange = saveCfg; $("cfgMerge").onchange = saveCfg; $("cfgAdult").onchange = saveCfg;
  $("setInfo").textContent = sub ? `${subTypeLabel(sub)} · profil ${profile?.name || ""}. Favoris et reprises sur ce PC (synchro à venir).` : "Aucun abonnement.";
  show("settings");
}
$("setFavs").onclick = () => { curKind = "fav"; openBrowse("fav"); };

// ---------- Navigation ----------
const TITLES = { live: "TV", movie: "FILMS", series: "SÉRIES", fav: "FAVORIS" };
async function openBrowse(kind) {
  if (!prov) return toast("Aucun abonnement");
  curKind = kind;
  $("browseTitle").textContent = TITLES[kind] || "";
  $("search").value = "";
  $("cats").innerHTML = ""; $("content").innerHTML = '<div class="spin">Chargement…</div>';
  show("browse");
  if (kind === "fav") return renderFavorites();
  try {
    let cats = await prov.categories(kind);
    if (getCfg().adult) cats = cats.filter((c) => !isAdultName(c.name));
    const rows = [{ id: "__fav", name: "★ Favoris" }, ...cats];
    $("cats").innerHTML = "";
    rows.forEach((c, i) => {
      const r = document.createElement("div"); r.className = "row"; r.textContent = c.name;
      r.onclick = () => { $("cats").querySelectorAll(".row.active").forEach((x) => x.classList.remove("active")); r.classList.add("active"); c.id === "__fav" ? renderFavorites(kind) : loadCategory(kind, c.id); };
      $("cats").appendChild(r);
      if (i === 1) { r.classList.add("active"); loadCategory(kind, c.id); }
    });
    if (rows.length === 1) { rows[0] && $("cats").firstChild.classList.add("active"); renderFavorites(kind); }
  } catch (err) {
    $("content").innerHTML = `<div class="empty">Impossible de charger : ${esc(err.message)}</div>`;
  }
}

async function loadCategory(kind, catId) {
  curCatId = catId;
  $("content").innerHTML = '<div class="spin">Chargement…</div>';
  try {
    curItems = applyCleanup(kind, await prov.streams(kind, catId));
    renderItems(kind, curItems);
  } catch (err) { $("content").innerHTML = `<div class="empty">Erreur : ${esc(err.message)}</div>`; }
}

// Tout le contenu de la section (mis en cache) pour chercher dans toutes les catégories.
async function ensureAll(kind) {
  const k = (sub?.id || "") + ":" + kind;
  if (allCache[k]) return allCache[k];
  const items = applyCleanup(kind, await prov.allStreams(kind));
  allCache[k] = items;
  return items;
}

// Recherche globale, en direct (prédictions au fur et à mesure)
let searchTimer = null, searchSeq = 0;
$("search").oninput = () => {
  const q = $("search").value.trim().toLowerCase();
  clearTimeout(searchTimer);
  if (!q) { // champ vidé → on revient à la catégorie sélectionnée
    if (curKind === "fav") renderFavorites(); else if (curCatId != null) loadCategory(curKind, curCatId);
    return;
  }
  if (curKind === "fav") { renderItems(KIND.LIVE, curItems.filter((i) => (i.name || "").toLowerCase().includes(q))); return; }
  const seq = ++searchSeq;
  searchTimer = setTimeout(async () => {
    $("content").innerHTML = '<div class="spin">Recherche…</div>';
    let all;
    try { all = await ensureAll(curKind); } catch (e) { all = curItems; }
    if (seq !== searchSeq) return; // une frappe plus récente a pris le relais
    const res = all.filter((i) => (i.name || "").toLowerCase().includes(q))
      .sort((a, b) => (a.name || "").toLowerCase().startsWith(q) === (b.name || "").toLowerCase().startsWith(q) ? 0 : ((a.name || "").toLowerCase().startsWith(q) ? -1 : 1));
    if (!res.length) { $("content").className = "content"; $("content").innerHTML = `<div class="empty">Rien trouvé pour « ${esc($("search").value.trim())} ».</div>`; return; }
    renderItems(curKind, res.slice(0, 300));
    if (res.length > 300) { const n = document.createElement("div"); n.className = "empty"; n.textContent = `${res.length} résultats — affine ta recherche pour en voir plus.`; $("content").appendChild(n); }
  }, 180);
};

function renderItems(kind, items) {
  const box = $("content");
  if (!items.length) { box.innerHTML = '<div class="empty">Rien à afficher ici.</div>'; return; }
  if (kind === KIND.LIVE) {
    box.className = "content list"; box.innerHTML = "";
    for (const it of items) {
      const r = document.createElement("div"); r.className = "row";
      r.innerHTML = `<img src="${esc(it.icon || "")}" onerror="this.style.visibility='hidden'"><div class="nm">${esc(it.name)}</div><div class="star ${isFav(it) ? "on" : ""}">★</div>`;
      r.querySelector(".nm").onclick = () => playLive(it);
      r.querySelector("img").onclick = () => playLive(it);
      r.querySelector(".star").onclick = (e) => { e.stopPropagation(); toggleFav(it); r.querySelector(".star").classList.toggle("on"); };
      box.appendChild(r);
    }
  } else {
    box.className = "content"; const g = document.createElement("div"); g.className = "grid"; box.innerHTML = ""; box.appendChild(g);
    for (const it of items) {
      const c = document.createElement("div"); c.className = "poster";
      const bg = it.icon ? `style="background-image:url('${esc(it.icon)}')"` : "";
      c.innerHTML = `<div class="ph" ${bg}>${it.icon ? "" : esc(it.name)}</div><div class="star ${isFav(it) ? "on" : ""}">★</div><div class="cap">${esc(it.name)}</div>`;
      c.querySelector(".ph").onclick = () => (kind === KIND.MOVIE ? playMovie(it) : openSeries(it));
      c.querySelector(".cap").onclick = () => (kind === KIND.MOVIE ? playMovie(it) : openSeries(it));
      c.querySelector(".star").onclick = (e) => { e.stopPropagation(); toggleFav(it); c.querySelector(".star").classList.toggle("on"); };
      g.appendChild(c);
    }
  }
}

// ---------- Favoris (locaux par profil) ----------
function favKey() { return "favs:" + (profile?.id || "p1"); }
function favList() { try { return JSON.parse(localStorage.getItem(favKey()) || "[]"); } catch (e) { return []; } }
function favId(it) { return `${sub?.id}|${it.kind}|${it.id}`; }
function isFav(it) { return favList().some((f) => f._k === favId(it)); }
function toggleFav(it) {
  const l = favList(); const k = favId(it); const i = l.findIndex((f) => f._k === k);
  if (i >= 0) l.splice(i, 1); else l.push({ _k: k, subId: sub?.id, kind: it.kind, id: it.id, name: it.name, icon: it.icon, ext: it.ext });
  localStorage.setItem(favKey(), JSON.stringify(l));
}
function renderFavorites(kind) {
  const all = favList().filter((f) => f.subId === sub?.id && (!kind || kind === "fav" || f.kind === kind));
  curItems = all;
  if (!all.length) { $("content").className = "content"; $("content").innerHTML = '<div class="empty">Aucun favori ici. Touche l\'étoile ★ sur une chaîne, un film ou une série.</div>'; return; }
  // Regroupe : live en liste, le reste en grille → on affiche selon le kind dominant
  renderItems(all.every((x) => x.kind === KIND.LIVE) ? KIND.LIVE : KIND.MOVIE, all);
}

// ---------- Lecteur ----------
function openPlayer(title) { $("playTitle").textContent = title; $("playHint").classList.add("hidden"); $("player").classList.remove("hidden"); applyAspect(); }
$("playClose").onclick = () => { if (document.fullscreenElement) document.exitFullscreen(); saveResume(); stopPlayback(); $("player").classList.add("hidden"); };
function stopPlayback() { try { if (player) { player.destroy(); player = null; } } catch (e) {} const v = $("video"); try { v.pause(); v.removeAttribute("src"); v.load(); } catch (e) {} resumeCtx = null; }

async function playLive(it) {
  try {
    const r = await prov.resolve(KIND.LIVE, it);
    if (useMpv() && await tryMpv(Object.assign({ url: r.ts || r.hls, live: true }, stalkerExtras()))) return;
    openPlayer(it.name); setPlayerMode(true); playMpegtsOrHls(r.ts, r.hls);
  } catch (err) { openPlayer(it.name); hint("Lecture impossible : " + err.message); }
}
async function playMovie(it) {
  try {
    const r = await prov.resolve(KIND.MOVIE, it);
    const start = parseFloat(localStorage.getItem(resumeKey(KIND.MOVIE, it.id)) || "0");
    if (useMpv() && await tryMpv(Object.assign({ url: r.url, start }, stalkerExtras()))) return;
    openPlayer(it.name); playVod(r.url, resumeKey(KIND.MOVIE, it.id));
  } catch (err) { openPlayer(it.name); hint("Lecture impossible : " + err.message); }
}
async function playEpisode(ep, seriesName) {
  try {
    const r = await prov.resolve(KIND.SERIES, ep);
    const start = parseFloat(localStorage.getItem(resumeKey(KIND.SERIES, ep.id)) || "0");
    if (useMpv() && await tryMpv(Object.assign({ url: r.url, start }, stalkerExtras()))) return;
    openPlayer(`${seriesName} · ${ep.name}`); playVod(r.url, resumeKey(KIND.SERIES, ep.id));
  } catch (err) { openPlayer(`${seriesName} · ${ep.name}`); hint("Lecture impossible : " + err.message); }
}

function hint(t) { $("playHint").textContent = t; $("playHint").classList.remove("hidden"); }

function playMpegtsOrHls(tsUrl, hlsUrl) {
  stopPlaybackKeepPlayer();
  setPlayerMode(true);
  const v = $("video");
  if (window.mpegts && mpegts.isSupported()) {
    player = mpegts.createPlayer({ type: "mpegts", isLive: true, url: tsUrl }, { enableWorker: true, liveBufferLatencyChasing: true });
    player.attachMediaElement(v); player.on(mpegts.Events.ERROR, () => playHls(hlsUrl));
    player.load(); v.play().catch(() => {});
  } else playHls(hlsUrl);
}
function playHls(url) {
  stopPlaybackKeepPlayer();
  const v = $("video");
  if (window.Hls && Hls.isSupported()) { player = new Hls({ lowLatencyMode: true }); player.loadSource(url); player.attachMedia(v); v.play().catch(() => {}); }
  else { v.src = url; v.play().catch(() => {}); }
}
function stopPlaybackKeepPlayer() { try { if (player) { player.destroy(); player = null; } } catch (e) {} }

// Reprise (locale) pour films/épisodes
let resumeCtx = null;
function resumeKey(kind, id) { return `resume:${sub?.id}|${profile?.id}|${kind}|${id}`; }
function playVod(url, key) {
  stopPlaybackKeepPlayer();
  setPlayerMode(false);
  const v = $("video"); resumeCtx = key;
  if (url.toLowerCase().includes(".m3u8") && window.Hls && Hls.isSupported()) { player = new Hls(); player.loadSource(url); player.attachMedia(v); }
  else v.src = url;
  const saved = parseFloat(localStorage.getItem(key) || "0");
  const onMeta = () => { if (saved > 5 && saved < (v.duration || 1e9) - 10) { v.currentTime = saved; toast("Reprise où tu t'étais arrêté"); } v.removeEventListener("loadedmetadata", onMeta); };
  v.addEventListener("loadedmetadata", onMeta);
  v.play().catch(() => {});
}
function saveResume() { if (resumeCtx) { const v = $("video"); if (v.currentTime > 5) localStorage.setItem(resumeCtx, String(v.currentTime)); } }
$("video").addEventListener("timeupdate", () => { if (resumeCtx && Date.now() % 5000 < 300) saveResume(); });

// ---------- Détail série ----------
let seriesCtx = null;
async function openSeries(it) {
  $("seriesTitle").textContent = it.name; $("seasons").innerHTML = ""; $("eplist").innerHTML = '<div class="spin">Chargement…</div>';
  $("seriesOverlay").classList.remove("hidden");
  try {
    const info = await prov.seriesInfo(it.id);
    seriesCtx = { name: it.name, info };
    if (!info.seasons || !info.seasons.length) { $("eplist").innerHTML = '<div class="empty">Aucun épisode trouvé.</div>'; return; }
    info.seasons.forEach((s, i) => {
      const b = document.createElement("button"); b.className = "sec"; b.textContent = "Saison " + s.season; b.setAttribute("aria-sel", i === 0);
      b.onclick = () => { $("seasons").querySelectorAll("button").forEach((x) => x.setAttribute("aria-sel", false)); b.setAttribute("aria-sel", true); renderEpisodes(s); };
      $("seasons").appendChild(b);
    });
    renderEpisodes(info.seasons[0]);
  } catch (err) { $("eplist").innerHTML = `<div class="empty">Erreur : ${esc(err.message)}</div>`; }
}
function renderEpisodes(season) {
  const box = $("eplist"); box.innerHTML = "";
  for (const ep of season.episodes) {
    const r = document.createElement("div"); r.className = "row";
    r.textContent = `Épisode ${ep.number} — ${ep.name}`;
    r.onclick = () => playEpisode(ep, seriesCtx.name);
    box.appendChild(r);
  }
}
$("seriesClose").onclick = () => $("seriesOverlay").classList.add("hidden");

// ---------- Barre de lecture (toujours visible) ----------
let seeking = false;
function fmt(t) { if (!isFinite(t) || t < 0) return "0:00"; const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = Math.floor(t % 60); return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(s).padStart(2, "0"); }
function setPlayerMode(live) {
  $("pLive").classList.toggle("hidden", !live);
  $("pSeek").classList.toggle("hidden", live);
  $("pCur").classList.toggle("hidden", live);
  $("pDur").classList.toggle("hidden", live);
  $("pBack").classList.toggle("hidden", live);
  $("pFwd").classList.toggle("hidden", live);
}
const SVG_PLAY = '<svg viewBox="0 0 24 24"><path d="M7 5l12 7-12 7z"/></svg>';
const SVG_PAUSE = '<svg viewBox="0 0 24 24"><rect x="7" y="5" width="3.3" height="14" rx="1"/><rect x="13.7" y="5" width="3.3" height="14" rx="1"/></svg>';
const SVG_SND = '<svg viewBox="0 0 24 24"><path d="M4 9v6h4l5 4V5L8 9H4z"/><path d="M16 9.2a4 4 0 0 1 0 5.6" fill="none" stroke="currentColor" stroke-width="1.7"/></svg>';
const SVG_MUTE = '<svg viewBox="0 0 24 24"><path d="M4 9v6h4l5 4V5L8 9H4z"/><path d="M16 9l5 6M21 9l-5 6" fill="none" stroke="currentColor" stroke-width="1.7"/></svg>';
(function initPlayerBar() {
  const v = $("video");
  $("pPlay").onclick = () => { v.paused ? v.play() : v.pause(); };
  v.addEventListener("play", () => { $("pPlay").innerHTML = SVG_PAUSE; });
  v.addEventListener("pause", () => { $("pPlay").innerHTML = SVG_PLAY; });
  $("pBack").onclick = () => { v.currentTime = Math.max(0, v.currentTime - 10); };
  $("pFwd").onclick = () => { v.currentTime = Math.min((v.duration || 1e9), v.currentTime + 30); };
  $("pSeek").addEventListener("input", () => { seeking = true; $("pCur").textContent = fmt((+$("pSeek").value / 1000) * (v.duration || 0)); });
  $("pSeek").addEventListener("change", () => { if (v.duration) v.currentTime = (+$("pSeek").value / 1000) * v.duration; seeking = false; });
  v.addEventListener("timeupdate", () => {
    if (!seeking && v.duration) { $("pSeek").value = String((v.currentTime / v.duration) * 1000); $("pCur").textContent = fmt(v.currentTime); }
  });
  v.addEventListener("loadedmetadata", () => { $("pDur").textContent = fmt(v.duration); });
  $("pMute").onclick = () => { v.muted = !v.muted; $("pMute").innerHTML = v.muted ? SVG_MUTE : SVG_SND; };
  $("pVol").addEventListener("input", () => { v.volume = +$("pVol").value; v.muted = false; $("pMute").innerHTML = v.volume ? SVG_SND : SVG_MUTE; });
  $("pAspect").onclick = () => { const cur = localStorage.getItem("aspect") || "contain"; const i = ASPECTS.findIndex((a) => a[0] === cur); const nx = ASPECTS[(i + 1) % ASPECTS.length]; localStorage.setItem("aspect", nx[0]); applyAspect(); toast("Format : " + nx[1]); };
  $("pFs").onclick = toggleFullscreen;
  $("video").addEventListener("dblclick", toggleFullscreen);
})();
function toggleFullscreen() {
  const el = $("player");
  if (!document.fullscreenElement) el.requestFullscreen && el.requestFullscreen();
  else document.exitFullscreen && document.exitFullscreen();
}
