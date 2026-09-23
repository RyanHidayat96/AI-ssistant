package com.aissistant.app;

import android.accessibilityservice.AccessibilityService;
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
    private static long lastToken;
    private static HashMap<String, NodeRef> lastNodes = new HashMap<String, NodeRef>();

    static volatile AgentA11y live;

    @Override public void onServiceConnected() { live = this; }

    @Override public void onDestroy() { live = null; super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { /* no polling needed */ }

    @Override public void onInterrupt() { }

    static boolean ready() { return live != null; }

    static String statusLine() {
        return ready()
                ? "target-window accessibility: enabled"
                : "target-window accessibility: disabled; enable AI-ssistant in Android Accessibility settings";
    }

    static String observe(String packageName) {
        AgentA11y s = live;
        if (s == null) return "[accessibility service not enabled]";
        try {
            Target target = s.target(packageName);
            if (target == null) {
                return "[target app window not found: " + (empty(packageName) ? "foreground app" : packageName)
                        + ". Open the target app or pass its package name. AI-ssistant overlay windows were ignored.]";
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
        }
    }

    static String act(String packageName, String nodeId, String action, String text) {
        AgentA11y s = live;
        if (s == null) return "[accessibility service not enabled]";
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
            Target target = s.target(ref.packageName);
            if (target == null) return "[act_app error: target package not visible: " + ref.packageName + "]";
            AccessibilityNodeInfo node = byPath(target.root, ref.path);
            if (node == null) return "[act_app error: node changed since observe_app. Run observe_app again.]";
            String nodePackage = text(node.getPackageName());
            if (nodePackage.startsWith(s.getPackageName())) {
                return "[act_app refused: node belongs to AI-ssistant overlay]";
            }

            boolean ok;
            if ("click".equals(act)) {
                AccessibilityNodeInfo c = clickable(node);
                ok = c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } else if ("long_click".equals(act)) {
                ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            } else if ("focus".equals(act)) {
                ok = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            } else if ("set_text".equals(act)) {
                AccessibilityNodeInfo edit = editable(node);
                if (edit == null) return "[act_app refused: node is not editable]";
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            } else if ("scroll_forward".equals(act)) {
                AccessibilityNodeInfo scroll = scrollable(node);
                ok = scroll != null && scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } else if ("scroll_backward".equals(act)) {
                AccessibilityNodeInfo scroll = scrollable(node);
                ok = scroll != null && scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
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

    private Target target(String requestedPackage) {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return null;
        String want = requestedPackage == null ? "" : requestedPackage.trim();
        Target best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            AccessibilityNodeInfo root;
            try { root = window.getRoot(); } catch (Throwable t) { root = null; }
            if (root == null) continue;
            String pkg = text(root.getPackageName());
            if (pkg.isEmpty() || pkg.equals(getPackageName())) continue;
            if (!want.isEmpty() && !want.equals(pkg)) continue;
            int score = 0;
            try { if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) score += 3; } catch (Throwable ignored) { }
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
            refs.put(id, new NodeRef(packageName, path));
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

        NodeRef(String packageName, String path) {
            this.packageName = packageName;
            this.path = path == null ? "" : path;
        }
    }
}
