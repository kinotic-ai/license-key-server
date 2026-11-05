package org.kinotic.licenses.internal.api.services;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Limits how many times a key may act within a sliding window. State is held in this node's memory, which is the
 * whole cluster: the server runs as one node.
 */
public class SlidingWindowRateLimiter {

    private final int limit;
    private final Duration window;
    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private volatile Instant lastSweep = Instant.EPOCH;

    /**
     * @param limit  the most acts a key may make within the window
     * @param window the window's length
     */
    public SlidingWindowRateLimiter(int limit, Duration window) {
        this.limit = limit;
        this.window = window;
    }

    /**
     * Records an act by the key at {@code now} if the key is under its limit.
     *
     * @return {@code true} when the act is allowed and was recorded, {@code false} when the key is over its limit
     */
    public boolean tryAcquire(String key, Instant now) {
        Instant windowStart = now.minus(window);
        AtomicBoolean allowed = new AtomicBoolean();
        // pruning and recording happen under the key's own map lock, so a concurrent sweep never detaches the deque
        attempts.compute(key, (k, times) -> {
            Deque<Instant> ret = times != null ? times : new ArrayDeque<>();
            while (!ret.isEmpty() && ret.peekFirst().isBefore(windowStart)) {
                ret.pollFirst();
            }
            allowed.set(ret.size() < limit);
            if (allowed.get()) {
                ret.addLast(now);
            }
            return ret;
        });
        sweep(now, windowStart);
        return allowed.get();
    }

    // keys that have gone quiet are dropped at most once per window, so the map tracks only recent callers
    private void sweep(Instant now, Instant windowStart) {
        if (lastSweep.plus(window).isBefore(now)) {
            lastSweep = now;
            for (String key : attempts.keySet()) {
                attempts.computeIfPresent(key, (k, times) -> times.isEmpty() || times.peekLast().isBefore(windowStart) ? null : times);
            }
        }
    }
}
