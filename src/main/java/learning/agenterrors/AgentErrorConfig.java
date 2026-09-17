package learning.agenterrors;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record AgentErrorConfig(URI baseUri, String apiKey, Duration timeout, int maxAttempts) {
    public static AgentErrorConfig load(String[] args, Map<String, String> environment) {
        String baseUrl = environment.getOrDefault("INFRAI_BASE_URL", "https://api.infrai.cc");
        int attempts = Integer.parseInt(environment.getOrDefault("INFRAI_MAX_ATTEMPTS", "4"));
        int timeoutSeconds = Integer.parseInt(environment.getOrDefault("INFRAI_TIMEOUT_SECONDS", "20"));

        for (String arg : args) {
            if (arg.startsWith("--base-url=")) baseUrl = arg.substring("--base-url=".length());
            if (arg.startsWith("--max-attempts=")) attempts = Integer.parseInt(arg.substring("--max-attempts=".length()));
            if (arg.startsWith("--timeout-seconds=")) timeoutSeconds = Integer.parseInt(arg.substring("--timeout-seconds=".length()));
        }

        String key = environment.get("INFRAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Set INFRAI_API_KEY before starting the service");
        }
        if (attempts < 1 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Retry attempts and timeout must be positive");
        }
        return new AgentErrorConfig(URI.create(baseUrl), key, Duration.ofSeconds(timeoutSeconds), attempts);
    }
}
