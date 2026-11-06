package com.example.support.mcp;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Token bucket per named bucket (reads and writes are limited separately). */
class RateLimiter {
    record Decision(boolean allowed, int retryAfterSeconds) {}

    private static final class Bucket {
        double tokens;
        long last;
    }

    private final LongSupplier clockMs;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimiter() {
        this(System::currentTimeMillis);
    }

    RateLimiter(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    Decision tryAcquire(String bucket, int perMinute) {
        double perMs = perMinute / 60_000.0;
        Bucket b = buckets.computeIfAbsent(bucket, k -> {
            Bucket nb = new Bucket();
            nb.tokens = perMinute;
            nb.last = clockMs.getAsLong();
            return nb;
        });
        synchronized (b) {
            long now = clockMs.getAsLong();
            b.tokens = Math.min(perMinute, b.tokens + (now - b.last) * perMs);
            b.last = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return new Decision(true, 0);
            }
            return new Decision(false, (int) Math.ceil((1 - b.tokens) / perMs / 1000.0));
        }
    }
}
