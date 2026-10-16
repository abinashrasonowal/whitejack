// One WebSocket to /ws, speaking the §7 envelope. Reconnects with backoff and re-sends `join`
// on every open: the server treats a join from an already-seated player as the reconnect.
import { ServerFrame } from '@/types/wire';
import { readSession, writeSession } from '@/utils/storage';

export type SocketStatus = 'connecting' | 'open' | 'reconnecting' | 'closed';

interface JoinPayload {
  room: string;
  nick: string;
  clientSeed: string;
}

const MAX_BACKOFF_MS = 15_000;

export class WhitejackSocket {
  private ws: WebSocket | null = null;
  private nextId = 0;
  private attempt = 0;
  private stopped = false;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private joinPayload: JoinPayload | null = null;
  private frameListeners = new Set<(frame: ServerFrame) => void>();
  private statusListeners = new Set<(status: SocketStatus) => void>();

  constructor(private readonly token: string) {}

  /** @returns a function that unsubscribes the listener */
  onFrame(listener: (frame: ServerFrame) => void): () => void {
    this.frameListeners.add(listener);
    return () => this.frameListeners.delete(listener);
  }

  /** @returns a function that unsubscribes the listener */
  onStatus(listener: (status: SocketStatus) => void): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  connect(): void {
    this.stopped = false;
    this.open();
  }

  join(room: string, nick: string, clientSeed: string): void {
    this.joinPayload = { room, nick, clientSeed };
    if (this.ws?.readyState === WebSocket.OPEN) this.send('join', this.joinPayload);
  }

  /**
   * @returns the frame id, which a later `accepted`/`rejected` echoes as `re`; null when the
   *     socket is not open and nothing was sent. Frames are not queued: a move made while
   *     reconnecting may no longer be legal by the time the socket is back.
   */
  send(type: string, payload: object = {}): string | null {
    if (this.ws?.readyState !== WebSocket.OPEN) return null;
    const id = `c${++this.nextId}`;
    this.ws.send(JSON.stringify({ v: 1, id, type, payload }));
    return id;
  }

  close(): void {
    this.stopped = true;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.ws?.close(1000);
    this.ws = null;
    this.setStatus('closed');
  }

  private open(): void {
    this.setStatus(this.attempt === 0 ? 'connecting' : 'reconnecting');
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    // The cookie normally identifies us; ?t= is the fallback when it is withheld (§4).
    const ws = new WebSocket(`${scheme}://${window.location.host}/ws?t=${encodeURIComponent(this.token)}`);
    this.ws = ws;

    ws.onopen = () => {
      this.attempt = 0;
      this.setStatus('open');
      if (this.joinPayload) this.send('join', this.joinPayload);
    };
    ws.onmessage = (event) => {
      let frame: ServerFrame;
      try {
        frame = JSON.parse(event.data);
      } catch {
        return;
      }
      this.frameListeners.forEach((listener) => listener(frame));
    };
    ws.onclose = (event) => {
      if (this.ws !== ws) return;
      this.ws = null;
      // 4000+ is the server saying "this room is gone": retrying cannot help.
      if (this.stopped || event.code >= 4000) {
        this.setStatus('closed');
        return;
      }
      const delay = Math.min(1000 * 2 ** this.attempt++, MAX_BACKOFF_MS);
      this.setStatus('reconnecting');
      this.retryTimer = setTimeout(() => this.open(), delay);
    };
  }

  private setStatus(status: SocketStatus): void {
    this.statusListeners.forEach((listener) => listener(status));
  }
}

/** 32 random bytes as lower-case hex, kept per room so a refresh reuses the committed seed. */
export function clientSeedFor(room: string): string {
  const key = `whitejack.seed.${room}`;
  const existing = readSession(key);
  if (existing) return existing;
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const seed = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  writeSession(key, seed); // if storage is blocked, a fresh seed per page load is still valid
  return seed;
}
