package com.aissistants.app;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounds genuine repeated observations; never replaces a fresh result with cached output. */
final class RunGuard {
    private final Map<String, String> observations = new LinkedHashMap<String, String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > 128;
        }
    };
    private int repeated, calls;
    private boolean exhausted;

    void reset() {
        observations.clear();
        repeated = 0;
        calls = 0;
        exhausted = false;
    }

    boolean reportOnly() { return exhausted; }

    String observe(String action, String output) {
        calls++;
        String result = output == null ? "" : output;
        String previous = observations.put(action == null ? "" : action, result);
        repeated = result.equals(previous) ? repeated + 1 : 0;
        if (repeated >= 12 || calls >= 240) {
            exhausted = true;
            return "\n[RUNTIME: execution budget reached (" + calls + " calls, " + repeated
                    + " repeated observations). This is not proof the task is impossible. Report verified "
                    + "results, remaining uncertainty, and the next concrete check. No further tools this run.]";
        }
        if (repeated == 6 || repeated == 10) {
            return "\n[RUNTIME: repeated action/result pairs add no new observable evidence. "
                    + "Use an independent observation, revise the hypothesis, or report the remaining question. "
                    + "Do not mutate the device merely to count as progress.]";
        }
        if (calls % 20 == 0) {
            return "\n[RUNTIME CHECKPOINT: use save_checkpoint for goal, sourced facts, failed approaches, "
                    + "changes, verification, rollback and next action. Read-only findings count as progress; "
                    + "command success alone does not verify the outcome.]";
        }
        return "";
    }
}
