package com.whitejack.games.hearts;

import com.whitejack.contract.Card;
import com.whitejack.contract.Deck;
import com.whitejack.contract.EngineContext;
import com.whitejack.contract.ErrorCode;
import com.whitejack.contract.GameDefinition;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.RandomSource;
import com.whitejack.contract.Seat;
import com.whitejack.contract.Suit;
import com.whitejack.contract.Turn;
import com.whitejack.contract.Validation;
import com.whitejack.games.hearts.HeartsEvent.Dealt;
import com.whitejack.games.hearts.HeartsEvent.Passed;
import com.whitejack.games.hearts.HeartsEvent.Played;
import com.whitejack.games.hearts.HeartsIntent.Pass;
import com.whitejack.games.hearts.HeartsIntent.Play;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Four-player Hearts, standard rules: pass three cards left, right, across, then hold; the
 * 2♣ leads; follow suit; no points on the first trick; hearts cannot be led until broken;
 * hearts are a point each and the Q♠ thirteen; taking all 26 shoots the moon; the game ends
 * when someone reaches 100, and the lowest score wins.
 *
 * <p>Passing is simultaneous even though the engine names one actor per turn: any seat that
 * has not passed may pass. {@link #turn} only picks whose clock runs, so an absent player is
 * passed for when it expires.
 *
 * <p>Stateless by construction. The one field is a Jackson mapper, used only to read cards
 * and safe to share across room threads.
 */
public final class HeartsGame implements GameDefinition<HeartsState, HeartsIntent, HeartsEvent> {

    static final int SEATS = 4;
    static final int HAND_SIZE = 13;
    static final int TARGET = 100;
    static final int MOON = 26;
    private static final Duration PASS_LIMIT = Duration.ofSeconds(45);
    private static final Duration PLAY_LIMIT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String id() {
        return "hearts";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public int minPlayers() {
        return SEATS;
    }

    @Override
    public int maxPlayers() {
        return SEATS;
    }

    @Override
    public HeartsIntent parseIntent(JsonNode raw) {
        String type = raw == null ? "" : raw.path("type").asText();
        return switch (type) {
            case "pass" -> {
                JsonNode cards = raw.path("cards");
                if (!cards.isArray()) throw new IllegalArgumentException("pass needs a \"cards\" array");
                List<Card> parsed = new ArrayList<>();
                for (JsonNode card : cards) parsed.add(card(card));
                yield new Pass(parsed);
            }
            case "play" -> new Play(card(raw.get("card")));
            default -> throw new IllegalArgumentException("hearts understands \"pass\" and \"play\", not \"" + type + "\"");
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
    public HeartsState createInitialState(List<Seat> seats, Map<String, String> options, EngineContext ctx) {
        if (seats.size() != SEATS) throw new IllegalStateException("hearts needs " + SEATS + " seats, got " + seats.size());
        return apply(HeartsState.seated(seats), deal(0, seats, ctx.random()));
    }

    private static Dealt deal(int hand, List<Seat> seats, RandomSource random) {
        Deck deck = Deck.standard52().shuffled(random);
        Map<PlayerId, List<Card>> hands = new LinkedHashMap<>();
        for (Seat seat : seats) {
            Deck.Deal deal = deck.deal(HAND_SIZE);
            hands.put(seat.id(), deal.cards());
            deck = deal.rest();
        }
        return new Dealt(hand, hands);
    }

    /** Seats are fixed once the cards are dealt: a fifth player has nowhere to sit. */
    @Override
    public Validation canJoin(HeartsState state, Seat seat) {
        return Validation.reject(ErrorCode.SEAT_UNAVAILABLE, "hearts seats are fixed once the cards are dealt");
    }

    @Override
    public Validation validate(HeartsState state, HeartsIntent intent, PlayerId actor) {
        if (state.positionOf(actor) < 0) return Validation.reject(ErrorCode.NOT_SEATED, "you are not at this table");
        List<Card> hand = state.handOf(actor);

        if (intent instanceof Pass pass) {
            if (state.phase() != HeartsPhase.PASSING) {
                return Validation.reject(ErrorCode.WRONG_PHASE, "there is no passing now");
            }
            if (state.passes().containsKey(actor)) {
                return Validation.reject(ErrorCode.WRONG_PHASE, "you have already passed");
            }
            if (pass.cards().size() != 3) return Validation.reject(ErrorCode.ILLEGAL_MOVE, "pass exactly three cards");
            if (new HashSet<>(pass.cards()).size() != 3) {
                return Validation.reject(ErrorCode.ILLEGAL_MOVE, "pass three different cards");
            }
            if (!hand.containsAll(pass.cards())) {
                return Validation.reject(ErrorCode.ILLEGAL_MOVE, "you can only pass cards in your hand");
            }
            return Validation.OK;
        }

        Play play = (Play) intent;
        if (state.phase() == HeartsPhase.PASSING) {
            return Validation.reject(ErrorCode.WRONG_PHASE, "cards are still being passed");
        }
        if (state.phase() != HeartsPhase.PLAYING) return Validation.reject(ErrorCode.WRONG_PHASE, "the game is over");
        PlayerId onClock = onClock(state);
        if (!onClock.equals(actor)) {
            return Validation.reject(ErrorCode.NOT_YOUR_TURN, "waiting on " + state.seats().get(state.positionOf(onClock)).nick());
        }
        if (!hand.contains(play.card())) return Validation.reject(ErrorCode.ILLEGAL_MOVE, "that card is not in your hand");
        if (!Rules.legalPlays(state, actor).contains(play.card())) {
            return Validation.reject(ErrorCode.ILLEGAL_MOVE, Rules.whyIllegal(state, play.card()));
        }
        return Validation.OK;
    }

    @Override
    public List<HeartsEvent> reduce(HeartsState state, HeartsIntent intent, PlayerId actor, EngineContext ctx) {
        if (intent instanceof Pass pass) return List.of(new Passed(actor, pass.cards()));

        Played played = new Played(actor, ((Play) intent).card());
        HeartsState next = apply(state, played);
        if (next.phase() != HeartsPhase.SCORING) return List.of(played);
        // The last card of a hand also deals the next one, so the table never rests between hands.
        return List.of(played, deal(next.hand() + 1, next.seats(), ctx.random()));
    }

    @Override
    public HeartsState apply(HeartsState state, HeartsEvent event) {
        // Java 17: no pattern switch yet. The interface is sealed, so these three are all of them.
        if (event instanceof Dealt dealt) return startHand(state, dealt);
        if (event instanceof Passed passed) return pass(state, passed);
        return play(state, (Played) event);
    }

    private static HeartsState startHand(HeartsState state, Dealt dealt) {
        Map<PlayerId, List<Card>> hands = new LinkedHashMap<>();
        dealt.hands().forEach((player, cards) -> hands.put(player, sorted(cards)));
        boolean hold = PassDirection.forHand(dealt.hand()) == PassDirection.HOLD;
        return new HeartsState(state.seats(), dealt.hand(), hold ? HeartsPhase.PLAYING : HeartsPhase.PASSING,
                hands, Map.of(), List.of(), hold ? holderOf(hands, Rules.TWO_OF_CLUBS) : null, 0, false,
                HeartsState.zeroes(state.seats()), state.scores(), state.history(), List.of(), null, List.of(),
                Map.of());
    }

    private static HeartsState pass(HeartsState state, Passed passed) {
        Map<PlayerId, List<Card>> passes = new LinkedHashMap<>(state.passes());
        passes.put(passed.player(), passed.cards());
        if (passes.size() < SEATS) {
            return new HeartsState(state.seats(), state.hand(), state.phase(), state.hands(), passes, state.trick(),
                    state.leader(), state.tricksPlayed(), state.heartsBroken(), state.handPoints(), state.scores(),
                    state.history(), state.lastTrick(), state.lastTrickWinner(), state.tricks(), state.exchanged());
        }

        // The fourth pass: everyone's cards move at once.
        int offset = state.passDirection().offset();
        Map<PlayerId, List<Card>> hands = new LinkedHashMap<>();
        for (Seat seat : state.seats()) {
            List<Card> cards = new ArrayList<>(state.handOf(seat.id()));
            cards.removeAll(passes.get(seat.id()));
            hands.put(seat.id(), cards);
        }
        for (int i = 0; i < SEATS; i++) {
            PlayerId giver = state.seats().get(i).id();
            PlayerId receiver = state.seats().get((i + offset) % SEATS).id();
            hands.get(receiver).addAll(passes.get(giver));
        }
        hands.replaceAll((player, cards) -> sorted(cards));
        return new HeartsState(state.seats(), state.hand(), HeartsPhase.PLAYING, hands, Map.of(), List.of(),
                holderOf(hands, Rules.TWO_OF_CLUBS), 0, false, state.handPoints(), state.scores(), state.history(),
                List.of(), null, List.of(), passes);
    }

    private static HeartsState play(HeartsState state, Played played) {
        Map<PlayerId, List<Card>> hands = new LinkedHashMap<>(state.hands());
        List<Card> remaining = new ArrayList<>(state.handOf(played.player()));
        if (!remaining.remove(played.card())) {
            throw new IllegalStateException(played.player() + " does not hold " + played.card());
        }
        hands.put(played.player(), remaining);

        List<PlayedCard> trick = new ArrayList<>(state.trick());
        trick.add(new PlayedCard(played.player(), played.card()));
        boolean broken = state.heartsBroken() || played.card().suit() == Suit.HEARTS;
        PlayerId leader = trick.size() == 1 ? played.player() : state.leader();

        if (trick.size() < SEATS) {
            return new HeartsState(state.seats(), state.hand(), state.phase(), hands, state.passes(), trick, leader,
                    state.tricksPlayed(), broken, state.handPoints(), state.scores(), state.history(),
                    state.lastTrick(), state.lastTrickWinner(), state.tricks(), state.exchanged());
        }

        PlayerId winner = Rules.trickWinner(trick);
        Map<PlayerId, Integer> handPoints = new LinkedHashMap<>(state.handPoints());
        handPoints.merge(winner, trick.stream().mapToInt(p -> Rules.points(p.card())).sum(), Integer::sum);
        int tricksPlayed = state.tricksPlayed() + 1;
        List<Trick> tricks = new ArrayList<>(state.tricks());
        tricks.add(new Trick(trick, winner));

        if (tricksPlayed < HAND_SIZE) {
            return new HeartsState(state.seats(), state.hand(), state.phase(), hands, state.passes(), List.of(),
                    winner, tricksPlayed, broken, handPoints, state.scores(), state.history(), trick, winner, tricks,
                    state.exchanged());
        }

        // Trick 13: score the hand.
        boolean moon = handPoints.containsValue(MOON);
        Map<PlayerId, Integer> scores = new LinkedHashMap<>(state.scores());
        List<Integer> row = new ArrayList<>();
        for (Seat seat : state.seats()) {
            int taken = handPoints.get(seat.id());
            int scored = moon ? (taken == MOON ? 0 : MOON) : taken;
            scores.merge(seat.id(), scored, Integer::sum);
            row.add(scored);
        }
        List<List<Integer>> history = new ArrayList<>(state.history());
        history.add(row);
        boolean over = Collections.max(scores.values()) >= TARGET;
        return new HeartsState(state.seats(), state.hand(), over ? HeartsPhase.GAME_OVER : HeartsPhase.SCORING,
                hands, state.passes(), List.of(), winner, tricksPlayed, broken, handPoints, scores, history, trick,
                winner, tricks, state.exchanged());
    }

    @Override
    public Optional<Turn> turn(HeartsState state) {
        return switch (state.phase()) {
            case PASSING -> state.seats().stream()
                    .filter(seat -> !state.passes().containsKey(seat.id()))
                    .findFirst()
                    .map(seat -> new Turn(seat.id(), PASS_LIMIT));
            case PLAYING -> Optional.of(new Turn(onClock(state), PLAY_LIMIT));
            case SCORING, GAME_OVER -> Optional.empty();
        };
    }

    /** Whoever plays next: seat order round from the leader. Only meaningful while PLAYING. */
    static PlayerId onClock(HeartsState state) {
        int leader = state.positionOf(state.leader());
        return state.seats().get((leader + state.trick().size()) % SEATS).id();
    }

    /** Passes the three highest cards, or plays the lowest legal one — always a legal move. */
    @Override
    public HeartsIntent onTimeout(HeartsState state, PlayerId actor) {
        return switch (state.phase()) {
            case PASSING -> new Pass(state.handOf(actor).stream()
                    .sorted(Comparator.reverseOrder())
                    .limit(3)
                    .toList());
            case PLAYING -> new Play(Collections.min(Rules.legalPlays(state, actor)));
            case SCORING, GAME_OVER -> throw new IllegalStateException("nobody is on the clock in " + state.phase());
        };
    }

    @Override
    public HeartsView project(HeartsState state, Optional<PlayerId> viewer) {
        PlayerId me = viewer.filter(id -> state.positionOf(id) >= 0).orElse(null);
        PlayerId onClock = turn(state).map(Turn::actor).orElse(null);

        List<HeartsView.SeatView> seats = new ArrayList<>();
        for (int i = 0; i < state.seats().size(); i++) {
            Seat seat = state.seats().get(i);
            seats.add(new HeartsView.SeatView(i, seat.id(), seat.nick(), state.handOf(seat.id()).size(),
                    state.handPoints().get(seat.id()), state.scores().get(seat.id()),
                    state.passes().containsKey(seat.id())));
        }

        List<Card> legal = List.of();
        if (me != null && state.phase() == HeartsPhase.PASSING && !state.passes().containsKey(me)) {
            legal = state.handOf(me);
        } else if (me != null && state.phase() == HeartsPhase.PLAYING && me.equals(onClock)) {
            legal = Rules.legalPlays(state, me);
        }

        // Your own pass is yours to see: the pick while it is pending, then what changed hands.
        List<Card> myPass = List.of();
        List<Card> received = List.of();
        if (me != null) {
            myPass = state.passes().getOrDefault(me, state.exchanged().getOrDefault(me, List.of()));
            if (!state.exchanged().isEmpty()) {
                int giver = Math.floorMod(state.positionOf(me) - state.passDirection().offset(), SEATS);
                received = state.exchanged().getOrDefault(state.seats().get(giver).id(), List.of());
            }
        }

        return new HeartsView(state.phase(), state.hand(), state.passDirection(), state.heartsBroken(),
                state.tricksPlayed(), seats, me == null ? List.of() : state.handOf(me), legal, state.trick(),
                state.leader(), onClock, state.lastTrick(), state.lastTrickWinner(), state.tricks(), myPass, received,
                state.history(), state.phase() == HeartsPhase.GAME_OVER ? lowestScore(state) : null);
    }

    /** Ties go to the earlier seat — rare, and a named winner is a better end than none. */
    private static PlayerId lowestScore(HeartsState state) {
        return state.seats().stream()
                .min(Comparator.comparingInt(seat -> state.scores().get(seat.id())))
                .map(Seat::id)
                .orElseThrow();
    }

    /** The next deal happens inside the same intent, so the only hand boundary that rests is the last. */
    @Override
    public boolean isHandComplete(HeartsState state) {
        return state.phase() == HeartsPhase.GAME_OVER;
    }

    @Override
    public boolean isComplete(HeartsState state) {
        return state.phase() == HeartsPhase.GAME_OVER;
    }

    private static List<Card> sorted(List<Card> cards) {
        return cards.stream().sorted(Rules.BY_SUIT).toList();
    }

    private static PlayerId holderOf(Map<PlayerId, List<Card>> hands, Card card) {
        return hands.entrySet().stream()
                .filter(entry -> entry.getValue().contains(card))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("nobody holds " + card));
    }
}
