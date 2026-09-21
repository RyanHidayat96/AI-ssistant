#!/usr/bin/env python3
"""AI-ssistants v1.1 fixes - idempotent re-apply script.

P1  system prompt: /data/data tmpfs-overlay hint (nsenter -t 1 -m)
P2  system prompt: never re-run a command whose output you already have
P3  agent loop  : 'step limit reached' notice (only on real exhaustion, not on a logical break)
P4  agent loop  : quiet 'stopped.' bubble when the user pressed Stop
P5  settings    : MaxHeightScrollView (55% of screen) so chat + input stay reachable
P6  touch       : quick-action chips 40->48dp, ROOT pill minHeight 48dp
P7  input       : shorter hint (was wrapping to ~4 lines), '$ shell' chip -> '$ root cmd'
P8  stop button : AiClient.cancel() aborts the in-flight HTTP request
P9  manifest    : versionCode 2, versionName 1.1.0
"""
import sys, io, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MA = os.path.join(ROOT, "app-src", "src", "com", "aissistants", "app", "MainActivity.java")
AC = os.path.join(ROOT, "app-src", "src", "com", "aissistants", "app", "AiClient.java")
MF = os.path.join(ROOT, "app-src", "manifest", "AndroidManifest.xml")

MAIN_PATCHES = [
    # P1
    (r'''          .append("- apps/files: /data/data/<pkg>, /sdcard, /data/local/tmp (use `cat`, `cp`, `sed -i`)\n")''',
     r'''          .append("- apps/files: /data/data/<pkg>, /sdcard, /data/local/tmp (use `cat`, `cp`, `sed -i`)\n")
          .append("- NOTE: /data/data in THIS shell is a tmpfs overlay that mostly shows only your own dir, so plain `ls/du /data/data/<pkg>` can fail or lie even as uid 0. Go through init's namespace right away: `nsenter -t 1 -m -- ls -la /data/data/<pkg>`, `nsenter -t 1 -m -- du -sh /data/data/<pkg>` (same for cat/cp/sed).\n")'''),
    # P2
    (r'''          .append("the background only if the user asked for it.\n")''',
     r'''          .append("the background only if the user asked for it. Never repeat a command whose output you ")
          .append("already have - use it and answer.\n")'''),
    # P3a: exhaustion detector seed
    (r'''            for (int step = 1; step <= steps && !stop; step++) {
                final int s = step;''',
     r'''            boolean brokeEarly = false;
            for (int step = 1; step <= steps && !stop; step++) {
                final int s = step;'''),
    # P3b: the two logical exits mark brokeEarly (error break handled in P4 patch; cmds-empty break here)
    (r'''                if (cmds.isEmpty()) break;''',
     r'''                if (cmds.isEmpty()) { brokeEarly = true; break; }'''),
    # P3c: notice after the loop
    (r'''                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {''',
     r'''                    } catch (Throwable ignored) { }
                }
            }
            if (!brokeEarly && !stop) {
                addBubble("note", "step limit reached (" + steps + ") - raise Max steps in settings and send 'continue'");
            }
        } catch (Throwable t) {'''),
    # P4: quiet stop + mark brokeEarly on error break
    (r'''                if (!reply.ok) {
                    addBubble("note", "\u26a0 " + reply.error);
                    break;
                }''',
     r'''                if (!reply.ok) {
                    if (stop) addBubble("note", "stopped.");
                    else addBubble("note", "\u26a0 " + reply.error);
                    brokeEarly = true;
                    break;
                }'''),
    # P5a
    (r'''        settingsScroll = new ScrollView(this);''',
     r'''        settingsScroll = new MaxHeightScrollView(this, (int) (getResources().getDisplayMetrics().heightPixels * 0.55f));'''),
    # P5b
    (r'''    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(radius));
        return g;
    }
}''',
     r'''    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(radius));
        return g;
    }

    /** ScrollView that never grows past a slice of the screen, so the chat + input row stay reachable */
    private static class MaxHeightScrollView extends ScrollView {
        private final int maxH;
        MaxHeightScrollView(android.content.Context c, int maxH) { super(c); this.maxH = maxH; }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        }
    }
}'''),
    # P6a
    (r'''        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));''',
     r'''        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));'''),
    # P6b
    (r'''        pill.setPadding(dp(12), dp(6), dp(12), dp(6));''',
     r'''        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setMinHeight(dp(48));'''),
    # P7a
    (r'''        input.setHint("Ask for anything\u2026 or start with $ to run a root command");''',
     r'''        input.setHint("Ask anything\u2026 or $ for root");'''),
    # P7b
    (r'''                {"$ shell", "$ "},''',
     r'''                {"$ root cmd", "$ "},'''),
    # P8b (stop button wiring)
    (r'''                stop = true;
                addBubble("note", "stopping\u2026");''',
     r'''                stop = true;
                AiClient.cancel();
                addBubble("note", "stopping\u2026");'''),
]

AI_PATCHES = [
    # P8a: cancel support
    (r'''    private AiClient() { }''',
     r'''    private AiClient() { }

    /** the live connection, so the Stop button can abort a blocked read */
    private static volatile HttpURLConnection active;

    static void cancel() {
        HttpURLConnection c = active;
        if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
    }'''),
    (r'''            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");''',
     r'''            conn = (HttpURLConnection) new URL(url).openConnection();
            active = conn;
            conn.setRequestMethod("POST");'''),
    (r'''        } finally {
            if (conn != null) conn.disconnect();
        }''',
     r'''        } finally {
            if (active == conn) active = null;
            if (conn != null) conn.disconnect();
        }'''),
]

MF_PATCHES = [
    (r'''android:versionCode="1"''', r'''android:versionCode="2"'''),
    (r'''android:versionName="1.0.0"''', r'''android:versionName="1.1.0"'''),
]


def apply(path, patches, label):
    raw = open(path, "r", encoding="utf-8", newline="").read()
    crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n") if crlf else raw
    report = []
    changed = 0
    for i, (old, new) in enumerate(patches, 1):
        n = text.count(old)
        if n == 1:
            text = text.replace(old, new)
            changed += 1
            report.append("  [%s %02d] applied" % (label, i))
        elif n == 0:
            n2 = text.count(new.strip())
            state = "already-applied" if new.strip() and new in text else "MISS"
            report.append("  [%s %02d] %s" % (label, i, state))
        else:
            report.append("  [%s %02d] AMBIGUOUS x%d - skipped" % (label, i, n))
    if changed:
        out = text.replace("\n", "\r\n") if crlf else text
        open(path, "w", encoding="utf-8", newline="").write(out)
    print("%s: %d/%d applied (%s)" % (label, changed, len(patches), "CRLF" if crlf else "LF"))
    print("\n".join(report))


apply(MA, MAIN_PATCHES, "MA")
apply(AC, AI_PATCHES, "AC")
apply(MF, MF_PATCHES, "MF")
print("done")
