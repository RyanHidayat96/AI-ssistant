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

    // ---- endpoint / agent configuration ------------------------------------------------

    String baseUrl() { return sp.getString("baseUrl", ""); }
    String apiKey() { return sp.getString("apiKey", ""); }
    String model() { return sp.getString("model", ""); }
    int maxSteps() { return sp.getInt("maxSteps", 12); }
    int temperature() { return sp.getInt("temperature", 30); }        // percent, 0..100
    int timeoutSec() { return sp.getInt("timeoutSec", 180); }
    boolean autoRun() { return sp.getBoolean("autoRun", true); }

    void save(String baseUrl, String apiKey, String model, int maxSteps, int temperature,
              int timeoutSec, boolean autoRun) {
        sp.edit()
                .putString("baseUrl", baseUrl == null ? "" : baseUrl.trim())
                .putString("apiKey", apiKey == null ? "" : apiKey.trim())
                .putString("model", model == null ? "" : model.trim())
                .putInt("maxSteps", Math.max(1, Math.min(40, maxSteps)))
                .putInt("temperature", Math.max(0, Math.min(100, temperature)))
                .putInt("timeoutSec", Math.max(20, Math.min(1800, timeoutSec)))
                .putBoolean("autoRun", autoRun)
                .apply();
    }

    boolean configured() { return !baseUrl().isEmpty() && !model().isEmpty(); }

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
