package com.whitejack.engine;

import com.whitejack.contract.EngineContext;
import com.whitejack.contract.ErrorCode;
import com.whitejack.contract.GameDefinition;
import com.whitejack.contract.GameEvent;
import com.whitejack.contract.Intent;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.PlayerView;
import com.whitejack.contract.Seat;
import com.whitejack.contract.Turn;
import com.whitejack.contract.Validation;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A game that exists to misbehave on demand.
 *
 * <p>The engine's tests deliberately do not use {@code high-card}: engine-core must never know
 * a game (§3), and a real game cannot be asked to throw from {@code apply} on the third event
 * of five — which is exactly the case that decides whether the fold really happens before the
 * append.
 */
final class FakeGame implements GameDefinition<FakeGame.State, FakeGame.Bump, FakeGame.Bumped> {

    record State(List<Seat> seats, List<String> applied) {
        State {
            seats = List.copyOf(seats);
            applied = List.copyOf(applied);
        }
    }

    record Bump(int events) implements Intent {}

    record Bumped(String note) implements GameEvent {}

    record View(List<String> applied, boolean seated) implements PlayerView {}

    /** The note whose {@code apply} throws, or null for a game that behaves. */
    private final String explodeOn;

    private final int minPlayers;

    /** How many applied events end the game. */
    private final int completeAt;

    FakeGame() {
        this(null, 1);
    }

    FakeGame(String explodeOn) {
        this(explodeOn, 1);
    }

    FakeGame(String explodeOn, int minPlayers) {
        this(explodeOn, minPlayers, Integer.MAX_VALUE);
    }

    FakeGame(String explodeOn, int minPlayers, int completeAt) {
        this.explodeOn = explodeOn;
        this.minPlayers = minPlayers;
        this.completeAt = completeAt;
    }

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public int version() {
        return 7;
    }

    @Override
    public int minPlayers() {
        return minPlayers;
    }

    @Override
    public int maxPlayers() {
        return 4;
    }

    @Override
    public Bump parseIntent(JsonNode raw) {
        if (raw == null || !"bump".equals(raw.path("type").asText())) {
            throw new IllegalArgumentException("fake understands only bump");
        }
        return new Bump(raw.path("events").asInt(1));
    }

    @Override
    public State createInitialState(List<Seat> seats, Map<String, String> options, EngineContext ctx) {
        return new State(seats, List.of());
    }

    @Override
    public Validation canJoin(State state, Seat seat) {
        return Validation.OK;
    }

    @Override
    public Validation validate(State state, Bump intent, PlayerId actor) {
        if (intent.events() < 0) {
            return Validation.reject(ErrorCode.ILLEGAL_MOVE, "cannot bump " + intent.events() + " times");
        }
        return Validation.OK;
    }

    @Override
    public List<Bumped> reduce(State state, Bump intent, PlayerId actor, EngineContext ctx) {
        List<Bumped> events = new ArrayList<>();
        for (int i = 0; i < intent.events(); i++) {
            events.add(new Bumped(actor.value() + "-e" + i));
        }
        return events;
    }

    @Override
    public State apply(State state, Bumped event) {
        if (event.note().equals(explodeOn)) {
            throw new IllegalStateException("boom on " + event.note());
        }
        List<String> applied = new ArrayList<>(state.applied());
        applied.add(event.note());
        return new State(state.seats(), applied);
    }

    @Override
    public Optional<Turn> turn(State state) {
        if (state.seats().isEmpty()) return Optional.empty();
        return Optional.of(new Turn(state.seats().get(0).id(), Duration.ofSeconds(10)));
    }

    @Override
    public Bump onTimeout(State state, PlayerId actor) {
        return new Bump(1);
    }

    @Override
    public View project(State state, Optional<PlayerId> viewer) {
        return new View(state.applied(), viewer.isPresent());
    }

    @Override
    public boolean isHandComplete(State state) {
        return state.applied().size() >= 3;
    }

    @Override
    public boolean isComplete(State state) {
        return state.applied().size() >= completeAt;
    }
}
