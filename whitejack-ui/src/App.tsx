import React, { useState, useEffect } from 'react';
import { useSettings } from '@/app/SettingsContext';
import { useIdentity } from '@/app/useIdentity';
import { Footer } from '@/layout/Footer';
import { Header } from '@/layout/Header';
import { SettingsModal } from '@/modals/SettingsModal';
import { createRoom } from '@/network/api';
import { LiveTable } from '@/pages/games/live/LiveTable';
import { ABOUT_HASH, AboutPage } from '@/pages/about/AboutPage';
import { GameTable } from '@/pages/games/offline/GameTable';
import { JoinInvite } from '@/pages/lobby/JoinInvite';
import { LobbyPage } from '@/pages/lobby/LobbyPage';
import { offlineGameForCode } from '@/config/games';
import { GameKey, RoomRules } from '@/types/game';
import { soundFx } from '@/utils/audio';
import { ROOM_CODE } from '@/utils/cards';
import { loadNick, saveNick } from '@/utils/storage';

export default function App() {
  const [currentScreen, setCurrentScreen] = useState<'lobby' | 'game'>('lobby');
  const [activeGameKey, setActiveGameKey] = useState<GameKey>('hearts');
  const [activeRoomCode, setActiveRoomCode] = useState<string>('');
  const [activePlayerName, setActivePlayerName] = useState<string>('');
  const [activeRules, setActiveRules] = useState<RoomRules>({
    rule1: true,
    rule2: true,
    rule3: false,
  });

  const { settings, update } = useSettings();

  const [aboutOpen, setAboutOpen] = useState<boolean>(() => window.location.hash === ABOUT_HASH);
  const [isSettingsOpen, setIsSettingsOpen] = useState<boolean>(false);

  const { me, ensureMe } = useIdentity();
  const [live, setLive] = useState<{ room: string; nick: string } | null>(null);
  /** A room code from an invite link, waiting for the visitor to say who they are. */
  const [invite, setInvite] = useState<string | null>(null);

  const enterLive = (room: string, nick: string) => {
    saveNick(nick);
    setLive({ room, nick });
    window.location.hash = room;
  };

  const handleCreateLive = async (gameId: string, nick: string) => {
    await ensureMe();
    enterLive(await createRoom(gameId), nick);
  };

  // Check URL hash for direct room code join: a six-character server code (#K7M2QX) opens the
  // live table; the older prefixed codes (#HRT-8429) still open the offline demos.
  useEffect(() => {
    const onHash = () => {
      setAboutOpen(window.location.hash === ABOUT_HASH);
      window.scrollTo({ top: 0 });
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    if (window.location.hash === ABOUT_HASH) return;
    const hash = window.location.hash.replace('#', '').trim().toUpperCase();
    if (ROOM_CODE.test(hash)) {
      setInvite(hash);
    } else if (hash && hash.length >= 4) {
      setActiveRoomCode(hash);
      setActiveGameKey(offlineGameForCode(hash));
    }
  }, []);

  const handleToggleSound = () => {
    const updated = !settings.soundEnabled;
    update({ soundEnabled: updated });
    if (updated) {
      soundFx.playClick();
    }
  };

  const handleStartGame = (
    gameKey: GameKey,
    playerName: string,
    roomCode: string,
    rules: RoomRules
  ) => {
    setActiveGameKey(gameKey);
    setActivePlayerName(playerName);
    setActiveRoomCode(roomCode);
    setActiveRules(rules);
    setCurrentScreen('game');
    window.location.hash = roomCode;
  };

  const handleJoinRoom = (roomCode: string, nickname: string) => {
    const code = roomCode.toUpperCase();
    if (ROOM_CODE.test(code)) {
      enterLive(code, nickname);
      return;
    }
    handleStartGame(offlineGameForCode(code), nickname, code, {
      rule1: true,
      rule2: true,
      rule3: false,
    });
  };

  const handleLeaveTable = () => {
    soundFx.playClick();
    setLive(null);
    setInvite(null);
    setCurrentScreen('lobby');
    window.location.hash = '';
  };

  return (
    <div className="min-h-dvh flex flex-col justify-between">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50 focus:rounded-md focus:bg-stone-900 focus:px-3 focus:py-2 focus:text-sm focus:text-white"
      >
        Skip to content
      </a>

      {/* Top Header */}
      <Header
        soundEnabled={settings.soundEnabled}
        onToggleSound={handleToggleSound}
        aboutActive={aboutOpen && live == null && currentScreen === 'lobby'}
        onOpenSettings={() => setIsSettingsOpen(true)}
        inGame={live != null || currentScreen === 'game'}
        gameTitle={live ? 'LIVE ROOM' : activeGameKey.toUpperCase()}
        roomCode={live ? live.room : activeRoomCode}
        onLeaveGame={handleLeaveTable}
        liveRoom={live != null}
      />

      {/* Main Content Area */}
      <main id="main" className="w-full pt-24 pb-14 flex-1 flex flex-col">
        {live ? (
          me ? (
            <LiveTable
              room={live.room}
              nick={live.nick}
              playerId={me.playerId}
              token={me.token}
              onLeave={handleLeaveTable}
            />
          ) : (
            <p role="status" className="text-center text-sm text-stone-500">Connecting to the server…</p>
          )
        ) : invite ? (
          <JoinInvite
            room={invite}
            initialNickname={loadNick()}
            onJoin={(nick) => {
              setInvite(null);
              enterLive(invite, nick);
            }}
            onCancel={handleLeaveTable}
          />
        ) : aboutOpen && currentScreen === 'lobby' ? (
          <AboutPage />
        ) : currentScreen === 'lobby' ? (
          <LobbyPage
            initialNickname={activePlayerName}
            onStartGame={handleStartGame}
            onJoinRoom={handleJoinRoom}
            onCreateLive={handleCreateLive}
          />
        ) : (
          <GameTable
            gameKey={activeGameKey}
            roomCode={activeRoomCode}
            playerName={activePlayerName}
            rules={activeRules}
            onLeaveTable={handleLeaveTable}
            onOpenSettings={() => setIsSettingsOpen(true)}
          />
        )}
      </main>

      {/* Footer */}
      <Footer wide={live != null || currentScreen === 'game'} room={live?.room} />

      {/* Modals */}
      <SettingsModal
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
      />
    </div>
  );
}
