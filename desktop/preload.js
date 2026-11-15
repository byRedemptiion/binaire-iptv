const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("binaire", {
  platform: process.platform,
  version: "0.7.3",
  httpGet: (url, headers) => ipcRenderer.invoke("http-get", url, headers),
  mpvAvailable: () => ipcRenderer.invoke("mpv-available"),
  mpvPlay: (opts) => ipcRenderer.invoke("mpv-play", opts),
  mpvStop: () => ipcRenderer.invoke("mpv-stop"),
  onMpvClosed: (cb) => ipcRenderer.on("mpv-closed", () => cb())
});
