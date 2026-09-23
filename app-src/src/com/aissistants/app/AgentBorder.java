package com.aissistants.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Thin blue edge while the agent drives another app.
 *
 * Mirrors "someone is operating this screen" the way a browser agent outlines its window.
 * Non-focusable, non-touchable and invisible to accessibility, so it can never steal the
 * focus or show up in the agent's own uiautomator dumps.
 */
public final class AgentBorder {

    private static final long HOLD_MS = 4000L;   // keep it visible this long after the last driving command
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static View view;
    private static WindowManager wm;
    private static Runnable hideTask;
    private static Pulse pulse;

    private AgentBorder() { }

    /** call with every command the agent runs; only UI-driving commands light the edge */
    public static void ping(Context ctx, String cmd) {
        try {
            String c = cmd == null ? "" : cmd.toLowerCase(java.util.Locale.ENGLISH);
            boolean drives = drivesTargetApp(c);
            if (!drives) return;
            android.util.Log.i("AIssistants", "border ping hit: " + c.substring(0, Math.min(60, c.length())));
            final Context ac = ctx.getApplicationContext();
            H.post(new Runnable() { @Override public void run() { show(ac); } });
            if (hideTask != null) H.removeCallbacks(hideTask);
            hideTask = new Runnable() { @Override public void run() { hide(); } };
            H.postDelayed(hideTask, HOLD_MS);
        } catch (Throwable ignored) { }
    }

    /** Enter a UI-critical phase before the command can read or touch another app. */
    public static boolean prepareTargetScreen(Context ctx, String cmd) {
        try {
            String c = cmd == null ? "" : cmd.toLowerCase(java.util.Locale.ENGLISH);
            if (!drivesTargetApp(c)) return false;
            OverlayView.prepareForAgent(ctx, observesTargetScreen(c));
            return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** Restore overlay after one UI command. */
    public static void finishTargetScreen(Context ctx, String cmd) {
        try {
            String c = cmd == null ? "" : cmd.toLowerCase(java.util.Locale.ENGLISH);
            if (!drivesTargetApp(c)) return;
            OverlayView.finishAgentObservation(ctx);
        } catch (Throwable ignored) { }
    }

    private static boolean drivesTargetApp(String c) {
        return c.matches("(?s).*\\bmonkey\\b.*")
                || c.matches("(?s).*\\bam\\s+start\\b.*")
                || c.matches("(?s).*\\binput\\s+(tap|text|keyevent|swipe|roll|press)\\b.*")
                || c.matches("(?s).*\\bcmd\\s+activity\\b.*")
                || c.matches("(?s).*\\bscreencap\\b.*")
                || c.matches("(?s).*\\buiautomator\\s+dump\\b.*")
                || c.matches("(?s).*\\bdumpsys\\s+window\\b.*");
    }

    private static boolean observesTargetScreen(String c) {
        return c.matches("(?s).*\\bscreencap\\b.*");
    }


    private static long lastFocusCheck;

    /** If our overlay holds focus while the agent drives another app, release it immediately. */
    public static void standDownIfOurs(Context ctx) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastFocusCheck < 2500L) return;      // cheap: at most one probe per 2.5s
            lastFocusCheck = now;
            String out = RootShell.run("dumpsys window | grep -m1 mCurrentFocus", 6);
            if (out == null || !out.contains("com.aissistants.app")) return;
            android.util.Log.i("AIssistants", "overlay held focus - release + pass-through");
            try { OverlayView.prepareForAgent(ctx, false); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }

    public static void hide() {
        H.post(new Runnable() { @Override public void run() { drop(); } });
    }

    private static void drop() {
        try {
            if (pulse != null) { pulse.stop(); pulse = null; }
            if (view != null && wm != null) { wm.removeViewImmediate(view); android.util.Log.i("AIssistants", "border gone"); }
        } catch (Throwable ignored) { }
        view = null;
    }

    private static void show(Context ctx) {
        try {
            if (view != null) { if (pulse != null) pulse.bump(); return; }
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            Edge e = new Edge(ctx);
            e.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);   // no status/nav bar inset
            if (android.os.Build.VERSION.SDK_INT >= 28)
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            wm.addView(e, lp);
            android.util.Log.i("AIssistants", "border window added");
            view = e;
            pulse = new Pulse(e);
            pulse.start();
        } catch (Throwable t) { android.util.Log.w("AIssistants", "border show FAILED: " + t); }
    }

    /** the blue frame itself: a travelling wave with a solid -> transparent fade around the edge */
    private static final class Edge extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path frame = new android.graphics.Path();
        private final android.graphics.Path seg = new android.graphics.Path();
        private final android.graphics.PathMeasure pm = new android.graphics.PathMeasure();
        private final long t0 = System.currentTimeMillis();
        private float stroke, radius;

        Edge(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            stroke = 7f * d;
            radius = 22f * d;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float in = stroke / 2f;                     // outer edge of the stroke sits exactly on the screen edge
            android.graphics.RectF r = new android.graphics.RectF(in, in, w - in, h - in);
            frame.reset();
            frame.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
            pm.setPath(frame, false);
        }

        @Override protected void onDraw(Canvas c) {
            float len = pm.getLength();
            if (len <= 0) { postInvalidateDelayed(60); return; }
            double phase = (System.currentTimeMillis() - t0) / 1000.0 * 0.55;   // slow travel
            final int N = 150;
            for (int i = 0; i < N; i++) {
                double u = (double) i / N;                                        // 0..1 around the frame
                double wave = 0.5 + 0.5 * Math.sin(u * Math.PI * 2 * 3 - phase);   // 3 soft lobes
                double fade = 0.12 + 0.88 * (0.5 + 0.5 * Math.cos(u * Math.PI * 2)); // pekat -> transparan -> pekat
                int alpha = (int) (240 * (0.35 + 0.65 * wave) * fade);
                p.setAlpha(Math.max(6, alpha));
                p.setStrokeWidth(stroke * (0.55f + 0.75f * (float) wave));
                seg.reset();
                if (pm.getSegment(len * i / N, len * (i + 1) / N + stroke * 0.5f, seg, true)) c.drawPath(seg, p);
            }
            postInvalidateDelayed(33);                                            // keep it moving
        }
    }

    /** slow breathe in/out so a glance tells whether the agent is still working */
    private static final class Pulse implements Runnable {
        private final View v;
        private long t0 = System.currentTimeMillis();
        private boolean on;

        Pulse(View v) { this.v = v; }

        void start() { on = true; H.postDelayed(this, 40); }
        void stop() { on = false; H.removeCallbacks(this); }
        void bump() { t0 = System.currentTimeMillis(); }

        @Override public void run() {
            if (!on) return;
            try {
                double ph = (System.currentTimeMillis() - t0) / 900.0;
                float g = (float) (0.65 + 0.35 * Math.sin(ph));
                v.invalidate();
            } catch (Throwable ignored) { }
            H.postDelayed(this, 40);
        }
    }
}
