package br.ufla.poc.agent;

import br.ufla.poc.tool.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReActDiagnosticAgent implements DiagnosticAgent {

    private final OllamaChatModel model;
    private final LogQueryTool logQueryTool;
    private final MetricsQueryTool metricsQueryTool;
    private final HealthCheckTool healthCheckTool;
    private final TopologyTool topologyTool;
    private final QueueInspectionTool queueInspectionTool;
    private final IncidentHistoryTool incidentHistoryTool;

    private static final int MAX_ITERATIONS = 12;
    private final ThreadLocal<Integer> lastToolCallCount = ThreadLocal.withInitial(() -> 0);

    private static final Pattern ACTION_RE = Pattern.compile(
        "Action:\\s*([a-zA-Z]+)\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FINAL_RE = Pattern.compile(
        "Final\\s+Answer.*?```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final String SYSTEM_PROMPT = """
        You are an expert SRE diagnosing a distributed microservices incident.
        Follow the ReAct loop strictly: one Thought, then one Action, then STOP and wait.

        Available tools (call one at a time):
          checkAllServicesHealth()
          queryLogs(serviceName, minutesBack)
          queryLatency(serviceName, minutesBack)
          getServiceDependencies(serviceName)
          inspectQueues()
          getIncidentHistory(serviceName)

        STRICT FORMAT — output exactly this for each step:
          Thought: <your reasoning for this step>
          Action: toolName(arg1, arg2)

        After each Action line, STOP. Do not write Observation yourself.
        I will provide the real Observation from the system.

        EVIDENCE RULES:
        - Current-run health checks, logs, metrics and queue observations are primary evidence.
        - Incident history is secondary context only. It may suggest a hypothesis, but it MUST NOT
          be reported as the current root cause unless current-run observations independently support it.
        - Never invent or generalize evidence. Every item in evidenceUsed must be a concrete fact that
          appeared in an Observation from the current diagnostic session.
        - If historical context conflicts with current-run telemetry, prefer current-run telemetry.
        - probableCause must describe the failure mechanism supported by the current observations,
          not merely a similar historical incident.
        - Use exact component names observed in the environment (for example, order-service rather
          than an invented pluralized variant).

        After the required tool calls from the incident request have been completed, output:
          Thought: I have sufficient current evidence.
          Final Answer:
          ```json
          {
            "affectedService": "<name>",
            "probableCause": "<root cause supported by current observations>",
            "confidenceLevel": "HIGH|MEDIUM|LOW",
            "evidenceUsed": ["<concrete fact from a current Observation>"],
            "recommendedActions": [
              {"action": "<specific action>", "riskLevel": "LOW|MEDIUM|HIGH|CRITICAL", "requiresHumanApproval": true}
            ],
            "potentialImpact": "<impact>",
            "relatedServices": ["<exact-service-name>"]
          }
          ```
        """;

    @Override
    public String diagnose(String problem) {
        lastToolCallCount.set(0);
        log.info("[REACT] Starting ReAct loop for: {}",
            problem.substring(0, Math.min(120, problem.length())));

        List<ChatMessage> history = new ArrayList<>();
        history.add(SystemMessage.from(SYSTEM_PROMPT));
        history.add(UserMessage.from(problem));

        int toolCallCount = 0;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            log.debug("[REACT] Iteration {}/{} toolCalls={}", iter + 1, MAX_ITERATIONS, toolCallCount);

            String raw;
            try {
                raw = model.generate(history).content().text();
            } catch (Exception e) {
                log.error("[REACT] Model call failed iter={}: {}", iter, e.getMessage());
                lastToolCallCount.set(toolCallCount);
                return buildErrorJson(e.getMessage());
            }

            Matcher finalM = FINAL_RE.matcher(raw);
            if (finalM.find()) {
                if (toolCallCount >= 3) {
                    lastToolCallCount.set(toolCallCount);
                    log.info("[REACT] Final answer after {} real tool calls", toolCallCount);
                    return raw;
                }
                history.add(AiMessage.from(raw));
                history.add(UserMessage.from(
                    "You do not yet have enough current evidence. Call another required tool now.\n" +
                    "Remember: historical incidents are context only and cannot substitute for current telemetry.\n" +
                    "Thought: I need more current evidence.\nAction: "));
                continue;
            }

            Matcher actionM = ACTION_RE.matcher(raw);
            if (!actionM.find()) {
                history.add(AiMessage.from(raw));
                history.add(UserMessage.from(
                    "Please call a tool using exactly:\nAction: toolName(args)\n\n" +
                    "Available: checkAllServicesHealth(), queryLogs(svc,min), queryLatency(svc,min), " +
                    "getServiceDependencies(svc), inspectQueues(), getIncidentHistory(svc)"));
                continue;
            }

            int actionEnd = actionM.end();
            String accepted = raw.substring(0, actionEnd);
            history.add(AiMessage.from(accepted));

            String toolName = actionM.group(1).trim();
            String[] args = splitArgs(actionM.group(2));
            String observation = executeTool(toolName, args);
            toolCallCount++;
            lastToolCallCount.set(toolCallCount);

            if (observation.length() > 1500) {
                observation = observation.substring(0, 1500) + "\n...[truncated for context]";
            }

            history.add(UserMessage.from("Observation: " + observation +
                "\n\nContinue the ReAct loop. Follow the incident request's required tool sequence. " +
                "Base the final root cause on current-run observations; incident history is secondary context only."));
        }

        lastToolCallCount.set(toolCallCount);
        history.add(UserMessage.from(
            "Maximum iterations reached. Write the Final Answer now. " +
            "Use only root causes supported by current-run observations; do not promote historical causes without corroboration."));

        try {
            return model.generate(history).content().text();
        } catch (Exception e) {
            return buildErrorJson("Max iterations: " + e.getMessage());
        }
    }

    @Override
    public int consumeLastToolCallCount() {
        int value = lastToolCallCount.get();
        lastToolCallCount.remove();
        return value;
    }

    private String executeTool(String name, String[] args) {
        try {
            return switch (name) {
                case "checkAllServicesHealth" -> healthCheckTool.checkAllServicesHealth();
                case "checkServiceHealth" -> healthCheckTool.checkServiceHealth(s(args, 0, "order-service"));
                case "queryLogs" -> logQueryTool.queryLogs(s(args, 0, "order-service"), i(args, 1, 10));
                case "queryAllLogs" -> logQueryTool.queryAllLogs(s(args, 0, "order-service"), i(args, 1, 10));
                case "queryLatency" -> metricsQueryTool.queryLatency(s(args, 0, "order-service"), i(args, 1, 10));
                case "queryHttpStatus" -> metricsQueryTool.queryHttpStatus(s(args, 0, "order-service"), i(args, 1, 10));
                case "queryJvmMetrics" -> metricsQueryTool.queryJvmMetrics(s(args, 0, "order-service"));
                case "getServiceTopology" -> topologyTool.getServiceTopology();
                case "getServiceDependencies" -> topologyTool.getServiceDependencies(s(args, 0, "order-service"));
                case "inspectQueues" -> queueInspectionTool.inspectQueues();
                case "inspectQueue" -> queueInspectionTool.inspectQueue(s(args, 0, "poc.notifications"));
                case "getIncidentHistory" -> incidentHistoryTool.getIncidentHistory(s(args, 0, "order-service"));
                case "getRecentIncidents" -> incidentHistoryTool.getRecentIncidents(i(args, 0, 7));
                default -> "Unknown tool '" + name + "'.";
            };
        } catch (Exception e) {
            log.error("[REACT] Tool '{}' threw: {}", name, e.getMessage());
            return "Tool error for " + name + ": " + e.getMessage();
        }
    }

    private String[] splitArgs(String raw) {
        if (raw == null || raw.isBlank()) return new String[0];
        return raw.split(",");
    }

    private String s(String[] a, int i, String def) {
        if (a.length > i) return a[i].trim().replaceAll("^[\"'\\s]+|[\"'\\s]+$", "");
        return def;
    }

    private int i(String[] a, int i, int def) {
        try { return Integer.parseInt(s(a, i, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    private String buildErrorJson(String error) {
        return "Final Answer:\n```json\n{" +
            "\"affectedService\":\"unknown\"," +
            "\"probableCause\":\"Agent error: " + error.replace("\"", "'").replace("\n", " ") + "\"," +
            "\"confidenceLevel\":\"LOW\"," +
            "\"evidenceUsed\":[]," +
            "\"recommendedActions\":[]," +
            "\"potentialImpact\":\"Unknown\"," +
            "\"relatedServices\":[]}\n```";
    }
}
