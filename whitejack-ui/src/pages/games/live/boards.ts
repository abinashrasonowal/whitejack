import React from 'react';
import { GinRummyBoard } from '@/pages/games/gin-rummy/GinRummyBoard';
import { HeartsBoard } from '@/pages/games/hearts/HeartsBoard';
import { PokerBoard } from '@/pages/games/poker/PokerBoard';
import { ViewByGame } from '@/types/wire';

/** What every live board takes: the server's view of its game, and a way to send intents. */
export interface BoardProps<V> {
  view: V;
  playerId: string;
  intent: (payload: object) => string | null;
  /** Extra sidebar sections the live table adds, such as the bots' reasoning. */
  aside?: React.ReactNode;
}

/** The board for each server game module. A new live game is one line here plus its board. */
export const LIVE_BOARDS: { [G in keyof ViewByGame]: React.FC<BoardProps<ViewByGame[G]>> } = {
  hearts: HeartsBoard,
  'gin-rummy': GinRummyBoard,
  poker: PokerBoard,
};

/**
 * The board for `gameId`, or null for a game this client cannot draw. The view arrives as
 * `unknown` from the socket; the server's `game` id is what vouches for its shape.
 */
export function boardFor(gameId: string | null): React.FC<BoardProps<unknown>> | null {
  if (!gameId || !(gameId in LIVE_BOARDS)) return null;
  return LIVE_BOARDS[gameId as keyof ViewByGame] as React.FC<BoardProps<unknown>>;
}
