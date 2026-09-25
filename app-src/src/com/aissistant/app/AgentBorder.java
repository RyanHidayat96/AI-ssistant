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
    /** A launch returns before Activity.onPause on many devices; retain its edge through that handoff. */
    private static final long LAUNCH_BORDER_SETTLE_MS = 900L;
    /** Short model thinking stays on target app; long thinking returns the session page. */
    private static final long LONG_THINK_SESSION_RETURN_MS = 8000L;

    private static View view;
    private static WindowManager wm;
    private static Pulse pulse;
    private static Context lastContext;
    /** Obsolete queued handoff holder; kept only so older queued callbacks can be cancelled safely. */
    private static Runnable pendingReturnToMain;
    /** Number of target-app actions in flight. Main-thread only. */
    private static int activeOperations;
    /** True from first target action until the owning agent run finishes. Main-thread only. */
    private static boolean targetAppSession;
    /** At least one foreground target operation, including a launch, happened in this session. */
    private static boolean targetOperationCompleted;
    private static final int MODE_ACTIVE = 1;
    private static final int MODE_HOLD = 2;
    /** Active = bright control, hold = dim reserved target while model plans next UI step. */
    private static int visualMode;

    private AgentBorder() { }

    /** Start one command that controls or observes another app. Always pair with {@link #endOperation()}. */
    public static boolean beginOperation(Context ctx, String cmd) {
        try {
            String c = cmd == null ? "" : cmd.toLowerCase(java.util.Locale.ENGLISH);
            if (!controlsTargetApp(c)) return false;
            android.util.Log.i("AIssistant", "border operation start: "
                    + c.substring(0, Math.min(60, c.length())));
            final Context ac = ctx.getApplicationContext();
            lastContext = ac;
            H.post(new Runnable() { @Override public void run() {
                cancelQueuedReturnToMain();
                targetAppSession = true;
                targetOperationCompleted = true;
                activeOperations++;
                visualMode = MODE_ACTIVE;
                show(ac);
            } });
            return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** Main activity is now behind an app while an action remains in flight. */
    public static void showForBackgroundOperation(final Context ctx) {
        if (ctx == null) return;
        final Context ac = ctx.getApplicationContext();
        lastContext = ac;
        // onPause happens before onStop, so MainActivity.appVisible can still be true here.
        // This callback is the lifecycle proof that the target app is taking the foreground.
        H.post(new Runnable() { @Override public void run() { show(ac, true); } });
    }

    /** Recreate edge only if target-app action still runs after UI isolation ends. */
    public static void restoreForActiveOperation(Context ctx) {
        showForBackgroundOperation(ctx);
    }

    /** User returned to AI-ssistant; keep run state but remove duplicate visual chrome. */
    public static void hideForForegroundApp() {
        H.post(new Runnable() { @Override public void run() {
            cancelQueuedReturnToMain();
            drop();
            if (activeOperations == 0) {
                targetAppSession = false;
                targetOperationCompleted = false;
                visualMode = 0;
            }
        } });
    }

    /** Begin a target-window Accessibility action. This path never performs raw screen input. */
    public static boolean beginAccessibilityOperation(Context ctx) {
        try {
            if (ctx == null || OverlayHub.agentIsolation()) return false;
            final Context ac = ctx.getApplicationContext();
            lastContext = ac;
            final java.util.concurrent.atomic.AtomicBoolean done =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            boolean completed = onMainAndWait(new Runnable() { @Override public void run() {
                cancelQueuedReturnToMain();
                targetAppSession = true;
                targetOperationCompleted = true;
                activeOperations++;
                visualMode = MODE_ACTIVE;
                show(ac);
                done.set(true);
            } }, 700L);
            return completed && done.get();
        } catch (Throwable ignored) { }
        return false;
    }

    /** Short visual hold for one-click Accessibility actions; repeated calls merge into one pulse. */
    public static boolean pulseAccessibilityOperation(Context ctx) {
        if (!beginAccessibilityOperation(ctx)) return false;
        H.postDelayed(new Runnable() { @Override public void run() { endOperation(); } }, 800L);
        return true;
    }

    /** End one target-app action and remove only its visual indicator. */
    public static void endOperation() {
        H.post(new Runnable() { @Override public void run() { endOperationOnMain(); } });
    }

    /**
     * Finish a shell operation. Starting another Activity often returns before its pause
     * lifecycle callback, so hold only launch commands briefly for a visible, reliable edge.
     */
    public static void endOperationAfterCommand(String cmd) {
        String c = cmd == null ? "" : cmd.toLowerCase(java.util.Locale.ENGLISH);
        long delay = launchesTargetApp(c) ? LAUNCH_BORDER_SETTLE_MS : 0L;
        H.postDelayed(new Runnable() { @Override public void run() { endOperationOnMain(); } }, delay);
    }

    private static void endOperationOnMain() {
        if (activeOperations > 0) activeOperations--;
        if (activeOperations == 0) {
            if (targetAppSession && !MainActivity.appVisible) hold();
            else drop();
        }
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

    /** UI state reads still isolate overlay, but do not claim user app is being operated. */
    private static boolean controlsTargetApp(String c) {
        return c.matches("(?s).*\\bmonkey\\b.*")
                || c.matches("(?s).*\\bam\\s+start\\b.*")
                || c.matches("(?s).*\\binput\\s+(tap|text|keyevent|swipe|roll|press)\\b.*")
                || c.matches("(?s).*\\b(sendevent|uinput)\\b.*")
                || c.matches("(?s).*\\bservice\\s+call\\s+input\\b.*")
                || c.matches("(?s).*\\bcmd\\s+(activity|input)\\b.*");
    }

    /** Public classifier for the run loop: true means keep the target app in front for this command. */
    public static boolean drivesTargetAppCommand(String cmd) {
        String c = cmd == null ? "" : cmd.toLowerCase(java.util.Locale.ENGLISH);
        return drivesTargetApp(c);
    }

    /** Starting a target immediately is necessary; the first input/gesture waits after launch. */
    private static boolean launchesTargetApp(String c) {
        return c.matches("(?s).*\\bmonkey\\b.*")
                || c.matches("(?s).*\\bam\\s+start\\b.*")
                || c.matches("(?s).*\\bcmd\\s+activity\\b.*");
    }

    private static boolean observesTargetScreen(String c) {
        return c.matches("(?s).*\\b(screencap|uiautomator)\\b.*")
                || c.matches("(?s).*\\bdumpsys\\s+(window|activity|input|accessibility|surfaceflinger)\\b.*");
    }

    /** Synchronize raw-command transition. Border remains only for an in-flight target action. */
    static boolean suppressForAgentRun() {
        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean completed = onMainAndWait(new Runnable() {
            @Override public void run() {
                try {
                    if (activeOperations == 0 || MainActivity.appVisible) drop();
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
            android.util.Log.i("AIssistant", "overlay held focus - release focus");
            try { OverlayView.releaseFocus(); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }

    /** Stop current target visuals without changing the foreground app. */
    public static void hide() {
        H.post(new Runnable() { @Override public void run() {
            activeOperations = 0;
            visualMode = 0;
            drop();
        } });
    }

    /**
     * The worker has conclusively finished.  This is the only point at which returning the
     * session page is safe: no later model turn can still need the target app in front.
     */
    public static void finishRun() {
        H.post(new Runnable() { @Override public void run() {
            activeOperations = 0;
            visualMode = 0;
            drop();
            returnToSessionIfIdleOnMain();
        } });
    }

    /**
     * The agent has switched from operating a target app to ordinary non-UI work or final output.
     * Return the session page immediately when no target action is in flight.
     */
    public static void handoffToSessionIfIdle() {
        H.post(new Runnable() { @Override public void run() { returnToSessionIfIdleOnMain(); } });
    }

    /** Model thinking often emits the next UI action quickly; avoid bouncing unless it takes long. */
    public static void handoffToSessionIfLongThinking() {
        H.post(new Runnable() { @Override public void run() {
            cancelQueuedReturnToMain();
            if (activeOperations != 0 || !targetAppSession) return;
            if (MainActivity.appVisible) {
                targetAppSession = false;
                targetOperationCompleted = false;
                return;
            }
            pendingReturnToMain = new Runnable() {
                @Override public void run() {
                    pendingReturnToMain = null;
                    returnToSessionIfIdleOnMain();
                }
            };
            H.postDelayed(pendingReturnToMain, LONG_THINK_SESSION_RETURN_MS);
        } });
    }

    private static void returnToSessionIfIdleOnMain() {
        cancelQueuedReturnToMain();
        if (activeOperations != 0 || !targetAppSession) return;
        if (MainActivity.appVisible) {
            targetAppSession = false;
            targetOperationCompleted = false;
            visualMode = 0;
            return;
        }
        targetAppSession = false;
        targetOperationCompleted = false;
        visualMode = 0;
        drop();
        MainActivity host = MainActivity.instance;
        if (host != null) host.returnToMainAfterTargetOperation();
    }

    /** Any next target action wins over queued idle handoff. */
    private static void cancelQueuedReturnToMain() {
        if (pendingReturnToMain == null) return;
        H.removeCallbacks(pendingReturnToMain);
        pendingReturnToMain = null;
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

    private static void hold() {
        visualMode = MODE_HOLD;
        try {
            if (!(view instanceof Edge) && lastContext != null && !MainActivity.appVisible) showReserved(lastContext);
            if (view instanceof Edge) {
                Edge edge = (Edge) view;
                edge.setHold(true);
                if (pulse == null) { pulse = new Pulse(edge); pulse.start(); }
                else pulse.bump();
            }
            android.util.Log.i("AIssistant", "border hold");
        } catch (Throwable ignored) { }
    }

    /** Reserved target-app state: visible cue without claiming an input/scroll is currently firing. */
    private static void showReserved(Context ctx) {
        try {
            if (ctx == null || MainActivity.appVisible || view != null) return;
            lastContext = ctx;
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            Edge e = new Edge(ctx);
            e.setHold(true);
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
            if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
            if (android.os.Build.VERSION.SDK_INT >= 28)
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            wm.addView(e, lp);
            view = e;
            pulse = new Pulse(e);
            pulse.start();
            android.util.Log.i("AIssistant", "border hold window added");
        } catch (Throwable t) { android.util.Log.w("AIssistant", "border hold show FAILED: " + t); }
    }

    private static void drop() {
        visualMode = 0;
        try {
            if (pulse != null) { pulse.stop(); pulse = null; }
            if (view != null && wm != null) { wm.removeViewImmediate(view); android.util.Log.i("AIssistant", "border gone"); }
        } catch (Throwable ignored) { }
        view = null;
    }

    private static void show(Context ctx) { show(ctx, false); }

    /** onPause has already proved the app left foreground even though onStop may not run yet. */
    private static void show(Context ctx, boolean duringBackgroundTransition) {
        try {
            // This edge is secure, non-touchable and hidden from Accessibility. Active mode means touch/scroll/input;
            // hold mode means the target app is still reserved while the model plans the next UI action.
            if (activeOperations <= 0 || (MainActivity.appVisible && !duringBackgroundTransition)) return;
            visualMode = MODE_ACTIVE;
            if (view instanceof Edge) { ((Edge) view).setHold(false); if (pulse != null) pulse.bump(); return; }
            lastContext = ctx;
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
        private boolean hold;
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
                        + "uniform float u_hold;\n"
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
                        + "  float hold = clamp(u_hold, 0.0, 1.0);\n"
                        + "  float pulse = 0.5 + 0.5 * sin(u_time * mix(0.52, 0.95, 1.0 - hold));\n"
                        + "  float solid = 1.25 * u_density;\n"
                        + "  float fadeEnd = (30.0 + 6.0 * pulse) * u_density;\n"
                        + "  float fade = 1.0 - smoothstep(solid, fadeEnd, edge);\n"
                        + "  fade = pow(clamp(fade, 0.0, 1.0), 1.55);\n"
                        + "  float innerGate = 1.0 - smoothstep(fadeEnd * 0.68, fadeEnd, edge);\n"
                        + "  float outerGlow = 1.0 - smoothstep(0.0, 7.0 * u_density, edge);\n"
                        + "  float wavePhase = edge / max(u_density, 0.001) * 0.92 - u_time * mix(1.25, 5.25, 1.0 - hold);\n"
                        + "  float wave = 0.5 + 0.5 * sin(wavePhase);\n"
                        + "  float crest = smoothstep(0.62, 1.0, wave) * innerGate * fade * mix(0.18, 1.0, 1.0 - hold);\n"
                        + "  float alphaF = (outerGlow * 0.66 + fade * 0.52 + crest * 0.34) * mix(0.34, 1.0, 1.0 - hold);\n"
                        + "  half3 blue = half3(0.020, 0.455, 1.00);\n"
                        + "  half alpha = half(clamp(alphaF, 0.0, 0.94));\n"
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

        void setHold(boolean hold) {
            if (this.hold == hold) return;
            this.hold = hold;
            if (runtime != null) runtime.setFloatUniform("u_hold", hold ? 1f : 0f);
            invalidate();
        }

        @SuppressWarnings("NewApi")
        private void initRuntimeShader() {
            try {
                runtime = new RuntimeShader(AMBIENT_EDGE_SHADER);
                runtime.setFloatUniform("u_hold", hold ? 1f : 0f);
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
            runtime.setFloatUniform("u_hold", hold ? 1f : 0f);
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
            else if (fallback != null) fallback.draw(c, p, (System.currentTimeMillis() - t0) / 1000f, hold);
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
            private final float density;
            private final float inset;

            Fallback(float density, int w, int h) {
                this.density = density;
                inset = 36f * density;
                int outer = Color.argb(240, 5, 116, 255);
                int middle = Color.argb(72, 5, 116, 255);
                int clear = Color.argb(0, 5, 116, 255);
                top = new LinearGradient(0, 0, 0, inset,
                        new int[] { outer, middle, clear }, new float[] { 0f, .42f, 1f }, Shader.TileMode.CLAMP);
                right = new LinearGradient(w, 0, w - inset, 0,
                        new int[] { outer, middle, clear }, new float[] { 0f, .42f, 1f }, Shader.TileMode.CLAMP);
                bottom = new LinearGradient(0, h, 0, h - inset,
                        new int[] { outer, middle, clear }, new float[] { 0f, .42f, 1f }, Shader.TileMode.CLAMP);
                left = new LinearGradient(0, 0, inset, 0,
                        new int[] { outer, middle, clear }, new float[] { 0f, .42f, 1f }, Shader.TileMode.CLAMP);
            }

            void draw(Canvas c, Paint p, float seconds, boolean hold) {
                float breath = .92f + .08f * (float) Math.sin(seconds * (hold ? .38f : .72f));
                float modeAlpha = hold ? .34f : 1f;
                p.setAlpha((int) (255f * breath * modeAlpha));
                p.setShader(top); c.drawRect(0, 0, c.getWidth(), inset, p);
                p.setShader(right); c.drawRect(c.getWidth() - inset, 0, c.getWidth(), c.getHeight(), p);
                p.setShader(bottom); c.drawRect(0, c.getHeight() - inset, c.getWidth(), c.getHeight(), p);
                p.setShader(left); c.drawRect(0, 0, inset, c.getHeight(), p);
                p.setShader(null);
                drawWaveStrips(c, p, seconds, hold);
                p.setAlpha(255);
            }

            private void drawWaveStrips(Canvas c, Paint p, float seconds, boolean hold) {
                int w = c.getWidth();
                int h = c.getHeight();
                float step = 7.5f * density;
                float strip = Math.max(1.0f, 1.35f * density);
                int blue = Color.rgb(5, 116, 255);
                p.setStyle(Paint.Style.FILL);
                for (float d = 5f * density; d < inset * .72f; d += step) {
                    float normalized = 1f - (d / inset);
                    float wave = .5f + .5f * (float) Math.sin((d / density) * .95f - seconds * (hold ? 1.3f : 5.4f));
                    int alpha = (int) ((hold ? 18f : 72f) * normalized * normalized * wave);
                    if (alpha < 8) continue;
                    p.setColor(Color.argb(alpha, Color.red(blue), Color.green(blue), Color.blue(blue)));
                    c.drawRect(0, d, w, d + strip, p);
                    c.drawRect(0, h - d - strip, w, h - d, p);
                    c.drawRect(d, 0, d + strip, h, p);
                    c.drawRect(w - d - strip, 0, w - d, h, p);
                }
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

