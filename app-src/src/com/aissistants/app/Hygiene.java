package com.aissistants.app;

/**
 * Device hygiene: the assistant is allowed to use heavy tooling (frida, hook scripts, adb tricks)
 * while a task runs, but it must never leave the phone in a state that trips other apps' tamper
 * detection. An anti-tamper SDK sees a running frida-server, its default port 27042, a permissive
 * kernel, or leftover artefacts - and then a banking / HR / game app refuses to start with
 * "suspicious activity detected in the OS".
 *
 * clean() is called automatically at the end of every run; scan() explains the current state.
 */
final class Hygiene {

    private Hygiene() { }

    /** kill hooking servers, delete their binaries, restore SELinux - never touches /data/adb */
    static String clean() {
        String out = RootShell.run(cleanCmd(), 30);
        return out == null ? "" : out.trim();
    }

    static String cleanCmd() {
        String cmd =
                "before=$(ps -A 2>/dev/null | grep -icE 'frida|re\\.frida|objection' || true);"
              + " pkill -9 -f frida-server 2>/dev/null;"
              + " pkill -9 -f re.frida.server 2>/dev/null;"
              + " pkill -9 -f frida-helper 2>/dev/null;"
              + " for p in $(pgrep -f '/data/local/tmp/ai-ssistants/' 2>/dev/null); do kill -9 $p 2>/dev/null; done;"
              + " for p in $(pgrep -f '/data/local/tmp/frida' 2>/dev/null); do kill -9 $p 2>/dev/null; done;"
              + " rm -rf /data/local/tmp/re.frida.server 2>/dev/null;"
              + " find /data/local/tmp -maxdepth 3 -type f -name 'frida-server*' -delete 2>/dev/null;"
              + " find /data/local/tmp -maxdepth 3 -type f -name 'frida-inject*' -delete 2>/dev/null;"
              + " for f in $(find " + MainActivity.toolsDir() + " -maxdepth 3 \\( -iname '*frida*' -o -iname '*gadget*' -o -iname '*objection*' \\) 2>/dev/null); do rm -rf \"$f\"; done;"
              + " rm -f /data/local/tmp/.frida* 2>/dev/null;"
              + " if [ \"$(getenforce 2>/dev/null)\" = \"Permissive\" ]; then setenforce 1; echo 'selinux: dipulihkan ke Enforcing'; fi;"
              + " sleep 1; after=$(ps -A 2>/dev/null | grep -icE 'frida|re\\.frida|objection' || true);"
              + " ports=$(ss -ltn 2>/dev/null | grep -cE '27042|27043' || true);"
              + " if [ \"$ports\" != 0 ]; then"
              + "   for pid in $(ss -ltnp 2>/dev/null | grep -E '27042|27043' | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u); do"
              + "     exe=$(readlink /proc/$pid/exe 2>/dev/null);"
              + "     pc=$(tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null);"
              + "     case \"$pc\" in *causentry*|*ksud*|*magisk*) echo \"lewati pid $pid (infrastruktur modul lu)\";;"
              + "       *) kill -9 $pid 2>/dev/null; echo \"listener port hooking dimatikan: pid $pid ($exe)\";; esac;"
              + "   done; sleep 1; ports=$(ss -ltn 2>/dev/null | grep -cE '27042|27043' || true); fi;"
              + " if [ \"$after\" != 0 ] || [ \"$ports\" != 0 ]; then"
              + "   pkill -9 -f frida 2>/dev/null; pkill -9 -f objection 2>/dev/null; sleep 1;"
              + "   find /data/local/tmp -maxdepth 3 -type f -name '*frida*' -delete 2>/dev/null;"
              + "   after=$(ps -A 2>/dev/null | grep -icE 'frida|re\\.frida|objection' || true);"
              + "   ports=$(ss -ltn 2>/dev/null | grep -cE '27042|27043' || true); fi;"
              + " echo \"frida-proses: $before -> $after\"; echo \"port-hook: $ports\";"
              + " if [ \"$after\" = 0 ] && [ \"$ports\" = 0 ]; then echo 'device-state: BERSIH';"
              + " else echo 'device-state: MASIH ADA SISA - cek manual'; fi; true";
        return cmd;
    }

