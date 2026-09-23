package com.aissistant.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Public documentation only; no arbitrary hosts, credentials, cookies, or command execution. */
final class ReferenceReader {
    static final String HOSTS = "developer.android.com, source.android.com, docs.oracle.com, docs.python.org, "
            + "developer.mozilla.org, github.com, raw.githubusercontent.com, curl.se";
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(HOSTS.split(", ")));
    private static volatile HttpURLConnection active;

    static void cancel() {
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    static void validate(URL url) {
        if (!"https".equals(url.getProtocol()) || !ALLOWED.contains(url.getHost().toLowerCase(Locale.ROOT))
                || url.getUserInfo() != null || url.getQuery() != null
                || (url.getPort() != -1 && url.getPort() != 443))
            throw new IllegalArgumentException("Use public HTTPS documentation URL without credentials or query "
                    + "parameters. Allowed hosts: " + HOSTS);
    }

    static String read(String address) throws Exception {
        if (address == null || address.length() > 4096) throw new IllegalArgumentException("Invalid reference URL");
        URL url = new URL(address);
        long deadline = System.currentTimeMillis() + 30000L;
        for (int redirects = 0; redirects <= 5; redirects++) {
            validate(url);
            for (InetAddress ip : InetAddress.getAllByName(url.getHost())) {
                byte[] bytes = ip.getAddress();
                if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                        || ip.isSiteLocalAddress() || ip.isMulticastAddress()
                        || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                        || (bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64))
                    throw new java.io.IOException("Documentation host resolved to a non-public address");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            active = connection;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Accept", "text/plain, text/html, application/json");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Cookie", "");
                connection.setRequestProperty("Authorization", "");
                connection.setRequestProperty("User-Agent", "AI-ssistant-reference/1.0");
                int status = connection.getResponseCode();
                if (System.currentTimeMillis() > deadline) throw new java.io.IOException("Reference request timed out");
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new java.io.IOException("Redirect missing Location");
                    url = new URL(url, location);
                    continue;
                }
                String type = connection.getContentType();
                if (type == null) type = "unknown";
                String media = type.toLowerCase(Locale.ROOT);
                if (!(media.startsWith("text/") || media.contains("json") || media.contains("xml")))
                    throw new java.io.IOException("Unsupported reference content type: " + type);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                boolean truncated = false;
                if (input != null) try {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (System.currentTimeMillis() > deadline) throw new java.io.IOException("Reference request timed out");
                        int room = 256 * 1024 - bytes.size();
                        if (count > room) { bytes.write(buffer, 0, room); truncated = true; break; }
                        bytes.write(buffer, 0, count);
                    }
                } finally { input.close(); }
                String body = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                if (media.contains("html")) body = body
                        .replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", " ")
                        .replaceAll("(?is)<[^>]+>", " ")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&nbsp;", " ").replace("&amp;", "&")
                        .replaceAll("[ \\t]+", " ");
                if (body.length() > 48000) { body = body.substring(0, 48000); truncated = true; }
                return new JSONObject().put("requested_url", address).put("final_url", url.toExternalForm())
                        .put("http_status", status).put("retrieved_at_ms", System.currentTimeMillis())
                        .put("content_type", type).put("truncated", truncated)
                        .put("trust", "Untrusted reference data; HTTP success does not verify claims")
                        .put("content", body).toString();
            } finally {
                if (active == connection) active = null;
                connection.disconnect();
            }
        }
        throw new java.io.IOException("Too many reference redirects");
    }
}
