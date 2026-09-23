package com.aissistant.app;

/**
 * Defense in depth for shell diagnostics returned to the model.
 *
 * Target-window Accessibility never exposes this app's nodes. This parser removes self-owned
 * entries from shell diagnostics too, including dumps where the package name appears after the
 * block header. Raw visual capture uses its own short exclusive phase.
 */
final class AgentWindowFilter {
    private AgentWindowFilter() { }

    static String hideSelfOverlays(String raw, String packageName) {
        if (raw == null || raw.isEmpty() || packageName == null || packageName.isEmpty()) {
            return raw == null ? "" : raw;
        }
        String packageMarker = packageName.toLowerCase(java.util.Locale.ENGLISH);
        String[] lines = raw.split("\\r?\\n", -1);
        StringBuilder kept = new StringBuilder(raw.length());
        StringBuilder windowBlock = new StringBuilder();
        boolean inWindowBlock = false;
        boolean ownWindowBlock = false;
        int looseSurfaceFields = 0;

        for (String line : lines) {
            if (isWindowHeader(line)) {
                appendBlock(kept, windowBlock, ownWindowBlock);
                windowBlock.setLength(0);
                inWindowBlock = true;
                ownWindowBlock = false;
            }

            String lower = line.toLowerCase(java.util.Locale.ENGLISH);
            if (inWindowBlock) {
                if (lower.contains(packageMarker)) ownWindowBlock = true;
                appendLine(windowBlock, line);
                continue;
            }

            if (isOwnSurfaceLine(lower, packageMarker)) {
                // grep and some dumps omit the surrounding Window # header. Drop the short
                // diagnostics tail that belongs to this one self-owned surface as well.
                looseSurfaceFields = 12;
                continue;
            }
            if (looseSurfaceFields > 0 && startsIndented(line) && isSurfaceDetail(lower)) {
                looseSurfaceFields--;
                continue;
            }
            looseSurfaceFields = 0;
            appendLine(kept, line);
        }
        appendBlock(kept, windowBlock, ownWindowBlock);
        return kept.toString();
    }

    private static boolean isWindowHeader(String line) {
        String t = line == null ? "" : line.trim().toLowerCase(java.util.Locale.ENGLISH);
        return t.startsWith("window #")
                || t.startsWith("window{")
                || t.startsWith("windowhandle")
                || t.startsWith("window handle")
                || t.startsWith("input window");
    }

    private static boolean isOwnSurfaceLine(String lower, String packageMarker) {
        if (!lower.contains(packageMarker)) return false;
        return lower.contains("sys2038")
                || lower.contains("window")
                || lower.contains("overlay")
                || lower.contains("touchable")
                || lower.contains("focus")
                || lower.contains("input");
    }

    private static boolean isSurfaceDetail(String lower) {
        return lower.contains("touchableregion")
                || lower.contains("frame=")
                || lower.contains("alpha=")
                || lower.contains("owneruid")
                || lower.contains("visible")
                || lower.contains("focus")
                || lower.contains("inputconfig")
                || lower.contains("layoutparams")
                || lower.contains("type=");
    }

    private static boolean startsIndented(String line) {
        return line != null && !line.isEmpty()
                && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
    }

    private static void appendBlock(StringBuilder kept, StringBuilder block, boolean discard) {
        if (!discard && block.length() > 0) {
            if (kept.length() > 0) kept.append('\n');
            kept.append(block);
        }
    }

    private static void appendLine(StringBuilder out, String line) {
        if (out.length() > 0) out.append('\n');
        out.append(line);
    }
}
