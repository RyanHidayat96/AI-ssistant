package com.aissistant.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Desktop regression tests for the non-Android runtime pieces. */
public final class AgentReliabilityTest {
    private AgentReliabilityTest() { }

    public static void main(String[] args) throws Exception {
        testAppLockHashing();
        testToolValidation();
        testMemory();
        testMessageBranch();
        testOverlayWindowMask();
        testOverlayIsolationState();
        testRunGuard();
        testCapabilityTriage();
        testPromptIsGeneral();
        testStreamingResponses();
        testPersistentShellMarker();
        System.out.println("AgentReliabilityTest: PASS");
    }

    private static void testAppLockHashing() throws Exception {
        check(AppLock.MIN_PASSWORD_LENGTH == 4, "app lock accepts four-character passwords");
        char[] password = "1234".toCharArray();
        AppLock.PasswordHash record = AppLock.create(password);
        AppLock.wipe(password);
        check(AppLock.verify("1234".toCharArray(), record.salt, record.hash,
                        record.kdf, record.iterations),
                "password hash verifies only its source password");
        check(!AppLock.verify("wrong password".toCharArray(), record.salt, record.hash,
                        record.kdf, record.iterations),
                "wrong password does not unlock app");
    }

    private static void testToolValidation() throws Exception {
        JSONArray tools = AgentTools.definitions();
        check(tools.length() == 9, "all registered tools exposed");
        JSONObject valid = call("run_shell", "{\"command\":\"id\"}");
        check("id".equals(AgentTools.arguments(valid).getString("command")), "valid shell arguments accepted");
        rejects(call("run_shell", "id"), "non-JSON shell arguments rejected");
        rejects(call("run_shell", "{\"command\":\"id\",\"extra\":true}"), "unexpected argument rejected");
        rejects(call("not_registered", "{}"), "unknown tool rejected");
        JSONObject observe = call("observe_app", "{\"package\":\"com.example.target\"}");
        check("com.example.target".equals(AgentTools.arguments(observe).getString("package")),
                "target app observation arguments accepted");
        JSONObject act = call("act_app", "{\"node\":\"n1\",\"action\":\"click\"}");
        check("click".equals(AgentTools.arguments(act).getString("action")), "target app action arguments accepted");
        JSONObject scroll = call("scroll_app", "{\"node\":\"n2\",\"direction\":\"forward\",\"duration_ms\":10000}");
        check(AgentTools.arguments(scroll).getInt("duration_ms") == 10000,
                "timed Accessibility scroll arguments accepted");
        rejects(call("scroll_app", "{\"node\":\"n2\",\"direction\":\"forward\",\"duration_ms\":99}"),
                "short timed scroll rejected");
        JSONObject evidence = call("read_evidence", "{\"id\":\"e-1-1.txt\",\"offset\":3}");
        check(AgentTools.arguments(evidence).getInt("offset") == 3, "integer evidence offset accepted");
    }

    private static void testMemory() throws Exception {
        Path root = Files.createTempDirectory("aissistant-memory-");
        AgentMemory memory = new AgentMemory(root.toFile(), "session / test");
        check(memory.checkpoint().isEmpty(), "new memory starts blank");
        memory.saveCheckpoint("GOAL\nrepair\nVERIFIED\nnone yet");
        check(memory.checkpoint().contains("repair"), "checkpoint round-trip");
        String id = memory.evidence("inspect", "first\n" + repeat('x', 7000) + "\nlast");
        String page = memory.readEvidence(id, 0);
        check(page.contains("ACTION: inspect") && page.contains("HISTORICAL EVIDENCE"), "evidence round-trip");
        check(AgentMemory.isEvidenceReadAction("read_evidence {\"id\":\"" + id + "\"}"),
                "evidence retrieval action recognized");
        check(!AgentMemory.isEvidenceReadAction("run_shell {\"command\":\"id\"}"),
                "ordinary action is not evidence retrieval");
        String excerpt = AgentMemory.excerpt("head" + repeat('x', 80) + "tail", 20);
        check(excerpt.contains("head") && excerpt.contains("tail"), "excerpt retains both ends");
        rejectsEvidence(memory, "../checkpoint.txt");
        memory.clear();
        check(memory.checkpoint().isEmpty(), "branch clears saved task checkpoint");
        boolean oldEvidenceRemoved = false;
        try { memory.readEvidence(id, 0); }
        catch (Exception expected) { oldEvidenceRemoved = true; }
        check(oldEvidenceRemoved, "branch clears saved task evidence");

        JSONArray bubbles = new JSONArray();
        bubbles.put(new JSONObject().put("role", "user").put("text", "original goal"));
        bubbles.put(new JSONObject().put("role", "tool").put("text", "$ id"));
        bubbles.put(new JSONObject().put("role", "tool").put("text", "uid=0"));
        for (int i = 0; i < 45; i++) bubbles.put(new JSONObject().put("role", "assistant").put("text", "turn " + i));
        boolean goalKept = false;
        boolean actionKept = false;
        for (JSONObject item : AgentMemory.restore(bubbles)) {
            String text = item.optString("content");
            goalKept |= text.contains("original goal");
            actionKept |= text.contains("ACTION: id");
        }
        check(goalKept && actionKept, "resume retains goal and paired tool evidence");
    }

