package br.ufla.poc.governance;

import br.ufla.poc.dto.DiagnosticDtos.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Governance layer — maps to the "Camada de Governança e Controle" in the reference architecture.
 *
 * Responsibilities:
 *  - Classify the overall risk level of a recommendation
 *  - Decide whether human approval is mandatory
 *  - Flag actions that exceed the agent's authorized scope
 *
 * This component is what separates a simple chatbot from a governable agent:
 * it enforces the supervised-autonomy model described in the paper.
 */
@Service
@Slf4j
public class GovernanceService {

    // Actions that ALWAYS require human approval regardless of context
    private static final List<Pattern> CRITICAL_PATTERNS = List.of(
        Pattern.compile("restart",                          Pattern.CASE_INSENSITIVE),
        Pattern.compile("scale\\s+(up|down|in|out)",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("delete\\s+",                       Pattern.CASE_INSENSITIVE),
        Pattern.compile("drop\\s+(table|database|queue)",   Pattern.CASE_INSENSITIVE),
        Pattern.compile("rollback",                         Pattern.CASE_INSENSITIVE),
        Pattern.compile("terminate",                        Pattern.CASE_INSENSITIVE),
        Pattern.compile("flush\\s+(cache|redis|queue)",     Pattern.CASE_INSENSITIVE),
        Pattern.compile("kill\\s+process",                  Pattern.CASE_INSENSITIVE),
        Pattern.compile("redeploy",                         Pattern.CASE_INSENSITIVE),
        Pattern.compile("reboot",                           Pattern.CASE_INSENSITIVE),
        Pattern.compile("stop\\s+(the\\s+)?service",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("shut\\s*down",                     Pattern.CASE_INSENSITIVE)
    );

    // Actions that require approval only if service criticality is HIGH
    private static final List<Pattern> HIGH_RISK_PATTERNS = List.of(
        Pattern.compile("change\\s+config",       Pattern.CASE_INSENSITIVE),
        Pattern.compile("update\\s+environment",  Pattern.CASE_INSENSITIVE),
        Pattern.compile("modify\\s+circuit",      Pattern.CASE_INSENSITIVE),
        Pattern.compile("increase\\s+timeout",    Pattern.CASE_INSENSITIVE),
        Pattern.compile("purge\\s+",              Pattern.CASE_INSENSITIVE)
    );

    public GovernanceDecision evaluate(String agentResponse, List<RecommendedAction> actions) {
        log.info("[GOVERNANCE] Evaluating recommendation for governance compliance");

        boolean requiresApproval = false;
        String overallRisk = "LOW";
        StringBuilder rationale = new StringBuilder();

        // Check agent response for critical patterns
        for (Pattern p : CRITICAL_PATTERNS) {
            if (p.matcher(agentResponse).find()) {
                requiresApproval = true;
                overallRisk = "CRITICAL";
                rationale.append("Critical action detected: ").append(p.pattern()).append(". ");
                log.warn("[GOVERNANCE] Critical pattern detected: {}", p.pattern());
            }
        }

        // Check each recommended action
        if (actions != null) {
            for (var action : actions) {
                String riskLevel = action.getRiskLevel();

                // Honour the agent's own flag
                if (action.isRequiresHumanApproval()) {
                    requiresApproval = true;
                    overallRisk = elevateRisk(overallRisk, "HIGH");
                    rationale.append("Agent flagged action as requiring approval: '")
                             .append(action.getAction()).append("'. ");
                }

                if ("CRITICAL".equals(riskLevel) || "HIGH".equals(riskLevel)) {
                    requiresApproval = true;
                    overallRisk = elevateRisk(overallRisk, riskLevel);
                    rationale.append("Action risk level ").append(riskLevel)
                             .append(": '").append(action.getAction()).append("'. ");
                }

                // Pattern check on the action text itself
                for (Pattern p : CRITICAL_PATTERNS) {
                    if (p.matcher(action.getAction()).find()) {
                        requiresApproval = true;
                        overallRisk = elevateRisk(overallRisk, "CRITICAL");
                        action.setRequiresHumanApproval(true);
                        rationale.append("Critical pattern '").append(p.pattern())
                                 .append("' in action: '").append(action.getAction()).append("'. ");
                    }
                }
            }
        }

        // Safe actions (read-only, monitoring, alerting) never need approval
        if (!requiresApproval) {
            rationale.append("All recommended actions are within autonomous agent scope (read/diagnose/alert only).");
        }

        log.info("[GOVERNANCE] Decision: requiresApproval={} risk={}", requiresApproval, overallRisk);

        return GovernanceDecision.builder()
            .requiresHumanApproval(requiresApproval)
            .riskLevel(overallRisk)
            .rationale(rationale.toString())
            .approvalStatus(requiresApproval ? "PENDING" : "NOT_REQUIRED")
            .build();
    }

    /**
     * Calculate a traceability score (0.0–1.0) based on how well the agent
     * grounded its recommendation in observable evidence.
     * This directly maps to the "Rastreabilidade" metric in Table 3 of the paper.
     */
    public double calculateTraceabilityScore(String agentResponse, int toolCallCount, int evidenceCount) {
        double score = 0.0;

        // Tool calls made (max 0.3)
        score += Math.min(toolCallCount / 5.0, 0.3);

        // Evidence items cited (max 0.4)
        score += Math.min(evidenceCount / 4.0, 0.4);

        // Structured output with required fields (max 0.3)
        boolean hasAffectedService   = agentResponse.contains("affectedService");
        boolean hasProbableCause     = agentResponse.contains("probableCause");
        boolean hasRecommendedActions = agentResponse.contains("recommendedActions");
        boolean hasEvidenceUsed      = agentResponse.contains("evidenceUsed");

        int structureScore = (hasAffectedService ? 1 : 0) + (hasProbableCause ? 1 : 0)
                           + (hasRecommendedActions ? 1 : 0) + (hasEvidenceUsed ? 1 : 0);
        score += structureScore * 0.075;

        double finalScore = Math.min(score, 1.0);
        log.info("[GOVERNANCE] Traceability score={} (tools={}, evidence={}, structure={}/4)",
            String.format("%.2f", finalScore), toolCallCount, evidenceCount, structureScore);
        return finalScore;
    }

    private String elevateRisk(String current, String candidate) {
        List<String> levels = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
        return levels.indexOf(candidate) > levels.indexOf(current) ? candidate : current;
    }
}
