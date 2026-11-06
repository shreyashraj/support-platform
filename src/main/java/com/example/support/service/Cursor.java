package com.example.support.service;

import com.example.support.web.ProblemException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque keyset cursor: the last item's sort key and id, plus a fingerprint of the query it belongs to. */
public record Cursor(long key, String id, String fingerprint) {

    public String encode() {
        String raw = key + "|" + id + "|" + fingerprint;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String token, String expectedFingerprint) {
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", 3);
            Cursor c = new Cursor(Long.parseLong(p[0]), p[1], p[2]);
            if (!c.fingerprint.equals(expectedFingerprint)) {
                throw ProblemException.badRequest("invalid-cursor", "Invalid cursor",
                        "This cursor was issued for different filters or sort order.");
            }
            return c;
        } catch (ProblemException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ProblemException.badRequest("invalid-cursor", "Invalid cursor", "The cursor is malformed.");
        }
    }
}
