package com.aissistants.app;

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

    /**
     * One continuous ambient edge.  Android 13+ draws it with one GPU distance-field shader;
     * older devices get a calm static gradient fallback.  Keeping this as one surface means the
     * moving inner tide keeps its phase through every corner instead of looking like four strips.
     */
    private static final class Edge extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint contour = new Paint(Paint.ANTI_ALIAS_FLAG);
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
         * Distance field measured inward from nearest display edge.  `along` is a normalized
         * clockwise position around the real rectangle perimeter, so a single low-amplitude tide
         * travels across all four sides and corners without seams or jumping phase.
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
                        + "  float perimeter = 2.0 * (w + h);\n"
                        + "  float along;\n"
                        + "  if (t <= l && t <= r && t <= b) {\n"
                        + "    along = fragCoord.x / perimeter;\n"
                        + "  } else if (r <= l && r <= t && r <= b) {\n"
                        + "    along = (w + fragCoord.y) / perimeter;\n"
                        + "  } else if (b <= l && b <= r && b <= t) {\n"
                        + "    along = (w + h + (w - fragCoord.x)) / perimeter;\n"
                        + "  } else {\n"
                        + "    along = (w + h + w + (h - fragCoord.y)) / perimeter;\n"
                        + "  }\n"
                        + "  float tide = 0.5 + 0.5 * sin(along * 6.2831853 - u_time * 0.26);\n"
                        + "  float crest = smoothstep(0.80, 0.985, tide);\n"
                        + "  float solid = 1.15 * u_density;\n"
                        + "  float fadeEnd = (11.5 + 1.25 * tide) * u_density;\n"
                        + "  float fade = 1.0 - smoothstep(solid, fadeEnd, edge);\n"
                        + "  float rim = 1.0 - smoothstep(0.0, 0.82 * u_density, edge);\n"
                        + "  float tideLine = smoothstep(fadeEnd - 3.1 * u_density, fadeEnd - 1.9 * u_density, edge)\n"
                        + "      * (1.0 - smoothstep(fadeEnd - 0.55 * u_density, fadeEnd, edge));\n"
                        + "  half3 indigo = half3(0.24, 0.35, 0.84);\n"
                        + "  half3 blue = half3(0.28, 0.57, 0.98);\n"
                        + "  half3 color = mix(indigo, blue, 0.12 + 0.28 * tide);\n"
                        + "  float alpha = fade * (0.20 + 0.05 * tide) + rim * 0.10\n"
                        + "      + tideLine * (0.035 + 0.12 * crest);\n"
                        + "  return half4(color, half(clamp(alpha, 0.0, 0.37)));\n"
                        + "}\n";

        Edge(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL);
            p.setDither(true);
            contour.setStyle(Paint.Style.STROKE);
            contour.setStrokeWidth(.78f * density);
            contour.setStrokeJoin(Paint.Join.ROUND);
            contour.setStrokeCap(Paint.Cap.BUTT);
            contour.setColor(Color.argb(36, 100, 144, 255));
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
            if (clipped >= 0) {
                c.restoreToCount(clipped);
                c.drawPath(physicalScreen, contour);       // exact physical rim: rounded corners + cutout contour
            }
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
                inset = 13f * density;
                int outer = Color.argb(72, 62, 93, 220);
                int middle = Color.argb(24, 57, 111, 230);
                int clear = Color.argb(0, 57, 111, 230);
                top = new LinearGradient(0, 0, 0, inset,
                        new int[] { outer, middle, clear }, new float[] { 0f, .48f, 1f }, Shader.TileMode.CLAMP);
                right = new LinearGradient(w, 0, w - inset, 0,
                        new int[] { outer, middle, clear }, new float[] { 0f, .48f, 1f }, Shader.TileMode.CLAMP);
                bottom = new LinearGradient(0, h, 0, h - inset,
                        new int[] { outer, middle, clear }, new float[] { 0f, .48f, 1f }, Shader.TileMode.CLAMP);
                left = new LinearGradient(0, 0, inset, 0,
                        new int[] { outer, middle, clear }, new float[] { 0f, .48f, 1f }, Shader.TileMode.CLAMP);
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
