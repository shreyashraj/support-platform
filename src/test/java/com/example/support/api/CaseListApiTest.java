package com.example.support.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.support.IntegrationTestBase;
import com.example.support.mock.Chaos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CaseListApiTest extends IntegrationTestBase {

    @Autowired Chaos chaos;
    @Autowired ObjectMapper json;

    @Test
    void answersTheSupportCopilotQuestionInOneCall() throws Exception {
        mvc.perform(get("/v1/cases")
                        .param("priority", "P1").param("status", "open").param("minOpenHours", "48")
                        .param("supportTier", "Platinum").param("renewalWithinDays", "90").param("sort", "openedAt")
                        .header("Authorization", bearer("support-agent")))
                .andExpect(status().isOk())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.items[*].id", contains("CS-10233", "CS-10231", "CS-10232")))
                .andExpect(jsonPath("$.items[0].account.name").value("Tyrell Corp"))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist());
    }

    @Test
    void cursorPaginationWalksEveryCaseExactlyOnce() throws Exception {
        Set<String> seen = new HashSet<>();
        List<String> openedAt = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var req = get("/v1/cases").param("limit", "7").param("sort", "-openedAt")
                    .header("Authorization", bearer("support-agent"));
            if (cursor != null) req.param("cursor", cursor);
            String body = mvc.perform(req).andExpect(status().isOk()).andExpect(MATCHES_SPEC)
                    .andReturn().getResponse().getContentAsString();
            JsonNode page = json.readTree(body);
            for (JsonNode item : page.get("items")) {
                Assertions.assertTrue(seen.add(item.get("id").asText()), "duplicate across pages: " + item.get("id"));
                openedAt.add(item.get("openedAt").asText());
            }
            JsonNode next = page.get("page").get("nextCursor");
            cursor = next.isNull() ? null : next.asText();
            pages++;
        } while (cursor != null && pages < 20);

        Assertions.assertEquals(37, seen.size());
        List<String> expected = new ArrayList<>(openedAt);
        expected.sort(Comparator.reverseOrder());
        Assertions.assertEquals(expected, openedAt);
    }

    @Test
    void cursorIsBoundToItsFilters() throws Exception {
        String body = mvc.perform(get("/v1/cases").param("limit", "2").param("priority", "P1")
                        .header("Authorization", bearer("support-agent"))).andReturn().getResponse().getContentAsString();
        String cursor = json.readTree(body).get("page").get("nextCursor").asText();
        mvc.perform(get("/v1/cases").param("limit", "2").param("priority", "P2").param("cursor", cursor)
                        .header("Authorization", bearer("support-agent")))
                .andExpect(status().isBadRequest())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("invalid-cursor"));
    }

    @Test
    void sortsByPriority() throws Exception {
        mvc.perform(get("/v1/cases").param("sort", "priority").param("limit", "3")
                        .header("Authorization", bearer("support-agent")))
                .andExpect(jsonPath("$.items[*].priority", contains("P1", "P1", "P1")));
    }

    @Test
    void repeatedStatusParametersAreOred() throws Exception {
        mvc.perform(get("/v1/cases").param("status", "pending").param("status", "escalated").param("priority", "P1")
                        .header("Authorization", bearer("support-agent")))
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder("CS-10238", "CS-10239")));
    }

    @Test
    void callerOnlySeesTheirAccounts() throws Exception {
        mvc.perform(get("/v1/cases").param("limit", "100").header("Authorization", bearer("csm-limited")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.accountId != 'ACC-003' && @.accountId != 'ACC-007')]").isEmpty());
    }

    @Test
    void badParametersAreValidationProblems() throws Exception {
        for (String[] p : new String[][] {{"limit", "0"}, {"limit", "101"}, {"status", "bogus"}, {"sort", "id"},
                {"renewalWithinDays", "9999"}, {"accountId", "x"}}) {
            mvc.perform(get("/v1/cases").param(p[0], p[1]).header("Authorization", bearer("support-agent")))
                    .andExpect(status().isBadRequest())
                    .andExpect(RESPONSE_MATCHES_SPEC)
                    .andExpect(jsonPath("$.code").value("validation-error"));
        }
    }

    @Test
    void entitlementOutageDegradesPlainListings() throws Exception {
        chaos.set("entitlements", Chaos.Mode.DOWN, 0, 0);
        mvc.perform(get("/v1/cases").param("priority", "P1").header("Authorization", bearer("support-agent")))
                .andExpect(status().isOk())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.items[0].account").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void entitlementOutageFailsCrossSystemFilters() throws Exception {
        chaos.set("entitlements", Chaos.Mode.DOWN, 0, 0);
        mvc.perform(get("/v1/cases").param("supportTier", "Platinum").header("Authorization", bearer("support-agent")))
                .andExpect(status().is5xxServerError())
                .andExpect(MATCHES_SPEC);
    }

    @Test
    void caseDetailCarriesCommentsAndEtag() throws Exception {
        mvc.perform(get("/v1/cases/CS-10231").header("Authorization", bearer("support-agent")))
                .andExpect(status().isOk())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.comments[0].author").value("jdoe"))
                .andExpect(header().string("ETag", "\"v1\""));
    }
}
