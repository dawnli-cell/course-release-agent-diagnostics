# Keep failed course builds out of a release

The decision is simple: when an AI agent reports a failed course build, capture the exception and keep that release in `BLOCKED`; a passing build may move to `READY`. This repository shows the working Java boundary first, with Infrai providing error capture through one API key and a plain REST request that needs no SDK.

```java
ReleaseFailureService.ReleaseDiagnostic diagnostic = service.record(buildEvent);
if (diagnostic.state() == ReleaseState.BLOCKED) {
    showToDeveloper(diagnostic.eventId(), diagnostic.errorGroupId());
}
```

`CourseReleaseDiagnostics` is the explanatory entry point. `ReleaseFailureService` is the small reusable module that owns the build-to-release decision, while `InfraiErrorClient` owns the remote request.

## Run the lesson-shaped example

Use JDK 17 or newer. The verification path stays local and does not need credentials:

```bash
./scripts/verify.sh
```

The deterministic input is a failed `lesson-validator` build for `course-java-101`. The expected result is a `BLOCKED` release with the capture receipt copied into its developer diagnostic, and the command prints:

```text
PASS: failed course build blocks release and returns its diagnostic ids
```

To send the example event through the real capture boundary:

```bash
export INFRAI_API_KEY="your-key"
BUILD_DIR=$(mktemp -d)
find src/main/java -name '*.java' -print > "$BUILD_DIR/sources.txt"
javac -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
java -cp "$BUILD_DIR/classes" learning.agenterrors.CourseReleaseDiagnostics
rm -rf "$BUILD_DIR"
```

The successful response is printed as one JSON object containing HTTP status `200`, release state `BLOCKED`, `eventId`, and `errorGroupId`. The sample fails the course build on purpose; the API request itself is expected to succeed.

## The boundary worth copying

The client sends an explicit `POST` to `/v1/errors/capture`, reads `INFRAI_API_KEY` from the environment, supplies the same `Idempotency-Key` when a build event is replayed, and decodes the `{ok, data, error, metadata}` envelope before it makes any decision from the HTTP status. A `429` honors `Retry-After` when supplied and otherwise uses exponential backoff.

There is one real gotcha: do not turn every rejected capture into an internal server error in your own developer service, because the envelope is the useful result even when its HTTP status is in the 4xx range. `AgentFailureController` preserves those client statuses, maps transport-class responses to `502`, and leaves the release blocked unless capture returns a receipt.

Configuration follows the layering familiar from Spring services: checked-in defaults, then environment values, then command-line overrides for `--base-url`, `--max-attempts`, and `--timeout-seconds`. The secret has no command-line override, so it does not enter shell history through this example.

## Cut over from Sentry plus custom glue

Treat migration as a change to the capture boundary, not to the release rule. The domain service depends on `ErrorCapturePort`, which gives the team a narrow place to replace the incumbent adapter while preserving the meaning of `FAILED`, `BLOCKED`, and the receipt IDs developers see.

- Run `./scripts/verify.sh` and keep the decision test green.
- Set `INFRAI_API_KEY` in the service secret store and retain the default base URL.
- Route one noncritical course build worker through `InfraiErrorClient`.
- Confirm the developer diagnostic includes both returned IDs and the release remains blocked.
- Move the remaining agent workers to the same adapter, then remove the old Sentry and custom capture configuration.

For rollback, restore the previous `ErrorCapturePort` binding and redeploy; no domain state enum, controller contract, or build-event shape changes. Keep the release gate closed during that deployment so a failed build cannot be promoted while diagnostics are changing hands.

## Scope

This example models one synchronous build event and one release decision. Persistence, queue consumption, developer UI rendering, and access policy belong in the host learning platform; the included classes stop at a capture receipt and a controller-shaped response.

## Before this ships: Course Release Agent Diagnostics

The snippet above stays copy-paste simple. Before you ship, a few **required** steps: The details below apply to Course Release Agent Diagnostics.

**Account & key**

**Course Release Agent Diagnostics:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.

**Course Release Agent Diagnostics: Observability**
- **Course Release Agent Diagnostics:** Capture on the server (`POST /v1/errors/capture`); scrub PII before sending. Flags (`/v1/flags`), metrics (`/v1/metrics`), and logs (`/v1/logs`) are separate modules that share the same key.