    private static void testMessageBranch() throws Exception {
        JSONArray transcript = new JSONArray();
        transcript.put(new JSONObject().put("role", "user").put("text", "original task"));
        transcript.put(new JSONObject().put("role", "tool").put("text", "$ id"));
        transcript.put(new JSONObject().put("role", "tool").put("text", "uid=0"));
        transcript.put(new JSONObject().put("role", "assistant").put("text", "partial answer"));

        JSONArray branch = ChatBranch.beforeEditedUser(transcript, 0, "original task");
        check(branch != null && branch.length() == 0, "edit branches before original user turn");
        branch.put(new JSONObject().put("role", "user").put("text", "revised task"));
        String restored = AgentMemory.restore(branch).get(0).optString("content");
        check("revised task".equals(restored), "branch context excludes old tool work and answer");
        check(ChatBranch.beforeEditedUser(transcript, 0, "stale text") == null,
                "branch rejects a message that changed before submit");
    }

    private static void testOverlayWindowMask() {
        String dump = "Window #5:\n"
                + "  name=Sys2038:com.aissistant.app\n"
                + "  touchableRegion=[24,360][1100,1429]\n"
                + "Window #4: com.example.target/.MainActivity\n"
                + "  touchableRegion=[0,0][1200,2400]";
        String filtered = AgentWindowFilter.hideSelfOverlays(dump, "com.aissistant.app");
        check(!filtered.contains("Sys2038:com.aissistant.app") && !filtered.contains("[24,360]"),
                "self overlay window block does not reach model");
        check(filtered.contains("com.example.target/.MainActivity"),
                "target window diagnostics remain available");

        String bareInputDump = "InputWindowHandle name=Sys2038:com.aissistant.app\n"
                + "  frame=[24,360][1100,1429]\n"
                + "  alpha=1.0\n"
                + "InputWindowHandle name=com.example.target/.MainActivity\n"
                + "  frame=[0,0][1200,2400]";
        String bareFiltered = AgentWindowFilter.hideSelfOverlays(bareInputDump, "com.aissistant.app");
        check(!bareFiltered.contains("Sys2038") && !bareFiltered.contains("[24,360]"),
                "header variants discard their complete self-owned diagnostics block");
        check(bareFiltered.contains("com.example.target/.MainActivity"),
                "next target diagnostics block remains after self-owned input block");

        String grep = "mCurrentFocus=Window{42 Sys2038:com.aissistant.app}\n"
                + "mFocusedApp=AppWindowToken{com.example.target}";
        String grepFiltered = AgentWindowFilter.hideSelfOverlays(grep, "com.aissistant.app");
        check(!grepFiltered.contains("aissistant") && grepFiltered.contains("com.example.target"),
                "headerless self-owned focus result does not reach model");
    }

    private static void testOverlayIsolationState() {
        OverlayHub.finishAgentRun();
        OverlayHub.beginAgentRun();
        check(!OverlayHub.agentPassThrough() && !OverlayHub.agentIsolation()
                        && !OverlayHub.overlaySuppressedForDriving(),
                "active run keeps the overlay usable while target-window tools filter it out");
        OverlayHub.enterAgentIsolation();
        check(OverlayHub.agentPassThrough() && OverlayHub.agentIsolation()
                        && OverlayHub.overlaySuppressedForDriving(),
                "raw command isolation removes any self-owned window before global input");
        OverlayHub.leaveAgentIsolation();
        check(!OverlayHub.agentPassThrough() && !OverlayHub.agentIsolation()
                        && !OverlayHub.overlaySuppressedForDriving(),
                "raw command completion restores normal overlay interaction");
        OverlayHub.enterRawInputPassThrough();
        check(OverlayHub.agentPassThrough() && !OverlayHub.agentIsolation()
                        && !OverlayHub.overlaySuppressedForDriving(),
                "raw input passes through without detaching the normal overlay lifecycle");
        OverlayHub.leaveRawInputPassThrough();
        check(!OverlayHub.agentPassThrough() && !OverlayHub.agentIsolation(),
                "raw input completion restores overlay interaction");
        OverlayHub.finishAgentRun();
        check(!OverlayHub.agentPassThrough() && !OverlayHub.agentIsolation(),
                "terminal run cleanup restores normal overlay interaction");
    }

