package com.example.support.upstream;

public class UpstreamUnavailableException extends RuntimeException {
    public enum Kind { TIMEOUT, CIRCUIT_OPEN, ERROR }

    private final String upstream;
    private final Kind kind;

    public UpstreamUnavailableException(String upstream, Kind kind, Throwable cause) {
        super(upstream + " unavailable (" + kind + ")", cause);
        this.upstream = upstream;
        this.kind = kind;
    }

    public String upstream() { return upstream; }
    public Kind kind() { return kind; }
}
