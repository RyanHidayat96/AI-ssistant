package com.aissistants.app;

import java.net.URLEncoder;

/**
 * The capability registry: one adapter per app, plus scheme-only adapters (SMS / dialer / mail /
 * browser) used only when the user did not name a different app. Selectors accept alternatives
 * separated by '|'.
 */
final class Adapters {

    private static final String WA = "com.whatsapp";
    private static final String WA_SEND =
            "id:com.whatsapp:id/send|id:com.whatsapp:id/send_btn|desc:Send|desc:Kirim";
    private static final String WA_ENTRY = "id:com.whatsapp:id/entry|id:com.whatsapp:id/input";
    private static final String WA_SEARCH_FIELD =
            "id:com.whatsapp:id/search_src_text|id:com.whatsapp:id/search_input";
    private static final String WA_SEARCH_BTN = "id:com.whatsapp:id/menuitem_search|desc:Search|desc:Cari";

    static final AppAdapter[] ALL = {
            new WhatsApp(), new YouTube(), new Maps(), new Sms(), new Dialer(), new Mail(), new Browser()
    };

    private Adapters() { }

    /** adapter bound to a concrete package, or null */
    static AppAdapter forPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        for (AppAdapter a : ALL) {
            if (pkg.equals(a.pkg())) return a;
        }
        return null;
    }

    /** scheme-only adapter that can serve this action without knowing the app, or null */
    static AppAdapter forAction(String action, String appHint) {
        String hint = appHint == null ? "" : appHint.toLowerCase(java.util.Locale.US);
        for (AppAdapter a : ALL) {
            if (!a.pkg().isEmpty()) continue;
            if (!a.handles(action)) continue;
            if (a instanceof Sms && !wantsSms(hint)) continue;
            if (a instanceof Browser && !wantsBrowser(hint)) continue;
            return a;
        }
        return null;
    }

    static boolean isSend(String action) { return "send_message".equals(action); }

    private static boolean wantsSms(String hint) {
        if (hint == null || hint.isEmpty()) return true;
        return hint.contains("sms") || hint.contains("text") || hint.contains("pesan biasa")
                || hint.contains("messages") || hint.contains("messaging");
    }

    private static boolean wantsBrowser(String hint) {
        if (hint == null || hint.isEmpty()) return true;
        return hint.contains("browser") || hint.contains("chrome") || hint.contains("google")
                || hint.contains("web") || hint.contains("internet");
    }

    // ---- helpers ----------------------------------------------------------------------------

    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Throwable t) { return s == null ? "" : s; }
    }

    static String digits(String phone) {
        if (phone == null) return "";
        String d = phone.replaceAll("[^0-9]", "");
        if (d.startsWith("0")) d = "62" + d.substring(1);          // Indonesian local format
        return d;
    }

    static String shortText(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    // =========================================================================================
    // apps
    // =========================================================================================

    static final class WhatsApp extends AppAdapter {
        @Override String pkg() { return WA; }
        @Override String name() { return "WhatsApp"; }

        @Override boolean handles(String action) {
            return "open".equals(action) || "send_message".equals(action) || "search".equals(action);
        }

        @Override Plan plan(LocalParser.Spec spec, HybridRouter.Host host) {
            if ("open".equals(spec.action)) {
                return new Plan(WA, "Buka WhatsApp")
                        .add(Action.intent("android.intent.action.MAIN", "app:" + WA))
                        .add(Action.verify(act("Conversation|HomeActivity|com.whatsapp"), 6000))
                        .success("WhatsApp terbuka.");
            }
            if ("search".equals(spec.action)) {
                Plan p = new Plan(WA, "Cari di WhatsApp: " + spec.target)
                        .add(Action.intent("android.intent.action.MAIN", "app:" + WA))
                        .add(Action.wait(id("com.whatsapp:id/contact_row_container") + "|" + WA_SEARCH_BTN, 9000))
                        .add(Action.tap(WA_SEARCH_BTN))
                        .add(Action.wait(WA_SEARCH_FIELD, 6000))
                        .add(Action.input(WA_SEARCH_FIELD, spec.target))
                        .add(Action.verify(text(spec.target), 9000))
                        .success("Hasil pencarian WhatsApp untuk \"" + spec.target + "\" sudah tampil.");
                p.risk("none", "");
                return p;
            }

            // send_message
            String num = host == null ? null : host.contactNumber(spec.target);
            Plan p = new Plan(WA, "Kirim WhatsApp ke " + spec.target);
            p.risk("external_write", "kirim pesan WhatsApp ke " + spec.target + ": \""
                    + shortText(spec.text) + "\"");
            if (num != null && num.length() >= 6) {
                p.add(Action.note("kontak " + spec.target + " \u2192 " + num + " \u00b7 jalur deep link"));
                p.add(Action.intent("android.intent.action.VIEW",
                        "https://wa.me/" + digits(num) + "?text=" + enc(spec.text)));
                p.add(Action.wait(WA_SEND, 9000));
                p.add(Action.tap(WA_SEND));
                p.add(Action.verify(text(spec.text), 9000));
                p.success("Terkirim ke " + spec.target + " via WhatsApp (deep link, "
                        + digits(num) + ").");
                return p;
            }
            p.add(Action.note("nomor " + spec.target + " tidak ada di kontak \u00b7 jalur UI adapter"));
            p.add(Action.intent("android.intent.action.MAIN", "app:" + WA));
            p.add(Action.wait(id("com.whatsapp:id/contact_row_container") + "|" + WA_SEARCH_BTN, 9000));
            p.add(Action.tap(WA_SEARCH_BTN));
            p.add(Action.wait(WA_SEARCH_FIELD, 6000));
            p.add(Action.input(WA_SEARCH_FIELD, spec.target));
            p.add(Action.wait(text(spec.target), 9000));
            p.add(Action.tap(text(spec.target)));
            p.add(Action.wait(WA_ENTRY, 9000));
            p.add(Action.input(WA_ENTRY, spec.text));
            p.add(Action.tap(WA_SEND));
            p.add(Action.verify(text(spec.text), 9000));
            p.success("Terkirim ke " + spec.target + " via WhatsApp (pencarian UI).");
            return p;
        }
    }

    static final class YouTube extends AppAdapter {
        @Override String pkg() { return "com.google.android.youtube"; }
        @Override String name() { return "YouTube"; }
        @Override boolean handles(String a) { return "open".equals(a) || "search".equals(a); }

        @Override Plan plan(LocalParser.Spec spec, HybridRouter.Host host) {
            if ("open".equals(spec.action)) {
                return new Plan(pkg(), "Buka YouTube")
                        .add(Action.intent("android.intent.action.MAIN", "app:" + pkg()))
                        .success("YouTube terbuka.");
            }
            return new Plan(pkg(), "Cari di YouTube: " + spec.target)
                    .add(Action.intent("android.intent.action.VIEW",
                            "https://www.youtube.com/results?search_query=" + enc(spec.target)))
                    .success("Hasil YouTube untuk \"" + spec.target + "\" terbuka.");
        }
    }

    static final class Maps extends AppAdapter {
        @Override String pkg() { return "com.google.android.apps.maps"; }
        @Override String name() { return "Maps"; }
        @Override boolean handles(String a) { return "navigate".equals(a) || "search".equals(a) || "open".equals(a); }

        @Override Plan plan(LocalParser.Spec spec, HybridRouter.Host host) {
            if ("open".equals(spec.action)) {
                return new Plan(pkg(), "Buka Maps")
                        .add(Action.intent("android.intent.action.MAIN", "app:" + pkg()))
                        .success("Maps terbuka.");
            }
            return new Plan(pkg(), "Peta: " + spec.target)
                    .add(Action.intent("android.intent.action.VIEW", "geo:0,0?q=" + enc(spec.target)))
                    .success("Lokasi \"" + spec.target + "\" dibuka di Maps.");
        }
    }

    static final class Sms extends AppAdapter {
        @Override String pkg() { return ""; }
        @Override String name() { return "SMS"; }
        @Override boolean handles(String a) { return "send_message".equals(a); }

        @Override Plan plan(LocalParser.Spec spec, HybridRouter.Host host) {
            String num = host == null ? null : host.contactNumber(spec.target);
            String to = num != null && num.length() >= 6 ? num : spec.target;
            if (to.replaceAll("[^0-9+]", "").length() < 5) return null;      // cannot guess a number
            Plan p = new Plan("", "SMS ke " + to);
            p.risk("external_write", "buka draft SMS ke " + to + " berisi \"" + shortText(spec.text)
                    + "\" (butuh tap Kirim dari kamu)");
            p.add(Action.intent("android.intent.action.SENDTO", "smsto:" + to + "?body=" + enc(spec.text)));
            p.add(Action.verify(id("com.android.mms:id/embedded_text_editor")
                    + "|id:com.google.android.apps.messaging:id/compose_message_text|text:" + spec.text, 7000));
            p.success("Draft SMS ke " + to + " sudah terbuka dengan isinya \u00b7 tinggal tekan Kirim.");
            return p;
        }
    }

    static final class Dialer extends AppAdapter {
        @Override String pkg() { return ""; }
        @Override String name() { return "Phone"; }
        @Override boolean handles(String a) { return "dial".equals(a) || "call".equals(a); }

        @Override Plan plan(LocalParser.Spec spec, HybridRouter.Host host) {
            String num = spec.target.replaceAll("[^+0-9]", "");
            if (num.length() < 5) return null;
            if ("call".equals(spec.action)) {
                Plan p = new Plan("", "Telepon " + num);
                p.risk("call", "menelepon " + num + " atas nama kamu");
                p.add(Action.intent("android.intent.action.CALL", "tel:" + num));
                p.add(Action.verify(act("InCallActivity|incallui|com.android.dialer|call"), 9000));
                p.success("Panggilan ke " + num + " dimulai.");
                return p;
            }
            Plan p = new Plan("", "Buka dialer: " + num);
            p.add(Action.intent("android.intent.action.DIAL", "tel:" + num));
            p.add(Action.verify(text(num) + "|act(dialer)|act(DialtactsActivity)|act(com.android.dialer)", 6000));
            p.success("Dialer terisi nomor " + num + " \u00b7 tekan tombol hijau untuk menelepon.");
            return p;
        }
    }

    static final class Mail extends AppAdapter {
        @Override String pkg() { return ""; }
        @Override String name() { return "Email"; }
        @Override boolean handles(String a) { return "email".equals(a); }

        @Override Plan plan(LocalParser.Spec spec, HybridRouter.Host host) {
            if (spec.target.indexOf('@') < 0) return null;
            Plan p = new Plan("", "Email ke " + spec.target);
            p.risk("external_write", "buka draft email ke " + spec.target);
            String uri = "mailto:" + spec.target;
            if (!spec.text.isEmpty()) uri += "?body=" + enc(spec.text);
            p.add(Action.intent("android.intent.action.SENDTO", uri));
            p.add(Action.verify(text(spec.target), 8000));
            p.success("Draft email ke " + spec.target + " sudah terbuka.");
            return p;
        }
    }

    static final class Browser extends AppAdapter {
        @Override String pkg() { return ""; }
        @Override String name() { return "Browser"; }
        @Override boolean handles(String a) { return "search".equals(a); }

        @Override Plan plan(LocalParser.Spec spec, HybridRouter.Host host) {
            if (spec.target.isEmpty()) return null;
            return new Plan("", "Cari di web: " + spec.target)
                    .add(Action.intent("android.intent.action.VIEW",
                            "https://www.google.com/search?q=" + enc(spec.target)))
                    .success("Hasil pencarian web untuk \"" + spec.target + "\" dibuka di browser.");
        }
    }
}
