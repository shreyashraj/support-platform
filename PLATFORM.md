# Platform best practices and NFRs

How I would run this API as a shared platform, not just what the repo does today. Where the repo already does something, I say so.

## 1. Gateway vs service

The gateway owns what is the same for every API and needs no business knowledge; the service owns what needs the data model.

| At the gateway (Kong / Apigee / API GW / Azure APIM / MuleSoft) | In the service |
|---|---|
| TLS, mTLS for clients that need it, WAF | Business rules (escalation rules, P4 can't escalate) |
| Token validation and coarse scope checks per route | Field-level and per-resource authorisation (`arrUsd`, account visibility) |
| Global and per-client rate limits and quotas, `RateLimit-*` headers | Per-agent limits (the service keeps a small agent bucket as a backstop) |
| Request schema validation against `openapi.yaml` | Semantic validation (cursor bound to filters, state transitions) |
| Correlation id injection, CORS, size limits, IP allow-lists | Idempotency, optimistic concurrency, webhooks |
| Versioned routing, deprecation headers, canary split | Aggregation and degradation (which upstream is optional) |

**MCP traffic** goes through the same gateway as a client with its own identity: it presents the user's token, adds `X-Agent-Id` and gets a lower rate-limit policy. The gateway sees every agent call, so it is the central place to cut an agent off. A remote MCP server would also be fronted by the gateway (auth, rate limit, payload size). Today the repo does rate limiting and token validation in the service so it runs standalone.

## 2. NFRs and SLAs

First-version targets, to be revised against real traffic after a month.

| Endpoint | Availability | Latency (server side) | Throughput | Notes |
|---|---|---|---|---|
| `GET /v1/customers/{id}` | 99.9% | p95 < 400ms, p99 < 1.2s | 50 rps, 150 burst | The 2.5s optional-source budget caps p99.9 |
| `GET /v1/cases` | 99.9% | p95 < 500ms, p99 < 1.5s | 30 rps | Filters in memory today; needs push-down at scale |
| `POST .../comments`, `.../escalate` | 99.9% | p95 < 800ms, p99 < 2s | 5 rps | Dominated by the case system. Raised from 99.5%: a lost escalation costs more than a slow read |
| MCP tools (read) | 99.5% | p95 < 1s | 30 calls/min/agent | Adds a hop and sanitising |
| MCP tools (write) | 99.5% | p95 < 1.5s | 6 calls/min/agent | Escalation is two calls plus a human |
| Webhook delivery | 99.9% of events | p95 < 10s to first attempt | n/a | At-least-once; a dead event is an alert |

Reads can beat the upstreams' availability because only entitlement is a hard dependency: a usage or case-count outage costs no availability, only `degraded: true`.

**Write availability depends on the case system.** Every write needs it, so 99.9% is only honest with a fallback: accept the write, queue it like the webhook outbox, return `202`, and apply it on recovery under the same `Idempotency-Key`. Not built. Until then the target is a goal, not something this version meets. MCP writes stay at 99.5% because escalation also needs a human step.

**SLIs:** share of requests below 500 (excluding caller-caused 429s), latency per route, share of `degraded: true` per source, age of the oldest pending webhook, MCP success rate per tool. **SLOs** are the table over 30 days; the error budget for 99.9% is about 43 minutes a month. Alert on burn rate: page at 2% of the monthly budget in an hour, ticket at 10% in a day. Also alert on: breaker open over 2 minutes, oldest pending webhook over 5 minutes, any dead event, any client above 20% 429s.

## 3. Observability

**In the repo:** structured JSON logs with a `correlationId` on every line, taken from `X-Correlation-ID` or generated, echoed on responses, sent to upstreams, webhooks and error bodies; Actuator health probes; a Prometheus endpoint with JVM and HTTP metrics. **To add:** OpenTelemetry tracing (correlation id becomes trace id) and custom meters for breaker state, retries, degraded responses, outbox depth and age, idempotency replays.

**A good dashboard,** top to bottom: golden signals per route (rate, error %, p50/p95/p99); error-budget burn per SLO; one row per upstream (calls, errors, latency, breaker state); degraded responses by source; webhooks (pending, oldest age, delivered/failed/dead); MCP (calls per tool, calls blocked by a guardrail, escalations per session); top callers by rate and 429s. Panels link to logs for the same time range.

## 4. Governance

**Standards.** Spectral in CI (`.spectral.yaml`): `spectral:oas` plus own rules (every operation has an `operationId`, tag, `x-required-scopes` and problem+json for errors; paths start with a major version). A spec-PR checklist: plural nouns, error codes listed, new fields optional, idempotency and pagination consistent. Docs standard: every operation has a summary and a description of failure behaviour and side effects, and the spec is the developer-portal source.

**Breaking-change detection.** `oasdiff` compares the PR's spec with main and fails on removed or renamed fields, narrowed request enums, new required parameters and type changes. A deliberate break needs a new major version and a sunset date in the deprecation register.

**Adoption without authority.** Make the standard the easy path: a template repo, a one-line CI step, a linter that suggests fixes and this repo as a worked example. Publish a visible, non-punitive scorecard (lint results, contract coverage), review specs early, and show a few outcomes (time to first call, share of APIs with a contract, breaking changes caught) to the people who do hold authority. Teams adopt what saves time: generated clients, mocks from the spec, free contract tests.

## 5. Security and compliance

- **Secrets.** Only dev values for the mocks are in the repo. In real environments they come from a secret manager (Vault / AWS Secrets Manager), are rotated, and never appear in images or logs. Webhook secrets are per subscription, shown once, and should be stored encrypted (the demo stores them plain).
- **mTLS.** Mesh or sidecar certificates with short lifetimes. The API and the MCP server authenticate to the gateway with mTLS plus the user's token. Upstream SaaS credentials are held by the service, never by the agent.
- **Network.** Private subnets; SaaS egress over private connectivity (PrivateLink) where offered, otherwise a fixed-IP NAT with the vendor's allow-list. Metrics on an internal-only port (the repo uses the main port). Webhook delivery through an egress proxy that enforces https and blocks private ranges.
- **Audit.** `audit_log` records actor, agent, action, resource, outcome and correlation id for writes and rejected escalations. In production it ships to append-only storage (object lock) with policy-based retention, and the service account cannot edit it.
- **SOX change control.** Pull requests with required reviews and no self-approval, CI results attached, deploys by pipeline only with approver and image digest recorded, break-glass production access. Segregation of duties: whoever approves a change to financial fields (`arrUsd`) does not deploy it. Quarterly review of who holds `finance:read`.
- **FedRAMP-style.** FIPS-validated crypto, hardened scanned images, an SBOM per build, vulnerability SLAs, no real data in lower environments. This is a control-mapping exercise with compliance; code alone doesn't provide it.

## 6. Reuse

1. **Connectors as versioned modules** (`case-system`, `entitlements`, `usage-warehouse`) with typed client, resilience policy, normalisation and fixture tests, published to the internal registry.
2. **Generated SDKs** (Java, TypeScript, Python) from `openapi.yaml` on each release.
3. **Catalog entry** per API (owner, SLO, scopes, sandbox URL, deprecation status), plus a spec-based MuleSoft/Workato connector so flows get the contract, not a hand-built HTTP call.
4. **A sandbox:** this repo's mock mode, with seeded data, fault injection and a token issuer.
5. **MCP guardrails as a library** (untrusted-text handling, confirmation tokens, audit, per-agent limits) so the safe pattern is the default for the next agent-facing API.

The adoption metric is a second team using it without asking me anything.

## 7. CI/CD

Stages, failing fast:

1. **Lint:** Spectral on the spec, static analysis on code.
2. **Unit tests:** domain rules, resilience, guardrails.
3. **Contract tests:** the app against `openapi.yaml`, the spec-vs-routes parity test, `oasdiff` against main.
4. **Integration and Newman** against an ephemeral environment with the mock upstreams.
5. **Security scan:** dependencies (OWASP Dependency-Check / Trivy), secrets, SAST, image scan, SBOM.
6. **Build** an immutable image, sign it, push by digest.
7. **Staging** with real upstream sandboxes: smoke tests and a short soak with fault injection.
8. **Canary** at 5%, 25%, 100% with automated error-rate and latency analysis; **automatic rollback** on SLO burn. Migrations are expand/contract so rollback is safe.
9. **Post-deploy:** a synthetic run of the Copilot question, and the deploy event written to the audit trail.

## 8. iPaaS decision

| Part | Use | Why |
|---|---|---|
| Plain SaaS connectivity (pull cases, the CSV, auth, paging) with no business logic | **MuleSoft / Workato / Boomi** connectors | The vendor maintains the connector and auth; faster and easier for an integration team to own |
| Workflows business teams change often (on escalation: post to Slack, open Jira, email the owner) | **iPaaS**, triggered by the `case.escalated` webhook | Orchestration and glue; the owning team changes it without a deploy |
| API gateway and catalog if the company is already on Anypoint | **MuleSoft** | Policy and portal, not logic |
| Aggregate customer view, escalation rules, idempotency, ETags, degraded flag | **Custom code** | Business rules, fine-grained resilience and field-level authorisation are costly to express and test in low-code; I want unit tests, contract tests and code review |
| MCP server and guardrails | **Custom code** | Needs precise control over schemas, sanitising and audit, and is security-sensitive |
| One-off, low-volume, low-risk syncs | **iPaaS** | Not worth a service |

Principle: logic that defines the product goes in code with tests; plumbing and frequently changing glue go in the iPaaS. The webhook is the seam, and `openapi.yaml` is what the iPaaS flows build against.
