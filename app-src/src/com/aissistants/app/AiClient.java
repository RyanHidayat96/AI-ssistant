package com.aissistants.app;

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

    static final class Reply {
        boolean ok;
        String text = "";
        JSONArray toolCalls = new JSONArray();
        String error = "";
        int promptTokens;
        int completionTokens;
    }

    private AiClient() { }

    /** the live connection, so the Stop button can abort a blocked read */
    private static volatile HttpURLConnection active;

    static void cancel() {
        HttpURLConnection c = active;
        if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
    }

    static Reply complete(String baseUrl, String apiKey, String model, JSONArray messages,
                          JSONArray tools, double temperature, int timeoutSec, StreamCb cb) {
        Reply out = new Reply();
        HttpURLConnection conn = null;
        try {
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

            JSONObject body = new JSONObject();
            body.put("model", model.trim());
            body.put("messages", messages);
            body.put("temperature", temperature);
            body.put("max_tokens", 4096);
            body.put("stream", true);
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }

            byte[] payload = body.toString().getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            active = conn;
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(Math.max(60, timeoutSec) * 1000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream, application/json");
            conn.setRequestProperty("User-Agent", "AI-ssistants/1.3 (Android)");
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
                return out;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            StringBuilder raw = new StringBuilder();
            TreeMap<Integer, JSONObject> calls = new TreeMap<>();
            boolean sse = false;
            boolean first = true;
            String line;
            while ((line = br.readLine()) != null) {
                if (first) {
                    first = false;
                    sse = line.startsWith("data:");
                }
                if (!sse) {
                    raw.append(line).append('\n');
                    continue;
                }
                if (!line.startsWith("data:")) continue;
                String chunkText = line.substring(5).trim();
                if (chunkText.isEmpty() || "[DONE]".equals(chunkText)) continue;
                JSONObject chunk;
                try {
                    chunk = new JSONObject(chunkText);
                } catch (Throwable t) {
                    continue;
                }
                JSONObject usage = chunk.optJSONObject("usage");
                if (usage != null) {
                    out.promptTokens = usage.optInt("prompt_tokens", out.promptTokens);
                    out.completionTokens = usage.optInt("completion_tokens", out.completionTokens);
                }
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject choice = choices.optJSONObject(0);
                if (choice == null) continue;
                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) continue;

                // JSON null must stay "": optString() turns it into the string "null"
                Object pc = delta.opt("content");
                Object pr = delta.opt("reasoning_content");
                String piece = pc instanceof String ? (String) pc : "";
                String think = pr instanceof String ? (String) pr : "";
                if (!piece.isEmpty()) content.append(piece);
                if (!think.isEmpty()) reasoning.append(think);
                if ((!piece.isEmpty() || !think.isEmpty()) && cb != null) {
                    cb.onDelta(content.toString(), reasoning.toString());
                }

                JSONArray tcs = delta.optJSONArray("tool_calls");
                if (tcs != null) {
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
                            if (nv instanceof String) afn.put("name", afn.optString("name", "") + nv);
                            Object av = dfn.opt("arguments");
                            if (av instanceof String) afn.put("arguments", afn.optString("arguments", "") + av);
                        }
                    }
                }
            }

            if (sse) {
                out.ok = true;
                out.text = content.toString();
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
            JSONArray callsArr = message.optJSONArray("tool_calls");
            if (callsArr != null) out.toolCalls = callsArr;
            JSONObject usage = resp.optJSONObject("usage");
            if (usage != null) {
                out.promptTokens = usage.optInt("prompt_tokens", 0);
                out.completionTokens = usage.optInt("completion_tokens", 0);
            }
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
