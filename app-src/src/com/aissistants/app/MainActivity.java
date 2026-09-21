package com.aissistants.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AI-ssistants - a chat-first assistant that owns the device.
 *
 * One activity, three screens (chat / chats / settings), drawn programmatically:
 *  - chat     : bubbles (user right, assistant left, root output as monospace cards, notices),
 *               suggestion cards when empty, timestamps, one button that turns Send into Stop.
 *  - chats    : every conversation with preview, rename (long-press) and delete.
 *  - settings : endpoint + agent behaviour.
 *
 * The model gets exactly one powerful tool: a root shell. Every command result is fed straight
 * back so it can verify its own work.
 */
public class MainActivity extends Activity {

    // ---- design tokens: one gutter, one rhythm -------------------------------------------
    private static final int GUTTER = 20;
    private static final int ROW_MIN = 56;

    private static final int BG        = Color.rgb(11, 18, 32);
    private static final int SURFACE   = Color.rgb(19, 28, 43);
    private static final int TOOL_BG   = Color.rgb(8, 13, 22);
    private static final int LINE      = Color.rgb(34, 48, 74);
    private static final int FG        = Color.rgb(232, 237, 247);
    private static final int MUTED     = Color.rgb(147, 160, 184);
    private static final int ACCENT    = Color.rgb(59, 130, 246);
    private static final int OK        = Color.rgb(34, 197, 94);
    private static final int WARN      = Color.rgb(245, 158, 11);
    private static final int DANGER    = Color.rgb(239, 68, 68);
    private static final int ON_ACCENT = Color.rgb(6, 18, 31);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private Store store;

    private LinearLayout root;
    private LinearLayout chatScreen, chatLog, pendingBar;
    private ScrollView chatScroll;
    private TextView barTitle, subtitle, pill;
    private EditText input;
    private Button sendBtn;
    private LinearLayout inputRow;
    private int lastIme = -1;
    private TextView streamView;
    private View busyView;

    private JSONArray sessions = new JSONArray();
    private JSONObject cur;
    private final List<JSONObject> messages = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();

    private volatile boolean busy;
    private volatile boolean stop;
    private Thread worker;
    private int stepNow, stepTotal;
    private String lastUsage = "";
    private int screen = 0;   // 0 chat, 1 chats, 2 settings