    private static void testRunGuard() {
        RunGuard guard = new RunGuard();
        String note = "";
        for (int i = 0; i < 7; i++) note = guard.observe("dumpsys activity", "same");
        check(note.contains("repeated"), "repeated observation nudges instead of caching");
        guard.observe("dumpsys activity", "changed");
        check(!guard.reportOnly(), "new observation is usable");
        for (int i = 0; i < 13; i++) guard.observe("same", "same");
        check(guard.reportOnly(), "finite execution budget switches to final report");
        guard.reset();
        for (int i = 0; i < 3; i++) note = guard.observe("read_evidence {\"id\":\"e-1-1.txt\"}", "historical");
        check(note.contains("historical-evidence retrievals"), "evidence chain receives a direct nudge");
        guard.observe("read_evidence {\"id\":\"e-1-1.txt\"}", "historical");
        check(guard.reportOnly(), "evidence retrieval chain switches to final report");
        guard.reset();
        for (int i = 0; i < 3; i++) {
            note = guard.observe("wc -l; grep relevant file | sed -n '" + (i * 60 + 1) + "," + (i * 60 + 60) + "p'", "fragment");
        }
        check(note.contains("sequential paged slices"), "micro-paged text receives a focused nudge");
        for (int i = 3; i < 5; i++) {
            note = guard.observe("grep relevant file | sed -n '" + (i * 60 + 1) + "," + (i * 60 + 60) + "p'", "fragment");
        }
        check(guard.reportOnly(), "micro-paged text stops before it becomes a long loop");
        guard.reset();
        for (int i = 0; i < 3; i++) {
            note = guard.observe("grep -rl \"derived-value\" sources", "");
        }
        check(note.contains("literal searches produced no match"),
                "empty literal searches nudge source-to-derived mapping");
        for (int i = 3; i < 5; i++) guard.observe("grep -rl \"derived-value\" sources", "");
        check(guard.reportOnly(), "empty literal searches stop before a representation loop");
        guard.reset();
        check(!guard.reportOnly(), "new run resets guard state");
    }

    private static void testCapabilityTriage() {
        RunGuard guard = new RunGuard();
        String note = "";
        for (int i = 0; i < 4; i++) {
            note = guard.observe("different-tool-" + i, "sh: analyzer: not found");
        }
        check(note.contains("CAPABILITY TRIAGE") && note.contains("TOOL_OR_RUNTIME"),
                "different failed tool paths trigger triage before terminal report");
        check(!guard.reportOnly(), "triage is a nudge, not an unsupported-task claim");
        guard.reset();
        for (int i = 0; i < 4; i++) {
            note = guard.observe("different-interface-" + i, "camera unavailable");
        }
        check(note.contains("HARDWARE_OR_INTERFACE"), "hardware-like failures require prerequisite triage");
    }
    private static void testPromptIsGeneral() {
        String prompt = AgentPrompt.build("/tmp/run", "probe_epoch_ms=1", "sdk=35");
        String low = prompt.toLowerCase();
        check(prompt.contains("list_skills") && prompt.contains("read_reference") && prompt.contains("save_checkpoint")
                        && prompt.contains("observe_app") && prompt.contains("act_app") && prompt.contains("scroll_app"),
                "prompt documents registered capabilities");
        check(prompt.contains("ANDROID RECIPE CANDIDATES") && prompt.contains("TOOL INVENTORY SNAPSHOT"),
                "prompt labels generic candidates and volatile probe");
        check(prompt.contains("CAPABILITY GAP RESOLUTION") && prompt.contains("minimum compatible item/spec/action"),
                "prompt requires evidence-backed external requirements");
        check(prompt.contains("agent_tool_list") && prompt.contains("agent_tool_shared") && prompt.contains("agent_tool_session")
                        && prompt.contains("agent_tool_register_shared"),
                "prompt requires explicit shared and session tool scopes");
        check(prompt.contains("TRANSFORMED ARTIFACTS") && prompt.contains("source location or call-site"),
                "prompt requires mapping derived artifact values to their source");
        check(prompt.contains("$TOOLS/shared/<name>/<version>/<abi>")
                        && prompt.contains("$WD/.tools/<name>"),
                "prompt documents stable tool storage layouts");
        check(prompt.contains("UI primary: call observe_app")
                        && prompt.contains("Raw fallback receives a short exclusive phase"),
                "prompt keeps ordinary Android UI on target-window Accessibility");
        check(!low.contains("proven recipes on this phone") && !low.contains("unlocking a feature")
                        && !low.contains("vip camera") && !low.contains("tricky_store"),
                "prompt has no case-specific route or stale device claim");
    }

