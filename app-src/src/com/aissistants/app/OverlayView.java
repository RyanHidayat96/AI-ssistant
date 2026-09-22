package com.aissistants.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Floating panel that keeps the run visible while the agent drives another app: the activity goes to
 * the background (opening WhatsApp, a game, the dialer), this window stays on top with the live
 * status, the last transcript lines, STOP and a way back into the app.
 */
final class OverlayView {

    private static final int SURFACE = Color.rgb(19, 28, 43);
    private static final int TOOL_BG = Color.rgb(8, 13, 22);
    private static final int LINE = Color.rgb(34, 48, 74);
    private static final int FG = Color.rgb(232, 237, 247);
    private static final int MUTED = Color.rgb(147, 160, 184);
    private static final int ACCENT = Color.rgb(59, 130, 246);
    private static final int DANGER = Color.rgb(239, 68, 68);
    private static final int ON_ACCENT = Color.rgb(6, 18, 31);

    private static OverlayView current;

    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private View panel;
    private LinearLayout body;
    private ScrollView scroll;
    private TextView statusView;
    private Handler timer;
    private boolean collapsed;
    private int lastCount = -1;
    private int lastVersion = -1;
    private boolean atBottom = true;
    private TextView jumpPill;       // "↓ N" pill: new lines arrived while the user reads older ones
    private int pendingNew;
    private android.widget.EditText input;
    private int panelW;          // panel width in px (draggable)
    private int transcriptH;     // chat area height in px (draggable)
    private android.view.View grip;

    private OverlayView(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        android.content.SharedPreferences sp = ctx.getSharedPreferences("aissistants", Context.MODE_PRIVATE);
        panelW = sp.getInt("ovW", 0);
        transcriptH = sp.getInt("ovH", 0);
        if (panelW <= 0) panelW = defaultWidth();
        if (transcriptH <= 0) transcriptH = dp(230);
        this.lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NON-focusable by default. A driving agent verifies the target app through
                // `dumpsys window | grep mCurrentFocus` and uiautomator: if this panel holds the
                // input focus those checks see the panel instead of the app being driven, and the
                // agent "loses" the app. Focus is lent to the panel only while the user types
                // (useIme(true)), and outside taps still reach the app via NOT_TOUCH_MODAL.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 24;
        lp.y = 220;
    }

    /** does this app hold the "draw over other apps" permission? */
    static boolean canDraw(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try { return Settings.canDrawOverlays(ctx); } catch (Throwable t) { return false; }
    }

    /** settings page where the user grants it (there is no runtime dialog for this one) */
    static Intent permissionIntent(Context ctx) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        i.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    static void show(Context ctx) {
        try {
            if (OverlayHub.overlaySuppressedForDriving()) return;
            if (!canDraw(ctx)) return;
            if (current != null) { current.attach(); return; }
            current = new OverlayView(ctx);
            current.attach();
        } catch (Throwable t) {
            android.util.Log.e("AIssistants", "overlay show failed: " + t);
        }
    }

    static void hide() {
        android.util.Log.i("AIssistants", "overlay hide requested\n" + android.util.Log.getStackTraceString(new Throwable()));
        try {
            if (current != null) current.detach();
        } catch (Throwable t) {
            android.util.Log.e("AIssistants", "overlay hide failed: " + t);
        }
        current = null;
    }

