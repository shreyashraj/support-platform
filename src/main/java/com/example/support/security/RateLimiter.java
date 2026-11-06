package com.example.support.security;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory token bucket per key. Fine for one instance; a shared limiter belongs at the gateway (see PLATFORM.md). */
@Component
public class RateLimiter {
    public record Decision(boolean allowed, int limit, int remaining, int resetSeconds, int retryAfterSeconds) {}

    private static final class Bucket {
        double tokens;
        long lastRefillMs;
    }

    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public Decision tryAcquire(String key, int perMinute) {
        double ratePerMs = perMinute / 60_000.0;
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.tokens = perMinute;
            nb.lastRefillMs = clock.millis();
            return nb;
        });
        synchronized (b) {
            long now = clock.millis();
            b.tokens = Math.min(perMinute, b.tokens + (now - b.lastRefillMs) * ratePerMs);
            b.lastRefillMs = now;
            boolean ok = b.tokens >= 1;
            if (ok) b.tokens -= 1;
            int reset = (int) Math.ceil((perMinute - b.tokens) / ratePerMs / 1000.0);
            int retry = ok ? 0 : (int) Math.ceil((1 - b.tokens) / ratePerMs / 1000.0);
            return new Decision(ok, perMinute, (int) Math.floor(b.tokens), reset, retry);
        }
    }

    public void reset() {
        buckets.clear();
    }
}
