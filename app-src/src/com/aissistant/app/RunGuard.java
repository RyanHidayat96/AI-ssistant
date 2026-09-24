package com.aissistant.app;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounds repeated work and asks for capability triage before an agent mistakes a recurring failure for progress. */
final class RunGuard {
    private final Map<String, String> observations = new LinkedHashMap<String, String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > 128;
        }
    };
    /** Counts one error class across different checks; it nudges triage but never proves a task impossible. */
    private final Map<String, Integer> failureFamilies = new LinkedHashMap<String, Integer>();
    private static final Pattern ONE_LINE_SED = Pattern.compile("\\bsed\\s+-n\\s+['\\\"]?(\\d+)p['\\\"]?");
    private int repeated, calls, consecutiveEvidenceReads, consecutiveMicroSlices;
    private String microSliceFamily = "";
    private boolean exhausted;

    void reset() {
        observations.clear();
        failureFamilies.clear();
        repeated = 0;
        calls = 0;
        consecutiveEvidenceReads = 0;
        consecutiveMicroSlices = 0;
        microSliceFamily = "";
        exhausted = false;
    }

    boolean reportOnly() { return exhausted; }

    String observe(String action, String output) {
        calls++;
        String key = action == null ? "" : action;
        String result = output == null ? "" : output;
        String previous = observations.put(key, result);
        repeated = result.equals(previous) ? repeated + 1 : 0;
        boolean evidenceRead = key.trim().startsWith("read_evidence");
        consecutiveEvidenceReads = evidenceRead ? consecutiveEvidenceReads + 1 : 0;
        String microFamily = oneLineSliceFamily(key);
        if (!microFamily.isEmpty() && microFamily.equals(microSliceFamily)) {
            consecutiveMicroSlices++;
        } else {
            microSliceFamily = microFamily;
            consecutiveMicroSlices = microFamily.isEmpty() ? 0 : 1;
        }
        String triage = capabilityTriage(previous == null ? failureFamily(result) : null);
        if (consecutiveMicroSlices >= 6) {
            exhausted = true;
            return triage + "\n[RUNTIME: six sequential one-line slices from the same text pipeline produced fragments, not a decision. "
                    + "Stop line-by-line paging. State the current hypothesis and facts, then on resume use one bounded coherent excerpt or a structural query tied to that hypothesis. No further tools this run.]";
        }
        if (consecutiveMicroSlices == 4) {
            return triage + "\n[RUNTIME: four sequential one-line slices from the same text pipeline. Do not keep paging individual lines. "
                    + "Use a bounded coherent excerpt or a structural query that can resolve the current hypothesis, then synthesize the finding.]";
        }
        if (consecutiveEvidenceReads >= 4) {
            exhausted = true;
            return triage + "\n[RUNTIME: four consecutive historical-evidence retrievals add context but no fresh observation. "
                    + "Stop retrieving evidence, report the facts already extracted, and choose one concrete next action on resume. No further tools this run.]";
        }
        if (consecutiveEvidenceReads == 3) {
            return triage + "\n[RUNTIME: three consecutive historical-evidence retrievals. Do not retrieve evidence of a retrieval or follow a reference chain. "
                    + "Use the source facts now, or make one fresh, task-relevant observation.]";
        }
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

    /** Detect line-by-line paging while allowing a bounded source/text excerpt to continue normally. */
    private static String oneLineSliceFamily(String action) {
        if (action == null) return "";
        String low = action.toLowerCase(Locale.US);
        Matcher m = ONE_LINE_SED.matcher(low);
        if (!m.find()) return "";
        return low.substring(0, m.start(1)) + "#" + low.substring(m.end(1));
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
