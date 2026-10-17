package io.github.derekh_233.watranslate;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.libxposed.api.XposedModule;

/**
 * Settings access, from both sides of the process boundary.
 *
 * <ul>
 *   <li><b>Hooked process</b> - reads through the framework's remote preferences
 *       ({@link XposedModule#getRemotePreferences(String)}), which the module app
 *       writes via {@link PrefsBridge}. The handle is re-fetched on a short
 *       interval so edits still take effect without restarting WhatsApp.</li>
 *   <li><b>Module process</b> - {@link #get(Context)} is the ordinary local
 *       preferences backing the settings UI; every edit is pushed to the
 *       framework by {@link PrefsBridge}.</li>
 * </ul>
 */
public final class Prefs {

    public static final String MODULE_PACKAGE = "io.github.derekh_233.watranslate";

    /** Local prefs file name, and the group name used for remote preferences. */
    public static final String NAME = "prefs";

    public static final String KEY_ENGINE = "engine";
    public static final String KEY_TARGET_LANG = "target_lang";
    public static final String KEY_TRANSLATION_COLOR = "translation_color";
    public static final String KEY_TRANSLATION_SCALE = "translation_scale";
    public static final String KEY_TRANSLATION_ITALIC = "translation_italic";
    public static final String KEY_INCOMING_ENABLED = "incoming_enabled";
    public static final String KEY_OUTGOING_ENABLED = "outgoing_enabled";
    public static final String KEY_DEBUG_LOG = "debug_log";

    /** Long-press the send button to translate the draft. */
    public static final String KEY_INPUT_TRANSLATE_ENABLED = "input_translate_enabled";

    /** Aim the outgoing language at whoever the chat is with. */
    public static final String KEY_FOLLOW_PEER = "follow_peer";

    /** "system", "light" or "dark" - which palette the settings screen draws in. */
    public static final String KEY_THEME = "theme";

    /** Engine used for the outgoing draft; empty means "the same as incoming". */
    public static final String KEY_OUT_ENGINE = "out_engine";

    public static final String KEY_AI_ENDPOINT = "ai_endpoint";
    public static final String KEY_AI_KEY = "ai_key";
    public static final String KEY_AI_MODEL = "ai_model";
    public static final String KEY_AI_PROMPT = "ai_prompt";

    /** Which API shape the custom AI endpoint speaks. */
    public static final String KEY_AI_FORMAT = "ai_format";

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

    /** How long a remote-preferences snapshot is reused before re-fetching. */
    private static final long REFRESH_MS = 800L;

    private static volatile XposedModule module;
    private static volatile SharedPreferences local;
    private static volatile SharedPreferences remote;
    private static volatile long remoteAt;

    private Prefs() {
    }

    static void attach(XposedModule m) {
        module = m;
    }

    /**
     * Module process only: the local preferences behind the settings UI.
     * In a hooked process this returns null - use the typed accessors instead.
     */
    public static SharedPreferences get(Context ctx) {
        SharedPreferences p = local;
        if (p == null) {
            synchronized (Prefs.class) {
                p = local;
                if (p == null) {
                    p = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
                    local = p;
                }
            }
        }
        return p;
    }

    /** The preferences to read from, whichever side of the boundary we are on. */
    private static SharedPreferences source() {
        XposedModule m = module;
        if (m == null) {
            return local;
        }
        long now = System.currentTimeMillis();
        SharedPreferences p = remote;
        if (p != null && now - remoteAt < REFRESH_MS) {
            return p;
        }
        synchronized (Prefs.class) {
            if (remote != null && now - remoteAt < REFRESH_MS) {
                return remote;
            }
            try {
                p = m.getRemotePreferences(NAME);
                remote = p;
                remoteAt = now;
            } catch (Throwable t) {
                if (remote == null) {
                    Logger.w("getRemotePreferences failed: " + t);
                }
                return remote;
            }
            return p;
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

    /** Long-press the send button to translate what is in the chat box. */
    public static boolean inputTranslateEnabled() {
        return readBoolean(KEY_INPUT_TRANSLATE_ENABLED, true);
    }

    /**
     * A fingerprint of everything that decides how a rendered translation looks.
     *
     * <p>Cheap to compare, so the renderer can notice a style edit and repaint
     * what is already on screen. Without that, a colour change only reaches
     * messages that happen to be re-bound afterwards, which reads as "the setting
     * did nothing" until WhatsApp is restarted.</p>
     */
    public static String styleSignature() {
        return Integer.toHexString(translationColor()) + '/' + translationScale()
                + '/' + translationItalic();
    }

    /**
     * Whether the outgoing language should follow the person being written to.
     *
     * <p>On by default: talking to several countries is the case this exists for,
     * and when the peer cannot be resolved the configured {@link #outLang()} is
     * used unchanged.</p>
     */
    public static boolean followPeer() {
        return readBoolean(KEY_FOLLOW_PEER, true);
    }

    /**
     * Engine for the outgoing draft, falling back to the incoming one.
     *
     * <p>Empty means "follow {@link #engine()}", which is what an existing
     * install gets and what most people want.</p>
     */
    public static String outEngine() {
        String v = readString(KEY_OUT_ENGINE, "");
        return v == null || v.isEmpty() ? engine() : v;
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

    /** API shape for the custom AI endpoint; see {@link Translators} for the names. */
    public static String aiFormat() {
        return readString(KEY_AI_FORMAT, Translators.AI_CHAT);
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

    /**
     * Language the chat box gets translated into. Defaults to English because
     * the direction people want here is "write in my language, send in theirs",
     * not the incoming direction.
     */
    public static String outLang() {
        return readString(KEY_OUT_LANG, "en");
    }

    private static String readString(String key, String def) {
        try {
            SharedPreferences p = source();
            if (p == null) {
                return def;
            }
            String v = p.getString(key, def);
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    private static int readInt(String key, int def) {
        try {
            SharedPreferences p = source();
            if (p == null) {
                return def;
            }
            return p.getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean readBoolean(String key, boolean def) {
        try {
            SharedPreferences p = source();
            if (p == null) {
                return def;
            }
            return p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static float readFloat(String key, float def) {
        try {
            String s = readString(key, String.valueOf(def));
            return Float.parseFloat(s);
        } catch (Throwable t) {
            return def;
        }
    }
}
