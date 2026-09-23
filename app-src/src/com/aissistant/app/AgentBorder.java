package com.aissistant.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Choreographer;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;

/**
 * Animated edge while the agent drives another app.
 *
 * Mirrors "someone is operating this screen" the way a browser agent outlines its window.
 * Non-focusable, non-touchable and invisible to accessibility, so it can never steal the
 * focus or show up in the agent's own uiautomator dumps.
 */
public final class AgentBorder {

    private static final Handler H = new Handler(Looper.getMainLooper());

    private static View view;
    private static WindowManager wm;
    private static Pulse pulse;
    /** Number of commands currently controlling or observing another app. Main-thread only. */
    private static int activeOperations;

    private AgentBorder() { }

    /** Start one command that controls or observes another app. Always pair with {@link #endOperation()}. */
    public static boolean beginOperation(Context ctx, String cmd) {
        try {
            String c = cmd == null ? "" : cmd.toLowerCase(java.util.Locale.ENGLISH);
            if (!drivesTargetApp(c)) return false;
            // A border is still an application overlay. During a strict agent run it would leak
            // into screenshots/window dumps and participate in obscuring-opacity input checks.
            if (OverlayHub.agentIsolation()) return false;
            android.util.Log.i("AIssistant", "border operation start: "
                    + c.substring(0, Math.min(60, c.length())));
            final Context ac = ctx.getApplicationContext();
            H.post(new Runnable() { @Override public void run() {
                activeOperations++;
                show(ac);
            } });
            return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** End one command previously admitted by {@link #beginOperation(Context, String)}. */
    public static void endOperation() {
        H.post(new Runnable() { @Override public void run() {
            if (activeOperations > 0) activeOperations--;
            if (activeOperations == 0) drop();
        } });
    }

    /** Enter a UI-critical phase before the command can read or touch another app. */
    public static boolean prepareTargetScreen(Context ctx, String cmd) {
        try {
            String c = cmd == null ? "" : cmd.toLowerCase(java.util.Locale.ENGLISH);
            if (!drivesTargetApp(c)) return false;
            return OverlayView.ensureAgentIsolation(ctx) && suppressForAgentRun();
        } catch (Throwable ignored) { }
        return false;
    }

    /** Restore overlay after one UI command. */
    public static void finishTargetScreen(Context ctx, String cmd) {
        try {
            String c = cmd == null ? "" : cmd.toLowerCase(java.util.Locale.ENGLISH);
            if (!drivesTargetApp(c)) return;
            if (OverlayHub.agentIsolation()) return;
            OverlayView.finishAgentObservation(ctx);
        } catch (Throwable ignored) { }
    }

    private static boolean drivesTargetApp(String c) {
        return c.matches("(?s).*\\bmonkey\\b.*")
                || c.matches("(?s).*\\bam\\s+start\\b.*")
                || c.matches("(?s).*\\binput\\s+(tap|text|keyevent|swipe|roll|press)\\b.*")
                || c.matches("(?s).*\\b(sendevent|uinput)\\b.*")
                || c.matches("(?s).*\\bservice\\s+call\\s+input\\b.*")
                || c.matches("(?s).*\\bcmd\\s+activity\\b.*")
                || c.matches("(?s).*\\bdumpsys\\s+(window|activity|input|accessibility|surfaceflinger)\\b.*")
                || c.matches("(?s).*\\bcmd\\s+(window|accessibility|input)\\b.*")
                || c.matches("(?s).*\\bscreencap\\b.*")
                || c.matches("(?s).*\\buiautomator\\s+dump\\b.*");
    }

    private static boolean observesTargetScreen(String c) {
        return c.matches("(?s).*\\b(screencap|uiautomator)\\b.*")
                || c.matches("(?s).*\\bdumpsys\\s+(window|activity|input|accessibility|surfaceflinger)\\b.*");
    }

    /**
     * Synchronously remove this secondary app-owned surface before an agent command. Returning
     * false lets the caller fail closed if the main thread is unavailable instead of injecting
     * touch through a stale full-screen SAW window.
     */
    static boolean suppressForAgentRun() {
        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean completed = onMainAndWait(new Runnable() {
            @Override public void run() {
                try {
                    activeOperations = 0;
                    drop();
                    done.set(true);
                } catch (Throwable t) {
                    android.util.Log.e("AIssistant", "border isolate failed: " + t);
                }
            }
        }, 700L);
        return completed && done.get();
    }


    private static long lastFocusCheck;

    /** If our overlay holds focus while the agent drives another app, release it immediately. */
    public static void standDownIfOurs(Context ctx) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastFocusCheck < 2500L) return;      // cheap: at most one probe per 2.5s
            lastFocusCheck = now;
            String out = RootShell.run("dumpsys window | grep -m1 mCurrentFocus", 6);
            if (out == null || !out.contains("com.aissistant.app")) return;
            android.util.Log.i("AIssistant", "overlay held focus - release + pass-through");
            try { OverlayView.prepareForAgent(ctx, false); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }

    /** Terminal cleanup for stop, error, or completed run. */
    public static void hide() {
        H.post(new Runnable() { @Override public void run() {
            activeOperations = 0;
            drop();
        } });
    }

    private static boolean onMainAndWait(final Runnable work, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            work.run();
            return true;
        }
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        H.post(new Runnable() {
            @Override public void run() {
                try { work.run(); }
                finally { done.countDown(); }
            }
        });
        try { return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Throwable ignored) { return false; }
    }

    private static void drop() {
        try {
            if (pulse != null) { pulse.stop(); pulse = null; }
            if (view != null && wm != null) { wm.removeViewImmediate(view); android.util.Log.i("AIssistant", "border gone"); }
        } catch (Throwable ignored) { }
        view = null;
    }

    private static void show(Context ctx) {
        try {
            // The operation can finish before its queued show work reaches the main thread.
            if (activeOperations <= 0 || OverlayHub.agentIsolation()) return;
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
                            | WindowManager.LayoutParams.FLAG_SECURE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);   // no status/nav bar inset
            if (android.os.Build.VERSION.SDK_INT >= 28)
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            wm.addView(e, lp);
            android.util.Log.i("AIssistant", "border window added");
            view = e;
            pulse = new Pulse(e);
            pulse.start();
        } catch (Throwable t) { android.util.Log.w("AIssistant", "border show FAILED: " + t); }
    }

