package com.example.support.service;

import com.example.support.domain.CaseStatus;
import com.example.support.domain.Comment;
import com.example.support.domain.Priority;
import com.example.support.domain.SupportTier;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response shapes. Field names are the contract in openapi.yaml. */
public final class Views {
    private Views() {}

    public record AccountView(String name, SupportTier supportTier, LocalDate renewalDate) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseView(
            String id,
            String accountId,
            Priority priority,
            CaseStatus status,
            String subject,
            Instant openedAt,
            long openHours,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant escalatedAt,
            int commentCount,
            @JsonInclude(JsonInclude.Include.ALWAYS) AccountView account,
            List<Comment> comments) {}

    public record Page(int limit, String nextCursor) {}

    public record CaseList(List<CaseView> items, Page page, boolean degraded, List<String> degradedSources) {}

    public record UsageView(String latestMonth, double protectedTb, long apiCalls, int activeUsers,
            Double protectedTbChangePct, List<com.example.support.domain.UsageMonth> trend) {}

    public record SupportView(int openCases, int openP1Cases) {}

    public record CustomerView(
            String id,
            String name,
            SupportTier supportTier,
            LocalDate contractEnd,
            LocalDate renewalDate,
            Long daysToRenewal,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long arrUsd,
            UsageView usage,
            SupportView support,
            boolean degraded,
            List<String> degradedSources) {}
}
