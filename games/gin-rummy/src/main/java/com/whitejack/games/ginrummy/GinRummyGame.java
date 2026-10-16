package com.whitejack.games.ginrummy;

import com.whitejack.contract.Card;
import com.whitejack.contract.Deck;
import com.whitejack.contract.EngineContext;
import com.whitejack.contract.ErrorCode;
import com.whitejack.contract.GameDefinition;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.RandomSource;
import com.whitejack.contract.Seat;
import com.whitejack.contract.Turn;
import com.whitejack.contract.Validation;
import com.whitejack.games.ginrummy.GinEvent.Dealt;
import com.whitejack.games.ginrummy.GinEvent.Discarded;
import com.whitejack.games.ginrummy.GinEvent.Drew;
import com.whitejack.games.ginrummy.GinIntent.Discard;
import com.whitejack.games.ginrummy.GinIntent.Draw;
import com.whitejack.games.ginrummy.HandResult.Outcome;
import com.whitejack.games.ginrummy.HandResult.Revealed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Two-player Gin Rummy. Deal 10 each and turn one card up; the non-dealer goes first and the
 * deal alternates. On a turn: draw from the stock or the discard pile, then discard — never the
 * card just taken from the pile. Knock with 10 or less deadwood; gin is a knock with none.
 * After a knock (not gin) the defender lays off onto the knocker's melds. Knock scores the
 * deadwood difference; gin scores 25 plus the defender's deadwood; an undercut — defender's
 * deadwood no more than the knocker's — gives the defender 25 plus the difference. When two
 * cards remain in the stock the hand is dead and redealt. First to 100 wins.
 *
 * <p>House simplifications: no first-turn upcard offer, and no line or game bonuses.
 *
 * <p>Stateless by construction. The one field is a Jackson mapper, used only to read cards.
 */
public final class GinRummyGame implements GameDefinition<GinState, GinIntent, GinEvent> {

