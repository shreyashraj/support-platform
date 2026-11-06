package com.example.support.service;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final long LEASE_MS = 60_000;

    private record Due(long id, String eventId, String payload, int attempts, String url, String secret) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RestClient http;
    private final int maxAttempts;
    private final boolean enabled;

    public WebhookDispatcher(JdbcTemplate jdbc, Clock clock, RestClient.Builder builder,
            com.example.support.config.PlatformProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.maxAttempts = props.webhooks().maxAttempts();
        this.enabled = props.webhooks().dispatcherEnabled();
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = builder.clone().requestFactory(factory).build();
    }

    @Scheduled(fixedDelay = 1000)
    void tick() {
        if (enabled) dispatchDue();
    }

    public void dispatchDue() {
        long now = clock.millis();
        List<Due> due = jdbc.query("""
                SELECT o.id, o.event_id, o.payload, o.attempts, s.url, s.secret
                FROM webhook_outbox o JOIN webhook_subscriptions s ON s.id = o.subscription_id
                WHERE o.status = 'pending' AND o.next_attempt_at <= ?
                ORDER BY o.next_attempt_at LIMIT 20""",
                (rs, i) -> new Due(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6)),
                now);
        for (Due d : due) {
            // lease the row first so a second instance (or the next tick) doesn't pick it up mid-delivery
            int claimed = jdbc.update("UPDATE webhook_outbox SET next_attempt_at = ? WHERE id = ? AND status = 'pending' AND next_attempt_at <= ?",
                    now + LEASE_MS, d.id(), now);
            if (claimed == 1) deliver(d);
        }
    }

    private void deliver(Due d) {
        long ts = clock.millis() / 1000;
        try {
            http.post().uri(d.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSignature.HEADER_ID, d.eventId())
                    .header(WebhookSignature.HEADER_TIMESTAMP, String.valueOf(ts))
                    .header(WebhookSignature.HEADER_SIGNATURE, WebhookSignature.sign(d.secret(), ts, d.payload()))
                    .body(d.payload())
                    .retrieve().toBodilessEntity();
            jdbc.update("UPDATE webhook_outbox SET status = 'delivered', attempts = attempts + 1, delivered_at = ?, last_error = NULL WHERE id = ?",
                    clock.millis(), d.id());
            log.info("webhook delivered event={} url={}", d.eventId(), d.url());
        } catch (RuntimeException e) {
            int attempts = d.attempts() + 1;
            String error = String.valueOf(e.getMessage());
            if (attempts >= maxAttempts) {
                jdbc.update("UPDATE webhook_outbox SET status = 'dead', attempts = ?, last_error = ? WHERE id = ?", attempts, error, d.id());
                log.error("webhook dead event={} url={} after {} attempts: {}", d.eventId(), d.url(), attempts, error);
            } else {
                long delay = Math.min(5_000L << (attempts - 1), 3_600_000L);
                jdbc.update("UPDATE webhook_outbox SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                        attempts, clock.millis() + delay, error, d.id());
                log.warn("webhook failed event={} url={} attempt={} retry in {}ms: {}", d.eventId(), d.url(), attempts, delay, error);
            }
        }
    }
}
