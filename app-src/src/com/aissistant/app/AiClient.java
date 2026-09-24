package com.aissistant.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.TreeMap;

/**
 * Minimal OpenAI-compatible chat client. It runs in a worker thread and speaks plain
 * HttpURLConnection, so any endpoint works (OpenAI, OpenRouter, Groq, a local llama.cpp /
 * Ollama / LM Studio server on the LAN) without CORS or extra dependencies.
 *
 * Requests are sent with stream=true (SSE) so text can be shown live; servers that ignore the
 * flag and answer with a plain JSON body are parsed on the same connection as a fallback.
 */
final class AiClient {

    /** live text chunks while the model streams (content + reasoning/thinking) */
    interface StreamCb {
        void onDelta(String content, String reasoning);
    }

    /** provider transport status before any model text/tool reaches the UI */
    interface StatusCb {
        void onRetry(int retry, int maxRetries, String error, long waitMs);
    }

    static final class Reply {
        boolean ok;
        String text = "";
        String reasoning = "";
        JSONArray toolCalls = new JSONArray();
        String error = "";
        int promptTokens;
        int cacheHitTokens;
        int completionTokens;
        /** Number of safe transport retries completed before this result. */
        int retries;
        /** A streamed response already reached the UI; replaying it could duplicate work. */
        boolean responseStarted;
    }

    private AiClient() { }

