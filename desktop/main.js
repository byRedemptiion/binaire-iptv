const { app, BrowserWindow, shell, ipcMain } = require("electron");
const path = require("path");
const http = require("http");
const https = require("https");

// Requête HTTP côté Node (processus principal) : contrôle total des en-têtes
// (Cookie, User-Agent, Authorization) — indispensable pour les portails Stalker,
// et pratique pour tout contourner proprement le CORS depuis l'appli de bureau.
function httpGet(url, headers = {}, redirects = 0) {
  return new Promise((resolve) => {
    let lib, u;
    try { u = new URL(url); } catch (e) { return resolve({ ok: false, status: 0, body: "", error: "URL invalide" }); }
    lib = u.protocol === "https:" ? https : http;
    const req = lib.request(u, { method: "GET", headers, timeout: 20000 }, (res) => {
      // Redirections manuelles (le portail peut renvoyer un 302 vers le flux).
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location && redirects < 5) {
        res.resume();
        const next = new URL(res.headers.location, u).toString();
        return resolve(httpGet(next, headers, redirects + 1));
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

function createWindow() {
  const win = new BrowserWindow({
    width: 1320, height: 840, minWidth: 1000, minHeight: 640,
    backgroundColor: "#070912", autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true, nodeIntegration: false,
      // Maquette : lève, EN LOCAL, le blocage CORS + le blocage flux http sur page https.
      webSecurity: false
    }
  });
  win.maximize();
  win.loadFile(path.join(__dirname, "renderer", "index.html"));
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: "deny" }; });
}

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
