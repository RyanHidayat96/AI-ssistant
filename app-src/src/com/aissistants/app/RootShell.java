package com.aissistants.app;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Runs shell commands on the device as root (uid 0) through the KernelSU/Magisk `su` binary.
 *
 * The app itself is unprivileged: every privileged action goes through this one door, which is
 * what makes the assistant able to touch anything on the device - app data, /system, the kernel
 * (via /dev, insmod, sysfs), other apps' processes - without the app holding any special
 * permission of its own.
 */
final class RootShell {

    /** hard cap on captured output so a chatty command cannot exhaust memory */
    static final int MAX_OUT = 48 * 1024;

    /** what to say while KernelSU has not handed root to this app yet */
    static final String NO_ROOT_HINT =
            "root not granted yet.\n"
          + "Open the KernelSU / Magisk manager, go to Superuser, enable AI-ssistants "
          + "(or approve the request when it pops up), then tap the status pill to re-check.\n"
          + "Nothing runs as uid 0 until that is done.";

    /** null = unknown, TRUE = su works, FALSE = denied (then fail fast instead of hanging) */
    private static volatile Boolean granted = null;

    static Boolean state() { return granted; }

    private RootShell() { }

    /** probe `su` with a short budget so a denied request cannot block the UI thread */
    static boolean available() {
        String out = run("id -u", 10);
        boolean ok = out != null && out.trim().startsWith("0");
        granted = ok ? Boolean.TRUE : Boolean.FALSE;
        return ok;
    }

    /** run a script as root, merged stdout+stderr, bounded by timeoutSec */
    static String run(String script, int timeoutSec) {
        if (Boolean.FALSE.equals(granted)) return NO_ROOT_HINT;
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", script);
            pb.redirectErrorStream(true);
            p = pb.start();
            try { p.getOutputStream().close(); } catch (Throwable ignored) { }

            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    InputStream is = null;
                    try {
                        is = proc.getInputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) >= 0) {
                            if (sb.length() < MAX_OUT) {
                                sb.append(new String(buf, 0, n, "UTF-8"));
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        try { if (is != null) is.close(); } catch (Throwable ignored) { }
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = p.waitFor(Math.max(1, timeoutSec), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                reader.join(1500);
                return trim(sb.toString()) + "\n[timeout after " + timeoutSec + "s]";
            }
            reader.join(2000);
            int rc = p.exitValue();
            String out = trim(sb.toString());
            return out + (rc == 0 ? "" : "\n[exit " + rc + "]");
        } catch (Throwable t) {
            return "root shell unavailable: " + t
                    + "\n(grant root to AI-ssistants in KernelSU/Magisk, then retry)";
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_OUT) return s;
        return s.substring(0, MAX_OUT) + "\n[output truncated]";
    }

    /** single-line probe used by the UI status pill */
    static String probe() {
        String out = run("id; echo ---; getprop ro.build.version.release; echo ---; getprop ro.product.model", 20);
        return out == null ? "" : out.trim();
    }
}
