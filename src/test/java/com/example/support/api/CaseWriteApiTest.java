package com.example.support.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.support.IntegrationTestBase;
import com.example.support.service.WebhookDispatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class CaseWriteApiTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookDispatcher dispatcher;

    private MockHttpServletRequestBuilder comment(String caseId, String key, String body, String client) {
        return post("/v1/cases/" + caseId + "/comments").header("Authorization", bearer(client))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + body + "\"}");
    }

    private MockHttpServletRequestBuilder escalate(String caseId, String key, String ifMatch, String target, String client) {
        var b = post("/v1/cases/" + caseId + "/escalate").header("Authorization", bearer(client))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Customer is down and RPO is breached\",\"target\":\"" + target + "\"}");
        return ifMatch == null ? b : b.header("If-Match", ifMatch);
    }

    // ---- comments

    @Test
    void commentIsCreatedWithAuthorFromTheToken() throws Exception {
        mvc.perform(comment("CS-10231", "key-comment-1", "Customer confirmed node 3 is back", "support-agent"))
                .andExpect(status().isCreated())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.author").value("alice.support"))
                .andExpect(jsonPath("$.id").value("CS-10231-C2"));
    }

    @Test
    void replayingAnIdempotencyKeyCreatesNothingNew() throws Exception {
        mvc.perform(comment("CS-10231", "key-comment-2", "once only", "support-agent")).andExpect(status().isCreated());
        mvc.perform(comment("CS-10231", "key-comment-2", "once only", "support-agent"))
                .andExpect(status().isCreated())
                .andExpect(MATCHES_SPEC)
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value("CS-10231-C2"));
        mvc.perform(get("/v1/cases/CS-10231").header("Authorization", bearer("support-agent")))
                .andExpect(jsonPath("$.commentCount").value(2));
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        mvc.perform(comment("CS-10231", "key-comment-3", "first", "support-agent")).andExpect(status().isCreated());
        mvc.perform(comment("CS-10231", "key-comment-3", "second", "support-agent"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("idempotency-key-reuse"));
    }

    @Test
    void idempotencyKeysAreScopedPerCaller() throws Exception {
        mvc.perform(comment("CS-10231", "shared-key-1", "from alice", "support-agent")).andExpect(status().isCreated());
        mvc.perform(comment("CS-10231", "shared-key-1", "from alice", "csm-limited")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.author").value("carl.csm"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/v1/cases/CS-10231/comments").header("Authorization", bearer("support-agent"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(RESPONSE_MATCHES_SPEC)
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void emptyOrUnknownFieldsInBodyAre400() throws Exception {
        mvc.perform(comment("CS-10231", "key-comment-4", "", "support-agent"))
                .andExpect(status().isBadRequest())
                .andExpect(RESPONSE_MATCHES_SPEC)
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        mvc.perform(post("/v1/cases/CS-10231/comments").header("Authorization", bearer("support-agent"))
                        .header("Idempotency-Key", "key-comment-5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"ok\",\"author\":\"someone-else\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void commentOnUnknownCaseIs404() throws Exception {
        mvc.perform(comment("CS-99999", "key-comment-6", "hi", "support-agent"))
                .andExpect(status().isNotFound()).andExpect(MATCHES_SPEC);
    }

    @Test
    void readOnlyTokensCannotComment() throws Exception {
        mvc.perform(comment("CS-10231", "key-comment-7", "hi", "support-readonly"))
                .andExpect(status().isForbidden()).andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void commentsOutsideTheCallersBookLookLikeMissingCases() throws Exception {
        mvc.perform(comment("CS-10233", "key-comment-8", "hi", "csm-limited")).andExpect(status().isNotFound());
    }

    // ---- escalation

    @Test
    void escalationNeedsIfMatch() throws Exception {
        mvc.perform(escalate("CS-10231", "key-esc-0001", null, "tier2", "support-agent"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("precondition-required"));
    }

    @Test
    void staleEtagIs412() throws Exception {
        mvc.perform(escalate("CS-10231", "key-esc-0002", "\"v7\"", "tier2", "support-agent"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("precondition-failed"));
    }

    @Test
    void escalationChangesStateRaisesPriorityAndBumpsEtag() throws Exception {
        String etag = etagOf("CS-10237", "support-agent");
        mvc.perform(escalate("CS-10237", "key-esc-0003", etag, "management", "support-agent"))
                .andExpect(status().isOk())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.status").value("escalated"))
                .andExpect(jsonPath("$.priority").value("P1"))
                .andExpect(header().string("ETag", "\"v2\""))
                .andExpect(jsonPath("$.escalatedAt").exists());
        mvc.perform(get("/v1/cases/CS-10237").header("Authorization", bearer("support-agent")))
                .andExpect(jsonPath("$.comments[-1:].body").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("Escalated to management by alice.support"))));
    }

    @Test
    void escalatingTwiceWithTheSameKeyReplays() throws Exception {
        String etag = etagOf("CS-10237", "support-agent");
        mvc.perform(escalate("CS-10237", "key-esc-0004", etag, "tier2", "support-agent")).andExpect(status().isOk());
        mvc.perform(escalate("CS-10237", "key-esc-0004", etag, "tier2", "support-agent"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("ETag", "\"v2\""));
    }

    @Test
    void escalatingAnAlreadyEscalatedCaseIs409() throws Exception {
        String etag = etagOf("CS-10239", "support-agent");
        mvc.perform(escalate("CS-10239", "key-esc-0005", etag, "tier2", "support-agent"))
                .andExpect(status().isConflict()).andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("case-already-escalated"));
    }

    @Test
    void closedCasesCannotBeEscalated() throws Exception {
        String etag = etagOf("CS-10272", "support-agent");
        mvc.perform(escalate("CS-10272", "key-esc-0006", etag, "tier2", "support-agent"))
                .andExpect(status().isConflict()).andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("case-not-escalatable"));
    }

    @Test
    void p4CasesCannotBeEscalated() throws Exception {
        String etag = etagOf("CS-10270", "support-agent");
        mvc.perform(escalate("CS-10270", "key-esc-0007", etag, "tier2", "support-agent"))
                .andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("business-rule-violation"));
    }

    @Test
    void managementTargetNeedsP1OrP2() throws Exception {
        String etag = etagOf("CS-10271", "support-agent");
        mvc.perform(escalate("CS-10271", "key-esc-0008", etag, "management", "support-agent"))
                .andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_SPEC);
        mvc.perform(escalate("CS-10271", "key-esc-0009", etag, "tier2", "support-agent"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.priority").value("P2"));
    }

    @Test
    void escalationNeedsItsOwnScope() throws Exception {
        String etag = etagOf("CS-10237", "support-agent");
        mvc.perform(escalate("CS-10237", "key-esc-0010", etag, "tier2", "csm-limited"))
                .andExpect(status().isForbidden());
    }

    @Test
    void escalationIsAuditedAndEmitsAVerifiedWebhook() throws Exception {
        String etag = etagOf("CS-10237", "support-agent");
        mvc.perform(escalate("CS-10237", "key-esc-0011", etag, "tier2", "support-agent")).andExpect(status().isOk());

        Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='case.escalate' AND resource_id='CS-10237' AND outcome='success' AND actor='alice.support'",
                Integer.class));
        Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_outbox WHERE status='pending' AND event_type='case.escalated'", Integer.class));

        dispatcher.dispatchDue();

        Assertions.assertEquals("delivered", jdbc.queryForObject("SELECT status FROM webhook_outbox", String.class));
        mvc.perform(get("/mock/webhook-receiver/events"))
                .andExpect(jsonPath("$[0].caseId").value("CS-10237"))
                .andExpect(jsonPath("$[0].signatureVerified").value(true));
    }

    @Test
    void rejectedEscalationsAreAuditedToo() throws Exception {
        String etag = etagOf("CS-10270", "support-agent");
        mvc.perform(escalate("CS-10270", "key-esc-0012", etag, "tier2", "support-agent")).andExpect(status().isUnprocessableEntity());
        Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='case.escalate' AND outcome='rejected' AND resource_id='CS-10270'",
                Integer.class));
    }

    @Test
    void correlationIdIsEchoedAndAppearsInProblems() throws Exception {
        mvc.perform(get("/v1/cases/CS-99999").header("Authorization", bearer("support-agent"))
                        .header("X-Correlation-ID", "trace-abc-123"))
                .andExpect(header().string("X-Correlation-ID", "trace-abc-123"))
                .andExpect(jsonPath("$.correlationId").value("trace-abc-123"));
    }
}
