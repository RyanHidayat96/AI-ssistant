package com.aissistants.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Endpoint configuration + all chat sessions, kept in the app's PRIVATE SharedPreferences so the
 * API key never lands in a world-readable file. Nothing here is ever sent anywhere except the
 * endpoint the user configured.
 *
 * Sessions are one JSON array under "sessions"; the active one's id lives under "activeId".
 * A single legacy transcript ("history") from older builds is migrated by MainActivity.
 */
final class Store {

    private static final String PREF = "aissistants";
    private final SharedPreferences sp;

    Store(Context ctx) {
        this.sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---- agent parameters ---------------------------------------------------------------

    int temperature() { return sp.getInt("temperature", 30); }        // percent, 0..100
    int timeoutSec() { return sp.getInt("timeoutSec", 180); }
    boolean autoRun() { return sp.getBoolean("autoRun", false); }
    int thinking() { return sp.getInt("thinking", 3); }   // 0 off, 1 low, 2 high, 3 auto

    /** cached inventory of the device's tools, injected into the system prompt */
    String toolProbe() { return sp.getString("toolProbe", ""); }
    long toolProbeAt() { return sp.getLong("toolProbeAt", 0L); }
    void setToolProbe(String s) { sp.edit().putString("toolProbe", s).putLong("toolProbeAt", System.currentTimeMillis()).apply(); }

    void save(int temperature, int timeoutSec, boolean autoRun, int thinking) {
        sp.edit()
                .putInt("temperature", Math.max(0, Math.min(100, temperature)))
                .putInt("timeoutSec", Math.max(20, Math.min(1800, timeoutSec)))
                .putBoolean("autoRun", autoRun)
                .putInt("thinking", Math.max(0, Math.min(3, thinking)))
                .apply();
    }

    // ---- providers & models -------------------------------------------------------------

    String providersJson() { return sp.getString("providers", ""); }
    void saveProviders(String json) { sp.edit().putString("providers", json == null ? "" : json).apply(); }

    String modelsJson() { return sp.getString("models", ""); }
    void saveModels(String json) { sp.edit().putString("models", json == null ? "" : json).apply(); }

    String activeModelId() { return sp.getString("activeModelId", ""); }
    void setActiveModelId(String id) { sp.edit().putString("activeModelId", id == null ? "" : id).apply(); }

    // ---- permission gate (installs etc.) -------------------------------------------------

    boolean allowAlways(String cat) { return sp.getBoolean("allow_" + cat, false); }
    void setAllowAlways(String cat, boolean b) { sp.edit().putBoolean("allow_" + cat, b).apply(); }

    /** per-conversation grant ("di percakapan ini") - keyed by session id so it survives a restart */
    boolean allowChat(String cat, String sid) { return sp.getBoolean("allowc_" + cat + "_" + sid, false); }
    void setAllowChat(String cat, String sid, boolean b) {
        if (sid == null || sid.isEmpty()) return;
        sp.edit().putBoolean("allowc_" + cat + "_" + sid, b).apply();
    }

    // ---- legacy single endpoint (migration source only) ---------------------------------

    String legacyBaseUrl() { return sp.getString("baseUrl", ""); }
    String legacyApiKey() { return sp.getString("apiKey", ""); }
    String legacyModel() { return sp.getString("model", ""); }
    void clearLegacyEndpoint() { sp.edit().remove("baseUrl").remove("apiKey").remove("model").apply(); }

    // ---- chat sessions ------------------------------------------------------------------

    String sessionsJson() { return sp.getString("sessions", ""); }

    void saveSessions(String json) {
        sp.edit().putString("sessions", json == null ? "" : json).apply();
    }

    String activeId() { return sp.getString("activeId", ""); }

    void setActiveId(String id) {
        sp.edit().putString("activeId", id == null ? "" : id).apply();
    }

    // ---- legacy single-transcript (pre-sessions builds) ---------------------------------

    String legacyHistory() { return sp.getString("history", ""); }

    void clearLegacyHistory() { sp.edit().remove("history").apply(); }
}
