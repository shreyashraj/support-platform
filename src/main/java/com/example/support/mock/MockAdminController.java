package com.example.support.mock;

import com.example.support.upstream.EntitlementClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dev-only controls: token minting, fault injection, reseeding. None of this exists when mocks are disabled. */
@RestController
@MockConditional
public class MockAdminController {
    public record ChaosRequest(Chaos.Mode mode, Long delayMs, Integer failNext) {}

    private final TokenIssuer tokens;
    private final Chaos chaos;
    private final MockCaseController cases;
    private final MockUsageController usage;
    private final MockWebhookReceiver receiver;
    private final EntitlementClient entitlements;
    private final java.util.List<com.example.support.upstream.Guard> guards;

    public MockAdminController(TokenIssuer tokens, Chaos chaos, MockCaseController cases, MockUsageController usage,
            MockWebhookReceiver receiver, EntitlementClient entitlements, java.util.List<com.example.support.upstream.Guard> guards) {
        this.tokens = tokens;
        this.chaos = chaos;
        this.cases = cases;
        this.usage = usage;
        this.receiver = receiver;
        this.entitlements = entitlements;
        this.guards = guards;
    }

    @PostMapping(value = "/mock/idp/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Map<String, Object>> token(@RequestParam("client_id") String clientId,
            @RequestParam(value = "grant_type", defaultValue = "client_credentials") String grantType,
            @RequestParam(value = "ttl_seconds", defaultValue = "3600") long ttl) {
        var persona = tokens.persona(clientId);
        if (persona.isEmpty() || !grantType.equals("client_credentials")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_client",
                    "known_clients", TokenIssuer.PERSONAS.keySet()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", tokens.issue(clientId, Duration.ofSeconds(Math.min(ttl, 86_400))));
        body.put("token_type", "Bearer");
        body.put("expires_in", ttl);
        body.put("scope", persona.get().scope());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/mock/chaos/{target}")
    Map<String, Object> setChaos(@PathVariable String target, @RequestBody ChaosRequest req) {
        chaos.set(target, req.mode() == null ? Chaos.Mode.OK : req.mode(),
                req.delayMs() == null ? 0 : req.delayMs(), req.failNext() == null ? 0 : req.failNext());
        return chaos.snapshot();
    }

    @GetMapping("/mock/chaos")
    Map<String, Object> getChaos() {
        return chaos.snapshot();
    }

    /** The entitlement snapshot is cached for 30s, so an outage is invisible until it expires. This skips the wait. */
    @PostMapping("/mock/cache/invalidate")
    Map<String, String> invalidateCaches() {
        entitlements.invalidate();
        return Map.of("status", "invalidated");
    }

    @PostMapping("/mock/reset")
    public Map<String, String> reset() {
        chaos.reset();
        cases.reset();
        usage.reset();
        receiver.clear();
        entitlements.invalidate();
        guards.forEach(g -> g.breaker().reset());
        return Map.of("status", "reset");
    }
}
