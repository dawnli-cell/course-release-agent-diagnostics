package learning.agenterrors;

import java.util.Map;

public final class InfraiException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    private final Map<String, Object> detail;

    public InfraiException(String code, int httpStatus, Map<String, Object> detail) {
        super(code + ": " + detail);
        this.code = code;
        this.httpStatus = httpStatus;
        this.detail = Map.copyOf(detail);
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
    public Map<String, Object> detail() { return detail; }
}
