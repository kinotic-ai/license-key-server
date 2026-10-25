package org.kinotic.licenses.internal.api.services;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits how many times a key may act within a sliding window. State is held in this node's memory, which is the
 * whole cluster: the server runs as one node.
 */
public class SlidingWindowRateLimiter {

    private final int limit;
    private final Duration window;
    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

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
        Deque<Instant> times = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        boolean ret;
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(windowStart)) {
                times.pollFirst();
            }
            ret = times.size() < limit;
            if (ret) {
                times.addLast(now);
            }
        }
        // keys that have gone quiet are dropped so the map tracks only recent callers
        attempts.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty() || entry.getValue().peekLast().isBefore(windowStart);
            }
        });
        return ret;
    }
}
