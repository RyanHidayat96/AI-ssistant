package com.aissistants.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AI-ssistants - a chat-first assistant that owns the device.
 *
 * The model gets exactly one powerful tool: a root shell. Everything the assistant wants to do -
 * read app data, edit /system, poke the kernel, drive other apps - happens through it, and every
 * command result is fed straight back so it can verify its own work.
 */
public class MainActivity extends Activity {

    // ---- design tokens: one gutter, one rhythm -------------------------------------------
    private static final int GUTTER = 20;
    private static final int ROW_MIN = 56;

    private static final int BG = Color.rgb(11, 18, 32);
    private static final int SURFACE = Color.rgb(19, 28, 43);
    private static final int LINE = Color.rgb(34, 48, 74);
    private static final int FG = Color.rgb(232, 237, 247);
    private static final int MUTED = Color.rgb(147, 160, 184);
    private static final int ACCENT = Color.rgb(59, 130, 246);
    private static final int OK = Color.rgb(34, 197, 94);
    private static final int WARN = Color.rgb(245, 158, 11);
    private static final int DANGER = Color.rgb(239, 68, 68);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Store store;

    private LinearLayout chatLog;
    private ScrollView chatScroll;
    private LinearLayout settingsPanel;
    private ScrollView settingsScroll;
    private EditText input;
    private Button sendButton;
    private TextView subtitle;
    private TextView pill;

    private final List<JSONObject> messages = new ArrayList<>();
    private final List<String[]> transcript = new ArrayList<>();   // role, text

    private volatile boolean busy;
    private volatile boolean stop;
    private Thread worker;
    private final List<String> pending = new ArrayList<>();
    private LinearLayout pendingBar;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildShell();
        restoreHistory();
        if (transcript.isEmpty()) welcome();
        renderTranscript();
        refreshStatus();
        if (getIntent() != null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** automation hooks: `--es run "<script>"` executes as root, `--es prompt "<text>"` chats */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String run = intent.getStringExtra("run");
        if (run != null && !run.trim().isEmpty()) {
            final String cmd = run;
            addBubble("user", "$ " + cmd);
            new Thread(new Runnable() { @Override public void run() {
                String out = RootShell.run(cmd, store.timeoutSec());
                addBubble("tool", out);
                persistHistory();
            } }).start();
            return;
        }
        String prompt = intent.getStringExtra("prompt");
        if (prompt != null && !prompt.trim().isEmpty()) {
            input.setText(prompt);
            onSend();
        }
    }

