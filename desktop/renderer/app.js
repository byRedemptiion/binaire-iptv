import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut }
  from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import { getFirestore, collection, getDocs }
  from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";

// Même projet Firebase que l'appli et les pages web Binaire.
const app = initializeApp({
  apiKey: "AIzaSyD4AGPutgrGhmttoeeVuHhJQs8rzqJuQac",
  authDomain: "binaire-iptv.firebaseapp.com",
  projectId: "binaire-iptv",
  storageBucket: "binaire-iptv.firebasestorage.app",
  messagingSenderId: "712562892693",
  appId: "1:712562892693:web:3c4b359ea30a0fff9b209c"
});
const auth = getAuth(app);
const db = getFirestore(app);
const $ = (id) => document.getElementById(id);
const M3U = "m3u:", STALKER = "stalker:";

let subs = [];
let current = null;   // abonnement choisi
let player = null;    // mpegts/hls en cours

function show(view) {
  $("login").classList.toggle("hidden", view !== "login");
  $("browse").classList.toggle("hidden", view !== "browse");
}
function lmsg(t, ok = false) { $("l-msg").textContent = t || ""; $("l-msg").className = "msg" + (t ? (ok ? " ok" : " err") : ""); }

// ---------- Connexion ----------
$("f-login").onsubmit = async (e) => {
  e.preventDefault();
  $("l-go").disabled = true; lmsg("");
  try {
    await signInWithEmailAndPassword(auth, $("email").value.trim(), $("pass").value);
  } catch (err) {
    lmsg("E-mail ou mot de passe incorrect.");
  } finally { $("l-go").disabled = false; }
};
$("logout").onclick = () => { stop(); signOut(auth); };

onAuthStateChanged(auth, async (user) => {
  if (!user) return show("login");
  if (!user.emailVerified) { lmsg("Confirme d'abord ton e-mail (lien reçu par mail), puis reconnecte-toi."); return show("login"); }
  $("who").textContent = user.email;
  await loadSubs(user.uid);
});

// ---------- Abonnements ----------
async function loadSubs(uid) {
  try {
    const snap = await getDocs(collection(db, "users", uid, "subs"));
    subs = snap.docs.map((d) => ({ id: d.id, ...d.data() }))
      .filter((s) => !(s.server || "").startsWith(M3U) && !(s.server || "").startsWith(STALKER)); // maquette : Xtream only
  } catch (err) { subs = []; }
  const pick = $("subPick");
  if (!subs.length) {
    pick.innerHTML = `<option>Aucun abonnement Xtream</option>`;
    show("browse");
    $("stageHint").textContent = "Cette maquette lit les abonnements Xtream. Ajoute-en un depuis l'appli ou la page Mon compte.";
    return;
  }
  pick.innerHTML = subs.map((s, i) => `<option value="${i}">${s.name || "Abonnement"}</option>`).join("");
  pick.onchange = () => selectSub(subs[pick.value]);
  show("browse");
  selectSub(subs[0]);
}

function xt(base, params) {
  return `${base.replace(/\/+$/, "")}/player_api.php?username=${encodeURIComponent(current.user)}&password=${encodeURIComponent(current.pass)}&${params}`;
}

async function selectSub(sub) {
  current = sub;
  stop();
  $("stageHint").classList.remove("hidden");
  $("stageHint").textContent = "Chargement des catégories…";
  $("cats").innerHTML = "<h3>Catégories</h3>";
  $("chans").innerHTML = "<h3>Chaînes</h3>";
  try {
    const cats = await (await fetch(xt(current.server, "action=get_live_categories"))).json();
    for (const c of cats) addRow($("cats"), c.category_name, () => loadChannels(c.category_id));
    $("stageHint").textContent = "Choisis une catégorie puis une chaîne.";
  } catch (err) {
    $("stageHint").textContent = "Impossible de joindre le serveur IPTV. Vérifie l'abonnement et ta connexion.";
  }
}

async function loadChannels(catId) {
  $("chans").innerHTML = "<h3>Chaînes</h3>";
  try {
    const list = await (await fetch(xt(current.server, "action=get_live_streams&category_id=" + encodeURIComponent(catId)))).json();
    for (const ch of list) addRow($("chans"), ch.name, () => playChannel(ch));
  } catch (err) {
    addRow($("chans"), "Erreur de chargement", () => {});
  }
}

function addRow(col, label, onClick) {
  const d = document.createElement("div");
  d.className = "row"; d.textContent = label;
  d.onclick = () => {
    col.querySelectorAll(".row.active").forEach((x) => x.classList.remove("active"));
    d.classList.add("active"); onClick();
  };
  col.appendChild(d);
}

// ---------- Lecture ----------
function stop() {
  try { if (player) { player.destroy(); player = null; } } catch (e) {}
  const v = $("video"); try { v.pause(); v.removeAttribute("src"); v.load(); } catch (e) {}
}

function playChannel(ch) {
  stop();
  const base = current.server.replace(/\/+$/, "");
  const u = `${encodeURIComponent(current.user)}/${encodeURIComponent(current.pass)}`;
  const tsUrl = `${base}/live/${u}/${ch.stream_id}.ts`;
  const hlsUrl = `${base}/live/${u}/${ch.stream_id}.m3u8`;
  const video = $("video");
  $("stageHint").classList.add("hidden");
  $("pill").classList.remove("hidden");
  $("pill").textContent = "▶ " + (ch.name || "Chaîne");

  // MPEG-TS (le plus courant en direct Xtream) via mpegts.js ; repli HLS si besoin.
  if (window.mpegts && mpegts.isSupported()) {
    player = mpegts.createPlayer({ type: "mpegts", isLive: true, url: tsUrl }, { enableWorker: true, liveBufferLatencyChasing: true });
    player.attachMediaElement(video);
    player.on(mpegts.Events.ERROR, () => playHls(hlsUrl));
    player.load();
    video.play().catch(() => {});
  } else {
    playHls(hlsUrl);
  }
}

function playHls(url) {
  stop();
  const video = $("video");
  if (window.Hls && Hls.isSupported()) {
    player = new Hls({ lowLatencyMode: true });
    player.loadSource(url);
    player.attachMedia(video);
    video.play().catch(() => {});
  } else {
    video.src = url; video.play().catch(() => {});
  }
}
