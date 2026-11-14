const { contextBridge } = require("electron");
contextBridge.exposeInMainWorld("binaire", { platform: process.platform, version: "0.1.0" });
