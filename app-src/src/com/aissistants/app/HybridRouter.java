package com.aissistants.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hierarchical router: local parser -> capability registry -> adapter plan -> event-driven
 * executor -> verify. Only when nothing here can serve the request does the generic LLM agent run.
 *
 * The executor never waits a fixed time for the UI: it polls conditions (window focus / node
 * selectors) with a deadline, and it reuses the app's single root shell.
 */
final class HybridRouter {

    /** everything the router needs from the activity */
    interface Host {
        int DENY = 3, ONCE = 0, CHAT = 1, ALWAYS = 2;      // mirrors MainActivity PERM_*
        Context ctx();
        void bubble(String role, String text);
        void status(String text);
        String sessionId();
        boolean stopped();
        boolean autoApprove();
        boolean allowAlways(String cat);
        boolean allowChat(String cat);
        int ask(String cat, String reason);
        void setLastReason(String text);
        void audit(String cat, String verdict, String note);
        String contactNumber(String name);
        String run(String cmd, int timeoutSec);
        /** the app's own gate category for a shell command (null = not gated) */
        String categoryOf(String cmd);
        /** package for a user-typed app name, or "" when it is not installed */
        String packageIfInstalled(String name);
    }

    static final class Result {
        boolean handled;
        String summary = "";
        String error = "";
        String planTitle = "";
        LocalParser.Spec spec;
        long ms;
        String text() { return summary.isEmpty() ? error : summary; }
    }

    private static final Pattern NODE = Pattern.compile("<node[^>]*>");
    private static final Pattern BOUNDS =
            Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");

    private HybridRouter() { }

    // ---- entry point -------------------------------------------------------------------------

    /** parse + plan + execute. Never throws. */
    static Result routeAndRun(Host host, String text, LocalParser.AppLookup lookup) {
        try {
            // 1) plain device instruction: one shell chain through the persistent root shell
            AppAdapter.Plan fast = FastTasks.plan(text, host);
            if (fast != null) {
                android.util.Log.i("AIssistants", "fast task: " + fast.title + " | risk=" + fast.risk);
                return runPlan(host, fast, null);
            }
            // 2) app action through the capability registry
            return runSpec(host, LocalParser.parse(text, lookup));
        } catch (Throwable t) {
            Result bad = new Result();
            bad.error = "parse error: " + t;
            return bad;
        }
    }

    /** execute an already-parsed spec (used by the model planner too) */
    static Result runSpec(Host host, LocalParser.Spec spec) {
        long t0 = SystemClock.elapsedRealtime();
        Result r = new Result();
        try {
            r.spec = spec;
            if (spec.action.isEmpty()) {
                r.error = "tidak ada perintah terstruktur yang terbaca";
                return r;
            }
            host.bubble("note", "parser lokal \u00b7 " + spec);
            if ("shell".equals(spec.action)) {          // planner asked for a shell command
                AppAdapter.Plan sp = new AppAdapter.Plan("", "Perintah shell (planner)");
                sp.add(AppAdapter.Action.root(spec.text));
                sp.showOutput = true;
                sp.success("Perintah dijalankan.");
                String cat = host.categoryOf(spec.text);
                if (cat != null && !cat.isEmpty()) {
                    sp.risk = cat;
                    sp.confirmReason = "menjalankan: " + spec.text;
                }
                return runPlan(host, sp, spec);
            }
            if (!spec.executable()) {
                r.error = "confidence " + String.format(java.util.Locale.US, "%.2f", spec.confidence)
                        + " \u00b7 " + spec.why;
                return r;
            }
            AppAdapter adapter = null;
            if (!spec.pkg.isEmpty()) adapter = Adapters.forPackage(spec.pkg);
            if ((adapter == null || !adapter.handles(spec.action)) && !spec.pkg.isEmpty()) {
                AppAdapter generic = new GenericApp();
                if (generic.handles(spec.action)) adapter = generic;
            }
            if (adapter == null || !adapter.handles(spec.action)) {
                AppAdapter byAction = Adapters.forAction(spec.action, spec.app);
                if (byAction != null) adapter = byAction;
            }
            if (adapter == null || !adapter.handles(spec.action)) {
                r.error = "belum ada adapter untuk action=" + spec.action
                        + (spec.pkg.isEmpty() ? "" : " pkg=" + spec.pkg);
                return r;
            }
            AppAdapter.Plan plan = adapter.plan(spec, host);
            if (plan == null) {
                r.error = "adapter " + adapter.name() + " tidak bisa menyusun rencana";
                return r;
            }
            android.util.Log.i("AIssistants", "hybrid plan: " + adapter.name() + " | " + plan.title
                    + " | steps=" + plan.steps.size() + " | risk=" + plan.risk);
            return runPlan(host, plan, spec);
        } catch (Throwable t) {
            r.error = "router error: " + t;
            return r;
        } finally {
            if (r.ms == 0) r.ms = SystemClock.elapsedRealtime() - t0;
        }
    }