    private static void testStreamingResponses() throws Exception {
        AiClient.Reply transientReply = new AiClient.Reply();
        transientReply.error = "java.io.IOException: unexpected end of stream";
        check(AiClient.shouldRetryTransient(transientReply), "unstarted transient transport failure is retried");
        transientReply.responseStarted = true;
        check(!AiClient.shouldRetryTransient(transientReply), "started stream is never replayed");
        transientReply.responseStarted = false;
        transientReply.error = "HTTP 401: invalid key";
        check(!AiClient.shouldRetryTransient(transientReply), "credential errors are not retried");
        AtomicInteger request = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> respond(exchange, request.incrementAndGet()));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AiClient.resetCancel();
            AiClient.Reply valid = AiClient.complete(base, "", "test", new JSONArray(), AgentTools.definitions(), 0, 0, 5, null);
            check(valid.ok && valid.toolCalls.length() == 1, "complete SSE tool call accepted: ok="
                    + valid.ok + " calls=" + valid.toolCalls.length() + " error=" + valid.error);
            check("id".equals(AgentTools.arguments(valid.toolCalls.getJSONObject(0)).getString("command")),
                    "assembled streamed tool arguments are exact");
            AiClient.Reply incomplete = AiClient.complete(base, "", "test", new JSONArray(), null, 0, 0, 5, null);
            check(!incomplete.ok && incomplete.error.contains("incomplete"), "truncated SSE rejected before dispatch");
            AiClient.Reply malformed = AiClient.complete(base, "", "test", new JSONArray(), null, 0, 0, 5, null);
            check(!malformed.ok && malformed.error.contains("invalid stream frame"), "malformed SSE rejected before dispatch");
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int request) throws IOException {
        String payload;
        if (request == 1) {
            JSONObject function = new JSONObject().put("name", "run_shell")
                    .put("arguments", new JSONObject().put("command", "id").toString());
            JSONObject tool = new JSONObject().put("index", 0).put("id", "call_1").put("function", function);
            JSONObject first = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                    .put("delta", new JSONObject().put("tool_calls", new JSONArray().put(tool)))
                    .put("finish_reason", JSONObject.NULL)));
            JSONObject finish = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                    .put("delta", new JSONObject()).put("finish_reason", "tool_calls")));
            payload = "data: " + first + "\n\ndata: " + finish + "\n\ndata: [DONE]\n\n";
        } else if (request == 2) {
            payload = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n";
        } else {
            payload = "data: {not json}\n\n";
        }
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void testPersistentShellMarker() throws Exception {
        PipedWriter pipe = new PipedWriter();
        PipedReader reader = new PipedReader(pipe);
        setRootShell("shell", new DummyProcess());
        setRootShell("shellOut", new java.io.BufferedReader(reader));
        setRootShell("shellIn", new MarkerWriter(pipe));
        setRootShell("shellBroken", false);
        RootShell.resetCancel();
        try {
            String output = RootShell.run("printf hello", 2);
            check("hello".equals(output.trim()), "persistent shell recognizes marker after output without trailing newline: " + output);
        } finally {
            RootShell.closeShell();
            pipe.close();
            reader.close();
        }
    }

    private static JSONObject call(String name, String args) throws Exception {
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("arguments", args));
    }

    private static void rejects(JSONObject call, String message) throws Exception {
        try { AgentTools.arguments(call); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { }
    }

    private static void rejectsEvidence(AgentMemory memory, String id) throws Exception {
        try { memory.readEvidence(id, 0); throw new AssertionError("unsafe evidence id accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static String repeat(char character, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(character);
        return result.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void setRootShell(String name, Object value) throws Exception {
        Field field = RootShell.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static final class MarkerWriter extends StringWriter {
        private static final Pattern MARKER = Pattern.compile("__AISS_RC_([0-9]+)_%s");
        private final PipedWriter output;
        private boolean answered;

        MarkerWriter(PipedWriter output) { this.output = output; }

        @Override public void write(String value) {
            super.write(value);
            answer();
        }

        @Override public void write(char[] value, int offset, int length) {
            super.write(value, offset, length);
            answer();
        }

        private void answer() {
            if (answered) return;
            Matcher marker = MARKER.matcher(toString());
            if (!marker.find()) return;
            answered = true;
            try {
                output.write("hello\n__AISS_RC_" + marker.group(1) + "_0\n");
                output.flush();
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }
    }

    private static final class DummyProcess extends Process {
        @Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return true; }
    }
}
