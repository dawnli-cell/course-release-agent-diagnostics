package learning.agenterrors;

import java.util.Map;

public final class AgentFailureController {
    private final ReleaseFailureService service;

    public AgentFailureController(ReleaseFailureService service) {
        this.service = service;
    }

    public ControllerResponse recordBuild(ReleaseFailureService.BuildEvent event) {
        try {
            return new ControllerResponse(200, service.record(event));
        } catch (InfraiException error) {
            int status = error.httpStatus() >= 400 && error.httpStatus() < 500 ? error.httpStatus() : 502;
            return new ControllerResponse(status, Map.of("code", error.code(), "detail", error.detail()));
        }
    }

    public record ControllerResponse(int status, Object body) {}
}
