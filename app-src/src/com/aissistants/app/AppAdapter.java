package com.aissistants.app;

import java.util.ArrayList;
import java.util.List;

/**
 * One app's fast path. The adapter only DESCRIBES the steps (intents, root commands, UI conditions);
 * the executor in {@link HybridRouter} runs them generically. Adding support for another app =
 * write one subclass + register it in {@link Adapters}.
 */
abstract class AppAdapter {

    /** a single executor step */
    static final class Action {
        static final String INTENT = "intent";   // arg1 = intent action, arg2 = data uri or "app:<pkg>"
        static final String ROOT   = "root";     // arg1 = shell command
        static final String WAIT   = "wait";     // arg1 = selector, timeoutMs
        static final String TAP    = "tap";      // arg1 = selector (taps the node centre)
        static final String INPUT  = "input";    // arg1 = selector to focus, arg2 = text to type
        static final String VERIFY = "verify";   // arg1 = selector, timeoutMs
        static final String NOTE   = "note";     // arg1 = text for the transcript

        final String kind, arg1, arg2;
        final int timeoutMs;

        Action(String kind, String arg1, String arg2, int timeoutMs) {
            this.kind = kind; this.arg1 = arg1; this.arg2 = arg2; this.timeoutMs = timeoutMs;
        }

        static Action intent(String action, String uri) { return new Action(INTENT, action, uri, 0); }
        static Action root(String cmd) { return new Action(ROOT, cmd, "", 30); }
        static Action wait(String sel, int ms) { return new Action(WAIT, sel, "", ms); }
        static Action tap(String sel) { return new Action(TAP, sel, "", 0); }
        static Action input(String sel, String text) { return new Action(INPUT, sel, text, 0); }
        static Action verify(String sel, int ms) { return new Action(VERIFY, sel, "", ms); }
        static Action note(String t) { return new Action(NOTE, t, "", 0); }
    }

    /** ordered steps plus what a verified run should say */
    static final class Plan {
        final String pkg;
        final String title;
        final List<Action> steps = new ArrayList<Action>();
        String success = "Selesai.";
        String risk = "none";
        String confirmReason = "";
        boolean showOutput = false;      // append the shell output to the transcript bubble

        Plan(String pkg, String title) { this.pkg = pkg; this.title = title; }

        Plan add(Action a) { steps.add(a); return this; }
        Plan risk(String r, String reason) { this.risk = r; this.confirmReason = reason; return this; }
        Plan success(String s) { this.success = s; return this; }
    }

    abstract String pkg();
    abstract String name();

    /** can this adapter serve that parsed action? */
    abstract boolean handles(String action);

    /** describe the run; return null when this adapter cannot serve the spec */
    abstract Plan plan(LocalParser.Spec spec, HybridRouter.Host host);

    // selector helpers --------------------------------------------------------------
    static String id(String s) { return "id:" + s; }
    static String text(String s) { return "text:" + s; }
    static String desc(String s) { return "desc:" + s; }
    static String act(String s) { return "act:" + s; }
}
