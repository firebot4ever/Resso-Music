import * as net from 'net';

const CLIENT_ID = '1543679166967193620';

enum Opcode {
  HANDSHAKE = 0,
  FRAME = 1,
  CLOSE = 2,
  PING = 3,
  PONG = 4,
}

export class DiscordDesktopRpc {
  private socket: net.Socket | null = null;
  private connected = false;
  private currentActivity: any = null;

  connect() {
    if (this.socket) return;

    for (let i = 0; i < 10; i++) {
      const pipePath =
        process.platform === 'win32'
          ? `\\\\?\\pipe\\discord-ipc-${i}`
          : `${process.env.XDG_RUNTIME_DIR || process.env.TMPDIR || process.env.TMP || '/tmp'}/discord-ipc-${i}`;

      try {
        const sock = net.connect(pipePath, () => {
          this.socket = sock;
          this.connected = true;
          this.send(Opcode.HANDSHAKE, { v: 1, client_id: CLIENT_ID });
          if (this.currentActivity) {
            this.setActivity(this.currentActivity);
          }
        });

        sock.on('error', () => {
          this.cleanup();
        });

        sock.on('close', () => {
          this.cleanup();
        });

        break;
      } catch {
        continue;
      }
    }
  }

  private cleanup() {
    this.connected = false;
    if (this.socket) {
      try {
        this.socket.destroy();
      } catch {}
      this.socket = null;
    }
  }

  private send(opcode: Opcode, payload: any) {
    if (!this.socket || this.socket.destroyed) return;
    const json = JSON.stringify(payload);
    const length = Buffer.byteLength(json);
    const packet = Buffer.alloc(8 + length);
    packet.writeInt32LE(opcode, 0);
    packet.writeInt32LE(length, 4);
    packet.write(json, 8, length, 'utf8');
    try {
      this.socket.write(packet);
    } catch {
      this.cleanup();
    }
  }

  setActivity(activity: any) {
    this.currentActivity = activity;
    if (!this.connected) {
      this.connect();
      return;
    }

    this.send(Opcode.FRAME, {
      cmd: 'SET_ACTIVITY',
      args: {
        pid: process.pid,
        activity: activity || null,
      },
      nonce: Date.now().toString(),
    });
  }

  clearActivity() {
    this.setActivity(null);
  }
}

export const discordRpc = new DiscordDesktopRpc();
