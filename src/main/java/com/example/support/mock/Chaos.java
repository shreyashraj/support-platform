package com.example.support.mock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Fault injection for the mock upstreams, so the resilience behaviour can be shown and tested. */
@Component
@MockConditional
public class Chaos {
    public enum Mode { OK, DOWN, SLOW }

    public static final class State {
        volatile Mode mode = Mode.OK;
        volatile long delayMs = 0;
        final AtomicInteger failNext = new AtomicInteger();
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public State state(String target) {
        return states.computeIfAbsent(target, t -> new State());
    }

    public void set(String target, Mode mode, long delayMs, int failNext) {
        State s = state(target);
        s.mode = mode;
        s.delayMs = delayMs;
        s.failNext.set(failNext);
    }

    public void reset() {
        states.clear();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new java.util.TreeMap<>();
        states.forEach((k, v) -> out.put(k, Map.of("mode", v.mode, "delayMs", v.delayMs, "failNext", v.failNext.get())));
        return out;
    }

    /** @return true if the request should be failed with a 503 */
    public boolean apply(String target) {
        State s = state(target);
        if (s.failNext.get() > 0 && s.failNext.getAndDecrement() > 0) return true;
        if (s.mode == Mode.DOWN) return true;
        if (s.mode == Mode.SLOW) {
            try {
                Thread.sleep(s.delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }
}
