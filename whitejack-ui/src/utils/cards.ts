import { Card, Rank, Suit } from '@/types/game';
import { WireCard, WireRank, WireSuit } from '@/types/wire';

const RANKS: Record<WireRank, [Rank, number]> = {
  TWO: ['2', 2], THREE: ['3', 3], FOUR: ['4', 4], FIVE: ['5', 5], SIX: ['6', 6],
  SEVEN: ['7', 7], EIGHT: ['8', 8], NINE: ['9', 9], TEN: ['10', 10],
  JACK: ['J', 11], QUEEN: ['Q', 12], KING: ['K', 13], ACE: ['A', 14],
};

/** Server card → the shape CardView already renders. */
export function toUiCard(card: WireCard): Card {
  const [rank, value] = RANKS[card.rank];
  const suit = card.suit.toLowerCase() as Suit;
  return { id: `${rank}-${suit}`, rank, suit, value };
}

/** A stable React key for a server card. */
export const cardKey = (card: WireCard) => `${card.rank}-${card.suit}`;

/** Same rank and suit; false when either is missing. */
export const sameCard = (a: WireCard | null, b: WireCard | null) =>
  !!a && !!b && a.rank === b.rank && a.suit === b.suit;

export const containsCard = (cards: WireCard[], card: WireCard | null) => cards.some((c) => sameCard(c, card));

const SUIT_ORDER: Record<WireSuit, number> = { SPADES: 0, DIAMONDS: 1, CLUBS: 2, HEARTS: 3 };

/** A hand laid out left to right: spades, diamonds, clubs, hearts, each low to high. */
export function sortHand(cards: WireCard[]): WireCard[] {
  return [...cards].sort(
    (a, b) => SUIT_ORDER[a.suit] - SUIT_ORDER[b.suit] || RANKS[a.rank][1] - RANKS[b.rank][1]
  );
}

/** Crockford base32, six characters — RoomRegistry's alphabet. */
export const ROOM_CODE = /^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$/;
