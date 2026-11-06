package com.example.support.domain;

import java.time.Instant;
import java.util.List;

public record SupportCase(
        String id,
        String accountId,
        Priority priority,
        CaseStatus status,
        String subject,
        Instant openedAt,
        Instant escalatedAt,
        int version,
        List<Comment> comments) {

    public String etag() {
        return "\"v" + version + "\"";
    }
}
