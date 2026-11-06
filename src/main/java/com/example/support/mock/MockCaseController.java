package com.example.support.mock;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@MockConditional
@RequestMapping("/mock/cases/services/cases")
public class MockCaseController {

    private static final class MockCase {
        String id, accountId, priority, status, subject, escalatedAt;
        Instant openedAt;
        int version = 1;
        final List<Map<String, Object>> comments = new ArrayList<>();
    }

    private final Clock clock;
    private final Map<String, MockCase> store = new ConcurrentHashMap<>();
    private final Map<String, Boolean> seenKeys = new ConcurrentHashMap<>();

    public MockCaseController(Clock clock) {
        this.clock = clock;
        reset();
    }

    public synchronized void reset() {
        store.clear();
        seenKeys.clear();
        for (SeedData.CaseRow r : SeedData.cases()) {
            MockCase c = new MockCase();
            c.id = r.id();
            c.accountId = r.accountId();
            c.priority = r.priority();
            c.status = r.status();
            c.subject = r.subject();
            c.openedAt = SeedData.hoursAgo(clock, r.hoursOpen());
            for (SeedData.CommentRow cr : r.comments()) {
                c.comments.add(comment(cr.author(), cr.body(), c.openedAt.plusSeconds(cr.minutesAfterOpen() * 60L)));
            }
            store.put(c.id, c);
        }
    }

    @GetMapping
    public synchronized Map<String, Object> list(@RequestParam(required = false) String accountId) {
        List<Map<String, Object>> records = store.values().stream()
                .filter(c -> accountId == null || c.accountId.equalsIgnoreCase(accountId))
                .sorted(java.util.Comparator.comparing(c -> c.id))
                .map(MockCaseController::toJson).toList();
        return Map.of("totalSize", records.size(), "records", records);
    }

    @GetMapping("/{id}")
    public synchronized ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        MockCase c = store.get(id);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(toJson(c));
    }

    @PostMapping("/{id}/comments")
    public synchronized ResponseEntity<Map<String, Object>> addComment(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody Map<String, String> body) {
        MockCase c = store.get(id);
        if (c == null) return ResponseEntity.notFound().build();
        if (key == null || seenKeys.putIfAbsent(id + ":" + key, true) == null) {
            c.comments.add(comment(body.get("author"), body.get("body"), clock.instant()));
            c.version++;
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toJson(c));
    }

    @PatchMapping("/{id}")
    @SuppressWarnings("unchecked")
    public synchronized ResponseEntity<Map<String, Object>> patch(@PathVariable String id, @RequestBody Map<String, Object> body) {
        MockCase c = store.get(id);
        if (c == null) return ResponseEntity.notFound().build();
        if (!Integer.valueOf(c.version).equals(body.get("Version"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "STALE_VERSION"));
        }
        if (body.get("Status") != null) c.status = (String) body.get("Status");
        if (body.get("priority") != null) c.priority = (String) body.get("priority");
        if (body.get("EscalatedAt") != null) c.escalatedAt = (String) body.get("EscalatedAt");
        if (body.get("Comment") instanceof Map<?, ?> cm) {
            Map<String, String> m = (Map<String, String>) cm;
            c.comments.add(comment(m.get("author"), m.get("body"), clock.instant()));
        }
        c.version++;
        return ResponseEntity.ok(toJson(c));
    }

    private static Map<String, Object> comment(String author, String body, Instant at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("author", author);
        m.put("body", body);
        m.put("at", at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        return m;
    }

    /** Capitalised keys on purpose: the case system is inconsistent, the platform is not. */
    private static Map<String, Object> toJson(MockCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("accountId", c.accountId);
        m.put("priority", c.priority);
        m.put("Status", c.status);
        m.put("openedAt", c.openedAt.toString());
        m.put("subject", c.subject);
        m.put("Comments", new ArrayList<>(c.comments));
        m.put("Version", c.version);
        if (c.escalatedAt != null) m.put("EscalatedAt", c.escalatedAt);
        return m;
    }
}