    /** the same conversation without provider-specific extras (e.g. assistant.reasoning_content) */
    private static JSONArray plainMessages(JSONArray in) {
        JSONArray out = new JSONArray();
        try {
            for (int i = 0; i < in.length(); i++) {
                JSONObject m = in.optJSONObject(i);
                if (m == null) continue;
                JSONObject copy = new JSONObject();
                java.util.Iterator<String> keys = m.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if ("reasoning_content".equals(k) || "reasoning".equals(k)) continue;
                    copy.put(k, m.get(k));
                }
                out.put(copy);
            }
            return out;
        } catch (Throwable t) {
            return in;          // never break a request just because the cleanup failed
        }
    }

    /** the live connection, so the Stop button can abort a blocked read */
    private static volatile HttpURLConnection active;

    /** endpoints that already answered 400 to our optional extras: learned at runtime, keyed by base URL,
     *  so no provider is special-cased in code - the app just stops repeating the rejected field */
    private static final java.util.Set<String> plainOnly =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** set by Stop, cleared when a new run starts - so a cancel is never lost to a race */
    private static volatile boolean cancelled;

    /** User-visible provider wait window: if no model response arrives in 20 seconds, stop. */
    private static final int MODEL_WAIT_TIMEOUT_MS = 20000;

    static void cancel() {
        cancelled = true;
        HttpURLConnection c = active;
        if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
    }

    static void resetCancel() { cancelled = false; }

    /** Result of a low-cost configuration probe. A 2xx response is enough to prove acceptance. */
    static final class Probe {
        boolean ok;
        int statusCode;
        String error = "";
    }

    /**
     * Checks provider endpoint and credentials only. `/models` is part of the OpenAI-compatible
     * provider contract and needs no configured chat model.
     */
    static Probe probeProvider(String baseUrl, String apiKey, int timeoutSec) {
        return probe(baseUrl, apiKey, null, timeoutSec);
    }

    /**
     * Checks one configured model with the smallest portable chat request. Do not require a full
     * generated answer: HTTP acceptance proves this provider accepted this model and credential.
     */
    static Probe probeModel(String baseUrl, String apiKey, String model, int timeoutSec) {
        return probe(baseUrl, apiKey, model, timeoutSec);
    }

    private static Probe probe(String baseUrl, String apiKey, String model, int timeoutSec) {
        Probe out = new Probe();
        HttpURLConnection conn = null;
        try {
            if (cancelled) { out.error = "stopped"; return out; }
            if (baseUrl == null || baseUrl.trim().isEmpty()) { out.error = "no endpoint configured"; return out; }
            boolean checkModel = model != null;
            if (checkModel && model.trim().isEmpty()) { out.error = "no model configured"; return out; }

            String root = baseUrl.trim();
            while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
            if (root.endsWith("/chat/completions")) {
                root = root.substring(0, root.length() - "/chat/completions".length());
            }
            String url = checkModel ? root + "/chat/completions" : root + "/models";
            conn = (HttpURLConnection) new URL(url).openConnection();
            active = conn;
            conn.setRequestMethod(checkModel ? "POST" : "GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(Math.max(10, timeoutSec) * 1000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "AI-ssistant/1.3 (Android)");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            if (checkModel) {
                JSONObject body = new JSONObject();
                body.put("model", model.trim());
                body.put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user").put("content", "Reply only with OK.")));
                body.put("max_tokens", 16);
                body.put("stream", false);
                byte[] payload = body.toString().getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream os = conn.getOutputStream();
                try { os.write(payload); } finally { os.close(); }
            }

            out.statusCode = conn.getResponseCode();
            String body = slurp(out.statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (cancelled) { out.error = "stopped"; return out; }
            if (out.statusCode < 200 || out.statusCode >= 300) {
                out.error = "HTTP " + out.statusCode + ": " + cut(body, 400);
                return out;
            }
            // Some proxies put an OpenAI-style error object in a 2xx JSON body. Do not call it success.
            try {
                JSONObject json = new JSONObject(body);
                if (json.has("error")) {
                    Object error = json.opt("error");
                    out.error = "provider error: " + cut(String.valueOf(error), 400);
                    return out;
                }
            } catch (Throwable ignored) { }
            out.ok = true;
            return out;
        } catch (java.net.SocketTimeoutException t) {
            out.error = "model wait timed out after 20 seconds; no commands were executed";
            return out;
        } catch (Throwable t) {
            out.error = String.valueOf(t);
            return out;
        } finally {
            if (active == conn) active = null;
            if (conn != null) conn.disconnect();
        }
    }

    static Reply complete(String baseUrl, String apiKey, String model, JSONArray messages,
                          JSONArray tools, double temperature, int thinking, int timeoutSec, StreamCb cb) {
        return complete(baseUrl, apiKey, model, messages, tools, temperature, thinking, timeoutSec, 4096, cb);
    }

    static Reply complete(String baseUrl, String apiKey, String model, JSONArray messages,
                          JSONArray tools, double temperature, int thinking, int timeoutSec,
                          StreamCb cb, StatusCb statusCb) {
        return complete(baseUrl, apiKey, model, messages, tools, temperature, thinking, timeoutSec, 4096, cb, statusCb);
    }

    /** same call with an explicit output cap - the local planner asks for ~512 */
    static Reply complete(String baseUrl, String apiKey, String model, JSONArray messages,
                          JSONArray tools, double temperature, int thinking, int timeoutSec,
                          int maxTokens, StreamCb cb) {
        return complete(baseUrl, apiKey, model, messages, tools, temperature, thinking, timeoutSec, maxTokens, cb, false, null);
    }

    static Reply complete(String baseUrl, String apiKey, String model, JSONArray messages,
                          JSONArray tools, double temperature, int thinking, int timeoutSec,
                          int maxTokens, StreamCb cb, StatusCb statusCb) {
        return complete(baseUrl, apiKey, model, messages, tools, temperature, thinking, timeoutSec, maxTokens, cb, false, statusCb);
    }

    /**
     * providers differ: some accept the extra fields the DeepSeek-compatible ones take (thinking,
     * reasoning_effort) and reject anything they do not know with HTTP 400. {@code minimal} drops every
     * provider-specific extra so a plain OpenAI-compatible body can be retried. No provider is named here.
     */
    static Reply complete(String baseUrl, String apiKey, String model, JSONArray messages,
                          JSONArray tools, double temperature, int thinking, int timeoutSec,
                          int maxTokens, StreamCb cb, boolean minimal) {
        return complete(baseUrl, apiKey, model, messages, tools, temperature, thinking, timeoutSec, maxTokens, cb, minimal, null);
    }

    static Reply complete(String baseUrl, String apiKey, String model, JSONArray messages,
                          JSONArray tools, double temperature, int thinking, int timeoutSec,
                          int maxTokens, StreamCb cb, boolean minimal, StatusCb statusCb) {
        final int maxRetries = 2;
        Reply last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (cancelled) {
                Reply stopped = new Reply();
                stopped.error = "stopped";
                stopped.retries = attempt;
                return stopped;
            }
            last = completeOnce(baseUrl, apiKey, model, messages, tools, temperature, thinking,
                    timeoutSec, maxTokens, cb, minimal);
            last.retries = attempt;
            if (!shouldRetryTransient(last) || attempt == maxRetries) return last;
            long waitMs = 350L * (attempt + 1);
            android.util.Log.i("AIssistant", "temporary provider transport failure; retry "
                    + (attempt + 1) + "/" + maxRetries + ": " + last.error);
            if (statusCb != null) statusCb.onRetry(attempt + 1, maxRetries, last.error, waitMs);
            if (!waitForRetry(waitMs)) return last;
        }
        return last == null ? new Reply() : last;
    }

    /** Retry only before response text or tool calls reach the UI; replaying a started stream is unsafe. */
    static boolean shouldRetryTransient(Reply reply) {
        if (reply == null || reply.ok || reply.responseStarted || cancelled) return false;
        String error = reply.error == null ? "" : reply.error.toLowerCase(java.util.Locale.ENGLISH);
        if (error.contains("sockettimeoutexception") || error.contains("timed out")
                || error.contains("timeout") || error.contains("connection refused")
                || error.contains("network is unreachable") || error.contains("unable to resolve host")) return false;
        if (error.startsWith("http 429") || error.startsWith("http 500")
                || error.startsWith("http 502") || error.startsWith("http 503")
                || error.startsWith("http 504")) return true;
        return error.contains("unexpected end of stream")
                || error.contains("connection reset")
                || error.contains("broken pipe")
                || error.contains("eofexception");
    }

    private static boolean waitForRetry(long millis) {
        long until = System.currentTimeMillis() + Math.max(0L, millis);
        while (!cancelled && System.currentTimeMillis() < until) {
            try { Thread.sleep(Math.min(80L, Math.max(1L, until - System.currentTimeMillis()))); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return false; }
        }
        return !cancelled;
    }

    private static Reply completeOnce(String baseUrl, String apiKey, String model, JSONArray messages,
                                      JSONArray tools, double temperature, int thinking, int timeoutSec,
                                      int maxTokens, StreamCb cb, boolean minimal) {
        Reply out = new Reply();
        HttpURLConnection conn = null;
        try {
            if (cancelled) {
                out.error = "stopped";
                return out;
            }
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                out.error = "no endpoint configured";
                return out;
            }
            if (model == null || model.trim().isEmpty()) {
                out.error = "no model configured";
                return out;
            }
            String base = baseUrl.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
            if (!minimal && plainOnly.contains(base)) minimal = true;   // this endpoint already said no

            JSONObject body = new JSONObject();
            body.put("model", model.trim());
            body.put("messages", minimal ? plainMessages(messages) : messages);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens > 0 ? maxTokens : 4096);
            body.put("stream", true);
            // ask for the usage block even in streaming mode - it carries the cache-hit numbers
            try { body.put("stream_options", new JSONObject().put("include_usage", true)); }
            catch (Throwable ignored) { }
            // thinking is ON by default on DeepSeek v4 - off is the fastest path for UI work
            if (!minimal && thinking == 0) {
                JSONObject t = new JSONObject();
                t.put("type", "disabled");
                body.put("thinking", t);
            } else if (!minimal && thinking == 1) {
                body.put("reasoning_effort", "low");
            } else if (!minimal && thinking == 2) {
                body.put("reasoning_effort", "high");
            }
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }

            byte[] payload = body.toString().getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            active = conn;
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(MODEL_WAIT_TIMEOUT_MS);
            conn.setReadTimeout(MODEL_WAIT_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream, application/json");
            conn.setRequestProperty("User-Agent", "AI-ssistant/1.3 (Android)");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
            } finally {
                os.close();
            }

            int code = conn.getResponseCode();
            if (code >= 400) {
                out.error = "HTTP " + code + ": " + cut(slurp(conn.getErrorStream()), 600);
                android.util.Log.e("AIssistant", "HTTP " + code + " from " + url + " :: " + out.error);
                // a provider that rejects our optional extras answers 400; retry the plain OpenAI body once
                if (code == 400 && !minimal) {
                    plainOnly.add(base);      // remember: this endpoint does not take our extras
                    android.util.Log.i("AIssistant", "HTTP 400 - ulang tanpa field khusus provider (diingat untuk " + base + ")");
                    Reply plain = complete(baseUrl, apiKey, model, messages, tools, temperature, thinking,
                            timeoutSec, maxTokens, cb, true);
                    boolean plainOk = plain != null && plain.ok
                            && (plain.error == null || plain.error.isEmpty());
                    if (plainOk) return plain;
                    if (plain != null && plain.error != null && !plain.error.isEmpty()) {
                        out.error = plain.error;
                        android.util.Log.e("AIssistant", "HTTP 400 also on the plain body :: " + out.error);
                    }
                }
                return out;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            StringBuilder raw = new StringBuilder();
            TreeMap<Integer, JSONObject> calls = new TreeMap<>();
            boolean sse = conn.getContentType() != null && conn.getContentType().contains("text/event-stream");
            boolean first = true;
            boolean done = false;
            String finishReason = "";
            String line;
            while ((line = br.readLine()) != null) {
                if (cancelled) {
                    out.error = "stopped";
                    return out;
                }
                if (first && line.trim().isEmpty()) continue;
                if (first) {
                    first = false;
                    sse = sse || line.startsWith("data:") || line.startsWith(":") || line.startsWith("event:");
                }
                if (!sse) {
                    raw.append(line).append('\n');
                    continue;
                }
                if (!line.startsWith("data:")) continue;
                String chunkText = line.substring(5).trim();
                if (chunkText.isEmpty()) continue;
                if ("[DONE]".equals(chunkText)) { done = true; break; }
                JSONObject chunk;
                try {
                    chunk = new JSONObject(chunkText);
                } catch (Throwable t) {
                    out.error = "invalid stream frame; no commands were executed";
                    return out;
                }
                if (chunk.has("error")) {
                    out.error = "provider stream error: " + cut(chunk.optString("error"), 400);
                    return out;
                }
                JSONObject usage = chunk.optJSONObject("usage");
                if (usage != null) {
                    out.promptTokens = usage.optInt("prompt_tokens", out.promptTokens);
                    out.completionTokens = usage.optInt("completion_tokens", out.completionTokens);
                    out.cacheHitTokens = usage.optInt("prompt_cache_hit_tokens", out.cacheHitTokens);
                    android.util.Log.i("AIssistant", "usage prompt=" + out.promptTokens
                            + " cacheHit=" + out.cacheHitTokens + " completion=" + out.completionTokens);
                }
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject choice = choices.optJSONObject(0);
                if (choice == null) continue;
                Object finish = choice.opt("finish_reason");
                if (finish instanceof String) finishReason = (String) finish;
                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) continue;

                // JSON null must stay "": optString() turns it into the string "null"
                Object pc = delta.opt("content");
                Object pr = delta.opt("reasoning_content");
                String piece = pc instanceof String ? (String) pc : "";
                String think = pr instanceof String ? (String) pr : "";
                if (!piece.isEmpty()) content.append(piece);
                if (!think.isEmpty()) reasoning.append(think);
                if (!piece.isEmpty() || !think.isEmpty()) out.responseStarted = true;
                if ((!piece.isEmpty() || !think.isEmpty()) && cb != null) {
                    cb.onDelta(content.toString(), reasoning.toString());
                }

                JSONArray tcs = delta.optJSONArray("tool_calls");
                if (tcs != null) {
                    if (tcs.length() > 0) out.responseStarted = true;
                    for (int i = 0; i < tcs.length(); i++) {
                        JSONObject d = tcs.optJSONObject(i);
                        if (d == null) continue;
                        int idx = d.optInt("index", 0);
                        JSONObject acc = calls.get(idx);
                        if (acc == null) {
                            acc = new JSONObject();
                            Object id0 = d.opt("id");
                            acc.put("id", id0 instanceof String && !((String) id0).isEmpty() ? id0 : "call_" + idx);
                            acc.put("type", "function");
                            acc.put("function", new JSONObject().put("name", "").put("arguments", ""));
                            calls.put(idx, acc);
                        }
                        Object idv = d.opt("id");
                        if (idv instanceof String && !((String) idv).isEmpty()) acc.put("id", idv);
                        JSONObject dfn = d.optJSONObject("function");
                        if (dfn != null) {
                            JSONObject afn = acc.optJSONObject("function");
                            if (afn == null) {
                                afn = new JSONObject().put("name", "").put("arguments", "");
                                acc.put("function", afn);
                            }
                            Object nv = dfn.opt("name");
                            if (nv instanceof String) {
                                String cur = afn.optString("name", "");
                                afn.put("name", cur.isEmpty() ? (String) nv : cur);
                            }
                            Object av = dfn.opt("arguments");
                            if (av instanceof String) afn.put("arguments", afn.optString("arguments", "") + av);
                        }
                    }
                }
            }

            if (sse) {
                if ((!done && finishReason.isEmpty()) || ("length".equals(finishReason) && content.length() == 0)
                        || "content_filter".equals(finishReason)) {
                    out.error = "incomplete model response (" + (finishReason.isEmpty()
                            ? "stream disconnected" : finishReason) + "); no commands were executed";
                    return out;
                }
                if (content.length() == 0 && calls.isEmpty()) {
                    out.error = "empty model response; no commands were executed";
                    return out;
                }
                out.ok = true;
                out.text = content.toString();
                out.reasoning = reasoning.toString();
                JSONArray arr = new JSONArray();
                for (JSONObject c : calls.values()) arr.put(c);
                out.toolCalls = arr;
                return out;
            }

            // ---- non-streaming fallback: the server ignored stream=true -------------------
            JSONObject resp = new JSONObject(raw.toString());
            JSONArray choices = resp.optJSONArray("choices");
            JSONObject message = null;
            if (choices != null && choices.length() > 0 && choices.optJSONObject(0) != null) {
                String finish = choices.optJSONObject(0).optString("finish_reason", "");
                if (("length".equals(finish) && out.text != null && out.text.length() == 0) || "content_filter".equals(finish)) {
                    out.error = "incomplete model response (" + finish + "); no commands were executed";
                    return out;
                }
                message = choices.optJSONObject(0).optJSONObject("message");
                if (message == null) {
                    String plain = choices.optJSONObject(0).optString("text", "");
                    if (!plain.isEmpty()) {
                        out.ok = true;
                        out.text = plain;
                        return out;
                    }
                }
            }
            if (message == null) {
                out.error = "unexpected response: " + cut(raw.toString(), 400);
                return out;
            }
            out.ok = true;
            out.text = message.optString("content", "");
            Object rc = message.opt("reasoning_content");
            if (rc instanceof String) out.reasoning = (String) rc;
            JSONArray callsArr = message.optJSONArray("tool_calls");
            if (callsArr != null) out.toolCalls = callsArr;
            JSONObject usage = resp.optJSONObject("usage");
            if (usage != null) {
                out.promptTokens = usage.optInt("prompt_tokens", 0);
                out.completionTokens = usage.optInt("completion_tokens", 0);
            }
            return out;
        } catch (java.net.SocketTimeoutException t) {
            out.error = "model wait timed out after 20 seconds; no commands were executed";
            return out;
        } catch (Throwable t) {
            out.error = String.valueOf(t);
            return out;
        } finally {
            if (active == conn) active = null;
            if (conn != null) conn.disconnect();
        }
    }

    private static String slurp(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
