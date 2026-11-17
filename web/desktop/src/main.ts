import { app, BrowserWindow, globalShortcut, ipcMain, Menu, Tray, protocol, shell } from 'electron';
import * as path from 'path';
import * as fs from 'fs';
import { discordRpc } from './discord';

let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;
let isQuitting = false;

// Register privileged custom scheme before app is ready
protocol.registerSchemesAsPrivileged([
  {
    scheme: 'app',
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      corsEnabled: true,
      stream: true,
    },
  },
]);

const gotTheLock = app.requestSingleInstanceLock();
if (!gotTheLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    if (process.platform === 'win32') {
      app.setAppUserModelId('com.music.resso.desktop');
    }

    setupCustomProtocol();
    createMainWindow();
    setupTray();
    setupMediaShortcuts();
    setupIpc();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createMainWindow();
      }
    });
  });
}

function getAppDistPath(): string {
  // In development and production (app.asar), app-dist is sibling to dist
  return path.join(__dirname, '../app-dist');
}

function setupCustomProtocol() {
  const mimeTypes: Record<string, string> = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.mjs': 'application/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
    '.ttf': 'font/ttf',
    '.wasm': 'application/wasm',
  };

  protocol.handle('app', (request) => {
    try {
      const url = new URL(request.url);
      let pathname = decodeURIComponent(url.pathname);
      if (pathname === '/' || !path.extname(pathname)) {
        pathname = '/index.html';
      }

      const safePath = path.normalize(pathname).replace(/^(\.\.[\/\\])+/, '').replace(/^\/+/, '');
      let filePath = path.join(getAppDistPath(), safePath);

      if (!fs.existsSync(filePath)) {
        filePath = path.join(getAppDistPath(), 'index.html');
      }

      const ext = path.extname(filePath).toLowerCase();
      const data = fs.readFileSync(filePath);

      return new Response(data, {
        status: 200,
        headers: {
          'Content-Type': mimeTypes[ext] || 'application/octet-stream',
          'Access-Control-Allow-Origin': '*',
        },
      });
    } catch {
      return new Response('Not Found', { status: 404 });
    }
  });
}

function createMainWindow() {
  const iconPath = path.join(__dirname, '../assets/icon.png');

  mainWindow = new BrowserWindow({
    title: 'Resso',
    width: 1240,
    height: 820,
    minWidth: 960,
    minHeight: 620,
    backgroundColor: '#0a0a0c',
    icon: iconPath,
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: false,
      webSecurity: false,
    },
  });

  const devUrl = process.env.VITE_DEV_SERVER_URL;
  if (devUrl) {
    mainWindow.loadURL(devUrl);
  } else {
    mainWindow.loadURL('app://resso/index.html');
  }

  // Allow F12 or Ctrl+Shift+I to open DevTools
  mainWindow.webContents.on('before-input-event', (_event, input) => {
    if (input.key === 'F12' || (input.control && input.shift && input.key.toLowerCase() === 'i')) {
      mainWindow?.webContents.toggleDevTools();
    }
  });

  mainWindow.once('ready-to-show', () => {
    mainWindow?.show();
  });

  mainWindow.on('close', (event) => {
    if (!isQuitting) {
      event.preventDefault();
      mainWindow?.hide();
    }
  });
}

function setupTray() {
  const iconPath = path.join(__dirname, '../assets/icon.png');
  try {
    tray = new Tray(iconPath);
    tray.setToolTip('Resso Music');

    const contextMenu = Menu.buildFromTemplate([
      {
        label: 'Play / Pause',
        click: () => mainWindow?.webContents.send('media:toggle'),
      },
      {
        label: 'Next Track',
        click: () => mainWindow?.webContents.send('media:next'),
      },
      {
        label: 'Previous Track',
        click: () => mainWindow?.webContents.send('media:prev'),
      },
      { type: 'separator' },
      {
        label: 'Show Resso',
        click: () => {
          mainWindow?.show();
          mainWindow?.focus();
        },
      },
      {
        label: 'Quit',
        click: () => {
          isQuitting = true;
          app.quit();
        },
      },
    ]);

    tray.setContextMenu(contextMenu);
    tray.on('double-click', () => {
      if (mainWindow?.isVisible()) {
        mainWindow.hide();
      } else {
        mainWindow?.show();
        mainWindow?.focus();
      }
    });
  } catch (err) {
    console.error('Failed to initialize tray:', err);
  }
}

