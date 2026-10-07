package com.littlesauce.watrans;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Cross-process settings bridge.
 *
 * <ul>
 *   <li>In the module's own process (SettingsActivity) we write through the real
 *       {@link SharedPreferences}. The manifest declares
 *       {@code xposedsharedprefs} so the framework makes the file readable by the
 *       hooked processes.</li>
 *   <li>In the hooked WhatsApp process we read through {@link XSharedPreferences},
 *       which re-reads the file when its mtime changes - so settings edits apply
 *       without restarting WhatsApp.</li>
 * </ul>
 */
public final class Prefs {

    public static final String MODULE_PACKAGE = "com.littlesauce.watrans";

    /** Mirror file the hooked process can read directly; see {@link #mirror}. */
    public static final String MIRROR_NAME = "mirror.xml";

    public static final String NAME = "prefs";

    public static final String KEY_ENGINE = "engine";
    public static final String KEY_TARGET_LANG = "target_lang";
    public static final String KEY_TRANSLATION_COLOR = "translation_color";
    public static final String KEY_TRANSLATION_SCALE = "translation_scale";
    public static final String KEY_TRANSLATION_ITALIC = "translation_italic";
    public static final String KEY_INCOMING_ENABLED = "incoming_enabled";
    public static final String KEY_OUTGOING_ENABLED = "outgoing_enabled";
    public static final String KEY_DEBUG_LOG = "debug_log";

    public static final String KEY_AI_ENDPOINT = "ai_endpoint";
    public static final String KEY_AI_KEY = "ai_key";
    public static final String KEY_AI_MODEL = "ai_model";
    public static final String KEY_AI_PROMPT = "ai_prompt";

    public static final String KEY_BAIDU_APPID = "baidu_appid";
    public static final String KEY_BAIDU_KEY = "baidu_key";
    public static final String KEY_DEEPL_KEY = "deepl_key";
    public static final String KEY_MS_KEY = "ms_key";
    public static final String KEY_MS_REGION = "ms_region";

    public static final String KEY_SHOW_LOCALE = "show_locale";
    public static final String KEY_MY_REGION = "my_region";

    /** Language used when translating what *we* send out. */
    public static final String KEY_OUT_LANG = "out_lang";

    public static final String ENGINE_GOOGLE_WEB = "google_web";
    public static final String ENGINE_GOOGLE_GTX = "google_gtx";
    public static final String ENGINE_BING = "bing";
    public static final String ENGINE_CUSTOM_AI = "custom_ai";

    public static final int DEFAULT_TRANSLATION_COLOR = 0xFF8A8A8A;

    private static final long RELOAD_INTERVAL_MS = 800L;

    private static volatile XSharedPreferences xPrefs;
    private static volatile long lastReload;

    private Prefs() {
    }

