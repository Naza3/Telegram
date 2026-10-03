# AI summary JVM regression harness

This harness compiles the actual `SummaryHistoryLoader`, `AiSummaryClient`,
`AiSummarySettings`, `AiSummaryPrompt` and `SummaryMessage` production sources.
It does not contact Telegram, a user model, or any external HTTP service.

Run with Java 17 or later, an Eclipse ECJ compiler, and a JVM `org.json` jar:

```sh
ECJ_JAR=/path/to/ecj.jar JSON_JAR=/path/to/json.jar bash tests/ai-summary/run.sh
```

The default dependency directory is `/workspace/ai-summary-tools`; override it
with `AI_SUMMARY_TOOLS_DIR`. Dependencies are not downloaded by this script.
Class files are built in a temporary directory and removed on exit.

The tests cover:

- `SummaryHistoryLoaderTest`: bounded recent-message pagination, short and
  overlapping pages, filtering while advancing cursors, fixed upper bounds,
  chronological source references, local-day boundaries in a non-UTC timezone,
  topic-root cursors, link-preview original text, cancellation, and account-slot
  changes. Telegram replies and the Android main loop are queued test doubles.
- `AiSummaryClientTest`: real loopback HTTP requests and responses, non-streaming
  request shape, optional model/key, 401, redirects without following them,
  malformed/empty responses, text content parts, truncated output, MNN's HTTP 200
  `Error:` response, thinking removal, response-size limits, and cancellation
  before or after a UI callback is queued. It binds only to `127.0.0.1` using an
  ephemeral port. A sandbox must permit local sockets for this test.
- `AiSummaryPromptTest`: source/merge budgets, message references and prompt data
  handling; see the assertions in the test for its exact cases.

The isolated `stubs/` directory models only the Telegram and Android members
needed for these tests. The secret-store test double checks the settings-layer
boundary; it does **not** implement or verify Android Keystore encryption.
These tests do not substitute for an Android Gradle build, real MTProto history
validation, device UI/lifecycle checks, Android cleartext/TLS policy checks,
Keystore persistence, or testing against an actual MNN service. They do not
prove that a model's cited claims are factually supported by the source messages.
