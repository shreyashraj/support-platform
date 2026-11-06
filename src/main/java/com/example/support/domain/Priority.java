package com.example.support.domain;

public enum Priority {
    P1, P2, P3, P4;

    public Priority raise() {
        return this == P1 ? P1 : values()[ordinal() - 1];
    }

    public static Priority parse(String raw) {
        return valueOf(raw.trim().toUpperCase());
    }
}
