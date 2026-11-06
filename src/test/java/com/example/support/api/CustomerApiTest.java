package com.example.support.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.support.IntegrationTestBase;
import com.example.support.mock.Chaos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CustomerApiTest extends IntegrationTestBase {

    @Autowired Chaos chaos;

    @Test
    void aggregatesEntitlementUsageAndCases() throws Exception {
        mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("support-agent")))
                .andExpect(status().isOk())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.name").value("Globex Systems"))
                .andExpect(jsonPath("$.supportTier").value("Platinum"))
                .andExpect(jsonPath("$.usage.trend.length()").value(3))
                .andExpect(jsonPath("$.support.openP1Cases").value(1))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void feedOverridesCsvForTierAndRenewal() throws Exception {
        // ACC-003 is Gold in the CSV, Platinum with a 35 day renewal in the XML feed
        mvc.perform(get("/v1/customers/ACC-003").header("Authorization", bearer("support-agent")))
                .andExpect(jsonPath("$.supportTier").value("Platinum"))
                .andExpect(jsonPath("$.daysToRenewal").value(35));
    }

    @Test
    void legacyAccountIdsFromTheCsvAreNormalised() throws Exception {
        mvc.perform(get("/v1/customers/ACC-077").header("Authorization", bearer("support-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Corp"));
    }

    @Test
    void arrIsOnlyVisibleWithFinanceScope() throws Exception {
        mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("support-agent")))
                .andExpect(jsonPath("$.arrUsd").doesNotExist());
        mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("finance-analyst")))
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.arrUsd").value(310000));
    }

    @Test
    void accountOutsideTheCallersBookIsNotFound() throws Exception {
        mvc.perform(get("/v1/customers/ACC-010").header("Authorization", bearer("csm-limited")))
                .andExpect(status().isNotFound())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("not-found"));
        mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("csm-limited")))
                .andExpect(status().isOk());
    }

    @Test
    void usageWarehouseDownStillReturnsEntitlementDegraded() throws Exception {
        chaos.set("usage", Chaos.Mode.DOWN, 0, 0);
        mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("support-agent")))
                .andExpect(status().isOk())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.name").value("Globex Systems"))
                .andExpect(jsonPath("$.usage").value(nullValue()))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degradedSources", hasItem("usage")));
    }

    @Test
    void caseSystemSlowDegradesSupportBlockOnly() throws Exception {
        chaos.set("cases", Chaos.Mode.SLOW, 3000, 0);
        mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("support-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.support").value(nullValue()))
                .andExpect(jsonPath("$.usage.latestMonth").exists())
                .andExpect(jsonPath("$.degradedSources", hasItem("support")));
    }

    @Test
    void entitlementDownFailsTheWholeCall() throws Exception {
        chaos.set("entitlements", Chaos.Mode.DOWN, 0, 0);
        mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("support-agent")))
                .andExpect(status().is5xxServerError())
                .andExpect(MATCHES_SPEC)
                .andExpect(jsonPath("$.upstream").value("entitlements"));
    }

    @Test
    void transientUpstreamFailuresAreRetried() throws Exception {
        chaos.set("entitlements", Chaos.Mode.OK, 0, 2);
        mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("support-agent")))
                .andExpect(status().isOk());
    }

    @Test
    void conditionalGetReturns304() throws Exception {
        var first = mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("support-agent")))
                .andExpect(header().exists("ETag")).andReturn();
        String etag = first.getResponse().getHeader("ETag");
        mvc.perform(get("/v1/customers/ACC-007").header("Authorization", bearer("support-agent")).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void malformedIdIs400WithProblemBody() throws Exception {
        mvc.perform(get("/v1/customers/nope").header("Authorization", bearer("support-agent")))
                .andExpect(status().isBadRequest())
                .andExpect(RESPONSE_MATCHES_SPEC)
                .andExpect(jsonPath("$.code").value("validation-error"))
                .andExpect(jsonPath("$.correlationId").exists());
    }
}
