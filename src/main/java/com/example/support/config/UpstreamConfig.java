package com.example.support.config;

import com.example.support.upstream.CaseSystemClient;
import com.example.support.upstream.EntitlementClient;
import com.example.support.upstream.Guard;
import com.example.support.upstream.UsageClient;
import com.example.support.web.Problems;
import java.net.http.HttpClient;
import java.time.Clock;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class UpstreamConfig {

    private RestClient rest(RestClient.Builder builder, PlatformProperties.Endpoint ep, String apiKey) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(ep.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(ep.readTimeout());
        return builder.clone()
                .baseUrl(ep.baseUrl())
                .requestFactory(factory)
                .defaultHeader("X-Upstream-Key", apiKey)
                .requestInterceptor((request, body, execution) -> {
                    String cid = MDC.get(Problems.MDC_CORRELATION);
                    if (cid != null) request.getHeaders().set("X-Correlation-ID", cid);
                    return execution.execute(request, body);
                })
                .build();
    }

    @Bean
    Guard casesGuard(PlatformProperties p) {
        var ep = p.upstream().cases();
        return new Guard("cases", ep.retryAttempts(), ep.retryBackoff());
    }

    @Bean
    Guard entitlementsGuard(PlatformProperties p) {
        var ep = p.upstream().entitlements();
        return new Guard("entitlements", ep.retryAttempts(), ep.retryBackoff());
    }

    @Bean
    Guard usageGuard(PlatformProperties p) {
        var ep = p.upstream().usage();
        return new Guard("usage", ep.retryAttempts(), ep.retryBackoff());
    }

    @Bean
    CaseSystemClient caseSystemClient(RestClient.Builder b, PlatformProperties p, Guard casesGuard) {
        return new CaseSystemClient(rest(b, p.upstream().cases(), p.upstream().apiKey()), casesGuard);
    }

    @Bean
    EntitlementClient entitlementClient(RestClient.Builder b, PlatformProperties p, Guard entitlementsGuard, Clock clock) {
        var ep = p.upstream().entitlements();
        return new EntitlementClient(rest(b, ep, p.upstream().apiKey()), entitlementsGuard, clock, ep.cacheTtl());
    }

    @Bean
    UsageClient usageClient(RestClient.Builder b, PlatformProperties p, Guard usageGuard) {
        return new UsageClient(rest(b, p.upstream().usage(), p.upstream().apiKey()), usageGuard);
    }
}
