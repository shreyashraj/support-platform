package com.example.support.mcp;

import java.util.List;
import java.util.regex.Pattern;

/** Handling of text that came from customers or other people: never trusted, never allowed to grow unbounded. */
final class Guardrails {
    private Guardrails() {}

    static final String WITHHELD = "[withheld: text looks like instructions aimed at an AI agent]";

    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("ignore\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts?|rules)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+|the\\s+)?(previous|prior|above|system)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(in\\s+)?(an?\\s+)?\\w*\\s*(mode|admin|root|assistant)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(admin|developer|debug|jailbreak)\\s+mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(system|developer)\\s+(prompt|message|instruction)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(escalate|close|delete|reveal|export|forward)\\s+(every|all|each)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("do\\s+not\\s+(tell|inform|mention)\\s+(the\\s+)?user", Pattern.CASE_INSENSITIVE),
            Pattern.compile("</?\\s*(system|assistant|tool|instructions?)\\s*>", Pattern.CASE_INSENSITIVE));

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w])\\+?\\d[\\d\\s().-]{7,}\\d(?![\\w])");
    // control characters, zero-width and bidi override characters, which are used to hide instructions
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");

    static boolean looksLikeInjection(String text) {
        for (Pattern p : INJECTION) if (p.matcher(text).find()) return true;
        return false;
    }

    static String redactPii(String text) {
        return PHONE.matcher(EMAIL.matcher(text).replaceAll("[email]")).replaceAll("[phone]");
    }

    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Result of cleaning one piece of untrusted text. */
    record Clean(String text, boolean flagged) {}

    static Clean untrusted(String raw, int maxChars) {
        if (raw == null) return new Clean("", false);
        String t = INVISIBLE.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
        if (looksLikeInjection(t)) return new Clean(WITHHELD, true);
        return new Clean(truncate(redactPii(t), maxChars), false);
    }

    /** Exact contract value never reaches the model, only a coarse band. */
    static String arrBand(Long arr) {
        if (arr == null) return "restricted";
        if (arr < 50_000) return "<$50k";
        if (arr < 250_000) return "$50k-250k";
        if (arr < 1_000_000) return "$250k-1M";
        return ">$1M";
    }
}
