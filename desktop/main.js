const { app, BrowserWindow, shell } = require("electron");
const path = require("path");

// Maquette : webSecurity désactivé pour franchir, EN LOCAL, les deux murs du navigateur
// (CORS vers le serveur IPTV + flux http sur page https). Acceptable pour une appli de
// bureau que l'utilisateur contrôle ; pour la version finale on encadrera ça proprement.
function createWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    backgroundColor: "#070912",
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false
    }
  });
  win.loadFile(path.join(__dirname, "renderer", "index.html"));
  // Les liens externes s'ouvrent dans le navigateur, pas dans l'appli.
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: "deny" }; });
}

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