    /**
     * One continuous ambient edge.  Android 13+ draws it with one GPU distance-field shader;
     * older devices get a calm static gradient fallback.  Keeping this as one surface means the
     * moving inner tide keeps its phase through every corner instead of looking like four strips.
     */
    private static final class Edge extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long t0 = System.currentTimeMillis();
        private final float density;
        private final RectF displayBounds = new RectF();
        private Path physicalScreen;
        private RuntimeShader runtime;
        private Fallback fallback;
        private float tlX, tlY, tlR;
        private float trX, trY, trR;
        private float brX, brY, brR;
        private float blX, blY, blR;

        /*
         * Distance field measured inward from nearest display edge. One radial pulse changes the
         * fade depth across every edge together: it travels into the screen, then back out.
         */
        private static final String AMBIENT_EDGE_SHADER =
                "uniform float2 u_resolution;\n"
                        + "uniform float u_density;\n"
                        + "uniform float u_time;\n"
                        + "uniform float3 u_tl;\n"
                        + "uniform float3 u_tr;\n"
                        + "uniform float3 u_br;\n"
                        + "uniform float3 u_bl;\n"
                        + "half4 main(float2 fragCoord) {\n"
                        + "  float w = u_resolution.x;\n"
                        + "  float h = u_resolution.y;\n"
                        + "  float l = fragCoord.x;\n"
                        + "  float r = w - fragCoord.x;\n"
                        + "  float t = fragCoord.y;\n"
                        + "  float b = h - fragCoord.y;\n"
                        + "  float edge = min(min(l, r), min(t, b));\n"
                        + "  float activeTL = step(0.5, u_tl.z) * step(fragCoord.x, u_tl.x) * step(fragCoord.y, u_tl.y);\n"
                        + "  float activeTR = step(0.5, u_tr.z) * step(u_tr.x, fragCoord.x) * step(fragCoord.y, u_tr.y);\n"
                        + "  float activeBR = step(0.5, u_br.z) * step(u_br.x, fragCoord.x) * step(u_br.y, fragCoord.y);\n"
                        + "  float activeBL = step(0.5, u_bl.z) * step(fragCoord.x, u_bl.x) * step(u_bl.y, fragCoord.y);\n"
                        + "  float roundTL = mix(1000000.0, u_tl.z - length(fragCoord - u_tl.xy), activeTL);\n"
                        + "  float roundTR = mix(1000000.0, u_tr.z - length(fragCoord - u_tr.xy), activeTR);\n"
                        + "  float roundBR = mix(1000000.0, u_br.z - length(fragCoord - u_br.xy), activeBR);\n"
                        + "  float roundBL = mix(1000000.0, u_bl.z - length(fragCoord - u_bl.xy), activeBL);\n"
                        + "  edge = min(edge, min(min(roundTL, roundTR), min(roundBR, roundBL)));\n"
                        + "  float pulse = 0.5 + 0.5 * sin(u_time * 1.10);\n"
                        + "  float solid = 1.80 * u_density;\n"
                        + "  float fadeEnd = (18.0 + 8.0 * pulse) * u_density;\n"
                        + "  float fade = 1.0 - smoothstep(solid, fadeEnd, edge);\n"
                        + "  float opacityFalloff = fade * fade;\n"
                        + "  half3 blue = half3(0.086, 0.365, 1.00);\n"
                        + "  half alpha = half(clamp(opacityFalloff * (0.78 + 0.12 * pulse), 0.0, 0.90));\n"
                        + "  return half4(blue * alpha, alpha);\n"
                        + "}\n";

