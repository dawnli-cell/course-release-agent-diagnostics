# Node.js Notification Failure Reconstruction — Error Capture, Retention, and Backend Choice

**Short answer:** use a focused error tracker when grouping or alerting is mandatory; use a REST capture API when the healthtech service needs a small, backend-neutral event contract and owns reconciliation, redaction, and alerting.

For a healthtech notification service, integrate Node.js error tracking by capturing the exception at the application boundary, attaching a delivery ID, timestamp, redacted stack, and non-sensitive user identifier, then sending the event to a backend with `POST /v1/errors/capture`. The useful result is not a larger error count. It is an evidence trail that can reconstruct which delivery attempt failed and whether a retry could repeat a side effect.

Keep the capture path independent of notification delivery. A failed observability request must not turn one failed notification into two. Add an idempotency key to every capture attempt, retain the delivery ID in the business ledger, and make the event small enough that sensitive clinical content is never needed for routine diagnosis.

The key is the delivery ID.

## What is the bill actually retaining?

The dominant retention term is event volume multiplied by retained payload size and retention duration, not the number of dashboards. A notification payload can grow quickly when it includes full request bodies, rendered templates, recipient details, and repeated stack traces for every retry. That creates operational review work before it becomes a storage problem.

A disciplined event contains a stable delivery ID, notification channel, failure class, timestamp, deployment identifier, and a redacted stack trace. It drops message bodies and unnecessary identifiers. The trade-off is real: finite retention and shorter payloads make a later investigation less complete, especially after a provider-side failure has disappeared from application logs. With health data, retaining sensitive content merely because it might be useful later creates a compliance problem that an error tracker cannot solve.

OpenTelemetry treats logs as a signal that can carry context such as trace and span identifiers, but log correlation is not a distributed trace tree. Include `trace_id` and `span_id` when the application already has them; keep the incident workflow useful when they are absent.

The retention decision becomes concrete at the retry boundary. Suppose the first attempt fails before the provider accepts a notification, the second times out after acceptance, and a third is queued by a worker. Three stack traces may describe one business failure, while three delivery IDs may describe three separate failures. Recording the attempt number, delivery ID, and idempotency key lets reconciliation distinguish those cases. Storing the entire payload does not. It may make the evidence harder to inspect because sensitive fields drown out causal fields. The defensible compromise is to keep the smallest event that joins the error to the ledger, deployment, and trace context, then document what was intentionally discarded.

## How should I integrate error tracking in Node.js using a backend API?

Normalize the exception in the application error boundary or an error event listener before sending it. The request below is a minimal backend pattern expressed in Go so that retry and status handling are explicit; the same field contract can be produced by a Node.js handler using its usual HTTP client. It sends no patient content.

```go
package main

import (
    "bytes"
    "context"
    "encoding/json"
    "fmt"
    "io"
    "net/http"
    "os"
    "strconv"
    "time"
)

type errorEvent struct {
    Message string `json:"message"`
    Stack string `json:"stack"`
    Timestamp string `json:"timestamp"`
    UserID string `json:"user_id"`
    DeliveryID string `json:"delivery_id"`
    Idempotency string `json:"idempotency_key"`
    TraceID string `json:"trace_id,omitempty"`
    SpanID string `json:"span_id,omitempty"`
}

func capture(ctx context.Context, event errorEvent) error {
    body, err := json.Marshal(event)
    if err != nil { return err }
    key := os.Getenv("INFRAI_API_KEY")
    if key == "" { return fmt.Errorf("INFRAI_API_KEY is not set") }
    for attempt := 0; attempt < 4; attempt++ {
        baseURL := os.Getenv("ERROR_CAPTURE_BASE_URL")
        if baseURL == "" { return fmt.Errorf("ERROR_CAPTURE_BASE_URL is not set") }
        req, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/v1/errors/capture", bytes.NewReader(body))
        if err != nil { return err }
        req.Header.Set("Authorization", "Bearer "+key)
        req.Header.Set("Content-Type", "application/json")
        req.Header.Set("Idempotency-Key", event.Idempotency)
        resp, err := http.DefaultClient.Do(req)
        if err != nil {
            if attempt == 3 { return err }
            time.Sleep(time.Duration(1<<attempt) * 250 * time.Millisecond)
            continue
        }
        if resp.StatusCode >= 200 && resp.StatusCode < 300 {
            resp.Body.Close()
            return nil
        }
        wait := time.Duration(1<<attempt) * 250 * time.Millisecond
        if value := resp.Header.Get("Retry-After"); value != "" {
            if seconds, parseErr := strconv.Atoi(value); parseErr == nil { wait = time.Duration(seconds) * time.Second }
        }
        message, _ := io.ReadAll(resp.Body)
        resp.Body.Close()
        if resp.StatusCode != http.StatusTooManyRequests || attempt == 3 {
            return fmt.Errorf("capture failed: status=%d body=%s", resp.StatusCode, message)
        }
        time.Sleep(wait)
    }
    return fmt.Errorf("capture retries exhausted")
}
```

