// Mirrors of what whitejack-server puts on the wire (architecture §7). Views, never events.

export type Phase = 'LOBBY' | 'PLAYING' | 'FINISHED';

export interface LobbyMember {
  seat: number;
  id: string;
  nick: string;
  connected: boolean;
}

/** engine-core LobbyView: what everyone sees before the host starts. */
export interface LobbyView {
  room: string;
  gameId: string;
  phase: Phase;
  host: string | null;
  members: LobbyMember[];
}

export type WireRank =
  | 'TWO' | 'THREE' | 'FOUR' | 'FIVE' | 'SIX' | 'SEVEN' | 'EIGHT'
  | 'NINE' | 'TEN' | 'JACK' | 'QUEEN' | 'KING' | 'ACE';
export type WireSuit = 'CLUBS' | 'DIAMONDS' | 'HEARTS' | 'SPADES';

export interface WireCard {
  rank: WireRank;
  suit: WireSuit;
}

/** games/hearts HeartsView. Other players' hands arrive only as `cardCount`. */
export type HeartsPhase = 'PASSING' | 'PLAYING' | 'SCORING' | 'GAME_OVER';
export type PassDirection = 'LEFT' | 'RIGHT' | 'ACROSS' | 'HOLD';

export interface HeartsSeat {
  index: number;
  id: string;
  nick: string;
  cardCount: number;
  handPoints: number;
  score: number;
  passed: boolean;
}

export interface HeartsPlay {
  player: string;
  card: WireCard;
}

export interface HeartsTrick {
  plays: HeartsPlay[];
  winner: string;
}

export interface HeartsView {
  phase: HeartsPhase;
  hand: number;
  passDirection: PassDirection;
  heartsBroken: boolean;
  tricksPlayed: number;
  seats: HeartsSeat[];
  myHand: WireCard[];
  /** Cards you may pass or play right now, computed by the server; empty when it is not your move. */
  legal: WireCard[];
  trick: HeartsPlay[];
  leader: string | null;
  onClock: string | null;
  lastTrick: HeartsPlay[];
  lastTrickWinner: string | null;
  /** Every trick finished this hand, in order. */
  tricks: HeartsTrick[];
  /** The three cards you passed this hand; empty on a hold hand. */
  myPass: WireCard[];
  /** The three cards passed to you; empty until the exchange. */
  received: WireCard[];
  /** One row per finished hand, points in seat order. */
  history: number[][];
  winner: string | null;
}

/** games/gin-rummy GinView. Legal moves are spelled out, so the client (or a bot) never needs the rules. */
export type GinPhase = 'DRAW' | 'DISCARD' | 'SCORING' | 'GAME_OVER';
export type DrawSource = 'STOCK' | 'DISCARD';

export interface GinArrangement {
  melds: WireCard[][];
  deadwood: WireCard[];
  deadwoodPoints: number;
}

export interface GinRevealed {
  player: string;
  melds: WireCard[][];
  deadwood: WireCard[];
  laidOff: WireCard[];
  deadwoodPoints: number;
}

export interface GinHandResult {
  outcome: 'KNOCK' | 'GIN' | 'UNDERCUT' | 'DEAD';
  knocker: string | null;
  winner: string | null;
  points: number;
  hands: GinRevealed[];
}

export interface GinSeat {
  index: number;
  id: string;
  nick: string;
  cardCount: number;
  score: number;
}

export interface GinView {
  phase: GinPhase;
  hand: number;
  dealer: string;
  onClock: string | null;
  seats: GinSeat[];
  myHand: WireCard[];
  myMelds: GinArrangement | null;
  stockCount: number;
  discardTop: WireCard | null;
  discardCount: number;
  takenFromDiscard: WireCard | null;
  /** Your legal moves right now; all empty when it is not your turn. */
  drawSources: DrawSource[];
  discards: WireCard[];
  knockDiscards: WireCard[];
  ginDiscards: WireCard[];
  lastHand: GinHandResult | null;
  history: number[][];
  winner: string | null;
}

/** `game` is the server module id ("poker", "hearts"); it picks the board that renders `view`. */
type ViewFrame =
  | { viewType: 'lobby'; game: string; view: LobbyView }
  | { viewType: 'game'; game: string; view: unknown };

export type ServerFrame =
  | ({ v: 1; type: 'update'; room: string } & ViewFrame)
  | ({ v: 1; type: 'sync'; seq: number } & ViewFrame)
  | { v: 1; type: 'accepted'; re: string; seq: number }
  | { v: 1; type: 'rejected'; re: string | null; error: string; detail: string }
  | { v: 1; type: 'fault'; room: string; detail: string };

/** games/poker PokerView. Other players' hole cards appear only in `lastHand`, after a showdown. */
export type PokerPhase = 'BETTING' | 'SETTLED' | 'GAME_OVER';
export type PokerStreet = 'PREFLOP' | 'FLOP' | 'TURN' | 'RIVER';
export type PokerMove = 'fold' | 'check' | 'call' | 'raise';

export interface PokerSeat {
  index: number;
  id: string;
  nick: string;
  stack: number;
  /** Chips put in on the current street. */
  bet: number;
  /** Dealt into this hand; a player with no chips sits out. */
  inHand: boolean;
  folded: boolean;
  allIn: boolean;
  lastAction: string | null;
}

export interface PokerPot {
  amount: number;
  winners: string[];
  /** Null when everyone else folded. */
  handName: string | null;
}

export interface PokerReveal {
  player: string;
  cards: WireCard[];
  handName: string;
  best: WireCard[];
}

export interface PokerHandResult {
  hand: number;
  board: WireCard[];
  reveals: PokerReveal[];
  pots: PokerPot[];
}

export interface PokerView {
  phase: PokerPhase;
  street: PokerStreet;
  hand: number;
  smallBlind: number;
  bigBlind: number;
  dealer: string;
  seats: PokerSeat[];
  myCards: WireCard[];
  board: WireCard[];
  pot: number;
  currentBet: number;
  onClock: string | null;
  /** Moves the viewer may make now; empty when it is not their move. */
  legal: PokerMove[];
  toCall: number;
  minRaiseTo: number;
  maxRaiseTo: number;
  lastHand: PokerHandResult | null;
  winner: string | null;
}

/** whitejack-bots Decision: one move a bot sent, with every option it weighed. */
export interface BotDecision {
  hand: number;
  player: string;
  nick: string;
  /** play · pass (Hearts), draw · discard (Gin), act (Poker). */
  phase: string;
  /** `probability` is null when the advisor gave none (a heuristic or forced move). */
  options: { label: string; probability: number | null }[];
  /** Indices into `options`, best first. */
  picked: number[];
  source: 'ADVISOR' | 'FORCED' | 'HEURISTIC' | 'AFTER_REJECT';
  /** How long the advisor took; 0 when it was not asked. */
  millis: number;
}

/** GET /api/rooms/{room}/bot-notes: finished hands only, newest first. */
export interface BotHandNotes {
  hand: number;
  decisions: BotDecision[];
}
