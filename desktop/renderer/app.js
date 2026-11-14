import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut }
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

let uid = null, subs = [], profiles = [], profile = null;
let sub = null, prov = null;
let curKind = null, curItems = [], player = null;

const views = ["login", "who", "home", "browse", "settings"];
const show = (v) => views.forEach((x) => $(x).classList.toggle("hidden", x !== v));
function toast(t) { const el = $("toast"); el.textContent = t; el.classList.add("show"); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.remove("show"), 2400); }
function setAccent(i) { document.documentElement.style.setProperty("--accent", ACCENTS[(+i) || 0] || ACCENTS[0]); }
const esc = (s) => String(s ?? "");

// ---------- Connexion ----------
$("f-login").onsubmit = async (e) => {
  e.preventDefault();
  $("l-go").disabled = true; $("l-msg").className = "msg";
  try { await signInWithEmailAndPassword(auth, $("email").value.trim(), $("pass").value); }
  catch (err) { $("l-msg").textContent = "E-mail ou mot de passe incorrect."; $("l-msg").className = "msg err"; }
  finally { $("l-go").disabled = false; }
};
$("logout").onclick = () => { stopPlayback(); signOut(auth); };

onAuthStateChanged(auth, async (user) => {
  if (!user) return show("login");
  if (!user.emailVerified) { $("l-msg").textContent = "Confirme d'abord ton e-mail, puis reconnecte-toi."; $("l-msg").className = "msg err"; return show("login"); }
  uid = user.uid;
  await loadAccount();
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
function setProfile(p) { profile = p; setAccent(p.color); $("profName").textContent = p.name || "Moi"; }

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

function openProvider() { prov = sub ? makeProvider(sub) : null; }

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
  $("setInfo").textContent = sub ? `${subTypeLabel(sub)} · profil ${profile?.name || ""}. Maquette : favoris et reprises enregistrés sur ce PC.` : "Aucun abonnement.";
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
    const cats = await prov.categories(kind);
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
  $("content").innerHTML = '<div class="spin">Chargement…</div>';
  try {
    curItems = await prov.streams(kind, catId);
    renderItems(kind, curItems);
  } catch (err) { $("content").innerHTML = `<div class="empty">Erreur : ${esc(err.message)}</div>`; }
}

$("search").oninput = () => {
  const q = $("search").value.trim().toLowerCase();
  renderItems(curKind === "fav" ? "live" : curKind, q ? curItems.filter((i) => (i.name || "").toLowerCase().includes(q)) : curItems);
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
function openPlayer(title) { $("playTitle").textContent = title; $("playHint").classList.add("hidden"); $("player").classList.remove("hidden"); }
$("playClose").onclick = () => { saveResume(); stopPlayback(); $("player").classList.add("hidden"); };
function stopPlayback() { try { if (player) { player.destroy(); player = null; } } catch (e) {} const v = $("video"); try { v.pause(); v.removeAttribute("src"); v.load(); } catch (e) {} resumeCtx = null; }

async function playLive(it) {
  openPlayer(it.name);
  try { const r = await prov.resolve(KIND.LIVE, it); playMpegtsOrHls(r.ts, r.hls); }
  catch (err) { hint("Lecture impossible : " + err.message); }
}
async function playMovie(it) {
  openPlayer(it.name);
  try { const r = await prov.resolve(KIND.MOVIE, it); playVod(r.url, resumeKey(KIND.MOVIE, it.id)); }
  catch (err) { hint("Lecture impossible : " + err.message); }
}
async function playEpisode(ep, seriesName) {
  openPlayer(`${seriesName} · ${ep.name}`);
  try { const r = await prov.resolve(KIND.SERIES, ep); playVod(r.url, resumeKey(KIND.SERIES, ep.id)); }
  catch (err) { hint("Lecture impossible : " + err.message); }
}

function hint(t) { $("playHint").textContent = t; $("playHint").classList.remove("hidden"); }

function playMpegtsOrHls(tsUrl, hlsUrl) {
  stopPlaybackKeepPlayer();
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
