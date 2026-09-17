package learning.agenterrors;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ReleaseFailureService {
    private final ErrorCapturePort errors;

    public ReleaseFailureService(ErrorCapturePort errors) {
        this.errors = errors;
    }

    public ReleaseDiagnostic record(BuildEvent event) {
        if (event.outcome() == BuildOutcome.PASSED) {
            return new ReleaseDiagnostic(event.releaseId(), ReleaseState.READY, null, null,
                    "Build passed; release may continue");
        }

        BuildFailure failure = new BuildFailure(event.courseId(), event.buildId(), event.agentStep(),
                event.exceptionType(), event.exceptionMessage(), event.stackTrace());
        String idempotencyKey = UUID.nameUUIDFromBytes(
                (event.courseId() + ":" + event.buildId() + ":" + event.agentStep()).getBytes(StandardCharsets.UTF_8)
        ).toString();
        ErrorCapturePort.CaptureReceipt receipt = errors.capture(failure, idempotencyKey);
        return new ReleaseDiagnostic(event.releaseId(), ReleaseState.BLOCKED, receipt.eventId(),
                receipt.errorGroupId(), "Build failure captured; release remains blocked");
    }

    public enum BuildOutcome { PASSED, FAILED }
    public enum ReleaseState { READY, BLOCKED }

    public record BuildEvent(
            String courseId,
            String buildId,
            String releaseId,
            String agentStep,
            BuildOutcome outcome,
            String exceptionType,
            String exceptionMessage,
            String stackTrace) {}

    public record ReleaseDiagnostic(
            String releaseId,
            ReleaseState state,
            String eventId,
            String errorGroupId,
            String message) {}
}
