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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
    private TextView sendBtn;
    private LinearLayout inputRow;
    private LinearLayout attachBar;
    private int lastIme = -1;
    private TextView streamView;
    private View busyView;
    private String pendingPath = null;
    private String pendingName = null;
    private boolean pickerOpen = false;

    /** permission gate: 0 = once, 1 = this chat, 2 = always, 3 = denied */
    private static final int PERM_ONCE = 0, PERM_CHAT = 1, PERM_ALWAYS = 2, PERM_DENY = 3;
    /** risky-action categories that need a human OK before running (index-aligned) */
    private static final String[] CAT_KEYS = { "install", "destructive", "system", "egress", "messaging" };
    private static final String[] CAT_LABEL = {
            "meng-install paket / APK",
            "menghapus, memformat, atau menimpa data",
            "mengubah sistem/kernel - atau menjalankan kode dari internet sebagai root",
            "mengirim data keluar dari HP ini",
            "mengirim pesan / SMS atas nama kamu"
    };
    private static final java.util.regex.Pattern[] CAT_RE = {
            java.util.regex.Pattern.compile("(pkg|apt|apt-get|dpkg|pip|pip3|npm|yarn|pnpm|gem|go|apk|pm|magisk|cargo)\\s+(-{1,2}[\\w=-]+\\s+)*(install|add|i|-i)\\b"),
            java.util.regex.Pattern.compile("(rm\\s+-[a-zA-Z]*[rf]|\\bshred\\s|dd\\s+[^|;]*of=/dev/|\\bmkfs|MASTER_CLEAR|--wipe|truncate\\s+-s\\s+0)"),
            java.util.regex.Pattern.compile("(mount\\s+[^|;]*(remount|,rw)|\\binsmod\\b|\\brmmod\\b|magisk\\s+--(install|remove|uninstall)|\\bksud\\b|setenforce\\s+0|/data/adb/(modules|ksu)|wm\\s+(size|density)\\s+[0-9]|settings\\s+put|svc\\s+(data|wifi|bluetooth|power)|\\|\\s*(sh|bash)\\b|eval\\s+\\$\\()"),
            java.util.regex.Pattern.compile("(curl[^|;]*(--data|-d\\s|-F\\s|-T\\s|--upload-file|-X\\s*(POST|PUT|PATCH))|wget[^|;]*--post-data|\\bscp\\b|\\brsync\\b|\\bnc\\s+-)"),
            java.util.regex.Pattern.compile("(\\bsendto\\b|\\bsmsto\\b|service\\s+call\\s+isms|android\\.intent\\.action\\.SEND\\b)")
    };
    private final java.util.Set<String> allowInChat = new java.util.HashSet<>();
    private volatile java.util.concurrent.CountDownLatch permLatch;
    private volatile java.util.concurrent.atomic.AtomicInteger permResult;
    /** the model's last sentence, shown in the dialog so the user knows WHY it wants the command */
    private volatile String lastAssistantSaid = "";

    /** how many times each exact command ran during the current run (anti-repeat-loop guard) */
    private final java.util.Map<String, Integer> runCounts = new java.util.HashMap<>();

    /** tool runs the user expanded in the transcript: keys are "sessionId:firstBubbleIndex" */
    private final java.util.Set<String> expandedGroups = new java.util.HashSet<>();
    private static ArrayList<AppEntry> installedApps = null;

    /** cached app icons for the @mention picker (loaded off the UI thread, shared between threads) */
    private static final java.util.Map<String, android.graphics.drawable.Drawable> appIcons =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, android.graphics.drawable.Drawable>());

    private static final class AppEntry {
        final String label;
        final String pkg;
        final android.content.pm.ApplicationInfo info;
        AppEntry(String label, String pkg, android.content.pm.ApplicationInfo info) {
            this.label = label;
            this.pkg = pkg;
            this.info = info;
        }
    }
    private static final int REQ_ATTACH = 7;

    private JSONArray sessions = new JSONArray();
    private JSONObject cur;
    private final List<JSONObject> messages = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();

    private volatile boolean busy;
    private volatile boolean stop;
    private volatile String lastPrompt = "";
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
        migrateEndpoints();
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
        if (screen == 3) { showSettings(); return; }
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

        panel.addView(sectionLabel("ACTIVE MODEL"));
        LinearLayout mcard = new LinearLayout(this);
        mcard.setOrientation(LinearLayout.HORIZONTAL);
        mcard.setGravity(Gravity.CENTER_VERTICAL);
        mcard.setBackground(ripple(SURFACE, LINE, 14));
        mcard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout minfo = new LinearLayout(this);
        minfo.setOrientation(LinearLayout.VERTICAL);
        TextView m1 = tv(15, FG, Typeface.BOLD);
        m1.setText(activeLabel());
        m1.setSingleLine(true);
        TextView m2 = tv(12, MUTED, Typeface.NORMAL);
        JSONObject am = activeModelObj();
        JSONObject amp = providerOf(am);
        m2.setText(am == null ? "no model yet \u2014 tap to add one"
                : (am.optBoolean("enabled", true) ? "" : "(disabled)  ")
                  + (amp == null ? "no provider" : amp.optString("name", "") + "  \u00b7  " + hostOf(amp.optString("baseUrl", ""))));
        m2.setSingleLine(true);
        m2.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        minfo.addView(m1);
        minfo.addView(m2);
        mcard.addView(minfo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mchev = tv(16, MUTED, Typeface.NORMAL);
        mchev.setText("\u25B8");
        mcard.addView(mchev);
        mcard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { showModels(); }
        });
        LinearLayout.LayoutParams mclp = new LinearLayout.LayoutParams(-1, -2);
        mclp.setMargins(0, dp(6), 0, 0);
        panel.addView(mcard, mclp);

        Button manage = new Button(this);
        manage.setText("Manage models & providers");
        manage.setAllCaps(false);
        manage.setTextSize(15);
        manage.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        manage.setTextColor(FG);
        manage.setBackground(ripple(SURFACE, LINE, 14));
        manage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { showModels(); }
        });
        LinearLayout.LayoutParams mglp = new LinearLayout.LayoutParams(-1, dp(50));
        mglp.setMargins(0, dp(10), 0, 0);
        panel.addView(manage, mglp);

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

        LinearLayout.LayoutParams thlp = new LinearLayout.LayoutParams(-1, -2);
        thlp.setMargins(0, dp(16), 0, dp(6));
        panel.addView(sectionLabel("THINKING \u00b7 AUTO PICKS PER TASK"), thlp);
        final int[] thinking = { store.thinking() };
        final String[] tnames = { "Auto", "Off", "Low", "High" };
        final Button[] tbtns = new Button[4];
        LinearLayout trow = new LinearLayout(this);
        trow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final int fi = i;
            Button tb = new Button(this);
            tb.setText(tnames[i]);
            tb.setAllCaps(false);
            tb.setTextSize(13);
            tb.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            tb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    thinking[0] = fi;
                    for (int k = 0; k < 4; k++) styleThinking(tbtns[k], k == fi);
                }
            });
            tbtns[i] = tb;
            styleThinking(tb, i == thinking[0]);
            LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(0, dp(44), 1);
            tlp2.setMargins(0, 0, i < 3 ? dp(8) : 0, 0);
            trow.addView(tb, tlp2);
        }
        panel.addView(trow);

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
                store.save(num(steps.getText().toString(), 12),
                        num(temp.getText().toString(), 30), num(timeout.getText().toString(), 180),
                        auto.isChecked(), thinking[0]);
                refreshStatus();
                toast("Saved \u00b7 " + activeLabel());
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
        row.setClipToPadding(false);
        row.setPadding(dp(GUTTER), dp(6), dp(GUTTER), navigationBarHeight() + dp(12));

        TextView attach = new TextView(this);
        attach.setText("\u002B");
        attach.setTextSize(22);
        attach.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        attach.setTextColor(FG);
        attach.setGravity(Gravity.CENTER);
        attach.setIncludeFontPadding(false);
        attach.setPadding(0, 0, 0, 0);
        attach.setBackground(ripple(SURFACE, LINE, 24));
        attach.setContentDescription("Add attachment");
        attach.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { openAttachPicker(); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(48), dp(48));
        alp.gravity = Gravity.BOTTOM;
        alp.setMargins(0, 0, dp(8), 0);
        row.addView(attach, alp);

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
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (count == 1 && before == 0 && start < s.length() && s.charAt(start) == '@') {
                    final int at = start;
                    input.postDelayed(new Runnable() { @Override public void run() { openAppPicker(at); } }, 120);
                }
            }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        sendBtn = new TextView(this);
        sendBtn.setText("\u2191");
        sendBtn.setTextSize(22);
        sendBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sendBtn.setTextColor(ON_ACCENT);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setIncludeFontPadding(false);
        sendBtn.setPadding(0, 0, 0, 0);
        sendBtn.setBackground(circle(ACCENT));
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (busy) doStop(); else onSend();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(52), dp(52));
        blp.gravity = Gravity.BOTTOM;
        blp.setMargins(dp(10), 0, 0, 0);
        row.addView(sendBtn, blp);

        attachBar = new LinearLayout(this);
        attachBar.setOrientation(LinearLayout.VERTICAL);
        attachBar.setVisibility(View.GONE);
        attachBar.setPadding(dp(GUTTER), 0, dp(GUTTER), dp(8));
        chatScreen.addView(attachBar);

        inputRow = row;
        chatScreen.addView(row);
    }

    private void menu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "New chat");
        pm.getMenu().add(0, 2, 1, "Chats");
        pm.getMenu().add(0, 3, 2, "Settings");
        pm.getMenu().add(0, 4, 3, "Clear this chat");
        pm.getMenu().add(0, 5, 4, "Models & providers");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                if (item.getItemId() == 1) newChat();
                else if (item.getItemId() == 2) showHistory();
                else if (item.getItemId() == 3) showSettings();
                else if (item.getItemId() == 4) confirmClear();
                else if (item.getItemId() == 5) showModels();
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
            for (int i = 0; i < snap.size(); i++) {
                Object[] m = snap.get(i);
                String role = (String) m[0];
                if ("tool".equals(role)) {
                    int j = i;
                    while (j < snap.size() && "tool".equals((String) snap.get(j)[0])) j++;
                    final int start = i;
                    final int count = j - i;
                    final String key = cur.optString("id", "") + ":" + start;
                    boolean open = expandedGroups.contains(key);
                    addToolGroup(start, count, open, key);
                    if (open) {
                        for (int k = i; k < j; k++) {
                            addBubbleView("tool", (String) snap.get(k)[1], (Long) snap.get(k)[2], "tool");
                        }
                    }
                    i = j - 1;
                    prev = "tool";
                    continue;
                }
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

    /** one collapsed row standing in for a whole run of tool bubbles; tap to show/hide the detail */
    private void addToolGroup(final int start, final int count, final boolean open, final String key) {
        int cmds = 0;
        String last = "";
        synchronized (lock) {
            JSONArray b = bubblesOf(cur);
            for (int k = start; k < start + count && k < b.length(); k++) {
                JSONObject o = b.optJSONObject(k);
                if (o == null) continue;
                String tx = o.optString("text", "");
                if (tx.startsWith("$ ")) {
                    cmds++;
                    last = tx.length() > 80 ? tx.substring(0, 80) + "\u2026" : tx;
                }
            }
        }
        if (cmds == 0) cmds = count;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ripple(TOOL_BG, LINE, 12));
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView t = tv(12, MUTED, Typeface.NORMAL);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        t.setText("\u2699 " + cmds + (cmds == 1 ? " command" : " commands")
                + (open ? " \u00b7 hide" : (last.isEmpty() ? " \u00b7 show" : " \u00b7 " + last)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        card.addView(t, tlp);
        TextView chev = tv(12, MUTED, Typeface.NORMAL);
        chev.setText(open ? "\u25BE" : "\u25B8");
        card.addView(chev);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (open) expandedGroups.remove(key); else expandedGroups.add(key);
                renderTranscript();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(lp);
        chatLog.addView(card);
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
                {"Pull an APK", "copy it out + summary: activities, perms, libs, protection",
                        "tarik APK <paket> ke /data/local/tmp dan ringkas isinya: aktivitas utama, izin, native libs, dan tanda proteksi"},
                {"Reverse an app", "decompile dex + resources, explain the internals",
                        "decompile APK <paket> lalu jelaskan internalnya: entry point, endpoint network, dan bagian yang menarik"},
                {"Mod an APK", "patch, rebuild, sign, install -r",
                        "mod APK <paket>: <perubahan yang diinginkan>, build ulang, sign, install -r, lalu verifikasi hasilnya"},
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

    // ==================== providers & models (data) ====================

    private JSONArray providers() {
        try {
            String r = store.providersJson();
            return (r == null || r.isEmpty()) ? new JSONArray() : new JSONArray(r);
        } catch (Throwable t) { return new JSONArray(); }
    }

    private JSONArray models() {
        try {
            String r = store.modelsJson();
            return (r == null || r.isEmpty()) ? new JSONArray() : new JSONArray(r);
        } catch (Throwable t) { return new JSONArray(); }
    }

    private void saveProviders(JSONArray a) { store.saveProviders(a.toString()); }
    private void saveModels(JSONArray a) { store.saveModels(a.toString()); }

    private JSONObject findById(JSONArray arr, String id) {
        if (id == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    private JSONObject activeModelObj() { return findById(models(), store.activeModelId()); }

    private JSONObject providerOf(JSONObject model) {
        return model == null ? null : findById(providers(), model.optString("providerId"));
    }

    private boolean hasActiveModel() {
        JSONObject m = activeModelObj();
        return m != null && m.optBoolean("enabled", true) && providerOf(m) != null;
    }

    // ---- vision (image attachments) ----

    /** does the active model accept image content? default on; text-only models just error out */
    private boolean activeModelVision() {
        JSONObject m = activeModelObj();
        return m == null || m.optBoolean("vision", true);
    }

    private boolean isImageFile(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ENGLISH);
        return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp")
                || p.endsWith(".gif") || p.endsWith(".bmp") || p.endsWith(".heic") || p.endsWith(".heif");
    }

    /** downscale the attachment and hand it to the model as a data URL (OpenAI image_url part) */
    private String imageDataUrl(String path) {
        try {
            android.graphics.BitmapFactory.Options probe = new android.graphics.BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, probe);
            int max = 1024;
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= max || probe.outHeight / (sample * 2) >= max) sample *= 2;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(path, o);
            if (bm == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos);
            bm.recycle();
            byte[] data = bos.toByteArray();
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasImagePart(JSONArray msgs) {
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            if (m.opt("content") instanceof JSONArray) return true;
        }
        return false;
    }

    /** turn multimodal messages back into plain text (used when a model rejects images) */
    private static void stripImageParts(JSONArray msgs) {
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            Object c = m.opt("content");
            if (!(c instanceof JSONArray)) continue;
            JSONArray parts = (JSONArray) c;
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < parts.length(); k++) {
                JSONObject p = parts.optJSONObject(k);
                if (p != null && "text".equals(p.optString("type"))) sb.append(p.optString("text", ""));
            }
            try { m.put("content", sb.toString()); } catch (Throwable ignored) { }
        }
    }

    private String shortLabel(JSONObject m) {
        if (m == null) return "model";
        String lb = m.optString("label", "");
        return lb.isEmpty() ? m.optString("name", "model") : lb;
    }

    private String activeLabel() { return shortLabel(activeModelObj()); }
    private String activeModelName() { JSONObject m = activeModelObj(); return m == null ? "" : m.optString("name", ""); }
    private String activeBaseUrl() { JSONObject p = providerOf(activeModelObj()); return p == null ? "" : p.optString("baseUrl", ""); }
    private String activeApiKey() { JSONObject p = providerOf(activeModelObj()); return p == null ? "" : p.optString("apiKey", ""); }

    private JSONObject firstEnabledModel(String excludeId) {
        JSONArray ms = models();
        for (int i = 0; i < ms.length(); i++) {
            JSONObject o = ms.optJSONObject(i);
            if (o == null) continue;
            if (excludeId != null && excludeId.equals(o.optString("id"))) continue;
            if (o.optBoolean("enabled", true) && providerOf(o) != null) return o;
        }
        return null;
    }

    private void ensureActiveStillValid() {
        JSONObject a = activeModelObj();
        if (a != null && a.optBoolean("enabled", true) && providerOf(a) != null) { updateSubtitle(); return; }
        JSONObject next = firstEnabledModel(null);
        store.setActiveModelId(next == null ? "" : next.optString("id"));
        updateSubtitle();
    }

    /** one-time migration from the old single-endpoint config */
    private void migrateEndpoints() {
        try {
            if (providers().length() > 0 || store.legacyBaseUrl().isEmpty()) return;
            String pid = "p" + System.currentTimeMillis();
            String mid = "m" + (System.currentTimeMillis() + 1);
            JSONArray ps = new JSONArray();
            JSONObject p = new JSONObject();
            p.put("id", pid);
            p.put("name", "Default");
            p.put("baseUrl", store.legacyBaseUrl());
            p.put("apiKey", store.legacyApiKey());
            ps.put(p);
            JSONArray ms = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("id", mid);
            m.put("providerId", pid);
            m.put("name", store.legacyModel());
            m.put("label", store.legacyModel());
            m.put("enabled", true);
            ms.put(m);
            saveProviders(ps);
            saveModels(ms);
            store.setActiveModelId(mid);
            store.clearLegacyEndpoint();
        } catch (Throwable ignored) { }
    }

    // ==================== status ====================

    private void updateSubtitle() {
        if (subtitle == null) return;
        JSONObject m = activeModelObj();
        if (m == null) {
            subtitle.setText("No model configured \u00b7 \u22EE \u2192 Settings");
        } else {
            subtitle.setText(activeLabel() + " \u00b7 " + hostOf(activeBaseUrl())
                    + (m.optBoolean("enabled", true) ? "" : " \u00b7 disabled")
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
        allowInChat.clear();
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
                allowInChat.clear();
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
        if (stop) return;
        stop = true;
        AiClient.cancel();
        RootShell.cancel();
        java.util.concurrent.CountDownLatch l = permLatch;
        if (l != null) {
            java.util.concurrent.atomic.AtomicInteger r = permResult;
            if (r != null) r.set(PERM_DENY);
            l.countDown();
        }
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
                    sendBtn.setBackground(circle(DANGER));
                } else {
                    sendBtn.setText("\u2191");
                    sendBtn.setBackground(circle(ACCENT));
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
        AiClient.resetCancel();
        RootShell.resetCancel();
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
        if (!hasActiveModel()) {
            addBubble("user", text);
            addBubble("note", "No model selected. Open \u22EE \u2192 Models & providers, add a provider + model, "
                    + "then tap the model to make it active. Meanwhile you can still run anything with `$ <command>`.");
            return;
        }
        String attach = pendingPath;
        String dataUrl = null;
        if (attach != null) {
            if (isImageFile(attach) && activeModelVision()) dataUrl = imageDataUrl(attach);
            text = "[attached file on device: " + attach + "]\n\n" + text;
            pendingPath = null;
            pendingName = null;
            if (attachBar != null) attachBar.setVisibility(View.GONE);
        }
        addBubble("user", text);
        lastPrompt = text;
        try {
            JSONObject um = new JSONObject();
            um.put("role", "user");
            if (dataUrl != null) {
                JSONArray parts = new JSONArray();
                JSONObject pt = new JSONObject();
                pt.put("type", "text");
                pt.put("text", text);
                parts.put(pt);
                JSONObject pi = new JSONObject();
                JSONObject iu = new JSONObject();
                iu.put("url", dataUrl);
                pi.put("type", "image_url");
                pi.put("image_url", iu);
                parts.put(pi);
                um.put("content", parts);
            } else {
                um.put("content", text);
            }
            synchronized (messages) { messages.add(um); }
        } catch (Throwable ignored) { }
        busy = true;
        stop = false;
        runCounts.clear();
        lastAssistantSaid = "";
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
            final int thinkBase = store.thinking();
            boolean escalate = false;
            for (int step = 1; step <= steps && !stop; step++) {
                stepNow = step;
                stepTotal = steps;
                final int thinkNow = thinkBase == 3 ? (escalate ? 2 : autoThinking(lastPrompt)) : thinkBase;
                ui.post(new Runnable() {
                    @Override public void run() {
                        subtitle.setText("Working \u00b7 step " + stepNow + "/" + stepTotal
                                + (thinkBase == 3 ? " \u00b7 think:" + thinkNow : ""));
                        AgentService.status(MainActivity.this, "Working \u00b7 step " + stepNow + "/" + stepTotal
                                + (thinkBase == 3 ? " \u00b7 think:" + thinkNow : ""));
                        renderTranscript();
                    }
                });
                JSONArray msgs = new JSONArray();
                msgs.put(sys);
                synchronized (messages) {
                    for (JSONObject m : messages) msgs.put(m);
                }
                final boolean hadImage = hasImagePart(msgs);
                AiClient.Reply reply = AiClient.complete(activeBaseUrl(), activeApiKey(), activeModelName(),
                        msgs, tools(), store.temperature() / 100.0, thinkNow, 300, new AiClient.StreamCb() {
                            @Override public void onDelta(String text, String reasoning) { streamUpdate(text, reasoning); }
                        });
                if (!reply.ok && hadImage && reply.error != null && reply.error.indexOf("400") >= 0) {
                    // this model cannot take image parts - fall back to the file path and retry once
                    stripImageParts(msgs);
                    addBubble("note", "model refused the image \u2014 retried with the file path only");
                    reply = AiClient.complete(activeBaseUrl(), activeApiKey(), activeModelName(),
                            msgs, tools(), store.temperature() / 100.0, thinkNow, 300, new AiClient.StreamCb() {
                                @Override public void onDelta(String text, String reasoning) { streamUpdate(text, reasoning); }
                            });
                }
                streamReset();
                if (stop) break;
                if (reply.promptTokens + reply.completionTokens > 0) {
                    lastUsage = tok(reply.promptTokens) + "\u2192" + tok(reply.completionTokens) + " tok";
                }
                if (!reply.ok) {
                    addBubble("note", "\u26a0 " + reply.error);
                    brokeEarly = true;
                    break;
                }
                boolean hasToolCalls = reply.toolCalls != null && reply.toolCalls.length() > 0;
                List<String> cmds = extractCommands(reply.text);
                String visible = stripFences(reply.text).trim();
                if (!visible.isEmpty()) { addBubble("assistant", visible); lastAssistantSaid = visible; }

                try {
                    JSONObject am = new JSONObject();
                    am.put("role", "assistant");
                    am.put("content", reply.text == null ? "" : reply.text);
                    if (reply.reasoning != null && !reply.reasoning.isEmpty()) am.put("reasoning_content", reply.reasoning);
                    if (hasToolCalls) am.put("tool_calls", reply.toolCalls);
                    synchronized (messages) { messages.add(am); }
                } catch (Throwable ignored) { }

                if (hasToolCalls) {
                    for (int i = 0; i < reply.toolCalls.length() && !stop; i++) {
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
                        if (thinkBase == 3 && looksLikeFailure(result)) escalate = true;
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
                    if (thinkBase == 3 && looksLikeFailure(result)) escalate = true;
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
            if (stop) addBubble("note", "stopped.");
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
        String cat = permissionCategory(cmd);
        boolean gated = cat != null;
        if (gated && !store.allowAlways(cat) && !allowInChat.contains(cat)) {
            int verdict = askPermission(cat, cmd);
            if (verdict == PERM_DENY) {
                audit(cat, "DENY", cmd);
                addBubble("note", "ditolak \u00b7 " + cat + " \u00b7 " + firstLine(cmd));
                return "[denied by the user - this command was NOT executed. Do not retry it; report and continue.]";
            }
            audit(cat, verdict == PERM_ALWAYS ? "ALLOW-ALWAYS" : (verdict == PERM_CHAT ? "ALLOW-CHAT" : "ALLOW-ONCE"), cmd);
        } else if (gated) {
            audit(cat, "ALLOW-POLICY", cmd);
        }
        Integer seen = runCounts.get(cmd);
        int n = seen == null ? 0 : seen;
        if (n >= 2) {
            addBubble("note", "guard: perintah sama sudah 2x \u2014 dilewati");
            return "[blocked by the app: you already ran this exact command twice and the output did not change. "
                    + "Stop retrying it - explain the blocker to the user or try a genuinely different approach.]";
        }
        runCounts.put(cmd, n + 1);
        addBubble("tool", "$ " + cmd);
        final String out = RootShell.run(cmd, store.timeoutSec());
        addBubble("tool", out);
        return out;
    }

    /** which gate category this command belongs to (null = run it silently) */
    private String permissionCategory(String cmd) {
        if (cmd == null) return null;
        for (int i = 0; i < CAT_RE.length; i++) {
            if (CAT_RE[i].matcher(cmd).find()) return CAT_KEYS[i];
        }
        return null;
    }

    /** append every gated decision to <files>/audit.log */
    private void audit(String cat, String verdict, String cmd) {
        try {
            String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " | " + cat + " | " + verdict + " | " + firstLine(cmd) + "\n";
            java.io.FileOutputStream fos = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "audit.log"), true);
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) { }
    }

    /** blocks the worker thread until the user taps a choice in the dialog */
    private int askPermission(final String cat, final String cmd) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger res =
                new java.util.concurrent.atomic.AtomicInteger(PERM_DENY);
        permLatch = latch;
        permResult = res;
        ui.post(new Runnable() {
            @Override public void run() {
                LinearLayout box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(20), dp(2), dp(20), dp(10));

                String why = lastAssistantSaid == null ? "" : lastAssistantSaid.trim();
                if (why.length() > 220) why = why.substring(0, 220) + "\u2026";
                if (!why.isEmpty()) {
                    TextView r = tv(13, FG, Typeface.NORMAL);
                    r.setText(why);
                    box.addView(r, new LinearLayout.LayoutParams(-1, -2));
                }
                TextView msg = tv(12, MUTED, Typeface.NORMAL);
                String line = firstLine(cmd);
                if (line.length() > 220) line = line.substring(0, 220) + "\u2026";
                msg.setText("$ " + line);
                msg.setTypeface(Typeface.MONOSPACE);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
                mlp.setMargins(0, dp(6), 0, 0);
                box.addView(msg, mlp);

                final int ci = Math.max(0, java.util.Arrays.asList(CAT_KEYS).indexOf(cat));
                final String[] labels = { "Izinkan sekali", "Izinkan di percakapan ini", "Izinkan selalu (" + CAT_KEYS[ci] + ")", "Tolak" };
                final AlertDialog[] holder = new AlertDialog[1];
                for (int i = 0; i < labels.length; i++) {
                    final int choice = i;
                    TextView b = tv(15, choice == PERM_DENY ? DANGER : FG,
                            choice == PERM_DENY ? Typeface.NORMAL : Typeface.BOLD);
                    b.setText(labels[i]);
                    b.setGravity(Gravity.CENTER_VERTICAL);
                    b.setPadding(dp(14), 0, dp(14), 0);
                    b.setBackground(ripple(SURFACE, LINE, 12));
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View x) {
                            res.set(choice);
                            if (choice == PERM_CHAT) allowInChat.add(cat);
                            if (choice == PERM_ALWAYS) store.setAllowAlways(cat, true);
                            latch.countDown();
                            if (holder[0] != null) holder[0].dismiss();
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
                    lp.setMargins(0, dp(8), 0, 0);
                    box.addView(b, lp);
                }

                AlertDialog dlg = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Butuh izin: " + CAT_LABEL[ci])
                        .setView(box)
                        .create();
                holder[0] = dlg;
                dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) {
                        res.set(PERM_DENY);
                        latch.countDown();
                    }
                });
                dlg.show();
            }
        });
        try { latch.await(); } catch (Throwable ignored) { }
        permLatch = null;
        permResult = null;
        return res.get();
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
          .append("post, or buy anything unless the user asked for exactly that.\n")
          .append("8. Be FAST and dense. Batch related shell work into ONE command (`a; b; c`) instead of one ")
          .append("command per step; keep sleeps at 1-2s and confirm state with `dumpsys window | grep mCurrentFocus` ")
          .append("instead of waiting long. Reuse element ids/bounds you already found - never re-dump the same ")
          .append("screen twice. A typical UI task should be 2-3 tool calls total (locate+act combined, then ")
          .append("verify), not one call per action.\n")
          .append("9. Answer, do not narrate. Reply to the actual question in a few sentences and stop: no ")
          .append("'here is what I did' recap, no bullet list of the commands you ran (the user can expand them ")
          .append("in the UI), no preamble or closing smalltalk. When the user attaches an image you receive it ")
          .append("AS AN IMAGE - actually look at it and answer directly from what you see. If something is ")
          .append("genuinely impossible for you, say that in ONE short line and stop. Short beats complete.\n\n")
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
          .append("  batch example: `uiautomator dump /sdcard/u.xml >/dev/null; grep -o '<node[^>]*text=\"Pencarian[^\"]*\"[^>]*>' /sdcard/u.xml; input tap CX CY; sleep 1; input text 'cari%snama'; sleep 1; uiautomator dump /sdcard/u2.xml >/dev/null; grep -o 'text=\"[^\"]*\"' /sdcard/u2.xml | head -5`\n")
          .append("- mentions: `@<package>` in the user's message refers to that installed app (pm list packages, pm path <pkg>, dumpsys package <pkg>).\n")
          .append("- logs: logcat -d -b crash, logcat -d | tail -200, dmesg | tail\n")
          .append("- binaries: busybox/toybox, unzip, curl are present; there is NO java/python/aapt/apktool in ")
          .append("/system/bin. Tooling lives in Termux (installed here, its packages mostly not). apt/pkg REFUSE to ")
          .append("run as root (\"Cannot run 'pkg' as root\"), so run them AS THE TERMUX USER: ")
          .append("`U=$(pm list packages -U | sed -n 's/.*com\\.termux uid:\\([0-9]*\\).*/\\1/p'); su $U -c 'P=/data/data/com.termux/files/usr; ")
          .append("export PREFIX=$P HOME=/data/data/com.termux/files/home PATH=$P/bin LD_LIBRARY_PATH=$P/lib; ")
          .append("$P/bin/pkg install -y zip'` (needs network). Note /data/data/com.termux is not visible in this ")
          .append("shell's mount namespace - if a plain path says \"No such file\", read it via `nsenter -t 1 -m -- ...`.\n\n")
          .append("REVERSE ENGINEERING / MOD APK (everything on this device)\n")
          .append("- pull: `p=$(pm path <pkg> | sed 's/package://'); cp \"$p\" /data/local/tmp/base.apk; ls -l /data/local/tmp/base.apk`\n")
          .append("- peek without tools: `unzip -l base.apk`, `unzip -p base.apk classes.dex | strings -n 6 | head -50`, ")
          .append("`strings -n 8 base.apk | grep -E 'https?://' | head`; parsed manifest + permissions come from `dumpsys package <pkg>`\n")
          .append("- decompile / rebuild: `apktool d -f base.apk -o out`, edit smali/res, `apktool b out -o patched.apk`; ")
          .append("jadx for readable Java; patch smali only at the exact spot jadx pointed to\n")
          .append("- sign: `apksigner sign --ks keys.jks --ks-pass pass:<pw> --out signed.apk patched.apk`, or ")
          .append("`java -jar uber-apk-signer.jar -a patched.apk` (makes a debug key)\n")
          .append("- install: `pm install -r -d /data/local/tmp/patched.apk`, verify with `dumpsys package <pkg> | grep -E 'versionName|lastUpdateTime'`\n")
          .append("- this phone already runs KernelSU modules KPatch-Next, tricky_store, zygisk-detach and morphe patches: ")
          .append("signature/attestation checks may already be bypassed at kernel level - try the patched APK as-is first. ")
          .append("On INSTALL_FAILED_UPDATE_INCOMPATIBLE say so and stop; uninstall only if the user accepts losing app data.\n")
          .append("- protection awareness: an APK containing libpairipcore.so (PairIP) or a known packer breaks after ")
          .append("repackaging - say that up front instead of burning steps. Always keep the untouched base.apk as backup.\n")
          .append("- stay in scope: only pull/patch/install what the user asked for, report what changed and how you verified it.\n")
          .append("- risky actions are GATED in this app: installing packages/APKs, deleting or formatting data, ")
          .append("changing system/kernel state (or piping internet code into a shell), sending data off the phone, and ")
          .append("sending messages. The user gets a dialog (izinkan sekali / percakapan ini / selalu / tolak) with your ")
          .append("last sentence as the reason - so say WHY in one short line before such a command. If it is denied the ")
          .append("command does NOT run: do not retry it, explain the blocker, and offer an alternative.\n");
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

    /** auto mode: fast for UI/operational tasks, deeper thinking for debugging and analysis */
    private static int autoThinking(String prompt) {
        if (prompt == null) return 1;
        String p = prompt.toLowerCase(Locale.ENGLISH);
        String[] deep = {"kenapa", "mengapa", "why", "analisa", "analisis", "analyze", "debug", "trace",
                "audit", "investigasi", "investigate", "root cause", "penyebab", "perbaiki", "fix",
                "jelaskan", "explain", "bandingkan", "compare", "riset", "research", "review", "bug",
                "error", "crash", "stacktrace", "vulnerab", "optimalkan", "optimize", "rancang", "desain"};
        String[] fast = {"buka", "open", "launch", "tap", "ketuk", "klik", "click", "ketik", "type",
                "cari", "search", "scroll", "swipe", "screenshot", "screencap", "kirim", "send",
                "install", "uninstall", "restart", "reboot", "matikan", "nyalakan", "toggle",
                "jalankan", "pindah", "ganti", "ubah", "set"};
        int score = 0;
        for (String w : deep) if (p.contains(w)) score += 3;
        for (String w : fast) if (p.contains(w)) score -= 1;
        if (score >= 2) return 2;
        if (score <= -1) return 0;
        return 1;
    }

    /** did a shell step fail? then auto mode escalates the next call to deep thinking */
    private static boolean looksLikeFailure(String out) {
        if (out == null) return false;
        String l = out.toLowerCase(Locale.ENGLISH);
        return l.contains("[exit ") || l.contains("error") || l.contains("exception")
                || l.contains("permission denied") || l.contains("not found") || l.contains("failed")
                || l.contains("no such") || l.contains("cannot");
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

    private void styleThinking(Button b, boolean on) {
        b.setTextColor(on ? ON_ACCENT : FG);
        b.setBackground(ripple(on ? ACCENT : SURFACE, on ? ACCENT : LINE, 12));
    }

    // ==================== models & providers screen ====================

    private void showModels() {
        screen = 3;
        LinearLayout v = shell();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(GUTTER), statusBarHeight() + dp(6), dp(12), dp(6));
        bar.addView(iconBtn("\u2190", new View.OnClickListener() {
            @Override public void onClick(View x) { showSettings(); }
        }));
        TextView t = tv(18, FG, Typeface.BOLD);
        t.setText("Models & providers");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.setMargins(dp(8), 0, 0, 0);
        bar.addView(t, tlp);
        bar.addView(iconBtn("\u002B", new View.OnClickListener() {
            @Override public void onClick(View x) { addDialog(); }
        }));
        v.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(GUTTER), dp(4), dp(GUTTER), dp(24));
        sc.addView(list, new ScrollView.LayoutParams(-1, -2));
        v.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        JSONArray ps = providers();
        JSONArray ms = models();

        LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(-1, -2);
        h1.setMargins(0, dp(10), 0, dp(8));
        list.addView(sectionLabel("PROVIDERS \u00b7 " + ps.length()), h1);
        if (ps.length() == 0) {
            TextView e = tv(12, MUTED, Typeface.NORMAL);
            e.setText("No providers yet \u2014 tap + to add one (DeepSeek, OpenAI, OpenRouter, a local server\u2026).");
            list.addView(e);
        }
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null) list.addView(providerCard(p));
        }

        LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(-1, -2);
        h2.setMargins(0, dp(24), 0, dp(8));
        list.addView(sectionLabel("MODELS \u00b7 " + ms.length()), h2);
        if (ms.length() == 0) {
            TextView e = tv(12, MUTED, Typeface.NORMAL);
            e.setText("No models yet \u2014 tap + and add one under a provider.");
            list.addView(e);
        }
        for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            if (m != null) list.addView(modelCard(m));
        }

        TextView hint = tv(11, MUTED, Typeface.NORMAL);
        hint.setText("Tap a model to use it \u00b7 switch to enable/disable \u00b7 long-press to edit \u00b7 \u2715 to delete");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.setMargins(0, dp(6), 0, 0);
        list.addView(hint, hlp);

        Button addP = new Button(this);
        addP.setText("Add provider");
        addP.setAllCaps(false);
        addP.setTextSize(15);
        addP.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addP.setTextColor(FG);
        addP.setBackground(ripple(SURFACE, LINE, 14));
        addP.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { providerDialog(null); }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(50));
        plp.setMargins(0, dp(22), 0, dp(10));
        list.addView(addP, plp);

        Button addM = new Button(this);
        addM.setText("Add model");
        addM.setAllCaps(false);
        addM.setTextSize(15);
        addM.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addM.setTextColor(ON_ACCENT);
        addM.setBackground(ripple(ACCENT, ACCENT, 14));
        addM.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { modelDialog(null); }
        });
        list.addView(addM, new LinearLayout.LayoutParams(-1, dp(50)));

        root.removeAllViews();
        root.addView(v);
    }

    private void addDialog() {
        final String[] opts = { "Provider (base URL + API key)", "Model (under a provider)" };
        new AlertDialog.Builder(this)
                .setTitle("Add\u2026")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) providerDialog(null); else modelDialog(null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View providerCard(final JSONObject p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ripple(SURFACE, LINE, 16));
        card.setPadding(dp(14), dp(10), dp(6), dp(10));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = tv(15, FG, Typeface.BOLD);
        t1.setText(p.optString("name", "provider"));
        t1.setSingleLine(true);
        TextView t2 = tv(12, MUTED, Typeface.NORMAL);
        t2.setText(p.optString("baseUrl", "") + (p.optString("apiKey", "").isEmpty() ? "" : "  \u00b7  key set"));
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        info.addView(t1);
        info.addView(t2);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(iconBtn("\u2715", new View.OnClickListener() {
            @Override public void onClick(View x) { confirmDeleteProvider(p); }
        }));
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View x) { providerDialog(p); return true; }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    private View modelCard(final JSONObject m) {
        final boolean enabled = m.optBoolean("enabled", true);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ripple(SURFACE, LINE, 16));
        card.setPadding(dp(6), dp(6), dp(6), dp(6));

        Switch on = new Switch(this);
        on.setText("");
        on.setChecked(enabled);
        on.setMinHeight(dp(48));
        on.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                JSONArray arr = models();
                JSONObject cur = findById(arr, m.optString("id"));
                try { if (cur != null) cur.put("enabled", checked); } catch (Throwable ignored) { }
                saveModels(arr);
                if (!checked && m.optString("id").equals(store.activeModelId())) {
                    JSONObject next = firstEnabledModel(m.optString("id"));
                    store.setActiveModelId(next == null ? "" : next.optString("id"));
                } else if (checked && store.activeModelId().isEmpty()) {
                    store.setActiveModelId(m.optString("id"));
                }
                updateSubtitle();
                toast((checked ? "Enabled \u00b7 " : "Disabled \u00b7 ") + shortLabel(m));
                showModels();
            }
        });
        card.addView(on);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        boolean isActive = m.optString("id").equals(store.activeModelId());
        TextView t1 = tv(15, enabled ? FG : MUTED, Typeface.BOLD);
        t1.setText((isActive ? "\u25CF " : "") + shortLabel(m));
        t1.setSingleLine(true);
        TextView t2 = tv(12, MUTED, Typeface.NORMAL);
        JSONObject p = providerOf(m);
        t2.setText(m.optString("name", "") + "  \u00b7  " + (p == null ? "missing provider" : p.optString("name", ""))
                + (m.optBoolean("vision", true) ? "  \u00b7  vision" : "")
                + (enabled ? "" : "  \u00b7  disabled"));
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        info.addView(t1);
        info.addView(t2);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1);
        ilp.setMargins(dp(6), 0, dp(6), 0);
        card.addView(info, ilp);

        card.addView(iconBtn("\u2715", new View.OnClickListener() {
            @Override public void onClick(View x) { confirmDeleteModel(m); }
        }));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { activateModel(m); }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View x) { modelDialog(m); return true; }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    private void activateModel(JSONObject m) {
        if (!m.optBoolean("enabled", true)) { toast("Model is disabled \u2014 flip its switch on first"); return; }
        if (providerOf(m) == null) { toast("This model's provider is missing"); return; }
        store.setActiveModelId(m.optString("id"));
        updateSubtitle();
        toast("Active model \u00b7 " + shortLabel(m));
        showModels();
    }

    private void providerDialog(final JSONObject existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        final EditText name = field(box, "Name", existing == null ? "" : existing.optString("name", ""), "DeepSeek / OpenAI / Local", false);
        final EditText url = field(box, "Base URL", existing == null ? "" : existing.optString("baseUrl", ""), "https://api.deepseek.com", false);
        final EditText key = field(box, "API key", existing == null ? "" : existing.optString("apiKey", ""), "sk-\u2026 (empty for local)", true);
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add provider" : "Edit provider")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String nm = name.getText().toString().trim();
                        String u = url.getText().toString().trim();
                        if (u.isEmpty()) { toast("Base URL is required"); return; }
                        try {
                            JSONArray ps = providers();
                            JSONObject p = findById(ps, existing == null ? "" : existing.optString("id"));
                            if (p == null) {
                                p = new JSONObject();
                                p.put("id", "p" + System.currentTimeMillis());
                                ps.put(p);
                            }
                            p.put("name", nm.isEmpty() ? "Provider" : nm);
                            p.put("baseUrl", u);
                            p.put("apiKey", key.getText().toString().trim());
                            saveProviders(ps);
                            toast("Provider saved");
                        } catch (Throwable t) { toast("Save failed: " + t); }
                        showModels();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void modelDialog(final JSONObject existing) {
        JSONArray ps = providers();
        if (ps.length() == 0) { toast("Add a provider first"); providerDialog(null); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        final EditText name = field(box, "Model ID", existing == null ? "" : existing.optString("name", ""), "deepseek-flash / gpt-4o-mini", false);
        final EditText label = field(box, "Label", existing == null ? "" : existing.optString("label", ""), "shown in the app (optional)", false);
        box.addView(sectionLabel("PROVIDER"));
        final android.widget.Spinner sp = new android.widget.Spinner(this);
        final ArrayList<String> pnames = new ArrayList<>();
        final ArrayList<String> pids = new ArrayList<>();
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            pnames.add(p.optString("name", "provider"));
            pids.add(p.optString("id"));
        }
        sp.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, pnames));
        if (existing != null) {
            int idx = pids.indexOf(existing.optString("providerId"));
            if (idx >= 0) sp.setSelection(idx);
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(48));
        slp.setMargins(0, dp(6), 0, dp(4));
        box.addView(sp, slp);
        final Switch sees = new Switch(this);
        sees.setText("Sees images");
        sees.setTextColor(FG);
        sees.setTextSize(14);
        sees.setMinHeight(dp(48));
        sees.setChecked(existing == null || existing.optBoolean("vision", true));
        box.addView(sees, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add model" : "Edit model")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) { toast("Model ID is required"); return; }
                        int sel = sp.getSelectedItemPosition();
                        if (sel < 0 || sel >= pids.size()) { toast("Pick a provider"); return; }
                        try {
                            JSONArray ms = models();
                            JSONObject m = findById(ms, existing == null ? "" : existing.optString("id"));
                            if (m == null) {
                                m = new JSONObject();
                                m.put("id", "m" + System.currentTimeMillis());
                                m.put("enabled", true);
                                ms.put(m);
                            }
                            m.put("name", n);
                            m.put("label", label.getText().toString().trim());
                            m.put("providerId", pids.get(sel));
                            m.put("vision", sees.isChecked());
                            saveModels(ms);
                            if (store.activeModelId().isEmpty()) store.setActiveModelId(m.optString("id"));
                            toast("Model saved");
                        } catch (Throwable t) { toast("Save failed: " + t); }
                        showModels();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteProvider(final JSONObject p) {
        int n = 0;
        JSONArray ms = models();
        for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            if (m != null && p.optString("id").equals(m.optString("providerId"))) n++;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete provider?")
                .setMessage(p.optString("name", "") + (n > 0 ? " \u00b7 also deletes " + n + " model(s)" : ""))
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        JSONArray ps = providers();
                        JSONArray outP = new JSONArray();
                        for (int i = 0; i < ps.length(); i++) {
                            JSONObject o = ps.optJSONObject(i);
                            if (o != null && !p.optString("id").equals(o.optString("id"))) outP.put(o);
                        }
                        JSONArray ms2 = models();
                        JSONArray outM = new JSONArray();
                        for (int i = 0; i < ms2.length(); i++) {
                            JSONObject o = ms2.optJSONObject(i);
                            if (o != null && !p.optString("id").equals(o.optString("providerId"))) outM.put(o);
                        }
                        saveProviders(outP);
                        saveModels(outM);
                        ensureActiveStillValid();
                        showModels();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteModel(final JSONObject m) {
        new AlertDialog.Builder(this)
                .setTitle("Delete model?")
                .setMessage(shortLabel(m) + "  \u00b7  " + m.optString("name", ""))
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        JSONArray ms = models();
                        JSONArray out = new JSONArray();
                        for (int i = 0; i < ms.length(); i++) {
                            JSONObject o = ms.optJSONObject(i);
                            if (o != null && !m.optString("id").equals(o.optString("id"))) out.put(o);
                        }
                        saveModels(out);
                        ensureActiveStillValid();
                        showModels();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ==================== attachments ====================

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ATTACH && res == RESULT_OK && data != null && data.getData() != null) {
            saveAttachment(data.getData());
        }
    }

    private void openAttachPicker() {
        final String[] opts = { "Image / photo", "Any file" };
        new AlertDialog.Builder(this)
                .setTitle("Add attachment")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType(w == 0 ? "image/*" : "*/*");
                        try {
                            startActivityForResult(i, REQ_ATTACH);
                        } catch (Throwable t) {
                            toast("No file picker: " + t);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** copy the picked file into the app's external files dir so the root shell can read it */
    private void saveAttachment(final android.net.Uri uri) {
        toast("Copying attachment\u2026");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    android.content.ContentResolver cr = getContentResolver();
                    String name = "attachment.bin";
                    long size = -1;
                    android.database.Cursor c = cr.query(uri, null, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                                int si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                                if (ni >= 0 && c.getString(ni) != null) name = c.getString(ni);
                                if (si >= 0) size = c.getLong(si);
                            }
                        } finally { c.close(); }
                    }
                    name = name.replaceAll("[^A-Za-z0-9._-]", "_");
                    java.io.File base = getExternalFilesDir(null);
                    if (base == null) base = getFilesDir();
                    java.io.File dir = new java.io.File(base, "attachments");
                    dir.mkdirs();
                    java.io.File out = new java.io.File(dir, System.currentTimeMillis() + "_" + name);
                    java.io.InputStream in = cr.openInputStream(uri);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close();
                    in.close();
                    final String path = out.getAbsolutePath();
                    final String fname = name;
                    final String label = fname + (size > 0 ? "  \u00b7  " + (size / 1024) + " KB" : "");
                    ui.post(new Runnable() {
                        @Override public void run() {
                            pendingPath = path;
                            pendingName = fname;
                            showAttachChip(label);
                            toast("Attached \u00b7 " + fname);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() { toast("Attach failed: " + t); }
                    });
                }
            }
        }).start();
    }

    private void showAttachChip(String label) {
        if (attachBar == null) return;
        attachBar.removeAllViews();
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(round(SURFACE, LINE, 12));
        chip.setPadding(dp(12), dp(4), dp(4), dp(4));
        TextView t = tv(12, FG, Typeface.NORMAL);
        t.setText("\uD83D\uDCCE  " + label);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        chip.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        chip.addView(iconBtn("\u2715", new View.OnClickListener() {
            @Override public void onClick(View x) {
                pendingPath = null;
                pendingName = null;
                attachBar.setVisibility(View.GONE);
            }
        }));
        attachBar.addView(chip, new LinearLayout.LayoutParams(-1, -2));
        attachBar.setVisibility(View.VISIBLE);
    }

    // ==================== @mention installed apps ====================

    private void ensureAppsLoaded() {
        if (installedApps != null) return;
        installedApps = new ArrayList<>();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    android.content.pm.PackageManager pm = getPackageManager();
                    ArrayList<AppEntry> out = new ArrayList<>();
                    for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                        if (!ai.enabled) continue;
                        out.add(new AppEntry(String.valueOf(pm.getApplicationLabel(ai)), ai.packageName, ai));
                    }
                    Collections.sort(out, new Comparator<AppEntry>() {
                        @Override public int compare(AppEntry a, AppEntry b) {
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                    installedApps = out;
                } catch (Throwable t) {
                    installedApps = new ArrayList<>();
                }
            }
        }).start();
    }

    /** icon for a picker row: cached; safe to call from any thread */
    private android.graphics.drawable.Drawable iconFor(AppEntry e) {
        android.graphics.drawable.Drawable d = appIcons.get(e.pkg);
        if (d != null) return d;
        try {
            d = getPackageManager().getApplicationIcon(e.info);
        } catch (Throwable t) {
            d = null;
        }
        if (d == null) {
            try { d = getPackageManager().getDefaultActivityIcon(); } catch (Throwable ignored) { }
        }
        if (d != null) appIcons.put(e.pkg, d);
        return d;
    }

    private void openAppPicker(final int atIndex) {
        if (pickerOpen || input == null) return;
        pickerOpen = true;
        ensureAppsLoaded();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(4));

        final EditText search = new EditText(this);
        search.setHint("Search installed apps\u2026");
        search.setHintTextColor(MUTED);
        search.setTextColor(FG);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setMinHeight(dp(48));
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(round(BG, LINE, 12));
        box.addView(search);

        final android.widget.ListView lv = new android.widget.ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setPadding(0, dp(2), 0, dp(2));
        lv.setClipToPadding(false);
        final ArrayList<AppEntry> hits = new ArrayList<>();
        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return hits.size(); }
            @Override public Object getItem(int i) { return hits.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                LinearLayout row;
                if (convert instanceof LinearLayout) {
                    row = (LinearLayout) convert;
                } else {
                    // ListView rows cannot carry margins (AbsListView.LayoutParams) - pad the outer
                    // row and put the card inside it so the rounded card gets its own gap
                    row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(0, 0, 0, dp(6));
                    row.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));
                    LinearLayout card = new LinearLayout(MainActivity.this);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(Gravity.CENTER_VERTICAL);
                    card.setMinimumHeight(dp(56));
                    card.setPadding(dp(10), dp(8), dp(12), dp(8));
                    card.setBackground(ripple(SURFACE, LINE, 14));
                    ImageView iv = new ImageView(MainActivity.this);
                    iv.setId(1001);
                    card.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
                    LinearLayout col = new LinearLayout(MainActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    TextView t1 = tv(15, FG, Typeface.BOLD);
                    t1.setId(1002);
                    t1.setSingleLine(true);
                    t1.setEllipsize(TextUtils.TruncateAt.END);
                    col.addView(t1);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
                    clp.setMargins(dp(12), 0, 0, 0);
                    card.addView(col, clp);
                    row.addView(card);
                }
                AppEntry e = hits.get(pos);
                ((ImageView) row.findViewById(1001)).setImageDrawable(iconFor(e));
                ((TextView) row.findViewById(1002)).setText(e.label);
                return row;
            }
        };
        lv.setAdapter(adapter);
        final Runnable refill = new Runnable() {
            @Override public void run() {
                String q = search.getText().toString().trim().toLowerCase(Locale.ENGLISH);
                hits.clear();
                int n = 0;
                for (AppEntry a : installedApps) {
                    if (q.isEmpty()
                            || a.label.toLowerCase(Locale.ENGLISH).contains(q)
                            || a.pkg.toLowerCase(Locale.ENGLISH).contains(q)) {
                        hits.add(a);
                        if (++n >= 60) break;
                    }
                }
                adapter.notifyDataSetChanged();
                // pre-warm the icons off the UI thread; rows pick them up on the next pass
                final ArrayList<AppEntry> snapshot = new ArrayList<>(hits);
                new Thread(new Runnable() {
                    @Override public void run() {
                        for (AppEntry e : snapshot) {
                            if (!appIcons.containsKey(e.pkg)) iconFor(e);
                        }
                        ui.post(new Runnable() { @Override public void run() { adapter.notifyDataSetChanged(); } });
                    }
                }).start();
            }
        };
        refill.run();
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { refill.run(); }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, dp(330));
        llp.setMargins(0, dp(10), 0, 0);
        box.addView(lv, llp);
        ui.postDelayed(new Runnable() { @Override public void run() { refill.run(); } }, 600);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Mention an app")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= hits.size()) return;
                String pkg = hits.get(pos).pkg;
                android.text.Editable e = input.getText();
                if (atIndex >= 0 && atIndex < e.length() && e.charAt(atIndex) == '@') {
                    e.replace(atIndex, atIndex + 1, "@" + pkg + " ");
                } else {
                    e.insert(Math.min(Math.max(atIndex, 0), e.length()), "@" + pkg + " ");
                }
                dlg.dismiss();
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface x) { pickerOpen = false; }
        });
        dlg.show();
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

    /** a true circle that always fills the view bounds exactly (no corner-radius clamping quirks) */
    private RippleDrawable circle(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null);
    }
}
