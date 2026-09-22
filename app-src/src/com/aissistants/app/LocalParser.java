package com.aissistants.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands one user message without calling a model.
 *
 * Clear commands ("buka WhatsApp", "kirim \"hai\" ke Ryan", "telepon 0812...", "cari foo di YouTube")
 * become a structured Spec with a confidence; anything vague stays low-confidence so the caller can
 * spend a model round-trip on it, or hand the job to the generic UI agent.
 */
final class LocalParser {

    static final class Spec {
        String action = "";        // open | search | send_message | dial | call | email | navigate
        String app = "";           // app name as written by the user
        String pkg = "";           // resolved package ("" when unknown)
        String target = "";        // person / number / query / address
        String text = "";          // message body, when there is one
        String risk = "none";      // none | external_write | call | purchase | delete
        double confidence = 0;
        String why = "";

        boolean executable() { return !action.isEmpty() && confidence >= 0.7; }

        @Override public String toString() {
            StringBuilder b = new StringBuilder(action);
            if (!app.isEmpty()) b.append(" app=").append(app);
            if (!pkg.isEmpty()) b.append(" pkg=").append(pkg);
            if (!target.isEmpty()) b.append(" target=").append(target);
            if (!text.isEmpty()) b.append(" text=").append(text.length() > 60 ? text.substring(0, 60) + "…" : text);
            b.append(" risk=").append(risk)
             .append(" conf=").append(String.format(java.util.Locale.US, "%.2f", confidence));
            if (!why.isEmpty()) b.append(" (").append(why).append(")");
            return b.toString();
        }
    }

    /** resolves a human app name to a package (PackageManager), implemented by the activity */
    interface AppLookup {
        String packageOf(String name);
        String labelOf(String pkg);
    }

    private static final Pattern QUOTED =
            Pattern.compile("[\"\u201c\u201d']([^\"\u201c\u201d']{1,1000})[\"\u201c\u201d']");
    private static final Pattern PHONE = Pattern.compile("([+]?[0-9][0-9\\-\\s()]{6,20}[0-9])");
    private static final Pattern EMAIL = Pattern.compile("([\\w.+-]+@[\\w-]+\\.[\\w.]{2,})");

    private static final String[] SEND_VERBS = {
            "kirimkan", "kirim", "send", "chat", "whatsapp", "wa", "sms", "text", "kasih tau", "kasih tahu"};
    private static final String[] OPEN_VERBS = {"buka", "open", "jalankan", "launch", "start"};
    private static final String[] SEARCH_VERBS = {"carikan", "cariin", "cari", "search", "googling", "google"};
    private static final String[] DIAL_VERBS = {"telepon", "telpon", "dial", "panggil", "call"};
    private static final String[] MAIL_VERBS = {"emailkan", "email", "mail"};
    private static final String[] NAV_VERBS = {"navigasi", "rute", "arahkan", "navigate"};

    private static final String[] NOISE = {
            "tolong", "coba", "dong", "ya", "pls", "please", "bisa", "bantuin", "bantu"};
    private static final String[] PREP = {
            "pesan ke", "pesannya ke", "message to", "pesan", "pesannya", "message", "chat", "whatsapp",
            "wa", "sms", "teks", "text", "ke", "to", "untuk", "utk", "buat", "kepada", "via", "lewat"};
    private static final String[] APP_NOUNS = {"aplikasi", "app", "apps", "nya"};
    private static final String[] SEPARATORS = {":", " = ", " bahwa ", " isinya ", " berisi ", " dengan isi ", " dengan pesan "};

