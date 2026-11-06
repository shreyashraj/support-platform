package com.example.support.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Thin HTTP client for the platform API. Always sends the user's own token, never a service credential. */
class ApiClient {
    record Response(int status, Map<String, Object> body, String etag) {
        boolean ok() { return status >= 200 && status < 300; }

        String code() {
            return status == 0 ? "unreachable" : Json.str(body.getOrDefault("code", "http-" + status));
        }

        String detail() { return Json.str(body.getOrDefault("detail", "")); }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String token;

    ApiClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    Response get(String path, Map<String, String> query, String correlationId) {
        StringJoiner qs = new StringJoiner("&");
        query.forEach((k, v) -> qs.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        String url = baseUrl + path + (query.isEmpty() ? "" : "?" + qs);
        return send(builder(url, correlationId).GET().build());
    }

    Response post(String path, Map<String, String> headers, Object body, String correlationId) {
        HttpRequest.Builder b = builder(baseUrl + path, correlationId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        headers.forEach(b::header);
        return send(b.build());
    }

    private HttpRequest.Builder builder(String url, String correlationId) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("X-Agent-Id", "support-copilot")
                .header("X-Correlation-ID", correlationId);
    }

    private Response send(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = res.body() == null || res.body().isBlank() ? new LinkedHashMap<>() : Json.parse(res.body());
            return new Response(res.statusCode(), body, res.headers().firstValue("ETag").orElse(null));
        } catch (IOException e) {
            return new Response(0, Map.of("detail", "could not reach the platform API"), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, Map.of("detail", "interrupted"), null);
        }
    }
}
