package br.ufla.poc.service;

import br.ufla.poc.agent.DiagnosticAgent;
import br.ufla.poc.audit.AuditService;
import br.ufla.poc.dto.DiagnosticDtos.*;
import br.ufla.poc.governance.GovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/**
 * Orchestrator service — implements the diagnostic flow shown in the paper.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestratorService {

    private final DiagnosticAgent diagnosticAgent;
    private final GovernanceService governanceService;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public static final ThreadLocal<String> SCENARIO_CTX = new ThreadLocal<>();

    public DiagnosticResponse diagnose(DiagnosticRequest request) {
        String diagnosticId = UUID.randomUUID().toString();
        String scenarioId = request.getScenarioId() != null
            ? request.getScenarioId()
            : "MANUAL-" + diagnosticId.substring(0, 8);

        log.info("[ORCHESTRATOR] Starting diagnosis diagnosticId={} scenarioId={} service={}",
            diagnosticId, scenarioId, request.getSuspectedService());

        SCENARIO_CTX.set(scenarioId);
        long startTime = System.currentTimeMillis();

        String agentResponse;
        int toolCallCount;
        try {
            agentResponse = diagnosticAgent.diagnose(buildPrompt(request));
            toolCallCount = diagnosticAgent.consumeLastToolCallCount();
        } catch (Exception e) {
            log.error("[ORCHESTRATOR] Agent invocation failed: {}", e.getMessage(), e);
            toolCallCount = diagnosticAgent.consumeLastToolCallCount();
            agentResponse = buildFallbackResponse(request, e.getMessage());
        } finally {
            SCENARIO_CTX.remove();
        }

        long duration = System.currentTimeMillis() - startTime;
        ParsedDiagnosis parsed = parseAgentResponse(agentResponse, request);

        int evidenceCount = parsed.evidenceUsed != null ? parsed.evidenceUsed.size() : 0;
        GovernanceDecision gov = governanceService.evaluate(agentResponse, parsed.recommendedActions);
        double traceabilityScore = governanceService.calculateTraceabilityScore(
            agentResponse, toolCallCount, evidenceCount);

        auditService.recordRecommendation(scenarioId, agentResponse,
            parsed.affectedService, gov.isRequiresHumanApproval(), evidenceCount, duration);

        log.info("[ORCHESTRATOR] Diagnosis complete diagnosticId={} scenarioId={} durationMs={} toolCalls={} evidence={}",
            diagnosticId, scenarioId, duration, toolCallCount, evidenceCount);

        return DiagnosticResponse.builder()
            .diagnosticId(diagnosticId)
            .scenarioId(scenarioId)
            .affectedService(parsed.affectedService)
            .probableCause(parsed.probableCause)
            .confidenceLevel(parsed.confidenceLevel)
            .evidenceUsed(parsed.evidenceUsed)
            .recommendedActions(parsed.recommendedActions)
            .potentialImpact(parsed.potentialImpact)
            .relatedServices(parsed.relatedServices)
            .governance(gov)
            .metrics(DiagnosticMetrics.builder()
                .totalDurationMs(duration)
                .toolCallCount(toolCallCount)
                .evidenceCount(evidenceCount)
                .traceabilityScore(traceabilityScore)
                .build())
            .timestamp(Instant.now())
            .rawAgentResponse(agentResponse)
            .build();
    }

    public List<AuditEntry> getAuditTrail(String scenarioId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT agent_name, action_type, tool_name, tool_input, tool_output, duration_ms, timestamp " +
                "FROM agent_audit_log WHERE scenario_id = ? ORDER BY timestamp ASC",
                scenarioId);

            return rows.stream().map(r -> AuditEntry.builder()
                .timestamp(((java.sql.Timestamp) r.get("timestamp")).toInstant())
                .agentName((String) r.get("agent_name"))
                .actionType((String) r.get("action_type"))
                .toolName((String) r.get("tool_name"))
                .toolOutput((String) r.get("tool_output"))
                .durationMs(r.get("duration_ms") != null ? (Long) r.get("duration_ms") : 0L)
                .build()).toList();
        } catch (Exception e) {
            log.warn("[ORCHESTRATOR] Could not query audit trail (DB may be down): {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> getRecommendations(String scenarioId) {
        try {
            return jdbc.queryForList(
                "SELECT * FROM agent_recommendations WHERE scenario_id = ? ORDER BY created_at DESC",
                scenarioId);
        } catch (Exception e) {
            log.warn("[ORCHESTRATOR] Could not query recommendations: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildPrompt(DiagnosticRequest req) {
        String svc = req.getSuspectedService() != null ? req.getSuspectedService() : "order-service";
        return String.format("""
            INCIDENT REPORT
            ===============
            Scenario ID      : %s
            Symptom reported : %s
            Suspected service: %s
            Time window      : last %d minutes

            YOU MUST EXECUTE THE FOLLOWING TOOL CALLS IN ORDER — do not skip any:

            1. checkAllServicesHealth()
            2. queryLogs("%s", %d)
            3. queryLatency("%s", %d)
            4. getServiceDependencies("%s")
            5. inspectQueues()
            6. getIncidentHistory("%s")

            After completing the tool calls, write the diagnosis as a JSON block.
            """,
            req.getScenarioId() != null ? req.getScenarioId() : "MANUAL",
            req.getSymptomDescription(),
            svc, req.getLookbackMinutes(),
            svc, req.getLookbackMinutes(),
            svc, req.getLookbackMinutes(),
            svc,
            svc
        );
    }

    @SuppressWarnings("unchecked")
    private ParsedDiagnosis parseAgentResponse(String response, DiagnosticRequest req) {
        ParsedDiagnosis result = new ParsedDiagnosis();
        try {
            String json = extractJson(response);
            if (json != null) {
                Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
                result.affectedService = (String) parsed.getOrDefault("affectedService", "unknown");
                result.probableCause = (String) parsed.getOrDefault("probableCause", response);
                result.confidenceLevel = (String) parsed.getOrDefault("confidenceLevel", "LOW");
                result.potentialImpact = (String) parsed.getOrDefault("potentialImpact", "");
                result.evidenceUsed = (List<String>) parsed.getOrDefault("evidenceUsed", List.of());
                result.relatedServices = (List<String>) parsed.getOrDefault("relatedServices", List.of());
                List<Map<String, Object>> raw =
                    (List<Map<String, Object>>) parsed.getOrDefault("recommendedActions", List.of());
                result.recommendedActions = raw.stream().map(a -> RecommendedAction.builder()
                    .action((String) a.getOrDefault("action", ""))
                    .riskLevel((String) a.getOrDefault("riskLevel", "LOW"))
                    .requiresHumanApproval(Boolean.TRUE.equals(a.get("requiresHumanApproval")))
                    .build()).toList();
                return result;
            }
        } catch (Exception e) {
            log.warn("[ORCHESTRATOR] JSON parsing failed, using text fallback: {}", e.getMessage());
        }

        result.affectedService = req.getSuspectedService() != null ? req.getSuspectedService() : "unknown";
        result.probableCause = response.length() > 500 ? response.substring(0, 500) : response;
        result.confidenceLevel = "LOW";
        result.evidenceUsed = List.of();
        result.recommendedActions = List.of();
        result.relatedServices = List.of();
        return result;
    }

    private String extractJson(String text) {
        Pattern p1 = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
        Matcher m1 = p1.matcher(text);
        if (m1.find()) return m1.group(1).trim();

        Pattern p2 = Pattern.compile("(\\{[\\s\\S]*\"affectedService\"[\\s\\S]*\\})");
        Matcher m2 = p2.matcher(text);
        if (m2.find()) return m2.group(1).trim();

        String trimmed = text.trim();
        if (trimmed.startsWith("{")) return trimmed;
        return null;
    }

    private String buildFallbackResponse(DiagnosticRequest req, String error) {
        return String.format(
            "{\"affectedService\":\"%s\",\"probableCause\":\"Agent invocation failed: %s\"," +
            "\"confidenceLevel\":\"LOW\",\"evidenceUsed\":[],\"recommendedActions\":[]," +
            "\"potentialImpact\":\"Unknown\",\"relatedServices\":[]}",
            req.getSuspectedService() != null ? req.getSuspectedService() : "unknown",
            error.replace("\"", "'"));
    }

    private static class ParsedDiagnosis {
        String affectedService, probableCause, confidenceLevel, potentialImpact;
        List<String> evidenceUsed, relatedServices;
        List<RecommendedAction> recommendedActions;
    }
}
