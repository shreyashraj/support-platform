package com.example.support.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class ProblemException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Map<String, Object> extras = new LinkedHashMap<>();

    public ProblemException(HttpStatus status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public ProblemException with(String key, Object value) {
        extras.put(key, value);
        return this;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String title() { return title; }
    public Map<String, Object> extras() { return extras; }

    public static ProblemException badRequest(String code, String title, String detail) {
        return new ProblemException(HttpStatus.BAD_REQUEST, code, title, detail);
    }

    public static ProblemException notFound(String what) {
        return new ProblemException(HttpStatus.NOT_FOUND, "not-found", "Not found", what + " does not exist or is not visible to you.");
    }

    public static ProblemException conflict(String code, String title, String detail) {
        return new ProblemException(HttpStatus.CONFLICT, code, title, detail);
    }

    public static ProblemException unprocessable(String code, String title, String detail) {
        return new ProblemException(HttpStatus.UNPROCESSABLE_ENTITY, code, title, detail);
    }
}
