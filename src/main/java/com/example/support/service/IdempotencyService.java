package com.example.support.service;

import com.example.support.web.ProblemException;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    public record Outcome(int status, String body, String etag) {}

    public record Result(Outcome outcome, boolean replayed) {}

    private record Row(String fingerprint, String status, Integer responseStatus, String body, String etag, long createdAt) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * First caller with a (principal, key) pair runs {@code op}; later callers get the stored response, a 409 while
     * the first is still running, or a 422 if they reuse the key for a different request. Failures aren't stored,
     * so a client that got an error can safely retry with the same key.
     */
    public Result execute(String principal, String key, String fingerprint, Supplier<Outcome> op) {
        for (int attempt = 0; attempt < 3; attempt++) {
            long now = clock.millis();
            int inserted = jdbc.update("""
                    INSERT OR IGNORE INTO idempotency_keys (principal, idem_key, fingerprint, status, created_at)
                    VALUES (?,?,?,'in_progress',?)""", principal, key, fingerprint, now);
            if (inserted == 1) return run(principal, key, op);

            List<Row> rows = jdbc.query("""
                    SELECT fingerprint, status, response_status, response_body, response_etag, created_at
                    FROM idempotency_keys WHERE principal = ? AND idem_key = ?""",
                    (rs, i) -> new Row(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4),
                            rs.getString(5), rs.getLong(6)), principal, key);
            if (rows.isEmpty()) continue;
            Row row = rows.get(0);
            if (row.createdAt() < now - TTL_MS) {
                jdbc.update("DELETE FROM idempotency_keys WHERE principal = ? AND idem_key = ?", principal, key);
                continue;
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw ProblemException.unprocessable("idempotency-key-reuse", "Idempotency key reused",
                        "This Idempotency-Key was already used for a different request.");
            }
            if (row.status().equals("in_progress")) {
                throw ProblemException.conflict("idempotency-in-progress", "Request still in progress",
                        "A request with this Idempotency-Key is still being processed.").with("retryAfterSeconds", 1);
            }
            return new Result(new Outcome(row.responseStatus(), row.body(), row.etag()), true);
        }
        throw ProblemException.conflict("idempotency-in-progress", "Request still in progress",
                "Could not settle this Idempotency-Key, retry shortly.").with("retryAfterSeconds", 1);
    }

    private Result run(String principal, String key, Supplier<Outcome> op) {
        try {
            Outcome o = op.get();
            jdbc.update("""
                    UPDATE idempotency_keys SET status = 'completed', response_status = ?, response_body = ?, response_etag = ?
                    WHERE principal = ? AND idem_key = ?""", o.status(), o.body(), o.etag(), principal, key);
            return new Result(o, false);
        } catch (RuntimeException e) {
            jdbc.update("DELETE FROM idempotency_keys WHERE principal = ? AND idem_key = ?", principal, key);
            throw e;
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purgeExpired() {
        jdbc.update("DELETE FROM idempotency_keys WHERE created_at < ?", clock.millis() - TTL_MS);
    }
}
