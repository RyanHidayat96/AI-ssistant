package com.aissistant.app;

import java.util.Locale;

/** Shared, Android-free command policy for persistent tool reuse. */
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
}
