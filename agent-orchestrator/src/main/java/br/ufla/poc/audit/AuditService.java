package br.ufla.poc.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Audit service — persistence component of the governance layer.
 *
 * All writes are @Async so a DB outage (scenario S6) never blocks the agent loop.
 * Audit is best-effort: if Postgres is down, we log a warning and continue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Async
    public void recordToolCall(String scenarioId, String agentName, String toolName,
                               Map<String, Object> input, String output, long durationMs) {
        try {
            String inputJson = objectMapper.writeValueAsString(input);
            String truncated = output != null && output.length() > 4000
                ? output.substring(0, 4000) + "...[truncated]" : output;
            jdbc.update(
                "INSERT INTO agent_audit_log " +
                "(scenario_id, agent_name, action_type, tool_name, tool_input, tool_output, duration_ms) " +
                "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
                scenarioId, agentName, "TOOL_CALL", toolName, inputJson, truncated, durationMs);
            log.debug("[AUDIT] Recorded tool call scenario={} agent={} tool={} durationMs={}",
                scenarioId, agentName, toolName, durationMs);
        } catch (Exception e) {
            log.warn("[AUDIT] Could not persist tool call (DB may be down): {}", e.getMessage());
        }
    }

    public void recordToolCall(String agentName, String toolName,
                               Map<String, Object> input, String output, long durationMs) {
        recordToolCall(null, agentName, toolName, input, output, durationMs);
    }

    @Async
    public void recordRecommendation(String scenarioId, String agentResponse,
                                     String affectedService, boolean requiresHumanApproval,
                                     int evidenceCount, long durationMs) {
        try {
            jdbc.update(
                "INSERT INTO agent_audit_log " +
                "(scenario_id, agent_name, action_type, response, evidence_count, duration_ms) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                scenarioId, "DiagnosticAgent", "RECOMMENDATION", agentResponse, evidenceCount, durationMs);
            jdbc.update(
                "INSERT INTO agent_recommendations " +
                "(scenario_id, affected_service, probable_cause, requires_human_approval, evidence_used) " +
                "VALUES (?, ?, ?, ?, '{}'::jsonb)",
                scenarioId, affectedService,
                agentResponse.length() > 500 ? agentResponse.substring(0, 500) : agentResponse,
                requiresHumanApproval);
            log.info("[AUDIT] Recommendation recorded scenarioId={} service={} requiresApproval={}",
                scenarioId, affectedService, requiresHumanApproval);
        } catch (Exception e) {
            log.warn("[AUDIT] Could not persist recommendation (DB may be down): {}", e.getMessage());
        }
    }

    public void recordHumanDecision(String recommendationId, String decision, String decidedBy) {
        try {
            jdbc.update(
                "UPDATE agent_recommendations SET human_decision=?, human_decision_at=NOW() WHERE id=?::uuid",
                decision, recommendationId);
            log.info("[AUDIT] Human decision recorded id={} decision={} by={}",
                recommendationId, decision, decidedBy);
        } catch (Exception e) {
            log.warn("[AUDIT] Could not persist human decision: {}", e.getMessage());
        }
    }
}
