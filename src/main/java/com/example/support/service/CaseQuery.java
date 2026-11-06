package com.example.support.service;

import com.example.support.domain.CaseStatus;
import com.example.support.domain.Priority;
import com.example.support.domain.SupportTier;
import com.example.support.web.ProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

public record CaseQuery(
        String accountId,
        Set<CaseStatus> statuses,
        Set<Priority> priorities,
        SupportTier tier,
        Integer renewalWithinDays,
        Integer minOpenHours,
        Sort sort,
        int limit,
        String cursor) {

    public enum Sort {
        OPENED_ASC("openedAt"), OPENED_DESC("-openedAt"), PRIORITY_ASC("priority"), PRIORITY_DESC("-priority");

        private final String wire;

        Sort(String wire) { this.wire = wire; }

        public String wire() { return wire; }

        public static Sort parse(String s) {
            for (Sort v : values()) if (v.wire.equals(s)) return v;
            throw ProblemException.badRequest("validation-error", "Validation failed",
                    "sort must be one of openedAt, -openedAt, priority, -priority.");
        }
    }

    public boolean needsEntitlement() {
        return tier != null || renewalWithinDays != null;
    }

    /** Everything except limit and cursor, so a cursor can't be replayed against a different query. */
    public String fingerprint() {
        String canon = String.join("|", String.valueOf(accountId), new TreeSet<>(statuses).toString(),
                new TreeSet<>(priorities).toString(), String.valueOf(tier), String.valueOf(renewalWithinDays),
                String.valueOf(minOpenHours), sort.wire());
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canon.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
