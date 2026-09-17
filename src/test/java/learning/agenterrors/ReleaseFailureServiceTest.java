package learning.agenterrors;

import java.util.concurrent.atomic.AtomicReference;

import static learning.agenterrors.ReleaseFailureService.BuildOutcome.FAILED;
import static learning.agenterrors.ReleaseFailureService.ReleaseState.BLOCKED;

public final class ReleaseFailureServiceTest {
    public static void main(String[] args) {
        AtomicReference<BuildFailure> captured = new AtomicReference<>();
        AtomicReference<String> requestKey = new AtomicReference<>();
        ErrorCapturePort recorder = (failure, idempotencyKey) -> {
            captured.set(failure);
            requestKey.set(idempotencyKey);
            return new ErrorCapturePort.CaptureReceipt("evt_test_7", "group_lesson_validator");
        };

        ReleaseFailureService service = new ReleaseFailureService(recorder);
        ReleaseFailureService.BuildEvent event = new ReleaseFailureService.BuildEvent(
                "course-java-101", "build-77", "release-september", "lesson-validator", FAILED,
                "IllegalStateException", "Assessment is missing", "stack line");

        ReleaseFailureService.ReleaseDiagnostic first = service.record(event);
        ReleaseFailureService.ReleaseDiagnostic replay = service.record(event);

        check(first.state() == BLOCKED, "a failed build must block its release");
        check(first.eventId().equals("evt_test_7"), "the diagnostic must expose the captured event");
        check(captured.get().courseId().equals("course-java-101"), "course context must reach capture");
        check(requestKey.get() != null && !requestKey.get().isBlank(), "capture needs an idempotency key");
        check(first.errorGroupId().equals(replay.errorGroupId()), "replaying the build keeps the same diagnosis");
        System.out.println("PASS: failed course build blocks release and returns its diagnostic ids");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
