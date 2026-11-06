package com.example.support.service;

import com.example.support.security.Caller;
import com.example.support.web.Problems;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void record(Caller caller, String action, String resourceType, String resourceId, String outcome,
            Map<String, Object> detail) {
        String json;
        try {
            json = mapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.update("""
                INSERT INTO audit_log (ts, correlation_id, actor, agent, action, resource_type, resource_id, outcome, detail)
                VALUES (?,?,?,?,?,?,?,?,?)""",
                clock.millis(), Problems.correlationId(), caller.subject(), caller.agent(), action, resourceType,
                resourceId, outcome, json);
    }
}
