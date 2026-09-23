package com.aissistant.app;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * The live feed the floating overlay reads: one shared, thread-safe place the activity writes to
 * (status line + transcript lines) while the service paints it over whatever app is in front.
 */
final class OverlayHub {

    private static final int MAX_LINES = 400;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final Object LOCK = new Object();

    private static volatile String status = "";
    private static volatile boolean stopRequested = false;
    private static volatile boolean visible = false;
    private static volatile boolean busy = false;
    /** Clean capture window: do not recreate the panel until the current screenshot/dump finishes. */
    private static volatile boolean suppressedForDriving = false;
    /** True while a root/UI command is targeting another app; any overlay created now must ignore touch. */
    private static volatile boolean agentPassThrough = false;
    /**
     * Strict isolation for an active agent run.  The assistant's own app-owned windows must not
     * exist while its shell/accessibility tools inspect or drive another app.  This is deliberately
     * stronger than just removing focus: root-injected input and window diagnostics otherwise still
     * observe the same overlay.
     */
    private static volatile boolean agentIsolation = false;
    private static volatile int version = 0;

    private OverlayHub() { }

    static void setStatus(String s) { status = s == null ? "" : s; }
    static String status() { return status; }

    /** true while the agent is working - the overlay labels its button STOP vs Kirim */
    static void setBusy(boolean b) { busy = b; }
    static boolean busy() { return busy; }

    /** bumped on every line change, so the overlay repaints only when something moved */
    static int version() { return version; }

    /** the whole mirrored transcript, for scrolling back through the chat in the panel */
    static ArrayList<String> all() {
        synchronized (LOCK) { return new ArrayList<String>(LINES); }
    }

    static void line(String s) {
        if (s == null || s.trim().isEmpty()) return;
        String t = s.trim();
        String one = t.length() > 600 ? t.substring(0, 600) + "…" : t;
        synchronized (LOCK) {
            LINES.addLast(one);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
            version++;
        }
    }

    static ArrayList<String> tail(int n) {
        synchronized (LOCK) {
            ArrayList<String> out = new ArrayList<String>();
            int skip = Math.max(0, LINES.size() - n);
            int i = 0;
            for (String s : LINES) {
                if (i++ >= skip) out.add(s);
            }
            return out;
        }
    }

    /** replace everything - used to seed the panel with the session's real chat history */
    static void setAll(java.util.List<String> lines) {
        synchronized (LOCK) {
            LINES.clear();
            if (lines != null) {
                for (String s : lines) {
                    if (s == null || s.trim().isEmpty()) continue;
                    String one = s.trim();
                    if (one.length() > 600) one = one.substring(0, 600) + "\u2026";
                    LINES.addLast(one);
                    while (LINES.size() > MAX_LINES) LINES.removeFirst();
                }
            }
            version++;
        }
    }

    static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            version++;
        }
        status = "";
        stopRequested = false;
    }

    static boolean stopRequested() { return stopRequested; }
    static void resetStop() { stopRequested = false; }

    /** the overlay's STOP: same effect as the in-app Stop button */
    static void requestStop() {
        stopRequested = true;
        try { AiClient.cancel(); } catch (Throwable ignored) { }
        try { RootShell.cancel(); } catch (Throwable ignored) { }
    }

    static void setVisible(boolean v) { visible = v; }
    static boolean isVisible() { return visible; }

    static void suppressForDriving() { suppressedForDriving = true; }
    static void allowOverlayForRun() { suppressedForDriving = false; }
    static boolean overlaySuppressedForDriving() { return suppressedForDriving; }
    static void setAgentPassThrough(boolean v) { agentPassThrough = v; }
    static boolean agentPassThrough() { return agentPassThrough; }

    /** Run is active: any panel shown between commands remains visual-only. */
    static void beginAgentRun() {
        agentIsolation = false;
        agentPassThrough = true;
        suppressedForDriving = false;
    }

    /** A command is about to inspect or drive the screen: remove every self-owned surface. */
    static void enterAgentIsolation() {
        agentIsolation = true;
        agentPassThrough = true;
        suppressedForDriving = true;
    }

    /** Command completed; preserve visual-only mode until the full run has ended. */
    static void leaveAgentIsolation() {
        agentIsolation = false;
        suppressedForDriving = false;
    }

    /** Terminal cleanup: user can use the overlay normally again. */
    static void finishAgentRun() {
        agentIsolation = false;
        agentPassThrough = false;
        suppressedForDriving = false;
    }

    static boolean agentIsolation() { return agentIsolation; }
}
