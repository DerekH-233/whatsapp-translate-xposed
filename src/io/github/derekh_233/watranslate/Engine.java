package io.github.derekh_233.watranslate;

/**
 * The eight translation backends. Five of them work with no account and no key
 * at all; the three marked {@code needsKey} only ever send text to an endpoint
 * the user configured themselves.
 *
 * Every endpoint in this list was probed from the target device before being
 * wired in - see the README for the probe results.
 */
public enum Engine {

    GOOGLE_WEB("google_web", "Google 翻译（免密钥，推荐）", false),
    GOOGLE_GTX("google_gtx", "Google 翻译 · 备用通道（免密钥）", false),
    BING("bing", "Bing 翻译（免密钥）", false),
    MYMEMORY("mymemory", "MyMemory 翻译记忆库（免密钥）", false),
    BAIDU("baidu", "百度翻译（需填自己的 AppID + 密钥）", true),
    DEEPL("deepl", "DeepL 翻译（需填自己的 Auth Key）", true),
    MICROSOFT("microsoft", "微软翻译（需填自己的订阅密钥）", true),
    CUSTOM_AI("custom_ai", "自定义 AI 接口（OpenAI 兼容，需填自己的 Key）", true);

    private final String id;
    private final String label;
    private final boolean needsKey;

    Engine(String id, String label, boolean needsKey) {
        this.id = id;
        this.label = label;
        this.needsKey = needsKey;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public boolean needsKey() {
        return needsKey;
    }

    public static Engine of(String id) {
        if (id != null) {
            for (Engine e : values()) {
                if (e.id.equals(id)) {
                    return e;
                }
            }
        }
        return GOOGLE_WEB;
    }

    public static Engine[] all() {
        return values();
    }

    public static String[] labels() {
        Engine[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].label;
        }
        return out;
    }

    public static String[] ids() {
        Engine[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].id;
        }
        return out;
    }
}
