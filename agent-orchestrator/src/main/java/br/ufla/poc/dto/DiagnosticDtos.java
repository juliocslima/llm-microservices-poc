package br.ufla.poc.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

public class DiagnosticDtos {

    @Data
    public static class DiagnosticRequest {
        /** Scenario ID (S1-S6) — links audit records to a controlled experiment */
        private String scenarioId;
        /** Free-text symptom description from the operator */
        private String symptomDescription;
        /** Which service is suspected (optional hint) */
        private String suspectedService;
        /** How many minutes back to look for evidence */
        private Integer lookbackMinutes = 10;
    }

    @Data @Builder
    public static class DiagnosticResponse {
        private String diagnosticId;
        private String scenarioId;
        private String affectedService;
        private String probableCause;
        private String confidenceLevel;
        private List<String> evidenceUsed;
        private List<RecommendedAction> recommendedActions;
        private String potentialImpact;
        private List<String> relatedServices;
        private GovernanceDecision governance;
        private DiagnosticMetrics metrics;
        private Instant timestamp;
        private String rawAgentResponse;
    }

    @Data @Builder
    public static class RecommendedAction {
        private String action;
        private String riskLevel;
        private boolean requiresHumanApproval;
    }

    @Data @Builder
    public static class GovernanceDecision {
        private boolean requiresHumanApproval;
        private String riskLevel;
        private String rationale;
        private String approvalStatus;   // PENDING | APPROVED | REJECTED
    }

    @Data @Builder
    public static class DiagnosticMetrics {
        private long totalDurationMs;
        private int toolCallCount;
        private int evidenceCount;
        private double traceabilityScore;  // 0.0 - 1.0
    }

    @Data
    public static class HumanDecisionRequest {
        private String diagnosticId;
        private String decision;    // APPROVED | REJECTED
        private String decidedBy;
        private String comment;
    }

    @Data @Builder
    public static class AuditTrailResponse {
        private String diagnosticId;
        private List<AuditEntry> entries;
        private double traceabilityScore;
    }

    @Data @Builder
    public static class AuditEntry {
        private Instant timestamp;
        private String agentName;
        private String actionType;
        private String toolName;
        private Object toolInput;
        private String toolOutput;
        private long durationMs;
    }
}
