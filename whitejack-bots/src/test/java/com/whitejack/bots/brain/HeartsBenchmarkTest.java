package com.whitejack.bots.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.whitejack.bots.Choice;
import com.whitejack.bots.GameBrain;
import com.whitejack.bots.view.HeartsView;
import com.whitejack.contract.EngineContext;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.RandomSource;
import com.whitejack.contract.Seat;
import com.whitejack.contract.Turn;
import com.whitejack.contract.Validation;
import com.whitejack.games.hearts.HeartsEvent;
import com.whitejack.games.hearts.HeartsGame;
import com.whitejack.games.hearts.HeartsIntent;
import com.whitejack.games.hearts.HeartsPhase;
import com.whitejack.games.hearts.HeartsState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plays whole games of real Hearts between brains, each seeing exactly the JSON view the server
 * would send it, and compares the points they take. Every move must validate, so this is also a
 * legality check over thousands of positions.
 *
 * <p>Seats alternate (A B A B, then B A B A on the next seed) so neither brain gets the better
 * pass partner. {@code -Dhearts.games=N} plays more games; the default keeps the build quick.
 */
class HeartsBenchmarkTest {

    private static final HeartsGame GAME = new HeartsGame();
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new SimpleModule().addSerializer(PlayerId.class, new ToStringSerializer()));
    private static final int GAMES = Integer.getInteger("hearts.games", 200);

    /** Stands for the engine's own timeout play: pass the three highest, play the lowest legal card. */
    private static final GameBrain<HeartsView> TIMEOUT = null;

    @Test
    void cardCountingBrainTakesFewerPointsThanTheOldOne() {
        Result result = match(new HeartsBrain(), new LegacyHeartsBrain(), GAMES);
        System.out.println("hearts benchmark, new vs legacy: " + result);
        assertTrue(result.aPerHand() < result.bPerHand(), "new brain is not better: " + result);
    }

    @Test
    void cardCountingBrainBeatsTheTimeoutPlayer() {
        Result result = match(new HeartsBrain(), TIMEOUT, GAMES / 2);
        System.out.println("hearts benchmark, new vs timeout play: " + result);
        assertTrue(result.aPerHand() < result.bPerHand(), "new brain loses to the timeout player: " + result);
    }

    /** Points taken per hand, games won, and the spread of the per-hand mean. */
    record Result(int games, int hands, double aPerHand, double bPerHand, double stdErr, int aWins, int bWins) {
        @Override
        public String toString() {
            return String.format("%d games / %d hands: A %.2f vs B %.2f points per seat per hand (±%.2f), "
                    + "wins A %d, B %d", games, hands, aPerHand, bPerHand, stdErr, aWins, bWins);
        }
    }

    static Result match(GameBrain<HeartsView> a, GameBrain<HeartsView> b, int games) {
        double aPoints = 0;
        double bPoints = 0;
        int hands = 0;
        int aWins = 0;
        int bWins = 0;
        List<Double> diffs = new ArrayList<>();
        for (int g = 0; g < games; g++) {
            boolean aFirst = g % 2 == 0;
            List<GameBrain<HeartsView>> lineup = aFirst ? Arrays.asList(a, b, a, b) : Arrays.asList(b, a, b, a);
            HeartsState end = play(lineup, 1000L + g / 2);
            for (List<Integer> row : end.history()) {
                double handA = 0;
                double handB = 0;
                for (int s = 0; s < 4; s++) {
                    if (lineup.get(s) == a) handA += row.get(s);
                    else handB += row.get(s);
                }
                aPoints += handA / 2;
                bPoints += handB / 2;
                diffs.add((handA - handB) / 2);
                hands++;
            }
            int best = 0;
            for (int s = 1; s < 4; s++) {
                if (end.scores().get(end.seats().get(s).id()) < end.scores().get(end.seats().get(best).id())) best = s;
            }
            if (lineup.get(best) == a) aWins++;
            else bWins++;
        }
        double meanDiff = diffs.stream().mapToDouble(d -> d).average().orElse(0);
        double var = diffs.stream().mapToDouble(d -> (d - meanDiff) * (d - meanDiff)).sum() / Math.max(1, diffs.size() - 1);
        return new Result(games, hands, aPoints / hands, bPoints / hands, Math.sqrt(var / Math.max(1, diffs.size())),
                aWins, bWins);
    }

    /** One full game; a null brain plays the engine's timeout move. */
    static HeartsState play(List<GameBrain<HeartsView>> lineup, long seed) {
        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < 4; i++) seats.add(new Seat(i, new PlayerId("p" + i), "bot" + i));
        EngineContext ctx = ctx(seed, seats);
        HeartsState state = GAME.createInitialState(seats, Map.of(), ctx);
        for (int moves = 0; state.phase() != HeartsPhase.GAME_OVER; moves++) {
            if (moves > 20_000) fail("game did not finish");
            PlayerId actor = GAME.turn(state).map(Turn::actor).orElseThrow();
            GameBrain<HeartsView> brain = lineup.get(seats.indexOf(state.seats().stream()
                    .filter(seat -> seat.id().equals(actor)).findFirst().orElseThrow()));
            HeartsIntent intent = brain == null ? GAME.onTimeout(state, actor) : decide(brain, state, actor);
            Validation ok = GAME.validate(state, intent, actor);
            assertEquals(Validation.OK, ok, () -> "illegal move by " + brain + ": " + intent);
            for (HeartsEvent event : GAME.reduce(state, intent, actor, ctx)) state = GAME.apply(state, event);
        }
        return state;
    }

    private static HeartsIntent decide(GameBrain<HeartsView> brain, HeartsState state, PlayerId actor) {
        try {
            JsonNode wire = JSON.valueToTree(GAME.project(state, Optional.of(actor)));
            HeartsView view = JSON.treeToValue(wire, HeartsView.class);
            Choice choice = brain.choose(view, actor.value()).orElseThrow(() -> new AssertionError("no move for " + actor));
            return GAME.parseIntent(choice.intent(choice.fallback()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static EngineContext ctx(long seed, List<Seat> seats) {
        long[] s = {seed * 0x9E3779B97F4A7C15L + 1};
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
                return seats;
            }
        };
    }
}
