package com.example.support.service;

import com.example.support.domain.Account;
import com.example.support.domain.CaseStatus;
import com.example.support.domain.Priority;
import com.example.support.domain.SupportCase;
import com.example.support.domain.UsageMonth;
import com.example.support.security.Caller;
import com.example.support.service.Views.CustomerView;
import com.example.support.service.Views.SupportView;
import com.example.support.service.Views.UsageView;
import com.example.support.upstream.CaseSystemClient;
import com.example.support.upstream.EntitlementClient;
import com.example.support.upstream.UsageClient;
import com.example.support.upstream.UpstreamUnavailableException;
import com.example.support.web.ProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {
    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);
    private static final Duration OPTIONAL_BUDGET = Duration.ofMillis(2500);

    private final EntitlementClient entitlements;
    private final UsageClient usage;
    private final CaseSystemClient cases;
    private final ExecutorService executor;
    private final Clock clock;
    private final AuditService audit;

    public CustomerService(EntitlementClient entitlements, UsageClient usage, CaseSystemClient cases,
            ExecutorService fanOutExecutor, Clock clock, AuditService audit) {
        this.entitlements = entitlements;
        this.usage = usage;
        this.cases = cases;
        this.executor = fanOutExecutor;
        this.clock = clock;
        this.audit = audit;
    }

    public CustomerView get(String accountId, Caller caller) {
        if (!caller.scope().canSee(accountId)) throw ProblemException.notFound("Customer " + accountId);

        var accountF = async(() -> entitlements.find(accountId));
        var usageF = async(() -> usage.lastMonths(accountId, 3));
        var casesF = async(() -> cases.list(accountId));

        Account account = await(accountF).orElseThrow(() -> ProblemException.notFound("Customer " + accountId));
        long deadline = System.nanoTime() + OPTIONAL_BUDGET.toNanos();

        List<String> degraded = new ArrayList<>();
        UsageView usageView = null;
        try {
            usageView = toUsageView(awaitOptional(usageF, "usage", deadline));
        } catch (UpstreamUnavailableException e) {
            log.warn("usage unavailable for {}: {}", accountId, e.getMessage());
            degraded.add("usage");
        }
        SupportView supportView = null;
        try {
            supportView = toSupportView(awaitOptional(casesF, "cases", deadline));
        } catch (UpstreamUnavailableException e) {
            log.warn("case system unavailable for {}: {}", accountId, e.getMessage());
            degraded.add("support");
        }

        Long days = account.renewalDate() == null ? null
                : ChronoUnit.DAYS.between(LocalDate.now(clock), account.renewalDate());
        Long arr = null;
        if (caller.finance()) {
            arr = account.arrUsd();
            audit.record(caller, "customer.arr.read", "customer", accountId, "success", Map.of());
        }
        return new CustomerView(account.id(), account.name(), account.tier(), account.contractEnd(),
                account.renewalDate(), days, arr, usageView, supportView, !degraded.isEmpty(), degraded);
    }

    private static UsageView toUsageView(List<UsageMonth> months) {
        if (months.isEmpty()) return null;
        UsageMonth last = months.get(months.size() - 1);
        Double change = null;
        if (months.size() > 1 && months.get(months.size() - 2).protectedTb() > 0) {
            double prev = months.get(months.size() - 2).protectedTb();
            change = Math.round((last.protectedTb() - prev) / prev * 1000.0) / 10.0;
        }
        return new UsageView(last.month(), last.protectedTb(), last.apiCalls(), last.activeUsers(), change, months);
    }

    private static SupportView toSupportView(List<SupportCase> all) {
        int open = 0;
        int p1 = 0;
        for (SupportCase c : all) {
            if (c.status() == CaseStatus.open || c.status() == CaseStatus.pending || c.status() == CaseStatus.escalated) {
                open++;
                if (c.priority() == Priority.P1) p1++;
            }
        }
        return new SupportView(open, p1);
    }

    private <T> CompletableFuture<T> async(Supplier<T> op) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            if (mdc != null) MDC.setContextMap(mdc);
            try {
                return op.get();
            } finally {
                MDC.clear();
            }
        }, executor);
    }

    /** Enrichment gets a shared time budget; running past it counts as that source being down. */
    private static <T> T awaitOptional(CompletableFuture<T> f, String upstream, long deadlineNanos) {
        try {
            return f.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new UpstreamUnavailableException(upstream, UpstreamUnavailableException.Kind.TIMEOUT, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static <T> T await(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }
}
