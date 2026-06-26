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
public class TopologyTool {

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public String getServiceTopology() {
        log.info("[TOOL] getServiceTopology");
        long start = System.currentTimeMillis();

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT service_name, description, dependencies, criticality, port FROM service_topology ORDER BY criticality DESC");

            StringBuilder sb = new StringBuilder("=== Service Topology ===\n\n");
            for (var row : rows) {
                sb.append(String.format("Service: %s (port %s) [%s]\n",
                    row.get("service_name"), row.get("port"), row.get("criticality")));
                sb.append(String.format("  Description: %s\n", row.get("description")));
                sb.append(String.format("  Dependencies: %s\n\n", row.get("dependencies")));
            }

            String result = sb.toString();
            auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
                "TopologyTool", "getServiceTopology", Collections.emptyMap(), result,
                System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("[TOOL] getServiceTopology failed: {}", e.getMessage());
            return "Error reading service topology: " + e.getMessage();
        }
    }

    public String getServiceDependencies(String serviceName) {
        log.info("[TOOL] getServiceDependencies service={}", serviceName);
        long start = System.currentTimeMillis();

        try {
            List<Map<String, Object>> direct = jdbc.queryForList(
                "SELECT service_name, dependencies, criticality FROM service_topology WHERE service_name = ?",
                serviceName);

            List<Map<String, Object>> dependents = jdbc.queryForList(
                "SELECT service_name, dependencies FROM service_topology WHERE dependencies::text LIKE ?",
                "%" + serviceName + "%");

            StringBuilder sb = new StringBuilder(String.format("=== Dependencies for '%s' ===\n\n", serviceName));

            if (direct.isEmpty()) {
                sb.append("Service not found in topology registry.\n");
            } else {
                var row = direct.get(0);
                sb.append(String.format("Criticality: %s\n", row.get("criticality")));
                sb.append(String.format("Direct dependencies: %s\n\n", row.get("dependencies")));
            }

            sb.append("Services that depend ON this service (upstream impact):\n");
            if (dependents.isEmpty()) {
                sb.append("  None (leaf service or not registered)\n");
            } else {
                for (var dep : dependents) {
                    sb.append(String.format("  - %s\n", dep.get("service_name")));
                }
            }

            String result = sb.toString();
            auditService.recordToolCall(OrchestratorService.SCENARIO_CTX.get(),
                "TopologyTool", "getServiceDependencies", Map.of("service", serviceName), result,
                System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            return "Error reading dependencies: " + e.getMessage();
        }
    }
}
