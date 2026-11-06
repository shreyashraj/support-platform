package com.example.support.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.support.IntegrationTestBase;
import com.example.support.mock.Chaos;
import com.example.support.service.WebhookDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class SecurityAndWebhookApiTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired Chaos chaos;
    @Autowired ObjectMapper json;

    @Test
    void noTokenIs401Problem() throws Exception {
        mvc.perform(get("/v1/cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(MATCHES_SPEC)
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void garbageTokenIs401() throws Exception {
        mvc.perform(get("/v1/cases").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized()).andExpect(MATCHES_SPEC);
    }

    @Test
    void tokenForAnotherAudienceIsRejected() throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://idp.local/").subject("mallory")
                .audience("some-other-api").expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .claim("scope", "cases:read").build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner("dev-only-secret-change-me-dev-only-secret-change-me".getBytes()));
        mvc.perform(get("/v1/cases").header("Authorization", "Bearer " + jwt.serialize()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scopesGateEachOperation() throws Exception {
        mvc.perform(get("/v1/webhook-subscriptions").header("Authorization", bearer("support-agent")))
                .andExpect(status().isForbidden()).andExpect(MATCHES_SPEC);
        mvc.perform(get("/v1/cases").header("Authorization", bearer("support-readonly"))).andExpect(status().isOk());
    }

    @Test
    void agentTrafficHasItsOwnSmallerBucket() throws Exception {
        String token = bearer("support-agent");
        int limited = 0;
        for (int i = 0; i < 45; i++) {
            var res = mvc.perform(get("/v1/cases/CS-10231").header("Authorization", token).header("X-Agent-Id", "support-copilot"))
                    .andReturn().getResponse();
            if (res.getStatus() == 429) {
                limited++;
                Assertions.assertNotNull(res.getHeader("Retry-After"));
                Assertions.assertEquals("application/problem+json", res.getContentType());
            }
        }
        Assertions.assertEquals(5, limited);
        // the same person working directly is unaffected
        mvc.perform(get("/v1/cases/CS-10231").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().exists("RateLimit-Remaining"))
                .andExpect(MATCHES_SPEC);
    }

    @Test
    void subscriptionLifecycleReturnsSecretOnce() throws Exception {
        String admin = bearer("platform-admin");
        String created = mvc.perform(post("/v1/webhook-subscriptions").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/hook\",\"events\":[\"case.escalated\"]}"))
                .andExpect(status().isCreated()).andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.secret").isString())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();

        mvc.perform(get("/v1/webhook-subscriptions").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].secret").isEmpty());

        mvc.perform(delete("/v1/webhook-subscriptions/" + id).header("Authorization", admin)).andExpect(status().isNoContent());
        mvc.perform(delete("/v1/webhook-subscriptions/" + id).header("Authorization", admin))
                .andExpect(status().isNotFound()).andExpect(MATCHES_SPEC);
    }

    @Test
    void subscriptionValidation() throws Exception {
        String admin = bearer("platform-admin");
        for (String body : new String[] {
                "{\"url\":\"ftp://x\",\"events\":[\"case.escalated\"]}",
                "{\"url\":\"http://localhost/x\",\"events\":[]}",
                "{\"url\":\"http://localhost/x\",\"events\":[\"case.deleted\"]}"}) {
            mvc.perform(post("/v1/webhook-subscriptions").header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(RESPONSE_MATCHES_SPEC);
        }
    }

    @Test
    void failedDeliveryIsKeptForRetryNotLost() throws Exception {
        chaos.set("webhook-receiver", Chaos.Mode.DOWN, 0, 0);
        String etag = etagOf("CS-10237", "support-agent");
        mvc.perform(post("/v1/cases/CS-10237/escalate").header("Authorization", bearer("support-agent"))
                        .header("Idempotency-Key", "key-hook-0001").header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer is down and RPO is breached\",\"target\":\"tier2\"}"))
                .andExpect(status().isOk());

        dispatcher.dispatchDue();

        var row = jdbc.queryForMap("SELECT status, attempts, last_error FROM webhook_outbox");
        Assertions.assertEquals("pending", row.get("status"));
        Assertions.assertEquals(1, ((Number) row.get("attempts")).intValue());
        Assertions.assertNotNull(row.get("last_error"));
        mvc.perform(get("/mock/webhook-receiver/events")).andExpect(jsonPath("$").isEmpty());
    }
}
