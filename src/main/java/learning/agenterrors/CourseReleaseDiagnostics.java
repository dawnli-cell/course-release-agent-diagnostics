package learning.agenterrors;

import java.util.Map;

import static learning.agenterrors.ReleaseFailureService.BuildOutcome.FAILED;

public final class CourseReleaseDiagnostics {
    private CourseReleaseDiagnostics() {}

    public static void main(String[] args) {
        AgentErrorConfig config = AgentErrorConfig.load(args, System.getenv());
        AgentFailureController controller = new AgentFailureController(
                new ReleaseFailureService(new InfraiErrorClient(config)));

        ReleaseFailureService.BuildEvent build = new ReleaseFailureService.BuildEvent(
                "course-java-101",
                "build-2026-09-13-01",
                "release-fall-cohort",
                "lesson-link-validator",
                FAILED,
                "IllegalStateException",
                "Lesson 7 has no published assessment",
                "java.lang.IllegalStateException: Lesson 7 has no published assessment\n"
                        + "\tat learning.pipeline.LessonValidator.validate(LessonValidator.java:42)"
        );

        AgentFailureController.ControllerResponse response = controller.recordBuild(build);
        Object body = response.body();
        if (body instanceof ReleaseFailureService.ReleaseDiagnostic diagnostic) {
            body = Map.of(
                    "releaseId", diagnostic.releaseId(),
                    "state", diagnostic.state().name(),
                    "eventId", diagnostic.eventId(),
                    "errorGroupId", diagnostic.errorGroupId(),
                    "message", diagnostic.message());
        }
        System.out.println(JsonCodec.encode(Map.of("status", response.status(), "diagnostic", body)));
    }
}
