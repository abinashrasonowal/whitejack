import { GameDefinition, GameKey, RoomRules } from '@/types/game';

/** Every game the lobby offers. `live` games run on whitejack-server; the rest are offline demos. */
export const GAME_DEFINITIONS: Record<GameKey, GameDefinition> = {
  poker: {
    key: 'poker',
    name: 'Poker',
    shortName: 'Poker (2–8P)',
    badge: '2–8 Players',
    playerCountLabel: '2–8 Players',
    playersCount: 2,
    suitGlyph: '♠',
    suitColor: 'text-stone-900',
    accent: 'blue',
    description:
      'No-limit Texas Hold’em. Two hole cards, five on the board — bet, bluff and shove until one player holds every chip.',
    tag: 'Live · Server',
    rulesOverview:
      'Everyone starts with 1,000 chips; blinds start at 10/20 and double every 10 hands. Each player gets two private cards, then the flop (3), turn and river come out with a betting round before each and after the last. Fold, check, call or raise — no limit. Best five-card hand from your two cards and the board wins; side pots handle all-ins. Last player with chips wins.',
    targetScore: 'All chips',
    special: 'No Limit',
    defaultCode: '',
    serverGameId: 'poker',
  },
  hearts: {
    key: 'hearts',
    name: 'Hearts',
    shortName: 'Hearts (4P)',
    badge: '4 Players',
    playerCountLabel: '4 Players',
    playersCount: 4,
    suitGlyph: '♥',
    suitColor: 'text-red-600',
    accent: 'rose',
    description:
      'Duck every heart and the dreaded Q♠, or take all 26 points to shoot the moon and hand them to everyone else.',
    tag: 'Live · Server',
    rulesOverview:
      'Four players. Pass three cards left, right, across, then hold. The 2♣ leads; follow suit; no points on the first trick; hearts cannot be led until broken. Each heart is 1 pt and the Q♠ 13 — take all 26 to shoot the moon. First to 100 ends the game; lowest score wins.',
    targetScore: '100',
    special: 'Shoot the Moon',
    defaultCode: 'HRT-8429',
    serverGameId: 'hearts',
    rule1: 'Omnibus (+10 Jack of Diamonds ♦)',
    rule2: 'Passing (Left · Right · Across · Hold)',
    rule3: 'Fast Game to 50 pts (Default 100)',
  },
  gin_rummy: {
    key: 'gin_rummy',
    name: 'Gin Rummy',
    shortName: 'Gin Rummy (2P)',
    badge: 'Head to Head',
    playerCountLabel: '2 Players',
    playersCount: 2,
    suitGlyph: '♦',
    suitColor: 'text-red-600',
    accent: 'amber',
    description:
      'Draw and discard to build sets and runs, then knock at ten deadwood or go gin — but watch for the undercut.',
    tag: 'Live · Server',
    rulesOverview:
      'Two players, 10 cards each. Each turn draw from the stock or the discard pile, then discard. Build sets (3–4 of a rank) and runs (3+ in suit, ace low). Knock with 10 or less deadwood; gin (no deadwood) scores 25 plus the opponent\'s deadwood. The defender lays off onto the knocker\'s melds, and undercuts for 25 plus the difference. First to 100 wins.',
    targetScore: '100',
    special: 'Going Gin',
    defaultCode: '',
    serverGameId: 'gin-rummy',
  },
  spades: {
    key: 'spades',
    name: 'Spades',
    shortName: 'Spades (4P - 2v2)',
    badge: '2v2 Teams',
    playerCountLabel: '4 Players',
    playersCount: 4,
    suitGlyph: '♠',
    suitColor: 'text-stone-950',
    accent: 'emerald',
    description:
      'Partnership trick-taking where spades always trump. Bid exactly, cover your partner and dodge the bag penalty.',
    tag: 'Partnership Bidding',
    rulesOverview:
      'Partners sit across each other. Each player bids the tricks they expect to win. Spades are always trump. Make exact bids to avoid bag penalties (-100 for 10 bags) or bid Blind Nil for high stakes!',
    targetScore: '500',
    special: 'Blind Nil',
    defaultCode: 'SPD-3190',
    comingSoon: true,
    rule1: 'Blind Nil Bidding (+200 / -200 pts)',
    rule2: 'Bag Penalty Enabled (10 bags = -100)',
    rule3: 'First to 500 Points (or -200 loss)',
  },
  euchre: {
    key: 'euchre',
    name: 'Euchre',
    shortName: 'Euchre (4P - 2v2)',
    badge: '24-Card Deck',
    playerCountLabel: '4 Players',
    playersCount: 4,
    suitGlyph: '♦',
    suitColor: 'text-red-600',
    accent: 'violet',
    description:
      'Fast 24-card trick-taking. Name the trump suit, hunt the Right and Left Bowers, and go alone to march the hand.',
    tag: 'Trump Calling',
    rulesOverview:
      'Played with a 24-card deck (9 through Ace). Highest trump is the Jack of the trump suit (Right Bower), followed by the other Jack of same color (Left Bower). First team to 10 points wins.',
    targetScore: '10',
    special: 'Lone Hand',
    defaultCode: 'ECH-5021',
    rule1: 'Stick the Dealer (forced call)',
    rule2: 'Canadian Loner rule enabled',
    rule3: 'Standard 10-point match',
  },
  oh_hell: {
    key: 'oh_hell',
    name: 'Oh Hell / Contract',
    shortName: 'Oh Hell (3-6P)',
    badge: 'Variable Hands',
    playerCountLabel: '3-6P',
    playersCount: 4,
    suitGlyph: '♣',
    suitColor: 'text-stone-950',
    accent: 'orange',
    description:
      'Exact-contract bidding over hands that grow and shrink. Miss your bid by even one trick and the round scores nothing.',
    tag: 'Exact Contract Bidding',
    rulesOverview:
      'Each hand features an increasing or decreasing number of cards. Players must bid the exact number of tricks they will take. Only players who hit their exact contract earn bonus points.',
    targetScore: 'Most Pts',
    special: 'Exact Contract',
    defaultCode: 'OHL-7814',
    rule1: 'Strict Bidding (Total bids cannot equal hand)',
    rule2: 'Decreasing hand sizes (10 down to 1)',
    rule3: '10 pts + 1 per trick score format',
  },
  custom: {
    key: 'custom',
    name: 'Custom Deck / Sandbox',
    shortName: 'Custom Deck (2-8P)',
    badge: 'Freeform',
    playerCountLabel: '2-8P',
    playersCount: 4,
    suitGlyph: '🂠',
    suitColor: 'text-stone-950',
    accent: 'slate',
    description:
      'A freeform table for house games — deck size, jokers, chips and manual dealing are all yours to configure.',
    tag: 'Sandbox Table',
    rulesOverview:
      'Freeform sandbox room with custom deck configurations, jokers, customizable dealing controls, private hands, and chips. Perfect for homebrew games or classic variants.',
    special: 'House Rules',
    defaultCode: 'BOX-9912',
    rule1: 'Include Jokers (2 Cards)',
    rule2: 'Enable Table Poker Chips / Counters',
    rule3: 'Manual Dealer Rotation',
  },
};

/** Initial house-rule toggles per game, before the host changes any. */
export const DEFAULT_RULES: Record<GameKey, RoomRules> = {
  poker: { rule1: false, rule2: false, rule3: false },
  hearts: { rule1: true, rule2: true, rule3: false },
  gin_rummy: { rule1: false, rule2: false, rule3: false },
  spades: { rule1: true, rule2: true, rule3: true },
  euchre: { rule1: true, rule2: false, rule3: true },
  oh_hell: { rule1: true, rule2: true, rule3: true },
  custom: { rule1: true, rule2: true, rule3: false },
};

/**
 * The offline demo an old prefixed room code opens (#SPD-3190 → spades), matched on the prefix of
 * each game's `defaultCode`; anything else opens Hearts, as it always has.
 */
export function offlineGameForCode(code: string): GameKey {
  const upper = code.toUpperCase();
  const match = Object.values(GAME_DEFINITIONS).find(
    (def) => def.defaultCode && upper.startsWith(def.defaultCode.split('-')[0])
  );
  return match?.key ?? 'hearts';
}
