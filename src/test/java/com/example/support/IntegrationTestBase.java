package com.example.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.example.support.mock.MockAdminController;
import com.example.support.mock.TokenIssuer;
import com.example.support.security.RateLimiter;
import com.example.support.upstream.Guard;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Full application with its embedded mock upstreams on a real port (the upstream clients make real HTTP calls),
 * driven through MockMvc and validated against openapi.yaml.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {
    private static final int PORT = freePort();

    /** Request and response must both conform. */
    protected static final ResultMatcher MATCHES_SPEC = new OpenApiMatchers().isValid("openapi.yaml");

    /** For deliberately invalid requests: only the response is checked against the spec. */
    protected static final ResultMatcher RESPONSE_MATCHES_SPEC = new OpenApiMatchers().isValid(
            OpenApiInteractionValidator.createFor("openapi.yaml")
                    .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
                    .build());

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("server.port", () -> PORT);
        r.add("platform.webhooks.dispatcher-enabled", () -> "false");
        r.add("platform.db-path", () -> "target/test-db/" + UUID.randomUUID() + ".db");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected TokenIssuer tokens;
    @Autowired MockAdminController admin;
    @Autowired RateLimiter limiter;
    @Autowired List<Guard> guards;
    @Autowired org.springframework.jdbc.core.JdbcTemplate testJdbc;

    @BeforeEach
    void resetWorld() {
        admin.reset();
        testJdbc.update("DELETE FROM webhook_outbox");
        testJdbc.update("DELETE FROM audit_log");
        testJdbc.update("DELETE FROM idempotency_keys");
        limiter.reset();
        guards.forEach(g -> g.breaker().reset());
    }

    protected String bearer(String clientId) {
        return "Bearer " + tokens.issue(clientId, Duration.ofMinutes(5));
    }

    protected String etagOf(String caseId, String clientId) throws Exception {
        return mvc.perform(get("/v1/cases/" + caseId).header("Authorization", bearer(clientId)))
                .andReturn().getResponse().getHeader("ETag");
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
