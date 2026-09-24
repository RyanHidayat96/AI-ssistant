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
    private static final Pattern PAGED_SED = Pattern.compile("\\bsed\\s+-n\\s+['\\\"]?(\\d+(?:,\\d+)?)p['\\\"]?");
    private static final Pattern LITERAL_SEARCH = Pattern.compile("\\b(?:grep|rg)\\b");
    private static final int STATIC_ARTIFACT_NUDGE_AFTER = 20;
    private static final int STATIC_ARTIFACT_REPORT_AFTER = 120;
    private int repeated, calls, consecutiveEvidenceReads, consecutiveMicroSlices, consecutiveEmptyLiteralSearches;
    /** Cumulative read-only artifact work since last target interaction; prevents analysis becoming task avoidance. */
    private int staticArtifactReads;
    private String microSliceFamily = "";
    private boolean exhausted;

    void reset() {
        observations.clear();
        failureFamilies.clear();
        repeated = 0;
        calls = 0;
        consecutiveEvidenceReads = 0;
        consecutiveMicroSlices = 0;
        consecutiveEmptyLiteralSearches = 0;
        staticArtifactReads = 0;
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
        consecutiveEmptyLiteralSearches = emptyLiteralSearch(key, result)
                ? consecutiveEmptyLiteralSearches + 1 : 0;
        if (isTargetInteraction(key)) staticArtifactReads = 0;
        else if (staticArtifactRead(key)) staticArtifactReads++;
        String triage = capabilityTriage(previous == null ? failureFamily(result) : null);
        if (staticArtifactReads >= STATIC_ARTIFACT_REPORT_AFTER) {
            exhausted = true;
            return triage + "\n[RUNTIME: " + STATIC_ARTIFACT_REPORT_AFTER
                    + " read-only artifact checks occurred without another target interaction. Stop inspection now. "
                    + "State the verified gate, strongest source/runtime facts, and one bounded action or decisive test for resume. "
                    + "No further tools this run.]";
        }
        if (staticArtifactReads == STATIC_ARTIFACT_NUDGE_AFTER) {
            return triage + "\n[RUNTIME: " + STATIC_ARTIFACT_NUDGE_AFTER
                    + " read-only artifact checks occurred. Do not keep inventorying or paging source. "
                    + "Choose one current branch: a writable change location, runtime predicate, configuration condition, "
                    + "or behavior test; execute the bounded action that resolves it.]";
        }
        if (consecutiveEmptyLiteralSearches >= 5) {
            exhausted = true;
            return triage + "\n[RUNTIME: five literal searches produced no match. Stop searching the same representation. "
                    + "State the current facts and use source-to-derived mapping or one structural/runtime observation on resume. "
                    + "No further tools this run.]";
        }
        if (consecutiveEmptyLiteralSearches == 3) {
            return triage + "\n[RUNTIME: three literal searches produced no match. A decoded, indexed, or normalized value "
                    + "may not occur verbatim in the source. Stop literal searching; map the derived value to its source "
                    + "location or make one structural/runtime observation.]";
        }
        if (consecutiveMicroSlices >= 5) {
            exhausted = true;
            return triage + "\n[RUNTIME: five sequential paged slices from the same text pipeline produced fragments, not a decision. "
                    + "Stop paging output. State the current hypothesis and facts, then on resume use one bounded coherent excerpt or a structural query tied to that hypothesis. No further tools this run.]";
        }
        if (consecutiveMicroSlices == 3) {
            return triage + "\n[RUNTIME: three sequential paged slices from the same text pipeline. Do not keep paging output. "
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
        if (repeated >= 2 || calls >= 400) {
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

    /** Read-only APK/DEX/native/source inspection is useful only until it yields an action branch. */
    private static boolean staticArtifactRead(String action) {
        if (action == null) return false;
        String low = action.toLowerCase(Locale.US);
        boolean artifact = low.contains(".apk") || low.contains(".dex") || low.contains(".smali")
                || low.contains(".so") || low.contains("jadx") || low.contains("apktool")
                || low.contains("baksmali") || low.contains("/sources") || low.contains("$wd/dec")
                || low.contains("/dec/") || low.contains(" dec/");
        if (!artifact) return false;
        if (low.matches("(?s).*\\b(sed\\s+-i|tee|cp|mv|rm|chmod|chown|zipalign|apksigner|install|uninstall|apktool\\s+b|smali\\s+assemble)\\b.*")
                || low.matches("(?s).*[^0-9]>{1,2}\\s*(?!/dev/null\\b).*")) return false;
        return low.matches("(?s).*\\b(cat|grep|rg|sed|awk|head|tail|strings|readelf|objdump|xxd|hexdump|wc|find|ls|unzip|zipinfo|aapt|aapt2|jadx|baksmali)\\b.*");
    }

    /** An Accessibility interaction is a fresh behavior boundary for later artifact work. */
    private static boolean isTargetInteraction(String action) {
        if (action == null) return false;
        String low = action.trim().toLowerCase(Locale.US);
        return low.startsWith("act_app ") || low.startsWith("scroll_app ");
    }

    /** Detect sequential output paging while allowing a bounded source/text excerpt to continue normally. */
    private static String oneLineSliceFamily(String action) {
        if (action == null) return "";
        String low = action.toLowerCase(Locale.US);
        Matcher m = PAGED_SED.matcher(low);
        if (!m.find()) return "";
        // Drop a leading counting/banner clause so `wc -l; producer | sed …` stays in the
        // same family as the producer alone. The selected range is deliberately normalized.
        int boundary = low.lastIndexOf(';', m.start(1));
        String prefix = low.substring(boundary + 1, m.start(1)).trim();
        return prefix + "#" + low.substring(m.end(1));
    }

    /** A sequence of no-match grep/rg searches is usually a representation mistake, not new evidence. */
    private static boolean emptyLiteralSearch(String action, String output) {
        if (action == null || !LITERAL_SEARCH.matcher(action.toLowerCase(Locale.US)).find()) return false;
        String result = output == null ? "" : output.trim();
        if (result.isEmpty()) return true;
        boolean diagnosticsOnly = false;
        for (String line : result.split("\\r?\\n")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("===")
                    || value.matches("(?i)\\[exit\\s+1\\]") || value.matches("(?i)exit\\s*=\\s*1")) {
                diagnosticsOnly = true;
                continue;
            }
            return false;
        }
        return diagnosticsOnly;
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
