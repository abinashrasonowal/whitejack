package com.whitejack.games.hearts;

import com.whitejack.contract.Card;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.Seat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deeply immutable, as engine invariant 2 requires: every collection is re-copied on
 * construction, so no caller keeps a handle that could change a state another thread is
 * projecting.
 *
 * <p>Whose turn it is is derived ({@link HeartsGame#onClock}), never stored: the leader plus
 * the size of the trick already say it, and a second copy could disagree.
 *
 * @param seats the four seats, in table order; position in this list is the seat
 * @param hand zero-based hand number, which also picks the pass direction
 * @param passes cards chosen for passing this hand; they stay in the hand until the exchange
 * @param leader who led the current trick; null while passing
 * @param handPoints penalty points taken so far this hand
 * @param scores running totals across hands
 * @param history one row per finished hand, the points scored, in seat order
 * @param lastTrick the most recently completed trick, kept so the table can show its last card
 * @param tricks every trick completed this hand, in order; public, so a player can count cards
 * @param exchanged what each seat gave in this hand's pass, kept after the exchange so a player
 *     can be shown their own pass; empty before the exchange and on a hold hand
 */
public record HeartsState(
        List<Seat> seats,
        int hand,
        HeartsPhase phase,
        Map<PlayerId, List<Card>> hands,
        Map<PlayerId, List<Card>> passes,
        List<PlayedCard> trick,
        PlayerId leader,
        int tricksPlayed,
        boolean heartsBroken,
        Map<PlayerId, Integer> handPoints,
        Map<PlayerId, Integer> scores,
        List<List<Integer>> history,
        List<PlayedCard> lastTrick,
        PlayerId lastTrickWinner,
        List<Trick> tricks,
        Map<PlayerId, List<Card>> exchanged) {

    public HeartsState {
        seats = List.copyOf(seats);
        hands = copyHands(hands);
        passes = copyHands(passes);
        trick = List.copyOf(trick);
        handPoints = Map.copyOf(handPoints);
        scores = Map.copyOf(scores);
        history = history.stream().map(List::copyOf).toList();
        lastTrick = List.copyOf(lastTrick);
        tricks = List.copyOf(tricks);
        exchanged = copyHands(exchanged);
    }

    /** Before the first deal: everyone at zero. {@code createInitialState} folds a Dealt onto this. */
    static HeartsState seated(List<Seat> seats) {
        Map<PlayerId, Integer> zero = zeroes(seats);
        return new HeartsState(seats, -1, HeartsPhase.SCORING, Map.of(), Map.of(), List.of(), null, 0,
                false, zero, zero, List.of(), List.of(), null, List.of(), Map.of());
    }

    static Map<PlayerId, List<Card>> copyHands(Map<PlayerId, List<Card>> hands) {
        Map<PlayerId, List<Card>> copy = new LinkedHashMap<>();
        hands.forEach((player, cards) -> copy.put(player, List.copyOf(cards)));
        return Map.copyOf(copy);
    }

    static Map<PlayerId, Integer> zeroes(List<Seat> seats) {
        Map<PlayerId, Integer> zero = new LinkedHashMap<>();
        for (Seat seat : seats) zero.put(seat.id(), 0);
        return zero;
    }

    List<Card> handOf(PlayerId player) {
        return hands.getOrDefault(player, List.of());
    }

    /** Position in {@link #seats}, or -1 for someone not at the table. */
    int positionOf(PlayerId player) {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).id().equals(player)) return i;
        }
        return -1;
    }

    PassDirection passDirection() {
        return PassDirection.forHand(Math.max(hand, 0));
    }
}
