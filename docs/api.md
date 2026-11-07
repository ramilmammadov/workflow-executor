# API

The contract of the Workflow Executor API, version 1. The running service publishes the same contract as an
OpenAPI document at `/v3/api-docs`, browsable in Swagger UI at `/swagger-ui.html`.

## Contents

- [Conventions](#conventions)
- [Workflow definition](#workflow-definition)
- [Submit a run](#submit-a-run)
- [Get a run](#get-a-run)
- [Cancel a run](#cancel-a-run)
- [Run representation](#run-representation)
- [Errors](#errors)
- [Versioning](#versioning)

## Conventions

- **Base path:** `/api/v1`. Requests and responses use JSON (`application/json`); errors use
  `application/problem+json`.
- **Times** are ISO 8601 instants in UTC, for example `2026-11-06T20:31:57.901Z`.
- **Absent values:** a field without a value is omitted from responses, for example `closedAt` while a run is
  running.
- **Correlation:** every response carries an `X-Request-Id` header. The service reuses the caller's value when it
  is 1 to 64 letters, digits, `.`, `_` or `-`, and generates a UUID otherwise. Error bodies repeat it as
  `requestId`, and the service logs it with every line written for the request.
- **Idempotency:** `POST /workflow-runs` accepts an `Idempotency-Key` header. See [Submit a run](#submit-a-run).

## Workflow definition

```json
{
  "name": "order-fulfilment",
  "steps": [
    { "id": "validate-order", "type": "task" },
    { "id": "charge-card", "type": "task", "dependsOn": ["validate-order"], "retry": { "maxAttempts": 4 } },
    { "id": "fraud-hold", "type": "delay", "dependsOn": ["charge-card"], "delayMs": 3000 }
  ]
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `name` | string | no | Up to 100 characters. Shown on the run and in the Temporal Web UI. |
| `steps` | array of steps | yes | 1 to 100 steps. |

The whole request body may be at most 1,000,000 bytes.

Step fields:

| Field | Type | Applies to | Required | Default | Rules |
|---|---|---|---|---|---|
| `id` | string | all | yes | | 1 to 64 letters, digits, `.`, `_` or `-`, starting with a letter or digit. Unique in the definition. |
| `type` | string | all | yes | | `task` or `delay`. |
| `dependsOn` | array of step ids | all | no | `[]` | Up to 100 ids. Every id must exist. No self-dependency, no repeats, no cycles. |
| `retry.maxAttempts` | integer | task | no | 3 | 1 to 10. Counts the first attempt, so 1 means no retry. |
| `timeoutMs` | integer | task | no | 30000 | 100 to 300000. Limit for one attempt. |
| `delayMs` | integer | delay | yes | | 1 to 3600000. |
| `simulate.workMs` | integer | task | no | 0 | 0 to 60000. Simulated work per attempt. |
| `simulate.failFirstAttempts` | integer | task | no | 0 | 0 to 10. These first attempts fail with a retryable error. |
| `simulate.failPermanently` | boolean | task | no | `false` | Every attempt fails with a non-retryable error. |

Rules that apply to the whole definition:

- A field that does not apply to the step's type is rejected, for example `delayMs` on a task step.
- Unknown fields are rejected, so a misspelt field fails instead of being ignored.
- The delays along any chain of dependencies must add up to less than the execution timeout (24 hours by
  default), because such a run could never finish. Task durations are unknown in advance and are not counted.
- Retries back off exponentially: the first retry waits 1 second, each further wait doubles, up to 30 seconds.
  These are server settings.
- The service reports every violation in one response, listing at most 100 of them.

Tasks are simulated: the `simulate` block exists to exercise retries, timeouts and failures.

## Submit a run

`POST /api/v1/workflow-runs`

Validates the definition and starts a run. The run continues in the background; follow it with
[Get a run](#get-a-run).

| Header | Required | Meaning |
|---|---|---|
| `Idempotency-Key` | no, recommended | 1 to 255 printable ASCII characters without spaces, chosen by the client. |

With an `Idempotency-Key`:

- The first request starts the run.
- A repeat with the same key and the same body starts nothing and returns the original run with `200` and
  `Idempotent-Replayed: true`. This also holds for concurrent requests: exactly one starts the run.
- A request with the same key and a different body is rejected with `422`.
- The service remembers a key for as long as Temporal retains the run (24 hours on the local development
  server). After that, the key starts a new run.

Without a key, every request starts a new run. Send a key whenever the request may be retried: after a timeout
or a `503`, the first request may already have started the run.

| Status | Meaning | Headers |
|---|---|---|
| `202 Accepted` | Run started. The body is the run with every step `PENDING`; `createdAt` is absent if the service could not read it back. | `Location` |
| `200 OK` | Replay of an earlier request with the same key and body. The body is the run's current state. | `Location`, `Idempotent-Replayed: true` |
| `400 Bad Request` | Invalid definition, malformed body or invalid key. | |
| `413 Content Too Large` | The body is over 1,000,000 bytes. | |
| `422 Unprocessable Content` | The key was already used with a different body. | |
| `503 Service Unavailable` | Temporal is unreachable. Retry later with the same key. | `Retry-After` |

## Get a run

`GET /api/v1/workflow-runs/{runId}`

Returns the run and the current state of every step.

| Status | Meaning | Headers |
|---|---|---|
| `200 OK` | The run. | |
| `404 Not Found` | No run with this id. | |
| `503 Service Unavailable` | Temporal is unreachable, or no worker answered the progress query in time. | `Retry-After` |

## Cancel a run

`POST /api/v1/workflow-runs/{runId}/cancel`

Cancellation is asynchronous. Running steps are asked to stop and steps that have not started never start.
Poll the run to see it reach `CANCELLED`. If the request reaches a run whose last steps are just finishing, the
run still ends `CANCELLED`, and those steps keep their real final state.

Cancellation is a `POST` on a sub-resource rather than a `DELETE` of the run, because the run and its history
stay readable afterwards.

| Status | Meaning | Headers |
|---|---|---|
| `202 Accepted` | Cancellation requested. The body is the run as it is now, usually still `RUNNING`. | |
| `200 OK` | The run was already cancelled. | |
| `404 Not Found` | No run with this id. | |
| `409 Conflict` | The run already finished with another status. | |
| `503 Service Unavailable` | Temporal is unreachable. | `Retry-After` |

## Run representation

```json
{
  "id": "2c2a0b4d-501e-456a-a5da-806c1ce7a04b",
  "name": "card-declined",
  "status": "FAILED",
  "createdAt": "2026-11-06T20:38:00.331Z",
  "closedAt": "2026-11-06T20:38:00.384Z",
  "steps": [
    { "id": "charge-card", "type": "task", "dependsOn": [], "status": "FAILED", "attempts": 1,
      "startedAt": "2026-11-06T20:38:00.344Z", "finishedAt": "2026-11-06T20:38:00.377Z",
      "failure": { "type": "SimulatedPermanentFailure", "message": "Simulated permanent failure",
                   "retryState": "NON_RETRYABLE_FAILURE" } },
    { "id": "ship", "type": "task", "dependsOn": ["charge-card"], "status": "SKIPPED",
      "finishedAt": "2026-11-06T20:38:00.377Z", "statusReason": "Dependency 'charge-card' finished as FAILED" }
  ]
}
```

| Field | Meaning |
|---|---|
| `id` | Run id. Also the Temporal workflow id, so the run is easy to find in the Temporal Web UI. |
| `name` | The definition's name, when it has one. |
| `status` | Run status, see below. |
| `createdAt`, `closedAt` | When the run started and finished. `closedAt` is absent while the run is open. |
| `steps` | Every step, in definition order. Absent for a run that ended as `TERMINATED`, because Temporal cannot rebuild a terminated run's step state. Also absent in the answer to a write, an idempotent replay or a cancellation, when no worker could report step state; that write has still taken effect, and a read answers `503` in that situation instead. |
| `steps[].attempts` | Attempts a task step made. Set once the step has finished. |
| `steps[].startedAt`, `steps[].finishedAt` | When the step started and finished. |
| `steps[].failure` | Set on a failed step: the failure `type`, its `message`, and `retryState`, the reason Temporal stopped retrying (`MAXIMUM_ATTEMPTS_REACHED`, `NON_RETRYABLE_FAILURE`, `TIMEOUT`, `CANCEL_REQUESTED`). |
| `steps[].statusReason` | Set on a skipped or cancelled step: why. |

| Run status | Meaning |
|---|---|
| `RUNNING` | Steps are still running or waiting. |
| `SUCCEEDED` | Every step succeeded. |
| `FAILED` | At least one step failed. The other branches ran to completion first. |
| `CANCELLED` | The run was cancelled. Steps that finished before the cancellation took effect keep their state. |
| `TIMED_OUT` | The run exceeded the execution timeout (24 hours by default). |
| `TERMINATED` | An operator terminated the run in Temporal. Such a run has no step detail. |
| `PAUSED` | An operator paused the run in Temporal. |

| Step status | Meaning |
|---|---|
| `PENDING` | Waiting for its dependencies. |
| `RUNNING` | A task attempt is running or waiting to retry, or a delay is counting down. |
| `SUCCEEDED` | Finished successfully. |
| `FAILED` | Failed with a non-retryable error, or used up its attempts. |
| `SKIPPED` | Never started, because a dependency did not succeed. |
| `CANCELLED` | Stopped, or never started, because the run was cancelled or timed out. `statusReason` says which. A running task step becomes `CANCELLED` only once its task has stopped. |

## Errors

Every error is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem response:

```json
{
  "type": "urn:workflow-executor:problem:invalid-definition",
  "title": "Invalid workflow definition",
  "status": 400,
  "detail": "The workflow definition breaks one or more rules",
  "instance": "/api/v1/workflow-runs",
  "errors": [
    { "field": "steps[1].dependsOn[0]", "code": "UNKNOWN_DEPENDENCY",
      "message": "Step 'charge-card' depends on unknown step 'validate'" }
  ],
  "requestId": "4680b985-6f36-4140-a290-c52fbac9c923"
}
```

Clients should branch on `type`, which is stable. `title` and `detail` are for people. `errors` appears when
specific fields are at fault; each entry has the field's path, a machine-readable `code` and a message. At most
100 entries are listed, and `detail` says when there are more.

A failed step's `failure.type` is either a type the task declared, `Timeout`, or `UnexpectedError`. Unexpected
errors never expose internal detail to clients; the worker log has it.

| Status | `type` (after `urn:workflow-executor:problem:`) | When |
|---|---|---|
| 400 | `invalid-definition` | A field breaks a rule, or the definition breaks a graph rule. |
| 400 | `malformed-request` | The body is not JSON, has an unknown field, or has a value of the wrong JSON type. |
| 400 | `invalid-idempotency-key` | The `Idempotency-Key` header is not 1 to 255 printable ASCII characters without spaces. |
| 413 | `request-too-large` | The request body is over 1,000,000 bytes. |
| 404 | `run-not-found` | No run with this id. |
| 409 | `run-not-cancellable` | The run already finished with a status other than `CANCELLED`. |
| 422 | `idempotency-key-reused` | The `Idempotency-Key` was already used with a different body. |
| 503 | `engine-unavailable` | Temporal is unreachable, or no worker answered in time. Comes with `Retry-After`. |
| 500 | `internal-error` | An unexpected error. The body never contains internal details; quote the `requestId`. |

Errors raised by the web framework itself, such as an unknown path (`404`), a wrong method (`405`) or a wrong
content type (`415`), use the same format and include `requestId`. They carry no `type`, which RFC 9457 defines
as `about:blank`: the status code says it all.

Codes in `errors[].code`:

| Code | Meaning |
|---|---|
| `INVALID_FIELD` | A field is missing, too long, out of range or badly formatted. |
| `UNKNOWN_FIELD` | The body contains a field the API does not define. |
| `INVALID_VALUE` | A value has the wrong JSON type. |
| `UNSUPPORTED_STEP_TYPE` | `type` is not `task` or `delay`. |
| `MISSING_FIELD` | A field required by the step's type is missing. |
| `FIELD_NOT_ALLOWED` | A field does not apply to the step's type. |
| `DUPLICATE_STEP_ID` | Two steps share an id. |
| `UNKNOWN_DEPENDENCY` | `dependsOn` names a step that does not exist. |
| `SELF_DEPENDENCY` | A step depends on itself. |
| `DUPLICATE_DEPENDENCY` | `dependsOn` lists the same step twice. |
| `DEPENDENCY_CYCLE` | Steps depend on each other in a cycle; the message names them. |
| `EXCEEDS_EXECUTION_TIMEOUT` | The delays along a chain of dependencies add up to the execution timeout or more. |

## Versioning

The major version is part of the path. Within `v1`, changes are additive only: new optional request fields, new
response fields, new status values and new problem types. Clients should ignore response fields they do not know
and treat an unknown run status as not finished. A breaking change gets a new path, `/api/v2`, served alongside
`v1` until clients have moved.
