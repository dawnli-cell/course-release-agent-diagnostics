package learning.agenterrors;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InfraiErrorClient implements ErrorCapturePort {
    private final AgentErrorConfig config;
    private final HttpClient http;
    private final Sleeper sleeper;

    public InfraiErrorClient(AgentErrorConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build(), Thread::sleep);
    }

    InfraiErrorClient(AgentErrorConfig config, HttpClient http, Sleeper sleeper) {
        this.config = config;
        this.http = http;
        this.sleeper = sleeper;
    }

    @Override
    public CaptureReceipt capture(BuildFailure failure, String idempotencyKey) {
        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("type", failure.exceptionType());
        exception.put("value", failure.exceptionMessage());
        exception.put("stacktrace", failure.stackTrace());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exception", exception);
        Map<String, Object> data = postCapture(payload, idempotencyKey);
        return new CaptureReceipt(text(data, "event_id"), text(data, "error_group_id"));
    }

    // Canonical capability: infrai.errors.capture
    private Map<String, Object> postCapture(Map<String, Object> payload, String idempotencyKey) {
        for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
            HttpRequest request = HttpRequest.newBuilder(config.baseUri().resolve("/v1/errors/capture"))
                    .timeout(config.timeout())
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .method("POST", HttpRequest.BodyPublishers.ofString(JsonCodec.encode(payload)))
                    .build();
            HttpResponse<String> response = send(request);
            Map<String, Object> envelope = decodeEnvelope(response.body(), response.statusCode());

            if (response.statusCode() == 429 && attempt + 1 < config.maxAttempts()) {
                sleep(retryDelay(response, attempt));
                continue;
            }

            Object ok = envelope.get("ok");
            if (!Boolean.TRUE.equals(ok)) {
                Map<String, Object> error = object(envelope.get("error"));
                throw new InfraiException(textOr(error, "code", "INFRAI_REJECTION"), response.statusCode(), error);
            }
            if (response.statusCode() >= 500) {
                throw new InfraiException("INFRAI_SERVICE_RESPONSE", response.statusCode(), Map.of("status", response.statusCode()));
            }
            return object(envelope.get("data"));
        }
        throw new InfraiException("RATE_LIMITED", 429, Map.of("message", "Retry budget exhausted"));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new InfraiException("TRANSPORT_ERROR", 503, Map.of("message", exception.getMessage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfraiException("INTERRUPTED", 503, Map.of("message", "Request interrupted"));
        }
    }

    private static Map<String, Object> decodeEnvelope(String body, int status) {
        try {
            return JsonCodec.decodeObject(body);
        } catch (IllegalArgumentException exception) {
            throw new InfraiException("INVALID_ENVELOPE", status, Map.of("message", exception.getMessage()));
        }
    }

    private Duration retryDelay(HttpResponse<?> response, int attempt) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("").trim();
        if (!retryAfter.isEmpty()) {
            try { return Duration.ofSeconds(Math.max(0, Long.parseLong(retryAfter))); }
            catch (NumberFormatException ignored) {
                try {
                    long millis = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis();
                    return Duration.ofMillis(Math.max(0, millis));
                } catch (RuntimeException ignoredDate) {
                    // Continue with exponential backoff.
                }
            }
        }
        return Duration.ofMillis(250L * (1L << attempt));
    }

    private void sleep(Duration duration) {
        try { sleeper.sleep(duration.toMillis()); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfraiException("INTERRUPTED", 503, Map.of("message", "Backoff interrupted"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Response data is missing " + key);
        return value.toString();
    }

    private static String textOr(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value.toString();
    }

    @FunctionalInterface
    interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }
}
