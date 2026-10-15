package com.whitejack.games.hearts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.whitejack.contract.Card;
import com.whitejack.contract.EngineContext;
import com.whitejack.contract.ErrorCode;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.RandomSource;
import com.whitejack.contract.Rank;
import com.whitejack.contract.Seat;
import com.whitejack.contract.Suit;
import com.whitejack.contract.Validation;
import com.whitejack.games.hearts.HeartsEvent.Dealt;
import com.whitejack.games.hearts.HeartsIntent.Pass;
import com.whitejack.games.hearts.HeartsIntent.Play;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HeartsGameTest {

    private static final HeartsGame GAME = new HeartsGame();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final PlayerId P0 = new PlayerId("p0");
    private static final PlayerId P1 = new PlayerId("p1");
    private static final PlayerId P2 = new PlayerId("p2");
    private static final PlayerId P3 = new PlayerId("p3");
    private static final List<PlayerId> PLAYERS = List.of(P0, P1, P2, P3);
    private static final List<Seat> SEATS = List.of(
            new Seat(0, P0, "abi"), new Seat(1, P1, "sam"), new Seat(2, P2, "kim"), new Seat(3, P3, "lee"));

    /** Stands in for HmacRandom, which lives in engine-core and must never be on this classpath. */
    private static EngineContext ctx(long seed) {
        long[] s = {seed};
        RandomSource rng = bound -> {
            s[0] = s[0] * 6364136223846793005L + 1442695040888963407L;
            return (int) Long.remainderUnsigned(s[0] >>> 33, bound);
        };
        return new EngineContext() {
            @Override
            public long now() {
                return 1_700_000_000_000L;
            }

            @Override
            public RandomSource random() {
                return rng;
            }

            @Override
            public List<Seat> seats() {
                return SEATS;
            }
        };
    }

    /** The engine's loop from §9, minus the log: validate, reduce, fold. */
    private static HeartsState step(HeartsState state, HeartsIntent intent, PlayerId actor, EngineContext ctx) {
        Validation verdict = GAME.validate(state, intent, actor);
        assertTrue(verdict.isOk(), () -> actor + " " + intent + " rejected: " + verdict);
        HeartsState next = state;
        for (HeartsEvent event : GAME.reduce(state, intent, actor, ctx)) next = GAME.apply(next, event);
        return next;
    }

    private static ErrorCode rejection(HeartsState state, HeartsIntent intent, PlayerId actor) {
        Validation verdict = GAME.validate(state, intent, actor);
        return assertInstanceOf(Validation.Reject.class, verdict).code();
    }

    /** "QS", "10H", "2C". */
    private static Card c(String code) {
        String rank = code.substring(0, code.length() - 1);
        Suit suit = switch (code.charAt(code.length() - 1)) {
            case 'C' -> Suit.CLUBS;
            case 'D' -> Suit.DIAMONDS;
            case 'H' -> Suit.HEARTS;
            default -> Suit.SPADES;
        };
        Rank r = Arrays.stream(Rank.values()).filter(v -> v.symbol().equals(rank)).findFirst().orElseThrow();
        return new Card(r, suit);
    }

    private static List<Card> cards(String... codes) {
        return Arrays.stream(codes).map(HeartsGameTest::c).toList();
    }

    /** A hand in progress with chosen cards: nothing else about the state matters to these tests. */
    private static HeartsState playing(List<List<Card>> hands, PlayerId leader, List<PlayedCard> trick,
            int tricksPlayed, boolean heartsBroken, Map<PlayerId, Integer> handPoints, Map<PlayerId, Integer> scores) {
        Map<PlayerId, List<Card>> byPlayer = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) byPlayer.put(PLAYERS.get(i), hands.get(i));
        return new HeartsState(SEATS, 0, HeartsPhase.PLAYING, byPlayer, Map.of(), trick, leader, tricksPlayed,
                heartsBroken, handPoints, scores, List.of(), List.of(), null, List.of(), Map.of());
    }

    private static HeartsState playing(List<List<Card>> hands, PlayerId leader, List<PlayedCard> trick,
            int tricksPlayed, boolean heartsBroken) {
        Map<PlayerId, Integer> zero = HeartsState.zeroes(SEATS);
        return playing(hands, leader, trick, tricksPlayed, heartsBroken, zero, zero);
    }

    private static HeartsState passEveryone(HeartsState state) {
        for (PlayerId player : PLAYERS) state = step(state, new Pass(state.handOf(player).subList(0, 3)), player, ctx(1));
        return state;
    }

    // ---- dealing and passing ----

    @Test
    void dealsThirteenDistinctCardsToEachSeat() {
        HeartsState state = GAME.createInitialState(SEATS, Map.of(), ctx(7));
        Set<Card> all = new HashSet<>();
        for (PlayerId player : PLAYERS) {
            assertEquals(13, state.handOf(player).size());
            all.addAll(state.handOf(player));
        }
        assertEquals(52, all.size());
        assertEquals(HeartsPhase.PASSING, state.phase());
        assertEquals(PassDirection.LEFT, state.passDirection());
    }

    @Test
    void passDirectionCyclesLeftRightAcrossHold() {
        assertEquals(List.of(PassDirection.LEFT, PassDirection.RIGHT, PassDirection.ACROSS, PassDirection.HOLD,
                PassDirection.LEFT), List.of(0, 1, 2, 3, 4).stream().map(PassDirection::forHand).toList());
    }

    @Test
    void theFourthPassMovesCardsInTheHandsDirection() {
        for (int hand = 0; hand < 3; hand++) {
            HeartsState dealt = GAME.createInitialState(SEATS, Map.of(), ctx(hand + 11));
            HeartsState state = GAME.apply(dealt, new Dealt(hand, dealt.hands()));
            int offset = PassDirection.forHand(hand).offset();

            HeartsState passed = passEveryone(state);
            assertEquals(HeartsPhase.PLAYING, passed.phase());
            for (int i = 0; i < 4; i++) {
                List<Card> given = state.handOf(PLAYERS.get(i)).subList(0, 3);
                PlayerId receiver = PLAYERS.get((i + offset) % 4);
                assertTrue(passed.handOf(receiver).containsAll(given), "hand " + hand + ": seat " + i + "'s pass");
                assertEquals(13, passed.handOf(PLAYERS.get(i)).size());
            }
            assertTrue(passed.handOf(passed.leader()).contains(Rules.TWO_OF_CLUBS), "2♣ holder leads");
        }
    }

    @Test
    void holdHandsSkipPassing() {
        HeartsState dealt = GAME.createInitialState(SEATS, Map.of(), ctx(3));
        HeartsState hold = GAME.apply(dealt, new Dealt(3, dealt.hands()));
        assertEquals(HeartsPhase.PLAYING, hold.phase());
        assertTrue(hold.handOf(hold.leader()).contains(Rules.TWO_OF_CLUBS));
    }

    @Test
    void passesMustBeThreeOwnDistinctCardsOncePerHand() {
        HeartsState state = GAME.createInitialState(SEATS, Map.of(), ctx(5));
        List<Card> mine = state.handOf(P0);
        assertEquals(ErrorCode.ILLEGAL_MOVE, rejection(state, new Pass(mine.subList(0, 2)), P0));
        assertEquals(ErrorCode.ILLEGAL_MOVE, rejection(state, new Pass(List.of(mine.get(0), mine.get(0), mine.get(1))), P0));
        assertEquals(ErrorCode.ILLEGAL_MOVE, rejection(state, new Pass(state.handOf(P1).subList(0, 3)), P0));

        HeartsState passed = step(state, new Pass(mine.subList(0, 3)), P0, ctx(1));
        assertEquals(ErrorCode.WRONG_PHASE, rejection(passed, new Pass(mine.subList(3, 6)), P0));
        assertEquals(ErrorCode.WRONG_PHASE, rejection(passed, new Play(mine.get(5)), P0));
    }

    @Test
    void passingIsSimultaneousNotInSeatOrder() {
        HeartsState state = GAME.createInitialState(SEATS, Map.of(), ctx(5));
        assertEquals(P0, GAME.turn(state).orElseThrow().actor(), "the clock runs on the first seat to pass");
        HeartsState passed = step(state, new Pass(state.handOf(P3).subList(0, 3)), P3, ctx(1));
        assertEquals(P0, GAME.turn(passed).orElseThrow().actor());
    }

    // ---- play ----

    @Test
    void theTwoOfClubsLeadsTheFirstTrick() {
        HeartsState state = playing(List.of(cards("2C", "5C", "AS"), cards("3C", "KD", "4H"),
                cards("4C", "QS", "5H"), cards("6C", "2D", "6H")), P0, List.of(), 0, false);
        assertEquals(ErrorCode.ILLEGAL_MOVE, rejection(state, new Play(c("5C")), P0));
        assertEquals(ErrorCode.NOT_YOUR_TURN, rejection(state, new Play(c("3C")), P1));
        assertTrue(GAME.validate(state, new Play(c("2C")), P0).isOk());
    }

    @Test
    void playersMustFollowSuit() {
        HeartsState state = playing(List.of(cards("5C"), cards("3C", "KD"), cards("4H"), cards("6C")),
                P0, List.of(), 1, false);
        state = step(state, new Play(c("5C")), P0, ctx(1));
        assertEquals(ErrorCode.ILLEGAL_MOVE, rejection(state, new Play(c("KD")), P1));
        assertTrue(GAME.validate(state, new Play(c("3C")), P1).isOk());
    }

    @Test
    void noPointsOnTheFirstTrickUnlessThatIsAllYouHold() {
        HeartsState state = playing(List.of(cards("2C"), cards("QS", "4H", "KD"), cards("QH", "JH"), cards("6C")),
                P0, List.of(), 0, false);
        state = step(state, new Play(c("2C")), P0, ctx(1));
        assertEquals(ErrorCode.ILLEGAL_MOVE, rejection(state, new Play(c("QS")), P1));
        assertEquals(ErrorCode.ILLEGAL_MOVE, rejection(state, new Play(c("4H")), P1));
        state = step(state, new Play(c("KD")), P1, ctx(1));
        assertTrue(GAME.validate(state, new Play(c("QH")), P2).isOk(), "nothing but hearts: the rule gives way");
    }

    @Test
    void heartsCannotBeLedUntilBroken() {
        HeartsState unbroken = playing(List.of(cards("4H", "5C"), cards("3C"), cards("4C"), cards("6C")),
                P0, List.of(), 3, false);
        assertEquals(ErrorCode.ILLEGAL_MOVE, rejection(unbroken, new Play(c("4H")), P0));

        HeartsState onlyHearts = playing(List.of(cards("4H", "9H"), cards("3C"), cards("4C"), cards("6C")),
                P0, List.of(), 3, false);
        assertTrue(GAME.validate(onlyHearts, new Play(c("4H")), P0).isOk());

        HeartsState broken = playing(List.of(cards("4H", "5C"), cards("3C"), cards("4C"), cards("6C")),
                P0, List.of(), 3, true);
        assertTrue(GAME.validate(broken, new Play(c("4H")), P0).isOk());
    }

    @Test
    void highestOfTheLedSuitTakesTheTrickAndItsPointsAndLeadsNext() {
        HeartsState state = playing(List.of(cards("5C", "2S"), cards("KC", "3S"), cards("AH", "4S"), cards("QS", "5S")),
                P0, List.of(), 2, true);
        state = step(state, new Play(c("5C")), P0, ctx(1));
        state = step(state, new Play(c("KC")), P1, ctx(1));
        state = step(state, new Play(c("AH")), P2, ctx(1)); // void in clubs: an ace of hearts does not win
        state = step(state, new Play(c("QS")), P3, ctx(1));

        assertEquals(P1, state.lastTrickWinner());
        assertEquals(P1, state.leader());
        assertEquals(14, state.handPoints().get(P1));
        assertEquals(3, state.tricksPlayed());
        assertTrue(state.trick().isEmpty());
        assertEquals(4, state.lastTrick().size());
        assertEquals(P1, GAME.turn(state).orElseThrow().actor());
    }

    // ---- scoring ----

    @Test
    void takingAllTwentySixShootsTheMoonAndDealsTheNextHand() {
        Map<PlayerId, Integer> handPoints = Map.of(P0, 25, P1, 0, P2, 0, P3, 0);
        HeartsState state = playing(List.of(cards("AH"), cards("2C"), cards("3C"), cards("4C")), P0, List.of(), 12,
                true, handPoints, HeartsState.zeroes(SEATS));
        for (PlayerId player : PLAYERS) {
            state = step(state, new Play(state.handOf(player).get(0)), player, ctx(9));
        }
        assertEquals(List.of(0, 26, 26, 26), state.history().get(0));
        assertEquals(0, state.scores().get(P0));
        assertEquals(26, state.scores().get(P3));
        assertEquals(1, state.hand(), "the last card of a hand deals the next");
        assertEquals(HeartsPhase.PASSING, state.phase());
        assertEquals(PassDirection.RIGHT, state.passDirection());
        assertEquals(13, state.handOf(P2).size());
    }

    @Test
    void reachingOneHundredEndsTheGameAndTheLowestScoreWins() {
        Map<PlayerId, Integer> scores = Map.of(P0, 40, P1, 95, P2, 30, P3, 60);
        HeartsState state = playing(List.of(cards("2C"), cards("AC"), cards("3H"), cards("4H")), P0, List.of(), 12,
                true, Map.of(P0, 0, P1, 20, P2, 0, P3, 0), scores);
        for (PlayerId player : PLAYERS) {
            state = step(state, new Play(state.handOf(player).get(0)), player, ctx(9));
        }
        assertEquals(HeartsPhase.GAME_OVER, state.phase());
        assertEquals(117, state.scores().get(P1));
        assertTrue(GAME.isComplete(state));
        assertEquals(Optional.empty(), GAME.turn(state));
        assertEquals(P2, GAME.project(state, Optional.of(P0)).winner());
    }

    // ---- boundary ----

    @Test
    void seatsAreFixedOnceDealt() {
        HeartsState state = GAME.createInitialState(SEATS, Map.of(), ctx(1));
        assertEquals(ErrorCode.SEAT_UNAVAILABLE,
                assertInstanceOf(Validation.Reject.class, GAME.canJoin(state, new Seat(4, new PlayerId("x"), "x"))).code());
    }

    @Test
    void aViewNeverShowsAnotherPlayersCards() {
        HeartsState state = passEveryone(GAME.createInitialState(SEATS, Map.of(), ctx(21)));
        HeartsView mine = GAME.project(state, Optional.of(P1));
        assertEquals(state.handOf(P1), mine.myHand());
        for (HeartsView.SeatView seat : mine.seats()) assertEquals(13, seat.cardCount());
        for (PlayerId other : List.of(P0, P2, P3)) {
            for (Card card : state.handOf(other)) assertFalse(mine.myHand().contains(card));
        }
        if (!P1.equals(mine.onClock())) assertTrue(mine.legal().isEmpty(), "no legal cards off-turn");

        HeartsView spectator = GAME.project(state, Optional.empty());
        assertTrue(spectator.myHand().isEmpty());
        assertTrue(spectator.legal().isEmpty());
    }

    @Test
    void aViewShowsYourOwnPassBothWaysAndNobodyElses() {
        for (int hand = 0; hand < 3; hand++) {
            HeartsState dealt = GAME.createInitialState(SEATS, Map.of(), ctx(hand + 31));
            HeartsState state = GAME.apply(dealt, new Dealt(hand, dealt.hands()));
            int offset = PassDirection.forHand(hand).offset();

            HeartsState oneIn = step(state, new Pass(state.handOf(P0).subList(0, 3)), P0, ctx(1));
            assertEquals(state.handOf(P0).subList(0, 3), GAME.project(oneIn, Optional.of(P0)).myPass(), "pending pick");
            assertTrue(GAME.project(oneIn, Optional.of(P0)).received().isEmpty(), "nothing received before the exchange");
            assertTrue(GAME.project(oneIn, Optional.of(P1)).myPass().isEmpty(), "another seat's pick stays hidden");

            HeartsState passed = passEveryone(state);
            for (int i = 0; i < 4; i++) {
                HeartsView view = GAME.project(passed, Optional.of(PLAYERS.get(i)));
                assertEquals(state.handOf(PLAYERS.get(i)).subList(0, 3), view.myPass(), "hand " + hand + " seat " + i);
                PlayerId giver = PLAYERS.get(Math.floorMod(i - offset, 4));
                assertEquals(state.handOf(giver).subList(0, 3), view.received(), "hand " + hand + " seat " + i);
            }
            HeartsView spectator = GAME.project(passed, Optional.empty());
            assertTrue(spectator.myPass().isEmpty());
            assertTrue(spectator.received().isEmpty());
        }

        HeartsState dealt = GAME.createInitialState(SEATS, Map.of(), ctx(3));
        HeartsState hold = GAME.apply(dealt, new Dealt(3, dealt.hands()));
        assertTrue(GAME.project(hold, Optional.of(P0)).myPass().isEmpty());
        assertTrue(GAME.project(hold, Optional.of(P0)).received().isEmpty());
    }

    @Test
    void everyFinishedTrickOfTheHandIsPublicAndClearsOnTheNextDeal() {
        EngineContext ctx = ctx(41);
        HeartsState state = passEveryone(GAME.createInitialState(SEATS, Map.of(), ctx));
        List<Card> seen = new ArrayList<>();
        while (state.tricksPlayed() < 5) {
            PlayerId actor = GAME.turn(state).orElseThrow().actor();
            HeartsIntent move = GAME.onTimeout(state, actor);
            seen.add(((Play) move).card());
            state = step(state, move, actor, ctx);
        }
        HeartsView view = GAME.project(state, Optional.of(P2));
        assertEquals(5, view.tricks().size());
        assertEquals(seen.subList(0, 20), view.tricks().stream()
                .flatMap(t -> t.plays().stream()).map(PlayedCard::card).toList());
        assertEquals(state.lastTrickWinner(), view.tricks().get(4).winner());
        assertEquals(view.tricks(), GAME.project(state, Optional.empty()).tricks(), "spectators see the same tricks");

        while (state.hand() == 0) {
            PlayerId actor = GAME.turn(state).orElseThrow().actor();
            state = step(state, GAME.onTimeout(state, actor), actor, ctx);
        }
        assertTrue(GAME.project(state, Optional.of(P2)).tricks().isEmpty(), "a new hand starts with no tricks");
        assertTrue(GAME.project(state, Optional.of(P2)).myPass().isEmpty(), "and no pass yet");
    }

    @Test
    void parsesBothIntentsAndRejectsJunk() throws Exception {
        HeartsIntent pass = GAME.parseIntent(MAPPER.readTree(
                "{\"type\":\"pass\",\"cards\":[{\"rank\":\"ACE\",\"suit\":\"SPADES\"},"
                        + "{\"rank\":\"TWO\",\"suit\":\"CLUBS\"},{\"rank\":\"TEN\",\"suit\":\"HEARTS\"}]}"));
        assertEquals(new Pass(cards("AS", "2C", "10H")), pass);
        assertEquals(new Play(c("QS")),
                GAME.parseIntent(MAPPER.readTree("{\"type\":\"play\",\"card\":{\"rank\":\"QUEEN\",\"suit\":\"SPADES\"}}")));

        for (String junk : List.of("{\"type\":\"draw\"}", "{\"type\":\"play\"}", "{\"type\":\"play\",\"card\":{\"rank\":\"ELEVEN\",\"suit\":\"SPADES\"}}",
                "{\"type\":\"pass\",\"cards\":\"AS\"}", "{\"type\":\"play\",\"card\":{\"suit\":\"SPADES\"}}")) {
            assertThrows(IllegalArgumentException.class, () -> GAME.parseIntent(MAPPER.readTree(junk)), junk);
        }
    }

    // ---- property: whole games driven by the timeout path ----

    /**
     * Plays 200 seeded games using nothing but {@code onTimeout}. This is the contract
     * obligation — every timeout move must validate, or a room freezes alive — plus the
     * accounting that has to hold after every single move.
     */
    @Test
    void wholeGamesPlayedOnTimeoutsAlwaysReachGameOver() {
        for (long seed = 0; seed < 200; seed++) {
            EngineContext ctx = ctx(seed);
            HeartsState state = GAME.createInitialState(SEATS, Map.of(), ctx);
            int hands = 0;
            for (int moves = 0; state.phase() != HeartsPhase.GAME_OVER; moves++) {
                assertTrue(moves < 20_000, "seed " + seed + " never ended");
                PlayerId actor = GAME.turn(state).orElseThrow().actor();
                HeartsState next = step(state, GAME.onTimeout(state, actor), actor, ctx);

                int held = next.hands().values().stream().mapToInt(List::size).sum();
                int expected = next.phase() == HeartsPhase.GAME_OVER ? 0 : 52 - 4 * next.tricksPlayed() - next.trick().size();
                assertEquals(expected, held, "seed " + seed + ": cards went missing");

                if (next.history().size() > hands) {
                    hands = next.history().size();
                    int total = next.history().get(hands - 1).stream().mapToInt(Integer::intValue).sum();
                    assertTrue(total == 26 || total == 78, "seed " + seed + ": a hand scored " + total);
                }
                state = next;
            }
            assertTrue(state.scores().values().stream().anyMatch(score -> score >= 100));
            List<Integer> totals = new ArrayList<>(state.scores().values());
            assertEquals(totals.stream().mapToInt(Integer::intValue).sum(),
                    state.history().stream().flatMap(List::stream).mapToInt(Integer::intValue).sum());
            assertNull(GAME.project(state, Optional.of(P0)).onClock());
        }
    }
}
