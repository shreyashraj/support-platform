package com.example.support.mock;

import com.example.support.config.PlatformProperties;
import com.example.support.service.WebhookService;
import com.example.support.service.WebhookSignature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A webhook consumer that does what a real one should: verify the signature before trusting the body. */
@RestController
@MockConditional
public class MockWebhookReceiver implements ApplicationRunner {
    public static final String PATH = "/mock/webhook-receiver";

    private final PlatformProperties props;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Chaos chaos;
    private final WebhookService webhooks;
    private final int port;
    private final List<Map<String, Object>> received = new ArrayList<>();

    public MockWebhookReceiver(PlatformProperties props, Clock clock, ObjectMapper mapper, Chaos chaos,
            WebhookService webhooks, @Value("${server.port}") int port) {
        this.props = props;
        this.clock = clock;
        this.mapper = mapper;
        this.chaos = chaos;
        this.webhooks = webhooks;
        this.port = port;
    }

    /** Wire the receiver up as a subscriber so the escalation demo works out of the box. */
    @Override
    public void run(ApplicationArguments args) {
        String url = "http://localhost:" + port + PATH;
        if (webhooks.findByUrl(url).isEmpty()) {
            webhooks.createWithSecret(url, List.of("case.escalated"), "seed", props.mocks().demoWebhookSecret());
        }
    }

    @PostMapping(PATH)
    public synchronized ResponseEntity<Map<String, Object>> receive(@RequestBody String body,
            @RequestHeader(value = WebhookSignature.HEADER_ID, required = false) String id,
            @RequestHeader(value = WebhookSignature.HEADER_TIMESTAMP, required = false) String ts,
            @RequestHeader(value = WebhookSignature.HEADER_SIGNATURE, required = false) String sig) throws Exception {
        if (chaos.apply("webhook-receiver")) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        boolean ok = WebhookSignature.verify(props.mocks().demoWebhookSecret(), ts, body, sig, 300, clock.millis() / 1000);
        if (!ok) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad signature"));
        JsonNode event = mapper.readTree(body);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", id);
        row.put("type", event.path("type").asText());
        row.put("caseId", event.path("data").path("caseId").asText());
        row.put("signatureVerified", true);
        row.put("receivedAt", clock.instant().toString());
        received.add(row);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @GetMapping(PATH + "/events")
    public synchronized List<Map<String, Object>> events() {
        return new ArrayList<>(received);
    }

    @DeleteMapping(PATH + "/events")
    public synchronized void clear() {
        received.clear();
    }
}
