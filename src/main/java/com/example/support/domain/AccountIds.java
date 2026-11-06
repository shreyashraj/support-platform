package com.example.support.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AccountIds {
    private static final Pattern ID = Pattern.compile("^ACC-?0*(\\d+)$", Pattern.CASE_INSENSITIVE);

    private AccountIds() {}

    /** Upstreams disagree on padding ("ACC-77" vs "ACC-077"); everything inside the platform uses three digits or more. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        Matcher m = ID.matcher(raw.trim());
        if (!m.matches()) return raw.trim().toUpperCase();
        return String.format("ACC-%03d", Long.parseLong(m.group(1)));
    }
}