function setupMediaShortcuts() {
  try {
    globalShortcut.register('MediaPlayPause', () => {
      mainWindow?.webContents.send('media:toggle');
    });
    globalShortcut.register('MediaNextTrack', () => {
      mainWindow?.webContents.send('media:next');
    });
    globalShortcut.register('MediaPreviousTrack', () => {
      mainWindow?.webContents.send('media:prev');
    });
  } catch (err) {
    console.error('Failed to register global media keys:', err);
  }
}

function setupIpc() {
  // Window controls
  ipcMain.on('window:minimize', () => mainWindow?.minimize());
  ipcMain.on('window:maximize', () => {
    if (mainWindow?.isMaximized()) {
      mainWindow.unmaximize();
    } else {
      mainWindow?.maximize();
    }
  });
  ipcMain.on('window:close', () => {
    isQuitting = true;
    app.quit();
  });

  // Bridge call handler (CORS-free HTTP fetch & Discord RPC)
  ipcMain.handle('bridge:call', async (_event, method: string, params: any) => {
    // 1. YouTube & General HTTP Fetch
    if (method === 'yt.fetch' || method === 'lyrics.fetch' || method === 'httpRequest') {
      const url = params.url;
      const httpMethod = params.method || 'GET';
      const headers = { ...(params.headers || {}) };
      let requestBody: any = params.body;
      if (params.body && params.bodyEncoding === 'base64') {
        requestBody = Buffer.from(params.body, 'base64');
      }

      if (method === 'yt.fetch') {
        headers['User-Agent'] =
          headers['User-Agent'] ||
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36';
        headers['Origin'] = headers['Origin'] || 'https://music.youtube.com';
        headers['Referer'] = headers['Referer'] || 'https://music.youtube.com/';
      }

      const res = await fetch(url, {
        method: httpMethod,
        headers,
        body: ['GET', 'HEAD'].includes(httpMethod.toUpperCase()) ? undefined : requestBody,
      });

      const responseHeaders: Record<string, string> = {};
      res.headers.forEach((value, key) => {
        responseHeaders[key.toLowerCase()] = value;
      });

      let responseBody = '';
      let isBase64 = false;
      const contentType = res.headers.get('content-type') || '';
      if (contentType.includes('audio') || contentType.includes('octet-stream') || contentType.includes('image')) {
        const buffer = await res.arrayBuffer();
        responseBody = Buffer.from(buffer).toString('base64');
        isBase64 = true;
      } else {
        responseBody = await res.text();
      }

      return {
        status: res.status,
        headers: responseHeaders,
        body: responseBody,
        bodyEncoding: isBase64 ? 'base64' : 'text',
        url: res.url,
      };
    }

    // 2. YouTube Auth State
    if (method === 'yt.authState' || method === 'yt.selectChannel' || method === 'yt.setConnected') {
      return {
        connected: false,
        accounts: [],
      };
    }

    // 3. Lyrics
    if (method === 'lyrics.permissions') {
      return { granted: params?.origins || [] };
    }
    if (method === 'lyrics.requestPermissions') {
      return {};
    }

    // 4. Spotify
    if (method === 'spotify.authState') {
      return {
        connected: false,
        user: null,
      };
    }
    if (method === 'spotify.setConnected') {
      return { connected: params?.connected === true };
    }
    if (method === 'spotify.signIn') {
      shell.openExternal('https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F');
      return {};
    }

    // 5. Discord Rich Presence
    if (method === 'discord.state') {
      return {
        connected: true,
        enabled: true,
        user: null,
      };
    }
    if (method === 'discord.presence') {
      const { track } = params || {};
      if (!track) {
        discordRpc.clearActivity();
      } else {
        const rate = track.playbackRate > 0 ? track.playbackRate : 1;
        const startTimestamp = Math.floor((Date.now() - (track.positionMs || 0) / rate) / 1000);
        const endTimestamp = track.durationMs
          ? Math.floor(startTimestamp + track.durationMs / rate / 1000)
          : undefined;

        discordRpc.setActivity({
          details: track.title,
          state: track.artist,
          timestamps: {
            start: startTimestamp,
            end: endTimestamp,
          },
          assets: {
            large_image: track.artworkUrl || 'resso_logo',
            large_text: track.album || track.title,
          },
        });
      }
      return {};
    }
    if (method === 'discord.setActivity') {
      discordRpc.setActivity(params);
      return { ok: true };
    }
    if (method === 'discord.clearActivity') {
      discordRpc.clearActivity();
      return { ok: true };
    }
    if (method === 'discord.status') {
      return { connected: true };
    }

    return { ok: true };
  });
}

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
});
