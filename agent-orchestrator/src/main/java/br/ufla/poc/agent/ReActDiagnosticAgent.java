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

/**
 * ReAct (Reasoning + Acting) agent — manual loop compatible with any Ollama model.
 *
 * Key design: after each model call we scan the output for the FIRST "Action:" line,
 * truncate everything after it (discarding any Observations the model hallucinated),
 * execute the real tool, and inject the real "Observation:" before calling the model again.
 *
 * This forces genuine tool-calling regardless of whether the model tries to shortcut
 * the loop by inventing its own observations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReActDiagnosticAgent implements DiagnosticAgent {

    private final OllamaChatModel     model;
    private final LogQueryTool        logQueryTool;
    private final MetricsQueryTool    metricsQueryTool;
    private final HealthCheckTool     healthCheckTool;
    private final TopologyTool        topologyTool;
    private final QueueInspectionTool queueInspectionTool;
    private final IncidentHistoryTool incidentHistoryTool;

    private static final int MAX_ITERATIONS = 12;

    /** Matches the first Action line, capturing toolName and raw args. */
    private static final Pattern ACTION_RE = Pattern.compile(
        "Action:\\s*([a-zA-Z]+)\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    /** Matches the Final Answer marker together with the JSON block. */
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

        After at least 3 tool results, output your diagnosis:
          Thought: I have sufficient evidence.
          Final Answer:
          ```json
          {
            "affectedService": "<name>",
            "probableCause": "<root cause from actual observations>",
            "confidenceLevel": "HIGH|MEDIUM|LOW",
            "evidenceUsed": ["<quote from an actual observation>"],
            "recommendedActions": [
              {"action": "<specific action>", "riskLevel": "LOW|MEDIUM|HIGH|CRITICAL", "requiresHumanApproval": true}
            ],
            "potentialImpact": "<impact>",
            "relatedServices": ["<svc>"]
          }
          ```
        """;

    @Override
    public String diagnose(String problem) {
        log.info("[REACT] Starting ReAct loop for: {}",
            problem.substring(0, Math.min(120, problem.length())));

        List<ChatMessage> history = new ArrayList<>();
        history.add(SystemMessage.from(SYSTEM_PROMPT));
        history.add(UserMessage.from(problem));

        int toolCallCount = 0;
        StringBuilder fullTrace = new StringBuilder();

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            log.debug("[REACT] Iteration {}/{} toolCalls={}", iter + 1, MAX_ITERATIONS, toolCallCount);

            // ── Call model ────────────────────────────────────────────────────
            String raw;
            try {
                raw = model.generate(history).content().text();
            } catch (Exception e) {
                log.error("[REACT] Model call failed iter={}: {}", iter, e.getMessage());
                return buildErrorJson(e.getMessage());
            }
            log.debug("[REACT] Raw model output (iter {}):\n{}", iter + 1, raw);

            // ── Check for Final Answer first ───────────────────────────────
            Matcher finalM = FINAL_RE.matcher(raw);
            if (finalM.find()) {
                if (toolCallCount >= 3) {
                    log.info("[REACT] Final answer after {} real tool calls", toolCallCount);
                    return raw;
                }
                // Model gave up too early — push it to use more tools
                log.warn("[REACT] Final answer with only {} tool calls — requesting more evidence", toolCallCount);
                history.add(AiMessage.from(raw));
                history.add(UserMessage.from(
                    "You need at least 3 real tool observations before concluding. " +
                    "Please call another tool now.\nThought: I need more evidence.\nAction: "));
                continue;
            }

            // ── Extract FIRST Action only — discard anything after it ─────
            Matcher actionM = ACTION_RE.matcher(raw);
            if (!actionM.find()) {
                log.warn("[REACT] No Action found iter={}. Nudging.", iter);
                history.add(AiMessage.from(raw));
                history.add(UserMessage.from(
                    "Please call a tool using exactly:\nAction: toolName(args)\n\n" +
                    "Available: checkAllServicesHealth(), queryLogs(svc,min), queryLatency(svc,min), " +
                    "getServiceDependencies(svc), inspectQueues(), getIncidentHistory(svc)"));
                continue;
            }

            // Truncate the model output at the end of the Action line
            // This discards any hallucinated Observations that follow
            int actionEnd = actionM.end();
            String accepted = raw.substring(0, actionEnd);
            fullTrace.append(accepted).append("\n");

            history.add(AiMessage.from(accepted));

            // ── Execute the real tool ─────────────────────────────────────
            String toolCall   = actionM.group(0).replace("Action:", "").trim();
            String toolName   = actionM.group(1).trim();
            String[] args     = splitArgs(actionM.group(2));
            String observation = executeTool(toolName, args);
            toolCallCount++;

            log.info("[REACT] iter={} tool='{}' args={} → {} chars observed",
                iter + 1, toolName, java.util.Arrays.toString(args), observation.length());

            // Truncate long observations to keep context window manageable
            if (observation.length() > 1500) {
                observation = observation.substring(0, 1500) + "\n...[truncated for context]";
            }

            String observationMsg = "Observation: " + observation;
            fullTrace.append(observationMsg).append("\n\n");
            history.add(UserMessage.from(observationMsg +
                "\n\nContinue the ReAct loop. Write your next Thought and Action, " +
                "or write Final Answer if you have enough evidence (minimum 3 tool calls done: " +
                toolCallCount + ")."));
        }

        // Max iterations — force final answer
        log.warn("[REACT] Max iterations reached after {} tool calls. Forcing final answer.", toolCallCount);
        history.add(UserMessage.from(
            "Maximum iterations reached. You have made " + toolCallCount + " tool calls. " +
            "Write your Final Answer with the JSON block now based on what you observed."));

        try {
            String last = model.generate(history).content().text();
            log.info("[REACT] Forced final answer received");
            return last;
        } catch (Exception e) {
            return buildErrorJson("Max iterations: " + e.getMessage());
        }
    }

    // ── Tool dispatcher ────────────────────────────────────────────────────────

    private String executeTool(String name, String[] args) {
        log.info("[REACT] Executing: {}({})", name, String.join(", ", args));
        try {
            return switch (name) {
                case "checkAllServicesHealth"  -> healthCheckTool.checkAllServicesHealth();
                case "checkServiceHealth"      -> healthCheckTool.checkServiceHealth(s(args, 0, "order-service"));
                case "queryLogs"               -> logQueryTool.queryLogs(s(args, 0, "order-service"), i(args, 1, 10));
                case "queryAllLogs"            -> logQueryTool.queryAllLogs(s(args, 0, "order-service"), i(args, 1, 10));
                case "queryLatency"            -> metricsQueryTool.queryLatency(s(args, 0, "order-service"), i(args, 1, 10));
                case "queryHttpStatus"         -> metricsQueryTool.queryHttpStatus(s(args, 0, "order-service"), i(args, 1, 10));
                case "queryJvmMetrics"         -> metricsQueryTool.queryJvmMetrics(s(args, 0, "order-service"));
                case "getServiceTopology"      -> topologyTool.getServiceTopology();
                case "getServiceDependencies"  -> topologyTool.getServiceDependencies(s(args, 0, "order-service"));
                case "inspectQueues"           -> queueInspectionTool.inspectQueues();
                case "inspectQueue"            -> queueInspectionTool.inspectQueue(s(args, 0, "poc.notifications"));
                case "getIncidentHistory"      -> incidentHistoryTool.getIncidentHistory(s(args, 0, "order-service"));
                case "getRecentIncidents"      -> incidentHistoryTool.getRecentIncidents(i(args, 0, 7));
                default -> "Unknown tool '" + name + "'. Use one of: checkAllServicesHealth, " +
                           "queryLogs, queryLatency, getServiceDependencies, inspectQueues, getIncidentHistory";
            };
        } catch (Exception e) {
            log.error("[REACT] Tool '{}' threw: {}", name, e.getMessage());
            return "Tool error for " + name + ": " + e.getMessage();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

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
