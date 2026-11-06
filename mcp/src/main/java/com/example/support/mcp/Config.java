package com.example.support.mcp;

record Config(
        String apiUrl,
        String token,
        String auditFile,
        int readsPerMinute,
        int writesPerMinute,
        int maxEscalationsPerSession,
        int maxOutputChars) {

    static Config fromEnv() {
        return new Config(
                env("SUPPORT_API_URL", "http://localhost:8080"),
                env("SUPPORT_API_TOKEN", ""),
                env("MCP_AUDIT_FILE", "audit/tool-calls.jsonl"),
                Integer.parseInt(env("MCP_READS_PER_MIN", "30")),
                Integer.parseInt(env("MCP_WRITES_PER_MIN", "6")),
                Integer.parseInt(env("MCP_MAX_ESCALATIONS", "3")),
                Integer.parseInt(env("MCP_MAX_OUTPUT_CHARS", "6000")));
    }

    private static String env(String key, String dflt) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? dflt : v;
    }
}
