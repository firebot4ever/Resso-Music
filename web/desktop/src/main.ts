import { app, BrowserWindow, globalShortcut, ipcMain, Menu, Tray } from 'electron';
import * as path from 'path';
import { discordRpc } from './discord';

let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;
let isQuitting = false;

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

  // Load the web app
  const devUrl = process.env.VITE_DEV_SERVER_URL;
  if (devUrl) {
    mainWindow.loadURL(devUrl);
  } else {
    mainWindow.loadFile(path.join(__dirname, '../app-dist/index.html'));
  }

  mainWindow.once('ready-to-show', () => {
    mainWindow?.show();
  });

  mainWindow.on('close', (event) => {
    // If not quitting, minimize to tray
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

  // Bridge call handler (HTTP fetch & Discord)
  ipcMain.handle('bridge:call', async (_event, method: string, params: any) => {
    if (method === 'httpRequest') {
      const { url, method: httpMethod = 'GET', headers = {}, body, bodyEncoding } = params;
      let requestBody: any = body;
      if (body && bodyEncoding === 'base64') {
        requestBody = Buffer.from(body, 'base64');
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

    // Default response for other bridge methods
    return { ok: true };
  });
}

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
});
