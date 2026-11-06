package com.example.support.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Two-step confirmation for risky writes. The first call returns a preview plus one of these tokens; the action only
 * runs when a second call presents the token with identical arguments. Tokens are bound to the user, the case, the
 * exact reason and target, and the ETag the user was shown, expire after five minutes and work once.
 *
 * This does not by itself prove a human clicked anything (the model could call twice in a row), so it is paired with
 * the tool's destructive annotation, which lets the host application put its own approval prompt in front of the
 * second call, and with a hard cap on escalations per session.
 */
class ConfirmationTokens {
    record Verified(boolean ok, String problem, String etag) {}

    private static final long TTL_MS = 5 * 60 * 1000;

    private final byte[] secret = new byte[32];
    private final LongSupplier clockMs;
    private final Set<String> used = ConcurrentHashMap.newKeySet();

    ConfirmationTokens() {
        this(System::currentTimeMillis);
    }

    ConfirmationTokens(LongSupplier clockMs) {
        new SecureRandom().nextBytes(secret);
        this.clockMs = clockMs;
    }

    String issue(String user, String caseId, String reason, String target, String etag) {
        String nonce = HexFormat.of().formatHex(randomBytes(8));
        String payload = String.join("|", nonce, String.valueOf(clockMs.getAsLong() + TTL_MS), etag, binding(user, caseId, reason, target));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + mac(body);
    }

    Verified verify(String token, String user, String caseId, String reason, String target) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2 || !MessageDigest.isEqual(mac(parts[0]).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
                return new Verified(false, "confirmation_token is not valid", null);
            }
            String[] p = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("\\|", 4);
            if (Long.parseLong(p[1]) < clockMs.getAsLong()) return new Verified(false, "confirmation_token expired, request a new preview", null);
            if (!p[3].equals(binding(user, caseId, reason, target))) {
                return new Verified(false, "arguments differ from what the user was shown, request a new preview", null);
            }
            if (!used.add(p[0])) return new Verified(false, "confirmation_token was already used", null);
            return new Verified(true, null, p[2]);
        } catch (RuntimeException e) {
            return new Verified(false, "confirmation_token is not valid", null);
        }
    }

    private static String binding(String user, String caseId, String reason, String target) {
        return sha(String.join("\u0001", user, caseId, reason, target));
    }

    private String mac(String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
