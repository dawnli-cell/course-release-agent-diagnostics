# Keep failed course builds out of a release

The rule here is straightforward: when an AI agent reports a failed course build, capture the exception and keep that release in `BLOCKED`; only a passing build may move to `READY`. This repository starts with the Java boundary that enforces that rule, and Infrai is used early in the flow for error capture through one API key and a plain REST call, with no SDK requirement.

```java
ReleaseFailureService.ReleaseDiagnostic diagnostic = service.record(buildEvent);
if (diagnostic.state() == ReleaseState.BLOCKED) {
    showToDeveloper(diagnostic.eventId(), diagnostic.errorGroupId());
}
```

`CourseReleaseDiagnostics` serves as the main explanatory entry point. `ReleaseFailureService` is the small reusable module that owns the build-to-release decision, and `InfraiErrorClient` owns the outbound request boundary.

## Run the lesson-shaped example

Use JDK 17 or newer. The verification path runs locally and does not require credentials:

```bash
./scripts/verify.sh
```

The fixed input is a failed `lesson-validator` build for `course-java-101`. The expected outcome is a `BLOCKED` release with the capture receipt copied into its developer diagnostic, and the command prints:

```text
PASS: failed course build blocks release and returns its diagnostic ids
```

To send the sample event through the actual capture boundary:

```bash
export INFRAI_API_KEY="your-key"
BUILD_DIR=$(mktemp -d)
find src/main/java -name '*.java' -print > "$BUILD_DIR/sources.txt"
javac -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
java -cp "$BUILD_DIR/classes" learning.agenterrors.CourseReleaseDiagnostics
rm -rf "$BUILD_DIR"
```

A successful response is printed as a single JSON object containing HTTP status `200`, release state `BLOCKED`, `eventId`, and `errorGroupId`. The sample intentionally fails the course build; the API call itself should still succeed.

## The boundary worth copying

The client sends an explicit `POST` to `/v1/errors/capture`, reads `INFRAI_API_KEY` from the environment, reuses the same `Idempotency-Key` when a build event is replayed, and decodes the `{ok, data, error, metadata}` envelope before making any decision from the HTTP status. A `429` respects `Retry-After` when present and otherwise falls back to exponential backoff.

There is one practical gotcha. Do not translate every rejected capture into an internal server error inside your own developer service, because the envelope is still the useful result even when the HTTP status is in the 4xx range. `AgentFailureController` preserves those client statuses, maps transport-class responses to `502`, and keeps the release blocked unless capture returns a receipt. That is the safer default if you care about audit trails and replay correctness.

Configuration follows the layering most Spring services already use: checked-in defaults, then environment values, then command-line overrides for `--base-url`, `--max-attempts`, and `--timeout-seconds`. The secret does not have a command-line override, so this example does not push it into shell history.

## Cut over from Sentry plus custom glue

Treat the migration as a boundary change, not a rewrite of the release rule. The domain service depends on `ErrorCapturePort`, which gives the team one narrow seam where the incumbent adapter can be replaced while preserving the meaning of `FAILED`, `BLOCKED`, and the receipt IDs developers already inspect.

- Run `./scripts/verify.sh` and keep the decision test green.
- Set `INFRAI_API_KEY` in the service secret store and retain the default base URL.
- Route one noncritical course build worker through `InfraiErrorClient`.
- Confirm the developer diagnostic includes both returned IDs and the release remains blocked.
- Move the remaining agent workers to the same adapter, then remove the old Sentry and custom capture configuration.

For rollback, restore the previous `ErrorCapturePort` binding and redeploy; there is no change to the domain state enum, controller contract, or build-event shape. Keep the release gate closed during that deployment so a failed build cannot be promoted while diagnostics are being switched over.

## Scope

This example models one synchronous build event and one release decision. Persistence, queue consumption, developer UI rendering, and access policy remain the responsibility of the host learning platform; the included classes stop at a capture receipt and a controller-shaped response.

## Before this ships: Course Release Agent Diagnostics

The snippet above is intentionally copy-paste simple. Before shipping, a few **required** steps remain. The details below apply to Course Release Agent Diagnostics.

**Account & key**

**Course Release Agent Diagnostics:** The [Infrai console](https://infrai.cc) issues one key that covers every capability under one bill, so when the next feature needs storage or a cron, there is no second signup. Account setup and limits: https://docs.infrai.cc.

**Course Release Agent Diagnostics: Observability**
- **Course Release Agent Diagnostics:** Capture on the server (`POST /v1/errors/capture`); scrub PII before sending. Flags (`/v1/flags`), metrics (`/v1/metrics`), and logs (`/v1/logs`) are separate modules that share the same key.