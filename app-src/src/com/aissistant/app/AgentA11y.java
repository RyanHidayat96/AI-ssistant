package com.aissistant.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Lets the agent operate other apps through the target app's accessibility tree instead of raw taps.
 *
 * Why: `input tap` goes through the physical hit-test, so an overlay of ours can swallow a tap meant
 * for the target app. Accessibility actions are delivered to the node itself, so our own windows can
 * stay visible to the user without ever intercepting the agent's clicks.
 *
 * Deliberately does NOT request screenshots or dispatch gestures. Node actions are enough for
 * ordinary Android UI; coordinate/game fallback remains a shell concern.
 */
public class AgentA11y extends AccessibilityService {
    private static final Object LOCK = new Object();
    private static final int MAX_NODES = 180;
    private static final long CONNECT_WAIT_MS = 1600L;
    private static final long OBSERVE_LAUNCH_WAIT_MS = 6500L;
    private static final long ACTION_RELAUNCH_WAIT_MS = 4000L;
    private static final long RECOVERY_WAIT_MS = 3000L;
    private static final long RECOVERY_PAUSE_MS = 120L;
    private static long lastToken;
    private static HashMap<String, NodeRef> lastNodes = new HashMap<String, NodeRef>();

    static volatile AgentA11y live;
    private static volatile boolean connectedOnce;

    @Override public void onServiceConnected() {
        synchronized (LOCK) {
            live = this;
            connectedOnce = true;
            lastToken = 0L;
            lastNodes = new HashMap<String, NodeRef>();
            LOCK.notifyAll();
        }
        android.util.Log.i("AIssistant", "Accessibility connected");
    }