    static Spec parse(String raw, AppLookup lookup) {
        Spec s = new Spec();
        if (raw == null) return s;
        String t = raw.trim();
        if (t.isEmpty() || t.startsWith("$") || t.startsWith("@")) return s;    // shell / @mention paths
        t = stripNoiseAligned(t);                                  // filler gone, offsets stay aligned
        String low = t.toLowerCase(java.util.Locale.US);
        if (low.contains(" lalu ") || low.contains(" terus ") || low.contains(" kemudian ")
                || low.contains(" then ") || low.contains(" dan kirim ") || low.contains(" and send ")) {
            return new Spec();               // compound request: let the planner/agent decompose it
        }

        String verb = startsWith(low, SEND_VERBS);
        if (verb != null) {
            s.action = "send_message";
            String body = "";
            Matcher q = QUOTED.matcher(t);
            if (q.find()) {                                  // quoted body wins: `kirim "hai" ke Ryan`
                body = q.group(1).trim();
                t = (t.substring(0, q.start()) + " " + t.substring(q.end())).trim();
                low = t.toLowerCase(java.util.Locale.US);
            }
            int from = Math.min(verb.length(), t.length());
            int cut = sepAt(t, from);
            String target;
            if (cut >= 0) {
                int sepLen = sepLengthAt(t, cut);
                target = t.substring(from, cut).trim();
                if (body.isEmpty()) body = t.substring(Math.min(cut + sepLen, t.length())).trim();
            } else {
                target = t.substring(from).trim();
            }
            String targetText = stripPrep(target);
            String[] routed = splitTargetAndApp(targetText, lookup);
            if (routed != null) {
                targetText = routed[0];
                s.app = routed[1];
                s.pkg = lookup == null ? "" : nz(lookup.packageOf(s.app));
            }
            s.target = stripPrep(targetText);
            s.text = clean(body);
            s.risk = "external_write";
            if (s.target.isEmpty() && s.text.isEmpty()) { s.confidence = 0.2; s.why = "no target and no text"; }
            else if (s.target.isEmpty()) { s.confidence = 0.5; s.why = "no target"; }
            else if (s.text.isEmpty()) { s.confidence = 0.55; s.why = "no message body"; }
            else { s.confidence = 0.93; s.why = "target + body"; }
            if (s.app.isEmpty()) {
                s.app = hint(low, "whatsapp", "wa", "sms");
                s.pkg = lookup == null ? "" : nz(lookup.packageOf(s.app));
            }
            return s;
        }

        verb = startsWith(low, OPEN_VERBS);
        if (verb != null) {
            String name = stripTrailing(t.substring(Math.min(verb.length(), t.length())).trim());
            name = stripLeadingWords(name, APP_NOUNS);
            s.action = "open";
            s.app = name;
            s.pkg = name.startsWith("@") ? name.substring(1).trim()
                    : (lookup == null ? "" : nz(lookup.packageOf(name)));
            s.confidence = s.pkg.isEmpty() ? 0.4 : 0.92;
            s.why = s.pkg.isEmpty() ? "app not resolved by name" : "package resolved";
            return s;
        }

        verb = startsWith(low, SEARCH_VERBS);
        if (verb != null) {
            String rest = t.substring(Math.min(verb.length(), t.length())).trim();
            s.action = "search";
            int di = rest.toLowerCase(java.util.Locale.US).lastIndexOf(" di ");
            if (di > 0 && di + 4 < rest.length()) {
                s.target = clean(rest.substring(0, di));
                s.app = rest.substring(di + 4).trim();
                s.pkg = lookup == null ? "" : nz(lookup.packageOf(s.app));
            } else {
                s.target = clean(rest);
            }
            s.confidence = s.target.isEmpty() ? 0.3 : (s.app.isEmpty() ? 0.85 : 0.88);
            s.why = s.app.isEmpty() ? "browser search" : "search inside " + s.app;
            return s;
        }

        verb = startsWith(low, DIAL_VERBS);
        if (verb != null) {
            Matcher p = PHONE.matcher(t);
            String num = p.find() ? p.group(1).replaceAll("[^+0-9]", "") : "";
            boolean direct = low.startsWith("call") || low.startsWith("panggil") || low.contains("langsung");
            if (num.length() < 5) {
                s.action = "dial";
                s.target = t.substring(Math.min(verb.length(), t.length())).trim();
                s.risk = direct ? "call" : "none";
                s.confidence = 0.45;
                s.why = "no usable phone number";
                return s;
            }
            s.action = direct ? "call" : "dial";
            s.target = num;
            s.risk = direct ? "call" : "none";
            s.confidence = 0.95;
            s.why = direct ? "place the call" : "open the dialer with the number";
            return s;
        }

        verb = startsWith(low, MAIL_VERBS);
        if (verb != null) {
            s.action = "email";
            Matcher e = EMAIL.matcher(t);
            if (e.find()) {
                s.target = e.group(1);
                Matcher q2 = QUOTED.matcher(t);
                if (q2.find()) s.text = clean(q2.group(1));
                s.risk = "external_write";
                s.confidence = 0.9;
                s.why = "mailto";
            } else {
                s.confidence = 0.4;
                s.why = "no email address";
            }
            return s;
        }

        verb = startsWith(low, NAV_VERBS);
        if (verb != null) {
            String dest = t.substring(Math.min(verb.length(), t.length())).trim();
            String dlow = dest.toLowerCase(java.util.Locale.US);
            if (dlow.startsWith("ke ")) dest = dest.substring(3).trim();
            s.action = "navigate";
            s.target = clean(dest);
            s.confidence = s.target.isEmpty() ? 0.3 : 0.85;
            s.why = "maps intent";
            return s;
        }

        return s;   // vague request -> caller uses the model / generic agent
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static String nz(String s) { return s == null ? "" : s; }

    private static String startsWith(String low, String[] verbs) {
        for (String v : verbs) {
            if (low.equals(v) || low.startsWith(v + " ")) return v;
        }
        return null;
    }

    /** strips leading/trailing filler and RETURNS the shortened string, so text and lowercase stay aligned */
    private static String stripNoiseAligned(String s) {
        String out = s.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            String low = out.toLowerCase(java.util.Locale.US);
            for (String n : NOISE) {
                if (low.startsWith(n + " ")) { out = out.substring(n.length() + 1).trim(); changed = true; break; }
                if (low.endsWith(" " + n)) { out = out.substring(0, out.length() - n.length() - 1).trim(); changed = true; break; }
            }
        }
        return out;
    }

