package br.ufla.poc.tool;

import br.ufla.poc.audit.AuditService;
import br.ufla.poc.service.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class HealthCheckTool {

    private final WebClient webClient;
    private final AuditService auditService;

    @Value("${agent.services.order}")        private String orderUrl;
    @Value("${agent.services.payment}")      private String paymentUrl;
    @Value("${agent.services.inventory}")    private String inventoryUrl;
    @Value("${agent.services.notification}") private String notificationUrl;
    @Value("${agent.services.gateway}")      private String gatewayUrl;

    /** 4s timeout per health check — prevents 30s hangs when Postgres is down */
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(4);

    public String checkServiceHealth(String serviceName) {
        log.info("[TOOL] checkServiceHealth service={}", serviceName);
        long start = System.currentTimeMillis();

        String baseUrl = resolveServiceUrl(serviceName);
        if (baseUrl == null) {
            return "Unknown service '" + serviceName + "'. Available: order-service, payment-service, " +
                   "inventory-service, notification-service, api-gateway";
        }

        String result = doHealthCheck(serviceName, baseUrl);
        auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
            "HealthCheckTool", "checkServiceHealth",
            Map.of("service", serviceName), result,
            System.currentTimeMillis() - start);
        return result;
    }

    public String checkAllServicesHealth() {
        log.info("[TOOL] checkAllServicesHealth");
        long start = System.currentTimeMillis();

        // Run health checks in parallel using reactive streams
        Map<String, String> services = new LinkedHashMap<>();
        services.put("api-gateway",          gatewayUrl);
        services.put("order-service",        orderUrl);
        services.put("payment-service",      paymentUrl);
        services.put("inventory-service",    inventoryUrl);
        services.put("notification-service", notificationUrl);

        StringBuilder sb = new StringBuilder("=== System Health Status ===\n");
        int upCount = 0, downCount = 0;

        for (var entry : services.entrySet()) {
            String status = doHealthCheck(entry.getKey(), entry.getValue());
            sb.append(status).append("\n");
            if (status.startsWith("[UP]")) upCount++;
            else downCount++;
        }

        sb.append(String.format("\nSummary: %d UP / %d DOWN / %d total",
            upCount, downCount, services.size()));

        String result = sb.toString();
        auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
            "HealthCheckTool", "checkAllServicesHealth",
            Collections.emptyMap(), result,
            System.currentTimeMillis() - start);
        return result;
    }

    private String doHealthCheck(String serviceName, String baseUrl) {
        try {
            String response = webClient.get()
                .uri(baseUrl + "/actuator/health")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(HEALTH_TIMEOUT)
                .onErrorResume(e -> Mono.just("ERROR: " + e.getMessage()))
                .block();

            if (response != null && response.startsWith("ERROR:")) {
                log.warn("[TOOL] Health check failed service={} error={}", serviceName, response);
                return String.format("[DOWN]  %s: %s", serviceName, response);
            }

            // Truncate long responses
            String truncated = response != null && response.length() > 300
                ? response.substring(0, 300) + "..." : response;
            return String.format("[UP]    %s: %s", serviceName, truncated);

        } catch (Exception e) {
            log.warn("[TOOL] Health check failed service={} error={}", serviceName, e.getMessage());
            return String.format("[DOWN]  %s: Connection failed - %s", serviceName, e.getMessage());
        }
    }

    private String resolveServiceUrl(String name) {
        return switch (name.toLowerCase().replace(" ", "-")) {
            case "order-service",        "order"        -> orderUrl;
            case "payment-service",      "payment"      -> paymentUrl;
            case "inventory-service",    "inventory"    -> inventoryUrl;
            case "notification-service", "notification" -> notificationUrl;
            case "api-gateway",          "gateway"      -> gatewayUrl;
            default -> null;
        };
    }
}
