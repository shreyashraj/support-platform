# AI usage

Tool: Claude Code (Sonnet 5.5) in VS Code, working in this repo with shell access. No other assistants.

## What I used it for

- **First drafts of most of the code**: Spring Boot app, the upstream clients and mocks, the MCP server, the test suites,
  the Postman collection generator, Makefile/Dockerfile.
- **Drafting the contract and the docs** (`openapi.yaml`, DECISIONS, PLATFORM, READMEs) from the assignment PDF and my
  stated choices (Java, SQLite, outbox for webhooks, two-step confirmation for escalation).
- **Exploring unfamiliar APIs**: the Java MCP SDK has moved to Jackson 3 and its API is not what older examples show, so
  the assistant read the jar (`javap`) and compiled against it before writing the server.
- **Running things**: starting the app, curl smoke tests, newman, Spectral, the demo.

I did not use it to write a transcript of an LLM run. The scripted demo output is real output from the code. The LLM
demo is a separate command and needs my API key.

## What it got wrong, and how that was caught

| Mistake | How I found it | Fix |
|---|---|---|
| First attempt was Python/FastAPI, not what I wanted | I stopped it before it installed anything | Restarted in Java |
| Mock fault injection used `sendError(503)`; Spring Security treated the error dispatch as an unauthenticated request, so upstream failures looked like 401s and "usage down" produced a 502 instead of a degraded 200 | Smoke test with curl and the chaos endpoint, not by reading the code | Write the status directly |
| Retry x read-timeout made the degraded path take 4.8s | `time curl` during the same smoke test | Shorter timeouts, fewer retries for optional sources, a shared 2.5s budget |
| The spec said `escalatedAt` was required, the code omitted it when null | Contract tests (every response is validated against `openapi.yaml`) | Code now sends explicit `null`; spec unchanged |
| `info.summary` (OpenAPI 3.1 only) in a 3.0.3 file; an unquoted `: ` in a YAML description turned prose into a key; `allOf` + `nullable` rejected by the validator | Spec failed to load in the contract test base class, then response validation failed | Fixed the spec, restructured the nullable schemas |
| Negative-path tests (bad limit, empty body) failed because the matcher also validates the *request* against the spec | Test run | Added a response-only matcher for deliberately invalid requests |
| Several test files were silently not created in the first attempt (shell working directory had moved) | The first `mvn test` had fewer tests than expected | Recreated them with absolute paths |
| Escalation previews counted against the write rate limit, so a bulk "escalate all P1" run had its confirmations rejected and the most urgent case was missed | I ran harder prompts through Claude with all tools pre-approved and read the audit log | Previews use the read budget; regression test added and checked to fail without the fix; same prompt re-run |
| Postman run hid an entitlement outage behind the 30s cache, and a second run started with an open circuit breaker | Running the collection three times in a row | Dev endpoint to expire the cache; `/mock/reset` resets breakers |

## How I verified the output

- Read the generated code in the parts that carry risk: `SecurityConfig`, `IdempotencyService`, `CaseService.escalate`,
  `Guard`, the confirmation token and sanitising code.
- Ran it: `make run`, curl against every endpoint in the README, fault injection for each degraded path.
- Tests: 75 API tests (contract-checked against the spec, plus a spec-vs-routes parity test), 31 MCP tests including
  tests that assert a refused escalation makes no API call, a stale case forces a new preview, and injected comment text
  never reaches the model. A deliberately broken spec copy confirmed the Spectral rules fail when they should.
- Newman: 44 requests, 96 assertions, three consecutive runs against the same instance.
- Checked claims in the docs against behaviour (for example the 2.1s degraded timing, the three expected cases for the
  Copilot question, test counts).

## Where I did not rely on it

- **Choices I made, not the assistant.** I picked Java and Spring Boot. The assistant's first attempt was
  Python/FastAPI, and I stopped it before it installed anything because I preferred the API in Java, mainly for
  security reasons. I also picked SQLite for storage, an outbox for webhooks, and a two-step confirmation for
  escalation, so nothing changes until a human has seen the preview.
- **Rules and targets I set or changed.** I reviewed the escalation rules and kept the confirmation token at 5 minutes
  and the cap at 3 escalations per session: a token that stays valid longer is a security risk, and 3 is enough for a
  real session while limiting what a steered agent can do. I kept ARR as a band in the MCP tools, even for finance
  users, because the model cannot repeat a number it never saw. I kept the latency targets as written. I kept
  withholding suspicious free text instead of passing it through with a warning, because it is the safer default.
  I changed the availability target for comments and escalation from 99.5% to 99.9%, matching reads: a missed
  escalation on a P1 costs more than a slow dashboard. I know it needs a queue-and-retry fallback that is not built yet,
  and PLATFORM.md says so.
- **What I ran and checked myself.** I started the app and tried the endpoints with curl. I ran `make postman` myself
  and it passed. I ran `make demo` and read the output, including the CSM scope test where `get_case CS-10233`
  returns `not_found`. I also ran the other checks (`make test`, `make lint`, Docker, and the fault-injection
  scenarios) to test the scenarios myself. I have nothing further to add beyond what is listed here.
- **What I did not do.** `make demo-llm` stopped with "Set ANTHROPIC_API_KEY to run the LLM demo" because I do not have
  an Anthropic API key. I recorded an LLM run through the Claude Code CLI instead (`mcp/transcripts/llm-demo-claude-code.md`,
  real output, read tools only). I did not write any transcript by hand. Harder prompts (`llm-adversarial-claude-code.md`) found the rate-limit bug above.
  This is also listed in DECISIONS.md.
