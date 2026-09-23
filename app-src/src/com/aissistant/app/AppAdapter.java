package com.aissistant.app;

import java.util.ArrayList;
import java.util.List;

/**
 * One app's fast path. The adapter only DESCRIBES the steps (intents, root commands, UI conditions);
 * the executor in the deleted local path runs them generically. Adding support for another app =
 * write one subclass + kept for reference.
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
    abstract Plan plan(Spec spec, Host host);

    // selector helpers --------------------------------------------------------------
    static String id(String s) { return "id:" + s; }
    static String text(String s) { return "text:" + s; }
    static String desc(String s) { return "desc:" + s; }
    static String act(String s) { return "act:" + s; }

    /** The local fast path was deleted. These types survive only because GenericApp needs them. */
    static final class Spec {
        String action = "", app = "", pkg = "", target = "", text = "";
        String risk = "none";
        double confidence = 0;
        String why = "";
        boolean executable() { return !action.isEmpty() && confidence >= 0.7; }
        @Override public String toString() { return action + (app.isEmpty() ? "" : " app=" + app); }
    }

    interface Host {
        String run(String cmd, int timeoutSec);
        String contactNumber(String name);
        android.content.Context ctx();
        void bubble(String role, String text);
    }

    private static String genericSearchButton(String pkg) {
        return AppAdapter.desc("Search") + "|" + AppAdapter.desc("Cari") + "|" + AppAdapter.text("Search") + "|" + AppAdapter.text("Cari")
                + "|" + AppAdapter.id(pkg + ":id/search") + "|" + AppAdapter.id(pkg + ":id/menu_search")
                + "|" + AppAdapter.id(pkg + ":id/search_button") + "|" + AppAdapter.id(pkg + ":id/action_search");
    }
    private static String genericSearchField(String pkg) {
        return AppAdapter.id(pkg + ":id/search_src_text") + "|" + AppAdapter.id(pkg + ":id/search_text")
                + "|" + AppAdapter.id(pkg + ":id/search_input") + "|" + AppAdapter.id(pkg + ":id/search_edit_text")
                + "|" + AppAdapter.text("Search") + "|" + AppAdapter.text("Cari") + "|" + AppAdapter.desc("Search") + "|" + AppAdapter.desc("Cari");
    }

    /** the user's own adapter, moved here when the local executor was deleted */
    static final class GenericApp extends AppAdapter {
        @Override String pkg() { return ""; }
        @Override String name() { return "App"; }
        @Override boolean handles(String action) {
            return "open".equals(action) || "search".equals(action);
        }
        @Override Plan plan(Spec spec, Host host) {
            if (spec.pkg.isEmpty()) return null;
            String label = spec.app.isEmpty() ? spec.pkg : spec.app;
            if ("open".equals(spec.action)) {
                return new Plan(spec.pkg, "Buka " + label)
                        .add(Action.intent("android.intent.action.MAIN", "app:" + spec.pkg))
                        .success("Dibuka: " + label);
            }
            String searchBtn = genericSearchButton(spec.pkg);
            String searchField = genericSearchField(spec.pkg);
            return new Plan(spec.pkg, "Cari di " + label + ": " + spec.target)
                    .add(Action.intent("android.intent.action.MAIN", "app:" + spec.pkg))
                    .add(Action.wait(searchBtn + "|" + searchField, 7000))
                    .add(Action.tap(searchBtn + "|" + searchField))
                    .add(Action.wait(searchField, 5000))
                    .add(Action.input(searchField, spec.target))
                    .add(Action.verify(text(spec.target), 8000))
                    .success("Hasil pencarian \"" + spec.target + "\" dibuka di " + label + ".");
        }
    }
}
