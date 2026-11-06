package com.example.support.domain;

public enum SupportTier {
    Platinum, Gold, Silver, Standard;

    public static SupportTier parse(String raw) {
        if (raw == null) return Standard;
        for (SupportTier t : values()) {
            if (t.name().equalsIgnoreCase(raw.trim())) return t;
        }
        return Standard;
    }
}
