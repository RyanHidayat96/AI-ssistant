package com.aissistant.app;

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

    private static final String PREF = "aissistant";
    static final String LANGUAGE_SYSTEM = "system";
    static final String LANGUAGE_INDONESIAN = "id";
    static final String LANGUAGE_ENGLISH = "en";
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

    // ---- language -----------------------------------------------------------------------

    String languageMode() {
        return normalizeLanguageMode(sp.getString("languageMode", LANGUAGE_SYSTEM));
    }

    void setLanguageMode(String mode) {
        sp.edit().putString("languageMode", normalizeLanguageMode(mode)).apply();
    }

    private static String normalizeLanguageMode(String mode) {
        if (LANGUAGE_INDONESIAN.equals(mode) || LANGUAGE_ENGLISH.equals(mode)) return mode;
        return LANGUAGE_SYSTEM;
    }

    // ---- app lock -----------------------------------------------------------------------

    boolean appLockEnabled() {
        return sp.getBoolean("appLockEnabled", false)
                && !sp.getString("appLockHash", "").isEmpty()
                && !sp.getString("appLockSalt", "").isEmpty();
    }
    String appLockHash() { return sp.getString("appLockHash", ""); }
    String appLockSalt() { return sp.getString("appLockSalt", ""); }
    String appLockKdf() { return sp.getString("appLockKdf", ""); }
    int appLockIterations() { return sp.getInt("appLockIterations", 0); }
    boolean fingerprintUnlockEnabled() { return sp.getBoolean("fingerprintUnlockEnabled", false); }

    void enableAppLock(AppLock.PasswordHash record) {
        if (record == null) return;
        sp.edit()
                .putBoolean("appLockEnabled", true)
                .putString("appLockHash", record.hash)
                .putString("appLockSalt", record.salt)
                .putString("appLockKdf", record.kdf)
                .putInt("appLockIterations", record.iterations)
                .apply();
    }

    void disableAppLock() {
        sp.edit()
                .putBoolean("appLockEnabled", false)
                .putBoolean("fingerprintUnlockEnabled", false)
                .remove("appLockHash")
                .remove("appLockSalt")
                .remove("appLockKdf")
                .remove("appLockIterations")
                .apply();
    }

    void setFingerprintUnlockEnabled(boolean enabled) {
        sp.edit().putBoolean("fingerprintUnlockEnabled", enabled && appLockEnabled()).apply();
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
