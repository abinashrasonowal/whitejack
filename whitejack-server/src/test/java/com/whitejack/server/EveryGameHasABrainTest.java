package com.whitejack.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.whitejack.bots.Brains;
import com.whitejack.contract.GameDefinition;
import com.whitejack.engine.GameCatalog;
import org.junit.jupiter.api.Test;

/**
 * The server is the one module that sees both the games on the classpath and the bots' brains,
 * so this is where a new game without a brain is caught: otherwise "Add AI bot" would fail only
 * when someone clicks it.
 */
class EveryGameHasABrainTest {

    @Test
    void everyGameOnTheClasspathHasABotBrain() {
        GameCatalog catalog = GameCatalog.load(getClass().getClassLoader());
        assertFalse(catalog.all().isEmpty(), "no games found on the test classpath");
        for (GameDefinition<?, ?, ?> game : catalog.all()) {
            assertTrue(Brains.forGame(game.id()).isPresent(), "no bot brain for " + game.id());
        }
    }
}
