package com.aissistants.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal OpenAI-compatible chat client. It runs in a worker thread and speaks plain
 * HttpURLConnection, so any endpoint works (OpenAI, OpenRouter, Groq, a local llama.cpp /
 * Ollama / LM Studio server on the LAN) without CORS or extra dependencies.
 */
final class AiClient {

    static final class Reply {
        boolean ok;
        String text = "";
        JSONArray toolCalls = new JSONArray();
        String error = "";
        int promptTokens;
        int completionTokens;
    }

    private AiClient() { }

    static Reply complete(String baseUrl, String apiKey, String model, JSONArray messages,
                          JSONArray tools, double temperature, int timeoutSec) {
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
            body.put("stream", false);
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }

            byte[] payload = body.toString().getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(Math.max(30, timeoutSec) * 1000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "AI-ssistants/1.0 (Android)");
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
            String text = slurp(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                out.error = "HTTP " + code + ": " + cut(text, 600);
                return out;
            }

            JSONObject resp = new JSONObject(text);
            JSONArray choices = resp.optJSONArray("choices");
            JSONObject message = null;
            if (choices != null && choices.length() > 0 && choices.optJSONObject(0) != null) {
                message = choices.optJSONObject(0).optJSONObject("message");
                if (message == null) {
                    // some servers answer with a plain "text" field
                    String plain = choices.optJSONObject(0).optString("text", "");
                    if (!plain.isEmpty()) {
                        out.ok = true;
                        out.text = plain;
                        return out;
                    }
                }
            }
            if (message == null) {
                out.error = "unexpected response: " + cut(text, 400);
                return out;
            }
            out.ok = true;
            out.text = message.optString("content", "");
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls != null) out.toolCalls = calls;
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
