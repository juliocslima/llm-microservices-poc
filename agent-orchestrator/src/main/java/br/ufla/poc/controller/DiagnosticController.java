package br.ufla.poc.controller;

import br.ufla.poc.audit.AuditService;
import br.ufla.poc.dto.DiagnosticDtos.*;
import br.ufla.poc.service.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST interface — implements the "Interface Humano-Agente" layer.
 *
 * Endpoints:
 *  POST /api/diagnose            — trigger a new diagnostic session
 *  POST /api/diagnose/decision   — register human approval/rejection
 *  GET  /api/diagnose/audit/{id} — retrieve full audit trail for a scenario
 *  GET  /api/diagnose/scenarios  — list all recorded recommendations
 *  GET  /api/diagnose/health     — sanity check
 */
@RestController
@RequestMapping("/api/diagnose")
@RequiredArgsConstructor
@Slf4j
public class DiagnosticController {

    private final OrchestratorService orchestratorService;
    private final AuditService auditService;

    /**
     * Main endpoint: receives an incident description and returns a structured diagnosis.
     * This is the entry point of the 11-step flow shown in Figure 2.
     */
    @PostMapping
    public ResponseEntity<DiagnosticResponse> diagnose(@RequestBody DiagnosticRequest request) {
        log.info("POST /api/diagnose scenarioId={} symptom={}",
            request.getScenarioId(), request.getSymptomDescription());

        if (request.getSymptomDescription() == null || request.getSymptomDescription().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        DiagnosticResponse response = orchestratorService.diagnose(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Human decision endpoint — registers operator approval or rejection.
     * Implements the "Aprovação humana" node from Figure 2.
     */
    @PostMapping("/decision")
    public ResponseEntity<?> recordDecision(@RequestBody HumanDecisionRequest request) {
        log.info("POST /api/diagnose/decision id={} decision={} by={}",
            request.getDiagnosticId(), request.getDecision(), request.getDecidedBy());

        if (!List.of("APPROVED", "REJECTED").contains(request.getDecision())) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Decision must be APPROVED or REJECTED"));
        }

        auditService.recordHumanDecision(
            request.getDiagnosticId(), request.getDecision(), request.getDecidedBy());

        return ResponseEntity.ok(Map.of(
            "status", "recorded",
            "diagnosticId", request.getDiagnosticId(),
            "decision", request.getDecision()
        ));
    }

    /**
     * Retrieves the full audit trail for a scenario — demonstrates traceability.
     * Every tool call, every evidence piece, every agent reasoning step is recorded.
     */
    @GetMapping("/audit/{scenarioId}")
    public ResponseEntity<AuditTrailResponse> getAuditTrail(@PathVariable("scenarioId") String scenarioId) {
        log.info("GET /api/diagnose/audit/{}", scenarioId);

        List<AuditEntry> entries = orchestratorService.getAuditTrail(scenarioId);
        long toolCalls = entries.stream().filter(e -> "TOOL_CALL".equals(e.getActionType())).count();
        long evidenceCount = entries.stream().filter(e -> e.getToolOutput() != null
                && !e.getToolOutput().contains("No ") && !e.getToolOutput().contains("Error")).count();

        double score = Math.min(toolCalls / 5.0, 0.3)
                     + Math.min(evidenceCount / 4.0, 0.4);

        return ResponseEntity.ok(AuditTrailResponse.builder()
            .diagnosticId(scenarioId)
            .entries(entries)
            .traceabilityScore(Math.min(score, 1.0))
            .build());
    }

    @GetMapping("/scenarios/{scenarioId}")
    public ResponseEntity<?> getScenarioResults(@PathVariable("scenarioId") String scenarioId) {
        return ResponseEntity.ok(orchestratorService.getRecommendations(scenarioId));
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "agent", "DiagnosticAgent", "model", "ollama/llama3"));
    }
}
