package com.example.support.upstream;

import com.example.support.upstream.UpstreamUnavailableException.Kind;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Retry (outer) around a circuit breaker (inner) for one upstream. */
public class Guard {
    private static final Logger log = LoggerFactory.getLogger(Guard.class);

    private final String name;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public Guard(String name, int maxAttempts, Duration backoff) {
        this.name = name;
        this.breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(UpstreamClientException.class)
                .build());
        breaker.getEventPublisher().onStateTransition(e -> log.warn("circuit {} {}", name, e.getStateTransition()));
        this.retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(backoff, 2.0, 0.3))
                .retryOnException(e -> !(e instanceof CallNotPermittedException) && !(e instanceof UpstreamClientException))
                .build());
    }

    public CircuitBreaker breaker() { return breaker; }

    /**
     * @param retryable only pass true for calls that are safe to repeat (reads, or writes carrying an idempotency key)
     */
    public <T> T call(boolean retryable, Supplier<T> op) {
        Supplier<T> translated = () -> {
            try {
                return op.get();
            } catch (HttpClientErrorException e) {
                throw new UpstreamClientException(e.getStatusCode().value(), e.getResponseBodyAsString());
            }
        };
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, translated);
        if (retryable) guarded = Retry.decorateSupplier(retry, guarded);
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new UpstreamUnavailableException(name, Kind.CIRCUIT_OPEN, e);
        } catch (UpstreamClientException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UpstreamUnavailableException(name, isTimeout(e) ? Kind.TIMEOUT : Kind.ERROR, e);
        }
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HttpTimeoutException || c instanceof java.net.SocketTimeoutException) return true;
            if (c instanceof ResourceAccessException && c.getMessage() != null && c.getMessage().contains("timed out")) return true;
        }
        return false;
    }
}