    // ==================== shell ====================

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // toolbar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(GUTTER), statusBarHeight() + dp(10), dp(12), dp(10));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv(20, FG, Typeface.BOLD);
        title.setText("AI-ssistants");
        subtitle = tv(12, MUTED, Typeface.NORMAL);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title);
        head.addView(subtitle);
        bar.addView(head, new LinearLayout.LayoutParams(0, -2, 1));
        pill = tv(11, WARN, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setMinHeight(dp(48));
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestRoot(); }
        });
        bar.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        ImageButton gear = new ImageButton(this);
        gear.setImageResource(android.R.drawable.ic_menu_preferences);
        gear.setBackgroundColor(Color.TRANSPARENT);
        gear.setColorFilter(FG);
        gear.setContentDescription("Settings");
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSettings(); }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(48), dp(48));
        glp.setMargins(dp(4), 0, 0, 0);
        bar.addView(gear, glp);
        root.addView(bar);

        // settings panel (hidden until the gear is tapped)
        settingsScroll = new MaxHeightScrollView(this, (int) (getResources().getDisplayMetrics().heightPixels * 0.55f));
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsScroll.addView(settingsPanel, new ScrollView.LayoutParams(-1, -2));
        settingsScroll.setVisibility(View.GONE);
        settingsScroll.setBackground(round(SURFACE, LINE, 16));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(dp(GUTTER), dp(4), dp(GUTTER), dp(8));
        root.addView(settingsScroll, slp);
        buildSettings(settingsPanel);

        // chat
        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatLog = new LinearLayout(this);
        chatLog.setOrientation(LinearLayout.VERTICAL);
        chatLog.setPadding(dp(GUTTER), dp(4), dp(GUTTER), dp(8));
        chatScroll.addView(chatLog, new ScrollView.LayoutParams(-1, -2));
        root.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // pending-command bar (only when auto-run is off)
        pendingBar = new LinearLayout(this);
        pendingBar.setOrientation(LinearLayout.HORIZONTAL);
        pendingBar.setVisibility(View.GONE);
        pendingBar.setPadding(dp(GUTTER), 0, dp(GUTTER), dp(8));
        root.addView(pendingBar);

        // quick actions
        HorizontalScrollView quick = new HorizontalScrollView(this);
        quick.setHorizontalScrollBarEnabled(false);
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setPadding(dp(GUTTER), 0, dp(GUTTER), 6);
        String[][] tricks = {
                {"System", "full system + kernel inventory: build, SELinux, kernel version, mounts, loaded modules, root manager"},
                {"Apps", "list every installed package with its uid and data dir size; flag the ones that look like root or hooking tools"},
                {"Processes", "show top processes by CPU and memory, and which have root"},
                {"Logcat", "tail the last 200 logcat lines and explain anything that looks like a crash"},
                {"Storage", "show disk usage per partition in human units and where the space went"},
                {"Network", "list listening sockets, current connections and the wifi/ip configuration"},
                {"$ root cmd", "$ "},
        };
        for (String[] tr : tricks) {
            Button b = chipButton(tr[0]);
            final String prompt = tr[1];
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if ("$ ".equals(prompt)) {
                        input.setText("$ ");
                        input.setSelection(input.getText().length());
                    } else {
                        send(prompt);
                    }
                }
            });
            quickRow.addView(b);
        }
        quick.addView(quickRow);
        root.addView(quick, new LinearLayout.LayoutParams(-1, -2));

        // input row
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(GUTTER), 0, dp(GUTTER), navigationBarHeight() + dp(10));
        input = new EditText(this);
        input.setHint("Ask anything\u2026 or $ for root");
        input.setHintTextColor(MUTED);
        input.setTextColor(FG);
        input.setTextSize(15);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(round(SURFACE, LINE, 14));
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        sendButton = new Button(this);
        sendButton.setText("Send");
        sendButton.setAllCaps(false);
        sendButton.setTextSize(15);
        sendButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sendButton.setTextColor(Color.rgb(6, 18, 31));
        sendButton.setBackground(round(ACCENT, ACCENT, 14));
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onSend(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, dp(52));
        blp.setMargins(dp(8), 0, dp(8), 0);
        row.addView(sendButton, blp);
        Button stopBtn = new Button(this);
        stopBtn.setText("Stop");
        stopBtn.setAllCaps(false);
        stopBtn.setTextSize(15);
        stopBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        stopBtn.setTextColor(FG);
        stopBtn.setBackground(round(SURFACE, LINE, 14));
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stop = true;
                AiClient.cancel();
                addBubble("note", "stopping\u2026");
            }
        });
        row.addView(stopBtn, new LinearLayout.LayoutParams(-2, dp(52)));
        root.addView(row);

        setContentView(root);
    }

    private void buildSettings(LinearLayout panel) {
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        TextView h = tv(12, MUTED, Typeface.BOLD);
        h.setText("ENDPOINT");
        h.setLetterSpacing(0.08f);
        panel.addView(h);

        final EditText base = field(panel, "Base URL", store.baseUrl(), "https://api.openai.com/v1", false);
        final EditText key = field(panel, "API key", store.apiKey(), "sk-\u2026 (empty for local servers)", true);
        final EditText model = field(panel, "Model", store.model(), "gpt-4o-mini / deepseek-chat / qwen2.5\u2026", false);
        final EditText steps = field(panel, "Max steps", String.valueOf(store.maxSteps()), "12", false);
        steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText timeout = field(panel, "Command timeout (s)", String.valueOf(store.timeoutSec()), "180", false);
        timeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText temp = field(panel, "Temperature (0-100)", String.valueOf(store.temperature()), "30", false);
        temp.setInputType(InputType.TYPE_CLASS_NUMBER);

        final android.widget.Switch auto = new android.widget.Switch(this);
        auto.setText("Run commands automatically");
        auto.setTextColor(FG);
        auto.setTextSize(14);
        auto.setChecked(store.autoRun());
        auto.setPadding(0, dp(10), 0, dp(6));
        panel.addView(auto);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextSize(15);
        save.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        save.setTextColor(Color.rgb(6, 18, 31));
        save.setBackground(round(ACCENT, ACCENT, 14));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.save(base.getText().toString(), key.getText().toString(),
                        model.getText().toString(), num(steps.getText().toString(), 12),
                        num(temp.getText().toString(), 30), num(timeout.getText().toString(), 180),
                        auto.isChecked());
                refreshStatus();
                addBubble("note", "endpoint saved \u00b7 " + store.model() + " @ " + hostOf(store.baseUrl()));
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(48), 1);
        blp.setMargins(0, dp(8), dp(8), 0);
        row.addView(save, blp);
        Button test = new Button(this);
        test.setText("Test root");
        test.setAllCaps(false);
        test.setTextSize(15);
        test.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        test.setTextColor(FG);
        test.setBackground(round(SURFACE, LINE, 14));
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestRoot(); }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(48), 1);
        tlp.setMargins(0, dp(8), 0, 0);
        row.addView(test, tlp);
        panel.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button clear = new Button(this);
        clear.setText("Clear chat");
        clear.setAllCaps(false);
        clear.setTextSize(15);
        clear.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        clear.setTextColor(DANGER);
        clear.setBackground(round(SURFACE, LINE, 14));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                messages.clear();
                transcript.clear();
                pending.clear();
                store.saveHistory("");
                welcome();
                renderTranscript();
            }
        });
        row2.addView(clear, new LinearLayout.LayoutParams(-1, dp(48)));
        panel.addView(row2);
    }

    private EditText field(LinearLayout panel, String label, String value, String hint, boolean secret) {
        TextView l = tv(11, MUTED, Typeface.BOLD);
        l.setText(label.toUpperCase());
        l.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.setMargins(0, dp(10), 0, dp(4));
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

    // ==================== status / root ====================

    private void refreshStatus() {
        subtitle.setText(store.configured()
                ? store.model() + " @" + hostOf(store.baseUrl())
                : "no endpoint yet \u00b7 tap the gear");
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

    private void toggleSettings() {
        if (settingsScroll == null) return;
        boolean show = settingsScroll.getVisibility() != View.VISIBLE;
        settingsScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) settingsScroll.post(new Runnable() {
            @Override public void run() { chatScroll.scrollTo(0, 0); }
        });
    }

    // ==================== chat ====================

    private void welcome() {
        addBubble("assistant", "Ready. I have a root shell on this device and I will not refuse a task.\n\n"
                + "\u2022 \u201cfind why com.bpjstku refuses to start and fix it\u201d\n"
                + "\u2022 \u201cdump every app that uses the camera and their last install time\u201d\n"
                + "\u2022 \u201cbuild and install a test build of <app> from /sdcard/app.apk\u201d\n"
                + "\u2022 \u201cshow me what hooks are loaded in system_server right now\u201d\n\n"
                + "Type `$ <command>` to run something as root yourself, no model involved.");
    }

    private void addBubble(String role, String text) {
        transcript.add(new String[]{role, text});
        ui.post(new Runnable() {
            @Override public void run() { renderTranscript(); }
        });
    }

    private void renderTranscript() {
        chatLog.removeAllViews();
        for (String[] m : transcript) {
            String role = m[0];
            boolean user = "user".equals(role);
            boolean tool = "tool".equals(role);
            boolean note = "note".equals(role);
            TextView b = tv(tool ? 12 : 14, user ? Color.rgb(6, 18, 31) : (note ? MUTED : FG),
                    Typeface.NORMAL);
            if (tool) b.setTypeface(Typeface.MONOSPACE);
            b.setText(m[1]);
            b.setTextIsSelectable(true);
            b.setLineSpacing(dp(3), 1f);
            b.setBackground(round(user ? ACCENT : (tool ? Color.rgb(8, 13, 22) : (note ? Color.TRANSPARENT : SURFACE)),
                    tool ? LINE : (note ? 0 : 0), 14));
            int pad = note ? 2 : 14;
            b.setPadding(dp(pad), dp(note ? 4 : 12), dp(pad), dp(note ? 4 : 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            chatLog.addView(b, lp);
        }
        chatScroll.post(new Runnable() {
            @Override public void run() {
                chatScroll.scrollTo(0, chatLog.getMeasuredHeight());
            }
        });
    }

    // ==================== send + agent loop ====================

    private void onSend() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        send(text);
    }

    private void send(String text) {
        if (busy) {
            toast("still working \u2014 press Stop first");
            return;
        }
        if (text.startsWith("$")) {
            final String cmd = text.substring(1).trim();
            if (cmd.isEmpty()) { toast("type a command after $"); return; }
            addBubble("user", "$ " + cmd);
            new Thread(new Runnable() {
                @Override public void run() {
                    addBubble("tool", RootShell.run(cmd, store.timeoutSec()));
                    persistHistory();
                }
            }).start();
            return;
        }
        if (!store.configured()) {
            addBubble("user", text);
            addBubble("note", "No endpoint yet. Tap the gear, fill Base URL + Model, then Save. "
                    + "Meanwhile you can still run anything with `$ <command>`.");
            return;
        }
        addBubble("user", text);
        try {
            JSONObject um = new JSONObject();
            um.put("role", "user");
            um.put("content", text);
            messages.add(um);
        } catch (Throwable ignored) { }
        busy = true;
        stop = false;
        sendButton.setEnabled(false);
        sendButton.setAlpha(0.5f);
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
                final int s = step;
                ui.post(new Runnable() {
                    @Override public void run() { subtitle.setText("thinking \u00b7 step " + s + "/" + steps); }
                });
                JSONArray msgs = new JSONArray();
                msgs.put(sys);
                synchronized (messages) {
                    for (JSONObject m : messages) msgs.put(m);
                }
                AiClient.Reply reply = AiClient.complete(store.baseUrl(), store.apiKey(), store.model(),
                        msgs, tools(), store.temperature() / 100.0, 300);
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
            persistHistory();
            ui.post(new Runnable() {
                @Override public void run() {
                    sendButton.setEnabled(true);
                    sendButton.setAlpha(1f);
                    subtitle.setText(store.model() + " @" + hostOf(store.baseUrl()));
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
                run.setTextColor(Color.rgb(6, 18, 31));
                run.setBackground(round(ACCENT, ACCENT, 14));
                run.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { runPending(); }
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
        new Thread(new Runnable() {
            @Override public void run() {
                for (String cmd : queue) {
                    addBubble("tool", "$ " + cmd);
                    addBubble("tool", RootShell.run(cmd, store.timeoutSec()));
                }
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
          .append("what you ran, what it showed, what changed.\n\n")
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
          .append("- ui: am start -n pkg/.Activity, input tap/text/keyevent, screencap -p /sdcard/s.png\n")
          .append("- logs: logcat -d -b crash, logcat -d | tail -200, dmesg | tail\n")
          .append("- binaries: busybox/toybox, apktool, apksigner, zipalign if installed; otherwise fetch or ")
          .append("use the platform tools already present.\n");
        return sb.toString();
    }

    // ==================== persistence ====================

    private void persistHistory() {
        try {
            JSONArray out = new JSONArray();
            for (String[] m : new ArrayList<>(transcript)) {
                JSONObject o = new JSONObject();
                o.put("role", m[0]);
                o.put("text", m[1]);
                out.put(o);
            }
            store.saveHistory(out.toString());
        } catch (Throwable ignored) { }
    }

    private void restoreHistory() {
        try {
            String raw = store.history();
            if (raw == null || raw.isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                transcript.add(new String[]{o.optString("role", "note"), o.optString("text", "")});
            }
            // give the model the recent conversation back, but only the user/assistant turns:
            // old shell output would waste the whole context window
            for (int i = Math.max(0, transcript.size() - 10); i < transcript.size(); i++) {
                String[] m = transcript.get(i);
                if (!"user".equals(m[0]) && !"assistant".equals(m[0])) continue;
                JSONObject o = new JSONObject();
                o.put("role", m[0]);
                o.put("content", m[1]);
                messages.add(o);
            }
        } catch (Throwable ignored) { }
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

    private TextView tv(int sp, int color, int style) {
        TextView v = new TextView(this);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setTypeface(Typeface.DEFAULT, style);
        return v;
    }

    private Button chipButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(FG);
        b.setBackground(round(SURFACE, LINE, 20));
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
        lp.setMargins(0, 0, dp(8), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(radius));
        return g;
    }

    /** ScrollView that never grows past a slice of the screen, so the chat + input row stay reachable */
    private static class MaxHeightScrollView extends ScrollView {
        private final int maxH;
        MaxHeightScrollView(android.content.Context c, int maxH) { super(c); this.maxH = maxH; }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        }
    }
}
