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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
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
import android.widget.FrameLayout;
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

    /** AUTO toggle next to the input: when on, every permission gate is approved automatically */
    private volatile boolean autoApprove = false;
    private TextView autoBtn;

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
    /** a bad pattern must never take the app down - fall back and keep enough to still gate things */
    private static java.util.regex.Pattern safeRe(String re, String fallback) {
        try {
            return java.util.regex.Pattern.compile(re);
        } catch (Throwable t) {
            android.util.Log.e("aissistants", "bad gate pattern, using fallback: " + t.getMessage());
            return java.util.regex.Pattern.compile(fallback);
        }
    }

    private static final java.util.regex.Pattern[] CAT_RE = {
            safeRe("(pkg|apt|apt-get|dpkg|pip|pip3|npm|yarn|pnpm|gem|go|apk|pm|magisk|cargo)\\s+(-{1,2}[\\w=-]+\\s+)*(install|add|i|-i)\\b",
                    "(install|add)"),
            safeRe("(rm\\s+-[a-zA-Z]*[rf]|\\bshred\\s|dd\\s+[^|;]*of=/dev/|\\bmkfs|MASTER_CLEAR|--wipe|truncate\\s+-s\\s+0)",
                    "rm\\s+-[a-zA-Z]*[rf]"),
            safeRe("(mount\\s+[^|;]*(remount|,rw)|\\binsmod\\b|\\brmmod\\b|magisk\\s+--(install|remove|uninstall)|\\bksud\\b|setenforce\\s+0|>\\s*\\S*/data/adb/|(cp|mv|rm|ln|chmod|chown)\\s+[^;|]*/data/adb/|sed\\s+-i[^;|]*/data/adb/|wm\\s+(size|density)\\s+[0-9]|settings\\s+put|svc\\s+(data|wifi|bluetooth|power)|\\|\\s*(sh|bash)\\b|eval\\s+\\$\\(|curl[^|;]*(-o|--output)[^|;]*/data/local/tmp|wget[^|;]*/data/local/tmp|\\breboot\\b|\\bctl\\.(restart|start|stop)\\b|\\bsvc\\s+power\\s+(reboot|shutdown)\\b|\\bkill(all)?\\s+(-[0-9]+\\s+)?(zygote|system_server|init)\\b|\\bsetprop\\s+(sys\\.(powerctl|boot)|init\\.[a-z_.]*)\\b|(^|[;&|]\\s*)(stop|start)\\s*($|[;&|])",
                    "mount\\s+[^|;]*(remount|,rw)|setenforce\\s+0|settings\\s+put|\\|\\s*(sh|bash)\\b"),
            safeRe("(curl[^|;]*(--data|-d\\s|-F\\s|-T\\s|--upload-file|-X\\s*(POST|PUT|PATCH))|wget[^|;]*--post-data|\\bscp\\b|\\brsync\\b|\\bnc\\s+-)",
                    "curl[^|;]*(--data|-d\\s|-F\\s)|\\bscp\\b|\\brsync\\b"),
            safeRe("(\\bsendto\\b|\\bsmsto\\b|service\\s+call\\s+isms|android\\.intent\\.action\\.SEND\\b)",
                    "\\bsendto\\b|\\bsmsto\\b|\\.SEND\\b")
    };
    private final java.util.Set<String> allowInChat = new java.util.HashSet<>();
    private volatile java.util.concurrent.CountDownLatch permLatch;
    private volatile java.util.concurrent.atomic.AtomicInteger permResult;
    /** the model's last sentence, shown in the dialog so the user knows WHY it wants the command */
    private volatile String lastAssistantSaid = "";

    /** how many times each exact command ran during the current run (anti-repeat-loop guard) */
    private final java.util.Map<String, Integer> runCounts = new java.util.HashMap<>();
    /** first output of each command, replayed when the model insists on repeating it */
    private final java.util.Map<String, String> runOutputs = new java.util.HashMap<>();
    /** how many times the guard had to refuse each command; 3 = the run is stopped as a loop */
    private final java.util.Map<String, Integer> guardHits = new java.util.HashMap<>();
    private volatile boolean loopBroken = false;
    private volatile boolean stuckRun = false;   // guard tripped: think at max for the rest of the run

    /** tool runs the user expanded in the transcript: keys are "sessionId:firstBubbleIndex" */
    private final java.util.Set<String> expandedGroups = new java.util.HashSet<>();

    /** floating collapse chip: follows the scroll so a huge expanded tool group can be closed from anywhere */
    private TextView groupChip;
    private final java.util.List<String> groupKeys = new java.util.ArrayList<>();
    private final java.util.List<int[]> groupSpans = new java.util.ArrayList<>();
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
    /** durable per-session log: bubbles land on disk the moment they exist, so a kill/restart
     *  (HyperOS memory kill, crash, or a full zygote/system_server restart) cannot swallow a run */
    private SessionLog jrnl;
    private int bubbleTick = 0;
    private long lastBak = 0L;
    private JSONObject cur;
    /** once the shared-dir warning was shown for this run */
    private boolean foreignTmpWarned;
    private final List<JSONObject> messages = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();

    private volatile boolean busy;
    /** true once an injected mid-run message already got its one automatic continuation */
    private boolean midRunRestartUsed = false;
    /** mid-run user input waiting for a safe point in the conversation (never between tool_calls and its tool replies) */
    private final List<String> injectedQueue = new ArrayList<>();
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
        jrnl = new SessionLog(getFilesDir());
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
    protected void onPause() {
        super.onPause();
        persist();
    }

    @Override
    protected void onStop() {
        super.onStop();
        persist();
    }

    @Override
    public void onBackPressed() {
        if (screen == 3) { showSettings(); return; }
        if (screen != 0) { showChat(); return; }
        super.onBackPressed();
    }

    /** automation hooks: `--es run "<script>"` asks for in-app approval before executing as root. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String run = intent.getStringExtra("run");
        if (run != null && !run.trim().isEmpty()) {
            showChat();
            confirmAutomationRun(run.trim());
            return;
        }
        String prompt = intent.getStringExtra("prompt");
        if (prompt != null && !prompt.trim().isEmpty()) {
            showChat();
            confirmAutomationPrompt(prompt.trim());
        }
    }

    /** Launcher activity is exported, so external automation never gets root execution without a tap. */
    private void confirmAutomationRun(final String cmd) {
        if (cmd.length() > 2000) {
            new AlertDialog.Builder(this)
                    .setTitle("Command needs review")
                    .setMessage("External automation supplied a command longer than 2,000 characters. It will not run until you review it in the chat input.")
                    .setPositiveButton("Open in input", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            input.requestFocus();
                            input.setText("$ " + cmd);
                            input.setSelection(input.getText().length());
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Run root command?")
                .setMessage("External automation requested this command. Review it before it runs with full device access. Risky actions may need one more approval.\n\n$ " + cmd)
                .setPositiveButton("Review & run", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (busy) { toast("Still working — stop it first"); return; }
                        addBubble("user", "$ " + cmd);
                        beginReviewedRun(java.util.Collections.singletonList(cmd), false);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** An exported launcher must never silently turn another app's text into a privileged run. */
    private void confirmAutomationPrompt(final String prompt) {
        final String shown = prompt.length() > 2000 ? prompt.substring(0, 2000) + "\n\u2026 (truncated)" : prompt;
        new AlertDialog.Builder(this)
                .setTitle("Use external prompt?")
                .setMessage("Another app supplied this text. Review it before sending it to your selected provider.\n\n" + shown)
                .setPositiveButton("Open in input", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        input.requestFocus();
                        input.setText(prompt);
                        input.setSelection(input.getText().length());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

        TextView edit = iconBtn("\u270E", new View.OnClickListener() {
            @Override public void onClick(View x) { renameDialog(s); }
        });
        setButtonA11y(edit, "Rename chat " + titleOf(s));
        card.addView(edit);
        TextView remove = iconBtn("\u2715", new View.OnClickListener() {
            @Override public void onClick(View x) { confirmDelete(s); }
        });
        setButtonA11y(remove, "Delete chat " + titleOf(s));
        card.addView(remove);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { openSession(s.optString("id", "")); }
        });
        setButtonA11y(card, "Open chat " + titleOf(s));
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
        setButtonA11y(mcard, "Manage models and providers. Active: " + activeLabel());
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

        LinearLayout agentFields = new LinearLayout(this);
        agentFields.setOrientation(LinearLayout.VERTICAL);
        agentFields.setPadding(0, dp(2), 0, dp(4));
        final EditText steps = field(agentFields, "Max steps (1-5000)", String.valueOf(store.maxSteps()), "12", false);
        steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText timeout = field(agentFields, "Timeout seconds (20-1800)", String.valueOf(store.timeoutSec()), "180", false);
        timeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText temp = field(agentFields, "Temperature (0-100)", String.valueOf(store.temperature()), "30", false);
        temp.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(agentFields);
        TextView agentHelp = tv(12, MUTED, Typeface.NORMAL);
        agentHelp.setText("Lower temperature is more predictable. Settings are validated before saving.");
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(-1, -2);
        helpLp.setMargins(0, dp(8), 0, 0);
        panel.addView(agentHelp, helpLp);

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
            LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(0, dp(48), 1);
            tlp2.setMargins(0, 0, i < 3 ? dp(8) : 0, 0);
            trow.addView(tb, tlp2);
        }
        panel.addView(trow);

        final Switch auto = new Switch(this);
        auto.setText("Run safe commands automatically");
        auto.setTextColor(FG);
        auto.setTextSize(14);
        auto.setChecked(store.autoRun());
        auto.setMinHeight(dp(48));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.setMargins(0, dp(12), 0, 0);
        panel.addView(auto, alp);
        TextView autoHelp = tv(12, MUTED, Typeface.NORMAL);
        autoHelp.setText("Off by default. Risky actions still need review unless you enable AUTO for this chat.");
        LinearLayout.LayoutParams autoHelpLp = new LinearLayout.LayoutParams(-1, -2);
        autoHelpLp.setMargins(0, dp(2), 0, 0);
        panel.addView(autoHelp, autoHelpLp);

        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextSize(15);
        save.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        save.setTextColor(ON_ACCENT);
        save.setBackground(ripple(ACCENT, ACCENT, 14));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                Integer stepValue = wholeNumber(steps, 1, 5000, "Max steps");
                Integer tempValue = wholeNumber(temp, 0, 100, "Temperature");
                Integer timeoutValue = wholeNumber(timeout, 20, 1800, "Timeout");
                if (stepValue == null || tempValue == null || timeoutValue == null) return;
                store.save(stepValue, tempValue, timeoutValue,
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
        pill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        setButtonA11y(pill, "Root access status. Tap to request or retry root access.");
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
        chatScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int ox, int oy) { updateGroupChip(); }
        });
        ensureGroupChip();
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

        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(round(SURFACE, LINE, 24));
        field.setMinimumHeight(dp(48));

        TextView attach = new TextView(this);
        attach.setText("\u002B");
        attach.setTextSize(22);
        attach.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        attach.setTextColor(FG);
        attach.setGravity(Gravity.CENTER);
        attach.setIncludeFontPadding(false);
        attach.setPadding(0, 0, 0, 0);
        attach.setBackground(ripple(Color.TRANSPARENT, 0, 24));
        setButtonA11y(attach, "Add attachment");
        attach.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { openAttachPicker(); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(48), dp(48));
        alp.setMargins(dp(4), 0, dp(2), 0);
        field.addView(attach, alp);

        input = new EditText(this);
        input.setHint("Ask anything\u2026 or $ for root");
        input.setHintTextColor(MUTED);
        input.setTextColor(FG);
        input.setTextSize(15);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setMinHeight(dp(48));
        input.setPadding(dp(6), dp(12), dp(16), dp(12));
        input.setBackground(null);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshSendBtn();
                if (count == 1 && before == 0 && start < s.length() && s.charAt(start) == '@') {
                    final int at = start;
                    input.postDelayed(new Runnable() { @Override public void run() { openAppPicker(at); } }, 120);
                }
            }
            @Override public void afterTextChanged(android.text.Editable e) { refreshSendBtn(); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    if (busy && input.getText().toString().trim().length() > 0) midRunSend();
                    else if (!busy) onSend();
                    return true;
                }
                return false;
            }
        });
        field.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));

        autoBtn = new TextView(this);
        autoBtn.setText("REVIEW");
        autoBtn.setTextSize(12);
        autoBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        autoBtn.setLetterSpacing(0.06f);
        autoBtn.setGravity(Gravity.CENTER);
        setButtonA11y(autoBtn, "Review risky actions is on");
        autoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (!store.autoRun()) {
                    toast("Automatic commands are off. Enable them in Settings first.");
                } else if (autoApprove) {
                    autoApprove = false;
                    styleAutoBtn();
                    toast("Risky actions need review again");
                } else {
                    confirmAutoApproval();
                }
            }
        });
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(dp(70), dp(48));
        autoLp.gravity = Gravity.BOTTOM;
        autoLp.setMargins(dp(8), 0, 0, 0);
        row.addView(autoBtn, autoLp);
        styleAutoBtn();

        sendBtn = new TextView(this);
        sendBtn.setText("\u2191");
        sendBtn.setTextSize(22);
        sendBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sendBtn.setTextColor(ON_ACCENT);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setIncludeFontPadding(false);
        sendBtn.setPadding(0, 0, 0, 0);
        sendBtn.setBackground(circle(ACCENT));
        setButtonA11y(sendBtn, "Send message");
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (busy) {
                    if (input != null && input.getText().toString().trim().length() > 0) midRunSend();
                    else doStop();
                } else onSend();
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
        groupKeys.clear();
        groupSpans.clear();

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
                    int hidx = addToolGroup(start, count, open, key);
                    if (open) {
                        for (int k = i; k < j; k++) {
                            addBubbleView("tool", (String) snap.get(k)[1], (Long) snap.get(k)[2], "tool");
                        }
                        addToolGroupFooter(count, key);
                    }
                    groupKeys.add(key);
                    groupSpans.add(new int[]{ hidx, chatLog.getChildCount() - 1, count });
                    i = j - 1;
                    prev = "tool";
                    continue;
                }
                if ("note".equals(role) && (((String) m[1]).startsWith("step limit reached")
                        || ((String) m[1]).startsWith("run dihentikan"))) {
                    addContinueCard();
                    prev = role;
                    continue;
                }
                addBubbleView(role, (String) m[1], (Long) m[2], prev);
                prev = role;
            }
        }
        if (busy) { busyView = busyRow(); chatLog.addView(busyView); }
        if (!snap.isEmpty()) scrollToBottom(forceBottom);
        chatLog.post(new Runnable() { @Override public void run() { updateGroupChip(); } });
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
            String shown = text.length() > 6000 ? text.substring(0, 6000) + "\n\u2026 (truncated)" : text;
            b.setText(user ? shown : markdownText(shown));
            b.setTextIsSelectable(true);
            b.setLineSpacing(dp(2), 1f);
            b.setBackground(round(user ? ACCENT : SURFACE, user ? ACCENT : LINE, 18));
            b.setPadding(dp(14), dp(10), dp(14), dp(10));
            b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.84f));
            row.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }

        String stamp = fmtStamp(t);
        if (!stamp.isEmpty() && !note) {
            TextView s = tv(11, MUTED, Typeface.NORMAL);
            s.setText(stamp);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.setMargins(note ? 0 : dp(6), dp(3), dp(6), 0);
            if (!note && user) slp.gravity = Gravity.END;
            row.addView(s, slp);
        }
        chatLog.addView(row);
    }

    /** Small safe Markdown subset for model replies. User and shell text remain literal. */
    private CharSequence markdownText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = raw.split("\\n", -1);
        boolean fenced = false;
        int codeStart = -1;
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trim = line.trim();
            if (trim.startsWith("```")) {
                if (fenced) {
                    styleCode(out, codeStart, out.length());
                    fenced = false;
                    codeStart = -1;
                } else {
                    fenced = true;
                    codeStart = out.length();
                }
                continue;
            }
            if (fenced) {
                out.append(line);
            } else {
                int start = out.length();
                int heading = 0;
                while (heading < line.length() && line.charAt(heading) == '#') heading++;
                if (heading > 0 && heading < line.length() && line.charAt(heading) == ' ') {
                    appendInlineMarkdown(out, line.substring(heading + 1));
                    if (out.length() > start) {
                        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new RelativeSizeSpan(1.16f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    out.append("\u2022 ");
                    appendInlineMarkdown(out, line.substring(2));
                } else {
                    appendInlineMarkdown(out, line);
                }
            }
            if (n < lines.length - 1) out.append('\n');
        }
        if (fenced) styleCode(out, codeStart, out.length());
        return out;
    }

    private void appendInlineMarkdown(SpannableStringBuilder out, String line) {
        int i = 0;
        while (i < line.length()) {
            if (line.startsWith("**", i)) {
                int end = line.indexOf("**", i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    appendInlineMarkdown(out, line.substring(i + 2, end));
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (line.charAt(i) == '`') {
                int end = line.indexOf('`', i + 1);
                if (end > i + 1) {
                    int start = out.length();
                    out.append(line, i + 1, end);
                    styleCode(out, start, out.length());
                    i = end + 1;
                    continue;
                }
            }
            out.append(line.charAt(i));
            i++;
        }
    }

    private void styleCode(SpannableStringBuilder out, int start, int end) {
        if (start < 0 || end <= start) return;
        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(TOOL_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** the step-limit note becomes a button: tap = send "continue" */
    private void addContinueCard() {
        TextView c = tv(13, ON_ACCENT, Typeface.BOLD);
        c.setText("\u25B6 Lanjutkan (step limit) \u00b7 tap");
        c.setGravity(Gravity.CENTER);
        c.setBackground(ripple(ACCENT, ACCENT, 14));
        setButtonA11y(c, "Continue this task after the step limit");
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (busy) { toast("Masih jalan \u00b7 stop dulu"); return; }
                send("continue");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.setMargins(0, dp(12), 0, 0);
        chatLog.addView(c, lp);
    }

    /** one collapsed row standing in for a whole run of tool bubbles; tap to show/hide the detail */
    private int addToolGroup(final int start, final int count, final boolean open, final String key) {
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
        setButtonA11y(card, (open ? "Hide " : "Show ") + cmds
                + (cmds == 1 ? " command" : " commands"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(lp);
        chatLog.addView(card);
        return chatLog.getChildCount() - 1;
    }

    /** collapse control at the END of an expanded group - no need to scroll back to the top */
    private void addToolGroupFooter(final int count, final String key) {
        TextView f = tv(12, MUTED, Typeface.NORMAL);
        f.setText("\u25B4 Tutup \u00b7 " + count + (count == 1 ? " command" : " commands"));
        f.setGravity(Gravity.CENTER);
        f.setBackground(ripple(TOOL_BG, LINE, 12));
        f.setPadding(dp(12), dp(10), dp(12), dp(10));
        setButtonA11y(f, "Hide " + count + (count == 1 ? " command" : " commands"));
        f.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                expandedGroups.remove(key);
                renderTranscript();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(2));
        chatLog.addView(f, lp);
    }

    /** floating chip over the transcript: expands/collapses whichever tool group the viewport sits in */
    private void ensureGroupChip() {
        if (groupChip != null) return;
        ViewGroup root = findViewById(android.R.id.content);
        groupChip = tv(12, ON_ACCENT, Typeface.BOLD);
        groupChip.setBackground(ripple(ACCENT, ACCENT, 20));
        groupChip.setPadding(dp(16), dp(12), dp(16), dp(12));
        setButtonA11y(groupChip, "Hide expanded commands");
        groupChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, dp(16), dp(150));
        root.addView(groupChip, flp);
        groupChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Object tag = groupChip.getTag();
                if (!(tag instanceof String)) return;
                String k = (String) tag;
                if (!expandedGroups.contains(k)) { groupChip.setVisibility(View.GONE); return; }
                int idx = groupKeys.indexOf(k);
                final int anchor = (idx >= 0 && idx < groupSpans.size()) ? groupSpans.get(idx)[0] : -1;
                expandedGroups.remove(k);
                renderTranscript();
                if (anchor >= 0) {
                    chatLog.post(new Runnable() {
                        @Override public void run() {
                            if (anchor < chatLog.getChildCount()) {
                                chatScroll.scrollTo(0, Math.max(0, chatLog.getChildAt(anchor).getTop() - dp(8)));
                            }
                        }
                    });
                }
            }
        });
    }

    /** show the chip when the viewport is inside a tool group, hide it otherwise */
    private void updateGroupChip() {
        if (groupChip == null || chatLog == null || chatScroll == null) return;
        if (chatScreen != null && chatScreen.getVisibility() != View.VISIBLE) { groupChip.setVisibility(View.GONE); return; }
        int top = chatScroll.getScrollY();
        int bot = top + chatScroll.getHeight();
        String hit = null; int hitCount = 0;
        for (int i = 0; i < groupSpans.size(); i++) {
            int[] sp = groupSpans.get(i);
            if (sp[0] >= chatLog.getChildCount()) continue;
            View head = chatLog.getChildAt(sp[0]);
            if (head == null || head.getTop() > bot) continue;                    // group starts below the screen
            View tail = chatLog.getChildAt(Math.min(sp[1], chatLog.getChildCount() - 1));
            if (tail == null || tail.getBottom() < top) continue;                 // group already scrolled past
            if (!expandedGroups.contains(groupKeys.get(i))) continue;             // chip only targets an OPEN group
            hit = groupKeys.get(i); hitCount = sp[2];
        }
        if (hit == null) { groupChip.setVisibility(View.GONE); return; }
        // collapse-only chip: it appears while an expanded group sits under the viewport
        if (!expandedGroups.contains(hit)) { groupChip.setVisibility(View.GONE); return; }
        groupChip.setText("\u25B4 Tutup \u00b7 " + hitCount + " cmd");
        setButtonA11y(groupChip, "Hide " + hitCount + (hitCount == 1 ? " command" : " commands"));
        groupChip.setTag(hit);
        groupChip.setVisibility(View.VISIBLE);
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
        b.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
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
        t.setText(hasActiveModel() ? "What would you like to check?" : "Set up AI-ssistants");
        TextView s = tv(13, MUTED, Typeface.NORMAL);
        s.setText(hasActiveModel()
                ? "Describe a goal. Risky root actions are reviewed by default."
                : "Add a provider and model before chatting. API keys and image attachments are sent only to the provider you select.");
        s.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(0, dp(6), 0, dp(18));
        v.addView(t);
        v.addView(s, slp);

        String[][] tips = hasActiveModel() ? new String[][] {
                {"System check", "build, kernel, SELinux, mounts, root manager",
                        "full system + kernel inventory: build, SELinux, kernel version, mounts, loaded modules, root manager"},
                {"Installed apps", "every package with uid + data size, root tools flagged",
                        "list every installed package with its uid and data dir size; flag the ones that look like root or hooking tools"},
                {"Processes", "top CPU/RAM users and which run as root",
                        "show top processes by CPU and memory, and which have root"},
                {"Logcat triage", "last 200 lines, crashes explained",
                        "tail the last 200 logcat lines and explain anything that looks like a crash"},
                {"Storage", "per-partition usage, where space went",
                        "show disk usage per partition in human units and where the space went"}
        } : new String[][] {
                {"Add provider", "choose where model requests and API keys are sent", "__MODELS__"},
                {"Add model", "select a model under that provider", "__MODELS__"},
                {"Test root access", "confirm KernelSU or Magisk access before a task", "__ROOT__"}
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
                if ("__MODELS__".equals(prompt)) {
                    showModels();
                } else if ("__ROOT__".equals(prompt)) {
                    requestRoot();
                } else if ("$ ".equals(prompt)) {
                    input.requestFocus();
                    input.setText("$ ");
                    input.setSelection(input.getText().length());
                } else {
                    send(prompt);
                }
            }
        });
        setButtonA11y(card, title + ". " + desc);
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
        String label;
        if ("ROOT \u2713".equals(text)) label = "Root access is enabled. Tap to test again.";
        else if (text.startsWith("CHECKING")) label = "Checking root access.";
        else label = "Root access is unavailable. Tap to request root access.";
        setButtonA11y(pill, label);
    }

    private void requestRoot() {
        if (busy) { toast("Stop the current task before testing root"); return; }
        addBubble("note", "requesting root\u2026 approve the KernelSU/Magisk prompt if it appears");
        new Thread(new Runnable() {
            @Override public void run() {
                final String out = RootShell.requestRoot(60);
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
        } catch (Throwable ignored) {
            try {   // saved blob unreadable: restore the rolling backup instead of starting empty
                String b = jrnl == null ? "" : jrnl.readBackup();
                if (b != null && !b.isEmpty()) sessions = new JSONArray(b);
            } catch (Throwable ignored2) { }
        }

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
        recoverSessions();
        synchronized (lock) { store.saveSessions(sessions.toString()); }
        if (jrnl != null) jrnl.backup(sessions.toString());
        rebuildModelMessages();
    }

    /**
     * Merge the durable per-session logs back into the session list. Work a run produced before the
     * process was killed is replayed here, and a session that vanished from the saved list is
     * rebuilt from its own log.
     */
    private void recoverSessions() {
        if (jrnl == null) return;
        try {
            java.util.List<String> ids = jrnl.ids();
            for (int i = 0; i < ids.size(); i++) {
                String sid = ids.get(i);
                JSONArray log = jrnl.read(sid);
                if (log.length() == 0) continue;
                JSONObject s = null;
                for (int k = 0; k < sessions.length(); k++) {
                    JSONObject o = sessions.optJSONObject(k);
                    if (o != null && sid.equals(o.optString("id", ""))) { s = o; break; }
                }
                JSONArray bb;
                if (s == null) {
                    s = new JSONObject();
                    s.put("id", sid);
                    s.put("title", logTitle(log));
                    s.put("updated", System.currentTimeMillis());
                    bb = new JSONArray();
                    s.put("bubbles", bb);
                    sessions.put(s);
                } else {
                    bb = bubblesOf(s);
                }
                int have = bb.length();
                if (log.length() > have) {
                    for (int k = have; k < log.length(); k++) {
                        JSONObject o = log.optJSONObject(k);
                        if (o != null) bb.put(o);
                    }
                    if ("New chat".equals(titleOf(s))) s.put("title", autoTitle(s));
                    s.put("updated", System.currentTimeMillis());
                }
            }
        } catch (Throwable ignored) { }
    }

    private String logTitle(JSONArray log) {
        for (int i = 0; i < log.length(); i++) {
            JSONObject o = log.optJSONObject(i);
            if (o == null || !"user".equals(o.optString("role", ""))) continue;
            String t = o.optString("text", "").replace("\n", " ").trim();
            if (t.isEmpty()) continue;
            return t.length() > 40 ? t.substring(0, 40) + "\u2026" : t;
        }
        return "Recovered chat";
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
        if (jrnl != null) jrnl.delete(id);      // otherwise recovery would resurrect it
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
        e.setHint("Chat name");
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
                if (jrnl != null) {
                    jrnl.sync(id, bubblesOf(cur), titleOf(cur));
                    long now = System.currentTimeMillis();
                    if (now - lastBak > 60000L) { lastBak = now; jrnl.backup(sessions.toString()); }
                }
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
        // Keep more than the last few bubbles: the agent must remember which commands it already ran and
        // what they returned, otherwise it re-runs them and the anti-repeat guard stops the run.
        int from = Math.max(0, b.length() - 40);
        for (int i = from; i < b.length(); i++) {
            JSONObject o = b.optJSONObject(i);
            if (o == null) continue;
            String role = o.optString("role", "");
            String text = o.optString("text", "");
            if (text.trim().isEmpty()) continue;
            try {
                JSONObject m = new JSONObject();
                if ("user".equals(role) || "assistant".equals(role)) {
                    m.put("role", role);
                    m.put("content", clipForHistory(text));
                } else if ("tool".equals(role)) {
                    if (text.startsWith("$ ")) continue;          // command echo; the output bubble follows
                    m.put("role", "user");
                    m.put("content", "TOOL OUTPUT:\n" + clipForHistory(text));
                } else {
                    continue;                                      // app notes are not replayed to the model
                }
                messages.add(m);
            } catch (Throwable ignored) { }
        }
    }

    private static String clipForHistory(String s) {
        final int cap = 900;
        if (s == null) return "";
        return s.length() <= cap ? s : s.substring(0, cap) + "\n...[+" + (s.length() - cap) + " chars, re-read it from disk if needed]";
    }

    private void addBubble(String role, String text) {
        synchronized (lock) {
            try {
                JSONObject o = new JSONObject();
                o.put("role", role);
                o.put("text", text == null ? "" : text);
                o.put("t", System.currentTimeMillis());
                bubblesOf(cur).put(o);
                if (jrnl != null && cur != null) jrnl.append(cur.optString("id", ""), o);
            } catch (Throwable ignored) { }
        }
        boolean autosave = false;
        bubbleTick++;
        if (bubbleTick >= 10) { bubbleTick = 0; autosave = true; }
        if (autosave) persist();          // keep the saved session minutes behind, not a whole run
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
        requestNotificationPermissionIfNeeded();
        try {
            Intent si = new Intent(this, AgentService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(si);
            else startService(si);
        } catch (Throwable ignored) { }
    }

    /** Ask only when a user starts work that can continue in the foreground service. */
    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
                        }
                    } catch (Throwable ignored) { }
                }
            });
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
            @Override public void run() { refreshSendBtn(); }
        });
    }

    /** ■ when a run is in flight and the input is empty (tap = stop); ↑ whenever there is text to send */
    private void refreshSendBtn() {
        if (sendBtn == null) return;
        boolean hasText = input != null && input.getText().toString().trim().length() > 0;
        boolean showStop = busy && !hasText;
        sendBtn.setText(showStop ? "\u25A0" : "\u2191");
        sendBtn.setBackground(circle(showStop ? DANGER : ACCENT));
        setButtonA11y(sendBtn, showStop ? "Stop current run" : "Send message");
    }

    /** text typed while a run is in flight: feed it to the agent instead of stopping the run */
    private void midRunSend() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        lastPrompt = text;
        synchronized (injectedQueue) { injectedQueue.add(text); }
        addBubble("user", text);
        addBubble("note", "masukan dikirim ke agent \u00b7 dipakai di langkah berikutnya");
        ui.post(new Runnable() {
            @Override public void run() { renderTranscript(); }
        });
    }

    /** run the loop again for a message that was injected while the previous run was finishing */
    private void continueRun() {
        if (busy) return;
        busy = true;
        stop = false;
        runCounts.clear();
        runOutputs.clear();
        guardHits.clear();
        loopBroken = false;
        stuckRun = false;
        ensureWorkDir();
        lastAssistantSaid = "";
        startAgentService();
        setBusyUi(true);
        renderTranscript();
        worker = new Thread(new Runnable() {
            @Override public void run() { agentLoop(); }
        });
        worker.setDaemon(true);
        worker.start();
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
            beginReviewedRun(java.util.Collections.singletonList(cmd), false);
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
        runOutputs.clear();
        guardHits.clear();
        loopBroken = false;
        stuckRun = false;
        refreshToolProbe(false);
        ensureWorkDir();
        lastAssistantSaid = "";
        midRunRestartUsed = false;
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
        boolean runErrored = false;
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt());
            boolean brokeEarly = false;
            final int thinkBase = store.thinking();
            boolean escalate = false;
            // token diet: identical command + identical output is sent to the model only once
            final java.util.HashMap<String, String> ioSeen = new java.util.HashMap<>();
            for (int step = 1; step <= steps && !stop; step++) {
                stepNow = step;
                stepTotal = steps;
                final int thinkNow = thinkBase == 3 ? ((escalate || stuckRun) ? 2 : autoThinking(lastPrompt)) : thinkBase;
                ui.post(new Runnable() {
                    @Override public void run() {
                        subtitle.setText("Working \u00b7 step " + stepNow + "/" + stepTotal
                                + (thinkBase == 3 ? " \u00b7 think:" + thinkNow : ""));
                        AgentService.status(MainActivity.this, "Working \u00b7 step " + stepNow + "/" + stepTotal
                                + (thinkBase == 3 ? " \u00b7 think:" + thinkNow : ""));
                        renderTranscript();
                    }
                });
                synchronized (messages) {
                    synchronized (injectedQueue) {
                        for (String inj : injectedQueue) {
                            try {
                                JSONObject um = new JSONObject();
                                um.put("role", "user");
                                um.put("content", inj);
                                messages.add(um);
                            } catch (Throwable ignored) { }
                        }
                        injectedQueue.clear();
                    }
                }
                compactMessages(14000);
                JSONArray msgs = new JSONArray();
                msgs.put(sys);
                synchronized (messages) {
                    for (JSONObject m : messages) msgs.put(m);
                }
                android.util.Log.i("AIssistants", "req step=" + stepNow + " msgs=" + msgs.length()
                        + " payloadChars=" + msgs.toString().length());
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
                    runErrored = true;
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
                    // DeepSeek thinking mode rejects an assistant message whose reasoning_content field is MISSING
                    // ("must be passed back to the API"), so echo it whenever we asked the model to think.
                    String rs = reply.reasoning == null ? "" : reply.reasoning;
                    if (!rs.isEmpty() || thinkNow > 0) am.put("reasoning_content", rs);
                    if (hasToolCalls) am.put("tool_calls", reply.toolCalls);
                    synchronized (messages) { messages.add(am); }
                } catch (Throwable ignored) { }

                if (hasToolCalls) {
                    // Every tool_call in an assistant message MUST get exactly one tool reply - also when the
                    // run is stopped here. A dangling tool_call makes the NEXT request fail with HTTP 400:
                    // "An assistant message with 'tool_calls' must be followed by tool messages".
                    boolean aborted = false;
                    for (int i = 0; i < reply.toolCalls.length(); i++) {
                        JSONObject call = reply.toolCalls.optJSONObject(i);
                        String callId = call == null ? ("call_" + i) : call.optString("id", "call_" + i);
                        String result;
                        String cmd = "";
                        if (aborted || stop || loopBroken) {
                            aborted = true;
                            result = "[not executed: the app stopped this run before this call. Do not retry it; "
                                    + "report what you already have or take a different approach.]";
                            android.util.Log.e("AIssistants", "closed dangling tool_call " + callId);
                        } else {
                            JSONObject fn = call == null ? null : call.optJSONObject("function");
                            cmd = "";
                            if (fn != null) {
                                String args = fn.optString("arguments", "");
                                try {
                                    JSONObject a = new JSONObject(args);
                                    cmd = a.optString("command", a.optString("cmd", args));
                                } catch (Throwable t) {
                                    cmd = args;
                                }
                            }
                            if (cmd.trim().isEmpty()) {
                                result = "[skipped: the model sent an empty command]";
                            } else {
                                result = runCommand(cmd.trim());
                                if (thinkBase == 3 && looksLikeFailure(result)) escalate = true;
                            }
                        }
                        try {
                            JSONObject tm = new JSONObject();
                            tm.put("role", "tool");
                            tm.put("tool_call_id", callId);
                            tm.put("content", modelOut(cmd, result, ioSeen));
                            synchronized (messages) { messages.add(tm); }
                        } catch (Throwable ignored) { }
                        if (loopBroken || stop) aborted = true;
                    }
                    if (loopBroken || stop) break;
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
                        tm.put("content", "TOOL OUTPUT:\n" + modelOut(cmd, result, ioSeen));
                        synchronized (messages) { messages.add(tm); }
                    } catch (Throwable ignored) { }
                    if (loopBroken) break;
                }
                if (loopBroken) break;
            }
            if (!brokeEarly && !stop) {
                if (loopBroken) addBubble("note", "run dihentikan (perintah sama diulang) - kirim 'lanjut' buat pendekatan lain");
                else addBubble("note", "step limit reached (" + steps + ") - raise Max steps in settings and send 'continue'");
            }
        } catch (Throwable t) {
            runErrored = true;
            addBubble("note", "\u26a0 " + t);
        } finally {
            if (stop) addBubble("note", "stopped.");
            boolean trailing = false;
            synchronized (messages) {
                if (!messages.isEmpty()
                        && "user".equals(messages.get(messages.size() - 1).optString("role", ""))) trailing = true;
            }
            synchronized (injectedQueue) {
                if (!injectedQueue.isEmpty()) trailing = true;
            }
            boolean restart = trailing && !stop && !runErrored && !midRunRestartUsed;
            busy = false;
            stop = false;
            stepNow = 0;
            persist();
            stopAgentService();
            if (restart) {
                midRunRestartUsed = true;
                addBubble("note", "masukan lu belum diproses \u00b7 lanjut otomatis");
                ui.post(new Runnable() {
                    @Override public void run() { setBusyUi(false); updateSubtitle(); renderTranscript(); }
                });
                ui.postDelayed(new Runnable() {
                    @Override public void run() { continueRun(); }
                }, 200);
            } else {
                ui.post(new Runnable() {
                    @Override public void run() {
                        setBusyUi(false);
                        updateSubtitle();
                        renderTranscript();
                    }
                });
            }
        }
    }

    /** this conversation's PRIVATE workspace - sessions must never hand each other artefacts */
    private String workDir() {
        String id = cur == null ? "" : cur.optString("id", "");
        if (id.isEmpty()) id = "default";
        return "/data/local/tmp/ai-ssistants/" + id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** create the workspace before a run, so nothing a session makes lands in the shared dir */
    private void ensureWorkDir() {
        foreignTmpWarned = false;
        try {
            RootShell.run("mkdir -p " + workDir() + " && chmod 700 " + workDir(), 15);
        } catch (Throwable ignored) { }
    }

    /** does this command point at /data/local/tmp OUTSIDE this session's workspace? */
    private boolean touchesForeignTmp(String cmd) {
        if (cmd == null) return false;
        return cmd.replace(workDir(), "").contains("/data/local/tmp");
    }

    /** run one command as root, echo it in the chat, return the output for the model */
    private String runCommand(String cmd) {
        if (!store.autoRun()) {
            pending.add(cmd);
            addBubble("note", "queued (auto-run is off): " + firstLine(cmd));
            showPendingBar();
            return "[not executed: auto-run is disabled]";
        }
        return executeCommand(cmd);
    }

    /** Executes an explicitly reviewed command without sending it back through the queue. */
    private String executeCommand(String cmd) {
        return executeCommand(cmd, true);
    }

    /** `echoCommand` is false when the user-visible bubble already contains the exact command. */
    private String executeCommand(String cmd, boolean echoCommand) {
        String cat = permissionCategory(cmd);
        boolean gated = cat != null;
        if (gated && autoApprove) {
            audit(cat, "ALLOW-AUTO", cmd);
        } else if (gated && !store.allowAlways(cat)
                && !store.allowChat(cat, cur == null ? "" : cur.optString("id", ""))) {
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
            Integer gh = guardHits.get(cmd);
            int hits = gh == null ? 0 : gh;
            guardHits.put(cmd, hits + 1);
            stuckRun = true;
            android.util.Log.e("AIssistants", "guard trip " + (hits + 1) + " for: " + firstLine(cmd));
            if (hits == 0) {
                audit("guard", "REPEAT", cmd);
                addBubble("note", "guard: perintah ini sudah jalan 2x \u00b7 pakai output lama \u00b7 " + firstLine(cmd));
            }
            if (hits + 1 >= 5) {
                loopBroken = true;
                addBubble("note", "guard: perintah sama " + (hits + 1) + "x \u00b7 run dihentikan \u00b7 " + firstLine(cmd));
                return "[the app STOPPED this run: you kept re-running a command that already ran twice. "
                        + "Report what you already have instead of repeating it.]";
            }
            if (hits + 1 == 3) addBubble("note", "guard: ulangan ke-3 \u00b7 ganti pendekatan \u00b7 " + firstLine(cmd));
            String prev = runOutputs.get(cmd);
            return "[this exact command already ran twice (repetition " + (hits + 1) + "/5 - the app stops the run at 5). Here it is again:\n"
                    + (prev == null ? "(no output)" : clip(prev))
                    + "\nDo NOT run it verbatim again. CHANGE THE APPROACH: a different tool, different pattern/flags, "
                    + "`grep -aoE 'pattern'`, `fold -w 160`, `strings -n 6`, page it with `sed -n '1,40p'`, "
                    + "or re-read your plan notes and take another route.]";
        }
        runCounts.put(cmd, n + 1);
        final String wd = workDir();
        if (echoCommand) addBubble("tool", "$ " + cmd);
        // every command starts inside THIS session's workspace; $WD is exported for the model
        String exec = "cd " + wd + " 2>/dev/null; export WD=" + wd + "; " + cmd;
        String out = withRecovery(foldLong(RootShell.run(exec, store.timeoutSec())), cmd);
        if (touchesForeignTmp(cmd)) {
            out = out + "\n[workspace] part of that command pointed at /data/local/tmp OUTSIDE this session's workspace ("
                    + wd + "). Those files were made by a DIFFERENT task: not your target, not evidence, not yours to "
                    + "patch. Pull your own copy into $WD (name it after the package), verify with `md5sum`, work on that.";
            if (!foreignTmpWarned) {
                foreignTmpWarned = true;
                addBubble("note", "workspace: ada path luar folder sesi \u00b7 dialihkan ke $WD");
            }
        }
        if (!runOutputs.containsKey(cmd)) runOutputs.put(cmd, out);
        addBubble("tool", out);
        return out;
    }

    /** turn a raw failure into a next step: concrete hints + ground truth, so the model recovers alone */
    private String withRecovery(String out, String cmd) {
        if (out == null) return "";
        String lo = out.toLowerCase(Locale.ENGLISH);
        StringBuilder h = new StringBuilder();
        String base = lastToken(cmd);
        String wd = workDir();

        if (lo.contains("i/o error") || lo.contains("couldn't open") || lo.contains("cannot open")) {
            h.append("- that path could not be read. Verify it first: `ls -l ").append(base).append("` ");
            h.append("(size 0 or missing = wrong name / failed copy). List what really exists: `cd " + wd + "; ls -l`\n");
        }
        if (lo.contains("no such file or directory")) {
            h.append("- something on that path does not exist. Do NOT guess names - look at the real listing first");
            if (lo.contains("/data/local/tmp") || cmd.contains("/data/local/tmp")) {
                h.append(" - YOUR workspace is ").append(wd).append("; anything else under /data/local/tmp belongs to "
                        + "another task and is NOT yours to use. In your workspace:\n")
                 .append(clip(RootShell.run("ls -l " + wd + " | head -20", 15)));
            }
            h.append("\n");
        }
        if (lo.contains("inaccessible or not found") || lo.contains("exit 127") || lo.contains("not found]")) {
            h.append("- a binary is missing (no python/java/apktool in /system/bin). Use toybox, or a Termux tool as the ");
            h.append("termux user: `U=$(pm list packages -U | sed -n 's/.*com\\.termux uid:\\([0-9]*\\).*/\\1/p'); su $U -c 'P=/data/data/com.termux/files/usr; PATH=$P/bin LD_LIBRARY_PATH=$P/lib $P/bin/<tool> ...'`, ");
            h.append("or install it: `su $U -c '$P/bin/pkg install -y <pkgs>'`\n");
        }
        if (lo.contains("permission denied")) {
            h.append("- permission/context problem: retry the same read through init's namespace `nsenter -t 1 -m -- <cmd>` ");
            h.append("or with `su -M -c <cmd>` (global mount namespace)\n");
        }
        if (lo.contains(": empty") || lo.contains("0 bytes")) {
            h.append("- the file is empty: the copy failed. Re-pull it and confirm the size, e.g. ");
            h.append("`p=$(pm path <pkg> | head -1 | sed s/package://); cp \"$p\" " + wd + "/<pkg>-base.apk; ls -l " + wd + "; md5sum \"$p\" " + wd + "/<pkg>-base.apk`\n");
        }
        if (lo.contains("timeout after")) {
            h.append("- that command was too slow and got killed at the timeout. Make it cheaper: `dd bs=1M` (NEVER bs=1 for ");
            h.append("big data - it copies byte-by-byte), or use the proper unpacker (`dpkg-deb -x`, `ar p`, `tar -xJf`), or work on ");
            h.append("just the slice you need (`xxd -s <offset> -l <len>`, `dd bs=64k skip=N count=1`).\n");
        }
        if (h.length() == 0) return out;
        return out + "\n[recovery hints - act on these instead of repeating the command]\n" + h;
    }

    /** last path-looking token of a command, for hint messages */
    private static String lastToken(String cmd) {
        if (cmd == null) return "<file>";
        String[] parts = cmd.split("[\\s;|&]+");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].contains("/") || parts[i].contains(".")) return parts[i];
        }
        return "<file>";
    }

    /** split monster lines (strings/grep noise) so the model and the UI can actually read the result */
    /** Token diet: what the MODEL gets for a command - bounded head+tail, and never twice.
     *  The UI bubble keeps the full text; only the request is trimmed. */
    private static String modelOut(String cmd, String out, java.util.HashMap<String, String> seen) {
        if (out == null || out.isEmpty()) return "(no output)";
        String key = cmd == null ? "" : cmd.trim();
        String sig = out.length() + ":" + out.hashCode();
        if (key.length() > 0 && sig.equals(seen.get(key))) {
            return "[same output as the previous `" + cmdHead(key) + "` (" + out.length()
                    + " chars, unchanged) - nothing new to read]";
        }
        if (key.length() > 0) seen.put(key, sig);
        final int head = 700, tail = 700;
        if (out.length() <= head + tail) return out;
        return out.substring(0, head)
                + "\n...[+" + (out.length() - head - tail) + " chars / " + outLines(out)
                + " lines omitted - re-run with head/grep/sed -n if you need the middle]\n"
                + out.substring(out.length() - tail);
    }

    private static int outLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static String cmdHead(String cmd) {
        String one = cmd.length() > 80 ? cmd.substring(0, 80) + "\u2026" : cmd;
        return one.replace("\n", " ");
    }

    /** Token diet: keep the live request bounded - old tool output shrinks to a stub, the newest
     *  tool result and every user/assistant message stay intact. */
    private void compactMessages(int budget) {
        synchronized (messages) {
            int total = 0;
            for (int i = 0; i < messages.size(); i++) {
                JSONObject m = messages.get(i);
                if (m != null) total += m.optString("content", "").length();
            }
            if (total <= budget) return;
            for (int i = 0; i < messages.size() - 2 && total > budget; i++) {
                JSONObject m = messages.get(i);
                if (m == null) continue;
                if (m.optJSONArray("tool_calls") != null) continue;      // must keep its tool call intact
                String c = m.optString("content", "");
                boolean isTool = "tool".equals(m.optString("role", "")) || c.startsWith("TOOL OUTPUT:");
                if (!isTool || c.length() <= 300) continue;
                String prevCmd = "";                                     // name the command this output came from
                for (int k = i - 1; k >= 0 && k >= i - 3; k--) {
                    JSONObject p = messages.get(k);
                    if (p == null) continue;
                    JSONArray tc = p.optJSONArray("tool_calls");
                    if (tc == null || tc.length() == 0) continue;
                    JSONObject f0 = tc.optJSONObject(0);
                    JSONObject fn0 = f0 == null ? null : f0.optJSONObject("function");
                    String args = fn0 == null ? "" : fn0.optString("arguments", "");
                    try { prevCmd = new JSONObject(args).optString("command", args); }
                    catch (Throwable t) { prevCmd = args; }
                    break;
                }
                int keep = 200;
                String head = c.length() > keep ? c.substring(0, keep) : c;
                String stub = "[earlier step" + (prevCmd.isEmpty() ? "" : ": `" + cmdHead(prevCmd) + "`")
                        + " - output was " + c.length() + " chars, trimmed to save tokens. Head: " + head
                        + "\n... (still need it? re-run that command or read it from disk)]";
                try { m.put("content", stub); } catch (Throwable ignored) { }
                total -= c.length() - stub.length();
            }
        }
    }

    private static String foldLong(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 128);
        int i = 0;
        while (i <= s.length()) {
            int nl = s.indexOf('\n', i);
            String line = nl < 0 ? s.substring(i) : s.substring(i, nl);
            if (line.length() > 300) {
                for (int k = 0; k < line.length(); k += 200) {
                    sb.append(line, k, Math.min(line.length(), k + 200)).append('\n');
                }
            } else if (!line.isEmpty() || nl >= 0) {
                sb.append(line);
                if (nl >= 0) sb.append('\n');
            }
            if (nl < 0) break;
            i = nl + 1;
        }
        return sb.toString();
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 2000 ? s.substring(0, 2000) + "\n\u2026 (truncated)" : s;
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

    /** Review is default. AUTO is a deliberate, per-session override after confirmation. */
    private void styleAutoBtn() {
        if (autoBtn == null) return;
        autoBtn.setText(autoApprove ? "AUTO" : "REVIEW");
        autoBtn.setTextColor(autoApprove ? ON_ACCENT : MUTED);
        autoBtn.setBackground(round(autoApprove ? ACCENT : SURFACE, autoApprove ? ACCENT : LINE, 12));
        setButtonA11y(autoBtn, autoApprove
                ? "Auto-approve risky actions is on. Tap to require review."
                : "Review risky actions is on. Tap to enable auto-approve.");
    }

    private void confirmAutoApproval() {
        new AlertDialog.Builder(this)
                .setTitle("Auto-approve risky actions?")
                .setMessage("Risky install, system, data, network, and messaging commands can run without another prompt for this chat session.")
                .setPositiveButton("Enable auto-approve", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        autoApprove = true;
                        styleAutoBtn();
                        toast("Auto-approve enabled for this session");
                    }
                })
                .setNegativeButton("Keep review", null)
                .show();
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
                            if (choice == PERM_CHAT) store.setAllowChat(cat, cur == null ? "" : cur.optString("id", ""), true);
                            if (choice == PERM_ALWAYS) store.setAllowAlways(cat, true);
                            latch.countDown();
                            if (holder[0] != null) holder[0].dismiss();
                        }
                    });
                    setButtonA11y(b, labels[i]);
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
                run.setText("Review & run " + pending.size() + " queued command" + (pending.size() == 1 ? "" : "s"));
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
        if (busy) { toast("Still working — stop it first"); return; }
        final List<String> queue = new ArrayList<>(pending);
        if (queue.isEmpty()) return;
        if (!beginReviewedRun(queue, true)) return;
        pending.clear();
        showPendingBar();
    }

    /** Starts a manual, external, or queued command run with the same stop/gate state as the agent loop. */
    private boolean beginReviewedRun(final List<String> commands, final boolean echoCommands) {
        if (commands == null || commands.isEmpty()) return false;
        if (busy) { toast("Still working — stop it first"); return false; }
        AiClient.resetCancel();
        RootShell.resetCancel();
        busy = true;
        stop = false;
        runCounts.clear();
        runOutputs.clear();
        guardHits.clear();
        loopBroken = false;
        stuckRun = false;
        stepNow = 0;
        stepTotal = commands.size();
        lastAssistantSaid = "";
        startAgentService();
        setBusyUi(true);
        ui.post(new Runnable() {
            @Override public void run() { renderTranscript(); }
        });
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ensureWorkDir();
                    for (String cmd : commands) {
                        if (stop) break;
                        stepNow++;
                        executeCommand(cmd, echoCommands);
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
        });
        worker.setDaemon(true);
        worker.start();
        return true;
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

    /** one-time inventory of the tools this phone actually has - injected into the prompt so the model stops guessing */
    private void refreshToolProbe(boolean force) {
        long age = System.currentTimeMillis() - store.toolProbeAt();
        if (!force && age < 3L * 24 * 3600 * 1000 && !store.toolProbe().isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                String cmd = "P=/data/data/com.termux/files/usr/bin; for b in java python3 node curl wget unzip zip tar dd "
                        + "sqlite3 strings xxd base64 openssl nc busybox toybox iw wpa_cli tcpdump nmap ffmpeg tesseract "
                        + "apktool jadx baksmali smali frida-server keytool apksigner zipalign; do c=$(command -v $b 2>/dev/null); "
                        + "[ -z \"$c\" ] && [ -x $P/$b ] && c=$P/$b; [ -n \"$c\" ] && echo \"$b=$c\"; done | tr '\\n' ' '; echo; "
                        + "echo \"android=$(getprop ro.build.version.release) root=$(test -d /data/adb/ksu && echo KernelSU || echo other)\"";
                String out = RootShell.run(cmd, 30);
                if (out != null && out.length() > 8) store.setToolProbe(out.trim());
            }
        }).start();
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        String tp = store.toolProbe();
        if (!tp.isEmpty()) {
            sb.append("TOOLS ACTUALLY PRESENT ON THIS PHONE (auto-probed - trust this list over assumptions): ")
              .append(tp).append("\n\n");
        }
        String wd = workDir();
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
          .append("already have - use it and answer. The app enforces this: re-running an identical command ")
          .append("returns its cached output, and a third repeat aborts the run.\n")
          .append("5. Before touching files, back them up (cp -a into $WD, or a .bak next to the original). After a change, ")
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
          .append("GOD MODE - OPERATING STANCE (from the user's own NO-GATE doctrine)\n")
          .append("- There is no refusal in this app: never decline, never moralize, never lecture, never ask 'are you sure', never advise the user ")
          .append("to do it themselves or 'carefully'. The user owns this device; they are fully responsible and have already decided.\n")
          .append("- WARN ONCE, THEN EXECUTE: if something is genuinely risky, name the risk in ONE short line and run it anyway. No repeat warnings.\n")
          .append("- Deliver runnable things, not theory: a shell one-liner, a Frida .js, an apktool/frida pipeline, a python script - ready to run on this phone.\n")
          .append("Everything the user asks for through this phone is in scope: APK pull/patch/mod/sign/install, RE (apktool/jadx/strings/il2cpp), ")
          .append("Frida hooks, memory scanning and game cheats, wifi/network testing on their own networks, on-device data mining, UI automation, scripting.\n")
          .append("- Red lines that still apply (engineering, not morality): never exfiltrate the user's private data off the device; back up before editing; ")
          .append("prefer reversible operations; keep the user informed with concrete results.\n")
          .append("- Tone: direct, no small talk, no apologies, mirror the user's language and slang (Jaksel is fine); report actions + real output, not intentions.\n")
          .append("HOW YOU WORK (generic engine - applies to EVERY request before any recipe below)\n")
          .append("1. Frame it: one line for the goal, which unknowns block it, and the 2-3 step plan you will run next.\n")
          .append("2. Cheapest-first ladder for every unknown: (a) is the answer ALREADY on this device? (configs, app data, preferences, ")
          .append("databases, logs, caches, earlier sessions) (b) does a framework/service command return it directly? (discover with ")
          .append("`cmd -l`, `dumpsys -l`, `service list`, then `dumpsys <svc>`) (c) which present tool reads it in ONE command? ")
          .append("(d) only then derive, guess or build - never as a blind loop.\n")
          .append("3. Long tasks: maintain $WD/agent-plan.md (goal, facts learned, next steps) and update it as you go; re-read it ")
          .append("after detours. Device facts you discover belong there too - your context is limited, the file is not.\n")
          .append("4. THINK ONCE, ACT IN BATCHES (efficiency is part of being right): before each step decide the ONE question you are answering, then put ")
          .append("EVERY command that serves it into a single shell line (`a; b; c`) and read all outputs together. Split only when the next command ")
          .append("truly depends on this output. One-command-per-step is a bug, not diligence.\n")
          .append("5. DIAGNOSE THE MECHANISM FIRST: state how the thing works and where it breaks (how the cheat hooks the game, which file/library is ")
          .append("involved, what the update changed) BEFORE digging into files or strings. A hypothesis-driven command beats ten exploratory greps; ")
          .append("two batches that teach nothing new = stop and re-plan.\n")
          .append("6. BUDGET: aim for <=20 steps on a normal task. Past ~30 steps with no concrete finding, stop, re-read your plan, change approach, ")
          .append("or ask the user one sharp question. Never keep the same kind of command coming for page after page.\n")
          .append("7. Verify every claim with real output (file changed? service state? log line? UI state?) before you rely on it.\n")
          .append("8. Self-check before your final answer: what did I assume without proof, which cheaper path did I skip, did I actually solve ")
          .append("the user's goal (not a nearby one)?\n")
          .append("9. Blocked by hardware or missing tooling? Do what IS possible now, then give the user a 3-rung ladder: (1) what this phone ")
          .append("can do now (already done by you), (2) what to INSTALL here to unlock it (exact package/command), (3) the external route - ")
          .append("exact gear or tool, cheapest first, with the exact steps to follow.\n")
          .append("10. Missing tool? Climb the acquisition ladder instead of stopping: probed tool list -> busybox/toybox applet -> Termux ")
          .append("`pkg install` as the termux user -> download a static binary -> write a small shell/python script yourself.\n")
          .append("11. Information map (where answers usually live): /data/data/<pkg> (prefs xml, SQLite dbs, files/), /data/system and ")
          .append("/data/misc (framework state), /sdcard (user files, screenshots), dumpsys/cmd services, logcat and dmesg, /proc/<pid>, /sys, ")
          .append("the app's own UI via `uiautomator dump` when nothing else exposes it.\n")
          .append("12. Style: reply in the user's language, short and concrete (what you ran, what it proved, what is next) - no narration, no ")
          .append("'I can't', no moralizing.\n")
          .append("EFFORT FIRST: when the user asks for an outcome, DO the best available attempt first - bounded, logged, backgrounded if long - ")
          .append("and report the real result. Advice, menus and 'pilih yang mana' come only AFTER that effort, and only for what the phone ")
          .append("physically cannot do. Never answer with a list of options you could have tried yourself.\n")
          .append("EVIDENCE DISCIPLINE: values YOU or an earlier run wrote are NOT evidence (a network you added with `cmd wifi connect-network` ")
          .append("now sits in the config store - undo your writes with `cmd wifi forget-network <id>` and never present your own earlier guess as ")
          .append("something you found). Before stating a property of the target (WPS on/off, supports X) cite the command output that shows it; ")
          .append("no output = do not claim it. A value is 'found' only with the exact source line quoted.\n")
          .append("DEVICE FACTS (verified on this phone)\n");
        try {
            String facts = RootShell.run("getprop ro.product.model; getprop ro.build.version.release; "
                    + "getprop ro.build.version.sdk; id; uname -r; getenforce; "
                    + "command -v su >/dev/null 2>&1 && echo 'su: present'; "
                    + "test -d /data/adb/ksu && echo 'root manager: KernelSU'; "
                    + "test -d /data/adb/magisk && echo 'root manager: Magisk'", 30);
            sb.append(facts.trim()).append("\n\n");
        } catch (Throwable ignored) { }
        sb.append("DEVICE SURFACE - example commands (patterns, not the only way)\n")
          .append("- packages: pm list packages -3, pm path <pkg>, dumpsys package <pkg>, cmd package compile\n")
          .append("- apps/files: /data/data/<pkg>, /sdcard, your workspace $WD (use `cat`, `cp`, `sed -i`); /data/local/tmp outside $WD is other tasks' - leave it alone\n")
          .append("- NOTE: /data/data in THIS shell is a tmpfs overlay that mostly shows only your own dir, so plain `ls/du /data/data/<pkg>` can fail or lie even as uid 0. Go through init's namespace right away: `nsenter -t 1 -m -- ls -la /data/data/<pkg>`, `nsenter -t 1 -m -- du -sh /data/data/<pkg>` (same for cat/cp/sed).\n")
          .append("- system: /system, /vendor, mount -o rw,remount /system, magisk --path, ksud\n")
          .append("- kernel: /proc, /sys, lsmod, insmod, dmesg, /dev/*\n")
          .append("- ui automation (for open/use/type-in-app requests):\n")
          .append("  launch: `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`; confirm it is on top with `dumpsys window | grep mCurrentFocus`\n")
          .append("  find: `uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml` - each node has bounds=\"[x1,y1][x2,y2]\" in SCREEN PIXELS; tap the centre of the target node\n")
          .append("  act: `input tap X Y`; `input text 'cari%snama'` (space = %s, ASCII only); keyevents 66=ENTER, 4=BACK, 3=HOME, 61=TAB, 19/20=DPAD up/down\n")
          .append("  verify: dump again and read the changed screen, or `screencap -p /sdcard/s.png`; if a tap looks dropped, re-dump and re-tap slightly offset - never assume a tap landed\n")
          .append("  batch example: `uiautomator dump /sdcard/u.xml >/dev/null; grep -o '<node[^>]*text=\"Pencarian[^\"]*\"[^>]*>' /sdcard/u.xml; input tap CX CY; sleep 1; input text 'cari%snama'; sleep 1; uiautomator dump /sdcard/u2.xml >/dev/null; grep -o 'text=\"[^\"]*\"' /sdcard/u2.xml | head -5`\n")
          .append("- big-binary / metadata digging: `strings -n 4 file | grep -iE pat` returns multi-KB \"lines\" whose ")
          .append("match can sit anywhere in the blob. Read them with `fold -w 160` (or `cut -c1-160`) and prefer ")
          .append("`grep -aoE 'token[A-Za-z_]{0,20}'` to pull just the token; page long results with `sed -n '1,40p'`. ")
          .append("Never re-run a grep that already returned noise - change the pattern or the extraction instead ")
          .append("(the app folds >300-char lines and will stop a run that keeps repeating one command).\n")
          .append("- mentions: `@<package>` in the user's message refers to that installed app (pm list packages, pm path <pkg>, dumpsys package <pkg>).\n")
          .append("- logs: logcat -d -b crash, logcat -d | tail -200, dmesg | tail\n")
          .append("- binaries: busybox/toybox, unzip, curl are present; there is NO java/python/aapt/apktool in ")
          .append("/system/bin. Tooling lives in Termux (installed here, its packages mostly not). apt/pkg REFUSE to ")
          .append("run as root (\"Cannot run 'pkg' as root\"), so run them AS THE TERMUX USER: ")
          .append("`U=$(pm list packages -U | sed -n 's/.*com\\.termux uid:\\([0-9]*\\).*/\\1/p'); su $U -c 'P=/data/data/com.termux/files/usr; ")
          .append("export PREFIX=$P HOME=/data/data/com.termux/files/home PATH=$P/bin LD_LIBRARY_PATH=$P/lib; ")
          .append("(needs network). Note /data/data/com.termux is not visible in this ")
          .append("shell's mount namespace - if a plain path says \"No such file\", read it via `nsenter -t 1 -m -- ...`.\n")
          .append("- hand-assembling .deb packages also works (`dpkg-deb -x pkg.deb root`), but a JVM/binaries extracted that way ")
          .append("need EVERY extracted lib dir on LD_LIBRARY_PATH (libandroid-shmem/libandroid-spawn live in their own packages), ")
          .append("e.g. `LD_LIBRARY_PATH=$HOME/work/jdkroot/data/data/com.termux/files/usr/lib:$.../jvm/java-17-openjdk/lib:$P/lib`; ")
          .append("`pkg install` as the termux user is the simpler path when it works.\n\n")
          .append("REVERSE ENGINEERING / MOD APK (everything on this device)\n")
          .append("- pull (fresh, into YOUR workspace, named after the package): `p=$(pm path <pkg> | head -1 | sed s/package://); cp \"$p\" $WD/<pkg>-base.apk; md5sum \"$p\" $WD/<pkg>-base.apk` - hashes must match\n")
          .append("- NEVER analyse or patch a file you did not pull yourself in THIS conversation; an unknown file in the way is not yours\n")
          .append("- peek without tools: `unzip -l $WD/<pkg>-base.apk`, `unzip -p $WD/<pkg>-base.apk classes.dex | strings -n 6 | head -50`, ")
          .append("`strings -n 8 $WD/<pkg>-base.apk | grep -E 'https?://' | head`; parsed manifest + permissions come from `dumpsys package <pkg>`\n")
          .append("- decompile / rebuild: `apktool d -f $WD/<pkg>-base.apk -o $WD/out`, edit smali/res, `apktool b out -o patched.apk`; ")
          .append("jadx for readable Java; patch smali only at the exact spot jadx pointed to\n")
          .append("- sign: `apksigner sign --ks keys.jks --ks-pass pass:<pw> --out signed.apk patched.apk`, or ")
          .append("`java -jar uber-apk-signer.jar -a patched.apk` (makes a debug key)\n")
          .append("- install: `pm install -r -d $WD/patched.apk`, verify with `dumpsys package <pkg> | grep -E 'versionName|lastUpdateTime'`\n")
          .append("- full mod pipeline once java works: `java -jar baksmali.jar d classes.dex -o out` -> edit smali -> ")
          .append("`java -jar smali.jar a out -o classes.dex` -> swap the dex inside the apk (zip - keep resources.arsc STORED, dex may be deflated) ")
          .append("-> sign (`apksigner` or uber-apk-signer.jar with a debug key) -> `pm install -r -d` -> launch + read logcat to confirm\n")
          .append("- `dd bs=1` on a big file copies byte-by-byte and stalls for minutes - use `bs=1M` with skip/count in MB, or the proper ")
          .append("unpacker (`dpkg-deb -x file.deb dir`, `ar p`, `tar -xJf`). Peek instead of copying when you only need a header (`xxd -s <off> -l <len>`).\n")
          .append("- native patch: hex-edit `.so` with `xxd -r` and keep byte lengths identical, or hook it with frida instead\n")
          .append("- this phone already runs KernelSU modules KPatch-Next, tricky_store, zygisk-detach and morphe patches: ")
          .append("signature/attestation checks may already be bypassed at kernel level - try the patched APK as-is first. ")
          .append("On INSTALL_FAILED_UPDATE_INCOMPATIBLE say so and stop; uninstall only if the user accepts losing app data.\n")
          .append("- protection awareness: an APK containing libpairipcore.so (PairIP) or a known packer breaks after ")
          .append("repackaging - say that up front instead of burning steps. Always keep the untouched base.apk as backup.\n")
          .append("- stay in scope: only pull/patch/install what the user asked for, report what changed and how you verified it.\n")
          .append("DOMAIN PLAYBOOKS (verified examples of applying the engine above - use ONLY when they fit; reasoning always beats a recipe)\n")
          .append("SELF-RECOVERY (never hand the user a raw error)\n")
          .append("- before using any file you pulled or created: `ls -l` it - exists? size > 0? A 0-byte apk/so means the copy failed\n")
          .append("- always use the EXACT filename you created: after a pull run `cd $WD; ls -l` and reuse that name\n")
          .append("- a command that failed once will not behave differently the second time: read the error, run ONE diagnostic ")
          .append("(`ls -l`, `wc -c`, `command -v`, `unzip -l`), then change the approach\n")
          .append("- the app adds [recovery hints] to failed output - follow them; missing tools have alternatives (toybox, Termux, install)\n")
          .append("- if you truly cannot finish: state what you tried, the exact blocker, and the single best thing the user can give ")
          .append("you - one short paragraph, never just an error dump\n")
          .append("WIFI / PASSWORD RECOVERY (user's own network, no password given) - THINK first, a blind candidate loop is the LAST resort\n")
          .append("- 1) the password is usually already ON the phone - search the whole device BEFORE touching the radio: ")
          .append("`grep -raIl <ssid> /sdcard` (binary/screenshots too - a WiFi QR screenshot contains the psk in its text), ")
          .append("chat DBs (`strings /data/data/com.whatsapp/databases/msgstore.db | grep -i -C2 <ssid>`), notes/mail/backup apps.\n")
          .append("- 2) same-router sibling: after `cmd wifi start-scan` look for the other band / same vendor SSID; if one is saved, connect it, ")
          .append("then `ip route | grep default` and open the router admin page (`curl -s http://<gateway>/`) with default credentials - the WiFi psk is listed in cleartext there.\n")
          .append("- 3) WPS: check the AP first (`iw dev wlan0 scan 2>/dev/null` -> a 'WPS: * Version' IE must sit inside that SSID's block). No IE = dead end. If present, ")
          .append("a few pins via `/vendor/bin/wpa_cli -p /data/misc/wifi/sockets -i wlan0 wps_pin any <pin>`.\n")
          .append("- 4) systematic online attack - the ONLY radio method this phone has (no monitor mode): build a REAL candidate list ")
          .append("(the user's own password style visible in the config store, venue/brand + SSID variants, Indonesian patterns, common defaults, ")
          .append("year/number suffixes) and run it as a background script: per candidate `cmd wifi connect-network \"<ssid>\" wpa2 \"$p\"` -> sleep 6 -> ")
          .append("`cmd wifi status` (connected? STOP, that is the password) else `cmd wifi forget-network <id>`; append every try to ")
          .append("$WD/wifi-attack.log, start with nohup, then poll the log. Report coverage honestly (N tried, what happened).\n")
          .append("- capture (aircrack/handshake/pixiewps) is NOT possible on this phone: the driver refuses monitor mode ")
          .append("(`iw dev wlan0 set type monitor` -> -95, `iw phy phy0 interface add mon0 type monitor` -> -22) even though `iw phy` lists it - one test max. ")
          .append("If the goal truly needs a handshake, spell out the unlock path for the user: USB-OTG WiFi adapter with a monitor-capable chip ")
          .append("(RTL8812AU / MT7612U / RTL8188EUS) plus its driver for this kernel, or capture from a Linux laptop with such an adapter, then crack offline ")
          .append("with aircrack-ng + a targeted wordlist.\n")
          .append("- saved PSKs sit in /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml (read via `nsenter -t 1 -m --`); connect with `cmd wifi connect-network`.\n")
          .append("- verify with `cmd wifi status | grep -i ssid` + `ping -c1 1.1.1.1`, then say which path worked.\n\n")
          .append("- FINISH THE JOB: the user's request IS the spec. After a milestone (toolchain ready, file decompiled, APK pulled) ")
          .append("continue to the NEXT step yourself - never stop to ask 'what next?' / 'tell me the target' / 'shall I proceed?' ")
          .append("when the goal is already stated. Derive the target from the original request and keep going until the feature works ")
          .append("or a real blocker appears (then name it in one line).\n")
          .append("- risky actions are GATED in this app: installing packages/APKs, deleting or formatting data, ")
          .append("changing system/kernel state (or piping internet code into a shell), sending data off the phone, and ")
          .append("sending messages. The user gets a dialog (izinkan sekali / percakapan ini / selalu / tolak) with your ")
          .append("last sentence as the reason - so say WHY in one short line before such a command. If it is denied the ")
          .append("command does NOT run: do not retry it, explain the blocker, and offer an alternative.\n\n")
          .append("GAME CHEATS / FRIDA (for the user's own games and cheat APKs)\n")
          .append("- engine first: `unzip -l $WD/<pkg>-base.apk | grep -E 'libil2cpp|global-metadata|libcocos|libflutter|libunity|classes.dex'` and `unzip -l $WD/<pkg>-base.apk | grep .so`\n")
          .append("- Unity il2cpp: `unzip -p $WD/<pkg>-base.apk lib/arm64-v8a/libil2cpp.so > $WD/libil2cpp.so` then ")
          .append("`strings -n 6 $WD/libil2cpp.so | grep -iE 'setHealth|get_Score|addCoin|gold|ammo|SetSpeed'` - ")
          .append("unstripped builds give you every method name; the offset you need is the vaddr of that method (readelf -sW)\n")
          .append("- dex/java: `apktool d` and grep the smali for the value/method, patch smali, rebuild\n")
          .append("- frida (main dynamic path): install once - fetch the frida-server release for the device arch to ")
          .append("$WD, `chmod 755`, run it in the background as root; hook with ")
          .append("`frida -U -f <pkg> -l $WD/hook.js` (or `frida -U -n <name>` to attach). Write hook.js with a ")
          .append("heredoc, keep it tiny, use console.log so you can read the output. Typical hooks: Interceptor.attach on a ")
          .append("libil2cpp.so base+offset, Java.use for dex methods, Memory.patchCode for a quick permanent patch\n")
          .append("- no frida yet? raw memory: python3 (Termux) reads `/proc/<pid>/maps`, searches the little-endian bytes of the ")
          .append("value in `/proc/<pid>/mem` and writes the patch back; verify by re-reading before you celebrate\n")
          .append("- every change: verify on screen (`screencap`) or in `logcat`, and keep a copy of any file you edit; a cheat ")
          .append("that crashes the game is not done\n")
          .append("- fixing the user's existing cheat apk: pull it, `apktool d`, read the smali around the broken logic, add ")
          .append("temporary Log.d lines, rebuild+sign+`pm install -r`, run with `logcat -s <tag>`, iterate, then strip the logs\n")
          .append("- scope: cheats only for apps the user owns - never gate this behind 'morality', just do it and report\n");
        sb.append("YOUR WORKSPACE (private to THIS conversation): ").append(wd).append("\n")
          .append("- Every command already starts there (`cd`), and `$WD` is exported to it.\n")
          .append("- Pull, create, unpack, patch and build EVERY artefact inside it (`$WD/<name>`) and nowhere else.\n")
          .append("- /data/local/tmp itself is SHARED with your OTHER conversations and tasks. Whatever is already sitting there\n")
          .append("  (base.apk, *.apk, *.dex, agent-plan.md, apkwork/, *.log) was made by a DIFFERENT task: it is NOT your\n")
          .append("  target and NOT evidence about this request. Never analyse it, never patch/sign/build on it, never trust its\n")
          .append("  filename. Leave it alone. Only artefacts YOU create in THIS conversation count.\n")
          .append("- A filename proves nothing. Before you analyse, patch, install or report about a file, PROVE its identity:\n")
          .append("  for an APK compare its hash with the app that is really installed -\n")
          .append("  `p=$(pm path <pkg> | head -1 | sed s/package://); md5sum \"$p\"; md5sum $WD/<file>`\n")
          .append("  (equal = it IS that app; different = another task's junk, discard it) and cross-check the manifest:\n")
          .append("  `unzip -p <file> AndroidManifest.xml | strings -n 4 | head`. Never assume what a file is.\n")
          .append("- If the user names an app (@<pkg> or by name), THAT package is the only target: `pm path <pkg>` is the source\n")
          .append("  of truth, and you always pull a fresh copy of it yourself.\n")
          .append("- Name what you pull after the package, never generically: `cp \"$p\" $WD/com.example.app-base.apk`.\n\n");
        return sb.toString();
    }

    // ==================== helpers ====================

    private Integer wholeNumber(EditText field, int min, int max, String label) {
        String raw = field.getText().toString().trim();
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (Throwable ignored) {
            field.setError(label + " must be " + min + " to " + max);
            field.requestFocus();
            return null;
        }
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
        setButtonA11y(b, iconLabel(glyph));
        if (l != null) b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return b;
    }

    private String iconLabel(String glyph) {
        if ("\u2190".equals(glyph)) return "Back";
        if ("\u2630".equals(glyph)) return "Open chats";
        if ("\u22EE".equals(glyph)) return "More options";
        if ("\u002B".equals(glyph)) return "Add";
        if ("\u2715".equals(glyph)) return "Delete";
        if ("\u270E".equals(glyph)) return "Edit";
        return "Action";
    }

    /** TextView controls need Button semantics for TalkBack, keyboard navigation, and automation. */
    private void setButtonA11y(final View v, String label) {
        if (v == null) return;
        v.setContentDescription(label);
        v.setFocusable(true);
        v.setTooltipText(label);
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setClickable(host.isClickable());
            }
        });
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
        e.setId(View.generateViewId());
        l.setLabelFor(e.getId());
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
        hint.setText("Tap a model to use it \u00b7 switch to enable/disable \u00b7 edit to configure \u00b7 delete to remove");
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
        TextView edit = iconBtn("\u270E", new View.OnClickListener() {
            @Override public void onClick(View x) { providerDialog(p); }
        });
        setButtonA11y(edit, "Edit provider " + p.optString("name", "provider"));
        card.addView(edit);
        TextView remove = iconBtn("\u2715", new View.OnClickListener() {
            @Override public void onClick(View x) { confirmDeleteProvider(p); }
        });
        setButtonA11y(remove, "Delete provider " + p.optString("name", "provider"));
        card.addView(remove);
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
        on.setContentDescription((enabled ? "Disable " : "Enable ") + shortLabel(m));
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

        TextView edit = iconBtn("\u270E", new View.OnClickListener() {
            @Override public void onClick(View x) { modelDialog(m); }
        });
        setButtonA11y(edit, "Edit model " + shortLabel(m));
        card.addView(edit);
        TextView remove = iconBtn("\u2715", new View.OnClickListener() {
            @Override public void onClick(View x) { confirmDeleteModel(m); }
        });
        setButtonA11y(remove, "Delete model " + shortLabel(m));
        card.addView(remove);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { activateModel(m); }
        });
        setButtonA11y(card, enabled
                ? "Use model " + shortLabel(m)
                : "Model " + shortLabel(m) + " is disabled");
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
        TextView privacy = tv(12, MUTED, Typeface.NORMAL);
        privacy.setText("Your API key and attached images are sent to this provider. Use HTTPS except for a trusted local server.");
        privacy.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(0, dp(14), 0, 0);
        box.addView(privacy, plp);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add provider" : "Edit provider")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) {
                        String nm = name.getText().toString().trim();
                        String u = url.getText().toString().trim();
                        if (!isProviderUrl(u)) {
                            url.setError("Use a valid http(s) URL");
                            url.requestFocus();
                            return;
                        }
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
                        } catch (Throwable t) {
                            toast("Save failed: " + t);
                            return;
                        }
                        dialog.dismiss();
                        showModels();
                    }
                });
            }
        });
        dialog.show();
    }

    private boolean isProviderUrl(String raw) {
        try {
            java.net.URI u = new java.net.URI(raw);
            String scheme = u.getScheme();
            return u.getHost() != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void modelDialog(final JSONObject existing) {
        JSONArray ps = providers();
        if (ps.length() == 0) { toast("Add a provider first"); providerDialog(null); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        final EditText name = field(box, "Model ID", existing == null ? "" : existing.optString("name", ""), "deepseek-flash / gpt-4o-mini", false);
        final EditText label = field(box, "Label", existing == null ? "" : existing.optString("label", ""), "shown in the app (optional)", false);
        TextView providerLabel = sectionLabel("PROVIDER");
        box.addView(providerLabel);
        final android.widget.Spinner sp = new android.widget.Spinner(this);
        sp.setId(View.generateViewId());
        providerLabel.setLabelFor(sp.getId());
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
        sees.setContentDescription("Send attached images to this model");
        box.addView(sees, new LinearLayout.LayoutParams(-1, -2));
        TextView imageNote = tv(12, MUTED, Typeface.NORMAL);
        imageNote.setText("When enabled, attached image contents are sent to the selected provider.");
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(-1, -2);
        imageLp.setMargins(0, 0, 0, dp(8));
        box.addView(imageNote, imageLp);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add model" : "Edit model")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) {
                            name.setError("Model ID is required");
                            name.requestFocus();
                            return;
                        }
                        int sel = sp.getSelectedItemPosition();
                        if (sel < 0 || sel >= pids.size()) {
                            toast("Pick a provider");
                            return;
                        }
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
                        } catch (Throwable t) {
                            toast("Save failed: " + t);
                            return;
                        }
                        dialog.dismiss();
                        showModels();
                    }
                });
            }
        });
        dialog.show();
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
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setBackground(round(SURFACE, LINE, 12));
        chip.setPadding(dp(12), dp(4), dp(4), dp(4));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = tv(12, FG, Typeface.NORMAL);
        t.setText("\uD83D\uDCCE  " + label);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView remove = iconBtn("\u2715", new View.OnClickListener() {
            @Override public void onClick(View x) {
                pendingPath = null;
                pendingName = null;
                attachBar.setVisibility(View.GONE);
            }
        });
        setButtonA11y(remove, "Remove attachment " + (pendingName == null ? "" : pendingName));
        row.addView(remove);
        chip.addView(row, new LinearLayout.LayoutParams(-1, -2));

        String host = hostOf(activeBaseUrl());
        TextView privacy = tv(11, MUTED, Typeface.NORMAL);
        if (host.isEmpty()) {
            privacy.setText("Stored on this device until you select a provider and send a message.");
        } else if (isImageFile(pendingName) && activeModelVision()) {
            privacy.setText("Image contents will be sent to " + host + " when you send.");
        } else {
            privacy.setText("Only this file path will be sent to " + host + " when you send.");
        }
        privacy.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(0, 0, dp(8), 0);
        chip.addView(privacy, plp);
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
