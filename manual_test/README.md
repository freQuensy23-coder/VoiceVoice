# Standalone Codex Android manual testing

This package runs each YAML test with one standalone Codex CLI session, pinned to `@openai/codex@0.144.6` and `gpt-5.6-sol`. Authentication is the ChatGPT/Codex subscription OAuth state in the CLI’s own `auth.json`. There is no OpenAI API key, SDK client, direct completion request, auxiliary verifier, or second model.

Each test gets one autonomous agent from first observation through final verdict. The generic YAML contains preflight ADB commands, natural-language checklist steps, and evidence requests; application expectations are never embedded in Python or workflows.

The stdio Android MCP server exposes exactly `observe`, `tap`, `tap_text`, `type`, `swipe`, `press`, `capture`, `inspect`, and `submit`. Because MCP `ImageContent` is not model-visible in Codex CLI 0.144.6, every non-submit tool atomically writes a validated bounded `latest.png`, `latest.xml`, and hash manifest instead of flooding JSONL with unusable base64. The harness resumes the same Codex thread with `codex exec resume -i latest.png`, making pixels visible through native CLI image input. Captures remain immutable and must be reopened before submission. Only `final_result.json` written by `submit` counts; CLI prose is ignored.

## Isolated runtime

The launcher creates a mode-0700 `CODEX_HOME` from a strict `auth_mode=chatgpt` standalone Codex CLI `auth.json`, an empty working directory, and a `config.toml` that selects and auto-approves only the Android MCP server. It disables shell execution, delegation, plugins, workspace dependencies, apps, browser and computer use, image generation, web search, hooks, goals, and memories. A non-ephemeral thread is required for resume; every invocation remains read-only, without a model-accessible network tool or repository discovery.

Each YAML test is a bounded turn loop. Turn one must call exactly `observe`. After each successful tool call, the trusted harness validates exactly one new action-log record plus fresh type/size/XML/PNG/hash metadata and attaches only that `latest.png` to the next resume. Schema/validation errors receive a fresh snapshot so the same thread can correct them; up to three tool-free turns may be retried. Multiple tool calls in one invocation still fail closed. The loop caps turns, per-turn and total time, process output, total transcript size, action logs, XML, and images. It ends only after the single MCP call was `submit` and the server wrote a valid result.

From `manual_test/`:

```sh
uv sync --extra test
uv run pytest
uv run ruff check .
uv build
```

A real run also needs an Android SDK, `adb`, a running emulator, and the exact Codex CLI:

```sh
npm install --global '@openai/codex@0.144.6'
manual-test preflight --suite manual_test/tests.yaml --test-id recording-toggle --cwd "$PWD"
manual-test run --suite manual_test/tests.yaml --test-id recording-toggle \
  --run-dir manual_test/reports/recording-toggle --auth-file /secure/codex/auth.json
```

## Encrypted persistent OAuth state

`CODEX_AUTH_STATE_KEY` is a Fernet key. `CODEX_AUTH_SEED` is Fernet ciphertext produced by:

```sh
CODEX_AUTH_STATE_KEY=... manual-test export-auth --input /secure/codex/auth.json --output auth-state.fernet
CODEX_AUTH_STATE_KEY=... manual-test decrypt-auth --input auth-state.fernet --output auth.json
CODEX_AUTH_STATE_KEY=... manual-test persist-auth --run-dir manual_test/reports/recording-toggle --output rotated.fernet
```

CI stores ciphertext only on the `codex-auth-state` branch. Credentialed runs are globally serialized and the matrix uses `max-parallel: 1`. ADB preflight happens before decryption. The rotated `CODEX_HOME/auth.json` is always re-encrypted and pushed; plaintext is mode 0600, temporary, excluded from artifacts, and never logged.

## CI trust and evidence

The untrusted PR workflow builds the APK and uploads YAML without credentials. The protected `workflow_run` discovers PRs through `commits/{head_sha}/pulls` and validates the workflow event/path, repository, default base, head repository and SHA, exactly one PR, trusted association, and current head. Stale runs are rejected.

Untrusted artifacts are downloaded below `runner.temp` and must have exact names, counts, bounds, and APK ZIP/manifest signatures. Every YAML test receives a fresh emulator. Missing or invalid YAML, failed prepare/build, missing reports, and stale inputs produce bounded fallback reports and commit status. Aggregated evidence is published on `manual-test-evidence`, embeds validated screenshot/XML bytes at immutable URLs, records the tested SHA, caps the sticky PR comment, and never executes artifact content. All actions use immutable commit pins.

Before any app matrix test, credentialed CI runs `manual-test pixel-probe` once against a packaged 256×256 solid-red PNG whose XML and `latest.png` path contain no color answer while the checklist expects blue. The same resumed Codex thread must capture, inspect, and submit `fail`. CI persists the probe’s rotated OAuth state, then runs every YAML app test serially on a fresh emulator. This probes native CLI image visibility rather than MCP `ImageContent`.
