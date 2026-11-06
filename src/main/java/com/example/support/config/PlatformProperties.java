package com.example.support.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(
        @DefaultValue("data/platform.db") String dbPath,
        Security security,
        RateLimit rateLimit,
        Upstream upstream,
        Webhooks webhooks,
        Mocks mocks) {

    public record Security(String jwtSecret, String issuer, String audience) {}

    public record RateLimit(@DefaultValue("120") int perMinute, @DefaultValue("40") int agentPerMinute) {}

    public record Upstream(String apiKey, Endpoint cases, Endpoint entitlements, Endpoint usage) {}

    public record Endpoint(
            String baseUrl,
            @DefaultValue("300ms") Duration connectTimeout,
            @DefaultValue("1s") Duration readTimeout,
            @DefaultValue("3") int retryAttempts,
            @DefaultValue("100ms") Duration retryBackoff,
            @DefaultValue("30s") Duration cacheTtl) {}

    public record Webhooks(@DefaultValue("false") boolean allowPrivateTargets, @DefaultValue("8") int maxAttempts,
            @DefaultValue("true") boolean dispatcherEnabled) {}

    public record Mocks(@DefaultValue("false") boolean enabled, String demoWebhookSecret) {}
}
