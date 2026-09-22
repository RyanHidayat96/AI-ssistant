package com.aissistants.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fast-task catalog: plain instructions -> one root-shell command chain, no model round-trip.
 *
 * Every rule is a line in {@link #RULES}; adding support for a new instruction is adding one rule.
 * Queries report their output straight into the transcript; anything that changes the device is
 * still gated by the app's permission categories (system / destructive / install / egress).
 */
final class FastTasks {

    /** one instruction -> command rule */
    static final class Rule {
        final String id;
        final Pattern re;
        final String cmd;          // {pkg}, {arg}, {num}, {path}, {dir} get substituted
        final String risk;         // none | system | destructive | install | egress
        final String summary;      // {out} = trimmed output
        final boolean showOutput;
        final String needArg;      // "pkg" | "arg" | "num" | "path" | "dir" | ""

        Rule(String id, String re, String needArg, String cmd, String risk, String summary, boolean showOutput) {
            this.id = id;
            this.re = Pattern.compile(re, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            this.needArg = needArg;
            this.cmd = cmd;
            this.risk = risk;
            this.summary = summary;
            this.showOutput = showOutput;
        }
    }

    /** words that can never be an app name - skipped when hunting for one inside a sentence */
    private static final java.util.HashSet<String> STOPWORDS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "aplikasi", "app", "apps", "pesan", "message", "muncul", "menampilkan", "kenapa", "kok", "why",
            "tidak", "gak", "tdk", "bisa", "digunakan", "dipakai", "jalan", "error", "gagal", "nolak",
            "crash", "force", "close", "keluar", "suspicious", "activity", "detected", "tamper", "root",
            "yang", "dan", "atau", "saya", "aku", "gua", "the", "this", "that", "with", "from", "os",
            "handphone", "hp", "device", "cek", "lihat", "tolong", "bantu", "adalah", "itu", "ini",
            "agar", "supaya", "semua", "fitur", "fiturnya", "fungsi", "berfungsi", "perlu", "tanpa",
            "vip", "premium", "pro", "subscribe", "subscription", "berlangganan", "langganan", "bayar",
            "paid", "license", "lisensi", "purchase", "billing", "entitlement", "unlock", "terkunci"));

    private static final String FOCUS_CMD =
            "(dumpsys window 2>/dev/null | grep -m8 -E 'mCurrentFocus|mFocusedApp'; "
                    + "dumpsys activity activities 2>/dev/null | grep -m8 -E 'topResumedActivity|mResumedActivity|ResumedActivity') "
                    + "| grep -v com.aissistants.app | head -1";

    private static final Rule[] RULES = {
        // ---- read-only device facts -------------------------------------------------------
        r("battery", "(baterai|battery|daya|isi baterai)", "",
          "dumpsys battery | grep -E 'level|status|temperature|voltage|health'",
          "none", "Baterai: {out}", true),
        r("storage", "(penyimpanan|storage|disk|ruang|space|kapasitas)", "",
          "df -h /data /storage/emulated 2>/dev/null | grep -vE '^Filesystem'",
          "none", "Penyimpanan:\n{out}", true),
        r("memory", "(ram|memori|memory)", "",
          "free -h 2>/dev/null || head -3 /proc/meminfo",
          "none", "Memori:\n{out}", true),
        r("cpu-load", "(beban cpu|cpu load|load average|sibuk|heat|panas|temperatur|suhu)", "",
          "cat /proc/loadavg; echo; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null | head -1",
          "none", "CPU/suhu: {out}", true),
        r("uptime", "(uptime|udah nyala|berapa lama nyala|since boot|waktu nyala)", "",
          "cat /proc/uptime; echo; getprop ro.boot.bootreason 2>/dev/null",
          "none", "Uptime (detik; bootreason): {out}", true),
        r("wifi-status", "(status wifi|wifi status|wifi apa|ssid|koneksi wifi|jaringan wifi|cek wifi|cek jaringan)", "",
          "cmd wifi status | head -8",
          "none", "WiFi: {out}", true),
        r("net-info", "(ip (hp|ku|address|local)|alamat ip|ip lokal|ip hp)", "",
          "ip -4 addr show 2>/dev/null | grep -E 'inet ' | grep -v 127.0.0.1",
          "none", "IP: {out}", true),
        r("foreground", "(aplikasi (apa|yang) (lagi )?(terbuka|di depan|jalan)|app (apa )?(di depan|foreground)|lagi buka apa|current app)", "",
          FOCUS_CMD,
          "none", "Di depan sekarang: {out}", true),
        r("top-cpu", "(proses (paling )?(berat|banyak|makan|ram|cpu)|top process|proses terberat|process(es)? top)", "",
          "top -b -n1 -o %CPU 2>/dev/null | head -14",
          "none", "Proses teratas:\n{out}", true),
        r("root-check", "(cek root|root status|masih root|root aktif|root check)", "",
          "id -u; getenforce 2>/dev/null; uname -r",
          "none", "Root: uid={out}", true),
        r("app-list", "(daftar (semua )?(app|aplikasi)|list app|berapa (jumlah )?(app|aplikasi)|installed apps)", "",
          "pm list packages -3 --user 0 2>/dev/null | sed s/package:// | sort | tr '\\n' ' '; echo; pm list packages -3 --user 0 2>/dev/null | wc -l",
          "none", "Aplikasi terpasang:\n{out}", true),
        r("app-count", "(jumlah (app|aplikasi))", "",
          "pm list packages -3 --user 0 2>/dev/null | wc -l",
          "none", "Total aplikasi pihak ketiga: {out}", true),
        r("dir-list", "(isi (folder|direktori|dir)|list( isi)? (folder|direktori|file di)|apa isi )", "arg",
          "ls -la {arg} 2>&1 | head -40",
          "none", "Isi {arg}:\n{out}", true),
        r("file-read", "(baca (file|isi file)|isi file|tampilkan file|cat file)", "arg",
          "head -60 {arg} 2>&1",
          "none", "Isi {arg}:\n{out}", true),
        r("disk-usage", "(ukuran (folder|direktori|dir)|berapa besar|size of|du folder)", "arg",
          "du -sh {arg} 2>&1",
          "none", "Ukuran {arg}: {out}", true),
        r("app-info", "(info (app|aplikasi)|versi (app|aplikasi)|detail (app|aplikasi)|uid|ukuran data)", "pkg",
          "dumpsys package {pkg} | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime' | head -6;"
        + " echo; nsenter -t 1 -m -- du -sh /data/data/{pkg} 2>/dev/null",
          "none", "Info {pkg}:\n{out}", true),
        r("app-pid", "(pid (app|aplikasi)|proses (app|aplikasi))", "pkg",
          "pidof {pkg} 2>/dev/null || echo 'tidak jalan'",
          "none", "PID {pkg}: {out}", true),
        r("app-perms", "(izin (app|aplikasi)|permission (app|aplikasi)|hak akses)", "pkg",
          "dumpsys package {pkg} | grep -E 'granted=true' | head -25",
          "none", "Izin {pkg}:\n{out}", true),
        r("logcat-app", "(log (app|aplikasi)|logcat (app|aplikasi)|error (app|aplikasi))", "pkg",
          "logcat -d -t 200 2>/dev/null | grep -i {pkg} | tail -25",
          "none", "Log terakhir {pkg}:\n{out}", true),
        r("notif-count", "(berapa notif|jumlah notif|notifikasi (ada )?berapa)", "",
          "dumpsys notification 2>/dev/null | grep -c 'NotificationRecord'",
          "none", "Jumlah notifikasi aktif: {out}", true),

        r("app-diagnose", "(?=.*(nolak|tidak bisa|gak bisa|tdk bisa|gagal|error|crash|force close|keluar sendiri|suspicious|tamper|kenapa|kok|why|fitur|fungsi|berfungsi|vip|premium|pro|subscribe|subscription|berlangganan|langganan|bayar|paid|license|lisensi|purchase|billing|entitlement|unlock|terkunci))(?=.*(app|aplikasi|@[a-z0-9_.]+))", "pkg",
          "p={pkg}; echo '== IDENTITAS:'; dumpsys package $p 2>/dev/null | grep -E 'versionName|lastUpdateTime|installerPackageName|firstInstallTime' | head -4;"
        + " echo '== ERROR TERAKHIR (logcat):'; (logcat -d -t 800 2>/dev/null | grep -iE \"$p|unknownhost|exception|fatal|crash|integrity|tamper|license|licensecheck|billing|purchase|subscription|subscribe|premium|vip|entitlement|paywall|pairip|zimperium|root\" | tail -24 || true);"
        + " echo '== JEJAK OS YANG DIBACA APP:'; f=$(ps -A 2>/dev/null | grep -icE 'frida|objection'); h=$(ss -ltn 2>/dev/null | grep -cE '27042|27043'); echo \"proses-hook=$f port-hook=$h selinux=$(getenforce) dev_opts=$(settings get global development_settings_enabled) mock=$(settings get secure mock_location)\";"
        + " echo '== RESIDU BINER:'; (ls -d /data/local/tmp/*frida* /data/local/tmp/re.frida.server /data/local/tmp/ai-ssistants/*/frida-server 2>/dev/null || echo '  tidak ada');"
        + " echo '== DI DEPAN:'; (" + FOCUS_CMD + " || true);"
        + " echo '== PESAN APP (kalau ada):'; uiautomator dump /sdcard/.diag.xml >/dev/null 2>&1; (grep -oE 'text=\"[^\"]{6,90}\"' /sdcard/.diag.xml 2>/dev/null | head -6 || echo '  (layar tidak terbaca)');"
        + " if [ \"$f\" = 0 ] && [ \"$h\" = 0 ]; then echo 'VERDICT: jejak hooking bersih - kalau app masih nolak, cek dev options/mock/sertifikat/APK modif'; else echo 'VERDICT: ADA JEJAK HOOKING - ini penyebab umum pesan tamper. Jalankan: bersihkan jejak'; fi; true",
          "none", "Diagnosa {pkg}:\n{out}", true),

        // ---- device hygiene ------------------------------------------------------------------
        r("hygiene-scan", "(cek (jejak|deteksi|integritas|kebersihan)|deteksi root|root terdeteksi|suspicious activity|kenapa (app|aplikasi)[^\\n]{0,30}(nolak|gagal|gak bisa|tidak bisa|error)|device hygiene)", "",
          Hygiene.scanCmd(), "none", "Hasil cek integritas OS:\n{out}", true),
        r("agent-tools", "(tool (apa|yang) (saja|sudah ada)|tools? agent|cache tool|daftar tool|tool .*terpasang|tool apa)", "",
          "T=\"$TOOLS\"; echo \"cache: $T\"; du -sh \"$T\" 2>/dev/null || true; ls -la \"$T\" 2>/dev/null || true; echo '-- catatan --'; cat \"$T/agent-tools.md\" 2>/dev/null || echo '(belum ada catatan)'",
          "none", "Tool agent (cache bersama, dipakai ulang antar sesi):\n{out}", true),
        r("settings-restore", "(pulihkan (setelan|setting|pengaturan)|kembalikan setelan|restore settings|balikin setelan)", "",
          "f=/data/local/tmp/ai-ssistants/state.log; if [ ! -s \"$f\" ]; then echo 'belum ada catatan perubahan'; exit 0; fi;"
        + " awk '{a[NR]=$0} END{for(i=NR;i>0;i--) print a[i]}' \"$f\" | awk '!/^SETTINGS /{next} !seen[$2\" \"$3]++{print $2, $3, $4}'"
        + " | while read -r ns key val; do settings put \"$ns\" \"$key\" \"$val\" >/dev/null 2>&1 && echo \"$ns/$key -> $val\"; done;"
        + " echo; echo 'catatan dibiarkan supaya bisa dipakai lagi'",
          "none", "Setelan dipulihkan ke nilai sebelum diubah:\n{out}", true),
        r("hygiene-clean", "(bersihkan? (jejak|sampah|artefak)|hapus jejak|matikan (frida|hook)|stop (frida|hook)|leave no trace)", "",
          Hygiene.cleanCmd(), "none", "Jejak hooking dibersihkan:\n{out}", true),

        // ---- navigation/UI without a real world effect -------------------------------------
        r("screenshot", "(screenshot|tangkapan layar|ss layar)", "",
          "mkdir -p /sdcard/ai-ssistants; screencap -p /sdcard/ai-ssistants/shot-$(date +%H%M%S).png"
        + " && ls -t /sdcard/ai-ssistants/*.png | head -1",
          "none", "Screenshot disimpan: {out}", true),
        r("screen-on", "(nyalakan layar|bangunkan layar|wake|hidupkan layar|buka layar)", "",
          "input keyevent 224", "none", "Layar dinyalakan.", false),
        r("screen-off", "(matikan layar|lock (hp|layar)|kunci (hp|layar)|sleep hp)", "",
          "input keyevent 26", "none", "Layar dimatikan/dikunci.", false),
        r("back", "(tekan back|pencet back|tombol back|go back)", "",
          "input keyevent 4", "none", "Tombol back ditekan.", false),
        r("home", "(ke home|tekan home|tombol home|balik ke home)", "",
          "input keyevent 3", "none", "Ke home.", false),
        r("volume-up", "(volume (naik|up|tambah)|kerasin (volume|suara))", "",
          "input keyevent 24", "none", "Volume naik satu langkah.", false),
        r("volume-down", "(volume (turun|down|kurang)|kecilin (volume|suara))", "",
          "input keyevent 25", "none", "Volume turun satu langkah.", false),
        r("volume-mute", "(mute|bisu|senyap|mode silent)", "",
          "input keyevent 164", "none", "Mute aktif.", false),
        r("brightness-get", "(brightness (berapa|sekarang)|kecerahan (layar )?sekarang|cek brightness)", "",
          "settings get system screen_brightness",
          "none", "Brightness sekarang: {out}", true),
        r("brightness-set", "(brightness|kecerahan|terangkan layar|redupkan layar|turunkan kecerahan|naikkan kecerahan)", "num",
          journal("system", "screen_brightness") + "settings put system screen_brightness {num}; settings get system screen_brightness",
          "system", "Brightness diubah: {out}", true),
        r("rotate-lock", "(kunci rotasi|matikan auto ?rotate|putar layar|rotate)", "num",
          journal("system", "accelerometer_rotation") + journal("system", "user_rotation")
        + "settings put system accelerometer_rotation 0; settings put system user_rotation {num}; echo ok",
          "system", "Rotasi layar diatur: {out}", true),
        r("timeout-set", "(screen timeout|timeout layar|layar mati setelah)", "num",
          journal("system", "screen_off_timeout") + "settings put system screen_off_timeout {num}; settings get system screen_off_timeout",
          "system", "Timeout layar (ms): {out}", true),
        r("dnd-on", "(jangan ganggu|do not disturb|dnd on|mode senyap total)", "",
          "cmd notification set_dnd on", "system", "Do Not Disturb aktif.", false),
        r("dnd-off", "(matikan (dnd|jangan ganggu|do not disturb))", "",
          "cmd notification set_dnd off", "system", "Do Not Disturb dimatikan.", false),

        // ---- device state changes (gated) ---------------------------------------------------
        r("wifi-on", "((nyalakan|aktifkan|hidupkan|on)(kan)? wifi|wifi (on|nyala|aktif))", "",
          "svc wifi enable; sleep 2; cmd wifi status | head -3", "system", "WiFi dinyalakan: {out}", true),
        r("wifi-off", "((matikan|nonaktifkan|off)(kan)? wifi|wifi (off|mati))", "",
          "svc wifi disable; echo wifi-off", "system", "WiFi dimatikan.", false),
        r("bt-on", "((nyalakan|aktifkan|hidupkan|on)(kan)? (bluetooth|bt)|(bluetooth|bt) (on|nyala))", "",
          "cmd bluetooth_manager enable 2>/dev/null || svc bluetooth enable; echo bt-on",
          "system", "Bluetooth dinyalakan.", false),
        r("bt-off", "((matikan|nonaktifkan|off)(kan)? (bluetooth|bt)|(bluetooth|bt) (off|mati))", "",
          "cmd bluetooth_manager disable 2>/dev/null || svc bluetooth disable; echo bt-off",
          "system", "Bluetooth dimatikan.", false),
        r("data-on", "((nyalakan|aktifkan|on)(kan)? (data|internet seluler|mobile data)|data seluler (on|nyala))", "",
          "svc data enable; echo data-on", "system", "Data seluler dinyalakan.", false),
        r("data-off", "((matikan|nonaktifkan|off)(kan)? (data|internet seluler|mobile data)|data seluler (off|mati))", "",
          "svc data disable; echo data-off", "system", "Data seluler dimatikan.", false),
        r("airplane-on", "(mode pesawat|airplane mode|mode terbang)", "",
          "cmd connectivity airplane-mode enable 2>/dev/null || settings put global airplane_mode_on 1; echo on",
          "system", "Mode pesawat aktif.", false),
        r("airplane-off", "(matikan mode pesawat|airplane mode off)", "",
          "cmd connectivity airplane-mode disable 2>/dev/null || settings put global airplane_mode_on 0; echo off",
          "system", "Mode pesawat dimatikan.", false),
        r("app-force-stop", "((matiin|matikan|tutup|kill|force ?stop|stop)(kan)? (app |aplikasi )?)", "pkg",
          "am force-stop {pkg} && echo stopped {pkg}",
          "system", "Aplikasi dihentikan: {pkg}", false),
        r("app-clear", "((hapus|bersihkan|clear)(kan)? (data|cache) (app |aplikasi )?)", "pkg",
          "pm clear {pkg}",
          "destructive", "Data aplikasi dibersihkan: {out}", true),
        r("apk-install", "((install|pasang)(kan)? (apk|aplikasi))", "arg",
          "pm install -r -d {arg} 2>&1 | tail -3",
          "install", "Hasil install: {out}", true),
        r("reboot", "(reboot|restart hp|nyalain ulang hp)", "",
          "svc power reboot", "system", "HP di-reboot.", false),
        r("shutdown", "(matiin hp|shutdown|power off)", "",
          "svc power shutdown", "system", "HP dimatikan.", false),
        r("settings-open", "(buka (settings|pengaturan)( (wifi|bluetooth|baterai|aplikasi|storage))?)", "",
          "am start -a android.settings.SETTINGS", "none", "Settings dibuka.", false)
    };

    private static Rule r(String id, String re, String needArg, String cmd, String risk,
                          String summary, boolean showOutput) {
        return new Rule(id, re, needArg, cmd, risk, summary, showOutput);
    }

    private FastTasks() { }

    /** true when the sentence looks like a device task we can serve without a model */
    static boolean matches(String text) { return plan(text, null) != null; }

    /**
     * Build a plan from the raw sentence. Returns null when no rule applies (then the app-action
     * parser, the model planner and finally the generic UI agent get their turn).
     */
    static AppAdapter.Plan plan(String raw, HybridRouter.Host host) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty() || text.startsWith("$") || text.startsWith("@")) return null;
        String low = text.toLowerCase(java.util.Locale.US);

        // `$ cmd`, "jalankan <cmd>", "run <cmd>" - direct root shell, still policy-checked
        String shell = directShell(text, low);
        if (shell != null && !shell.trim().isEmpty()) {
            AppAdapter.Plan p = new AppAdapter.Plan("", "Jalankan perintah");
            p.add(AppAdapter.Action.root(shell.trim()));
            p.showOutput = true;
            p.success("Perintah dijalankan.");
            p.risk = shellRisk(shell, host);
            p.confirmReason = "menjalankan perintah shell: " + brief(shell);
            return p;
        }

        for (Rule rule : RULES) {
            Matcher m = rule.re.matcher(low);
            if (!m.find()) continue;
            String arg = argumentFor(text, low, rule, m, host);
            if (rule.needArg != null && !rule.needArg.isEmpty() && (arg == null || arg.isEmpty())) continue;
            String cmd = rule.cmd;
            cmd = cmd.replace("{arg}", arg == null ? "" : arg)
                     .replace("{pkg}", arg == null ? "" : arg)
                     .replace("{num}", arg == null ? "" : arg)
                     .replace("{path}", arg == null ? "" : arg)
                     .replace("{dir}", arg == null ? "" : arg);
            AppAdapter.Plan p = new AppAdapter.Plan("", rule.id);
            p.add(AppAdapter.Action.root(cmd));
            p.showOutput = rule.showOutput;
            p.success(summaryOf(rule, arg));
            if (!"none".equals(rule.risk)) {
                p.risk = rule.risk;
                p.confirmReason = summaryOf(rule, arg) + " (dari instruksi: \"" + brief(text) + "\")";
            }
            return p;
        }
        return null;
    }

    private static String summaryOf(Rule rule, String arg) {
        String s = rule.summary;
        if (arg != null) s = s.replace("{arg}", arg).replace("{pkg}", arg).replace("{num}", arg);
        return s;
    }

    // ---- extras ------------------------------------------------------------------------------

    /** "jalankan X", "run X", "exec X", "eksekusi X" -> the rest is a shell command */
    private static String directShell(String text, String low) {
        String[] verbs = {"jalankan", "run", "exec", "eksekusi", "command", "cmd"};
        for (String v : verbs) {
            if (low.startsWith(v + " ")) return text.substring(v.length() + 1);
        }
        return null;
    }

    /** reuse the app's own gate patterns so the shell route cannot smuggle a risky command through */
    private static String shellRisk(String cmd, HybridRouter.Host host) {
        String cat = host == null ? null : host.categoryOf(cmd);
        return cat == null ? "none" : cat;
    }

    /**
     * Work out the missing argument: a package (@pkg / resolved app name), a number, or a path.
     * Deliberately conservative - when nothing convincing is found the caller skips the rule.
     */
    private static String argumentFor(String text, String low, Rule rule, Matcher m, HybridRouter.Host host) {
        if (rule.needArg == null || rule.needArg.isEmpty()) return "";
        if ("pkg".equals(rule.needArg)) {
            String pkg = packageIn(text, host);
            return pkg == null ? "" : pkg;
        }
        if ("num".equals(rule.needArg)) {
            Matcher n = Pattern.compile("(\\d{1,3})").matcher(text);
            if ("timeout-set".equals(rule.id)) return "";                      // needs a real duration, let the planner do it
            if (n.find()) return n.group(1);
            return "";
        }
        // arg: a path, a package, or the tail after the verb
        String path = pathIn(text);
        if (path != null) return path;
        String pkg = packageIn(text, host);
        if (pkg != null) return pkg;
        int cut = low.lastIndexOf(" di ");
        if (cut > 0 && cut + 4 < text.length()) return text.substring(cut + 4).trim();
        for (String v : new String[]{"jalankan", "run ", "install ", "baca ", "isi ", "ukuran "}) {
            int at = low.lastIndexOf(v);
            if (at >= 0 && at + v.length() < text.length()) return text.substring(at + v.length()).trim();
        }
        return "";
    }

    /** "@pkg" or an app name resolved through the activity's PackageManager lookup */
    private static String packageIn(String text, HybridRouter.Host host) {
        Matcher at = Pattern.compile("@([A-Za-z0-9_.]+)").matcher(text);
        if (at.find()) return at.group(1);
        Matcher dots = Pattern.compile("\\b([a-z][a-z0-9_]+(\\.[a-z0-9_]+){2,})\\b").matcher(text);
        if (dots.find()) {
            String cand = dots.group(1);
            if (host != null) {
                String p = host.packageIfInstalled(cand);
                if (p != null && !p.isEmpty()) return p;
            }
        }
        // a bare app name: take the words after the verb and ask the lookup
        String tail = text;
        String low = text.toLowerCase(java.util.Locale.US);
        for (String v : new String[]{"matiin ", "matikan ", "tutup ", "kill ", "force stop ", "stop ", "clear data ", "hapus data ", "bersihkan data ", "info ", "versi ", "pid ", "izin ", "log ", "permission "}) {
            int cut2 = low.lastIndexOf(v);
            if (cut2 >= 0) { tail = text.substring(cut2 + v.length()).trim(); break; }
        }
        // a bare app name can sit anywhere in the sentence: try every 3/2/1-word window
        if (host != null) {
            String[] words = text.trim().split("[\\s,.;:!?\"']+");
            java.util.List<String> keep = new java.util.ArrayList<String>();
            for (String w : words) {
                String lw = w.toLowerCase(java.util.Locale.US);
                if (lw.length() < 3) continue;
                if (STOPWORDS.contains(lw)) continue;
                keep.add(w);
            }
            for (int size = 3; size >= 1; size--) {
                for (int i = 0; i + size <= keep.size(); i++) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < size; k++) {
                        if (k > 0) sb.append(' ');
                        sb.append(keep.get(i + k));
                    }
                    String p = host.packageIfInstalled(sb.toString());
                    if (p != null && !p.isEmpty()) return p;
                }
            }
            String p = host.packageIfInstalled(tail);
            if (p != null && !p.isEmpty()) return p;
        }
        return "";
    }

    /** an absolute path mentioned in the sentence */
    private static String pathIn(String text) {
        Matcher m = Pattern.compile("(/[\\w./\\-]+)").matcher(text);
        String best = null;
        while (m.find()) {
            String p = m.group(1);
            if (p.length() > 1 && (best == null || p.length() > best.length())) best = p;
        }
        return best;
    }

    /** record the previous value before a settings change, so it can be reverted later */
    private static String journal(String ns, String key) {
        return "mkdir -p /data/local/tmp/ai-ssistants; "
             + "echo \"SETTINGS " + ns + " " + key + " $(settings get " + ns + " " + key + ")\" "
             + ">> /data/local/tmp/ai-ssistants/state.log; ";
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** inverse of {@link #matches}, for logging */
    static String describe(String text, HybridRouter.Host host) {
        AppAdapter.Plan p = plan(text, host);
        return p == null ? "" : p.title;
    }
}
