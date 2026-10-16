package com.whitejack.bots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CircuitBreakingAdvisorTest {

    private static final Choice CHOICE = Choice.single("k", "state", "question",
            List.of(new Move("a", JsonNodeFactory.instance.textNode("a")), new Move("b", JsonNodeFactory.instance.textNode("b"))), 0);

    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicInteger asked = new AtomicInteger();
    private final AtomicBoolean up = new AtomicBoolean(false);
    private final Advisor remote = (rules, choice) -> {
        asked.incrementAndGet();
        return up.get() ? Optional.of(new double[] {1, 0}) : Optional.empty();
    };
    private final CircuitBreakingAdvisor breaker =
            new CircuitBreakingAdvisor(remote, 3, Duration.ofNanos(500), now::get);

    @Test
    void stopsAskingAfterRepeatedFailuresUntilTheCooldownEnds() {
        for (int i = 0; i < 3; i++) assertTrue(breaker.rank("", CHOICE).isEmpty());
        assertEquals(3, asked.get());
        assertTrue(breaker.isOpen());

        assertTrue(breaker.rank("", CHOICE).isEmpty());
        assertEquals(3, asked.get(), "an open breaker must not call the remote advisor");

        now.addAndGet(500);
        up.set(true);
        assertTrue(breaker.rank("", CHOICE).isPresent(), "the probe after the cooldown goes through");
        assertFalse(breaker.isOpen());
        assertEquals(4, asked.get());
    }

    @Test
    void aFailedProbeReopensAtOnce() {
        for (int i = 0; i < 3; i++) breaker.rank("", CHOICE);
        now.addAndGet(500);
        breaker.rank("", CHOICE);
        assertTrue(breaker.isOpen(), "one failure after the cooldown is enough to reopen");
    }

    @Test
    void anAnswerResetsTheCount() {
        breaker.rank("", CHOICE);
        breaker.rank("", CHOICE);
        up.set(true);
        breaker.rank("", CHOICE);
        up.set(false);
        breaker.rank("", CHOICE);
        breaker.rank("", CHOICE);
        assertFalse(breaker.isOpen(), "failures must be consecutive");
    }
}
