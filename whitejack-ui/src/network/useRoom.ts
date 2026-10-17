import { useEffect, useRef, useState } from 'react';
import { LobbyView, ServerFrame } from '@/types/wire';
import { WhitejackSocket, SocketStatus, clientSeedFor } from './socket';

export interface RoomError {
  error: string;
  detail: string;
}

/** Errors after which staying on the table is pointless. */
const FATAL = new Set(['ROOM_NOT_FOUND', 'FAULT']);

/**
 * One live room. `gameId` comes from every view frame, so a tab refreshed mid-game still knows
 * which board to render. The latest lobby is kept after the game starts because game views carry no
 * host, and only the host may close the room.
 */
export function useRoom(room: string, nick: string, token: string) {
  const socket = useRef<WhitejackSocket | null>(null);
  const [status, setStatus] = useState<SocketStatus>('connecting');
  const [lobby, setLobby] = useState<LobbyView | null>(null);
  const [game, setGame] = useState<unknown | null>(null);
  const [gameId, setGameId] = useState<string | null>(null);
  const [error, setError] = useState<RoomError | null>(null);

  useEffect(() => {
    const s = new WhitejackSocket(token);
    socket.current = s;
    setLobby(null);
    setGame(null);
    setGameId(null);
    setError(null);

    s.onStatus(setStatus);
    s.onFrame((frame: ServerFrame) => {
      switch (frame.type) {
        case 'update':
        case 'sync':
          setGameId(frame.game);
          if (frame.viewType === 'lobby') setLobby(frame.view);
          else setGame(frame.view);
          break;
        case 'accepted':
          setError(null);
          break;
        case 'rejected':
          setError({ error: frame.error, detail: frame.detail });
          if (FATAL.has(frame.error)) s.close();
          break;
        case 'fault':
          setError({ error: 'FAULT', detail: frame.detail });
          s.close();
          break;
      }
    });
    s.join(room, nick, clientSeedFor(room));
    s.connect();
    return () => s.close();
  }, [room, nick, token]);

  /** Sends, or says so on the table when the socket is down, instead of silently dropping the frame. */
  const send = (type: string, payload?: object) => {
    const id = socket.current?.send(type, payload) ?? null;
    if (id == null) setError({ error: 'NOT_CONNECTED', detail: 'Not connected right now, so that was not sent. Try again once the table is live.' });
    return id;
  };

  return {
    status,
    lobby,
    game,
    gameId,
    error,
    fatal: error != null && FATAL.has(error.error),
    start: () => send('start'),
    intent: (payload: object) => send('intent', payload),
    leave: () => send('leave'),
    closeRoom: () => send('close'),
  };
}
