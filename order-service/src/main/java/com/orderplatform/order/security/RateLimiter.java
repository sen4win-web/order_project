package com.orderplatform.order.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory sliding window rate limiter.
 * Tracks request timestamps per key (IP address) and rejects
 * requests exceeding the limit within the time window.
 * Includes periodic cleanup to prevent memory leaks from stale entries.
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

    /**
     * Periodic cleanup of stale entries to prevent memory leaks.
     * Runs every 5 minutes. Removes keys with no recent activity.
     */
    @Scheduled(fixedRate = 300_000)
    public void cleanup() {
        long windowStart = System.currentTimeMillis() - WINDOW_MS;
        attempts.entrySet().removeIf(entry -> {
            ConcurrentLinkedDeque<Long> timestamps = entry.getValue();
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            return timestamps.isEmpty();
        });
    }
}
