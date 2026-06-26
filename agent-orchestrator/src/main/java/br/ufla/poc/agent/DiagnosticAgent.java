package br.ufla.poc.agent;

/**
 * DiagnosticAgent — marker interface.
 * The actual agent logic is implemented in ReActDiagnosticAgent using
 * the ReAct (Reasoning + Acting) loop, which works with any Ollama model
 * regardless of native tool-calling support.
 */
public interface DiagnosticAgent {
    String diagnose(String problem);
}
