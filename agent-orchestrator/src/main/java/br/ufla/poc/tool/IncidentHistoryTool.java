package br.ufla.poc.tool;

import br.ufla.poc.audit.AuditService;
import br.ufla.poc.service.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class IncidentHistoryTool {

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    
    public String getIncidentHistory(String serviceName) {
        log.info("[TOOL] getIncidentHistory service={}", serviceName);
        long start = System.currentTimeMillis();

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT service_name, incident_type, description, resolution, occurred_at " +
                "FROM incident_history WHERE service_name = ? ORDER BY occurred_at DESC LIMIT 10",
                serviceName);

            if (rows.isEmpty()) {
                String result = String.format("No historical incidents found for '%s'. " +
                    "This may be a new service or the first occurrence of this type of failure.", serviceName);
                auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(), "IncidentHistoryTool", "getIncidentHistory",
                    Map.of("service", serviceName), result, System.currentTimeMillis() - start);
                return result;
            }

            StringBuilder sb = new StringBuilder(
                String.format("=== Incident History for '%s' (%d records) ===\n\n", serviceName, rows.size()));
            for (var row : rows) {
                sb.append(String.format("Type: %s\n", row.get("incident_type")));
                sb.append(String.format("Description: %s\n", row.get("description")));
                sb.append(String.format("Resolution: %s\n", row.get("resolution")));
                sb.append(String.format("Date: %s\n\n", row.get("occurred_at")));
            }

            String result = sb.toString();
            auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(), "IncidentHistoryTool", "getIncidentHistory",
                Map.of("service", serviceName), result, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            return "Error reading incident history: " + e.getMessage();
        }
    }

    public String getRecentIncidents(int days) {
        log.info("[TOOL] getRecentIncidents days={}", days);
        long start = System.currentTimeMillis();

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT service_name, incident_type, description, occurred_at " +
                "FROM incident_history WHERE occurred_at > NOW() - INTERVAL '" + days + " days' " +
                "ORDER BY occurred_at DESC");

            if (rows.isEmpty()) {
                return String.format("No incidents recorded in the last %d days.", days);
            }

            StringBuilder sb = new StringBuilder(
                String.format("=== Recent Incidents (last %d days, %d total) ===\n\n", days, rows.size()));
            for (var row : rows) {
                sb.append(String.format("[%s] %s - %s\n", row.get("occurred_at"),
                    row.get("service_name"), row.get("incident_type")));
            }

            String result = sb.toString();
            auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(), "IncidentHistoryTool", "getRecentIncidents",
                Map.of("days", days), result, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            return "Error reading recent incidents: " + e.getMessage();
        }
    }
}