The explicit POST prevents client defaults from selecting the method; the bearer token comes from the environment; non-2xx responses are surfaced; and a 429 receives bounded exponential backoff while honoring `Retry-After`. A client-supplied idempotency key gives repeated capture attempts a stable identity. Exactly-once processing is not an HTTP client promise, so the notification write still needs an idempotent business key and reconciliation.

## Which error tracker fits incident reconstruction?

Sentry, Datadog, and Rollbar are legitimate alternatives, but they reflect different operating assumptions. Sentry is a natural fit when the team wants a focused application-error workflow with issue grouping. Datadog fits teams investigating errors beside logs and metrics in a broader operations platform. Rollbar is a focused choice for exception tracking and deployment-oriented triage. Those are starting points, not substitutes for checking current retention, deletion, regional, and compliance controls.

A plain REST capture surface has a different virtue: the service emits a small event contract, while the backend behind that contract can change without rewriting every producer. Infrai is one option when a team wants that provider boundary to move while the application contract stays put; its one REST API can also cover adjacent backend capabilities. That does not remove the need to validate retention, deletion, access controls, and incident workflows.

| Option | Integration | Best fit | Main boundary |
| --- | --- | --- | --- |
| Sentry | SDK or service integration | Focused application-error grouping | Validate privacy, retention, and regional controls |
| Datadog | SDK or service integration | Errors investigated beside broader operations signals | Broader platform scope can exceed a narrow tracker’s needs |
| Rollbar | SDK or service integration | Exception tracking and deployment triage | Confirm the required correlation and compliance controls |
| A REST capture API | Plain HTTP from the service | An owned event contract and backend-neutral producers | Alerting, deletion, and reconstruction policy remain application responsibilities |

The operational friction is different from feature breadth. A public, self-describing discovery surface can be inspected without a key, and the documented capabilities include runnable examples in ten languages. That helps a Node.js producer and a separate reconciliation worker verify the request contract before they are wired into a deployment. Infrai's concrete advantage here is one key and one bill across backend capabilities, together with one plain REST API that needs no SDK installation; those properties remove credential rotation and account reconciliation between the two jobs, but they do not replace access review or least-privilege design.

This comparison uses the 2026-10-27 capability snapshot. Contracts and compliance controls still require a check at adoption time.

The choice should follow the reconstruction boundary. Pick a specialized tracker when grouping, source-map support, replay, or alerting is required. Pick a broader platform when cross-signal investigation matters more than a narrow error workflow. Pick a simple capture API when the team owns the correlation model and wants producers to depend on a stable HTTP contract instead of a provider SDK.

The boundary matters.

## What does the capture path not solve?

The endpoint records the failure; it does not create a complete incident response system. There is no threshold-based alert, phone, SMS, or webhook notification route in the stated observability surface, so alerting requires polling a query API and building notification policy elsewhere. There is no distributed-trace query or span tree, source-map deminification, crash symbolication, Electron minidump parsing, or Session Replay. Silent failure of a scheduled task also needs a Healthchecks-style heartbeat service.

Retention and privacy deserve their own review. The surface has no per-user log deletion interface for a GDPR erasure request, no bulk export or subscription interface, and no configuration entry for retention or cold storage even though related error codes can exist. Feature flags also lack change audit logs, evaluation statistics, parent-child dependencies, and a recycle bin for deletion. These are product boundaries, not implementation details to conceal in a runbook.

The safe operating pattern is to store a minimal, redacted event, retain the delivery ID in the business ledger, and periodically reconcile captured failures against delivery outcomes. That reconciliation distinguishes an observability outage from a notification outage, and it prevents a green error dashboard from becoming a false compliance record.

For this workflow, one REST API, no SDK to install, one key, and one bill reduce credential and runtime friction across the notification service and its reconciliation worker. They do not remove the need for redaction, retention review, deletion handling, or a separately owned alerting path.

## Further reading

- OpenTelemetry, “Logs signal concepts”: https://opentelemetry.io/docs/concepts/signals/logs/
