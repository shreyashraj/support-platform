package com.example.support.upstream;

import com.example.support.domain.UsageMonth;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/** Snowflake SQL API style: POST a parameterised statement, get columns and row arrays back. */
public class UsageClient {
    private static final String SQL = """
            SELECT month, protected_tb, api_calls, active_users
            FROM usage WHERE account_id = ? ORDER BY month DESC LIMIT ?""";

    private final RestClient http;
    private final Guard guard;

    public UsageClient(RestClient http, Guard guard) {
        this.http = http;
        this.guard = guard;
    }

    /** Oldest first. */
    public List<UsageMonth> lastMonths(String accountId, int months) {
        JsonNode res = guard.call(true, () -> http.post().uri("/api/v2/statements")
                .body(Map.of("statement", SQL, "bindings", List.of(accountId, months)))
                .retrieve().body(JsonNode.class));
        List<UsageMonth> out = new ArrayList<>();
        if (res != null && res.has("rows")) {
            for (JsonNode row : res.get("rows")) {
                out.add(new UsageMonth(row.get(0).asText(), row.get(1).asDouble(), row.get(2).asLong(), row.get(3).asInt()));
            }
        }
        Collections.reverse(out);
        return out;
    }
}