    /** drop leading filler words (app nouns, prepositions) from a name or a target */
    private static String stripLeadingWords(String s, String[] words) {
        String out = s.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            String low = out.toLowerCase(java.util.Locale.US);
            for (String w : words) {
                if (low.startsWith(w + " ")) { out = out.substring(w.length() + 1).trim(); changed = true; break; }
            }
        }
        return out;
    }

    private static String stripTrailing(String s) {
        String out = s.trim();
        for (String n : new String[]{"dong", "ya", "please", "pls"}) {
            if (out.toLowerCase(java.util.Locale.US).endsWith(" " + n)) {
                out = out.substring(0, out.length() - n.length() - 1).trim();
            }
        }
        return out;
    }

    private static String stripPrep(String s) {
        return stripLeadingWords(s, PREP);
    }

    private static String clean(String s) {
        if (s == null) return "";
        String out = s.trim();
        while (out.startsWith(":") || out.startsWith("-") || out.startsWith(",") || out.startsWith("=")) {
            out = out.substring(1).trim();
        }
        while (out.endsWith(".") || out.endsWith(",") || out.endsWith(";")) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }

    /** split "Ryan via Telegram" / "Ryan lewat WhatsApp" / "Ryan di Signal" into target + app */
    private static String[] splitTargetAndApp(String target, AppLookup lookup) {
        if (target == null) return null;
        String t = target.trim();
        String low = t.toLowerCase(java.util.Locale.US);
        String[] markers = {" via ", " lewat ", " pakai ", " pake ", " melalui ", " di "};
        for (String marker : markers) {
            int at = low.lastIndexOf(marker);
            if (at <= 0 || at + marker.length() >= t.length()) continue;
            String person = clean(t.substring(0, at));
            String app = clean(t.substring(at + marker.length()));
            if (person.isEmpty() || app.isEmpty()) continue;
            String resolved = lookup == null ? "" : nz(lookup.packageOf(app));
            String appLow = app.toLowerCase(java.util.Locale.US);
            if (!resolved.isEmpty() || "wa".equals(appLow) || appLow.contains("whatsapp")
                    || appLow.contains("sms") || appLow.contains("telegram")
                    || appLow.contains("signal") || appLow.contains("messenger")) {
                return new String[]{person, app};
            }
        }
        return null;
    }

    private static String hint(String low, String... names) {
        for (String n : names) if (low.contains(n)) return n;
        return "";
    }

    private static int sepAt(String t, int from) {
        int best = -1;
        for (String sep : SEPARATORS) {
            int at = t.indexOf(sep, from);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    private static int sepLengthAt(String t, int at) {
        for (String sep : SEPARATORS) {
            if (t.startsWith(sep, at)) return sep.length();
        }
        return 1;
    }
}
