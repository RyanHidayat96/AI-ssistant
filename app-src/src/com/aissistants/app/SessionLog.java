package com.aissistants.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Durable per-session log.
 *
 * Every bubble is appended to files/sessions/&lt;id&gt;.jsonl the moment it is created and the stream
 * is flushed immediately, so a run keeps its work even when Android kills the process - HyperOS
 * memory kills, an app crash, or a full runtime (zygote/system_server) restart. The old behaviour
 * kept a whole conversation in memory and only wrote the session blob when a run finished, so a
 * kill in the middle of a long run threw the entire run away.
 *
 * MainActivity merges these logs back into the session list on every launch (recoverSessions()),
 * which also repairs a session list that lost an entry.
 */
final class SessionLog {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    /** never let one oversized field (attachment payload) bloat the log */
    private static final int MAX_FIELD = 300000;

    private final File dir;
    private final File bak;
    private final Map<String, Integer> rows = new HashMap<String, Integer>();

    SessionLog(File filesDir) {
        File d = new File(filesDir, "sessions");
        try { if (!d.exists()) d.mkdirs(); } catch (Throwable ignored) { }
        this.dir = d;
        this.bak = new File(filesDir, "sessions.bak.json");
    }

    static String safe(String id) {
        if (id == null || id.isEmpty()) return "default";
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private File fileOf(String id) { return new File(dir, safe(id) + ".jsonl"); }

    /** append one bubble; flushed right away so a process kill cannot lose it */
    void append(String sid, JSONObject row) {
        if (row == null) return;
        final String id = sid == null ? "" : sid;
        final String line = shrink(row);
        if (line == null) return;
        synchronized (rows) { Integer n = rows.get(id); rows.put(id, n == null ? 1 : n + 1); }
        IO.execute(new Runnable() {
            @Override public void run() {
                Writer w = null;
                try {
                    w = new OutputStreamWriter(new FileOutputStream(fileOf(id), true), "UTF-8");
                    w.write(line);
                    w.write("\n");
                    w.flush();
                } catch (Throwable ignored) {
                } finally {
                    try { if (w != null) w.close(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private static String shrink(JSONObject row) {
        try {
            String s = row.toString();
            if (s.length() < MAX_FIELD) return s;
            JSONObject o = new JSONObject(s);
            List<String> big = new ArrayList<String>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.opt(k);
                if (v instanceof String && ((String) v).length() > MAX_FIELD) big.add(k);
            }
            for (int i = 0; i < big.size(); i++) o.put(big.get(i), "\u2026[too large to log]");
            return o.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** bubbles read back from this session's log, in append order */
    JSONArray read(String sid) {
        JSONArray out = new JSONArray();
        BufferedReader r = null;
        try {
            File f = fileOf(sid);
            if (!f.exists() || f.length() <= 0) return out;
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.isEmpty()) continue;
                try { out.put(new JSONObject(l)); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** session ids that have a non-empty log */
    List<String> ids() {
        List<String> out = new ArrayList<String>();
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return out;
            for (int i = 0; i < fs.length; i++) {
                String n = fs[i].getName();
                if (!n.endsWith(".jsonl") || fs[i].length() <= 2) continue;
                out.add(n.substring(0, n.length() - 6));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** make the log mirror the session exactly (after a session was saved / cleared / deleted) */
    void sync(String sid, JSONArray bubbles, String title) {
        if (sid == null || sid.isEmpty()) return;
        final String id = sid;
        final int mem = bubbles == null ? 0 : bubbles.length();
        synchronized (rows) { rows.put(id, mem); }
        if (read(id).length() == mem) return;
        final StringBuilder sb = new StringBuilder();
        if (bubbles != null) {
            for (int i = 0; i < bubbles.length(); i++) {
                JSONObject o = bubbles.optJSONObject(i);
                if (o == null) continue;
                String l = shrink(o);
                if (l != null) sb.append(l).append('\n');
            }
        }
        final String body = sb.toString();
        IO.execute(new Runnable() {
            @Override public void run() { writeAtomic(fileOf(id), body); }
        });
    }

    void delete(final String sid) {
        if (sid == null || sid.isEmpty()) return;
        synchronized (rows) { rows.remove(sid); }
        IO.execute(new Runnable() {
            @Override public void run() {
                try { fileOf(sid).delete(); } catch (Throwable ignored) { }
            }
        });
    }

    /** rolling copy of the last good session blob, used when the prefs blob will not parse */
    void backup(String raw) {
        if (raw == null || raw.isEmpty()) return;
        final String s = raw;
        IO.execute(new Runnable() {
            @Override public void run() { writeAtomic(bak, s); }
        });
    }

    String readBackup() {
        BufferedReader r = null;
        try {
            if (!bak.exists() || bak.length() <= 2) return "";
            r = new BufferedReader(new InputStreamReader(new FileInputStream(bak), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
    }

    /** temp file + rename: a half written log can never be observed */
    private static void writeAtomic(File f, String body) {
        FileOutputStream o = null;
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        try {
            o = new FileOutputStream(tmp);
            OutputStreamWriter w = new OutputStreamWriter(o, "UTF-8");
            w.write(body == null ? "" : body);
            w.flush();
            try { o.getFD().sync(); } catch (Throwable ignored) { }
            w.close();
            o = null;
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Throwable ignored) {
        } finally {
            try { if (o != null) o.close(); } catch (Throwable ignored) { }
            try { if (tmp.exists()) tmp.delete(); } catch (Throwable ignored) { }
        }
    }
}
