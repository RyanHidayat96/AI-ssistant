package com.aissistants.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Endpoint configuration + chat history, kept in the app's PRIVATE SharedPreferences so the API
 * key never lands in a world-readable file. Nothing here is ever sent anywhere except the
 * endpoint the user configured.
 */
final class Store {

    private static final String PREF = "aissistants";
    private final SharedPreferences sp;

    Store(Context ctx) {
        this.sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    String baseUrl() { return sp.getString("baseUrl", ""); }
    String apiKey() { return sp.getString("apiKey", ""); }
    String model() { return sp.getString("model", ""); }
    int maxSteps() { return sp.getInt("maxSteps", 12); }
    int temperature() { return sp.getInt("temperature", 30); }        // percent, 0..100
    int timeoutSec() { return sp.getInt("timeoutSec", 180); }
    boolean autoRun() { return sp.getBoolean("autoRun", true); }
    String history() { return sp.getString("history", ""); }

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

    void saveHistory(String json) { sp.edit().putString("history", json == null ? "" : json).apply(); }

    boolean configured() { return !baseUrl().isEmpty() && !model().isEmpty(); }
}
