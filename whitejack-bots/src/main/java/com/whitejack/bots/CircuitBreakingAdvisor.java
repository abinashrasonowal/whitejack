package com.whitejack.bots;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Wraps a remote {@link Advisor} so an outage costs a few slow moves, not every move. After
 * {@code failures} empty answers in a row it stops asking for {@code cooldown} and answers
 * empty at once, so bots play their heuristic move without waiting out a timeout. The first
 * call after the cooldown is a probe: an answer closes the breaker, another failure reopens it.
 *
 * <p>Shared by every bot thread; the counters are atomic and nothing else is mutable.
 */
public final class CircuitBreakingAdvisor implements Advisor {

    private static final System.Logger LOG = System.getLogger(CircuitBreakingAdvisor.class.getName());

    private final Advisor inner;
    private final int failures;
    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private final AtomicInteger consecutive = new AtomicInteger();
    private volatile boolean tripped;
    private volatile long openUntil;

    public CircuitBreakingAdvisor(Advisor inner, int failures, Duration cooldown) {
        this(inner, failures, cooldown, System::nanoTime);
    }

    CircuitBreakingAdvisor(Advisor inner, int failures, Duration cooldown, LongSupplier nanoTime) {
        this.inner = Objects.requireNonNull(inner, "inner");
        if (failures < 1) throw new IllegalArgumentException("failures must be at least 1");
        this.failures = failures;
        this.cooldownNanos = cooldown.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public Optional<double[]> rank(String rules, Choice choice) {
        if (isOpen()) return Optional.empty();
        Optional<double[]> answer = inner.rank(rules, choice);
        if (answer.isPresent()) {
            consecutive.set(0);
        } else if (consecutive.incrementAndGet() >= failures) {
            // Not reset here: after the cooldown a single further failure reopens at once.
            openUntil = nanoTime.getAsLong() + cooldownNanos;
            tripped = true;
            LOG.log(System.Logger.Level.WARNING, "advisor failed {0} times in a row; heuristic play for {1}s",
                    consecutive.get(), Duration.ofNanos(cooldownNanos).toSeconds());
        }
        return answer;
    }

    boolean isOpen() {
        return tripped && nanoTime.getAsLong() - openUntil < 0; // nanoTime may wrap: compare differences
    }
}
