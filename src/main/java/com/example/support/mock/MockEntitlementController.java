package com.example.support.mock;

import java.time.Clock;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@MockConditional
public class MockEntitlementController {
    private final Clock clock;

    public MockEntitlementController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/mock/entitlements/export/entitlements.csv")
    ResponseEntity<String> csv() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(SeedData.entitlementCsv(clock));
    }

    @GetMapping("/mock/entitlements/feed/accounts.xml")
    ResponseEntity<String> feed() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(SeedData.entitlementFeed(clock));
    }
}
