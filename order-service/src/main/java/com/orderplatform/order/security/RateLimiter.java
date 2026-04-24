package com.orderplatform.order.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Simple in-memory rate limiter using a sliding window.
 * Tracks request timestamps per key (e.g., IP address) and rejects
 * requests that exceed the configured limit within the time window.
 */
@Component
public class RateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 60_000; // 1 minute

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> attempts = new ConcurrentHashMap<>();

    /**
     * Returns true if the request is allowed, false if rate limit exceeded.
     */
    public boolean isAllowed(String key) {
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;

        ConcurrentLinkedDeque<Long> timestamps = attempts.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        // Remove expired entries
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= MAX_ATTEMPTS) {
            return false;
        }

        timestamps.addLast(now);
        return true;
    }
}
