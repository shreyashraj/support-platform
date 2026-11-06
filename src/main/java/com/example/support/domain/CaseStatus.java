package com.example.support.domain;

@SuppressWarnings("java:S115")
public enum CaseStatus {
    open, pending, escalated, resolved, closed;

    public boolean isActive() {
        return this == open || this == pending || this == escalated;
    }

    public static CaseStatus parse(String raw) {
        return valueOf(raw.trim().toLowerCase());
    }
}
