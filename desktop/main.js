const { app, BrowserWindow, shell, ipcMain } = require("electron");
const path = require("path");
const fs = require("fs");
const http = require("http");
const https = require("https");
const { spawn } = require("child_process");

let mainWin = null;
let mpv = null;

// Requête HTTP côté Node : contrôle total des en-têtes (Cookie/UA pour Stalker), pas de CORS.
function httpGet(url, headers = {}, redirects = 0) {
  return new Promise((resolve) => {
    let lib, u;
    try { u = new URL(url); } catch (e) { return resolve({ ok: false, status: 0, body: "", error: "URL invalide" }); }
    lib = u.protocol === "https:" ? https : http;
    const req = lib.request(u, { method: "GET", headers, timeout: 20000 }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location && redirects < 5) {
        res.resume();
        return resolve(httpGet(new URL(res.headers.location, u).toString(), headers, redirects + 1));
      }
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => resolve({ ok: res.statusCode >= 200 && res.statusCode < 300, status: res.statusCode, body: Buffer.concat(chunks).toString("utf8") }));
    });
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, status: 0, body: "", error: "timeout" }); });
    req.on("error", (e) => resolve({ ok: false, status: 0, body: "", error: e.message }));
    req.end();
  });
}
ipcMain.handle("http-get", (_e, url, headers) => httpGet(url, headers || {}));

// ---------- Lecteur mpv (moteur de VLC) : son AC3/DTS, pistes audio & sous-titres ----------
function mpvPath() {
  const packaged = path.join(process.resourcesPath || "", "mpv.exe");
  if (fs.existsSync(packaged)) return packaged;
  const dev = path.join(__dirname, "resources", "mpv.exe");
  return fs.existsSync(dev) ? dev : null;
}
ipcMain.handle("mpv-available", () => !!mpvPath());

function stopMpv() { if (mpv) { try { mpv.removeAllListeners("exit"); mpv.kill(); } catch (e) {} mpv = null; } }

ipcMain.handle("mpv-stop", () => stopMpv());

function mpvConfigDir() {
  const packaged = path.join(process.resourcesPath || "", "mpvconf");
  if (fs.existsSync(packaged)) return packaged;
  const dev = path.join(__dirname, "resources", "mpvconf");
  return fs.existsSync(dev) ? dev : null;
}

ipcMain.handle("mpv-play", (_e, opts) => {
  const bin = mpvPath();
  if (!bin || !mainWin) return { ok: false, error: "mpv indisponible" };
  stopMpv();
  // mpv en fenêtre propre (l'intégration --wid rend un écran noir sous Windows).
  const cfg = mpvConfigDir();
  const args = [
    "--force-window=immediate",
    "--keep-open=no",
    "--hwdec=auto-safe",
    "--cache=yes",
    "--title=Binaire IPTV — ${media-title}",
    "--geometry=92%x92%+50%+50%",
    // Reconnexion auto quand le serveur IPTV a un raté réseau (réduit les coupures).
    "--stream-lavf-o-append=reconnect=1",
    "--stream-lavf-o-append=reconnect_streamed=1",
    "--stream-lavf-o-append=reconnect_on_network_error=1",
    "--stream-lavf-o-append=reconnect_delay_max=5"
  ];
  if (cfg) args.push("--config-dir=" + cfg);        // barre uosc (audio/sous-titres) + ESC pour quitter
  else args.push("--no-config", "--osc=yes");
  if (opts && opts.live) {
    // Direct : cache sain (surtout PAS low-latency, qui désactive le tampon).
    args.push("--cache-secs=20", "--demuxer-max-bytes=192MiB", "--demuxer-readahead-secs=20");
  } else {
    // Films / séries (gros fichiers 4K) : gros tampon pour absorber les à-coups réseau.
    args.push("--cache-secs=120", "--demuxer-max-bytes=512MiB", "--demuxer-max-back-bytes=128MiB", "--demuxer-readahead-secs=60");
  }
  if (opts && opts.userAgent) args.push("--user-agent=" + opts.userAgent);
  if (opts && opts.referer) args.push("--referrer=" + opts.referer);
  if (opts && Array.isArray(opts.headers)) for (const h of opts.headers) args.push("--http-header-fields-append=" + h);
  if (opts && opts.start > 0) args.push("--start=+" + Math.floor(opts.start));
  args.push("--", opts.url);
  try {
    mpv = spawn(bin, args, { windowsHide: false });
  } catch (e) { mpv = null; return { ok: false, error: e.message }; }
  mpv.on("exit", () => { mpv = null; if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send("mpv-closed"); });
  mpv.on("error", () => { mpv = null; if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send("mpv-closed"); });
  return { ok: true };
});

function createWindow() {
  mainWin = new BrowserWindow({
    width: 1320, height: 840, minWidth: 1000, minHeight: 640,
    backgroundColor: "#070912", autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true, nodeIntegration: false,
      webSecurity: false
    }
  });
  mainWin.maximize();
  mainWin.loadFile(path.join(__dirname, "renderer", "index.html"));
  mainWin.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: "deny" }; });
  mainWin.on("closed", () => { stopMpv(); mainWin = null; });
}

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on("window-all-closed", () => { stopMpv(); if (process.platform !== "darwin") app.quit(); });
