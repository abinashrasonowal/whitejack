package com.whitejack.bots.brain;

import static com.whitejack.bots.brain.Cards.orEmpty;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whitejack.bots.Choice;
import com.whitejack.bots.GameBrain;
import com.whitejack.bots.Move;
import com.whitejack.bots.view.HeartsView;
import com.whitejack.contract.Card;
import com.whitejack.contract.Rank;
import com.whitejack.contract.Suit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The Hearts brain as it was before card counting, kept verbatim so {@link HeartsBenchmarkTest}
 * can measure the new one against it.
 *
 * <p>Options come straight from the view's {@code legal} list. The fallback plays standard Hearts:
 * pass the dangerous cards (high spades unless the queen is well guarded, high hearts, a short
 * suit to void), duck under the card winning the trick, dump the Q♠ and high hearts when void,
 * and lead low from a safe suit.
 */
final class LegacyHeartsBrain implements GameBrain<HeartsView> {

    private static final Card QUEEN_OF_SPADES = Card.of(Rank.QUEEN, Suit.SPADES);

    @Override
    public String gameId() {
        return "hearts";
    }

    @Override
    public Class<HeartsView> viewType() {
        return HeartsView.class;
    }

    @Override
    public String rules() {
        return "The game is Hearts with 4 players. Each heart taken is 1 point and the queen of spades is 13; "
                + "LOW score wins, and the game ends when someone reaches 100. Taking every heart and the queen "
                + "of spades (shooting the moon) gives 26 to each opponent instead. You must follow suit if you can. "
                + "Hearts cannot be led until broken. Before each hand you pass 3 cards; passing the Q, K and A of "
                + "spades and high hearts is usually wise, unless you hold four or more low spades to guard the "
                + "queen, and passing a short suit away lets you discard points when it is led. When following, "
                + "play the highest card that still loses the trick; if you must win it, win it with your highest "
                + "card when you play last. When you cannot follow suit, discard the Q♠ first, then high spades "
                + "and high hearts. Lead low cards, and do not lead spades while you hold the Q♠.";
    }

    @Override
    public Optional<Choice> choose(HeartsView view, String me) {
        List<Card> legal = orEmpty(view.legal()).stream().sorted().toList();
        if (legal.isEmpty()) return Optional.empty();
        List<Move> options = legal.stream()
                .map(card -> new Move(card.toString(), Cards.json(card)))
                .toList();

        // PASSING is simultaneous: onClock points at only one of the seats still to pass, so a
        // non-empty legal list is the trigger, not the clock.
        if ("PASSING".equals(view.phase()) && legal.size() >= 3) {
            List<Integer> pass = passFallback(legal, orEmpty(view.myHand()));
            String state = describe(view, me) + "\nPass 3 cards " + view.passDirection() + ".";
            return Optional.of(new Choice("pass:" + view.hand(), state,
                    "Which card is most important to pass away, to avoid taking points?", options, 3, pass, LegacyHeartsBrain::pass));
        }
        if ("PLAYING".equals(view.phase()) && me.equals(view.onClock())) {
            String key = "play:" + view.hand() + ":" + view.tricksPlayed() + ":" + orEmpty(view.trick()).size();
            return Optional.of(new Choice(key, describe(view, me),
                    "Which card should you play to take the fewest points this hand?", options, 1,
                    List.of(playFallback(view, legal)),
                    picked -> Cards.NODES.objectNode().put("type", "play").set("card", picked.get(0).value())));
        }
        return Optional.empty();
    }

    /** The three cards most worth passing, by index into {@code legal}. */
    static List<Integer> passFallback(List<Card> legal, List<Card> hand) {
        long lowSpades = hand.stream().filter(c -> c.suit() == Suit.SPADES && c.rank().value() < Rank.QUEEN.value()).count();
        boolean guarded = lowSpades >= 4;
        Map<Suit, Long> length = hand.stream().collect(Collectors.groupingBy(Card::suit, Collectors.counting()));
        return IntStream.range(0, legal.size()).boxed()
                .sorted(Comparator.comparingInt((Integer i) -> passScore(legal.get(i), guarded, length)).reversed()
                        .thenComparing(i -> legal.get(i), Comparator.reverseOrder()))
                .limit(3)
                .toList();
    }

    private static int passScore(Card card, boolean guarded, Map<Suit, Long> length) {
        int value = card.rank().value();
        if (card.suit() == Suit.SPADES) {
            if (value >= Rank.QUEEN.value()) return guarded ? value : 100 + value;
            return value; // low spades protect against the queen
        }
        if (card.suit() == Suit.HEARTS) return 3 * value;
        int shortSuit = length.getOrDefault(card.suit(), 0L) <= 2 ? 15 : 0;
        return 2 * value + shortSuit;
    }

    /** The index into {@code legal} of the card standard play would pick. */
    static int playFallback(HeartsView view, List<Card> legal) {
        List<HeartsView.PlayedCard> trick = orEmpty(view.trick());
        List<Card> hand = orEmpty(view.myHand());
        if (trick.isEmpty()) return legal.indexOf(lead(legal, hand));

        Suit led = trick.get(0).card().suit();
        Card winning = trick.stream().map(HeartsView.PlayedCard::card).filter(c -> c.suit() == led)
                .max(Comparator.naturalOrder()).orElseThrow();
        boolean following = legal.get(0).suit() == led;
        if (!following) return legal.indexOf(dump(legal));

        List<Card> losers = legal.stream().filter(c -> c.rank().value() < winning.rank().value()).toList();
        if (!losers.isEmpty()) return legal.indexOf(losers.get(losers.size() - 1));
        List<Card> safe = legal.stream().filter(c -> !c.equals(QUEEN_OF_SPADES)).toList();
        List<Card> pool = safe.isEmpty() ? legal : safe;
        int players = Math.max(4, orEmpty(view.seats()).size());
        boolean last = trick.size() == players - 1;
        // Winning anyway: last to play, shed the highest; otherwise go low and hope to be overtaken.
        return legal.indexOf(last ? pool.get(pool.size() - 1) : pool.get(0));
    }

