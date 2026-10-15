package com.whitejack.bots.brain;

import static com.whitejack.bots.brain.Cards.orEmpty;

import com.whitejack.bots.view.HeartsView;
import com.whitejack.contract.Card;
import com.whitejack.contract.Rank;
import com.whitejack.contract.Suit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a careful player knows at this point of the hand, worked out from one view: every card
 * played so far, what is still out, who has shown out of which suit, where the Q♠ is, and what
 * changed hands in the pass. A pure function of the view, so the brain stays stateless and a
 * reconnect loses nothing.
 */
final class HeartsMemory {

    static final Card QUEEN_OF_SPADES = Card.of(Rank.QUEEN, Suit.SPADES);
    static final Card KING_OF_SPADES = Card.of(Rank.KING, Suit.SPADES);
    static final Card ACE_OF_SPADES = Card.of(Rank.ACE, Suit.SPADES);

    /** Where the Q♠ is, as far as this player can tell. */
    enum Queen { MINE, PLAYED, PASSED, OUT }

    final String me;
    final List<Card> hand;
    /** Seat ids in table order; empty when the view carries no seats. */
    final List<String> order;
    final Map<String, String> nicks = new LinkedHashMap<>();
    final Set<Card> played = new HashSet<>();
    /** Cards neither in my hand nor played, low to high per suit: they are in the other three hands. */
    final Map<Suit, List<Card>> unseen = new EnumMap<>(Suit.class);
    final Map<String, Set<Suit>> voids = new LinkedHashMap<>();
    final Map<String, Integer> handPoints = new LinkedHashMap<>();
    final List<Card> myPass;
    final List<Card> received;
    final String passedTo;
    final String receivedFrom;
    final Queen queen;
    /** Who played and who took the Q♠, when it has gone. */
    final String queenPlayedBy;
    final String queenTakenBy;
    /** An opponent who has taken every point so far and is in reach of all 26, else null. */
    final String shooter;

    private HeartsMemory(HeartsView view, String me) {
        this.me = me;
        this.hand = orEmpty(view.myHand());
        List<HeartsView.SeatView> seats = orEmpty(view.seats()).stream()
                .sorted(Comparator.comparingInt(HeartsView.SeatView::index)).toList();
        this.order = seats.stream().map(HeartsView.SeatView::id).toList();
        seats.forEach(seat -> {
            nicks.put(seat.id(), seat.nick());
            handPoints.put(seat.id(), seat.handPoints());
        });

        String playedBy = null;
        String takenBy = null;
        List<List<HeartsView.PlayedCard>> all = new ArrayList<>();
        for (HeartsView.Trick trick : orEmpty(view.tricks())) {
            all.add(orEmpty(trick.plays()));
            if (orEmpty(trick.plays()).stream().anyMatch(p -> p.card().equals(QUEEN_OF_SPADES))) takenBy = trick.winner();
        }
        all.add(orEmpty(view.trick()));
        for (List<HeartsView.PlayedCard> plays : all) {
            if (plays.isEmpty()) continue;
            Suit led = plays.get(0).card().suit();
            for (HeartsView.PlayedCard play : plays) {
                played.add(play.card());
                if (play.card().equals(QUEEN_OF_SPADES)) playedBy = play.player();
                if (play.card().suit() != led) voids.computeIfAbsent(play.player(), p -> EnumSet.noneOf(Suit.class)).add(led);
            }
        }
        for (Suit suit : Suit.values()) unseen.put(suit, new ArrayList<>());
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                Card card = Card.of(rank, suit);
                if (!hand.contains(card) && !played.contains(card)) unseen.get(suit).add(card);
            }
        }

        this.myPass = orEmpty(view.myPass());
        this.received = orEmpty(view.received());
        int offset = switch (String.valueOf(view.passDirection())) {
            case "LEFT" -> 1;
            case "ACROSS" -> 2;
            case "RIGHT" -> 3;
            default -> 0;
        };
        int at = order.indexOf(me);
        this.passedTo = offset == 0 || at < 0 ? null : order.get((at + offset) % order.size());
        this.receivedFrom = offset == 0 || at < 0 ? null : order.get(Math.floorMod(at - offset, order.size()));

        this.queenPlayedBy = playedBy;
        this.queenTakenBy = takenBy;
        if (hand.contains(QUEEN_OF_SPADES)) queen = Queen.MINE;
        else if (playedBy != null) queen = Queen.PLAYED;
        else if (myPass.contains(QUEEN_OF_SPADES) && passedTo != null && !received.isEmpty()) queen = Queen.PASSED;
        else queen = Queen.OUT;
        this.shooter = findShooter();
    }

    static HeartsMemory of(HeartsView view, String me) {
        return new HeartsMemory(view, me);
    }

    /** The Q♠ is still in an opponent's hand, so high spades can catch it. */
    boolean queenAgainstMe() {
        return queen == Queen.OUT || queen == Queen.PASSED;
    }

    /** Cards of the same suit still out that beat {@code card}. */
    int higherOut(Card card) {
        return (int) unseen.get(card.suit()).stream().filter(c -> c.rank().value() > card.rank().value()).count();
    }

    int lowerOut(Card card) {
        return (int) unseen.get(card.suit()).stream().filter(c -> c.rank().value() < card.rank().value()).count();
    }

    int out(Suit suit) {
        return unseen.get(suit).size();
    }

    boolean isVoid(String player, Suit suit) {
        return voids.getOrDefault(player, Set.of()).contains(suit);
    }

    long held(Suit suit) {
        return hand.stream().filter(c -> c.suit() == suit).count();
    }

    /** Opponents still to play after me in a trick that already has {@code played} cards. */
    List<String> toAct(int played) {
        int at = order.indexOf(me);
        if (at < 0) return List.of();
        List<String> after = new ArrayList<>();
        for (int i = 1; i < order.size() - played; i++) after.add(order.get((at + i) % order.size()));
        return after;
    }

    String nick(String player) {
        if (player == null) return "?";
        if (player.equals(me)) return "you";
        return nicks.getOrDefault(player, "?");
    }

    /** Who sits where, from my seat: left gets my left pass and plays right after me. */
    String relative(String player) {
        int at = order.indexOf(me);
        int them = order.indexOf(player);
        if (at < 0 || them < 0 || at == them) return "";
        return switch (Math.floorMod(them - at, order.size())) {
            case 1 -> "left";
            case 2 -> "across";
            default -> "right";
        };
    }

    private String findShooter() {
        String only = null;
        for (Map.Entry<String, Integer> entry : handPoints.entrySet()) {
            if (entry.getValue() <= 0) continue;
            if (only != null) return null; // points are split: nobody can take all 26
            only = entry.getKey();
        }
        if (only == null || only.equals(me)) return null;
        int points = handPoints.get(only);
        boolean hasQueen = only.equals(queenTakenBy);
        int hearts = points - (hasQueen ? 13 : 0);
        if (queen == Queen.PLAYED && !hasQueen) return null;
        return (hasQueen && hearts >= 2) || hearts >= 6 ? only : null;
    }

    static int points(Card card) {
        if (card.equals(QUEEN_OF_SPADES)) return 13;
        return card.suit() == Suit.HEARTS ? 1 : 0;
    }
}
