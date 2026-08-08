package br.ufla.poc.agent;

/**
 * DiagnosticAgent abstraction used by the orchestration layer.
 * The concrete implementation is ReActDiagnosticAgent.
 */
public interface DiagnosticAgent {
    String diagnose(String problem);

    /**
     * Returns the number of real tool executions performed by the most recent
     * diagnose() call on the current request thread and clears that value.
     *
     * This avoids deriving per-execution metrics from accumulated audit rows
     * that share the same scenario identifier across repeated experiments.
     */
    int consumeLastToolCallCount();
}
