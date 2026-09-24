package com.aissistant.app;

import java.util.Locale;

/** Shared, Android-free command policy for persistent tool reuse and artifact routing. */
final class ToolPolicy {
    private ToolPolicy() { }

    static boolean usesToolInventory(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase(Locale.ENGLISH);
        return c.contains("agent_tool_list") || c.contains("agent_tool_find")
                || c.contains("tool-index.tsv") || c.contains("agent-tools.md")
                || c.contains("tool-candidates.tsv");
    }

    static boolean toolAcquisitionAttempt(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase(Locale.ENGLISH);
        boolean installer = c.matches("(?s).*\\b(pkg|apt|apt-get|pip|pip3|npm|gem|go)\\s+install\\b.*");
        boolean downloader = c.matches("(?s).*\\b(curl|wget|aria2c)\\b.*");
        if (!installer && !downloader) return false;
        if (installer) return true;
        return c.matches("(?s).*\\b(jdk|java|python|node|apktool|jadx|smali|baksmali|frida|apksigner|zipalign|android-sdk|gradle|nmap|tcpdump)\\b.*");
    }
    /** After a target UI gate is visible, block metadata/inventory detours but allow source materialization. */
    static boolean postBaselineArtifactDetour(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase(Locale.ENGLISH);
        if (directSourceTransformation(c) || targetArtifactStagingOnly(c) || directDexExtraction(c)) return false;
        if (c.matches("(?s).*\\b(aapt|aapt2|zipinfo|readelf|strings|sha(?:1|256)?sum|md5sum|file)\\b.*")) return true;
        if (c.matches("(?s).*\\bunzip\\s+-l\\b.*")) return true;
        if (c.matches("(?s).*\\bunzip\\b.*\\.apk\\b.*")) return true;
        if (c.matches("(?s).*\\bcp\\b.*\\.apk\\b.*")) return true;
        return (c.contains("$tools") || c.contains("/tools/"))
                && c.matches("(?s).*\\b(ls|find|cat)\\b.*");
    }

    /** A decompiler/disassembler creates the source branch demanded by the UI gate; it is not inventory. */
    static boolean directSourceTransformation(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase(Locale.ENGLISH);
        return c.matches("(?s).*\\b(?:apktool(?:\\.jar)?|baksmali)\\b.*(?:^|\\s)d(?:\\s|$).*")
                || c.matches("(?s).*\\bjadx(?:\\.cli\\.jadxcli)?\\b.*\\s-d(?:\\s|$).*");
    }

    /** Copying the known APK into the session workspace is staging, not analysis, when it stands alone. */
    static boolean targetArtifactStagingOnly(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase(Locale.ENGLISH);
        if (!c.matches("(?s).*\\bcp\\b.*\\.apk\\b.*")) return false;
        if (c.matches("(?s).*\\b(unzip|zipinfo|aapt|aapt2|jadx|apktool|baksmali|strings|readelf|objdump|xxd|hexdump|file|sha(?:1|256)?sum|md5sum|grep|rg|find|ls)\\b.*")) return false;
        return c.contains("$wd") || c.matches("(?s).*\\s(?:\\./)?[^\\s;|&]+\\.apk\\b.*")
                || c.contains("/proc/1/root/data/app/") || c.contains("/data/app/");
    }

    /** Extracting only DEX files is bounded source materialization for a following disassembler. */
    static boolean directDexExtraction(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase(Locale.ENGLISH);
        if (!c.matches("(?s).*\\bunzip\\b.*\\.apk\\b.*")) return false;
        if (!(c.contains("classes.dex") || c.contains("classes*.dex")
                || c.matches("(?s).*classes[0-9]+\\.dex.*"))) return false;
        return !c.matches("(?s).*\\b(unzip\\s+-l|zipinfo|aapt|aapt2|strings|readelf|file|sha(?:1|256)?sum|md5sum|ls|find)\\b.*");
    }
}


