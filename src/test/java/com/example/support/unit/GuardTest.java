package com.example.support.unit;

import com.example.support.upstream.Guard;
import com.example.support.upstream.UpstreamClientException;
import com.example.support.upstream.UpstreamUnavailableException;
import com.example.support.upstream.UpstreamUnavailableException.Kind;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class GuardTest {

    private Guard guard() {
        return new Guard("test", 3, Duration.ofMillis(1));
    }

    @Test
    void retriesTransientFailuresUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        String out = guard().call(true, () -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("boom");
            return "ok";
        });
        Assertions.assertEquals("ok", out);
        Assertions.assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryWhenNotRetryable() {
        AtomicInteger calls = new AtomicInteger();
        Assertions.assertThrows(UpstreamUnavailableException.class, () -> guard().call(false, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        Assertions.assertEquals(1, calls.get());
    }

    @Test
    void clientErrorsPassThroughUnretriedAndDoNotTripTheBreaker() {
        Guard g = guard();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            Assertions.assertThrows(UpstreamClientException.class, () -> g.call(true, () -> {
                calls.incrementAndGet();
                throw new UpstreamClientException(404, "nope");
            }));
        }
        Assertions.assertEquals(20, calls.get());
        Assertions.assertEquals(CircuitBreaker.State.CLOSED, g.breaker().getState());
    }

    @Test
    void breakerOpensAfterRepeatedFailuresAndFailsFast() {
        Guard g = guard();
        for (int i = 0; i < 4; i++) {
            Assertions.assertThrows(UpstreamUnavailableException.class, () -> g.call(true, () -> {
                throw new IllegalStateException("down");
            }));
        }
        Assertions.assertEquals(CircuitBreaker.State.OPEN, g.breaker().getState());

        AtomicInteger calls = new AtomicInteger();
        var ex = Assertions.assertThrows(UpstreamUnavailableException.class, () -> g.call(true, () -> {
            calls.incrementAndGet();
            return "never";
        }));
        Assertions.assertEquals(Kind.CIRCUIT_OPEN, ex.kind());
        Assertions.assertEquals(0, calls.get());
    }

    @Test
    void timeoutsAreClassifiedAsTimeouts() {
        var ex = Assertions.assertThrows(UpstreamUnavailableException.class, () -> guard().call(false, () -> {
            throw new ResourceAccessException("I/O error", new java.net.http.HttpTimeoutException("request timed out"));
        }));
        Assertions.assertEquals(Kind.TIMEOUT, ex.kind());
    }
}