        Edge(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL);
            p.setDither(true);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            if (Build.VERSION.SDK_INT >= 33) initRuntimeShader();
        }

        @SuppressWarnings("NewApi")
        private void initRuntimeShader() {
            try {
                runtime = new RuntimeShader(AMBIENT_EDGE_SHADER);
                p.setShader(runtime);
            } catch (Throwable ignored) {
                runtime = null;
                p.setShader(null);
            }
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            if (w <= 0 || h <= 0) return;
            rebuildScreenShape(getRootWindowInsets());
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            requestApplyInsets();
            post(new Runnable() { @Override public void run() { rebuildScreenShape(getRootWindowInsets()); } });
        }

        @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            rebuildScreenShape(insets);
            return super.onApplyWindowInsets(insets);
        }

        /** Build exact display contour once per resize/insets event, never during animation frames. */
        private void rebuildScreenShape(WindowInsets insets) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            readRoundedCorners(insets, w, h);
            displayBounds.set(0, 0, w, h);
            Path shape = new Path();
            shape.addRoundRect(displayBounds, new float[] {
                    tlR, tlR, trR, trR, brR, brR, blR, blR
            }, Path.Direction.CW);
            // Camera dots/notches are hardware occlusions, not a route for the activity cue.
            // Keep the rim continuous across them; the display compositor naturally hides pixels
            // where a device has no usable panel.
            physicalScreen = shape;
            if (runtime != null) {
                setRuntimeSize(w, h);
                setRuntimeCorners();
            } else {
                fallback = new Fallback(density, w, h);
            }
            invalidate();
        }

        /** API 31 returns each physical corner radius and its true center for this window. */
        private void readRoundedCorners(WindowInsets insets, int w, int h) {
            tlX = 0; tlY = 0; tlR = 0;
            trX = w; trY = 0; trR = 0;
            brX = w; brY = h; brR = 0;
            blX = 0; blY = h; blR = 0;
            if (insets == null || Build.VERSION.SDK_INT < 31) return;
            applyCorner(insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT), 0);
            applyCorner(insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT), 1);
            applyCorner(insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT), 2);
            applyCorner(insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT), 3);
        }

        @SuppressWarnings("NewApi")
        private void applyCorner(RoundedCorner corner, int position) {
            if (corner == null || corner.getRadius() <= 0) return;
            float x = corner.getCenter().x;
            float y = corner.getCenter().y;
            float r = corner.getRadius();
            if (position == 0) { tlX = x; tlY = y; tlR = r; }
            else if (position == 1) { trX = x; trY = y; trR = r; }
            else if (position == 2) { brX = x; brY = y; brR = r; }
            else { blX = x; blY = y; blR = r; }
        }

        @SuppressWarnings("NewApi")
        private void setRuntimeSize(int w, int h) {
            runtime.setFloatUniform("u_resolution", (float) w, (float) h);
            runtime.setFloatUniform("u_density", density);
        }

        @SuppressWarnings("NewApi")
        private void setRuntimeCorners() {
            runtime.setFloatUniform("u_tl", tlX, tlY, tlR);
            runtime.setFloatUniform("u_tr", trX, trY, trR);
            runtime.setFloatUniform("u_br", brX, brY, brR);
            runtime.setFloatUniform("u_bl", blX, blY, blR);
        }

        @Override protected void onDraw(Canvas c) {
            int clipped = -1;
            if (physicalScreen != null) {
                clipped = c.save();
                c.clipPath(physicalScreen);
            }
            if (runtime != null) drawRuntime(c);
            else if (fallback != null) fallback.draw(c, p, (System.currentTimeMillis() - t0) / 1000f);
            if (clipped >= 0) c.restoreToCount(clipped);
        }

        @SuppressWarnings("NewApi")
        private void drawRuntime(Canvas c) {
            runtime.setFloatUniform("u_time", (System.currentTimeMillis() - t0) / 1000f);
            c.drawPaint(p);
        }

        /** Static smooth fallback for API 26-32; keeps same safe, non-interactive overlay. */
        private static final class Fallback {
            private final Shader top, right, bottom, left;
            private final float inset;

            Fallback(float density, int w, int h) {
                inset = 26f * density;
                int outer = Color.argb(230, 22, 93, 255);
                int middle = Color.argb(36, 22, 93, 255);
                int clear = Color.argb(0, 22, 93, 255);
                top = new LinearGradient(0, 0, 0, inset,
                        new int[] { outer, middle, clear }, new float[] { 0f, .56f, 1f }, Shader.TileMode.CLAMP);
                right = new LinearGradient(w, 0, w - inset, 0,
                        new int[] { outer, middle, clear }, new float[] { 0f, .56f, 1f }, Shader.TileMode.CLAMP);
                bottom = new LinearGradient(0, h, 0, h - inset,
                        new int[] { outer, middle, clear }, new float[] { 0f, .56f, 1f }, Shader.TileMode.CLAMP);
                left = new LinearGradient(0, 0, inset, 0,
                        new int[] { outer, middle, clear }, new float[] { 0f, .56f, 1f }, Shader.TileMode.CLAMP);
            }

            void draw(Canvas c, Paint p, float seconds) {
                float breath = .90f + .10f * (float) Math.sin(seconds * .44f);
                p.setAlpha((int) (255f * breath));
                p.setShader(top); c.drawRect(0, 0, c.getWidth(), inset, p);
                p.setShader(right); c.drawRect(c.getWidth() - inset, 0, c.getWidth(), c.getHeight(), p);
                p.setShader(bottom); c.drawRect(0, c.getHeight() - inset, c.getWidth(), c.getHeight(), p);
                p.setShader(left); c.drawRect(0, 0, inset, c.getHeight(), p);
                p.setAlpha(255);
                p.setShader(null);
            }
        }
    }

    /** Vsync pacing keeps the full-screen shader smooth without timer jitter. */
    private static final class Pulse implements Choreographer.FrameCallback {
        private final View v;
        private boolean on;

        Pulse(View v) { this.v = v; }

        void start() { on = true; Choreographer.getInstance().postFrameCallback(this); }
        void stop() { on = false; Choreographer.getInstance().removeFrameCallback(this); }
        void bump() { if (on) v.postInvalidateOnAnimation(); }

        @Override public void doFrame(long frameTimeNanos) {
            if (!on) return;
            try {
                v.invalidate();
            } catch (Throwable ignored) { }
            if (on) Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