    /** did that cleanup actually kill something? */
    static boolean cleanFoundSomething(String cleanOutput) {
        if (cleanOutput == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("frida-proses:\\s*(\\d+)\\s*->\\s*(\\d+)").matcher(cleanOutput);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)) > 0; } catch (Throwable ignored) { }
        }
        return cleanOutput.contains("dipulihkan");
    }

    /** human-readable integrity triage: what an anti-tamper SDK would see right now */
    static String scan() {
        String out = RootShell.run(scanCmd(), 40);
        return out == null ? "" : out.trim();
    }

    static String scanCmd() {
        String cmd =
                "echo 'FRIDA PROSES:'; (ps -A 2>/dev/null | grep -iE 'frida|re\\.frida|objection' | grep -v grep || echo '  tidak ada');"
              + " echo 'PORT HOOK (27042/27043):'; (ss -ltn 2>/dev/null | grep -E '27042|27043' || echo '  tidak ada');"
              + " echo 'SELINUX:'; getenforce;"
              + " echo 'DEVELOPER OPTIONS / ADB:'; settings get global development_settings_enabled; settings get global adb_enabled;"
              + " echo 'MOCK LOCATION:'; settings get secure mock_location;"
              + " echo 'PROPS:'; getprop ro.boot.verifiedbootstate; getprop ro.boot.flash.locked; getprop ro.debuggable; getprop ro.build.type; getprop ro.build.tags;"
              + " echo 'SU TERLIHAT DI PATH:'; (command -v su || echo '  tidak ada di PATH');"
              + " echo 'BINARY HOOKING TERTINGGAL:'; (ls -d /data/local/tmp/*frida* /data/local/tmp/re.frida.server /data/local/tmp/ai-ssistants/*/frida-server 2>/dev/null || echo '  tidak ada');"
              + " echo 'SISTEM RW/MOUNT ANEH:'; (mount | grep -E ' /system | /vendor | /product ' | grep -v ' ro,' | head -4 || echo '  tidak ada'); true";
        return cmd;
    }

    /** one-line verdict for the transcript */
    static String verdict(String scanOutput) {
        if (scanOutput == null || scanOutput.isEmpty()) return "tidak bisa memeriksa device";
        StringBuilder bad = new StringBuilder();
        String s = scanOutput;
        java.util.regex.Matcher ports = java.util.regex.Pattern
                .compile("PORT HOOK[^\\n]*\\n\\s*(\\S+)").matcher(s);
        if (ports.find() && ports.group(1).contains(":")) bad.append("port hooking terbuka (").append(ports.group(1)).append(") · ");
        java.util.regex.Matcher fr = java.util.regex.Pattern
                .compile("FRIDA PROSES:\\n\\s*([^\\n]+)").matcher(s);
        if (fr.find() && !fr.group(1).contains("tidak ada")) bad.append("proses hooking jalan · ");
        java.util.regex.Matcher se = java.util.regex.Pattern.compile("SELINUX:\\n\\s*(\\S+)").matcher(s);
        if (se.find() && se.group(1).toLowerCase().contains("permissive")) bad.append("SELinux permissive · ");
        java.util.regex.Matcher dl = java.util.regex.Pattern
                .compile("DEVELOPER OPTIONS / ADB:\\n\\s*(\\d+)\\n\\s*(\\d+)").matcher(s);
        if (dl.find()) {
            if ("1".equals(dl.group(1))) bad.append("developer options nyala · ");
            if ("1".equals(dl.group(2))) bad.append("USB debugging nyala · ");
        }
        java.util.regex.Matcher ml = java.util.regex.Pattern
                .compile("MOCK LOCATION:\\n\\s*(\\S+)").matcher(s);
        if (ml.find() && "1".equals(ml.group(1))) bad.append("mock location nyala · ");
        return bad.length() == 0 ? "bersih: tidak ada artefak hooking/root yang kelihatan"
                : bad.substring(0, bad.length() - 3).toString();
    }
}
