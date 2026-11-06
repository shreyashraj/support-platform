package com.example.support.service;

import com.example.support.config.PlatformProperties;
import com.example.support.web.ProblemException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {
    public record Subscription(String id, String url, List<String> events, boolean active, Instant createdAt) {}

    public record CreatedSubscription(String id, String url, List<String> events, boolean active, Instant createdAt, String secret) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PlatformProperties props;

    public WebhookService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, PlatformProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.props = props;
    }

    public CreatedSubscription create(String url, List<String> events, String createdBy) {
        return createWithSecret(url, events, createdBy, randomHex(32));
    }

    public CreatedSubscription createWithSecret(String url, List<String> events, String createdBy, String secret) {
        validateTarget(url);
        String id = "whs_" + randomHex(8);
        long now = clock.millis();
        jdbc.update("INSERT INTO webhook_subscriptions (id, url, secret, events, active, created_by, created_at) VALUES (?,?,?,?,1,?,?)",
                id, url, secret, String.join(",", events), createdBy, now);
        return new CreatedSubscription(id, url, events, true, Instant.ofEpochMilli(now), secret);
    }

    public List<Subscription> list() {
        return jdbc.query("SELECT id, url, events, active, created_at FROM webhook_subscriptions ORDER BY created_at",
                (rs, i) -> new Subscription(rs.getString(1), rs.getString(2), List.of(rs.getString(3).split(",")),
                        rs.getInt(4) == 1, Instant.ofEpochMilli(rs.getLong(5))));
    }

    public Optional<Subscription> findByUrl(String url) {
        return list().stream().filter(s -> s.url().equals(url)).findFirst();
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM webhook_subscriptions WHERE id = ?", id) > 0;
    }

    /** Writes one outbox row per matching subscription. The dispatcher does the actual delivery. */
    @Transactional
    public String enqueue(String type, Map<String, Object> data) {
        String eventId = "evt_" + randomHex(8);
        long now = clock.millis();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", eventId);
        event.put("type", type);
        event.put("createdAt", Instant.ofEpochMilli(now).toString());
        event.put("data", data);
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        for (Subscription s : list()) {
            if (s.active() && s.events().contains(type)) {
                jdbc.update("""
                        INSERT INTO webhook_outbox (event_id, subscription_id, event_type, payload, next_attempt_at, created_at)
                        VALUES (?,?,?,?,?,?)""", eventId, s.id(), type, payload, now, now);
            }
        }
        return eventId;
    }

    private void validateTarget(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ProblemException.badRequest("validation-error", "Validation failed", "url is not a valid URI.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (uri.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw ProblemException.badRequest("validation-error", "Validation failed", "url must be an absolute http(s) URL.");
        }
        if (props.webhooks().allowPrivateTargets()) return;
        if (!scheme.equals("https")) {
            throw ProblemException.badRequest("validation-error", "Validation failed", "url must use https.");
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
                    throw ProblemException.badRequest("validation-error", "Validation failed", "url resolves to a private address.");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw ProblemException.badRequest("validation-error", "Validation failed", "url host does not resolve.");
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