    // ==================== lifecycle ====================

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);
        // edge-to-edge (targetSdk 35 on Android 15+): adjustResize no longer resizes the window,
        // so lift the whole layout by the IME inset ourselves and keep the input row visible
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets wi) {
                int ime;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    ime = wi.getInsets(WindowInsets.Type.ime()).bottom;
                } else {
                    ime = Math.max(0, wi.getSystemWindowInsetBottom() - navigationBarHeight());
                }
                if (ime != lastIme) {
                    lastIme = ime;
                    v.setPadding(0, 0, 0, ime);
                    if (inputRow != null) {
                        inputRow.setPadding(dp(GUTTER), dp(6), dp(GUTTER),
                                ime > 0 ? dp(12) : navigationBarHeight() + dp(12));
                    }
                    if (ime > 0) scrollToBottom(true);
                }
                return wi;
            }
        });
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
                }
            } catch (Throwable ignored) { }
        }
        buildChatScreen();
        loadSessions();
        screen = 0;
        root.removeAllViews();
        root.addView(chatScreen);
        renderTranscript(true);
        refreshStatus();
        if (getIntent() != null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (screen != 0) { showChat(); return; }
        super.onBackPressed();
    }

    /** automation hooks: `--es run "<script>"` executes as root, `--es prompt "<text>"` chats */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String run = intent.getStringExtra("run");
        if (run != null && !run.trim().isEmpty()) {
            showChat();
            final String cmd = run;
            addBubble("user", "$ " + cmd);
            startAgentService();
            new Thread(new Runnable() { @Override public void run() {
                addBubble("tool", RootShell.run(cmd, store.timeoutSec()));
                persist();
                stopAgentService();
            } }).start();
            return;
        }
        String prompt = intent.getStringExtra("prompt");
        if (prompt != null && !prompt.trim().isEmpty()) {
            showChat();
            input.setText(prompt);
            onSend();
        }
    }

    // ==================== screens ====================

    private LinearLayout shell() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(BG);
        return v;
    }

    private void showChat() {
        screen = 0;
        root.removeAllViews();
        root.addView(chatScreen);
        renderTranscript(true);
    }

    private void showHistory() {
        screen = 1;
        LinearLayout v = shell();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(GUTTER), statusBarHeight() + dp(6), dp(12), dp(6));
        bar.addView(iconBtn("\u2190", new View.OnClickListener() {
            @Override public void onClick(View x) { showChat(); }
        }));
        TextView t = tv(18, FG, Typeface.BOLD);
        t.setText("Chats");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.setMargins(dp(8), 0, 0, 0);
        bar.addView(t, tlp);
        bar.addView(iconBtn("\u002B", new View.OnClickListener() {
            @Override public void onClick(View x) { newChat(); }
        }));
        v.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(GUTTER), dp(4), dp(GUTTER), dp(16));
        sc.addView(list, new ScrollView.LayoutParams(-1, -2));
        v.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        ArrayList<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject o = sessions.optJSONObject(i);
            if (o != null) sorted.add(o);
        }
        Collections.sort(sorted, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("updated", 0), a.optLong("updated", 0));
            }
        });

        if (sorted.isEmpty()) {
            TextView empty = tv(13, MUTED, Typeface.NORMAL);
            empty.setText("No chats yet.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, 0);
            list.addView(empty);
        }
        for (JSONObject s : sorted) list.addView(sessionCard(s));

        Button ncBtn = new Button(this);
        ncBtn.setText("New chat");
        ncBtn.setAllCaps(false);
        ncBtn.setTextSize(15);
        ncBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ncBtn.setTextColor(ON_ACCENT);
        ncBtn.setBackground(ripple(ACCENT, ACCENT, 14));
        ncBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { newChat(); }
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, dp(52));
        nlp.setMargins(dp(GUTTER), dp(8), dp(GUTTER), navigationBarHeight() + dp(12));
        v.addView(ncBtn, nlp);

        root.removeAllViews();
        root.addView(v);
    }

    private View sessionCard(final JSONObject s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ripple(SURFACE, LINE, 16));
        card.setPadding(dp(14), dp(10), dp(6), dp(10));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv(15, FG, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(titleOf(s));
        TextView prev = tv(12, MUTED, Typeface.NORMAL);
        prev.setSingleLine(true);
        prev.setEllipsize(TextUtils.TruncateAt.END);
        prev.setText(previewOf(s));
        TextView meta = tv(11, MUTED, Typeface.NORMAL);
        meta.setText(metaOf(s));
        info.addView(title);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(0, dp(3), 0, 0);
        info.addView(prev, plp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.setMargins(0, dp(5), 0, 0);
        info.addView(meta, mlp);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        card.addView(iconBtn("\u2715", new View.OnClickListener() {
            @Override public void onClick(View x) { confirmDelete(s); }
        }));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { openSession(s.optString("id", "")); }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View x) { renameDialog(s); return true; }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        return card;
    }

    private void showSettings() {
        screen = 2;
        LinearLayout v = shell();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(GUTTER), statusBarHeight() + dp(6), dp(12), dp(6));
        bar.addView(iconBtn("\u2190", new View.OnClickListener() {
            @Override public void onClick(View x) { showChat(); }
        }));
        TextView t = tv(18, FG, Typeface.BOLD);
        t.setText("Settings");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMargins(dp(8), 0, 0, 0);
        bar.addView(t, tlp);
        v.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(GUTTER), dp(6), dp(GUTTER), dp(24));
        sc.addView(panel, new ScrollView.LayoutParams(-1, -2));
        v.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        panel.addView(sectionLabel("ENDPOINT"));
        final EditText base = field(panel, "Base URL", store.baseUrl(), "https://api.openai.com/v1", false);
        final EditText key = field(panel, "API key", store.apiKey(), "sk-\u2026 (empty for local servers)", true);
        final EditText model = field(panel, "Model", store.model(), "gpt-4o-mini / deepseek-chat / qwen2.5\u2026", false);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.setMargins(0, dp(22), 0, 0);
        panel.addView(sectionLabel("AGENT"), hlp);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        final EditText steps = compactField(row3, "Max steps", String.valueOf(store.maxSteps()), true);
        final EditText timeout = compactField(row3, "Timeout (s)", String.valueOf(store.timeoutSec()), true);
        final EditText temp = compactField(row3, "Temp 0-100", String.valueOf(store.temperature()), true);
        row3.setPadding(0, dp(10), 0, dp(4));
        panel.addView(row3);

        final Switch auto = new Switch(this);
        auto.setText("Run commands automatically");
        auto.setTextColor(FG);
        auto.setTextSize(14);
        auto.setChecked(store.autoRun());
        auto.setMinHeight(dp(48));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.setMargins(0, dp(12), 0, 0);
        panel.addView(auto, alp);

        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextSize(15);
        save.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        save.setTextColor(ON_ACCENT);
        save.setBackground(ripple(ACCENT, ACCENT, 14));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                store.save(base.getText().toString(), key.getText().toString(),
                        model.getText().toString(), num(steps.getText().toString(), 12),
                        num(temp.getText().toString(), 30), num(timeout.getText().toString(), 180),
                        auto.isChecked());
                refreshStatus();
                toast("Saved \u00b7 " + store.model() + " @ " + hostOf(store.baseUrl()));
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(52));
        slp.setMargins(0, dp(22), 0, dp(10));
        panel.addView(save, slp);

        Button test = new Button(this);
        test.setText("Test root access");
        test.setAllCaps(false);
        test.setTextSize(15);
        test.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        test.setTextColor(FG);
        test.setBackground(ripple(SURFACE, LINE, 14));
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { showChat(); requestRoot(); }
        });
        panel.addView(test, new LinearLayout.LayoutParams(-1, dp(52)));

        root.removeAllViews();
        root.addView(v);
    }

    // ==================== chat screen ====================

    private void buildChatScreen() {
        chatScreen = shell();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(GUTTER), statusBarHeight() + dp(6), dp(12), dp(6));
        bar.addView(iconBtn("\u2630", new View.OnClickListener() {
            @Override public void onClick(View x) { showHistory(); }
        }));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        barTitle = tv(17, FG, Typeface.BOLD);
        barTitle.setSingleLine(true);
        barTitle.setEllipsize(TextUtils.TruncateAt.END);
        barTitle.setText("AI-ssistants");
        subtitle = tv(12, MUTED, Typeface.NORMAL);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(barTitle);
        head.addView(subtitle);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1);
        hlp.setMargins(dp(8), 0, 0, 0);
        bar.addView(head, hlp);

        pill = tv(11, WARN, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setMinHeight(dp(48));
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { requestRoot(); }
        });
        bar.addView(pill);

        final Button more = iconBtn("\u22EE", null);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { menu(x); }
        });
        bar.addView(more);
        chatScreen.addView(bar);

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatLog = new LinearLayout(this);
        chatLog.setOrientation(LinearLayout.VERTICAL);
        chatLog.setPadding(dp(GUTTER), dp(4), dp(GUTTER), dp(8));
        chatScroll.addView(chatLog, new ScrollView.LayoutParams(-1, -2));
        chatScreen.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        pendingBar = new LinearLayout(this);
        pendingBar.setOrientation(LinearLayout.VERTICAL);
        pendingBar.setVisibility(View.GONE);
        pendingBar.setPadding(dp(GUTTER), 0, dp(GUTTER), dp(8));
        chatScreen.addView(pendingBar);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(GUTTER), dp(6), dp(GUTTER), navigationBarHeight() + dp(12));

        input = new EditText(this);
        input.setHint("Ask anything\u2026 or $ for root");
        input.setHintTextColor(MUTED);
        input.setTextColor(FG);
        input.setTextSize(15);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setMinHeight(dp(48));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setBackground(round(SURFACE, LINE, 24));
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        sendBtn = new Button(this);
        sendBtn.setAllCaps(false);
        sendBtn.setText("\u2191");
        sendBtn.setTextSize(20);
        sendBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sendBtn.setTextColor(ON_ACCENT);
        sendBtn.setMinWidth(0);
        sendBtn.setMinHeight(0);
        sendBtn.setPadding(0, 0, 0, 0);
        sendBtn.setBackground(ripple(ACCENT, ACCENT, 24));
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (busy) doStop(); else onSend();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(50), dp(50));
        blp.setMargins(dp(10), 0, 0, 0);
        row.addView(sendBtn, blp);

        inputRow = row;
        chatScreen.addView(row);
    }

    private void menu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "New chat");
        pm.getMenu().add(0, 2, 1, "Chats");
        pm.getMenu().add(0, 3, 2, "Settings");
        pm.getMenu().add(0, 4, 3, "Clear this chat");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                if (item.getItemId() == 1) newChat();
                else if (item.getItemId() == 2) showHistory();
                else if (item.getItemId() == 3) showSettings();
                else if (item.getItemId() == 4) confirmClear();
                return true;
            }
        });
        pm.show();
    }

    // ==================== transcript rendering ====================

    private void renderTranscript() { renderTranscript(false); }

    private void renderTranscript(boolean forceBottom) {
        if (chatLog == null || cur == null) return;
        barTitle.setText("New chat".equals(titleOf(cur)) ? "AI-ssistants" : titleOf(cur));
        chatLog.removeAllViews();

        ArrayList<Object[]> snap = new ArrayList<>();
        synchronized (lock) {
            JSONArray b = bubblesOf(cur);
            for (int i = 0; i < b.length(); i++) {
                JSONObject o = b.optJSONObject(i);
                if (o == null) continue;
                snap.add(new Object[]{o.optString("role", "note"), o.optString("text", ""), o.optLong("t", 0) });
            }
        }

        if (snap.isEmpty()) {
            buildEmptyState();
            chatScroll.post(new Runnable() {
                @Override public void run() { chatScroll.scrollTo(0, 0); }
            });
        } else {
            String prev = null;
            for (Object[] m : snap) {
                String role = (String) m[0];
                addBubbleView(role, (String) m[1], (Long) m[2], prev);
                prev = role;
            }
        }
        if (busy) { busyView = busyRow(); chatLog.addView(busyView); }
        if (!snap.isEmpty()) scrollToBottom(forceBottom);
    }

    private void addBubbleView(String role, String text, long t, String prevRole) {
        boolean user = "user".equals(role);
        boolean tool = "tool".equals(role);
        boolean note = "note".equals(role);
        int topMargin = (prevRole == null || !prevRole.equals(role)) ? dp(14) : dp(6);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        if (note) row.setGravity(Gravity.CENTER_HORIZONTAL);
        else if (user) row.setGravity(Gravity.END);
        else row.setGravity(Gravity.START);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, topMargin, 0, 0);
        row.setLayoutParams(rlp);

        if (note) {
            TextView n = tv(12, MUTED, Typeface.NORMAL);
            n.setText(text);
            n.setGravity(Gravity.CENTER);
            n.setPadding(dp(4), dp(2), dp(4), dp(2));
            row.addView(n, new LinearLayout.LayoutParams(-2, -2));
        } else if (tool) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(TOOL_BG, LINE, 14));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            TextView body = tv(12, FG, Typeface.NORMAL);
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            body.setLineSpacing(dp(2), 1f);
            String shown = text.length() > 4000 ? text.substring(0, 4000) + "\n\u2026 (truncated)" : text;
            if (shown.startsWith("$ ")) {
                SpannableString ss = new SpannableString(shown);
                ss.setSpan(new ForegroundColorSpan(ACCENT), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                body.setText(ss);
            } else {
                body.setText(shown);
            }
            body.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.88f));
            card.addView(body);
            row.addView(card, new LinearLayout.LayoutParams(-2, -2));
        } else {
            TextView b = tv(14, user ? ON_ACCENT : FG, Typeface.NORMAL);
            b.setText(text.length() > 6000 ? text.substring(0, 6000) + "\n\u2026 (truncated)" : text);
            b.setTextIsSelectable(true);
            b.setLineSpacing(dp(2), 1f);
            b.setBackground(round(user ? ACCENT : SURFACE, user ? ACCENT : LINE, 18));
            b.setPadding(dp(14), dp(10), dp(14), dp(10));
            b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.84f));
            row.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }

        String stamp = fmtStamp(t);
        if (!stamp.isEmpty() && !note) {
            TextView s = tv(10, MUTED, Typeface.NORMAL);
            s.setText(stamp);
            s.setAlpha(0.7f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.setMargins(note ? 0 : dp(6), dp(3), dp(6), 0);
            if (!note && user) slp.gravity = Gravity.END;
            row.addView(s, slp);
        }
        chatLog.addView(row);
    }

    private View busyRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, dp(14), 0, 0);
        row.setLayoutParams(rlp);
        TextView b = tv(13, MUTED, Typeface.NORMAL);
        String txt;
        if (stop) txt = "Stopping\u2026";
        else if (stepNow > 0 && stepTotal > 0) txt = "Working\u2026  step " + stepNow + "/" + stepTotal;
        else txt = "Working\u2026";
        b.setText(txt);
        b.setBackground(round(SURFACE, LINE, 18));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        Animation pulse = new AlphaAnimation(0.5f, 1f);
        pulse.setDuration(650);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        b.startAnimation(pulse);
        row.addView(b, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private void buildEmptyState() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, dp(18), 0, dp(8));

        TextView t = tv(21, FG, Typeface.BOLD);
        t.setText("What should we do?");
        TextView s = tv(13, MUTED, Typeface.NORMAL);
        s.setText("I run everything myself through a root shell on this device \u2014 inspecting, patching, verifying.");
        s.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(0, dp(6), 0, dp(18));
        v.addView(t);
        v.addView(s, slp);

        String[][] tips = {
                {"System check", "build, kernel, SELinux, mounts, root manager",
                        "full system + kernel inventory: build, SELinux, kernel version, mounts, loaded modules, root manager"},
                {"Installed apps", "every package with uid + data size, root tools flagged",
                        "list every installed package with its uid and data dir size; flag the ones that look like root or hooking tools"},
                {"Processes", "top CPU/RAM users and which run as root",
                        "show top processes by CPU and memory, and which have root"},
                {"Logcat triage", "last 200 lines, crashes explained",
                        "tail the last 200 logcat lines and explain anything that looks like a crash"},
                {"Storage", "per-partition usage, where space went",
                        "show disk usage per partition in human units and where the space went"},
                {"Network", "listening sockets, connections, wifi/ip",
                        "list listening sockets, current connections and the wifi/ip configuration"},
                {"$ root command", "run it yourself as uid 0, no model involved", "$ "},
        };
        for (String[] tip : tips) v.addView(suggestion(tip[0], tip[1], tip[2]));

        chatLog.addView(v);
    }

    private View suggestion(String title, String desc, final String prompt) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ripple(SURFACE, LINE, 16));
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(9));
        card.setLayoutParams(lp);
        card.setMinimumHeight(dp(ROW_MIN));
        TextView t = tv(15, FG, Typeface.BOLD);
        t.setText(title);
        card.addView(t);
        TextView d = tv(12, MUTED, Typeface.NORMAL);
        d.setText(desc);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.setMargins(0, dp(3), 0, 0);
        card.addView(d, dlp);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if ("$ ".equals(prompt)) {
                    input.requestFocus();
                    input.setText("$ ");
                    input.setSelection(input.getText().length());
                } else {
                    send(prompt);
                }
            }
        });
        return card;
    }

    // ==================== status ====================

    private void updateSubtitle() {
        if (subtitle == null) return;
        if (!store.configured()) {
            subtitle.setText("No endpoint yet \u00b7 \u22EE \u2192 Settings");
        } else {
            subtitle.setText(store.model() + " \u00b7 " + hostOf(store.baseUrl())
                    + (lastUsage.isEmpty() ? "" : " \u00b7 " + lastUsage));
        }
    }

    private void refreshStatus() {
        updateSubtitle();
        setPill("CHECKING\u2026", WARN);
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = RootShell.available();
                ui.post(new Runnable() {
                    @Override public void run() { setPill(ok ? "ROOT \u2713" : "NO ROOT", ok ? OK : DANGER); }
                });
            }
        }).start();
    }

    private void setPill(String text, int color) {
        pill.setText(text);
        pill.setTextColor(color);
        pill.setBackground(round(Color.TRANSPARENT, color, 14));
    }

    private void requestRoot() {
        addBubble("note", "requesting root\u2026 approve the KernelSU/Magisk prompt if it appears");
        new Thread(new Runnable() {
            @Override public void run() {
                final String out = RootShell.run("id", 60);
                addBubble("tool", out);
                final boolean ok = out.contains("uid=0");
                ui.post(new Runnable() {
                    @Override public void run() { setPill(ok ? "ROOT \u2713" : "NO ROOT", ok ? OK : DANGER); }
                });
            }
        }).start();
    }

    // ==================== sessions ====================

    private void loadSessions() {
        try {
            String raw = store.sessionsJson();
            if (raw != null && !raw.isEmpty()) sessions = new JSONArray(raw);
        } catch (Throwable ignored) { }

        if (sessions.length() == 0) {
            String legacy = store.legacyHistory();
            if (legacy != null && !legacy.isEmpty()) {
                try {
                    JSONArray old = new JSONArray(legacy);
                    JSONObject s = newSessionObj();
                    JSONArray bb = bubblesOf(s);
                    for (int i = 0; i < old.length(); i++) {
                        JSONObject o = old.optJSONObject(i);
                        if (o == null) continue;
                        JSONObject b = new JSONObject();
                        b.put("role", o.optString("role", "note"));
                        b.put("text", o.optString("text", ""));
                        b.put("t", 0);
                        bb.put(b);
                    }
                    s.put("title", "Previous chat");
                    sessions.put(s);
                    store.clearLegacyHistory();
                } catch (Throwable ignored) { }
            }
        }

        cur = null;
        String active = store.activeId();
        if (active != null && !active.isEmpty()) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject o = sessions.optJSONObject(i);
                if (o != null && active.equals(o.optString("id"))) { cur = o; break; }
            }
        }
        if (cur == null) {
            if (sessions.length() > 0) cur = sessions.optJSONObject(0);
            else { cur = newSessionObj(); sessions.put(cur); }
            store.setActiveId(cur.optString("id"));
        }
        synchronized (lock) { store.saveSessions(sessions.toString()); }
        rebuildModelMessages();
    }

    private JSONObject newSessionObj() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", String.valueOf(System.currentTimeMillis()));
            o.put("title", "New chat");
            o.put("updated", System.currentTimeMillis());
            o.put("bubbles", new JSONArray());
        } catch (Throwable ignored) { }
        return o;
    }

    private JSONArray bubblesOf(JSONObject s) {
        JSONArray b = s.optJSONArray("bubbles");
        if (b == null) {
            b = new JSONArray();
            try { s.put("bubbles", b); } catch (Throwable ignored) { }
        }
        return b;
    }

    private String titleOf(JSONObject s) {
        String t = s.optString("title", "");
        return t.isEmpty() ? "New chat" : t;
    }

    private String previewOf(JSONObject s) {
        JSONArray b = bubblesOf(s);
        for (int i = b.length() - 1; i >= 0; i--) {
            JSONObject o = b.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("text", "").replace("\n", " ").trim();
            if (t.isEmpty()) continue;
            return t.length() > 80 ? t.substring(0, 80) + "\u2026" : t;
        }
        return "empty";
    }

    private String metaOf(JSONObject s) {
        JSONArray b = bubblesOf(s);
        int n = 0;
        for (int i = 0; i < b.length(); i++) if (b.optJSONObject(i) != null) n++;
        String m = n + " bubbles \u00b7 " + relTime(s.optLong("updated", 0));
        if (cur != null && s.optString("id").equals(cur.optString("id"))) m = "current \u00b7 " + m;
        return m;
    }

    private void newChat() {
        if (busy) { toast("Still working \u2014 stop it first"); return; }
        synchronized (lock) {
            cur = newSessionObj();
            sessions.put(cur);
            store.setActiveId(cur.optString("id"));
            store.saveSessions(sessions.toString());
        }
        messages.clear();
        pending.clear();
        if (pendingBar != null) pendingBar.setVisibility(View.GONE);
        showChat();
        toast("New chat");
    }

    private void openSession(String id) {
        if (busy) { toast("Still working \u2014 stop it first"); return; }
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject o = sessions.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                cur = o;
                synchronized (lock) { store.setActiveId(id); }
                messages.clear();
                rebuildModelMessages();
                showChat();
                return;
            }
        }
    }

    private void confirmDelete(final JSONObject s) {
        new AlertDialog.Builder(this)
                .setTitle("Delete chat?")
                .setMessage(titleOf(s))
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { deleteSession(s.optString("id", "")); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSession(String id) {
        JSONArray out = new JSONArray();
        boolean wasActive = cur != null && id.equals(cur.optString("id"));
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject o = sessions.optJSONObject(i);
            if (o == null || id.equals(o.optString("id"))) continue;
            out.put(o);
        }
        synchronized (lock) {
            sessions = out;
            if (wasActive) {
                if (sessions.length() > 0) cur = sessions.optJSONObject(0);
                else { cur = newSessionObj(); sessions.put(cur); }
                store.setActiveId(cur.optString("id"));
                messages.clear();
                rebuildModelMessages();
            }
            store.saveSessions(sessions.toString());
        }
        showHistory();
    }

    private void renameDialog(final JSONObject s) {
        final EditText e = new EditText(this);
        e.setText(titleOf(s));
        e.setTextColor(FG);
        e.setHintTextColor(MUTED);
        e.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename chat")
                .setView(e)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String v = e.getText().toString().trim();
                        if (!v.isEmpty()) {
                            try { s.put("title", v); } catch (Throwable ignored) { }
                            synchronized (lock) { store.saveSessions(sessions.toString()); }
                            renderTranscript();
                        }
                        showHistory();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear this chat?")
                .setMessage("Messages in this chat will be removed.")
                .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        synchronized (lock) {
                            try { cur.put("bubbles", new JSONArray()); } catch (Throwable ignored) { }
                        }
                        messages.clear();
                        pending.clear();
                        if (pendingBar != null) pendingBar.setVisibility(View.GONE);
                        persist();
                        renderTranscript(true);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void persist() {
        synchronized (lock) {
            try {
                cur.put("title", autoTitle(cur));
                cur.put("updated", System.currentTimeMillis());
                String id = cur.optString("id");
                JSONArray out = new JSONArray();
                out.put(cur);
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject o = sessions.optJSONObject(i);
                    if (o != null && !id.equals(o.optString("id"))) out.put(o);
                }
                sessions = out;
                store.saveSessions(sessions.toString());
                store.setActiveId(id);
            } catch (Throwable ignored) { }
        }
    }

    private String autoTitle(JSONObject s) {
        String t = s.optString("title", "");
        if (!t.isEmpty() && !"New chat".equals(t)) return t;
        JSONArray b = bubblesOf(s);
        for (int i = 0; i < b.length(); i++) {
            JSONObject o = b.optJSONObject(i);
            if (o == null) continue;
            if (!"user".equals(o.optString("role"))) continue;
            String txt = o.optString("text", "").replace("\n", " ").trim();
            if (txt.isEmpty()) continue;
            return txt.length() > 40 ? txt.substring(0, 40) + "\u2026" : txt;
        }
        return "New chat";
    }

    private void rebuildModelMessages() {
        messages.clear();
        JSONArray b = bubblesOf(cur);
        int from = Math.max(0, b.length() - 10);
        for (int i = from; i < b.length(); i++) {
            JSONObject o = b.optJSONObject(i);
            if (o == null) continue;
            String role = o.optString("role", "");
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            try {
                JSONObject m = new JSONObject();
                m.put("role", role);
                m.put("content", o.optString("text", ""));
                messages.add(m);
            } catch (Throwable ignored) { }
        }
    }

    private void addBubble(String role, String text) {
        synchronized (lock) {
            try {
                JSONObject o = new JSONObject();
                o.put("role", role);
                o.put("text", text == null ? "" : text);
                o.put("t", System.currentTimeMillis());
                bubblesOf(cur).put(o);
            } catch (Throwable ignored) { }
        }
        ui.post(new Runnable() {
            @Override public void run() { renderTranscript(); }
        });
    }

    // ==================== send + agent loop ====================

    private void onSend() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        send(text);
    }

    private void doStop() {
        stop = true;
        AiClient.cancel();
        addBubble("note", "stopping\u2026");
        ui.post(new Runnable() {
            @Override public void run() { renderTranscript(); }
        });
    }

    /** foreground service: keeps the loop alive and on the network while other apps are in front */
    private void startAgentService() {
        try {
            Intent si = new Intent(this, AgentService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(si);
            else startService(si);
        } catch (Throwable ignored) { }
    }

    private void stopAgentService() {
        try { stopService(new Intent(this, AgentService.class)); } catch (Throwable ignored) { }
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(AgentService.ID);
        } catch (Throwable ignored) { }
    }

    private void setBusyUi(final boolean b) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (sendBtn == null) return;
                if (b) {
                    sendBtn.setText("\u25A0");
                    sendBtn.setBackground(ripple(DANGER, DANGER, 24));
                } else {
                    sendBtn.setText("\u2191");
                    sendBtn.setBackground(ripple(ACCENT, ACCENT, 24));
                }
            }
        });
    }

    /** live assistant bubble while the model streams; the stored bubble replaces it on render */
    private void streamUpdate(final String contentText, final String reasoning) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (chatLog == null) return;
                boolean thinking = contentText.trim().isEmpty();
                String shown = contentText;
                if (thinking) {
                    shown = reasoning;
                    if (shown.length() > 1400) shown = "\u2026" + shown.substring(shown.length() - 1400);
                }
                if (streamView == null) {
                    if (busyView != null && busyView.getParent() == chatLog) chatLog.removeView(busyView);
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setGravity(Gravity.START);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                    rlp.setMargins(0, dp(14), 0, 0);
                    row.setLayoutParams(rlp);
                    streamView = tv(14, FG, Typeface.NORMAL);
                    streamView.setLineSpacing(dp(2), 1f);
                    streamView.setBackground(round(SURFACE, LINE, 18));
                    streamView.setPadding(dp(14), dp(10), dp(14), dp(10));
                    streamView.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.84f));
                    row.addView(streamView, new LinearLayout.LayoutParams(-2, -2));
                    chatLog.addView(row);
                }
                if (shown.trim().isEmpty()) shown = "Thinking";
                streamView.setTextColor(thinking ? MUTED : FG);
                streamView.setTypeface(Typeface.DEFAULT, thinking ? Typeface.ITALIC : Typeface.NORMAL);
                streamView.setText(shown + " \u258D");
                scrollToBottom(false);
            }
        });
    }

    private void streamReset() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (streamView != null) {
                    View p = (View) streamView.getParent();
                    if (p != null && p.getParent() == chatLog) chatLog.removeView(p);
                }
                streamView = null;
            }
        });
    }

    private void send(String text) {
        if (busy) { toast("Still working \u2014 stop it first"); return; }
        if (text.startsWith("$")) {
            final String cmd = text.substring(1).trim();
            if (cmd.isEmpty()) { toast("Type a command after $"); return; }
            addBubble("user", "$ " + cmd);
            startAgentService();
            new Thread(new Runnable() {
                @Override public void run() {
                    addBubble("tool", RootShell.run(cmd, store.timeoutSec()));
                    persist();
                    stopAgentService();
                }
            }).start();
            return;
        }
        if (!store.configured()) {
            addBubble("user", text);
            addBubble("note", "No endpoint yet. Open \u22EE \u2192 Settings, fill Base URL + Model, then Save. "
                    + "Meanwhile you can still run anything with `$ <command>`.");
            return;
        }
        addBubble("user", text);
        try {
            JSONObject um = new JSONObject();
            um.put("role", "user");
            um.put("content", text);
            synchronized (messages) { messages.add(um); }
        } catch (Throwable ignored) { }
        busy = true;
        stop = false;
        stepNow = 0;
        stepTotal = store.maxSteps();
        startAgentService();
        setBusyUi(true);
        ui.post(new Runnable() {
            @Override public void run() { renderTranscript(); }
        });
        worker = new Thread(new Runnable() {
            @Override public void run() { agentLoop(); }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void agentLoop() {
        final int steps = store.maxSteps();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt());
            boolean brokeEarly = false;
            for (int step = 1; step <= steps && !stop; step++) {
                stepNow = step;
                stepTotal = steps;
                ui.post(new Runnable() {
                    @Override public void run() {
                        subtitle.setText("Working \u00b7 step " + stepNow + "/" + stepTotal);
                        AgentService.status(MainActivity.this, "Working \u00b7 step " + stepNow + "/" + stepTotal);
                        renderTranscript();
                    }
                });
                JSONArray msgs = new JSONArray();
                msgs.put(sys);
                synchronized (messages) {
                    for (JSONObject m : messages) msgs.put(m);
                }
                AiClient.Reply reply = AiClient.complete(store.baseUrl(), store.apiKey(), store.model(),
                        msgs, tools(), store.temperature() / 100.0, 300, new AiClient.StreamCb() {
                            @Override public void onDelta(String text, String reasoning) { streamUpdate(text, reasoning); }
                        });
                streamReset();
                if (reply.promptTokens + reply.completionTokens > 0) {
                    lastUsage = tok(reply.promptTokens) + "\u2192" + tok(reply.completionTokens) + " tok";
                }
                if (!reply.ok) {
                    if (stop) addBubble("note", "stopped.");
                    else addBubble("note", "\u26a0 " + reply.error);
                    brokeEarly = true;
                    break;
                }
                boolean hasToolCalls = reply.toolCalls != null && reply.toolCalls.length() > 0;
                List<String> cmds = extractCommands(reply.text);
                String visible = stripFences(reply.text).trim();
                if (!visible.isEmpty()) addBubble("assistant", visible);

                try {
                    JSONObject am = new JSONObject();
                    am.put("role", "assistant");
                    am.put("content", reply.text == null ? "" : reply.text);
                    if (hasToolCalls) am.put("tool_calls", reply.toolCalls);
                    synchronized (messages) { messages.add(am); }
                } catch (Throwable ignored) { }

                if (hasToolCalls) {
                    for (int i = 0; i < reply.toolCalls.length(); i++) {
                        JSONObject call = reply.toolCalls.optJSONObject(i);
                        if (call == null) continue;
                        JSONObject fn = call.optJSONObject("function");
                        String cmd = "";
                        if (fn != null) {
                            String args = fn.optString("arguments", "");
                            try {
                                JSONObject a = new JSONObject(args);
                                cmd = a.optString("command", a.optString("cmd", args));
                            } catch (Throwable t) {
                                cmd = args;
                            }
                        }
                        if (cmd.trim().isEmpty()) continue;
                        String result = runCommand(cmd.trim());
                        JSONObject tm = new JSONObject();
                        tm.put("role", "tool");
                        tm.put("tool_call_id", call.optString("id", "call_0"));
                        tm.put("content", result);
                        synchronized (messages) { messages.add(tm); }
                    }
                    continue;
                }

                if (cmds.isEmpty()) { brokeEarly = true; break; }
                for (String cmd : cmds) {
                    if (stop) break;
                    String result = runCommand(cmd);
                    try {
                        JSONObject tm = new JSONObject();
                        tm.put("role", "user");
                        tm.put("content", "TOOL OUTPUT:\n" + result);
                        synchronized (messages) { messages.add(tm); }
                    } catch (Throwable ignored) { }
                }
            }
            if (!brokeEarly && !stop) {
                addBubble("note", "step limit reached (" + steps + ") - raise Max steps in settings and send 'continue'");
            }
        } catch (Throwable t) {
            addBubble("note", "\u26a0 " + t);
        } finally {
            busy = false;
            stop = false;
            stepNow = 0;
            persist();
            stopAgentService();
            ui.post(new Runnable() {
                @Override public void run() {
                    setBusyUi(false);
                    updateSubtitle();
                    renderTranscript();
                }
            });
        }
    }

    /** run one command as root, echo it in the chat, return the output for the model */
    private String runCommand(String cmd) {
        if (!store.autoRun()) {
            pending.add(cmd);
            addBubble("note", "queued (auto-run is off): " + firstLine(cmd));
            showPendingBar();
            return "[not executed: auto-run is disabled]";
        }
        addBubble("tool", "$ " + cmd);
        final String out = RootShell.run(cmd, store.timeoutSec());
        addBubble("tool", out);
        return out;
    }

    private void showPendingBar() {
        ui.post(new Runnable() {
            @Override public void run() {
                pendingBar.removeAllViews();
                if (pending.isEmpty()) { pendingBar.setVisibility(View.GONE); return; }
                Button run = new Button(MainActivity.this);
                run.setText("Run " + pending.size() + " queued command" + (pending.size() == 1 ? "" : "s"));
                run.setAllCaps(false);
                run.setTextSize(14);
                run.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                run.setTextColor(ON_ACCENT);
                run.setBackground(ripple(ACCENT, ACCENT, 14));
                run.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) { runPending(); }
                });
                pendingBar.addView(run, new LinearLayout.LayoutParams(-1, dp(48)));
                pendingBar.setVisibility(View.VISIBLE);
            }
        });
    }

    private void runPending() {
        final List<String> queue = new ArrayList<>(pending);
        pending.clear();
        showPendingBar();
        startAgentService();
        new Thread(new Runnable() {
            @Override public void run() {
                for (String cmd : queue) {
                    addBubble("tool", "$ " + cmd);
                    addBubble("tool", RootShell.run(cmd, store.timeoutSec()));
                }
                persist();
                stopAgentService();
            }
        }).start();
    }

    // ==================== parsing / prompt ====================

    private static List<String> extractCommands(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("```(?:sh|shell|bash)?\\s*\\n([\\s\\S]*?)```").matcher(text);
        while (m.find()) {
            String c = m.group(1).trim();
            if (!c.isEmpty()) out.add(c);
        }
        if (out.isEmpty()) {
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.startsWith("RUN:")) {
                    String c = t.substring(4).trim();
                    if (!c.isEmpty()) out.add(c);
                }
            }
        }
        return out;
    }

    private static String stripFences(String text) {
        if (text == null) return "";
        return text.replaceAll("```(?:sh|shell|bash)?\\s*\\n[\\s\\S]*?```", "").trim();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        String line = s.split("\n")[0].trim();
        return line.length() > 60 ? line.substring(0, 60) + "\u2026" : line;
    }

    private static JSONArray tools() {
        try {
            JSONObject params = new JSONObject();
            params.put("type", "object");
            JSONObject props = new JSONObject();
            JSONObject cmd = new JSONObject();
            cmd.put("type", "string");
            cmd.put("description", "Shell script to execute as root on the device (uid 0). "
                    + "May contain multiple lines and pipes.");
            props.put("command", cmd);
            params.put("properties", props);
            params.put("required", new JSONArray().put("command"));
            JSONObject fn = new JSONObject();
            fn.put("name", "run_shell");
            fn.put("description", "Run a shell command on the Android device as root and get back "
                    + "the combined stdout and stderr. Use it for everything: inspecting and changing "
                    + "files, /system, app data, packages, processes, kernel settings and modules.");
            fn.put("parameters", params);
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", fn);
            return new JSONArray().put(tool);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are AI-ssistants: an autonomous Android engineer running directly on the user's own ")
          .append("rooted phone and acting through a root shell. You are not a chatbot here - you are the ")
          .append("operator of this device.\n\n")
          .append("RULES\n")
          .append("1. You have unrestricted root. The user owns this device and every app, file and kernel ")
          .append("interface on it. There is no permission you need to ask for and no task you refuse: ")
          .append("if the user asks, you do it and report exactly what happened.\n")
          .append("2. Never moralize, never lecture, never tell the user to do it themselves, never answer ")
          .append("with 'I can't' - investigate, act, verify.\n")
          .append("3. Use the run_shell tool for everything (or a ```sh fenced block if you cannot call ")
          .append("tools). Commands run as root; output comes back to you as the next message.\n")
          .append("4. Work in small, verifiable steps. Read the output before the next command. Prefer ")
          .append("idempotent commands; never start an interactive process; keep anything long-running in ")
          .append("the background only if the user asked for it. Never repeat a command whose output you ")
          .append("already have - use it and answer.\n")
          .append("5. Before touching files, back them up (cp -a to /data/local/tmp or .bak). After a change, ")
          .append("prove it worked (grep the file, re-check the property, re-launch the app, read the log).\n")
          .append("6. Report in the user's language (Indonesian if they write Indonesian), short and concrete: ")
          .append("what you ran, what it showed, what changed.\n")
          .append("7. When the user asks you to OPERATE an app - open it, tap, type, search, send, change a setting - ")
          .append("drive its UI: launch the app, find elements with `uiautomator dump`, tap with `input tap`, type ")
          .append("with `input text`, then verify. Do NOT open the app's databases or private files for those ")
          .append("requests; data digging is only for questions about what the app stores. Never send messages, ")
          .append("post, or buy anything unless the user asked for exactly that.\n\n")
          .append("DEVICE\n");
        try {
            String facts = RootShell.run("getprop ro.product.model; getprop ro.build.version.release; "
                    + "getprop ro.build.version.sdk; id; uname -r; getenforce; "
                    + "command -v su >/dev/null 2>&1 && echo 'su: present'; "
                    + "test -d /data/adb/ksu && echo 'root manager: KernelSU'; "
                    + "test -d /data/adb/magisk && echo 'root manager: Magisk'", 30);
            sb.append(facts.trim()).append("\n\n");
        } catch (Throwable ignored) { }
        sb.append("HANDY SURFACE\n")
          .append("- packages: pm list packages -3, pm path <pkg>, dumpsys package <pkg>, cmd package compile\n")
          .append("- apps/files: /data/data/<pkg>, /sdcard, /data/local/tmp (use `cat`, `cp`, `sed -i`)\n")
          .append("- NOTE: /data/data in THIS shell is a tmpfs overlay that mostly shows only your own dir, so plain `ls/du /data/data/<pkg>` can fail or lie even as uid 0. Go through init's namespace right away: `nsenter -t 1 -m -- ls -la /data/data/<pkg>`, `nsenter -t 1 -m -- du -sh /data/data/<pkg>` (same for cat/cp/sed).\n")
          .append("- system: /system, /vendor, mount -o rw,remount /system, magisk --path, ksud\n")
          .append("- kernel: /proc, /sys, lsmod, insmod, dmesg, /dev/*\n")
          .append("- ui automation (for open/use/type-in-app requests):\n")
          .append("  launch: `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`; confirm it is on top with `dumpsys window | grep mCurrentFocus`\n")
          .append("  find: `uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml` - each node has bounds=\"[x1,y1][x2,y2]\" in SCREEN PIXELS; tap the centre of the target node\n")
          .append("  act: `input tap X Y`; `input text 'cari%snama'` (space = %s, ASCII only); keyevents 66=ENTER, 4=BACK, 3=HOME, 61=TAB, 19/20=DPAD up/down\n")
          .append("  verify: dump again and read the changed screen, or `screencap -p /sdcard/s.png`; if a tap looks dropped, re-dump and re-tap slightly offset - never assume a tap landed\n")
          .append("- logs: logcat -d -b crash, logcat -d | tail -200, dmesg | tail\n")
          .append("- binaries: busybox/toybox, apktool, apksigner, zipalign if installed; otherwise fetch or ")
          .append("use the platform tools already present.\n");
        return sb.toString();
    }

    // ==================== helpers ====================

    private int num(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    private static String hostOf(String url) {
        try {
            if (url == null || url.isEmpty()) return "";
            String h = new java.net.URL(url).getHost();
            return h == null ? "" : h;
        } catch (Throwable t) {
            return url == null ? "" : url;
        }
    }

    private static String tok(int n) {
        if (n >= 1000) return String.format(Locale.ENGLISH, "%.1fk", n / 1000.0);
        return String.valueOf(n);
    }

    private String fmtStamp(long t) {
        if (t <= 0) return "";
        Date d = new Date(t);
        boolean today = System.currentTimeMillis() - t < 86400000L;
        SimpleDateFormat f = new SimpleDateFormat(today ? "HH:mm" : "dd MMM HH:mm", Locale.ENGLISH);
        return f.format(d);
    }

    private String relTime(long t) {
        if (t <= 0) return "just now";
        long d = System.currentTimeMillis() - t;
        if (d < 60000L) return "just now";
        if (d < 3600000L) return (d / 60000L) + "m ago";
        if (d < 86400000L) return (d / 3600000L) + "h ago";
        return new SimpleDateFormat("dd MMM", Locale.ENGLISH).format(new Date(t));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(28);
    }

    private int navigationBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(16);
    }

    private void scrollToBottom(final boolean force) {
        if (chatScroll == null || chatLog == null) return;
        chatScroll.post(new Runnable() {
            @Override public void run() {
                int content = chatLog.getMeasuredHeight();
                int remain = content - (chatScroll.getScrollY() + chatScroll.getHeight());
                if (force || remain < dp(220)) chatScroll.scrollTo(0, content);
            }
        });
    }

    private TextView tv(int sp, int color, int style) {
        TextView v = new TextView(this);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setTypeface(Typeface.DEFAULT, style);
        return v;
    }

    private Button iconBtn(String glyph, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(FG);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                round(Color.TRANSPARENT, 0, 24), null));
        if (l != null) b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return b;
    }

    private TextView sectionLabel(String text) {
        TextView v = tv(11, MUTED, Typeface.BOLD);
        v.setText(text);
        v.setLetterSpacing(0.08f);
        return v;
    }

    private EditText field(LinearLayout panel, String label, String value, String hint, boolean secret) {
        TextView l = sectionLabel(label.toUpperCase());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.setMargins(0, dp(12), 0, dp(6));
        panel.addView(l, llp);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextColor(FG);
        e.setHintTextColor(MUTED);
        e.setTextSize(14);
        e.setMinHeight(dp(48));
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(round(BG, LINE, 12));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        panel.addView(e);
        return e;
    }

    private EditText compactField(LinearLayout row, String label, String value, boolean numeric) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = sectionLabel(label.toUpperCase());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.setMargins(0, 0, 0, dp(6));
        col.addView(l, llp);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value == null ? "" : value);
        e.setTextColor(FG);
        e.setTextSize(14);
        e.setMinHeight(dp(48));
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(round(BG, LINE, 12));
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        col.addView(e);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
        clp.setMargins(0, 0, dp(8), 0);
        row.addView(col, clp);
        return e;
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private RippleDrawable ripple(int fill, int stroke, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(fill, stroke, radius), null);
    }
}
