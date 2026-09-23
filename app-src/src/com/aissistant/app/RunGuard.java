package com.aissistant.app;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounds repeated work and asks for capability triage before an agent mistakes a recurring failure for progress. */
final class RunGuard {
    private final Map<String, String> observations = new LinkedHashMap<String, String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > 128;
        }
    };
    /** Counts one error class across different checks; it nudges triage but never proves a task impossible. */
    private final Map<String, Integer> failureFamilies = new LinkedHashMap<String, Integer>();
    private int repeated, calls;
    private boolean exhausted;

    void reset() {
        observations.clear();
        failureFamilies.clear();
        repeated = 0;
        calls = 0;
        exhausted = false;
    }

    boolean reportOnly() { return exhausted; }

    String observe(String action, String output) {
        calls++;
        String key = action == null ? "" : action;
        String result = output == null ? "" : output;
        String previous = observations.put(key, result);
        repeated = result.equals(previous) ? repeated + 1 : 0;
        String triage = capabilityTriage(previous == null ? failureFamily(result) : null);
        if (repeated >= 12 || calls >= 240) {
            exhausted = true;
            return triage + "\n[RUNTIME: execution budget reached (" + calls + " calls, " + repeated
                    + " repeated observations). This is not proof the task is impossible. Report verified "
                    + "results, remaining uncertainty, and the next concrete check. No further tools this run.]";
        }
        if (repeated == 6 || repeated == 10) {
            return triage + "\n[RUNTIME: repeated action/result pairs add no new observable evidence. "
                    + "Use an independent observation, revise the hypothesis, or report the remaining question. "
                    + "Do not mutate the device merely to count as progress.]";
        }
        if (calls % 20 == 0) {
            return triage + "\n[RUNTIME CHECKPOINT: use save_checkpoint for goal, sourced facts, failed approaches, "
                    + "changes, verification, rollback and next action. Read-only findings count as progress; "
                    + "command success alone does not verify the outcome.]";
        }
        return triage;
    }

    private String capabilityTriage(String family) {
        if (family == null) return "";
        Integer old = failureFamilies.get(family);
        int count = old == null ? 1 : old + 1;
        failureFamilies.put(family, count);
        if (count != 4 && count != 8) return "";
        return "\n[CAPABILITY TRIAGE: " + family + " appeared across " + count
                + " distinct checks. This is not proof the task is blocked. Map the requested outcome to its exact "
                + "capability, probe the relevant prerequisite, try a compatible agent-resolvable setup or different path, "
                + "verify it, then report an external requirement only with evidence, ruled-out paths, minimum requirement, "
                + "and a resume step.]";
    }

    private static String failureFamily(String output) {
        String low = output == null ? "" : output.toLowerCase(Locale.US);
        if (low.contains("no such device") || low.contains("device not present") || low.contains("camera unavailable")
                || low.contains("no camera") || low.contains("hardware not supported")) return "HARDWARE_OR_INTERFACE";
        if (low.contains("permission denied") || low.contains("unauthorized") || low.contains("forbidden")
                || low.contains("http 401") || low.contains("http 403")) return "ACCESS_OR_ACCOUNT";
        if (low.contains("no space left") || low.contains("out of memory") || low.contains("insufficient storage")) return "RESOURCE";
        if (low.contains("network is unreachable") || low.contains("no route to host") || low.contains("connection refused")
                || low.contains("unknown host") || low.contains("dns")) return "REMOTE_OR_NETWORK";
        if (low.contains("not found") || low.contains("exit 127") || low.contains("not executable")
                || low.contains("exec format error")) return "TOOL_OR_RUNTIME";
        if (low.contains("operation not supported") || low.contains("unsupported")) return "UNSUPPORTED_MECHANISM";
        return null;
    }
}