    static final int HAND_SIZE = 10;
    static final int KNOCK_LIMIT = 10;
    static final int GIN_BONUS = 25;
    static final int UNDERCUT_BONUS = 25;
    static final int TARGET = 100;
    /** The hand is dead once the stock is down to this many cards. */
    static final int DEAD_STOCK = 2;
    private static final Duration TURN_LIMIT = Duration.ofSeconds(45);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String id() {
        return "gin-rummy";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public int minPlayers() {
        return 2;
    }

    @Override
    public int maxPlayers() {
        return 2;
    }

    @Override
    public GinIntent parseIntent(JsonNode raw) {
        String type = raw == null ? "" : raw.path("type").asText();
        return switch (type) {
            case "draw" -> switch (raw.path("source").asText()) {
                case "stock" -> new Draw(DrawSource.STOCK);
                case "discard" -> new Draw(DrawSource.DISCARD);
                default -> throw new IllegalArgumentException("draw source must be \"stock\" or \"discard\"");
            };
            case "discard" -> {
                JsonNode knock = raw.path("knock");
                if (!knock.isMissingNode() && !knock.isBoolean()) throw new IllegalArgumentException("knock must be true or false");
                yield new Discard(card(raw.get("card")), knock.asBoolean(false));
            }
            default -> throw new IllegalArgumentException("gin rummy understands \"draw\" and \"discard\", not \"" + type + "\"");
        };
    }

    private static Card card(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("expected a card {rank, suit}");
        try {
            return JSON.treeToValue(node, Card.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("not a card: " + node);
        }
    }

    @Override
    public GinState createInitialState(List<Seat> seats, Map<String, String> options, EngineContext ctx) {
        if (seats.size() != 2) throw new IllegalStateException("gin rummy needs 2 seats, got " + seats.size());
        return apply(GinState.seated(seats), deal(0, seats.get(0).id(), seats, ctx.random()));
    }

    private static Dealt deal(int hand, PlayerId dealer, List<Seat> seats, RandomSource random) {
        Deck deck = Deck.standard52().shuffled(random);
        Map<PlayerId, List<Card>> hands = new LinkedHashMap<>();
        for (Seat seat : seats) {
            Deck.Deal deal = deck.deal(HAND_SIZE);
            hands.put(seat.id(), deal.cards());
            deck = deal.rest();
        }
        Deck.Deal upcard = deck.deal(1);
        return new Dealt(hand, dealer, hands, upcard.card(), upcard.rest().cards());
    }

    @Override
    public Validation canJoin(GinState state, Seat seat) {
        return Validation.reject(ErrorCode.SEAT_UNAVAILABLE, "gin rummy seats are fixed once the cards are dealt");
    }

    @Override
    public Validation validate(GinState state, GinIntent intent, PlayerId actor) {
        if (state.positionOf(actor) < 0) return Validation.reject(ErrorCode.NOT_SEATED, "you are not at this table");
        if (state.phase() == GinPhase.GAME_OVER) return Validation.reject(ErrorCode.WRONG_PHASE, "the game is over");
        if (!actor.equals(state.turn())) {
            return Validation.reject(ErrorCode.NOT_YOUR_TURN, "waiting on " + state.seats().get(state.positionOf(state.turn())).nick());
        }

        if (intent instanceof Draw draw) {
            if (state.phase() != GinPhase.DRAW) return Validation.reject(ErrorCode.WRONG_PHASE, "you have drawn; now discard");
            if (draw.source() == DrawSource.DISCARD && state.discard().isEmpty()) {
                return Validation.reject(ErrorCode.ILLEGAL_MOVE, "the discard pile is empty");
            }
            return Validation.OK;
        }

        if (!(intent instanceof Discard discard)) throw new IllegalStateException("unhandled intent: " + intent);
        if (state.phase() != GinPhase.DISCARD) return Validation.reject(ErrorCode.WRONG_PHASE, "draw a card first");
        List<Card> hand = state.handOf(actor);
        if (!hand.contains(discard.card())) return Validation.reject(ErrorCode.ILLEGAL_MOVE, "that card is not in your hand");
        if (discard.card().equals(state.takenFromDiscard())) {
            return Validation.reject(ErrorCode.ILLEGAL_MOVE, "you cannot discard the card you just took from the pile");
        }
        if (discard.knock()) {
            int deadwood = deadwoodAfter(hand, discard.card());
            if (deadwood > KNOCK_LIMIT) {
                return Validation.reject(ErrorCode.ILLEGAL_MOVE,
                        "you need " + KNOCK_LIMIT + " or less deadwood to knock; you would have " + deadwood);
            }
        }
        return Validation.OK;
    }

    private static int deadwoodAfter(List<Card> hand, Card discard) {
        List<Card> rest = new ArrayList<>(hand);
        rest.remove(discard);
        return Melds.best(rest).deadwoodPoints();
    }

    @Override
    public List<GinEvent> reduce(GinState state, GinIntent intent, PlayerId actor, EngineContext ctx) {
        if (intent instanceof Draw draw) {
            Card card = draw.source() == DrawSource.STOCK ? state.stock().get(0) : state.discardTop();
            return List.of(new Drew(actor, draw.source(), card));
        }
        if (!(intent instanceof Discard discard)) throw new IllegalStateException("unhandled intent: " + intent);
        Discarded discarded = new Discarded(actor, discard.card(), discard.knock());
        GinState next = apply(state, discarded);
        if (next.phase() != GinPhase.SCORING) return List.of(discarded);
        // The discard that ends a hand also deals the next one; the deal alternates.
        return List.of(discarded, deal(next.hand() + 1, next.opponentOf(next.dealer()), next.seats(), ctx.random()));
    }

    @Override
    public GinState apply(GinState state, GinEvent event) {
        // Java 17: no pattern switch yet, so a new event type fails loudly here instead of being
        // cast to the wrong one.
        if (event instanceof Dealt dealt) return startHand(state, dealt);
        if (event instanceof Drew drew) return draw(state, drew);
        if (event instanceof Discarded discarded) return discard(state, discarded);
        throw new IllegalStateException("unhandled event: " + event);
    }

    private static GinState startHand(GinState state, Dealt dealt) {
        Map<PlayerId, List<Card>> hands = new LinkedHashMap<>();
        dealt.hands().forEach((player, cards) -> hands.put(player, sorted(cards)));
        Table t = new Table(state);
        t.hand = dealt.hand();
        t.dealer = dealt.dealer();
        t.phase = GinPhase.DRAW;
        t.turn = state.opponentOf(dealt.dealer());
        t.hands = hands;
        t.stock = dealt.stock();
        t.discard = List.of(dealt.upcard());
        t.takenFromDiscard = null;
        return t.freeze();
    }

    private static GinState draw(GinState state, Drew drew) {
        List<Card> stock = state.stock();
        List<Card> discard = state.discard();
        Card expected = drew.source() == DrawSource.STOCK ? stock.get(0) : state.discardTop();
        if (!expected.equals(drew.card())) {
            // Replay diverged: this log was produced by a different pile than the one we hold.
            throw new IllegalStateException("event says " + drew.card() + " but the pile shows " + expected);
        }
        if (drew.source() == DrawSource.STOCK) {
            stock = stock.subList(1, stock.size());
        } else {
            discard = discard.subList(0, discard.size() - 1);
        }
        Map<PlayerId, List<Card>> hands = new LinkedHashMap<>(state.hands());
        List<Card> hand = new ArrayList<>(state.handOf(drew.player()));
        hand.add(drew.card());
        hands.put(drew.player(), sorted(hand));
        Table t = new Table(state);
        t.phase = GinPhase.DISCARD;
        t.hands = hands;
        t.stock = stock;
        t.discard = discard;
        t.takenFromDiscard = drew.source() == DrawSource.DISCARD ? drew.card() : null;
        return t.freeze();
    }

    private static GinState discard(GinState state, Discarded discarded) {
        Map<PlayerId, List<Card>> hands = new LinkedHashMap<>(state.hands());
        List<Card> hand = new ArrayList<>(state.handOf(discarded.player()));
        if (!hand.remove(discarded.card())) {
            throw new IllegalStateException(discarded.player() + " does not hold " + discarded.card());
        }
        hands.put(discarded.player(), hand);
        List<Card> pile = new ArrayList<>(state.discard());
        pile.add(discarded.card());

        if (discarded.knock()) return score(state, hands, pile, discarded.player());
        if (state.stock().size() <= DEAD_STOCK) {
            HandResult dead = new HandResult(Outcome.DEAD, null, null, 0, List.of());
            return endHand(state, hands, pile, dead, List.of(0, 0));
        }
        Table t = new Table(state);
        t.phase = GinPhase.DRAW;
        t.turn = state.opponentOf(discarded.player());
        t.hands = hands;
        t.discard = pile;
        t.takenFromDiscard = null;
        return t.freeze();
    }

    /** A knock: lay both hands down, let the defender lay off (unless gin), and score. */
    private static GinState score(GinState state, Map<PlayerId, List<Card>> hands, List<Card> pile, PlayerId knocker) {
        PlayerId defender = state.opponentOf(knocker);
        Melds.Arrangement k = Melds.best(hands.get(knocker));
        Melds.Arrangement d = Melds.best(hands.get(defender));
        boolean gin = k.deadwoodPoints() == 0;

        List<Card> laidOff = gin ? List.of() : Melds.layoffs(d.deadwood(), k.melds());
        List<Card> defenderDeadwood = new ArrayList<>(d.deadwood());
        defenderDeadwood.removeAll(laidOff);
        int kd = k.deadwoodPoints();
        int dd = Melds.points(defenderDeadwood);

        Outcome outcome;
        PlayerId winner;
        int points;
        if (gin) {
            outcome = Outcome.GIN;
            winner = knocker;
            points = GIN_BONUS + dd;
        } else if (dd <= kd) {
            outcome = Outcome.UNDERCUT;
            winner = defender;
            points = UNDERCUT_BONUS + (kd - dd);
        } else {
            outcome = Outcome.KNOCK;
            winner = knocker;
            points = dd - kd;
        }

        HandResult result = new HandResult(outcome, knocker, winner, points, List.of(
                new Revealed(knocker, k.melds(), k.deadwood(), List.of(), kd),
                new Revealed(defender, d.melds(), defenderDeadwood, laidOff, dd)));
        List<Integer> row = new ArrayList<>();
        for (Seat seat : state.seats()) row.add(seat.id().equals(winner) ? points : 0);
        return endHand(state, hands, pile, result, row);
    }

    private static GinState endHand(GinState state, Map<PlayerId, List<Card>> hands, List<Card> pile,
            HandResult result, List<Integer> row) {
        Map<PlayerId, Integer> scores = new LinkedHashMap<>(state.scores());
        for (int i = 0; i < state.seats().size(); i++) scores.merge(state.seats().get(i).id(), row.get(i), Integer::sum);
        List<List<Integer>> history = new ArrayList<>(state.history());
        history.add(row);
        boolean over = scores.values().stream().anyMatch(score -> score >= TARGET);
        Table t = new Table(state);
        t.phase = over ? GinPhase.GAME_OVER : GinPhase.SCORING;
        t.turn = null;
        t.hands = hands;
        t.discard = pile;
        t.takenFromDiscard = null;
        t.scores = scores;
        t.history = history;
        t.lastHand = result;
        return t.freeze();
    }

    /**
     * A working copy of one state, local to a single {@code apply}, as in {@code PokerGame}: a
     * transition names only what it changes, so {@code dealer}/{@code turn} or
     * {@code stock}/{@code discard} cannot be swapped by position. Never stored.
     */
    private static final class Table {
        final List<Seat> seats;
        int hand;
        PlayerId dealer;
        GinPhase phase;
        PlayerId turn;
        Map<PlayerId, List<Card>> hands;
        List<Card> stock;
        List<Card> discard;
        Card takenFromDiscard;
        Map<PlayerId, Integer> scores;
        List<List<Integer>> history;
        HandResult lastHand;

        Table(GinState s) {
            seats = s.seats();
            hand = s.hand();
            dealer = s.dealer();
            phase = s.phase();
            turn = s.turn();
            hands = s.hands();
            stock = s.stock();
            discard = s.discard();
            takenFromDiscard = s.takenFromDiscard();
            scores = s.scores();
            history = s.history();
            lastHand = s.lastHand();
        }

        GinState freeze() {
            return new GinState(seats, hand, dealer, phase, turn, hands, stock, discard, takenFromDiscard, scores,
                    history, lastHand);
        }
    }

    @Override
    public Optional<Turn> turn(GinState state) {
        boolean playing = state.phase() == GinPhase.DRAW || state.phase() == GinPhase.DISCARD;
        return playing ? Optional.of(new Turn(state.turn(), TURN_LIMIT)) : Optional.empty();
    }

    /**
     * Plays a sensible move for someone out of time: draw from the stock, then throw the card
     * that leaves the least deadwood (the higher card on a tie), knocking if that allows it.
     * Always legal — and the seed of the heuristic bot this game was built to host.
     */
    @Override
    public GinIntent onTimeout(GinState state, PlayerId actor) {
        if (state.phase() == GinPhase.DRAW) return new Draw(DrawSource.STOCK);
        if (state.phase() != GinPhase.DISCARD) throw new IllegalStateException("nobody is on the clock in " + state.phase());
        List<Card> hand = state.handOf(actor);
        Card best = hand.stream()
                .filter(card -> !card.equals(state.takenFromDiscard()))
                .min(Comparator.<Card>comparingInt(card -> deadwoodAfter(hand, card))
                        .thenComparing(Comparator.comparingInt((Card card) -> Melds.points(card)).reversed()))
                .orElseThrow();
        return new Discard(best, deadwoodAfter(hand, best) <= KNOCK_LIMIT);
    }

    @Override
    public GinView project(GinState state, Optional<PlayerId> viewer) {
        PlayerId me = viewer.filter(id -> state.positionOf(id) >= 0).orElse(null);
        boolean myTurn = me != null && me.equals(state.turn());

        List<GinView.SeatView> seats = new ArrayList<>();
        for (int i = 0; i < state.seats().size(); i++) {
            Seat seat = state.seats().get(i);
            seats.add(new GinView.SeatView(i, seat.id(), seat.nick(), state.handOf(seat.id()).size(),
                    state.scores().get(seat.id())));
        }

        List<DrawSource> drawSources = List.of();
        List<Card> discards = List.of();
        List<Card> knockDiscards = new ArrayList<>();
        List<Card> ginDiscards = new ArrayList<>();
        if (myTurn && state.phase() == GinPhase.DRAW) {
            drawSources = state.discard().isEmpty() ? List.of(DrawSource.STOCK) : List.of(DrawSource.STOCK, DrawSource.DISCARD);
        } else if (myTurn && state.phase() == GinPhase.DISCARD) {
            List<Card> hand = state.handOf(me);
            discards = hand.stream().filter(card -> !card.equals(state.takenFromDiscard())).toList();
            for (Card card : discards) {
                int deadwood = deadwoodAfter(hand, card);
                if (deadwood <= KNOCK_LIMIT) knockDiscards.add(card);
                if (deadwood == 0) ginDiscards.add(card);
            }
        }

        List<Card> myHand = me == null ? List.of() : state.handOf(me);
        PlayerId winner = state.phase() == GinPhase.GAME_OVER
                ? state.seats().stream().max(Comparator.comparingInt(s -> state.scores().get(s.id()))).map(Seat::id).orElseThrow()
                : null;
        return new GinView(state.phase(), state.hand(), state.dealer(), turn(state).map(Turn::actor).orElse(null), seats,
                myHand, me == null ? null : Melds.best(myHand), state.stock().size(), state.discardTop(),
                state.discard().size(), state.takenFromDiscard(), drawSources, discards, knockDiscards, ginDiscards,
                state.lastHand(), state.history(), winner);
    }

    @Override
    public boolean isHandComplete(GinState state) {
        return state.phase() == GinPhase.GAME_OVER;
    }

    @Override
    public boolean isComplete(GinState state) {
        return state.phase() == GinPhase.GAME_OVER;
    }

    private static List<Card> sorted(List<Card> cards) {
        return cards.stream().sorted(Melds.BY_SUIT).toList();
    }
}
