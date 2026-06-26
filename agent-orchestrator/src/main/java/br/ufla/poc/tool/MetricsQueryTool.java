package br.ufla.poc.tool;

import br.ufla.poc.audit.AuditService;
import br.ufla.poc.service.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class MetricsQueryTool {

    private final WebClient webClient;
    private final AuditService auditService;

    @Value("${agent.observability.prometheus-url}")
    private String prometheusUrl;

    public String queryLatency(String serviceName, int minutesBack) {
        log.info("[TOOL] queryLatency service={} minutes={}", serviceName, minutesBack);
        long start = System.currentTimeMillis();

        String range = minutesBack + "m";
        String p99   = queryPrometheus(String.format(
            "histogram_quantile(0.99,rate(http_server_requests_seconds_bucket{application=\"%s\"}[%s]))",
            serviceName, range));
        String err   = queryPrometheus(String.format(
            "rate(http_server_requests_seconds_count{application=\"%s\",status=~\"5..\"}[%s])",
            serviceName, range));
        String total = queryPrometheus(String.format(
            "rate(http_server_requests_seconds_count{application=\"%s\"}[%s])",
            serviceName, range));

        String result = String.format(
            "Latency & error metrics for '%s' (last %d min):\n" +
            "  P99 latency : %s\n  5xx rate    : %s\n  Total rate  : %s",
            serviceName, minutesBack,
            formatMetric(p99, "s"), formatMetric(err, "req/s"), formatMetric(total, "req/s"));

        auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
            "MetricsQueryTool", "queryLatency",
            Map.of("service", serviceName, "minutesBack", minutesBack),
            result, System.currentTimeMillis() - start);
        return result;
    }

    public String queryHttpStatus(String serviceName, int minutesBack) {
        log.info("[TOOL] queryHttpStatus service={} minutes={}", serviceName, minutesBack);
        long start = System.currentTimeMillis();

        String raw = queryPrometheus(String.format(
            "sum by(status)(rate(http_server_requests_seconds_count{application=\"%s\"}[%dm]))",
            serviceName, minutesBack));

        String result = String.format("HTTP status breakdown for '%s' (last %d min):\n%s",
            serviceName, minutesBack, raw);
        auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
            "MetricsQueryTool", "queryHttpStatus", Map.of("service", serviceName),
            result, System.currentTimeMillis() - start);
        return result;
    }

    public String queryJvmMetrics(String serviceName) {
        log.info("[TOOL] queryJvmMetrics service={}", serviceName);
        long start = System.currentTimeMillis();

        String heap = queryPrometheus(String.format(
            "jvm_memory_used_bytes{application=\"%s\",area=\"heap\"}", serviceName));
        String result = String.format("JVM metrics for '%s':\n  Heap used: %s",
            serviceName, formatMetric(heap, "bytes"));
        auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
            "MetricsQueryTool", "queryJvmMetrics", Map.of("service", serviceName),
            result, System.currentTimeMillis() - start);
        return result;
    }

    /**
     * Build the Prometheus API URL manually to avoid double-encoding.
     * URLEncoder encodes the PromQL once; the rest of the URL is plain ASCII.
     */
    private String queryPrometheus(String promql) {
        try {
            // URLEncoder.encode produces application/x-www-form-urlencoded encoding,
            // which is exactly what Prometheus /api/v1/query expects for the query param.
            String encoded = URLEncoder.encode(promql, StandardCharsets.UTF_8);
            String url = prometheusUrl + "/api/v1/query?query=" + encoded;

            String response = webClient.get()
                .uri(URI.create(url))   // pass pre-built URI to skip WebClient encoding
                .retrieve()
                .bodyToMono(String.class)
                .block();
            return response != null ? response : "No data";
        } catch (Exception e) {
            log.error("[TOOL] Prometheus query failed: {}", e.getMessage());
            return "Prometheus unavailable: " + e.getMessage();
        }
    }

    private String formatMetric(String json, String unit) {
        if (json == null || json.contains("\"result\":[]")) return "no data";
        if (json.startsWith("Prometheus")) return json;
        try {
            int vi = json.indexOf("\"value\":[");
            if (vi < 0) return json.length() > 150 ? json.substring(0, 150) + "..." : json;
            int s = json.indexOf(",", vi) + 2;
            int e = json.indexOf("\"", s + 1);
            return json.substring(s, e) + " " + unit;
        } catch (Exception e) {
            return json.length() > 200 ? json.substring(0, 200) + "..." : json;
        }
    }
}
