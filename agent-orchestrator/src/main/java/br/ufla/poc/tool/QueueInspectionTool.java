package br.ufla.poc.tool;

import br.ufla.poc.audit.AuditService;
import br.ufla.poc.service.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueInspectionTool {

    private final WebClient webClient;
    private final AuditService auditService;

    @Value("${agent.rabbitmq.mgmt-url}") private String mgmtUrl;
    @Value("${agent.rabbitmq.user}")     private String user;
    @Value("${agent.rabbitmq.pass}")     private String pass;

    public String inspectQueues() {
        log.info("[TOOL] inspectQueues");
        long start = System.currentTimeMillis();

        try {
            String response = webClient.get()
                .uri(mgmtUrl + "/api/queues")
                .header(HttpHeaders.AUTHORIZATION, basicAuth(user, pass))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            String result = "=== RabbitMQ Queue Status ===\n" +
                (response != null && response.length() > 2000 ? response.substring(0, 2000) + "...[truncated]" : response);
            auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
                "QueueInspectionTool", "inspectQueues", Collections.emptyMap(), result,
                System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("[TOOL] inspectQueues failed: {}", e.getMessage());
            return "Error querying RabbitMQ management API: " + e.getMessage();
        }
    }

    public String inspectQueue(String queueName) {
        log.info("[TOOL] inspectQueue queue={}", queueName);
        long start = System.currentTimeMillis();

        try {
            String response = webClient.get()
                .uri(mgmtUrl + "/api/queues/%2F/" + queueName)
                .header(HttpHeaders.AUTHORIZATION, basicAuth(user, pass))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            String result = String.format("Queue '%s' details:\n%s", queueName,
                response != null && response.length() > 1000 ? response.substring(0, 1000) : response);
            auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
                "QueueInspectionTool", "inspectQueue", Map.of("queue", queueName), result,
                System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            return "Error inspecting queue " + queueName + ": " + e.getMessage();
        }
    }

    private String basicAuth(String u, String p) {
        return "Basic " + Base64.getEncoder().encodeToString((u + ":" + p).getBytes());
    }
}
