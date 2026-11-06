package com.example.support.unit;

import com.example.support.security.RateLimiter;
import com.example.support.service.WebhookSignature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InfraTest {

    /** A clock the test can move. */
    static class TestClock extends Clock {
        long millis = 1_000_000;

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    @Test
    void rateLimiterAllowsBurstThenRefills() {
        TestClock clock = new TestClock();
        RateLimiter limiter = new RateLimiter(clock);
        for (int i = 0; i < 60; i++) Assertions.assertTrue(limiter.tryAcquire("k", 60).allowed());
        var denied = limiter.tryAcquire("k", 60);
        Assertions.assertFalse(denied.allowed());
        Assertions.assertEquals(1, denied.retryAfterSeconds());

        clock.millis += 2_000;
        Assertions.assertTrue(limiter.tryAcquire("k", 60).allowed());
        Assertions.assertTrue(limiter.tryAcquire("k", 60).allowed());
        Assertions.assertFalse(limiter.tryAcquire("k", 60).allowed());
        Assertions.assertTrue(limiter.tryAcquire("other", 60).allowed());
    }

    @Test
    void signatureVerifiesAndRejectsTampering() {
        String body = "{\"id\":\"evt_1\"}";
        String sig = WebhookSignature.sign("s3cret", 1000, body);
        Assertions.assertTrue(sig.matches("^v1=[0-9a-f]{64}$"));
        Assertions.assertTrue(WebhookSignature.verify("s3cret", "1000", body, sig, 300, 1100));
        Assertions.assertFalse(WebhookSignature.verify("s3cret", "1000", body + " ", sig, 300, 1100), "body changed");
        Assertions.assertFalse(WebhookSignature.verify("wrong", "1000", body, sig, 300, 1100), "wrong secret");
        Assertions.assertFalse(WebhookSignature.verify("s3cret", "1000", body, sig, 300, 2000), "replay outside tolerance");
        Assertions.assertFalse(WebhookSignature.verify("s3cret", null, body, sig, 300, 1100));
        Assertions.assertFalse(WebhookSignature.verify("s3cret", "abc", body, sig, 300, 1100));
    }
}