    @Override public boolean onUnbind(Intent intent) {
        clearLive(this);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        clearLive(this);
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { /* no polling needed */ }

    @Override public void onInterrupt() { }

    static boolean ready() { return live != null; }

    static String statusLine() {
        return ready()
                ? "target-window accessibility: enabled"
                : "target-window accessibility: disabled; enable AI-ssistant in Android Accessibility settings";
    }

    /** When the target window vanished, relaunch it once and give it a moment. Usual cause: the
     *  target's own gate / anti-tamper closed its activity, so focus fell to whatever app was last used. */
    private static void relaunchTarget(Context ctx, String pkg) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) { }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static Target waitForTarget(AgentA11y s, String packageName, long timeoutMs) {
        if (s == null) return null;
        Target target = s.target(packageName);
        if (target != null || timeoutMs <= 0L) return target;
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            sleepQuiet(180L);
            target = s.target(packageName);
            if (target != null) return target;
        }
        return null;
    }

    static String observe(String packageName) {
        AgentA11y s = awaitLive(CONNECT_WAIT_MS);
        if (s == null) return unavailable();
        boolean borderActive = false;
        try {
            if (!empty(packageName)) borderActive = AgentBorder.beginAccessibilityOperation(s);
            Target target = waitForTarget(s, packageName, empty(packageName) ? 800L : 1200L);
            if (target == null) {
                if (!empty(packageName)) {
                    relaunchTarget(s, packageName);
                    target = waitForTarget(s, packageName, OBSERVE_LAUNCH_WAIT_MS);
                }
                if (target == null) {
                    return "[target app Accessibility window not visible: "
                            + (empty(packageName) ? "foreground app" : packageName)
                            + ". Launch/wait was attempted but no target application window appeared within "
                            + (OBSERVE_LAUNCH_WAIT_MS / 1000L)
                            + "s. If shell focus shows the package, wait briefly and call observe_app once more; "
                            + "if it keeps disappearing, switch to the static (jadx/apktool) or hook (frida) route.]";
                }
            }
            long token = System.currentTimeMillis();
            HashMap<String, NodeRef> refs = new HashMap<String, NodeRef>();
            StringBuilder b = new StringBuilder();
            b.append("TARGET WINDOW token=").append(token)
                    .append(" package=").append(target.packageName)
                    .append(" window=").append(target.windowId)
                    .append('\n');
            b.append("AI-ssistant overlay excluded. Use act_app with node ids below.\n");
            int[] count = new int[]{0};
            walk(target.root, "", 0, b, refs, target.packageName, count);
            if (count[0] == 0) {
                b.append("(no useful target nodes; canvas/game UI may need shell fallback)\n");
            }
            synchronized (LOCK) {
                lastToken = token;
                lastNodes = refs;
            }
            return b.toString();
        } catch (Throwable t) {
            return "observe_app failed: " + t;
        } finally {
            if (borderActive) AgentBorder.endOperation();
        }
    }

    static String act(String packageName, String nodeId, String action, String text) {
        AgentA11y s = awaitLive(CONNECT_WAIT_MS);
        if (s == null) return unavailable();
        try {
            if (empty(nodeId)) return "[act_app error: missing node id. Run observe_app first.]";
            if (empty(action)) return "[act_app error: missing action.]";
            String act = action.trim().toLowerCase(Locale.ENGLISH);
            NodeRef ref;
            long token;
            synchronized (LOCK) {
                ref = lastNodes.get(nodeId.trim());
                token = lastToken;
            }
            if (ref == null) return "[act_app error: stale or unknown node id " + nodeId + ". Run observe_app again.]";
            if (!empty(packageName) && !packageName.trim().equals(ref.packageName)) {
                return "[act_app refused: node belongs to " + ref.packageName + ", not " + packageName + "]";
            }
            Target target = waitForTarget(s, ref.packageName, 800L);
            if (target == null) {
                relaunchTarget(s, ref.packageName);
                Target again = waitForTarget(s, ref.packageName, ACTION_RELAUNCH_WAIT_MS);
                if (again == null) return "[act_app error: " + ref.packageName + " closed its own window. Relaunched "
                        + "once; if it keeps closing, use the static/hook route instead of chasing focus.]";
                target = again;
            }
            AccessibilityNodeInfo node = byPath(target.root, ref.path);
            if (node == null) return "[act_app error: node changed since observe_app. Run observe_app again.]";
            String nodePackage = text(node.getPackageName());
            if (nodePackage.startsWith(s.getPackageName())) {
                return "[act_app refused: node belongs to AI-ssistant overlay]";
            }

            boolean ok;
            if ("click".equals(act)) {
                AccessibilityNodeInfo c = clickable(node);
                if (c == null) return "[act_app refused: node is not clickable]";
                AgentBorder.pulseAccessibilityOperation(s);
                ok = c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } else if ("long_click".equals(act)) {
                AgentBorder.pulseAccessibilityOperation(s);
                ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            } else if ("focus".equals(act)) {
                AgentBorder.pulseAccessibilityOperation(s);
                ok = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            } else if ("set_text".equals(act)) {
                AccessibilityNodeInfo edit = editable(node);
                if (edit == null) return "[act_app refused: node is not editable]";
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                AgentBorder.pulseAccessibilityOperation(s);
                ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            } else if ("scroll_forward".equals(act)) {
                AccessibilityNodeInfo scroll = scrollable(node);
                if (scroll == null) return "[act_app refused: node is not scrollable]";
                AgentBorder.pulseAccessibilityOperation(s);
                ok = scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } else if ("scroll_backward".equals(act)) {
                AccessibilityNodeInfo scroll = scrollable(node);
                if (scroll == null) return "[act_app refused: node is not scrollable]";
                AgentBorder.pulseAccessibilityOperation(s);
                ok = scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            } else {
                return "[act_app error: unsupported action " + act
                        + ". Use click, long_click, focus, set_text, scroll_forward, scroll_backward.]";
            }
            return "act_app token=" + token + " package=" + ref.packageName + " node=" + nodeId
                    + " action=" + act + " ok=" + ok + "\n" + describe(node, ref.path);
        } catch (Throwable t) {
            return "act_app failed: " + t;
        }
    }

    /**
     * Repeats a node scroll for a bounded duration. Unlike `input swipe`, this action is delivered
     * to the target node, so the user can keep using the visible AI-ssistant overlay.
     */
    static String scrollFor(String packageName, String nodeId, String direction, int durationMs, int intervalMs) {
        AgentA11y s = awaitLive(CONNECT_WAIT_MS);
        if (s == null) return unavailable();
        if (empty(nodeId)) return "[scroll_app error: missing node id. Run observe_app first.]";
        String way = direction == null ? "" : direction.trim().toLowerCase(Locale.ENGLISH);
        if (!"forward".equals(way) && !"backward".equals(way))
            return "[scroll_app error: direction must be forward or backward.]";
        if (durationMs < 100 || durationMs > 30000)
            return "[scroll_app error: duration_ms must be 100 to 30000.]";
        int interval = intervalMs <= 0 ? 450 : intervalMs;
        if (interval < 80 || interval > 2000)
            return "[scroll_app error: interval_ms must be 80 to 2000.]";
        boolean borderActive = false;
        try {
            NodeRef ref;
            long token;
            synchronized (LOCK) {
                ref = lastNodes.get(nodeId.trim());
                token = lastToken;
            }
            if (ref == null) return "[scroll_app error: stale or unknown node id " + nodeId + ". Run observe_app first.]";
            if (!empty(packageName) && !packageName.trim().equals(ref.packageName))
                return "[scroll_app refused: node belongs to " + ref.packageName + ", not " + packageName + "]";

            Target initialTarget = waitForTarget(s, ref.packageName, 800L);
            if (initialTarget == null) {
                relaunchTarget(s, ref.packageName);
                initialTarget = waitForTarget(s, ref.packageName, ACTION_RELAUNCH_WAIT_MS);
                if (initialTarget == null) return "[scroll_app error: " + ref.packageName + " closed its own window. "
                        + "Relaunched once; if it keeps closing, use the static/hook route instead of chasing focus.]";
            }
            AccessibilityNodeInfo initialNode = resolveScrollable(initialTarget, ref);
            if (initialNode == null) return "[scroll_app error: node changed since observe_app. Run observe_app again.]";
            if (text(initialNode.getPackageName()).startsWith(s.getPackageName()))
                return "[scroll_app refused: node belongs to AI-ssistant overlay]";
            if (scrollable(initialNode) == null) return "[scroll_app refused: node is not scrollable]";
            borderActive = AgentBorder.beginAccessibilityOperation(s);

            long started = android.os.SystemClock.elapsedRealtime();
            long deadline = started + durationMs;
            int steps = 0;
            String stopped = "duration reached";
            long reconnectSince = -1L;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                long now = android.os.SystemClock.elapsedRealtime();
                long remain = deadline - now;
                AgentA11y current = awaitLive(Math.min(RECOVERY_PAUSE_MS, Math.max(0L, remain)));
                if (current == null) {
                    if (reconnectSince < 0L) reconnectSince = now;
                    if (now - reconnectSince >= RECOVERY_WAIT_MS) {
                        stopped = "accessibility service reconnecting";
                        break;
                    }
                    pause(Math.min(RECOVERY_PAUSE_MS, Math.max(0L, remain)));
                    continue;
                }
                reconnectSince = -1L;
                Target target = current.target(ref.packageName);
                if (target == null) { stopped = "target not visible"; break; }
                AccessibilityNodeInfo node = resolveScrollable(target, ref);
                if (node == null) { stopped = "scroll target changed"; break; }
                String nodePackage = text(node.getPackageName());
                if (nodePackage.startsWith(s.getPackageName())) {
                    stopped = "refused AI-ssistant overlay";
                    break;
                }
                boolean ok = node.performAction("forward".equals(way)
                        ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                if (!ok) { stopped = "target cannot scroll further"; break; }
                steps++;
                long afterActionRemaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (afterActionRemaining <= 0) break;
                if (!pause(Math.min((long) interval, afterActionRemaining))) { stopped = "interrupted"; break; }
            }
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            return "scroll_app token=" + token + " package=" + ref.packageName + " node=" + nodeId
                    + " direction=" + way + " elapsed_ms=" + elapsed + " steps=" + steps + " stopped=" + stopped
                    + "\nAI-ssistant overlay excluded.";
        } catch (Throwable t) {
            return "scroll_app failed: " + t;
        } finally {
            if (borderActive) AgentBorder.endOperation();
        }
    }

    /**
     * Redirect ordinary raw swipes only when Accessibility already exposes a real scroll target.
     * Canvas/game UIs without a scroll node retain their raw-input fallback.
     */
    static String rawSwipeRedirect() {
        AgentA11y s = live;
        if (s == null) return "";
        try {
            Target target = s.target("");
            if (target == null) return "";
            String path = firstScrollablePath(target.root, "", 0);
            if (path == null) return "";
            long token = System.currentTimeMillis();
            HashMap<String, NodeRef> refs = new HashMap<String, NodeRef>();
            refs.put("n0", new NodeRef(target.packageName, path, byPath(target.root, path)));
            synchronized (LOCK) {
                lastToken = token;
                lastNodes = refs;
            }
            return "[RAW SCROLL NOT EXECUTED: target package " + target.packageName
                    + " has Accessibility scroll node n0. Use scroll_app with package=\"" + target.packageName
                    + "\", node=\"n0\", direction=\"forward\" or \"backward\", and requested duration_ms. "
                    + "This keeps AI-ssistant overlay visible and excluded from agent UI access.]";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Accessibility can be rebound by Android while the app process remains alive. Wait briefly
     * for that normal lifecycle event instead of treating a transient null reference as revoked
     * user permission. */
    private static AgentA11y awaitLive(long timeoutMs) {
        AgentA11y current = live;
        if (current != null || timeoutMs <= 0L) return current;
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (LOCK) {
            while ((current = live) == null) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0L) return null;
                try { LOCK.wait(Math.min(remaining, 200L)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return current;
        }
    }

    private static void clearLive(AgentA11y service) {
        synchronized (LOCK) {
            if (live != service) return; // Never let a stale instance clear a newly rebound one.
            live = null;
            lastToken = 0L;
            lastNodes = new HashMap<String, NodeRef>();
            LOCK.notifyAll();
        }
        android.util.Log.i("AIssistant", "Accessibility disconnected");
    }

    private static String unavailable() {
        return connectedOnce
                ? "[accessibility service reconnecting; wait briefly and retry the same target-window action]"
                : "[accessibility service not enabled]";
    }

    private static boolean pause(long delayMs) {
        if (delayMs <= 0L) return true;
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Resolve a scrolling container again after list rows or window hierarchy change. */
    private static AccessibilityNodeInfo resolveScrollable(Target target, NodeRef ref) {
        if (target == null || ref == null) return null;
        AccessibilityNodeInfo node = byPath(target.root, ref.path);
        AccessibilityNodeInfo scroll = scrollable(node);
        if (scroll != null) return scroll;
        scroll = findScrollable(target.root, ref.scrollViewId, ref.scrollClassName, 0);
        if (scroll != null) return scroll;
        return ref.hadScrollableAncestor ? firstScrollableNode(target.root, 0) : null;
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, String wantedId,
            String wantedClass, int depth) {
        if (node == null || depth > 18) return null;
        boolean idMatch = !empty(wantedId) && wantedId.equals(text(node.getViewIdResourceName()));
        boolean classMatch = empty(wantedId) && !empty(wantedClass)
                && wantedClass.equals(text(node.getClassName()));
        if (safe(node, "scroll") && (idMatch || classMatch)) return node;
        int children;
        try { children = node.getChildCount(); } catch (Throwable ignored) { children = 0; }
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo found;
            try { found = findScrollable(node.getChild(i), wantedId, wantedClass, depth + 1); }
            catch (Throwable ignored) { found = null; }
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo firstScrollableNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 18) return null;
        if (safe(node, "scroll")) return node;
        int children;
        try { children = node.getChildCount(); } catch (Throwable ignored) { children = 0; }
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo found;
            try { found = firstScrollableNode(node.getChild(i), depth + 1); }
            catch (Throwable ignored) { found = null; }
            if (found != null) return found;
        }
        return null;
    }

    private Target target(String requestedPackage) {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return null;
        String want = requestedPackage == null ? "" : requestedPackage.trim();
        Target best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            // Only app windows can be the target. System/overlay/input-method windows are not
            // valid fallback targets even when they momentarily receive focus.
            try {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            } catch (Throwable ignored) { continue; }
            AccessibilityNodeInfo root;
            try { root = window.getRoot(); } catch (Throwable t) { root = null; }
            if (root == null) continue;
            String pkg = text(root.getPackageName());
            if (pkg.isEmpty() || pkg.equals(getPackageName())) continue;
            if (!want.isEmpty() && !want.equals(pkg)) continue;
            int score = 0;
            try { if (window.isActive()) score += 8; } catch (Throwable ignored) { }
            try { if (window.isFocused()) score += 4; } catch (Throwable ignored) { }
            if (!want.isEmpty() && want.equals(pkg)) score += 20;
            if (best == null || score > bestScore) {
                int id;
                try { id = window.getId(); } catch (Throwable t) { id = -1; }
                best = new Target(root, pkg, id);
                bestScore = score;
            }
        }
        return best;
    }

    private static void walk(AccessibilityNodeInfo n, String path, int depth, StringBuilder b,
            HashMap<String, NodeRef> refs, String packageName, int[] count) {
        if (n == null || depth > 14 || b.length() > 60000 || count[0] >= MAX_NODES) return;
        if (visible(n) && useful(n)) {
            String id = "n" + count[0]++;
            refs.put(id, new NodeRef(packageName, path, n));
            b.append(id).append(' ').append(describe(n, path)).append('\n');
        }
        int children;
        try { children = n.getChildCount(); } catch (Throwable t) { children = 0; }
        for (int i = 0; i < children && count[0] < MAX_NODES; i++) {
            AccessibilityNodeInfo child;
            try { child = n.getChild(i); } catch (Throwable t) { child = null; }
            walk(child, path.isEmpty() ? String.valueOf(i) : path + "." + i, depth + 1, b, refs, packageName, count);
        }
    }

    private static AccessibilityNodeInfo byPath(AccessibilityNodeInfo root, String path) {
        if (root == null) return null;
        if (path == null || path.isEmpty()) return root;
        AccessibilityNodeInfo n = root;
        for (String part : path.split("\\.")) {
            int index;
            try { index = Integer.parseInt(part); } catch (Throwable t) { return null; }
            try { n = n.getChild(index); } catch (Throwable t) { return null; }
            if (n == null) return null;
        }
        return n;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        for (int i = 0; i < 8 && n != null; i++) {
            try { if (n.isClickable()) return n; } catch (Throwable ignored) { }
            try { n = n.getParent(); } catch (Throwable t) { return null; }
        }
        return null;
    }

    private static AccessibilityNodeInfo editable(AccessibilityNodeInfo n) {
        for (int i = 0; i < 8 && n != null; i++) {
            try { if (n.isEditable()) return n; } catch (Throwable ignored) { }
            try { n = n.getParent(); } catch (Throwable t) { return null; }
        }
        return null;
    }

    private static AccessibilityNodeInfo scrollable(AccessibilityNodeInfo n) {
        for (int i = 0; i < 8 && n != null; i++) {
            try { if (n.isScrollable()) return n; } catch (Throwable ignored) { }
            try { n = n.getParent(); } catch (Throwable t) { return null; }
        }
        return null;
    }

    private static String firstScrollablePath(AccessibilityNodeInfo n, String path, int depth) {
        if (n == null || depth > 14) return null;
        if (safe(n, "scroll")) return path;
        int children;
        try { children = n.getChildCount(); } catch (Throwable t) { children = 0; }
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child;
            try { child = n.getChild(i); } catch (Throwable t) { child = null; }
            String found = firstScrollablePath(child, path.isEmpty() ? String.valueOf(i) : path + "." + i, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean useful(AccessibilityNodeInfo n) {
        return safe(n, "click") || safe(n, "long") || safe(n, "edit") || safe(n, "scroll")
                || safe(n, "focus") || safe(n, "check")
                || !text(n.getText()).isEmpty()
                || !text(n.getContentDescription()).isEmpty()
                || !text(n.getViewIdResourceName()).isEmpty();
    }

    private static boolean visible(AccessibilityNodeInfo n) {
        try { return n.isVisibleToUser(); } catch (Throwable t) { return true; }
    }

    private static boolean safe(AccessibilityNodeInfo n, String flag) {
        try {
            if ("click".equals(flag)) return n.isClickable();
            if ("long".equals(flag)) return n.isLongClickable();
            if ("edit".equals(flag)) return n.isEditable();
            if ("scroll".equals(flag)) return n.isScrollable();
            if ("focus".equals(flag)) return n.isFocusable();
            if ("check".equals(flag)) return n.isCheckable();
        } catch (Throwable ignored) { }
        return false;
    }

    private static String describe(AccessibilityNodeInfo n, String path) {
        Rect r = new Rect();
        try { n.getBoundsInScreen(r); } catch (Throwable ignored) { }
        StringBuilder b = new StringBuilder();
        b.append("path=").append(path == null || path.isEmpty() ? "root" : path);
        String cls = text(n.getClassName());
        int dot = cls.lastIndexOf('.');
        if (!cls.isEmpty()) b.append(" class=").append(dot >= 0 ? cls.substring(dot + 1) : cls);
        String t = clean(text(n.getText()));
        String d = clean(text(n.getContentDescription()));
        String id = clean(text(n.getViewIdResourceName()));
        if (!t.isEmpty()) b.append(" text=\"").append(limit(t)).append('"');
        if (!d.isEmpty()) b.append(" desc=\"").append(limit(d)).append('"');
        if (!id.isEmpty()) b.append(" id=").append(limit(id.replace(":id/", "/")));
        b.append(" bounds=").append(r.left).append(',').append(r.top).append('-').append(r.right).append(',').append(r.bottom);
        if (safe(n, "click")) b.append(" click");
        if (safe(n, "long")) b.append(" long");
        if (safe(n, "edit")) b.append(" edit");
        if (safe(n, "scroll")) b.append(" scroll");
        if (safe(n, "focus")) b.append(" focus");
        if (safe(n, "check")) b.append(" check");
        return b.toString();
    }

    private static String text(CharSequence v) { return v == null ? "" : v.toString(); }
    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private static String clean(String s) { return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim(); }
    private static String limit(String s) { return s.length() <= 90 ? s : s.substring(0, 90) + "..."; }

    private static final class Target {
        final AccessibilityNodeInfo root;
        final String packageName;
        final int windowId;

        Target(AccessibilityNodeInfo root, String packageName, int windowId) {
            this.root = root;
            this.packageName = packageName;
            this.windowId = windowId;
        }
    }

    private static final class NodeRef {
        final String packageName;
        final String path;
        final String scrollViewId;
        final String scrollClassName;
        final boolean hadScrollableAncestor;

        NodeRef(String packageName, String path, AccessibilityNodeInfo node) {
            this.packageName = packageName;
            this.path = path == null ? "" : path;
            AccessibilityNodeInfo scroll = scrollable(node);
            this.hadScrollableAncestor = scroll != null;
            this.scrollViewId = scroll == null ? "" : text(scroll.getViewIdResourceName());
            this.scrollClassName = scroll == null ? "" : text(scroll.getClassName());
        }
    }
}
