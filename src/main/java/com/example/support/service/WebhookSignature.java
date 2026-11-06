package com.example.support.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignature {
    public static final String HEADER_ID = "X-Webhook-Id";
    public static final String HEADER_TIMESTAMP = "X-Webhook-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Webhook-Signature";

    private WebhookSignature() {}

    public static String sign(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String secret, String timestampHeader, String body, String signatureHeader,
            long toleranceSeconds, long nowSeconds) {
        if (timestampHeader == null || signatureHeader == null) return false;
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowSeconds - ts) > toleranceSeconds) return false;
        byte[] expected = sign(secret, ts, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }
}
