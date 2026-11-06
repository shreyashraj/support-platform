package com.example.support.service;

import com.example.support.domain.Account;
import com.example.support.domain.CaseStatus;
import com.example.support.domain.Comment;
import com.example.support.domain.EscalationRules;
import com.example.support.domain.EscalationTarget;
import com.example.support.domain.Priority;
import com.example.support.domain.SupportCase;
import com.example.support.security.Caller;
import com.example.support.service.Views.AccountView;
import com.example.support.service.Views.CaseList;
import com.example.support.service.Views.CaseView;
import com.example.support.upstream.CaseSystemClient;
import com.example.support.upstream.EntitlementClient;
import com.example.support.upstream.UpstreamClientException;
import com.example.support.upstream.UpstreamUnavailableException;
import com.example.support.web.ProblemException;
import com.example.support.web.Problems;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CaseService {

    public record Detail(CaseView view, String etag) {}

    private final CaseSystemClient cases;
    private final EntitlementClient entitlements;
    private final AuditService audit;
    private final WebhookService webhooks;
    private final Clock clock;

    public CaseService(CaseSystemClient cases, EntitlementClient entitlements, AuditService audit,
            WebhookService webhooks, Clock clock) {
        this.cases = cases;
        this.entitlements = entitlements;
        this.audit = audit;
        this.webhooks = webhooks;
        this.clock = clock;
    }

    public CaseList list(CaseQuery q, Caller caller) {
        // Filters are applied here rather than pushed to the case system: its query language differs from ours
        // and the cross-system filters (tier, renewal) can't be pushed anyway. See DECISIONS.md.
        List<SupportCase> all = cases.list(q.accountId());

        Map<String, Account> accounts = null;
        boolean degraded = false;
        try {
            accounts = entitlements.all();
        } catch (UpstreamUnavailableException e) {
            if (q.needsEntitlement()) throw e;
            degraded = true;
        }

        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        Map<String, Account> acc = accounts;
        List<SupportCase> matched = new ArrayList<>();
        for (SupportCase c : all) {
            if (!caller.scope().canSee(c.accountId())) continue;
            if (!q.statuses().isEmpty() && !q.statuses().contains(c.status())) continue;
            if (!q.priorities().isEmpty() && !q.priorities().contains(c.priority())) continue;
            if (q.minOpenHours() != null && hoursOpen(c, now) < q.minOpenHours()) continue;
            if (q.needsEntitlement()) {
                Account a = acc.get(c.accountId());
                if (a == null) continue;
                if (q.tier() != null && a.tier() != q.tier()) continue;
                if (q.renewalWithinDays() != null) {
                    if (a.renewalDate() == null) continue;
                    long d = ChronoUnit.DAYS.between(today, a.renewalDate());
                    if (d < 0 || d > q.renewalWithinDays()) continue;
                }
            }
            matched.add(c);
        }

        Comparator<SupportCase> order = comparator(q.sort());
        matched.sort(order);

        if (q.cursor() != null) {
            Cursor cur = Cursor.decode(q.cursor(), q.fingerprint());
            matched.removeIf(c -> order.compare(c, stub(cur, q.sort())) <= 0);
        }

        boolean more = matched.size() > q.limit();
        List<SupportCase> pageItems = more ? matched.subList(0, q.limit()) : matched;
        String next = null;
        if (more) {
            SupportCase last = pageItems.get(pageItems.size() - 1);
            next = new Cursor(sortKey(last, q.sort()), last.id(), q.fingerprint()).encode();
        }
        List<CaseView> views = pageItems.stream().map(c -> view(c, acc, now, false)).toList();
        return new CaseList(views, new Views.Page(q.limit(), next), degraded, degraded ? List.of("entitlement") : List.of());
    }

    public Detail get(String id, Caller caller) {
        SupportCase c = load(id, caller);
        Map<String, Account> acc = tryAccounts();
        return new Detail(view(c, acc, clock.instant(), true), c.etag());
    }

    public Comment addComment(String id, String body, Caller caller, String idempotencyKey) {
        SupportCase c = load(id, caller);
        String upstreamKey = sha(caller.subject() + "|" + idempotencyKey).substring(0, 32);
        SupportCase updated = cases.addComment(c.id(), caller.subject(), body, upstreamKey);
        Comment created = updated.comments().get(updated.comments().size() - 1);
        audit.record(caller, "case.comment.add", "case", id, "success", Map.of("commentId", created.id()));
        return created;
    }

    public Detail escalate(String id, String ifMatch, String reason, EscalationTarget target, Caller caller) {
        SupportCase c = load(id, caller);
        try {
            if (!etagMatches(ifMatch, c.etag())) {
                throw new ProblemException(org.springframework.http.HttpStatus.PRECONDITION_FAILED, "precondition-failed",
                        "Case changed since you read it",
                        "The case is now at " + c.etag() + ". Re-read it and decide again.");
            }
            EscalationRules.check(c, target);
        } catch (ProblemException e) {
            audit.record(caller, "case.escalate", "case", id, "rejected", Map.of("code", e.code()));
            throw e;
        }

        Priority raised = c.priority().raise();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String auditComment = "Escalated to " + target + " by " + caller.subject() + ": " + reason;
        SupportCase updated;
        try {
            updated = cases.escalate(c.id(), c.version(), raised, now, caller.subject(), auditComment);
        } catch (UpstreamClientException e) {
            if (e.status() == 409 || e.status() == 412) {
                audit.record(caller, "case.escalate", "case", id, "rejected", Map.of("code", "precondition-failed"));
                throw new ProblemException(org.springframework.http.HttpStatus.PRECONDITION_FAILED, "precondition-failed",
                        "Case changed since you read it", "Someone updated the case while you were escalating it. Re-read it and decide again.");
            }
            throw e;
        }

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("caseId", c.id());
        eventData.put("accountId", c.accountId());
        eventData.put("previousPriority", c.priority());
        eventData.put("priority", updated.priority());
        eventData.put("target", target);
        eventData.put("reason", reason);
        eventData.put("escalatedBy", caller.subject());
        eventData.put("correlationId", Problems.correlationId());
        webhooks.enqueue("case.escalated", eventData);

        Map<String, Object> detail = new HashMap<>();
        detail.put("target", target);
        detail.put("from", c.priority());
        detail.put("to", updated.priority());
        detail.put("reason", reason);
        audit.record(caller, "case.escalate", "case", id, "success", detail);
        return new Detail(view(updated, tryAccounts(), clock.instant(), true), updated.etag());
    }

    private SupportCase load(String id, Caller caller) {
        SupportCase c = cases.get(id).orElseThrow(() -> ProblemException.notFound("Case " + id));
        if (!caller.scope().canSee(c.accountId())) throw ProblemException.notFound("Case " + id);
        return c;
    }

    private Map<String, Account> tryAccounts() {
        try {
            return entitlements.all();
        } catch (UpstreamUnavailableException e) {
            return null;
        }
    }

    private CaseView view(SupportCase c, Map<String, Account> accounts, Instant now, boolean withComments) {
        Account a = accounts == null ? null : accounts.get(c.accountId());
        AccountView av = a == null ? null : new AccountView(a.name(), a.tier(), a.renewalDate());
        return new CaseView(c.id(), c.accountId(), c.priority(), c.status(), c.subject(), c.openedAt(),
                hoursOpen(c, now), c.escalatedAt(), c.comments().size(), av, withComments ? c.comments() : null);
    }

    private static long hoursOpen(SupportCase c, Instant now) {
        return Duration.between(c.openedAt(), now).toHours();
    }

    private static Comparator<SupportCase> comparator(CaseQuery.Sort sort) {
        Comparator<SupportCase> byKey = Comparator.comparingLong(c -> sortKey(c, sort));
        boolean desc = sort == CaseQuery.Sort.OPENED_DESC || sort == CaseQuery.Sort.PRIORITY_DESC;
        return (desc ? byKey.reversed() : byKey).thenComparing(SupportCase::id);
    }

    private static long sortKey(SupportCase c, CaseQuery.Sort sort) {
        return switch (sort) {
            case OPENED_ASC, OPENED_DESC -> c.openedAt().toEpochMilli();
            case PRIORITY_ASC, PRIORITY_DESC -> c.priority().ordinal();
        };
    }

    private static SupportCase stub(Cursor cur, CaseQuery.Sort sort) {
        boolean byTime = sort == CaseQuery.Sort.OPENED_ASC || sort == CaseQuery.Sort.OPENED_DESC;
        Instant opened = byTime ? Instant.ofEpochMilli(cur.key()) : Instant.EPOCH;
        Priority p = byTime ? Priority.P1 : Priority.values()[(int) cur.key()];
        return new SupportCase(cur.id(), null, p, CaseStatus.open, null, opened, null, 0, List.of());
    }

    static boolean etagMatches(String ifMatch, String current) {
        if (ifMatch == null) return false;
        return Arrays.stream(ifMatch.split(","))
                .map(String::trim)
                .anyMatch(t -> t.equals("*") || t.equals(current));
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
