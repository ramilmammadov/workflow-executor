# Instructions for coding agents

This file is for AI coding agents (Claude Code, Codex, Cursor) working in this repository. People should start
with the [README](README.md).

## Commands

- Build and run every test: `./mvnw verify` (JDK 25). The tests need no running infrastructure.
- Run one test class: `./mvnw test -Dtest=DagWorkflowTest`.
- Run the service: `docker compose up --build`. The API listens on port 8080, the Temporal Web UI on 8233.

## Layout

| Package | Holds | Must not |
|---|---|---|
| `definition` | Request types, validation, mapping to the workflow input | Depend on any other package, on Temporal or on Spring |
| `execution` | The Temporal workflow and activities | Contain non-deterministic code (see below) |
| `run` | Starting, reading and cancelling runs, idempotency | Know about HTTP |
| `api` | Controller, problem responses, request id filter | Contain business rules |
| `config` | Configuration properties, Temporal wiring, the definition beans | |

## Rules for workflow code

`DagWorkflowImpl` and everything it calls are replayed from history, so they must decide the same way every time.

- Never use `System.currentTimeMillis`, `Instant.now`, `Thread.sleep`, threads, random values or I/O. Use
  `Workflow.currentTimeMillis`, `Workflow.newTimer`, `Async` and `Promise`.
- Iterate only ordered collections, such as lists and `LinkedHashMap`.
- Read settings from the workflow input, never from Spring beans or configuration.
- Log through `Workflow.getLogger`.
- Never rename the workflow type, the activity type or the query: run histories record those names.
- After any change to workflow code, run `DagWorkflowTest` and `ReplayCompatibilityTest`. If the second one
  fails, the change breaks runs in flight: guard it with `Workflow.getVersion` instead of changing the logic in
  place.

## Conventions

- A failure the client can act on is a typed exception thrown by the service and mapped once, in
  `ApiExceptionHandler`, to a `ProblemType`. A new problem type changes the API contract: document it in
  `docs/api.md`.
- Limits that are part of the contract are constants in `DefinitionLimits`. Server defaults are configuration.
- Every behaviour change comes with a test. Tests never sleep; they poll with Awaitility and an upper bound.
- Keep `README.md`, `docs/design.md` and `docs/api.md` in step with the code. Diagrams describe what the code
  does, not what it might do.
- `ArchitectureTest` enforces the package rules above and the determinism rules for workflow code. Run it after
  moving code between packages.
- Small functions, early returns, descriptive names, named constants, no commented-out code.
