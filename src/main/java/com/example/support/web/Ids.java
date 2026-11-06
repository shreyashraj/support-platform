package com.example.support.web;

import java.util.regex.Pattern;

final class Ids {
    private static final Pattern ACCOUNT = Pattern.compile("^ACC-[0-9]{3,6}$");
    private static final Pattern CASE = Pattern.compile("^CS-[0-9]{3,9}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private Ids() {}

    static String account(String id) {
        if (!ACCOUNT.matcher(id).matches()) throw bad("id", "must look like ACC-007");
        return id;
    }

    static String caseId(String id) {
        if (!CASE.matcher(id).matches()) throw bad("id", "must look like CS-10231");
        return id;
    }

    static String idempotencyKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw bad("Idempotency-Key", "required header, 8-64 characters of A-Z a-z 0-9 _ -");
        }
        return key;
    }

    private static ProblemException bad(String field, String message) {
        return ProblemException.badRequest("validation-error", "Validation failed", field + " " + message + ".")
                .with("errors", java.util.List.of(java.util.Map.of("field", field, "message", message)));
    }
}