    /** gate if needed, run the steps, verify, and describe what happened */
    static Result runPlan(Host host, AppAdapter.Plan plan, LocalParser.Spec spec) {
        long t0 = SystemClock.elapsedRealtime();
        Result r = new Result();
        r.spec = spec;
        r.planTitle = plan.title;
        try {
            if (plan.risk != null && !"none".equals(plan.risk)) {
                String cat = "external_write".equals(plan.risk) ? "messaging" : plan.risk;
                boolean pre = host.autoApprove() || host.allowAlways(cat) || host.allowChat(cat);
                if (pre) {
                    host.audit(cat, "ALLOW-POLICY", plan.title);
                } else {
                    host.setLastReason(plan.confirmReason);
                    int verdict = host.ask(cat, plan.confirmReason);
                    if (verdict == Host.DENY) {
                        host.audit(cat, "DENY", plan.title);
                        r.handled = true;
                        r.summary = "Dibatalkan \u00b7 " + plan.confirmReason;
                        r.ms = SystemClock.elapsedRealtime() - t0;
                        return r;
                    }
                    host.audit(cat, verdict == Host.ALWAYS ? "ALLOW-ALWAYS"
                            : (verdict == Host.CHAT ? "ALLOW-CHAT" : "ALLOW-ONCE"), plan.title);
                }
            }

            final StringBuilder out = new StringBuilder();
            for (AppAdapter.Action a : plan.steps) {
                if (host.stopped()) {
                    r.error = "dihentikan oleh user";
                    return r;
                }
                host.status(plan.title + " \u00b7 " + stepLabel(a));
                String err = step(host, a, out);
                if (err != null) {
                    String tail = out.length() == 0 ? "" : " \u00b7 output: " + tail(out.toString(), 200);
                    r.error = err + tail;
                    android.util.Log.e("AIssistants", "hybrid step failed: " + err);
                    return r;
                }
            }
            r.handled = true;
            String produced = tail(out.toString(), 1400);
            r.summary = plan.success.replace("{out}", produced);
            if (plan.showOutput && produced.length() > 0 && !plan.success.contains("{out}")) {
                r.summary = plan.success + "\n\n" + produced;
            }
            if (plan.showOutput && produced.length() == 0) {
                r.summary = plan.success + "\n\n(tidak ada output)";
            }
            r.ms = SystemClock.elapsedRealtime() - t0;
            android.util.Log.i("AIssistants", "hybrid done in " + r.ms + "ms: " + r.summary);
            return r;
        } catch (Throwable t) {
            r.error = "router error: " + t;
            return r;
        } finally {
            if (r.ms == 0) r.ms = SystemClock.elapsedRealtime() - t0;
        }
    }

    private static String stepLabel(AppAdapter.Action a) {
        if (AppAdapter.Action.INTENT.equals(a.kind)) return "buka " + shortArg(a.arg2);
        if (AppAdapter.Action.WAIT.equals(a.kind)) return "tunggu " + shortArg(a.arg1);
        if (AppAdapter.Action.TAP.equals(a.kind)) return "tap " + shortArg(a.arg1);
        if (AppAdapter.Action.INPUT.equals(a.kind)) return "ketik";
        if (AppAdapter.Action.VERIFY.equals(a.kind)) return "verifikasi";
        if (AppAdapter.Action.ROOT.equals(a.kind)) return "shell";
        return "langkah";
    }

    private static String shortArg(String s) {
        if (s == null) return "";
        String t = s.indexOf('|') > 0 ? s.substring(0, s.indexOf('|')) : s;
        return t.length() > 34 ? t.substring(0, 34) + "…" : t;
    }

    // ---- one step ----------------------------------------------------------------------------

    private static String step(Host host, AppAdapter.Action a, StringBuilder out) {
        if (AppAdapter.Action.NOTE.equals(a.kind)) {
            host.bubble("note", a.arg1);
            return null;
        }
        if (AppAdapter.Action.INTENT.equals(a.kind)) return fireIntent(host, a.arg1, a.arg2);
        if (AppAdapter.Action.ROOT.equals(a.kind)) {
            String res = host.run(a.arg1, a.timeoutMs > 0 ? a.timeoutMs : 30);
            if (out != null && res != null) out.append(res).append('\n');
            return commandFailed(res) ? "perintah gagal: " + firstLine(res) : null;
        }
        if (AppAdapter.Action.WAIT.equals(a.kind)) {
            return waitFor(host, a.arg1, a.timeoutMs) ? null : "timeout menunggu " + shortArg(a.arg1);
        }
        if (AppAdapter.Action.VERIFY.equals(a.kind)) {
            return waitFor(host, a.arg1, a.timeoutMs) ? null : "verifikasi gagal: " + shortArg(a.arg1);
        }
        if (AppAdapter.Action.TAP.equals(a.kind) || AppAdapter.Action.INPUT.equals(a.kind)) {
            int[] xy = centreOf(host, a.arg1);
            if (xy == null) return "elemen tidak ketemu: " + shortArg(a.arg1);
            host.run("input tap " + xy[0] + " " + xy[1], 15);
            SystemClock.sleep(180);                       // focus settle, not a state wait
            if (AppAdapter.Action.INPUT.equals(a.kind)) {
                host.run("input text " + shellText(a.arg2), 25);
                SystemClock.sleep(150);
            }
            return null;
        }
        return null;
    }

