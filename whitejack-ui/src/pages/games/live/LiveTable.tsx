import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { BotNotesPanel } from '@/components/BotNotesPanel';
import { GAME_DEFINITIONS } from '@/config/games';
import { ROOM_SLOT_ID } from '@/layout/Header';
import { addBot, botNotes, isBotNick, listGames } from '@/network/api';
import { useRoom } from '@/network/useRoom';
import { BotHandNotes } from '@/types/wire';
import { soundFx } from '@/utils/audio';
import { boardFor } from './boards';
import { WaitingRoom } from './WaitingRoom';

interface LiveTableProps {
  room: string;
  nick: string;
  playerId: string;
  token: string;
  onLeave: () => void;
}

const STATUS_LABEL = {
  connecting: 'Connecting…',
  open: 'Live',
  reconnecting: 'Reconnecting…',
  closed: 'Disconnected',
} as const;

/**
 * Every server-backed room: the navbar room controls, errors, the waiting room, then the board for whichever
 * game the server says this room plays. Boards receive views and send intents; none holds rules.
 */
export const LiveTable: React.FC<LiveTableProps> = ({ room, nick, playerId, token, onLeave }) => {
  const { status, lobby, game, gameId, error, fatal, start, intent, leave, closeRoom } = useRoom(room, nick, token);
  const [copied, setCopied] = useState(false);
  const def = Object.values(GAME_DEFINITIONS).find((d) => d.serverGameId === gameId);
  const isHost = lobby?.host === playerId;
  const [maxPlayers, setMaxPlayers] = useState<number | null>(null);
  const [slot, setSlot] = useState<HTMLElement | null>(null);
  const [notes, setNotes] = useState<BotHandNotes[]>([]);
  const hasBots = !!lobby?.members.some((m) => isBotNick(m.nick));

  // A hand's bot reasoning is released once the hand is over, so ask again whenever a new one
  // starts or the game ends. Hands are numbered in every live game but High Card.
  const handNumber = (game as { hand?: number } | null)?.hand ?? -1;
  const gameOver = (game as { phase?: string } | null)?.phase === 'GAME_OVER';
  useEffect(() => {
    if (!hasBots || (handNumber < 1 && !gameOver)) return;
    let live = true;
    botNotes(room)
      .then((n) => live && setNotes(n))
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [room, hasBots, handNumber, gameOver]);

  useEffect(() => setSlot(document.getElementById(ROOM_SLOT_ID)), []);

  useEffect(() => {
    if (!gameId) return;
    let live = true;
    listGames()
      .then((games) => {
        const info = games.find((g) => g.id === gameId);
        if (live && info) setMaxPlayers(info.maxPlayers);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [gameId]);

  const copyInvite = () => {
    navigator.clipboard?.writeText(`${window.location.origin}/#${room}`).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleLeave = () => {
    soundFx.playClick();
    leave();
    onLeave();
  };

  const handleClose = () => {
    soundFx.playClick();
    closeRoom();
    onLeave();
  };

  const aside = <BotNotesPanel notes={notes} hasBots={hasBots} />;

  const board = () => {
    const Board = boardFor(gameId);
    if (!Board) return <p className="text-sm text-stone-600">This client cannot render “{gameId}” yet.</p>;
    return <Board view={game} playerId={playerId} intent={intent} aside={aside} />;
  };

  const headerButton =
    'h-9 px-2.5 rounded-md text-sm font-medium text-stone-600 hover:text-stone-900 hover:bg-stone-100 transition-colors flex items-center gap-1.5 cursor-pointer';

  // The room's identity and exits live in the navbar, so the table gets the whole page.
  const roomControls = (
    <div className="flex items-center gap-2 sm:gap-2.5 mr-1 sm:mr-2">
      <span className="hidden lg:inline text-sm text-stone-500">
        {def?.name ?? 'Live room'}
      </span>
      <button
        type="button"
        onClick={copyInvite}
        title="Copy invite link"
        aria-label={`Room ${room}, ${STATUS_LABEL[status]}. Copy invite link`}
        className="h-8 pl-2.5 pr-2 rounded-md bg-white border border-stone-200 text-stone-800 hover:border-stone-300 transition-colors flex items-center gap-2 cursor-pointer"
      >
        <span
          aria-hidden
          className={`w-1.5 h-1.5 rounded-full ${status === 'open' ? 'bg-felt-500' : 'bg-amber-500 animate-pulse'}`}
        />
        <code className="font-mono-code text-xs tracking-wider">{room}</code>
        <span aria-hidden className="material-symbols-outlined text-[16px] text-stone-400">{copied ? 'check' : 'link'}</span>
      </button>
      {isHost && (
        <button type="button" onClick={handleClose} className={headerButton} title="Close room for everyone">
          <span aria-hidden className="material-symbols-outlined text-[18px]">close</span>
          <span className="hidden sm:inline">Close room</span>
        </button>
      )}
      <button type="button" onClick={handleLeave} className={headerButton} title="Leave room">
        <span aria-hidden className="material-symbols-outlined text-[18px]">logout</span>
        <span className="hidden sm:inline">Leave</span>
      </button>
    </div>
  );

  return (
    // A board in play spans the navbar's width; the waiting room stays a comfortable reading column.
    <div className={`w-full mx-auto px-4 sm:px-8 flex flex-col gap-6 ${game ? 'max-w-[1440px]' : 'max-w-6xl'}`}>
      {slot && createPortal(roomControls, slot)}

      {status !== 'open' && (
        <div role="status" className="rounded-md border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm font-medium text-amber-800">
          {STATUS_LABEL[status]}
        </div>
      )}

      {error && (
        <div
          role="alert"
          className={`rounded-md border px-4 py-3 text-sm ${
            fatal ? 'border-red-200 bg-red-50 text-red-800' : 'border-amber-200 bg-amber-50 text-amber-800'
          }`}
        >
          <strong className="font-mono-code text-xs mr-2">{error.error}</strong>
          {error.detail}
        </div>
      )}

      {game ? (
        board()
      ) : (
        <WaitingRoom
          room={room}
          copied={copied}
          onCopyInvite={copyInvite}
          lobby={lobby}
          playerId={playerId}
          minPlayers={def?.playersCount ?? 2}
          maxPlayers={maxPlayers ?? def?.playersCount ?? 2}
          onStart={start}
          onAddBot={() => addBot(room)}
        />
      )}
    </div>
  );
};
