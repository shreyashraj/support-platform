package com.example.support.mock;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A tiny SQL warehouse (in-memory SQLite) behind a Snowflake-style statements endpoint. */
@RestController
@MockConditional
public class MockUsageController {
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public MockUsageController(Clock clock) {
        this.clock = clock;
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setSuppressClose(true);
        this.jdbc = new JdbcTemplate(ds);
        reset();
    }

    public synchronized void reset() {
        jdbc.execute("DROP TABLE IF EXISTS usage");
        jdbc.execute("CREATE TABLE usage (account_id TEXT, month TEXT, protected_tb REAL, api_calls INTEGER, active_users INTEGER)");
        for (SeedData.UsageRow r : SeedData.usage(clock)) {
            jdbc.update("INSERT INTO usage VALUES (?,?,?,?,?)", r.accountId(), r.month(), r.protectedTb(), r.apiCalls(), r.activeUsers());
        }
    }

    @PostMapping("/mock/usage/api/v2/statements")
    @SuppressWarnings("unchecked")
    public synchronized ResponseEntity<Object> run(@RequestBody Map<String, Object> req) {
        String sql = String.valueOf(req.get("statement"));
        if (!sql.trim().toLowerCase(Locale.ROOT).startsWith("select")) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("message", "only SELECT is allowed"));
        }
        List<Object> bindings = (List<Object>) req.getOrDefault("bindings", List.of());
        List<List<Object>> rows = jdbc.query(sql, (rs, i) -> {
            List<Object> row = new ArrayList<>();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) row.add(rs.getObject(c));
            return row;
        }, bindings.toArray());
        return ResponseEntity.ok(Map.of("columns", List.of("month", "protected_tb", "api_calls", "active_users"), "rows", rows));
    }
}
