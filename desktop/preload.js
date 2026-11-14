const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("binaire", {
  platform: process.platform,
  version: "0.6.0",
  // Requête HTTP via le processus principal (en-têtes libres, pas de CORS).
  httpGet: (url, headers) => ipcRenderer.invoke("http-get", url, headers)
});