    private static String fireIntent(Host host, String action, String arg) {
        try {
            Context ctx = host.ctx();
            Intent i;
            if (arg != null && arg.startsWith("app:")) {
                String pkg = arg.substring(4);
                i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return "app " + pkg + " tidak terpasang";
            } else {
                String data = arg == null ? "" : arg;
                String extra = null;
                int q = data.indexOf("?body=");
                if (q >= 0) {
                    extra = data.substring(q + 6);
                    data = data.substring(0, q);
                }
                i = new Intent(action);
                if (!data.isEmpty()) i.setData(Uri.parse(data));
                if (extra != null) {
                    String body = Uri.decode(extra);
                    if (data.startsWith("smsto:")) {
                        i.putExtra("sms_body", body);
                        i.setType("vnd.android-dir/mms-sms");
                    } else {
                        i.putExtra(Intent.EXTRA_TEXT, body);
                    }
                }
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            SystemClock.sleep(250);                       // let the window come up
            return null;
        } catch (Throwable t) {
            return "intent gagal (" + action + "): " + t;
        }
    }

    // ---- UI conditions ----------------------------------------------------------------------

    /** poll a selector until it matches or the deadline passes; "act:<x>" checks window focus only */
    static boolean waitFor(Host host, String selector, int timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(1200, timeoutMs);
        String[] alts = selector.split("\\|");
        String xml = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (host.stopped()) return false;
            for (String raw : alts) {
                String sel = raw.trim();
                if (sel.isEmpty()) continue;
                if (sel.startsWith("act:")) {
                    String focus = windowFocus(host);
                    if (focus != null && focus.toLowerCase(java.util.Locale.US)
                            .contains(sel.substring(4).toLowerCase(java.util.Locale.US))) return true;
                    continue;
                }
                if (xml == null) xml = dumpUi(host);
                if (match(xml, sel)) return true;
            }
            xml = null;
            SystemClock.sleep(350);
        }
        return false;
    }

    static String windowFocus(Host host) {
        try {
            // The floating panel must never be treated as the app being driven. Prefer the
            // current focused window, then fall back to the focused/resumed app record.
            String out = host.run("dumpsys window | grep -m8 -E 'mCurrentFocus|mFocusedApp'", 10);
            String line = firstExternalFocus(out);
            if (!line.isEmpty()) return line;
            out = host.run("dumpsys activity activities | grep -m8 -E 'topResumedActivity|mResumedActivity|ResumedActivity'", 10);
            line = firstExternalFocus(out);
            return line.isEmpty() ? "" : line;
        } catch (Throwable t) { return ""; }
    }

    private static String firstExternalFocus(String out) {
        if (out == null) return "";
        for (String raw : out.split("\n")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            String low = line.toLowerCase(java.util.Locale.US);
            if (low.contains("com.aissistants.app")) continue;
            if (low.contains("mcurrentfocus") || low.contains("mfocusedapp")
                    || low.contains("topresumedactivity") || low.contains("mresumedactivity")
                    || low.contains("resumedactivity")) return line;
        }
        return "";
    }

    static String dumpUi(Host host) {
        try {
            return host.run("uiautomator dump /sdcard/.ai_ui.xml >/dev/null 2>&1; cat /sdcard/.ai_ui.xml", 40);
        } catch (Throwable t) {
            return "";
        }
    }

    static boolean match(String xml, String sel) {
        if (xml == null || sel == null || xml.isEmpty()) return false;
        if (sel.startsWith("id:")) return xml.contains("resource-id=\"" + sel.substring(3) + "\"");
        String low = xml.toLowerCase(java.util.Locale.US);
        if (sel.startsWith("text:")) return low.contains("text=\"" + sel.substring(5).toLowerCase(java.util.Locale.US) + "\"");
        if (sel.startsWith("desc:")) return low.contains("content-desc=\"" + sel.substring(5).toLowerCase(java.util.Locale.US) + "\"");
        if (sel.startsWith("act:")) return low.contains(sel.substring(4).toLowerCase(java.util.Locale.US));
        return low.contains(sel.toLowerCase(java.util.Locale.US));
    }

