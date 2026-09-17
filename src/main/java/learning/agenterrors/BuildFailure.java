package learning.agenterrors;

import java.util.Objects;

public record BuildFailure(
        String courseId,
        String buildId,
        String agentStep,
        String exceptionType,
        String exceptionMessage,
        String stackTrace) {
    public BuildFailure {
        Objects.requireNonNull(courseId);
        Objects.requireNonNull(buildId);
        Objects.requireNonNull(agentStep);
        Objects.requireNonNull(exceptionType);
        Objects.requireNonNull(exceptionMessage);
        Objects.requireNonNull(stackTrace);
    }
}
