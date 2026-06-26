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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class LogQueryTool {

    private final WebClient webClient;
    private final AuditService auditService;

    @Value("${agent.observability.loki-url}")
    private String lokiUrl;

    public String queryLogs(String serviceName, int minutesBack) {
        log.info("[TOOL] queryLogs service={} minutesBack={}", serviceName, minutesBack);
        long startTime = System.currentTimeMillis();

        // Try three label strategies in order
        String result = doLokiQuery(
            String.format("{service=\"%s\"} |~ \"(?i)(error|warn|exception|failed|refused|503|401|timeout)\"", serviceName),
            serviceName, minutesBack);

        if (isEmptyOrError(result)) {
            result = doLokiQuery(
                String.format("{container=\"poc-%s\"} |~ \"(?i)(error|warn|exception|failed|refused|503|401|timeout)\"", serviceName),
                serviceName, minutesBack);
        }

        if (isEmptyOrError(result)) {
            result = doLokiQuery(String.format("{service=\"%s\"}", serviceName), serviceName, minutesBack);
        }

        auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
            "LogQueryTool", "queryLogs",
            Map.of("service", serviceName, "minutesBack", minutesBack),
            result, System.currentTimeMillis() - startTime);
        return result;
    }

    public String queryAllLogs(String serviceName, int minutesBack) {
        log.info("[TOOL] queryAllLogs service={} minutesBack={}", serviceName, minutesBack);
        long startTime = System.currentTimeMillis();

        String result = doLokiQuery(String.format("{service=\"%s\"}", serviceName), serviceName, minutesBack);
        auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
            "LogQueryTool", "queryAllLogs",
            Map.of("service", serviceName, "minutesBack", minutesBack),
            result, System.currentTimeMillis() - startTime);
        return result;
    }

    private String doLokiQuery(String logql, String serviceName, int minutesBack) {
        try {
            Instant end   = Instant.now();
            Instant start = end.minus(minutesBack, ChronoUnit.MINUTES);

            // Build URL manually with URLEncoder — same pattern as MetricsQueryTool
            String encodedQuery = URLEncoder.encode(logql, StandardCharsets.UTF_8);
            String url = lokiUrl + "/loki/api/v1/query_range"
                + "?query=" + encodedQuery
                + "&start=" + start.toEpochMilli() + "000000"
                + "&end="   + end.toEpochMilli()   + "000000"
                + "&limit=30";

            String response = webClient.get()
                .uri(URI.create(url))
                .retrieve()
                .bodyToMono(String.class)
                .block();

            return parseLokiResponse(serviceName, response, minutesBack);
        } catch (Exception e) {
            log.error("[TOOL] queryLogs failed service={} error={}", serviceName, e.getMessage());
            return "Could not retrieve logs for '" + serviceName + "': " + e.getMessage();
        }
    }

    private boolean isEmptyOrError(String result) {
        return result == null
            || result.contains("Could not")
            || result.contains("No log entries")
            || result.contains("No error")
            || result.startsWith("No ");
    }

    private String parseLokiResponse(String serviceName, String raw, int minutesBack) {
        if (raw == null || raw.isBlank()) {
            return String.format("No logs found for '%s' in the last %d minutes.", serviceName, minutesBack);
        }
        if (raw.contains("\"result\":[]") || raw.contains("\"values\":[]")) {
            return String.format("No log entries for '%s' in the last %d minutes.", serviceName, minutesBack);
        }

        StringBuilder sb = new StringBuilder(
            String.format("Logs for '%s' (last %d min):\n", serviceName, minutesBack));
        try {
            Matcher m = Pattern.compile("\\[\"\\d+\",\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"\\]").matcher(raw);
            int count = 0;
            while (m.find() && count < 15) {
                String line = m.group(1)
                    .replace("\\n", " ")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
                if (line.length() > 200) line = line.substring(0, 200) + "...";
                sb.append("  ").append(line).append("\n");
                count++;
            }
            if (count == 0) {
                sb.append(raw, 0, Math.min(raw.length(), 800));
            }
        } catch (Exception e) {
            sb.append(raw, 0, Math.min(raw.length(), 800));
        }
        return sb.toString();
    }
}
