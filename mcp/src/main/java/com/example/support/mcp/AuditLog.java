package com.example.support.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One JSON line per tool call, including calls that were blocked. The same correlation id is sent to the API. */
class AuditLog {
    private final Path file;
    private final String sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);

    AuditLog(String path) {
        this.file = Path.of(path);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (IOException e) {
            System.err.println("audit log directory not writable: " + e.getMessage());
        }
    }

    synchronized void record(String user, String tool, Map<String, Object> args, String outcome, String detail,
            String correlationId, long millis) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.now().toString());
        row.put("session", sessionId);
        row.put("user", user);
        row.put("tool", tool);
        row.put("args", args);
        row.put("outcome", outcome);
        if (detail != null) row.put("detail", detail);
        row.put("correlationId", correlationId);
        row.put("ms", millis);
        try {
            Files.writeString(file, Json.write(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("could not write audit line: " + e.getMessage());
        }
    }
}
