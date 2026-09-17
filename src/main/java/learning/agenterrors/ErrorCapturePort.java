package learning.agenterrors;

public interface ErrorCapturePort {
    CaptureReceipt capture(BuildFailure failure, String idempotencyKey);

    record CaptureReceipt(String eventId, String errorGroupId) {}
}
