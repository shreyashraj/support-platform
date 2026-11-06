package com.example.support.upstream;

/** The upstream answered, and the answer was a 4xx. Not a health problem, so it never trips a breaker. */
public class UpstreamClientException extends RuntimeException {
    private final int status;
    private final String body;

    public UpstreamClientException(int status, String body) {
        super("upstream answered " + status);
        this.status = status;
        this.body = body;
    }

    public int status() { return status; }
    public String body() { return body; }
}