    /**
     * The agent is about to observe or control a different app. Remove the full interactive
     * panel, not merely its input focus: FLAG_NOT_TOUCH_MODAL still lets the panel consume taps
     * inside its own bounds. Suppression prevents lifecycle/service callbacks from recreating it
     * between a launch and the next UI action. The notification and non-touchable AgentBorder
     * remain available as progress indicators.
     */
    static void standDownForAgent() {
        OverlayHub.suppressForDriving();
        final Runnable remove = new Runnable() {
            @Override public void run() {
                try {
                    OverlayView ov = current;
                    current = null;
                    if (ov != null) ov.detach();
                } catch (Throwable t) {
                    android.util.Log.e("AIssistants", "overlay agent stand-down failed: " + t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove.run();
            return;
        }
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { remove.run(); }
                finally { done.countDown(); }
            }
        });
        try { done.await(350, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Throwable ignored) { }
    }

    static boolean visible() { return current != null && current.panel != null; }

    /** Called before agent-side observation so this panel never becomes the reported app focus. */
    static void releaseFocus() {
        try {
            final OverlayView ov = current;
            if (ov == null) return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                ov.useIme(false);
                return;
            }
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try { ov.useIme(false); }
                    finally { done.countDown(); }
                }
            });
            try { done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (Throwable ignored) { }
        } catch (Throwable t) {
            android.util.Log.e("AIssistants", "overlay release focus failed: " + t);
        }
    }

    // ---- the panel -----------------------------------------------------------------------------

    private void attach() {
        if (panel != null) return;
        panel = build();
        try {
            // keep the panel out of accessibility trees: uiautomator dumps drive the agent, and the
            // panel's nodes would otherwise show up in them as if they were part of the target app
            panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            wm.addView(panel, lp);
            OverlayHub.setVisible(true);
            startTimer();
            android.util.Log.i("AIssistants", "overlay up");
        } catch (Throwable t) {
            android.util.Log.e("AIssistants", "overlay add failed: " + t);
            panel = null;
        }
    }

    private void detach() {
        stopTimer();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Throwable ignored) { }
            panel = null;
        }
        OverlayHub.setVisible(false);
    }

    private void startTimer() {
        stopTimer();
        timer = new Handler(Looper.getMainLooper());
        timer.post(new Runnable() {
            @Override public void run() {
                refresh();
                if (timer != null) timer.postDelayed(this, 600);
            }
        });
    }

    private void stopTimer() {
        if (timer != null) {
            try { timer.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
            timer = null;
        }
    }

    private View build() {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(SURFACE, LINE, 14));
        root.setElevation(dp(9));
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) useIme(false);
                return false;
            }
        });

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(ctx);
        dot.setText("\u25CF");
        dot.setTextSize(10);
        dot.setTextColor(ACCENT);
        dot.setPadding(0, 0, dp(6), 0);
        head.addView(dot);

        statusView = new TextView(ctx);
        statusView.setTextSize(11);
        statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusView.setTextColor(FG);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(statusView, new LinearLayout.LayoutParams(0, -2, 1));

        head.addView(chip("STOP", DANGER, ON_ACCENT, new Runnable() {
            @Override public void run() {
                OverlayHub.requestStop();
                flash("dihentikan…");
            }
        }));
        head.addView(chip(collapsed ? "\u25B2" : "\u25BC", LINE, FG, new Runnable() {
            @Override public void run() { toggleCollapse(); }
        }));
        head.addView(chip("\u2197", LINE, FG, new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(ctx, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    ctx.startActivity(i);
                } catch (Throwable ignored) { }
            }
        }));
        head.addView(chip("\u2715", LINE, MUTED, new Runnable() {
            @Override public void run() { hide(); }
        }));
        installDrag(head);
        root.addView(head);

        scroll = new ScrollView(ctx);
        android.widget.FrameLayout holder = new android.widget.FrameLayout(ctx);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(widthPx(), transcriptH);
        slp.setMargins(0, dp(8), 0, 0);
        holder.setLayoutParams(slp);
        scroll.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
        scroll.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) useIme(false);
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    atBottom = isAtBottom();     // respect the user's scroll position
                }
                return false;
            }
        });
        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackground(round(TOOL_BG, LINE, 10));
        body.setPadding(dp(8), dp(6), dp(8), dp(6));
        scroll.addView(body);
        jumpPill = chip("\u2193 1 pesan", ACCENT, ON_ACCENT, new Runnable() {
            @Override public void run() {
                atBottom = true;
                pendingNew = 0;
                if (jumpPill != null) jumpPill.setVisibility(View.GONE);
                if (scroll != null) {
                    final ScrollView sc = scroll;
                    sc.post(new Runnable() { @Override public void run() {
                        try { sc.scrollTo(0, body == null ? 0 : body.getMeasuredHeight()); } catch (Throwable ignored) { } } });
                }
            }
        });
        jumpPill.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams jlp =
                new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        jlp.setMargins(0, 0, 0, dp(10));
        holder.addView(scroll);
        holder.addView(jumpPill, jlp);
        root.addView(holder);

        // input row: type a prompt straight into the panel (works mid-run too)
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(round(TOOL_BG, LINE, 12));
        row.setPadding(dp(8), dp(4), dp(6), dp(4));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(widthPx(), -2);
        rlp.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(rlp);

        input = new android.widget.EditText(ctx);
        input.setHint(mainAlive() ? "Ketik prompt\u2026" : "buka app dulu");
        input.setHintTextColor(MUTED);
        input.setTextColor(FG);
        input.setTextSize(13);
        input.setMaxLines(3);
        input.setMinHeight(dp(40));
        input.setBackground(null);
        input.setPadding(dp(4), dp(6), dp(6), dp(6));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { fireInput(); return true; }
                return false;
            }
        });
        // tapping the field must grab focus and pull the keyboard up, like a normal chat box
        input.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { grabIme(); }
        });
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { if (has) grabIme(); else useIme(false); }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(chip("\u2191", ACCENT, ON_ACCENT, new Runnable() {
            @Override public void run() { fireInput(); }
        }));
        root.addView(row);

        // resize grip: drag to change width + chat height, double-tap to reset
        grip = buildGrip();
        grip.setOnClickListener(new View.OnClickListener() {
            private long lastTap;
            @Override public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - lastTap < 300) {                 // double tap = back to the default size
                    panelW = defaultWidth();
                    transcriptH = dp(230);
                    applySize();
                    saveSize();
                }
                lastTap = now;
            }
        });
        root.addView(grip);
        return root;
    }

    /** hand the typed prompt to the activity (same path as the main input row) */
    private void fireInput() {
        if (input == null) return;
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;
        MainActivity host = MainActivity.instance;
        if (host == null) {
            OverlayHub.line("(app-nya sudah ditutup - buka AI-ssistants dulu)");
            return;
        }
        input.setText("");
        OverlayHub.line("> " + text);
        useIme(false);            // give the input focus straight back to the app being driven
        try { host.overlaySend(text); }
        catch (Throwable t) { OverlayHub.line("gagal kirim: " + t); }
    }

    private boolean mainAlive() { return MainActivity.instance != null; }

    /** focus the panel's field and let the keyboard connect to THIS window */
    private void grabIme() {
        try {
            if (input == null) return;
            useIme(true);                     // the window must be focusable before the IME can attach
            input.requestFocus();
            Object svc = ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (svc instanceof android.view.inputmethod.InputMethodManager) {
                ((android.view.inputmethod.InputMethodManager) svc)
                        .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
            armFocusTimeout();
        } catch (Throwable t) {
            android.util.Log.e("AIssistants", "overlay ime: " + t);
        }
    }

    /**
     * Lend the input focus to the panel (on) or hand it back to the app being driven (off).
     * A focusable overlay makes `dumpsys window | grep mCurrentFocus` and uiautomator report the
     * panel, so an agent that is driving another app thinks it lost that app - hence: focus only
     * while the user is actually typing.
     */
    private void useIme(boolean on) {
        try {
            if (panel == null) return;
            int before = lp.flags;
            if (on) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            if (lp.flags != before) wm.updateViewLayout(panel, lp);
            if (!on) {
                boolean hadFocus = input != null && input.hasFocus();
                boolean wasFocusable = (before & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
                if (hadFocus || wasFocusable || focusIdle != null) {
                    Object svc = ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (svc instanceof android.view.inputmethod.InputMethodManager) {
                        ((android.view.inputmethod.InputMethodManager) svc)
                                .hideSoftInputFromWindow(panel.getWindowToken(), 0);
                    }
                    if (input != null) input.clearFocus();
                    if (focusIdle != null && timer != null) timer.removeCallbacks(focusIdle);
                    focusIdle = null;
                }
            }
            if (on || lp.flags != before) android.util.Log.i("AIssistants", "overlay focusable=" + on);
        } catch (Throwable t) {
            android.util.Log.e("AIssistants", "overlay focus toggle: " + t);
        }
    }

    /** a panel left focusable mid-run would keep the agent blind to the app it drives */
    private Runnable focusIdle;

    private void armFocusTimeout() {
        if (timer == null) return;
        if (focusIdle != null) timer.removeCallbacks(focusIdle);
        focusIdle = new Runnable() {
            @Override public void run() { useIme(false); }
        };
        timer.postDelayed(focusIdle, 45000L);
    }

    private boolean isAtBottom() {
        try {
            if (scroll == null || body == null) return true;
            return scroll.getScrollY() + scroll.getHeight() >= body.getHeight() - dp(24);
        } catch (Throwable t) { return true; }
    }

    private int widthPx() { return panelW; }

    private int defaultWidth() {
        try {
            int w = ctx.getResources().getDisplayMetrics().widthPixels;
            return Math.min(dp(360), w - dp(24));
        } catch (Throwable t) { return dp(300); }
    }

    /** a grip in the bottom-right corner: drag it to size the panel (width + chat height) */
    private View buildGrip() {
        TextView g = new TextView(ctx);
        g.setText("\u25E2");        // ◢
        g.setTextSize(13);
        g.setTextColor(MUTED);
        g.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        g.setPadding(0, 0, dp(6), 0);
        g.setMinWidth(dp(56));
        g.setMinHeight(dp(30));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, dp(30));
        glp.setMargins(0, dp(2), 0, 0);
        g.setLayoutParams(glp);
        g.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startW, startH;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        useIme(false);
                        downX = e.getRawX(); downY = e.getRawY();
                        startW = panelW; startH = transcriptH;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int maxW = ctx.getResources().getDisplayMetrics().widthPixels - dp(20);
                        int maxH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.7);
                        panelW = clamp(startW + (int) (e.getRawX() - downX), dp(220), maxW);
                        transcriptH = clamp(startH + (int) (e.getRawY() - downY), dp(90), maxH);
                        applySize();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveSize();
                        return true;
                    default:
                        return false;
                }
            }
        });
        return g;
    }

    private void applySize() {
        try {
            View holder = scroll == null ? null : (View) scroll.getParent();
            if (holder != null && holder.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) holder.getLayoutParams();
                p.width = panelW;
                p.height = transcriptH;
                holder.setLayoutParams(p);
            }
            View row = input == null ? null : (View) input.getParent();
            if (row != null) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) row.getLayoutParams();
                p.width = panelW;
                row.setLayoutParams(p);
            }
            if (panel != null) panel.requestLayout();
        } catch (Throwable t) {
            android.util.Log.e("AIssistants", "overlay resize: " + t);
        }
    }

    private void saveSize() {
        try {
            ctx.getSharedPreferences("aissistants", Context.MODE_PRIVATE).edit()
                    .putInt("ovW", panelW).putInt("ovH", transcriptH).apply();
        } catch (Throwable ignored) { }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private TextView chip(String label, int bg, int fg, final Runnable action) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(10);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(7), dp(4), dp(7), dp(4));
        t.setBackground(round(bg, bg, 9));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(5), 0, 0, 0);
        t.setLayoutParams(p);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { action.run(); }
                finally { useIme(false); }
            }
        });
        return t;
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        if (scroll != null) scroll.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (input != null && input.getParent() instanceof View) {
            ((View) input.getParent()).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        // Collapse/expand must not make the panel a focused app. Only the text field may borrow
        // focus, and it gives it back through useIme(false).
        useIme(false);
        if (panel != null) {
            View head = ((LinearLayout) panel).getChildAt(0);
            // swap the arrow chip label
            if (head instanceof LinearLayout) {
                LinearLayout h = (LinearLayout) head;
                for (int i = 0; i < h.getChildCount(); i++) {
                    View c = h.getChildAt(i);
                    if (c instanceof TextView) {
                        String s = String.valueOf(((TextView) c).getText());
                        if ("\u25B2".equals(s) || "\u25BC".equals(s)) {
                            ((TextView) c).setText(collapsed ? "\u25B2" : "\u25BC");
                        }
                    }
                }
            }
        }
        refresh();
    }

    private void flash(String s) {
        OverlayHub.setStatus(s);
        refresh();
    }

    /** repaint from the hub: status line + the tail of the transcript */
    private void refresh() {
        if (panel == null) return;
        try {
            String st = OverlayHub.status();
            if (statusView != null) {
                statusView.setText(st == null || st.isEmpty() ? "AI-ssistants \u00b7 jalan" : st);
            }
            if (collapsed || body == null) return;
            ArrayList<String> lines = OverlayHub.all();
            int v = OverlayHub.version();
            if (v == lastVersion && body.getChildCount() > 0) return;
            boolean wasAtBottom = atBottom;
            int added = lastCount < 0 ? 0 : Math.max(0, lines.size() - lastCount);
            if (!wasAtBottom && added > 0) pendingNew += added;
            lastVersion = v;
            lastCount = lines.size();
            int from = Math.max(0, lines.size() - 250);      // keep the panel light on huge chats
            body.removeAllViews();
            if (lines.isEmpty()) {
                body.addView(line("menunggu perintah\u2026", MUTED));
            } else {
                for (int i = from; i < lines.size(); i++) {
                    String s = lines.get(i);
                    int color = FG;
                    if (s.startsWith("> ")) color = ACCENT;
                    else if (s.startsWith("$ ")) color = Color.rgb(120, 200, 160);
                    else if (s.startsWith("\u00b7 ")) color = MUTED;
                    body.addView(line(s, color));
                }
            }
            if (wasAtBottom && scroll != null) {
                final ScrollView sc = scroll;
                sc.post(new Runnable() { @Override public void run() {
                    try { sc.scrollTo(0, body == null ? 0 : body.getMeasuredHeight()); } catch (Throwable ignored) { }
                } });
            }
            if (jumpPill != null) {
                if (wasAtBottom || pendingNew <= 0) {
                    if (wasAtBottom) pendingNew = 0;
                    jumpPill.setVisibility(View.GONE);
                } else {
                    jumpPill.setText("\u2193 " + pendingNew + " pesan baru");
                    jumpPill.setVisibility(View.VISIBLE);
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("AIssistants", "overlay refresh: " + t);
        }
    }

    private TextView line(String s, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setMaxLines(6);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, dp(1), 0, dp(1));
        return t;
    }

    /** drag the panel by its header, and remember where the user parked it */
    private void installDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        useIme(false);
                        startX = lp.x;
                        startY = lp.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        if (lp.x < 0) lp.x = 0;
                        if (lp.y < 0) lp.y = 0;
                        try { if (panel != null) wm.updateViewLayout(panel, lp); } catch (Throwable ignored) { }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != fill) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