    /** centre of the first node matching the selector (alternatives split by '|'), or null */
    static int[] centreOf(Host host, String selector) {
        String xml = dumpUi(host);
        if (xml == null || xml.isEmpty()) return null;
        for (String raw : selector.split("\\|")) {
            String sel = raw.trim();
            if (sel.isEmpty()) continue;
            Matcher m = NODE.matcher(xml);
            while (m.find()) {
                String node = m.group(0);
                if (!match(node, sel)) continue;
                Matcher b = BOUNDS.matcher(node);
                if (!b.find()) continue;
                try {
                    int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2));
                    int x2 = Integer.parseInt(b.group(3)), y2 = Integer.parseInt(b.group(4));
                    if (x2 <= x1 || y2 <= y1) continue;
                    return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    /** last n chars, trimmed - keeps a query answer readable in the transcript */
    static String tail(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= n ? t : "…" + t.substring(t.length() - n);
    }

    // ---- misc ------------------------------------------------------------------------------

    /** text that `input text` can type: spaces become %s, quotes and backslashes dropped */
    static String shellText(String s) {
        if (s == null) return "''";
        String t = s.replace("\\", "").replace("\"", "").replace("'", "").replace("\n", " ");
        t = t.replace(" ", "%s");
        return "'" + t + "'";
    }

    /** a report full of words like "not found" is still a success - only a real non-zero exit is
     *  a failure, which the shell wrapper marks with [exit N]. */
    static boolean commandFailed(String res) {
        if (res == null) return true;
        String low = res.toLowerCase(java.util.Locale.US);
        return low.contains("[exit ") || low.contains("[timeout after")
                || low.contains("root shell unavailable") || low.contains("[stopped by user]");
    }

    static boolean looksBad(String out) {
        if (out == null) return false;
        String low = out.toLowerCase(java.util.Locale.US);
        return low.contains("no such file") || low.contains("not found")
                || low.contains("permission denied") || low.contains("error:") ? true : false;
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String one = nl < 0 ? s : s.substring(0, nl);
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }

    /** generic package fallback for no-side-effect app actions */
    static final class GenericApp extends AppAdapter {
        @Override String pkg() { return ""; }
        @Override String name() { return "App"; }
        @Override boolean handles(String action) {
            return "open".equals(action) || "search".equals(action);
        }
        @Override Plan plan(LocalParser.Spec spec, HybridRouter.Host host) {
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

    // ---- prompt for the small planner (used by MainActivity when confidence is low) ----------

    /** a deliberately tiny description of the world for the fallback planner */
    static String plannerPrompt(String userText, String installedSample, String focus) {
        StringBuilder b = new StringBuilder();
        b.append("You convert one user request into ONE JSON plan. Answer with JSON only, no prose.\n")
         .append("Schema: {\"action\":\"open|search|send_message|dial|call|email|navigate|shell|agent\",")
         .append("\"package\":\"\",\"app\":\"\",\"target\":\"\",\"text\":\"\",\"risk\":\"none|external_write|call\",")
         .append("\"confidence\":0.0}\n")
         .append("Use action=agent when the request needs real device exploration instead of a known app command.\n")
         .append("Use action=shell only when the user explicitly asks to run a command or device setting task.\n")
         .append("If the user names a specific app, keep that app/package; do not replace it with SMS/browser.\n")
         .append("risk=external_write for anything that sends data to another person.\n")
         .append("Installed apps (sample): ").append(installedSample == null ? "" : installedSample).append("\n")
         .append("Focused window now: ").append(focus == null ? "" : focus.trim()).append("\n")
         .append("Request: ").append(userText);
        return b.toString();
    }

    /** parse the planner's JSON answer into a Spec; tolerates markdown fences and prose */
    static LocalParser.Spec specFromJson(String answer) {
        LocalParser.Spec s = new LocalParser.Spec();
        if (answer == null) return s;
        String t = answer.replace("```json", " ").replace("```", " ");
        int open = t.indexOf('{'), close = t.lastIndexOf('}');
        if (open < 0 || close <= open) return s;
        try {
            org.json.JSONObject o = new org.json.JSONObject(t.substring(open, close + 1));
            s.action = o.optString("action", "");
            s.app = o.optString("app", "");
            s.pkg = o.optString("package", "");
            s.target = o.optString("target", "");
            s.text = o.optString("text", "");
            s.risk = o.optString("risk", "none");
            s.confidence = o.optDouble("confidence", 0.8);
            s.why = "planner model";
            if ("agent".equals(s.action)) { s.action = ""; s.confidence = 0; }
        } catch (Throwable ignored) { }
        return s;
    }
}
