package com.whitejack.bots;

import com.whitejack.bots.brain.GinBrain;
import com.whitejack.bots.brain.HeartsBrain;
import com.whitejack.bots.brain.HighCardBrain;
import com.whitejack.bots.brain.PokerBrain;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The games a bot knows how to play, by game id. One instance of each brain serves every bot
 * thread, so a {@link GameBrain} must keep no state between calls. Two brains claiming one id
 * fail here, at class load, rather than one silently shadowing the other.
 */
public final class Brains {

    private static final Map<String, GameBrain<?>> BY_GAME = Stream.<GameBrain<?>>of(
                    new HighCardBrain(), new HeartsBrain(), new GinBrain(), new PokerBrain())
            .collect(Collectors.toUnmodifiableMap(GameBrain::gameId, Function.identity()));

    private Brains() {}

    public static Optional<GameBrain<?>> forGame(String gameId) {
        return Optional.ofNullable(BY_GAME.get(gameId));
    }
}
