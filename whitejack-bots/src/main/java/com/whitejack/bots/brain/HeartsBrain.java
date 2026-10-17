package com.whitejack.bots.brain;

import static com.whitejack.bots.brain.Cards.orEmpty;
import static com.whitejack.bots.brain.HeartsMemory.ACE_OF_SPADES;
import static com.whitejack.bots.brain.HeartsMemory.KING_OF_SPADES;
import static com.whitejack.bots.brain.HeartsMemory.QUEEN_OF_SPADES;
import static com.whitejack.bots.brain.HeartsMemory.points;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Options come straight from the view's {@code legal} list. The fallback counts cards with
 * {@link HeartsMemory}: it passes the dangerous cards (high spades unless the queen is well
 * guarded, high hearts, a short suit to void), leads cards that something still out will beat,
 * flushes the Q♠ with low spades, ducks under the winning card but takes clean last-seat tricks
 * with its highest card, dumps the Q♠ and high hearts when void, and stops a moon shot.
 *
 * <p>The advisor gets the same knowledge in words: every trick this hand, the pass both ways,
 * what is still out per suit, who is void, where the Q♠ is, and which legal cards can win.
 */
public final class HeartsBrain implements GameBrain<HeartsView> {

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
                + "card when you play last, and take a trick with no points in it when you play last to get rid of "
                + "a high card. When you cannot follow suit, discard the Q♠ first, then high spades while the Q♠ is "
                + "still out, then high hearts. Lead low cards that a card still out will beat, and do not lead "
                + "spades while you hold the Q♠. Count cards: the position lists every trick played this hand, the "
                + "cards still out in each suit, and which players are void in which suit. If you lack the Q♠, K♠ "
                + "and A♠, leading spades flushes the queen out of an opponent's hand. Do not lead a suit an "
                + "opponent is void in unless your card is sure to be beaten, since they will discard points on the "
                + "winner. Never lead the A♠ or K♠ while the Q♠ is still out. If one opponent has taken every "
                + "point so far, stop the moon shot by taking a trick with a heart in it.";
    }

    @Override
    public Optional<Choice> choose(HeartsView view, String me) {
        List<Card> legal = orEmpty(view.legal()).stream().sorted().toList();
        if (legal.isEmpty()) return Optional.empty();
        HeartsMemory memory = HeartsMemory.of(view, me);
        List<Move> options = legal.stream()
                .map(card -> new Move(card.toString(), Cards.json(card)))
                .toList();

        // PASSING is simultaneous: onClock points at only one of the seats still to pass, so a
        // non-empty legal list is the trigger, not the clock.
        if ("PASSING".equals(view.phase()) && legal.size() >= 3) {
            List<Integer> pass = passFallback(legal, orEmpty(view.myHand()));
            String state = describe(view, me, memory) + "\nPass 3 cards " + view.passDirection()
                    + (memory.passedTo == null ? "" : ", to " + memory.nick(memory.passedTo)) + ".";
            return Optional.of(new Choice("pass:" + view.hand(), state,
                    "Which card is most important to pass away, to avoid taking points?", options, 3, pass, HeartsBrain::pass));
        }
        if ("PLAYING".equals(view.phase()) && me.equals(view.onClock())) {
            String key = "play:" + view.hand() + ":" + view.tricksPlayed() + ":" + orEmpty(view.trick()).size();
            return Optional.of(new Choice(key, describe(view, me, memory),
                    "Which card should you play to take the fewest points this hand?", options, 1,
                    List.of(playFallback(view, me, legal)),
                    picked -> Cards.NODES.objectNode().put("type", "play").set("card", picked.get(0).value())));
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- passing

    /** The three cards most worth passing, by index into {@code legal}. */
    static List<Integer> passFallback(List<Card> legal, List<Card> hand) {
        long lowSpades = hand.stream().filter(c -> c.suit() == Suit.SPADES && c.rank().value() < Rank.QUEEN.value()).count();
        boolean guarded = lowSpades >= 4;
        Map<Suit, Long> length = hand.stream().collect(Collectors.groupingBy(Card::suit, Collectors.counting()));

        Set<Card> picks = new LinkedHashSet<>();
        // 1. Cards that catch the queen or 13 points, then the hearts most likely to win tricks.
        if (!guarded) {
            for (Card card : List.of(QUEEN_OF_SPADES, ACE_OF_SPADES, KING_OF_SPADES)) {
                if (legal.contains(card)) picks.add(card);
            }
        }
        legal.stream().filter(c -> c.suit() == Suit.HEARTS && c.rank().value() >= Rank.QUEEN.value())
                .sorted(Comparator.reverseOrder()).forEach(picks::add);
        List<Card> chosen = new ArrayList<>(picks.stream().limit(3).toList());

        // 2. A side suit short enough to pass away entirely leaves a void to discard into.
        int slots = 3 - chosen.size();
        if (slots > 0) {
            legal.stream().map(Card::suit).filter(s -> s == Suit.CLUBS || s == Suit.DIAMONDS).distinct()
                    .filter(s -> length.getOrDefault(s, 0L) <= slots)
                    .min(Comparator.comparingLong((Suit s) -> length.getOrDefault(s, 0L))
                            .thenComparing(s -> highest(legal, s), Comparator.reverseOrder()))
                    .ifPresent(s -> legal.stream().filter(c -> c.suit() == s).forEach(chosen::add));
        }

        // 3. Fill up with the highest of whatever is left.
        legal.stream().filter(c -> !chosen.contains(c))
                .sorted(Comparator.comparingInt((Card c) -> passScore(c, guarded, length)).reversed()
                        .thenComparing(Comparator.reverseOrder()))
                .limit(Math.max(0, 3 - chosen.size()))
                .forEach(chosen::add);
        return chosen.stream().map(legal::indexOf).toList();
    }

    private static int highest(List<Card> cards, Suit suit) {
        return cards.stream().filter(c -> c.suit() == suit).mapToInt(c -> c.rank().value()).max().orElse(0);
    }

    private static int passScore(Card card, boolean guarded, Map<Suit, Long> length) {
        int value = card.rank().value();
        if (card.suit() == Suit.SPADES) {
            if (value >= Rank.QUEEN.value()) return guarded ? value - 40 : 100 + value;
            return value - 50; // low spades protect against the queen
        }
        if (card.suit() == Suit.HEARTS) return value >= Rank.JACK.value() ? 3 * value : value;
        int shortSuit = length.getOrDefault(card.suit(), 0L) <= 2 ? 15 : 0;
        return 2 * value + shortSuit;
    }

    // ---------------------------------------------------------------- playing

    /** The index into {@code legal} of the card standard play would pick. */
    static int playFallback(HeartsView view, String me, List<Card> legal) {
        HeartsMemory memory = HeartsMemory.of(view, me);
        List<HeartsView.PlayedCard> trick = orEmpty(view.trick());
        if (trick.isEmpty()) return legal.indexOf(lead(legal, memory, view.tricksPlayed()));

        Suit led = trick.get(0).card().suit();
        HeartsView.PlayedCard winning = trick.stream().filter(p -> p.card().suit() == led)
                .max(Comparator.comparing(HeartsView.PlayedCard::card)).orElseThrow();
        Card high = winning.card();
        int points = trick.stream().mapToInt(p -> points(p.card())).sum();
        boolean last = trick.size() == 3;
        List<String> toAct = memory.toAct(trick.size());
        boolean following = legal.get(0).suit() == led;
        if (!following) return legal.indexOf(dump(legal, memory, winning.player(), toAct, led));

        List<Card> beaters = legal.stream().filter(c -> c.rank().value() > high.rank().value())
                .filter(c -> !c.equals(QUEEN_OF_SPADES)).toList();
        List<Card> losers = legal.stream().filter(c -> c.rank().value() < high.rank().value()).toList();

        // Moon defence: a point trick the shooter is winning is worth taking from them.
        if (memory.shooter != null && memory.shooter.equals(winning.player()) && points > 0 && !beaters.isEmpty()) {
            Card take = beaters.get(0);
            if (last || memory.higherOut(take) == 0) return legal.indexOf(take);
        }

        // Last to play on a clean trick: winning it costs nothing and sheds the highest card.
        if (last && points == 0 && !beaters.isEmpty()) return legal.indexOf(beaters.get(beaters.size() - 1));

        if (!losers.isEmpty()) return legal.indexOf(losers.get(losers.size() - 1));

        List<Card> pool = beaters.isEmpty() ? legal : beaters;
        if (last) return legal.indexOf(pool.get(pool.size() - 1));
        // Forced to win before everyone has played: high while nobody can dump on it, else low.
        boolean someoneVoid = toAct.stream().anyMatch(p -> memory.isVoid(p, led));
        boolean queenRisk = led == Suit.SPADES && memory.queenAgainstMe();
        boolean risky = someoneVoid || queenRisk || led == Suit.HEARTS || memory.out(led) < toAct.size();
        return legal.indexOf(risky ? pool.get(0) : pool.get(pool.size() - 1));
    }

    /** Lowest risk of winning the trick, weighted by what winning it would cost. */
    private static Card lead(List<Card> legal, HeartsMemory memory, int tricksPlayed) {
        return legal.stream().min(Comparator.comparingDouble((Card c) -> leadCost(c, memory, tricksPlayed))
                .thenComparingInt(c -> c.rank().value())).orElseThrow();
    }

    static double leadCost(Card card, HeartsMemory memory, int tricksPlayed) {
        if (card.equals(QUEEN_OF_SPADES)) return memory.higherOut(card) == 0 ? 100 : 60;
        Suit suit = card.suit();
        int higher = memory.higherOut(card);
        int lower = memory.lowerOut(card);
        List<String> others = memory.toAct(0);
        long voids = others.stream().filter(p -> memory.isVoid(p, suit)).count();

        // How likely this card wins: certain with nothing higher out, near nil when it is the lowest.
        double win;
        if (higher == 0) win = 1;
        else if (lower == 0) win = 0.02;
        else win = Math.min(0.9, (double) lower / (lower + 3.0 * higher));

        double cost = 1 + 4 * voids;
        if (suit == Suit.HEARTS) cost += 3;
        if (suit == Suit.SPADES && memory.queenAgainstMe() && card.rank().value() > Rank.QUEEN.value()) cost += 13;
        if (higher + lower == 0) cost += 6; // nobody can follow: everyone discards onto it
        double score = 10 * win * cost;

        if (suit == Suit.SPADES) {
            boolean flush = memory.queenAgainstMe() && !memory.hand.contains(KING_OF_SPADES)
                    && !memory.hand.contains(ACE_OF_SPADES) && card.rank().value() < Rank.QUEEN.value();
            if (flush) score -= 2;
            if (memory.queen == HeartsMemory.Queen.MINE) score += 4;
        }
        // Early on, running a short side suit out makes a void to discard into later.
        if (tricksPlayed < 6 && suit != Suit.HEARTS && suit != Suit.SPADES) score += 0.3 * memory.held(suit);
        return score;
    }

    /** Void in the led suit: rid the hand of whatever would cost the most later. */
    private static Card dump(List<Card> legal, HeartsMemory memory, String winner, List<String> toAct, Suit led) {
        // Never feed a moon shooter: give them a dangerous card with no points in it instead.
        boolean feedingShooter = memory.shooter != null && memory.shooter.equals(winner)
                && toAct.stream().noneMatch(p -> !memory.isVoid(p, led) && !p.equals(memory.shooter));
        if (feedingShooter) {
            Optional<Card> clean = legal.stream().filter(c -> points(c) == 0)
                    .max(Comparator.comparingDouble(c -> danger(c, memory)));
            if (clean.isPresent()) return clean.get();
            return legal.stream().filter(c -> c.suit() == Suit.HEARTS).min(Comparator.naturalOrder())
                    .orElse(legal.get(0));
        }
        if (legal.contains(QUEEN_OF_SPADES)) return QUEEN_OF_SPADES;
        if (memory.queenAgainstMe()) {
            for (Card card : List.of(ACE_OF_SPADES, KING_OF_SPADES)) {
                if (legal.contains(card)) return card;
            }
        }
        return legal.stream().max(Comparator.comparingDouble(c -> danger(c, memory))).orElseThrow();
    }

    /** How likely a card is to win a costly trick later; hearts also cost a point each when taken. */
    private static double danger(Card card, HeartsMemory memory) {
        double value = card.rank().value();
        int higher = memory.higherOut(card);
        int lower = memory.lowerOut(card);
        if (higher == 0 && lower > 0) value += 6; // a sure winner whenever this suit is played
        if (lower == 0) value -= 6; // nothing out underneath: it can always be ducked
        if (card.suit() == Suit.HEARTS) value += 4;
        if (card.suit() == Suit.SPADES && memory.queenAgainstMe() && card.rank().value() > Rank.QUEEN.value()) value += 10;
        if (memory.held(card.suit()) <= 2) value += 1.5; // nearly a void
        return value;
    }

    private static ObjectNode pass(List<Move> picked) {
        ObjectNode intent = Cards.NODES.objectNode().put("type", "pass");
        ArrayNode cards = intent.putArray("cards");
        picked.forEach(move -> cards.add(move.value()));
        return intent;
    }

    // ---------------------------------------------------------------- the position in words

    /** "7♦ (2 higher out, 4 lower)": what a card is worth, so the advisor need not count. */
    private static String counted(Card card, HeartsMemory memory) {
        int higher = memory.higherOut(card);
        int lower = memory.lowerOut(card);
        String note;
        if (higher + lower == 0) note = "no other " + card.suit().symbol() + " out";
        else if (higher == 0) note = "highest " + card.suit().symbol() + " out, wins if led";
        else note = higher + " higher out, " + lower + " lower";
        return card + " (" + note + ")";
    }

    static String describe(HeartsView view, String me) {
        return describe(view, me, HeartsMemory.of(view, me));
    }

    private static String describe(HeartsView view, String me, HeartsMemory memory) {
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
        if (!memory.order.isEmpty() && memory.order.contains(me)) {
            lines.add("Seating: " + memory.order.stream().filter(p -> !p.equals(me))
                    .map(p -> memory.nick(p) + ("across".equals(memory.relative(p)) ? " across from you" : " on your " + memory.relative(p))).collect(Collectors.joining(", "))
                    + ". Play goes to your left.");
        }
        passLines(view, memory, lines);

        String scores = orEmpty(view.seats()).stream()
                .map(seat -> (seat.id().equals(me) ? "you" : seat.nick()) + " " + seat.score()
                        + " (+" + seat.handPoints() + " this hand)")
                .collect(Collectors.joining(", "));
        lines.add("Scores: " + scores);

        if ("PLAYING".equals(view.phase()) || !orEmpty(view.tricks()).isEmpty()) {
            countingLines(view, memory, lines);
        }

        List<HeartsView.PlayedCard> trick = orEmpty(view.trick());
        if (!trick.isEmpty()) {
            Suit led = trick.get(0).card().suit();
            HeartsView.PlayedCard winning = trick.stream().filter(p -> p.card().suit() == led)
                    .max(Comparator.comparing(HeartsView.PlayedCard::card)).orElseThrow();
            int points = trick.stream().mapToInt(p -> points(p.card())).sum();
            List<String> toAct = memory.toAct(trick.size());
            String after = toAct.isEmpty() ? "you play last."
                    : "still to play after you: " + toAct.stream().map(p -> memory.nick(p)
                            + (memory.isVoid(p, led) ? " (void in " + led.symbol() + ", may discard points)" : ""))
                            .collect(Collectors.joining(", ")) + ".";
            lines.add("Current trick, in order: " + played(memory, trick) + ". " + winning.card() + " from "
                    + memory.nick(winning.player()) + " is winning; " + points + " point" + (points == 1 ? "" : "s")
                    + " in it so far; " + after);
        } else if ("PLAYING".equals(view.phase())) {
            lines.add("You lead this trick.");
        }
        if ("PLAYING".equals(view.phase()) && !orEmpty(view.legal()).isEmpty()) {
            lines.add("Cards you may play: " + orEmpty(view.legal()).stream().sorted()
                    .map(card -> counted(card, memory)).collect(Collectors.joining(", ")) + ".");
        }
        return String.join("\n", lines);
    }

    private static void passLines(HeartsView view, HeartsMemory memory, List<String> lines) {
        if (!memory.myPass.isEmpty()) {
            List<Card> stillOut = memory.myPass.stream().filter(c -> !memory.played.contains(c)).toList();
            String to = memory.passedTo == null ? "" : " to " + memory.nick(memory.passedTo);
            lines.add("You passed " + Cards.list(memory.myPass) + to + "."
                    + ("PLAYING".equals(view.phase()) ? " Not yet played (so still in " + memory.nick(memory.passedTo)
                            + "'s hand): " + Cards.list(stillOut) + "." : ""));
        }
        if (!memory.received.isEmpty()) {
            lines.add("You received " + Cards.list(memory.received)
                    + (memory.receivedFrom == null ? "" : " from " + memory.nick(memory.receivedFrom)) + ".");
        }
        if ("HOLD".equals(view.passDirection())) lines.add("No passing this hand.");
    }

    private static void countingLines(HeartsView view, HeartsMemory memory, List<String> lines) {
        List<HeartsView.Trick> tricks = orEmpty(view.tricks());
        if (!tricks.isEmpty()) {
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < tricks.size(); i++) {
                HeartsView.Trick t = tricks.get(i);
                int points = orEmpty(t.plays()).stream().mapToInt(p -> points(p.card())).sum();
                rows.add("  " + (i + 1) + ". " + played(memory, orEmpty(t.plays())) + " -> taken by "
                        + memory.nick(t.winner()) + (points > 0 ? " (" + points + " pt" + (points == 1 ? "" : "s") + ")" : ""));
            }
            lines.add("Tricks this hand:\n" + String.join("\n", rows));
        } else {
            List<HeartsView.PlayedCard> last = orEmpty(view.lastTrick());
            if (!last.isEmpty() && view.lastTrickWinner() != null) {
                lines.add("Last trick: " + played(memory, last) + " (taken by " + memory.nick(view.lastTrickWinner()) + ").");
            }
        }

        lines.add("Cards still out (in the other three hands): " + Arrays.stream(Suit.values())
                .map(s -> s.symbol() + " " + memory.out(s) + (memory.out(s) == 0 ? "" : " [" + Cards.list(memory.unseen.get(s)) + "]"))
                .collect(Collectors.joining("; ")) + ".");

        List<String> shown = memory.voids.entrySet().stream().filter(e -> !e.getKey().equals(memory.me))
                .map(e -> memory.nick(e.getKey()) + " is void in "
                        + e.getValue().stream().map(Suit::symbol).collect(Collectors.joining(" ")))
                .toList();
        lines.add(shown.isEmpty() ? "Nobody has shown out of a suit yet." : "Known voids: " + String.join("; ", shown) + ".");

        lines.add(switch (memory.queen) {
            case MINE -> "The Q♠ is in your hand.";
            case PLAYED -> "The Q♠ is gone: " + memory.nick(memory.queenPlayedBy) + " played it"
                    + (memory.queenTakenBy == null ? "" : " and " + memory.nick(memory.queenTakenBy) + " took it")
                    + ". High spades are now safe.";
            case PASSED -> "The Q♠ is in " + memory.nick(memory.passedTo) + "'s hand (you passed it).";
            case OUT -> "The Q♠ has not been played and is in an opponent's hand; the A♠ and K♠ can catch it.";
        });
        if (memory.shooter != null) {
            lines.add("Warning: " + memory.nick(memory.shooter) + " has taken every point so far ("
                    + memory.handPoints.get(memory.shooter) + ") and may be shooting the moon; take a heart to stop it.");
        }
    }

    private static String played(HeartsMemory memory, List<HeartsView.PlayedCard> plays) {
        return plays.stream().map(play -> memory.nick(play.player()) + " " + play.card())
                .collect(Collectors.joining(", "));
    }

    @Override
    public boolean isOver(HeartsView view) {
        return "GAME_OVER".equals(view.phase());
    }
}
