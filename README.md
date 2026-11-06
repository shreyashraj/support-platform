# Customer Entitlement & Support Case API

One API in front of three systems (case system, entitlement system, usage warehouse), for Support, Finance and
Customer Success tools and for an AI "Support Copilot" that talks to it through MCP.

Java 21, Spring Boot 3.5, SQLite. The upstream systems are stubbed inside the app (JSON, CSV + XML, SQL), so there is
nothing to set up.

```
 apps / iPaaS / MCP server ──► platform API (/v1) ──┬─► case system      REST, JSON, capitalised keys
        (OAuth 2.0 bearer)      auth, scopes,       ├─► entitlements     CSV export + XML feed
                                idempotency, audit, └─► usage warehouse  SQL over HTTP
                                webhooks (SQLite)
```

## Run it

You need a JDK 21 or newer. Nothing else (Maven comes from the wrapper).

```bash
make run          # API on http://localhost:8080 with the mock upstreams
```

Get a token for one of the personas, then call something:

```bash
TOKEN=$(curl -s -X POST localhost:8080/mock/idp/token -d client_id=support-agent | jq -r .access_token)

# the Copilot's question in one call: Platinum, P1, open 48h+, renewal within 90 days
curl -s "localhost:8080/v1/cases?priority=P1&status=open&minOpenHours=48&supportTier=Platinum&renewalWithinDays=90" \
  -H "Authorization: Bearer $TOKEN" | jq '.items[] | {id, accountId, openHours, account}'

curl -s localhost:8080/v1/customers/ACC-007 -H "Authorization: Bearer $TOKEN" | jq
```

| client_id | who | can |
|---|---|---|
| `support-agent` | alice.support | read, comment, escalate |
| `support-readonly` | bob.readonly | read |
| `finance-analyst` | fiona.finance | read, plus `arrUsd` |
| `csm-limited` | carl.csm | read/comment, only ACC-003 and ACC-007 |
| `platform-admin` | root.admin | everything, including webhook subscriptions |

Docker works too: `docker compose up --build`.

## Tests

```bash
make test         # API: 75 tests, MCP server: 31 tests
make postman      # 44 requests / 96 assertions with newman, needs `make run` going
```

The API tests start the whole app and check every response against `openapi.yaml` (contract tests), and a separate
test fails if the code and the spec expose different operations. `make lint` runs Spectral on the spec with the rules
in `.spectral.yaml`. The Docker image was built and the Postman collection run against the container.

## Things to try (about five minutes)

Show resilience. Break the usage warehouse and the customer call still answers, flagged as degraded:

```bash
curl -s -X POST localhost:8080/mock/chaos/usage -H 'content-type: application/json' -d '{"mode":"DOWN"}'
curl -s localhost:8080/v1/customers/ACC-007 -H "Authorization: Bearer $TOKEN" | jq '{name, usage, degraded, degradedSources}'
curl -s -X POST localhost:8080/mock/reset
```

Escalate a case and watch the signed webhook arrive at the built-in receiver:

```bash
ETAG=$(curl -si localhost:8080/v1/cases/CS-10237 -H "Authorization: Bearer $TOKEN" | awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
curl -s -X POST localhost:8080/v1/cases/CS-10237/escalate -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H "If-Match: $ETAG" -H 'content-type: application/json' \
  -d '{"reason":"Customer is down and RPO is breached","target":"tier2"}' | jq '{status, priority}'
sleep 2; curl -s localhost:8080/mock/webhook-receiver/events | jq
```

Faults you can inject: `mode` is `OK`, `DOWN` or `SLOW` (with `delayMs`), and `failNext: 2` fails the next two calls
only. Targets: `cases`, `entitlements`, `usage`, `webhook-receiver`.

## MCP server and demo

See [mcp/README.md](mcp/README.md). Short version:

```bash
make run          # terminal 1
make demo         # terminal 2: drives the MCP server over stdio, writes mcp/transcripts/scripted-demo.md
make demo-llm     # same, with Claude choosing the tool calls (needs ANTHROPIC_API_KEY)
```

## Layout

| Path | What |
|---|---|
| `openapi.yaml` | The contract (Part A). Served at `/openapi.yaml` too |
| `src/main/java/.../` | `web` controllers and error handling, `service` business logic, `upstream` clients and resilience, `security`, `mock` stubbed upstreams and dev tools |
| `src/test/java/.../` | Unit tests and contract/integration tests. This is Maven's standard layout, so the brief's `src/tests/` lives here |
| `mcp/` | MCP server, guardrails, demo and transcripts (Part C). Its own Maven project |
| `postman/` | Collection and environment |
| `DECISIONS.md` | Trade-offs, assumptions, what I skipped |
| `PLATFORM.md` | Part D |
| `AI_USAGE.md` | How AI tools were used and checked |

`/mock/**` (token issuer, fault injection, reset, the upstream stubs) only exists while `platform.mocks.enabled=true`,
which is the default here and would be off anywhere real.
