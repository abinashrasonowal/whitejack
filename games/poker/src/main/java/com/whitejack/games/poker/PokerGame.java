package com.whitejack.games.poker;

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
import com.whitejack.games.poker.PokerEvent.Acted;
import com.whitejack.games.poker.PokerEvent.Action;
import com.whitejack.games.poker.PokerEvent.Dealt;
import com.whitejack.games.poker.PokerIntent.Call;
import com.whitejack.games.poker.PokerIntent.Check;
import com.whitejack.games.poker.PokerIntent.Fold;
import com.whitejack.games.poker.PokerIntent.Raise;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * No-limit Texas Hold'em for two to eight players, played as a freezeout: everyone starts on
 * {@value #STARTING_STACK} chips, the blinds double every {@value #HANDS_PER_LEVEL} hands, and
 * the game ends when one player holds every chip.
 *
 * <p>Standard rules: the button moves one live seat per hand; heads-up the button posts the
 * small blind and acts first before the flop; a raise must be at least the previous raise (or
 * the big blind), except all-in; uncalled chips go back to whoever bet them; side pots are
 * built from each player's contribution, and each pot goes to the best five-card hand among
 * the players who paid into it, split evenly with odd chips to the first winner left of the
 * button. One simplification: an all-in raise too small to be a full raise still reopens the
 * betting for players who have already acted.
 *
 * <p>Stateless by construction: no fields at all.
 */
public final class PokerGame implements GameDefinition<PokerState, PokerIntent, PokerEvent> {

    static final int MIN_SEATS = 2;
    static final int MAX_SEATS = 8;
    static final int STARTING_STACK = 1000;
    static final int SMALL_BLIND = 10;
    static final int HANDS_PER_LEVEL = 10;
    private static final Duration ACT_LIMIT = Duration.ofSeconds(30);

    @Override
    public String id() {
        return "poker";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public int minPlayers() {
        return MIN_SEATS;
    }

    @Override
    public int maxPlayers() {
        return MAX_SEATS;
    }

    @Override
    public PokerIntent parseIntent(JsonNode raw) {
        String type = raw == null ? "" : raw.path("type").asText();
        return switch (type) {
            case "fold" -> new Fold();
            case "check" -> new Check();
            case "call" -> new Call();
            case "raise" -> {
                JsonNode to = raw.get("to");
                if (to == null || !to.canConvertToInt()) throw new IllegalArgumentException("raise needs a whole-number \"to\"");
                yield new Raise(to.intValue());
            }
            default -> throw new IllegalArgumentException(
                    "poker understands \"fold\", \"check\", \"call\" and \"raise\", not \"" + type + "\"");
        };
    }

    @Override
    public PokerState createInitialState(List<Seat> seats, Map<String, String> options, EngineContext ctx) {
        if (seats.size() < MIN_SEATS || seats.size() > MAX_SEATS) {
            throw new IllegalStateException("poker seats " + MIN_SEATS + " to " + MAX_SEATS + ", got " + seats.size());
        }
        PokerState seated = PokerState.seated(seats, STARTING_STACK);
        return apply(seated, deal(seated, ctx.random()));
    }

    /** The next hand: the button moves on, the blinds may go up, and busted players get no cards. */
    private static Dealt deal(PokerState state, RandomSource random) {
        int hand = state.hand() + 1;
        int small = SMALL_BLIND << Math.min(hand / HANDS_PER_LEVEL, 16);
        int dealer = nextPosition(state.seats().size(), state.dealer(), p -> state.stackOf(state.idAt(p)) > 0);

        Deck deck = Deck.standard52().shuffled(random);
        Map<PlayerId, List<Card>> holes = new LinkedHashMap<>();
        for (int i = 1; i <= state.seats().size(); i++) {
            PlayerId player = state.idAt((dealer + i) % state.seats().size());
            if (state.stackOf(player) == 0) continue;
            Deck.Deal deal = deck.deal(2);
            holes.put(player, deal.cards());
            deck = deal.rest();
        }
        return new Dealt(hand, dealer, small, small * 2, holes, deck.deal(5).cards());
    }

    /** Seats are fixed for a freezeout: a newcomer would arrive with chips nobody lost. */
    @Override
    public Validation canJoin(PokerState state, Seat seat) {
        return Validation.reject(ErrorCode.SEAT_UNAVAILABLE, "poker seats are fixed once the cards are dealt");
    }

    @Override
    public Validation validate(PokerState state, PokerIntent intent, PlayerId actor) {
        if (state.positionOf(actor) < 0) return Validation.reject(ErrorCode.NOT_SEATED, "you are not at this table");
        if (state.phase() != PokerPhase.BETTING) return Validation.reject(ErrorCode.WRONG_PHASE, "the game is over");
        if (!actor.equals(state.toAct())) {
            return Validation.reject(ErrorCode.NOT_YOUR_TURN,
                    "waiting on " + state.seats().get(state.positionOf(state.toAct())).nick());
        }

        int toCall = state.currentBet() - state.betOf(actor);
        if (intent instanceof Check && toCall > 0) {
            return Validation.reject(ErrorCode.ILLEGAL_MOVE, "you cannot check: " + toCall + " to call");
        }
        if (intent instanceof Call && toCall == 0) {
            return Validation.reject(ErrorCode.ILLEGAL_MOVE, "there is nothing to call; check instead");
        }
        if (intent instanceof Raise raise) {
            int allIn = state.betOf(actor) + state.stackOf(actor);
            if (!canRaise(state, actor)) {
                return Validation.reject(ErrorCode.ILLEGAL_MOVE, allIn <= state.currentBet()
                        ? "you do not have enough to raise; calling puts you all-in"
                        : "everyone else is all-in, so there is nobody to raise");
            }
            if (raise.to() > allIn) return Validation.reject(ErrorCode.ILLEGAL_MOVE, "you can raise to at most " + allIn);
            if (raise.to() < minRaiseTo(state, actor)) {
                return Validation.reject(ErrorCode.ILLEGAL_MOVE, "the smallest raise is to " + minRaiseTo(state, actor));
            }
        }
        return Validation.OK;
    }

    /** More chips than the bet to match, and at least one opponent left who could call more. */
    private static boolean canRaise(PokerState state, PlayerId actor) {
        boolean anyoneToCall = state.seats().stream()
                .map(Seat::id)
                .anyMatch(other -> !other.equals(actor) && state.canBet(other));
        return anyoneToCall && state.betOf(actor) + state.stackOf(actor) > state.currentBet();
    }

    /** A full raise, or all-in when that is less. */
    private static int minRaiseTo(PokerState state, PlayerId actor) {
        return Math.min(state.currentBet() + state.minRaise(), state.betOf(actor) + state.stackOf(actor));
    }

    @Override
    public List<PokerEvent> reduce(PokerState state, PokerIntent intent, PlayerId actor, EngineContext ctx) {
        Acted acted;
        if (intent instanceof Fold) acted = new Acted(actor, Action.FOLD, 0);
        else if (intent instanceof Check) acted = new Acted(actor, Action.CHECK, 0);
        else if (intent instanceof Call) acted = new Acted(actor, Action.CALL, 0);
        else if (intent instanceof Raise raise) acted = new Acted(actor, Action.RAISE, raise.to());
        else throw new IllegalStateException("unhandled intent: " + intent);

        PokerState next = apply(state, acted);
        if (next.phase() != PokerPhase.SETTLED) return List.of(acted);
        // The move that ends a hand also deals the next one, so the table never rests between hands.
        return List.of(acted, deal(next, ctx.random()));
    }

    @Override
    public PokerState apply(PokerState state, PokerEvent event) {
        // Java 17: no pattern switch yet, so a new event type fails loudly here instead of being
        // cast to the wrong one.
        if (event instanceof Dealt dealt) return startHand(state, dealt);
        if (event instanceof Acted acted) return act(state, acted);
        throw new IllegalStateException("unhandled event: " + event);
    }

    private static PokerState startHand(PokerState state, Dealt dealt) {
        Table t = new Table(state);
        t.hand = dealt.hand();
        t.phase = PokerPhase.BETTING;
        t.street = Street.PREFLOP;
        t.dealer = dealt.dealer();
        t.smallBlind = dealt.smallBlind();
        t.bigBlind = dealt.bigBlind();
        t.holes = dealt.holes();
        t.board = dealt.board();
        t.bets.clear();
        t.contributed.clear();
        t.folded.clear();
        t.acted.clear();
        t.lastActions.clear();
        t.minRaise = dealt.bigBlind();

        // Heads-up the button is the small blind; otherwise the blinds sit to its left.
        int n = state.seats().size();
        Predicate<Integer> dealtIn = p -> t.holes.containsKey(t.idAt(p));
        int small = t.holes.size() == 2 ? t.dealer : nextPosition(n, t.dealer, dealtIn);
        int big = nextPosition(n, small, dealtIn);
        t.post(small, dealt.smallBlind(), "Small blind");
        t.post(big, dealt.bigBlind(), "Big blind");
        t.currentBet = Math.max(t.betOf(t.idAt(small)), t.betOf(t.idAt(big)));
        return settle(t, big);
    }

    private static PokerState act(PokerState state, Acted acted) {
        Table t = new Table(state);
        PlayerId player = acted.player();
        switch (acted.action()) {
            case FOLD -> {
                t.folded.add(player);
                t.lastActions.put(player, "Fold");
            }
            case CHECK -> t.lastActions.put(player, "Check");
            case CALL -> {
                t.move(player, Math.min(t.currentBet - t.betOf(player), t.stackOf(player)));
                t.lastActions.put(player, t.stackOf(player) == 0 ? "All-in " + t.betOf(player) : "Call " + t.betOf(player));
            }
            case RAISE -> {
                boolean opening = t.currentBet == 0;
                t.move(player, acted.to() - t.betOf(player));
                int increment = acted.to() - t.currentBet;
                if (increment >= t.minRaise) t.minRaise = increment;
                t.currentBet = acted.to();
                t.acted.clear(); // everyone else must answer the raise
                t.lastActions.put(player, t.stackOf(player) == 0 ? "All-in " + acted.to()
                        : (opening ? "Bet " : "Raise to ") + acted.to());
            }
        }
        t.acted.add(player);
        return settle(t, state.positionOf(player));
    }

    /** After a blind or an action at {@code from}: end the hand, end the street, or pass the action on. */
    private static PokerState settle(Table t, int from) {
        List<PlayerId> live = t.seatOrder().stream().filter(t::isLive).toList();
        if (live.size() == 1) return winUncontested(t, live.get(0));

        List<PlayerId> able = live.stream().filter(t::canBet).toList();
        boolean streetOver = able.stream().allMatch(p ->
                t.betOf(p) == t.currentBet && (t.acted.contains(p) || able.size() == 1));
        if (streetOver) return endStreet(t);

        t.toAct = t.idAt(nextPosition(t.seats.size(), from, p -> {
            PlayerId id = t.idAt(p);
            return t.canBet(id) && (!t.acted.contains(id) || t.betOf(id) < t.currentBet);
        }));
        return t.freeze();
    }

    private static PokerState endStreet(Table t) {
        long able = t.seatOrder().stream().filter(t::canBet).count();
        // With at most one player able to bet there is nothing left to decide: run the board out.
        if (t.street == Street.RIVER || able <= 1) return showdown(t);

        t.street = t.street.next();
        t.bets.clear();
        t.acted.clear();
        t.lastActions.keySet().retainAll(t.folded);
        t.currentBet = 0;
        t.minRaise = t.bigBlind;
        t.toAct = t.idAt(nextPosition(t.seats.size(), t.dealer, p -> t.canBet(t.idAt(p))));
        return t.freeze();
    }

    private static PokerState winUncontested(Table t, PlayerId winner) {
        int pot = t.contributed.values().stream().mapToInt(Integer::intValue).sum();
        t.stacks.merge(winner, pot, Integer::sum);
        t.lastHand = new HandResult(t.hand, t.visibleBoard(), List.of(), List.of(new HandResult.Pot(pot, List.of(winner), null)));
        return finishHand(t);
    }

    private static PokerState showdown(Table t) {
        t.street = Street.RIVER;
        List<PlayerId> live = t.seatOrder().stream().filter(t::isLive).toList();
        refundUncalled(t, live);

        Map<PlayerId, HandValue> values = new LinkedHashMap<>();
        List<HandResult.Reveal> reveals = new ArrayList<>();
        for (PlayerId player : live) {
            List<Card> seven = new ArrayList<>(t.holes.get(player));
            seven.addAll(t.board);
            HandValue value = HandValue.best(seven);
            values.put(player, value);
            reveals.add(new HandResult.Reveal(player, t.holes.get(player), value.name(), value.cards()));
        }

        List<HandResult.Pot> pots = new ArrayList<>();
        for (Pot pot : buildPots(t, live)) {
            int best = pot.eligible.stream().mapToInt(p -> values.get(p).score()).max().orElseThrow();
            // Clockwise from the button, so the odd chips land where the rules say.
            List<PlayerId> winners = clockwiseFromButton(t).stream()
                    .filter(p -> pot.eligible.contains(p) && values.get(p).score() == best)
                    .toList();
            int share = pot.amount / winners.size();
            int odd = pot.amount % winners.size();
            for (int i = 0; i < winners.size(); i++) t.stacks.merge(winners.get(i), share + (i < odd ? 1 : 0), Integer::sum);
            pots.add(new HandResult.Pot(pot.amount, winners, values.get(winners.get(0)).name()));
        }
        t.lastHand = new HandResult(t.hand, t.board, reveals, pots);
        return finishHand(t);
    }

    /** Chips nobody matched go straight back: the top contributor's excess over the next highest. */
    private static void refundUncalled(Table t, List<PlayerId> live) {
        PlayerId top = live.stream().max((a, b) -> Integer.compare(t.contributedOf(a), t.contributedOf(b))).orElseThrow();
        int next = t.contributed.entrySet().stream()
                .filter(e -> !e.getKey().equals(top))
                .mapToInt(Map.Entry::getValue)
                .max().orElse(0);
        int excess = t.contributedOf(top) - next;
        if (excess <= 0) return;
        t.contributed.put(top, next);
        t.stacks.merge(top, excess, Integer::sum);
    }

    private record Pot(int amount, Set<PlayerId> eligible) {}

    /**
     * Main pot then side pots, one per distinct all-in level among the live players. Folded
     * players' chips count toward every level they reached but make them eligible for none.
     */
    private static List<Pot> buildPots(Table t, List<PlayerId> live) {
        TreeSet<Integer> levels = new TreeSet<>();
        for (PlayerId player : live) if (t.contributedOf(player) > 0) levels.add(t.contributedOf(player));

        List<Pot> pots = new ArrayList<>();
        int previous = 0;
        for (int level : levels) {
            int amount = 0;
            for (int paid : t.contributed.values()) amount += Math.min(paid, level) - Math.min(paid, previous);
            final int reached = level;
            Set<PlayerId> eligible = new LinkedHashSet<>(live.stream().filter(p -> t.contributedOf(p) >= reached).toList());
            if (amount > 0) pots.add(new Pot(amount, eligible));
            previous = level;
        }
        return pots;
    }

    private static List<PlayerId> clockwiseFromButton(Table t) {
        List<PlayerId> order = new ArrayList<>();
        for (int i = 1; i <= t.seats.size(); i++) order.add(t.idAt((t.dealer + i) % t.seats.size()));
        return order;
    }

    private static PokerState finishHand(Table t) {
        t.bets.clear();
        t.contributed.clear();
        t.acted.clear();
        t.currentBet = 0;
        t.toAct = null;
        long withChips = t.stacks.values().stream().filter(stack -> stack > 0).count();
        t.phase = withChips <= 1 ? PokerPhase.GAME_OVER : PokerPhase.SETTLED;
        return t.freeze();
    }

    /** The first position after {@code from}, going round the table, that {@code wanted} accepts. */
    private static int nextPosition(int seats, int from, Predicate<Integer> wanted) {
        for (int i = 1; i <= seats; i++) {
            int position = (from + i) % seats;
            if (wanted.test(position)) return position;
        }
        throw new IllegalStateException("no seat qualifies");
    }

    @Override
    public Optional<Turn> turn(PokerState state) {
        if (state.phase() != PokerPhase.BETTING) return Optional.empty();
        return Optional.of(new Turn(state.toAct(), ACT_LIMIT));
    }

    /** Check when that is free, fold otherwise: never puts a chip in for someone who is away. */
    @Override
    public PokerIntent onTimeout(PokerState state, PlayerId actor) {
        if (state.phase() != PokerPhase.BETTING) throw new IllegalStateException("nobody is on the clock in " + state.phase());
        return state.currentBet() == state.betOf(actor) ? new Check() : new Fold();
    }

    @Override
    public PokerView project(PokerState state, Optional<PlayerId> viewer) {
        PlayerId me = viewer.filter(id -> state.positionOf(id) >= 0).orElse(null);
        boolean betting = state.phase() == PokerPhase.BETTING;

        List<PokerView.SeatView> seats = new ArrayList<>();
        for (int i = 0; i < state.seats().size(); i++) {
            Seat seat = state.seats().get(i);
            PlayerId id = seat.id();
            seats.add(new PokerView.SeatView(i, id, seat.nick(), state.stackOf(id), state.betOf(id),
                    state.isDealtIn(id), state.folded().contains(id), betting && state.isLive(id) && state.stackOf(id) == 0,
                    state.lastActions().get(id)));
        }

        List<String> legal = List.of();
        int toCall = 0;
        int minRaiseTo = 0;
        int maxRaiseTo = 0;
        if (me != null && betting && me.equals(state.toAct())) {
            toCall = Math.min(state.currentBet() - state.betOf(me), state.stackOf(me));
            List<String> moves = new ArrayList<>(List.of("fold", toCall == 0 ? "check" : "call"));
            if (canRaise(state, me)) {
                moves.add("raise");
                minRaiseTo = minRaiseTo(state, me);
                maxRaiseTo = state.betOf(me) + state.stackOf(me);
            }
            legal = moves;
        }

        List<Card> board = betting || state.lastHand() == null ? state.visibleBoard() : state.lastHand().board();
        PlayerId winner = state.phase() == PokerPhase.GAME_OVER
                ? state.seats().stream().map(Seat::id).filter(id -> state.stackOf(id) > 0).findFirst().orElse(null)
                : null;

        return new PokerView(state.phase(), state.street(), state.hand(), state.smallBlind(), state.bigBlind(),
                state.idAt(state.dealer()), seats, me != null && state.isDealtIn(me) ? state.holes().get(me) : List.of(),
                board, state.pot(), state.currentBet(), betting ? state.toAct() : null, legal, toCall, minRaiseTo,
                maxRaiseTo, state.lastHand(), winner);
    }

    /** The next deal happens inside the same intent, so the only hand boundary that rests is the last. */
    @Override
    public boolean isHandComplete(PokerState state) {
        return state.phase() == PokerPhase.GAME_OVER;
    }

    @Override
    public boolean isComplete(PokerState state) {
        return state.phase() == PokerPhase.GAME_OVER;
    }

    /**
     * A mutable working copy of one state, local to a single {@code apply}: the betting rules
     * touch a dozen fields at once, and rebuilding the record after each would bury them.
     * Frozen back into an immutable {@link PokerState} before it leaves.
     */
    private static final class Table {
        final List<Seat> seats;
        int hand;
        PokerPhase phase;
        Street street;
        int dealer;
        int smallBlind;
        int bigBlind;
        Map<PlayerId, List<Card>> holes;
        List<Card> board;
        final Map<PlayerId, Integer> stacks;
        final Map<PlayerId, Integer> bets;
        final Map<PlayerId, Integer> contributed;
        final Set<PlayerId> folded;
        final Set<PlayerId> acted;
        final Map<PlayerId, String> lastActions;
        int currentBet;
        int minRaise;
        PlayerId toAct;
        HandResult lastHand;

        Table(PokerState s) {
            seats = s.seats();
            hand = s.hand();
            phase = s.phase();
            street = s.street();
            dealer = s.dealer();
            smallBlind = s.smallBlind();
            bigBlind = s.bigBlind();
            holes = s.holes();
            board = s.board();
            stacks = new LinkedHashMap<>(s.stacks());
            bets = new LinkedHashMap<>(s.bets());
            contributed = new LinkedHashMap<>(s.contributed());
            folded = new LinkedHashSet<>(s.folded());
            acted = new LinkedHashSet<>(s.acted());
            lastActions = new LinkedHashMap<>(s.lastActions());
            currentBet = s.currentBet();
            minRaise = s.minRaise();
            toAct = s.toAct();
            lastHand = s.lastHand();
        }

        PokerState freeze() {
            return new PokerState(seats, hand, phase, street, dealer, smallBlind, bigBlind, holes, board, stacks, bets,
                    contributed, folded, acted, lastActions, currentBet, minRaise, toAct, lastHand);
        }

        PlayerId idAt(int position) {
            return seats.get(position).id();
        }

        List<PlayerId> seatOrder() {
            return seats.stream().map(Seat::id).toList();
        }

        int stackOf(PlayerId player) {
            return stacks.getOrDefault(player, 0);
        }

        int betOf(PlayerId player) {
            return bets.getOrDefault(player, 0);
        }

        int contributedOf(PlayerId player) {
            return contributed.getOrDefault(player, 0);
        }

        boolean isLive(PlayerId player) {
            return holes.containsKey(player) && !folded.contains(player);
        }

        boolean canBet(PlayerId player) {
            return isLive(player) && stackOf(player) > 0;
        }

        List<Card> visibleBoard() {
            return board.subList(0, Math.min(street.boardCards(), board.size()));
        }

        /** Chips from the stack into this street's bet and the hand's pot. */
        void move(PlayerId player, int chips) {
            stacks.merge(player, -chips, Integer::sum);
            bets.merge(player, chips, Integer::sum);
            contributed.merge(player, chips, Integer::sum);
        }

        /** A blind, or all a short stack has when that is less. */
        void post(int position, int blind, String label) {
            PlayerId player = idAt(position);
            int chips = Math.min(blind, stackOf(player));
            move(player, chips);
            lastActions.put(player, label + " " + chips);
        }
    }
}
