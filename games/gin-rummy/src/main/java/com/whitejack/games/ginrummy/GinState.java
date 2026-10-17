package com.whitejack.games.ginrummy;

import com.whitejack.contract.Card;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.Seat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deeply immutable, as engine invariant 2 requires: every collection is re-copied on
 * construction.
 *
 * @param seats the two seats, in table order
 * @param hand zero-based hand number
 * @param turn whoever must draw or discard now
 * @param stock face-down cards, index 0 on top
 * @param discard the face-up pile, last element on top
 * @param takenFromDiscard the card just taken from the discard pile, which may not go straight
 *     back; null otherwise
 * @param history one row per finished hand, points in seat order
 * @param lastHand how the previous hand ended; null before the first one finishes
 */
public record GinState(
        List<Seat> seats,
        int hand,
        PlayerId dealer,
        GinPhase phase,
        PlayerId turn,
        Map<PlayerId, List<Card>> hands,
        List<Card> stock,
        List<Card> discard,
        Card takenFromDiscard,
        Map<PlayerId, Integer> scores,
        List<List<Integer>> history,
        HandResult lastHand) {

    public GinState {
        seats = List.copyOf(seats);
        Map<PlayerId, List<Card>> copy = new LinkedHashMap<>();
        hands.forEach((player, cards) -> copy.put(player, List.copyOf(cards)));
        hands = Map.copyOf(copy);
        stock = List.copyOf(stock);
        discard = List.copyOf(discard);
        scores = Map.copyOf(scores);
        history = history.stream().map(List::copyOf).toList();
    }

    /** Before the first deal: everyone at zero. {@code createInitialState} folds a Dealt onto this. */
    static GinState seated(List<Seat> seats) {
        Map<PlayerId, Integer> zero = new LinkedHashMap<>();
        for (Seat seat : seats) zero.put(seat.id(), 0);
        return new GinState(seats, -1, seats.get(1).id(), GinPhase.SCORING, null, Map.of(), List.of(), List.of(), null,
                zero, List.of(), null);
    }

    List<Card> handOf(PlayerId player) {
        return hands.getOrDefault(player, List.of());
    }

    int positionOf(PlayerId player) {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).id().equals(player)) return i;
        }
        return -1;
    }

    PlayerId opponentOf(PlayerId player) {
        return seats.get(1 - positionOf(player)).id();
    }

    Card discardTop() {
        return discard.isEmpty() ? null : discard.get(discard.size() - 1);
    }
}
