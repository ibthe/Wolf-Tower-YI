const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('wt', {
  getState: () => ipcRenderer.invoke('state:get'),
  getLogs: () => ipcRenderer.invoke('log:get'),
  setStrategy: (id, s) => ipcRenderer.invoke('profile:strategy', id, s),
  setLang: (code) => ipcRenderer.invoke('lang:set', code),
  setEnabled: (id, on) => ipcRenderer.invoke('profile:enabled', id, on),
  toggleProtection: () => ipcRenderer.invoke('protection:toggle'),
  activate: (key) => ipcRenderer.invoke('license:activate', key),
  startProbe: () => ipcRenderer.invoke('probe:start'),
  cancelProbe: () => ipcRenderer.invoke('probe:cancel'),
  clearLog: () => ipcRenderer.invoke('log:clear'),
  copyDiag: () => ipcRenderer.invoke('diag:copy'),
  copyText: (t) => ipcRenderer.invoke('copy', t),
  paste: () => ipcRenderer.invoke('paste'),
  componentLicenses: () => ipcRenderer.invoke('licenses:get'),
  onState: (cb) => ipcRenderer.on('state', (_e, s) => cb(s)),
  onLog: (cb) => ipcRenderer.on('log', (_e, line) => cb(line)),
});
