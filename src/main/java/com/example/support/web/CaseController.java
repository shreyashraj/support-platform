package com.example.support.web;

import com.example.support.domain.CaseStatus;
import com.example.support.domain.Comment;
import com.example.support.domain.EscalationTarget;
import com.example.support.domain.Priority;
import com.example.support.domain.SupportTier;
import com.example.support.security.Caller;
import com.example.support.service.CaseQuery;
import com.example.support.service.CaseService;
import com.example.support.service.IdempotencyService;
import com.example.support.service.IdempotencyService.Outcome;
import com.example.support.service.Views.CaseList;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CaseController {

    public record CommentRequest(@NotBlank @Size(max = 2000) String body) {}

    public record EscalateRequest(@NotBlank @Size(min = 10, max = 500) String reason, @NotNull EscalationTarget target) {}

    private final CaseService cases;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public CaseController(CaseService cases, IdempotencyService idempotency, ObjectMapper mapper) {
        this.cases = cases;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @GetMapping("/v1/cases")
    CaseList list(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) List<CaseStatus> status,
            @RequestParam(required = false) List<Priority> priority,
            @RequestParam(required = false) SupportTier supportTier,
            @RequestParam(required = false) @Min(1) @Max(365) Integer renewalWithinDays,
            @RequestParam(required = false) @Min(1) @Max(87600) Integer minOpenHours,
            @RequestParam(defaultValue = "-openedAt") String sort,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 512) String cursor) {
        if (accountId != null) Ids.account(accountId);
        Set<CaseStatus> statuses = status == null || status.isEmpty() ? Set.of() : EnumSet.copyOf(status);
        Set<Priority> priorities = priority == null || priority.isEmpty() ? Set.of() : EnumSet.copyOf(priority);
        var query = new CaseQuery(accountId, statuses, priorities, supportTier, renewalWithinDays, minOpenHours,
                CaseQuery.Sort.parse(sort), limit, cursor);
        return cases.list(query, Caller.current());
    }

    @GetMapping("/v1/cases/{id}")
    ResponseEntity<Object> get(@PathVariable String id) {
        var detail = cases.get(Ids.caseId(id), Caller.current());
        return ResponseEntity.ok().eTag(detail.etag()).body(detail.view());
    }

    @PostMapping("/v1/cases/{id}/comments")
    ResponseEntity<String> addComment(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CommentRequest req) {
        Ids.caseId(id);
        Ids.idempotencyKey(key);
        Caller caller = Caller.current();
        String fingerprint = "comment|" + id + "|" + json(req);
        var result = idempotency.execute(caller.subject(), key, fingerprint, () -> {
            Comment c = cases.addComment(id, req.body(), caller, key);
            return new Outcome(201, json(c), null);
        });
        return respond(result);
    }

    @PostMapping("/v1/cases/{id}/escalate")
    ResponseEntity<String> escalate(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody EscalateRequest req) {
        Ids.caseId(id);
        Ids.idempotencyKey(key);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ProblemException(HttpStatus.PRECONDITION_REQUIRED, "precondition-required",
                    "If-Match required", "Send the ETag from GET /v1/cases/" + id + " in If-Match.");
        }
        Caller caller = Caller.current();
        String fingerprint = "escalate|" + id + "|" + json(req);
        var result = idempotency.execute(caller.subject(), key, fingerprint, () -> {
            var detail = cases.escalate(id, ifMatch, req.reason(), req.target(), caller);
            return new Outcome(200, json(detail.view()), detail.etag());
        });
        return respond(result);
    }

    private ResponseEntity<String> respond(IdempotencyService.Result r) {
        var builder = ResponseEntity.status(r.outcome().status()).contentType(MediaType.APPLICATION_JSON);
        if (r.outcome().etag() != null) builder.header(HttpHeaders.ETAG, r.outcome().etag());
        if (r.replayed()) builder.header("Idempotent-Replayed", "true");
        return builder.body(r.outcome().body());
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