    private static Card lead(List<Card> legal, List<Card> hand) {
        boolean holdQueen = hand.contains(QUEEN_OF_SPADES);
        Map<Suit, Long> length = hand.stream().collect(Collectors.groupingBy(Card::suit, Collectors.counting()));
        return legal.stream().min(Comparator
                .comparingInt((Card c) -> leadPenalty(c, holdQueen))
                .thenComparingInt(c -> c.rank().value())
                .thenComparingLong(c -> length.getOrDefault(c.suit(), 0L))).orElseThrow();
    }

    private static int leadPenalty(Card card, boolean holdQueen) {
        if (card.suit() == Suit.HEARTS) return 2;
        if (card.suit() == Suit.SPADES && (holdQueen || card.rank().value() >= Rank.QUEEN.value())) return 1;
        return 0;
    }

    /** Void in the led suit: the Q♠, then A♠ and K♠, then the highest heart, then the highest card. */
    private static Card dump(List<Card> legal) {
        if (legal.contains(QUEEN_OF_SPADES)) return QUEEN_OF_SPADES;
        for (Rank rank : List.of(Rank.ACE, Rank.KING)) {
            Card card = Card.of(rank, Suit.SPADES);
            if (legal.contains(card)) return card;
        }
        return legal.stream().filter(c -> c.suit() == Suit.HEARTS).max(Comparator.naturalOrder())
                .orElse(legal.get(legal.size() - 1));
    }

    private static ObjectNode pass(List<Move> picked) {
        ObjectNode intent = Cards.NODES.objectNode().put("type", "pass");
        ArrayNode cards = intent.putArray("cards");
        picked.forEach(move -> cards.add(move.value()));
        return intent;
    }

    private static String describe(HeartsView view, String me) {
        List<Card> hand = orEmpty(view.myHand());
        List<String> lines = new ArrayList<>();
        lines.add("Hand " + (view.hand() + 1) + ", tricks played " + view.tricksPlayed()
                + ", hearts " + (view.heartsBroken() ? "broken" : "not broken") + ".");
        lines.add("Your cards: " + Cards.list(hand));
        Set<Suit> held = hand.stream().map(Card::suit).collect(Collectors.toSet());
        List<String> voids = Arrays.stream(Suit.values()).filter(s -> !held.contains(s)).map(Suit::symbol).toList();
        if (!hand.isEmpty() && !voids.isEmpty()) lines.add("You are void in " + String.join(" ", voids) + ".");
        if (hand.contains(QUEEN_OF_SPADES)) {
            long guards = hand.stream().filter(c -> c.suit() == Suit.SPADES && c.rank().value() < Rank.QUEEN.value()).count();
            lines.add("You hold the Q♠, with " + guards + " lower spade" + (guards == 1 ? "" : "s") + " to guard it.");
        }
        String scores = orEmpty(view.seats()).stream()
                .map(seat -> (seat.id().equals(me) ? "you" : seat.nick()) + " " + seat.score()
                        + " (+" + seat.handPoints() + " this hand)")
                .collect(Collectors.joining(", "));
        lines.add("Scores: " + scores);
        List<HeartsView.PlayedCard> last = orEmpty(view.lastTrick());
        if (!last.isEmpty() && view.lastTrickWinner() != null) {
            lines.add("Last trick: " + played(view, last, me) + " (taken by " + nick(view, view.lastTrickWinner(), me) + ").");
        }
        List<HeartsView.PlayedCard> trick = orEmpty(view.trick());
        if (!trick.isEmpty()) {
            Suit led = trick.get(0).card().suit();
            HeartsView.PlayedCard winning = trick.stream().filter(p -> p.card().suit() == led)
                    .max(Comparator.comparing(HeartsView.PlayedCard::card)).orElseThrow();
            int points = trick.stream().mapToInt(p -> points(p.card())).sum();
            int players = Math.max(4, orEmpty(view.seats()).size());
            lines.add("Current trick, in order: " + played(view, trick, me) + ". " + winning.card() + " from "
                    + nick(view, winning.player(), me) + " is winning; " + points + " point" + (points == 1 ? "" : "s")
                    + " in it so far" + (trick.size() == players - 1 ? "; you play last." : "."));
        } else if ("PLAYING".equals(view.phase())) {
            lines.add("You lead this trick.");
        }
        return String.join("\n", lines);
    }

    private static int points(Card card) {
        if (card.equals(QUEEN_OF_SPADES)) return 13;
        return card.suit() == Suit.HEARTS ? 1 : 0;
    }

    private static String played(HeartsView view, List<HeartsView.PlayedCard> plays, String me) {
        return plays.stream().map(play -> nick(view, play.player(), me) + " " + play.card())
                .collect(Collectors.joining(", "));
    }

    private static String nick(HeartsView view, String player, String me) {
        if (player.equals(me)) return "you";
        return orEmpty(view.seats()).stream().filter(seat -> seat.id().equals(player))
                .map(HeartsView.SeatView::nick).findFirst().orElse("?");
    }

    @Override
    public boolean isOver(HeartsView view) {
        return "GAME_OVER".equals(view.phase());
    }
}
