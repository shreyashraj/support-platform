package com.example.support.upstream;

import com.example.support.domain.AccountIds;
import com.example.support.domain.CaseStatus;
import com.example.support.domain.Comment;
import com.example.support.domain.Priority;
import com.example.support.domain.SupportCase;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.client.RestClient;

/** Salesforce-shaped REST API: capitalised keys, a Version on every record, list wrapped in "records". */
public class CaseSystemClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawComment(String author, String body, Instant at) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawCase(
            String id,
            String accountId,
            String priority,
            @JsonProperty("Status") String status,
            Instant openedAt,
            String subject,
            @JsonProperty("Comments") List<RawComment> comments,
            @JsonProperty("Version") int version,
            @JsonProperty("EscalatedAt") Instant escalatedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawList(List<RawCase> records) {}

    private final RestClient http;
    private final Guard guard;

    public CaseSystemClient(RestClient http, Guard guard) {
        this.http = http;
        this.guard = guard;
    }

    public List<SupportCase> list(String accountId) {
        RawList raw = guard.call(true, () -> http.get()
                .uri(b -> {
                    b.path("/services/cases");
                    if (accountId != null) b.queryParam("accountId", accountId);
                    return b.build();
                })
                .retrieve().body(RawList.class));
        return raw == null || raw.records() == null ? List.of() : raw.records().stream().map(CaseSystemClient::map).toList();
    }

    public Optional<SupportCase> get(String id) {
        try {
            RawCase raw = guard.call(true, () -> http.get().uri("/services/cases/{id}", id).retrieve().body(RawCase.class));
            return Optional.ofNullable(raw).map(CaseSystemClient::map);
        } catch (UpstreamClientException e) {
            if (e.status() == 404) return Optional.empty();
            throw e;
        }
    }

    /** Safe to retry because the upstream de-duplicates on the idempotency key. */
    public SupportCase addComment(String id, String author, String body, String idempotencyKey) {
        RawCase raw = guard.call(true, () -> http.post()
                .uri("/services/cases/{id}/comments", id)
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("author", author, "body", body))
                .retrieve().body(RawCase.class));
        return map(raw);
    }

    /** Conditional on Version, so a stale caller gets a 409 (surfaced as UpstreamClientException). No retry: a lost response would turn into a false conflict. */
    public SupportCase escalate(String id, int expectedVersion, Priority newPriority, Instant at, String author, String comment) {
        Map<String, Object> body = new HashMap<>();
        body.put("Version", expectedVersion);
        body.put("Status", "escalated");
        body.put("priority", newPriority.name());
        body.put("EscalatedAt", at.toString());
        body.put("Comment", Map.of("author", author, "body", comment));
        RawCase raw = guard.call(false, () -> http.patch().uri("/services/cases/{id}", id).body(body)
                .retrieve().body(RawCase.class));
        return map(raw);
    }

    static SupportCase map(RawCase raw) {
        String id = raw.id();
        List<Comment> comments = new ArrayList<>();
        List<RawComment> rc = raw.comments() == null ? List.of() : raw.comments();
        for (int i = 0; i < rc.size(); i++) {
            comments.add(new Comment(id + "-C" + (i + 1), id, rc.get(i).author(), rc.get(i).body(), rc.get(i).at()));
        }
        return new SupportCase(id, AccountIds.normalize(raw.accountId()), Priority.parse(raw.priority()),
                CaseStatus.parse(raw.status()), raw.subject(), raw.openedAt(), raw.escalatedAt(), raw.version(),
                List.copyOf(comments));
    }
}
