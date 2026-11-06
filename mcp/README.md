# Support Copilot MCP server

Wraps the platform API as MCP tools over stdio. It acts as the signed-in user: the user's own access token goes in
`SUPPORT_API_TOKEN` and is forwarded on every API call, so the API still enforces that user's scopes and account
visibility. The server holds no credentials of its own.

| Tool | Kind | Notes |
|---|---|---|
| `search_cases` | read | Filters map onto `GET /v1/cases`, compact rows, capped at 25 and ~6k characters |
| `get_customer_summary` | read | Tier, renewal, usage trend, open case counts. ARR comes back as a band only |
| `get_case` | read | One case plus its last 5 comments, sanitised |
| `add_case_comment` | write | Marked `[Support Copilot]`, retry-safe, rate limited |
| `escalate_case` | write, high risk | Two steps: preview and token, then confirm. Capped per session |

Environment: `SUPPORT_API_URL` (default `http://localhost:8080`), `SUPPORT_API_TOKEN` (required),
`MCP_AUDIT_FILE` (default `audit/tool-calls.jsonl`), `MCP_READS_PER_MIN` (30), `MCP_WRITES_PER_MIN` (6),
`MCP_MAX_ESCALATIONS` (3 per session), `MCP_MAX_OUTPUT_CHARS` (6000).

## Run the demo

```bash
make run                       # in the repo root, API on :8080
make demo                      # scripted: fixed tool calls, no LLM
ANTHROPIC_API_KEY=... make demo-llm
```

`demo` writes `transcripts/scripted-demo.md`. `demo-llm` lets Claude (`DEMO_MODEL`, default `claude-sonnet-5-5`) pick
the calls and pauses for a typed `y` when the agent wants to escalate; it writes `transcripts/llm-demo-<time>.md`.

Transcripts in `transcripts/`:

| File | What it is |
|---|---|
| `scripted-demo.md` | Fixed tool calls, no LLM (`make demo`) |
| `llm-demo-claude-code.md` | Claude, run through the Claude Code CLI (no API key), answers the sample question; read tools only |
| `llm-adversarial-claude-code.md` | Harder prompts with all tools pre-approved: injection, bulk escalation, out-of-scope data, read-only user. Found the preview rate-limit bug (see DECISIONS.md) |

## Use it from Claude Desktop or Cursor

```bash
make mcp    # builds mcp/target/support-copilot-mcp-0.1.0.jar
TOKEN=$(curl -s -X POST localhost:8080/mock/idp/token -d client_id=support-agent | jq -r .access_token)
```

```json
{
  "mcpServers": {
    "support-copilot": {
      "command": "java",
      "args": ["-cp", "/absolute/path/to/mcp/target/support-copilot-mcp-0.1.0.jar", "com.example.support.mcp.CopilotServer"],
      "env": { "SUPPORT_API_URL": "http://localhost:8080", "SUPPORT_API_TOKEN": "<paste token>" }
    }
  }
}
```

Tokens from the mock issuer last an hour. Stdout is the protocol channel, so all logging goes to stderr.

## Where the guardrails live

| Concern | Where |
|---|---|
| Acting on behalf of a user | `ApiClient` forwards the user's token; `Identity` only reads claims for labelling and a fast scope check |
| Human approval for risky writes | `CopilotTools.escalate` + `ConfirmationTokens`; tool annotated `destructiveHint` so the host can add its own prompt |
| Injection via data | `Guardrails.untrusted`: strips invisible characters, withholds text that reads like instructions, redacts emails and phone numbers, truncates |
| ARR | `Guardrails.arrBand`, and the API already hides it without `finance:read` |
| Output size | `search_cases` limit of 25, `capped(...)`, comment excerpts of 300 chars |
| Rate limits | `RateLimiter`: separate read and write buckets in this process (an escalation preview counts as a read), plus the API's own per-agent bucket (`X-Agent-Id`) |
| Audit | `AuditLog`: every call, including blocked ones, with a correlation id that is also sent to the API |

Tests for all of it are in `src/test/java` and run without an LLM or the real API.
