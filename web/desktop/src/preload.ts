import { ipcRenderer } from 'electron';

// Desktop Bridge handler for Resso Web
window.addEventListener('message', async (event) => {
  const data = event.data;
  if (!data || data.channel !== 'Resso-bridge' || data.direction !== 'to-extension') {
    return;
  }

  const { id, method, params } = data;

  if (method === 'ping') {
    window.postMessage(
      {
        channel: 'Resso-bridge',
        protocol: 1,
        direction: 'to-page',
        id,
        ok: true,
        result: {
          version: '2.1.0',
          protocol: 1,
          browser: 'chromium',
          capabilities: ['yt', 'yt.auth', 'yt.stats', 'lyrics', 'spotify', 'discord'],
        },
      },
      '*',
    );
    return;
  }

  try {
    const result = await ipcRenderer.invoke('bridge:call', method, params);
    window.postMessage(
      {
        channel: 'Resso-bridge',
        protocol: 1,
        direction: 'to-page',
        id,
        ok: true,
        result,
      },
      '*',
    );
  } catch (err: any) {
    window.postMessage(
      {
        channel: 'Resso-bridge',
        protocol: 1,
        direction: 'to-page',
        id,
        ok: false,
        error: {
          code: err?.code || 'internal',
          message: err?.message || 'Bridge call failed',
        },
      },
      '*',
    );
  }
});

// Handle media controls from main process
ipcRenderer.on('media:toggle', () => {
  if (navigator.mediaSession && (navigator.mediaSession as any).playbackState) {
    const event = new KeyboardEvent('keydown', { code: 'Space', key: ' ', bubbles: true });
    window.dispatchEvent(event);
  } else {
    const playBtn = document.querySelector('button[aria-label="Play"], button[aria-label="Pause"]') as HTMLButtonElement | null;
    playBtn?.click();
  }
});

ipcRenderer.on('media:next', () => {
  const nextBtn = document.querySelector('button[aria-label="Next"], button[aria-label="Next track"]') as HTMLButtonElement | null;
  nextBtn?.click();
});

ipcRenderer.on('media:prev', () => {
  const prevBtn = document.querySelector('button[aria-label="Previous"], button[aria-label="Previous track"]') as HTMLButtonElement | null;
  prevBtn?.click();
});

// Expose Desktop API
(window as any).isRessoDesktop = true;
(window as any).ressoDesktop = {
  minimize: () => ipcRenderer.send('window:minimize'),
  maximize: () => ipcRenderer.send('window:maximize'),
  close: () => ipcRenderer.send('window:close'),
  platform: process.platform,
};
