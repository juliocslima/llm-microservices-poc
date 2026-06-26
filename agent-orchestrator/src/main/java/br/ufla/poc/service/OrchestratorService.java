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
 * Orchestrator service — implements the 11-step diagnostic flow shown in Figure 2 of the paper.
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

    /**
     * ThreadLocal makes the current scenarioId available to AuditService during the ReAct loop
     * without changing every tool's method signature.
     */
    public static final ThreadLocal<String> SCENARIO_CTX = new ThreadLocal<>();

    public DiagnosticResponse diagnose(DiagnosticRequest request) {
        String diagnosticId = UUID.randomUUID().toString();
        String scenarioId   = request.getScenarioId() != null
            ? request.getScenarioId()
            : "MANUAL-" + diagnosticId.substring(0, 8);

        log.info("[ORCHESTRATOR] Starting diagnosis diagnosticId={} scenarioId={} service={}",
            diagnosticId, scenarioId, request.getSuspectedService());

        SCENARIO_CTX.set(scenarioId);
        long startTime = System.currentTimeMillis();

        String agentResponse;
        try {
            agentResponse = diagnosticAgent.diagnose(buildPrompt(request));
        } catch (Exception e) {
            log.error("[ORCHESTRATOR] Agent invocation failed: {}", e.getMessage(), e);
            agentResponse = buildFallbackResponse(request, e.getMessage());
        } finally {
            SCENARIO_CTX.remove();
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("[ORCHESTRATOR] Agent response received diagnosticId={} durationMs={}", diagnosticId, duration);
        log.debug("[ORCHESTRATOR] Raw response:\n{}", agentResponse);

        ParsedDiagnosis parsed    = parseAgentResponse(agentResponse, request);
        int toolCallCount         = countToolCalls(scenarioId);

        // Fallback: if DB is down (S6), count Action: occurrences in the raw response
        if (toolCallCount == 0 && agentResponse.contains("Action:")) {
            long actionCount = agentResponse.lines()
                .filter(l -> l.trim().startsWith("Action:") || l.contains("Action:"))
                .filter(l -> !l.contains("None") && !l.contains("Final"))
                .count();
            if (actionCount > 0) {
                toolCallCount = (int) actionCount;
                log.info("[ORCHESTRATOR] DB unavailable — estimated {} tool calls from agent text", toolCallCount);
            }
        }

        int evidenceCount         = parsed.evidenceUsed != null ? parsed.evidenceUsed.size() : 0;
        GovernanceDecision gov    = governanceService.evaluate(agentResponse, parsed.recommendedActions);
        double traceabilityScore  = governanceService.calculateTraceabilityScore(agentResponse, toolCallCount, evidenceCount);

        auditService.recordRecommendation(scenarioId, agentResponse,
            parsed.affectedService, gov.isRequiresHumanApproval(), evidenceCount, duration);

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

    // ── Private helpers ────────────────────────────────────────────────────────

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

            1. checkAllServicesHealth()             → get health of every service
            2. queryLogs("%s", %d)                  → look for ERROR/WARN log entries
            3. queryLatency("%s", %d)               → check for latency spikes
            4. getServiceDependencies("%s")          → map upstream/downstream impact
            5. inspectQueues()                      → check RabbitMQ queue depth
            6. getIncidentHistory("%s")             → look for known past incidents

            After completing all 6 tool calls, write your diagnosis as a ```json block.
            Do NOT write the JSON until you have called all tools above.
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
                result.affectedService    = (String) parsed.getOrDefault("affectedService", "unknown");
                result.probableCause      = (String) parsed.getOrDefault("probableCause", response);
                result.confidenceLevel    = (String) parsed.getOrDefault("confidenceLevel", "LOW");
                result.potentialImpact    = (String) parsed.getOrDefault("potentialImpact", "");
                result.evidenceUsed       = (List<String>) parsed.getOrDefault("evidenceUsed", List.of());
                result.relatedServices    = (List<String>) parsed.getOrDefault("relatedServices", List.of());
                List<Map<String, Object>> raw =
                    (List<Map<String, Object>>) parsed.getOrDefault("recommendedActions", List.of());
                result.recommendedActions = raw.stream().map(a -> RecommendedAction.builder()
                    .action((String) a.getOrDefault("action", ""))
                    .riskLevel((String) a.getOrDefault("riskLevel", "LOW"))
                    .requiresHumanApproval(Boolean.TRUE.equals(a.get("requiresHumanApproval")))
                    .build()).toList();
                log.info("[ORCHESTRATOR] Parsed JSON diagnosis service={} confidence={}",
                    result.affectedService, result.confidenceLevel);
                return result;
            }
        } catch (Exception e) {
            log.warn("[ORCHESTRATOR] JSON parsing failed, using text fallback: {}", e.getMessage());
        }
        result.affectedService    = req.getSuspectedService() != null ? req.getSuspectedService() : "unknown";
        result.probableCause      = response.length() > 500 ? response.substring(0, 500) : response;
        result.confidenceLevel    = "LOW";
        result.evidenceUsed       = List.of();
        result.recommendedActions = List.of();
        result.relatedServices    = List.of();
        return result;
    }

    private String extractJson(String text) {
        // Pattern 1: ```json ... ``` block (with or without "Final Answer:" prefix)
        Pattern p1 = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
        Matcher m1 = p1.matcher(text);
        if (m1.find()) return m1.group(1).trim();

        // Pattern 2: bare JSON object containing "affectedService"
        Pattern p2 = Pattern.compile("(\\{[\\s\\S]*\"affectedService\"[\\s\\S]*\\})");
        Matcher m2 = p2.matcher(text);
        if (m2.find()) return m2.group(1).trim();

        // Pattern 3: entire response is JSON
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) return trimmed;

        return null;
    }

    private int countToolCalls(String scenarioId) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audit_log WHERE action_type = 'TOOL_CALL' AND scenario_id = ?",
                Integer.class, scenarioId);
            return count != null ? count : 0;
        } catch (Exception e) {
            // DB may be down (scenario S6) — return -1 to signal "unknown, DB unavailable"
            // The DiagnosticResponse will carry this as 0 but traceability is computed separately
            log.warn("[ORCHESTRATOR] Cannot count tool calls from DB (may be down): {}", e.getMessage());
            return 0;
        }
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
