package com.aissistants.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Animated edge while the agent drives another app.
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

    /** Ambient edge: solid at screen edge, transparent toward the app, with a slow inner wave. */
    private static final class Edge extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final long t0 = System.currentTimeMillis();
        private float base, wave;

        Edge(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            base = 18f * d;
            wave = 7f * d;
            p.setStyle(Paint.Style.FILL);
            p.setDither(true);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) { postInvalidateDelayed(60); return; }
            double seconds = (System.currentTimeMillis() - t0) / 1000.0;
            double phase = seconds * 0.42;                                       // slow wave circling the screen
            drawTop(c, w, h, phase);
            drawRight(c, w, h, phase);
            drawBottom(c, w, h, phase);
            drawLeft(c, w, h, phase);
            p.setShader(null);
            postInvalidateDelayed(40);                                           // smooth enough, less noisy
        }

        private void drawTop(Canvas c, int w, int h, double phase) {
            path.reset();
            path.moveTo(0, 0);
            path.lineTo(w, 0);
            for (int i = 64; i >= 0; i--) {
                float x = w * (i / 64f);
                path.lineTo(x, inner(0, x / Math.max(1f, w), phase));
            }
            path.close();
            shader(0, 0, 0, base + wave);
            c.drawPath(path, p);
        }

        private void drawRight(Canvas c, int w, int h, double phase) {
            path.reset();
            path.moveTo(w, 0);
            path.lineTo(w, h);
            for (int i = 64; i >= 0; i--) {
                float y = h * (i / 64f);
                path.lineTo(w - inner(1, y / Math.max(1f, h), phase), y);
            }
            path.close();
            shader(w, 0, w - base - wave, 0);
            c.drawPath(path, p);
        }

        private void drawBottom(Canvas c, int w, int h, double phase) {
            path.reset();
            path.moveTo(w, h);
            path.lineTo(0, h);
            for (int i = 64; i >= 0; i--) {
                float x = w - w * (i / 64f);
                path.lineTo(x, h - inner(2, x / Math.max(1f, w), phase));
            }
            path.close();
            shader(0, h, 0, h - base - wave);
            c.drawPath(path, p);
        }

        private void drawLeft(Canvas c, int w, int h, double phase) {
            path.reset();
            path.moveTo(0, h);
            path.lineTo(0, 0);
            for (int i = 64; i >= 0; i--) {
                float y = h - h * (i / 64f);
                path.lineTo(inner(3, y / Math.max(1f, h), phase), y);
            }
            path.close();
            shader(0, 0, base + wave, 0);
            c.drawPath(path, p);
        }

        private float inner(int side, double local, double phase) {
            double around = (side + local) / 4.0;
            double crest = 0.5 + 0.5 * Math.sin((around * Math.PI * 2.0 * 5.0) - phase);
            return base + (float) (wave * crest);
        }

        private void shader(float x0, float y0, float x1, float y1) {
            p.setShader(new LinearGradient(
                    x0, y0, x1, y1,
                    new int[] {
                            Color.argb(92, 37, 99, 235),
                            Color.argb(34, 14, 165, 233),
                            Color.argb(0, 14, 165, 233)
                    },
                    new float[] { 0f, 0.48f, 1f },
                    Shader.TileMode.CLAMP));
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