    /** Module process: a plain SharedPreferences handle for reading and writing.
     *  MODE_WORLD_READABLE is passed literally; platforms that restrict it simply
     *  ignore the bit, and {@link #mirror} covers that case. */
    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(NAME, 1);
    }

    /**
     * Second channel used only by the hooked process: a plain XML file the module
     * keeps world-readable. Read lazily with a short cache, so settings edits
     * still take effect without restarting WhatsApp.
     */
    private static java.util.Map<String, String> mirrorCache;
    private static long mirrorAt;

    private static void loadMirror() {
        long now = System.currentTimeMillis();
        if (mirrorCache != null && now - mirrorAt < RELOAD_INTERVAL_MS) {
            return;
        }
        mirrorAt = now;
        java.util.Map<String, String> map = null;
        String[] paths = {
                "/data/user/0/" + MODULE_PACKAGE + "/shared_prefs/" + MIRROR_NAME,
                "/data/data/" + MODULE_PACKAGE + "/shared_prefs/" + MIRROR_NAME,
        };
        for (String p : paths) {
            try {
                java.io.File f = new java.io.File(p);
                if (!f.canRead()) {
                    continue;
                }
                org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
                parser.setInput(new java.io.FileReader(f));
                map = new java.util.HashMap<String, String>();
                String key = null;
                StringBuilder sb = null;
                for (int ev = parser.getEventType();
                     ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
                     ev = parser.next()) {
                    if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        key = parser.getAttributeValue(null, "name");
                        sb = new StringBuilder();
                    } else if (ev == org.xmlpull.v1.XmlPullParser.TEXT && sb != null) {
                        sb.append(parser.getText());
                    } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                        if (key != null && sb != null) {
                            map.put(key, sb.toString());
                        }
                        key = null;
                        sb = null;
                    }
                }
                break;
            } catch (Throwable ignored) {
            }
        }
        mirrorCache = map;
    }

    private static String fromMirror(String key) {
        loadMirror();
        java.util.Map<String, String> m = mirrorCache;
        return m == null ? null : m.get(key);
    }

    /** Hooked process: read-only cross-process handle. */
    private static XSharedPreferences x() {
        XSharedPreferences p = xPrefs;
        if (p == null) {
            synchronized (Prefs.class) {
                p = xPrefs;
                if (p == null) {
                    p = new XSharedPreferences(MODULE_PACKAGE, NAME);
                    xPrefs = p;
                }
            }
        }
        return p;
    }

    private static void maybeReload() {
        long now = System.currentTimeMillis();
        if (now - lastReload < RELOAD_INTERVAL_MS) {
            return;
        }
        lastReload = now;
        try {
            x().reload();
        } catch (Throwable ignored) {
        }
    }

    public static String engine() {
        return readString(KEY_ENGINE, ENGINE_GOOGLE_WEB);
    }

    public static String targetLang() {
        return readString(KEY_TARGET_LANG, "zh-CN");
    }

    public static int translationColor() {
        return readInt(KEY_TRANSLATION_COLOR, DEFAULT_TRANSLATION_COLOR);
    }

    public static float translationScale() {
        return readFloat(KEY_TRANSLATION_SCALE, 0.85f);
    }

    public static boolean translationItalic() {
        return readBoolean(KEY_TRANSLATION_ITALIC, false);
    }

    public static boolean incomingEnabled() {
        return readBoolean(KEY_INCOMING_ENABLED, true);
    }

    public static boolean outgoingEnabled() {
        return readBoolean(KEY_OUTGOING_ENABLED, false);
    }

    public static boolean debugLog() {
        return readBoolean(KEY_DEBUG_LOG, true);
    }

    public static String aiEndpoint() {
        return readString(KEY_AI_ENDPOINT, "");
    }

    public static String aiKey() {
        return readString(KEY_AI_KEY, "");
    }

    public static String aiModel() {
        return readString(KEY_AI_MODEL, "");
    }

    public static String aiPrompt() {
        return readString(KEY_AI_PROMPT, Translators.DEFAULT_AI_PROMPT);
    }

    public static String baiduAppId() {
        return readString(KEY_BAIDU_APPID, "");
    }

    public static String baiduKey() {
        return readString(KEY_BAIDU_KEY, "");
    }

    public static String deeplKey() {
        return readString(KEY_DEEPL_KEY, "");
    }

    public static String msKey() {
        return readString(KEY_MS_KEY, "");
    }

    public static String msRegion() {
        return readString(KEY_MS_REGION, "");
    }

    public static boolean showLocale() {
        return readBoolean(KEY_SHOW_LOCALE, true);
    }

    /** Our own country code, e.g. "86". Used to detect "peer writes our language". */
    public static String myRegion() {
        return readString(KEY_MY_REGION, "86");
    }

    /** Target language for sent messages; empty means "use peer language". */
    public static String outLang() {
        return readString(KEY_OUT_LANG, "");
    }

    private static String readString(String key, String def) {
        try {
            maybeReload();
            String v = x().getString(key, def);
            if (v == null || (def != null && def.equals(v))) {
                String m = fromMirror(key);
                if (m != null) {
                    return m;
                }
            }
            return v == null ? def : v;
        } catch (Throwable t) {
            String m = fromMirror(key);
            return m != null ? m : def;
        }
    }

    private static int readInt(String key, int def) {
        try {
            maybeReload();
            return x().getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean readBoolean(String key, boolean def) {
        try {
            maybeReload();
            return x().getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static float readFloat(String key, float def) {
        try {
            return Float.parseFloat(readString(key, String.valueOf(def)));
        } catch (Throwable t) {
            return def;
        }
    }
}
