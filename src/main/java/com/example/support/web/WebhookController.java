package com.example.support.web;

import com.example.support.security.Caller;
import com.example.support.service.AuditService;
import com.example.support.service.WebhookService;
import com.example.support.service.WebhookService.CreatedSubscription;
import com.example.support.service.WebhookService.Subscription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    public record SubscriptionRequest(
            @NotBlank @Size(max = 2000) String url,
            @NotEmpty List<@Pattern(regexp = "case\\.escalated", message = "unsupported event type") String> events) {}

    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("^whs_[0-9a-f]{16}$");

    private final WebhookService webhooks;
    private final AuditService audit;

    public WebhookController(WebhookService webhooks, AuditService audit) {
        this.webhooks = webhooks;
        this.audit = audit;
    }

    @GetMapping("/v1/webhook-subscriptions")
    Map<String, List<Subscription>> list() {
        return Map.of("items", webhooks.list());
    }

    @PostMapping("/v1/webhook-subscriptions")
    ResponseEntity<CreatedSubscription> create(@Valid @RequestBody SubscriptionRequest req) {
        Caller caller = Caller.current();
        CreatedSubscription created = webhooks.create(req.url(), req.events(), caller.subject());
        audit.record(caller, "webhook.subscription.create", "webhook_subscription", created.id(), "success",
                Map.of("url", created.url()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/v1/webhook-subscriptions/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        if (!ID.matcher(id).matches() || !webhooks.delete(id)) throw ProblemException.notFound("Subscription " + id);
        audit.record(Caller.current(), "webhook.subscription.delete", "webhook_subscription", id, "success", Map.of());
        return ResponseEntity.noContent().build();
    }
}
