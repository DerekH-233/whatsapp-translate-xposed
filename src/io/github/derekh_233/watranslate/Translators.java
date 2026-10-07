package io.github.derekh_233.watranslate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;

/**
 * Translation backends - no registration, no subscription, no vendor server.
 *
 * Two families:
 *   - public/anonymous: Google (translateHtml), Google (gtx), Bing, MyMemory
 *   - user-keyed:       Baidu, DeepL, Microsoft, custom OpenAI-compatible AI
 *
 * Public key used by the Google web client. It is not a user credential and not
 * a secret; it ships inside Google's own page source.
 */
public final class Translators {

    private static final String GOOGLE_WEB_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";

    /**
     * Default translation prompt for the custom-AI engine.
     *
     * The three rules are not decoration - each one removes a specific failure
     * mode that a general chat model exhibits when asked to translate (see the
     * README for the A/B measurements):
     *   rule 1 stops the "Sure, here's the translation:" preamble,
     *   rule 2 stops it from re-flowing the paragraphs,
     *   rule 3 stops it from "helpfully" renaming identifiers.
     * {@code ${toCode}} is substituted with the configured target language.
     */
    public static final String DEFAULT_AI_PROMPT =
            "You are a professional ${toCode} native translator who needs to fluently translate text into ${toCode}.\n"
                    + "\n"
                    + "## Translation Rules\n"
                    + "1. Output only the translated content, without explanations or additional content "
                    + "(such as \"Here's the translation:\" or \"Translation as follows:\")\n"
                    + "2. The returned translation must maintain exactly the same number of paragraphs "
                    + "and format as the original text\n"
                    + "3. For content that should not be translated (such as proper nouns, code, etc.), "
                    + "keep the original text.\n"
                    + "\n"
                    + "## OUTPUT FORMAT:\n"
                    + "Output translation directly";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private Translators() {
    }

    /** Returns translated text, or null. Never throws. */
    public static String translate(String text, String from, String to) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        Engine engine = Engine.of(Prefs.engine());
        try {
            switch (engine) {
                case GOOGLE_GTX:
                    return googleGtx(text, from, to);
                case BING:
                    return bing(text, from, to);
                case MYMEMORY:
                    return myMemory(text, from, to);
                case BAIDU:
                    return baidu(text, from, to);
                case DEEPL:
                    return deepl(text, from, to);
                case MICROSOFT:
                    return microsoft(text, from, to);
                case CUSTOM_AI:
                    return customAi(text, from, to);
                case GOOGLE_WEB:
                default:
                    return googleWeb(text, from, to);
            }
        } catch (Throwable t) {
            Logger.w("translate[" + engine.id() + "] failed: " + t);
            return null;
        }
    }

    // ============================================================ Google web

    private static String googleWeb(String text, String from, String to) throws Exception {
        JSONArray inner = new JSONArray();
        JSONArray sentence = new JSONArray();
        sentence.put(escapeHtmlForGoogle(text));
        inner.put(sentence);
        inner.put(normalizeAuto(from));
        inner.put(to);
        JSONArray payload = new JSONArray();
        payload.put(inner);
        payload.put("wt_lib");

        HttpURLConnection c = open("https://translate-pa.googleapis.com/v1/translateHtml", "POST");
        c.setRequestProperty("Content-Type", "application/json+protobuf");
        c.setRequestProperty("X-Goog-API-Key", GOOGLE_WEB_KEY);
        write(c, payload.toString());
        String body = readOrLog(c);
        if (body == null) {
            return null;
        }
        Logger.d("googleWeb resp=" + shorten(body));
        return clean(extractFirstString(new JSONArray(body).opt(0)));
    }

    // ============================================================ Google gtx

    private static String googleGtx(String text, String from, String to) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl="
                + enc(normalizeAuto(from)) + "&tl=" + enc(to) + "&q=" + enc(text);
        HttpURLConnection c = open(url, "GET");
        String body = readOrLog(c);
        if (body == null) {
            return null;
        }
        Logger.d("googleGtx resp=" + shorten(body));
        JSONArray root = new JSONArray(body);
        JSONArray items = root.getJSONArray(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONArray item = items.optJSONArray(i);
            if (item != null && item.length() > 0 && !item.isNull(0)) {
                sb.append(item.getString(0));
            }
        }
        return clean(sb.toString());
    }

    // ================================================================== Bing

    private static String bing(String text, String from, String to) throws Exception {
        bingAuth();
        String url = "https://www.bing.com/ttranslatev3?isVertical=1&&IG="
                + BING_IG + "&IID=translator.5028.1";
        String form = "fromLang=" + enc("auto".equals(normalizeAuto(from)) ? "auto-detect" : from)
                + "&text=" + enc(text)
                + "&to=" + enc(mapBingLang(to));
        HttpURLConnection c = open(url, "POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Referer", "https://www.bing.com/translator");
        c.setRequestProperty("User-Agent", UA);
        write(c, form);
        String body = readOrLog(c);
        if (body == null) {
            return null;
        }
        Logger.d("bing resp=" + shorten(body));
        JSONArray root = new JSONArray(body);
        JSONObject first = root.optJSONObject(0);
        if (first == null) {
            return null;
        }
        JSONArray tr = first.optJSONArray("translations");
        if (tr == null || tr.length() == 0) {
            return null;
        }
        return clean(tr.getJSONObject(0).optString("text", null));
    }

    private static volatile String BING_IG = "1";
    private static volatile boolean bingAuthed;

    /** Bing expects an IG value scraped from the translator page. */
    private static synchronized void bingAuth() {
        if (bingAuthed) {
            return;
        }
        bingAuthed = true;
        try {
            HttpURLConnection c = open("https://www.bing.com/translator", "GET");
            c.setRequestProperty("User-Agent", UA);
            String page = streamToString(c.getInputStream());
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("IG:\"([0-9A-Fa-f]+)\"").matcher(page);
            if (m.find()) {
                BING_IG = m.group(1);
                Logger.d("bing IG=" + BING_IG);
            } else {
                m = java.util.regex.Pattern.compile("IG:([0-9A-Fa-f]+)").matcher(page);
                if (m.find()) {
                    BING_IG = m.group(1);
                    Logger.d("bing IG(alt)=" + BING_IG);
                }
            }
        } catch (Throwable t) {
            Logger.d("bing auth failed, using default IG: " + t);
        }
    }

    private static String mapBingLang(String to) {
        if (to == null) {
            return "zh-Hans";
        }
        if (to.equalsIgnoreCase("zh-CN") || to.equalsIgnoreCase("zh")) {
            return "zh-Hans";
        }
        if (to.equalsIgnoreCase("zh-TW")) {
            return "zh-Hant";
        }
        return to;
    }

    // ============================================================== MyMemory

    private static String myMemory(String text, String from, String to) throws Exception {
        String t = text.length() > 450 ? text.substring(0, 450) : text;
        String url = "https://api.mymemory.translated.net/get?q=" + enc(t)
                + "&langpair=" + enc(normalizeAuto(from) + "|" + to);
        HttpURLConnection c = open(url, "GET");
        String body = readOrLog(c);
        if (body == null) {
            return null;
        }
        Logger.d("mymemory resp=" + shorten(body));
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("responseData");
        if (data == null) {
            return null;
        }
        return clean(data.optString("translatedText", null));
    }

    // ================================================================= Baidu

    private static String baidu(String text, String from, String to) throws Exception {
        String appId = Prefs.baiduAppId();
        String secret = Prefs.baiduKey();
        if (appId.isEmpty() || secret.isEmpty()) {
            Logger.w("baidu: appid/key empty, skip");
            return null;
        }
        String salt = String.valueOf(System.currentTimeMillis());
        String sign = md5(appId + text + salt + secret);
        String url = "https://fanyi-api.baidu.com/api/trans/vip/translate";
        String form = "q=" + enc(text) + "&from=" + enc(mapBaiduLang(normalizeAuto(from)))
                + "&to=" + enc(mapBaiduLang(to)) + "&appid=" + enc(appId)
                + "&salt=" + enc(salt) + "&sign=" + enc(sign);
        HttpURLConnection c = open(url, "POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        write(c, form);
        String body = readOrLog(c);
        if (body == null) {
            return null;
        }
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("trans_result");
        if (arr == null || arr.length() == 0) {
            Logger.w("baidu error: " + shorten(body));
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String dst = arr.getJSONObject(i).optString("dst", "");
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(dst);
        }
        return clean(sb.toString());
    }

    private static String mapBaiduLang(String code) {
        if (code == null) {
            return "auto";
        }
        if (code.startsWith("zh")) {
            return code.equalsIgnoreCase("zh-TW") ? "cht" : "zh";
        }
        if (code.startsWith("en")) {
            return "en";
        }
        return code.contains("-") ? code.substring(0, code.indexOf('-')) : code;
    }

    // ================================================================= DeepL

    private static String deepl(String text, String from, String to) throws Exception {
        String key = Prefs.deeplKey();
        if (key.isEmpty()) {
            Logger.w("deepl: key empty, skip");
            return null;
        }
        // free keys end with ":fx"
        String host = key.endsWith(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com";
        StringBuilder form = new StringBuilder();
        form.append("text=").append(enc(text));
        form.append("&target_lang=").append(enc(mapDeepL(to)));
        String src = normalizeAuto(from);
        if (!"auto".equals(src)) {
            form.append("&source_lang=").append(enc(mapDeepL(src)));
        }
        HttpURLConnection c = open(host + "/v2/translate", "POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Authorization", "DeepL-Auth-Key " + key.trim());
        write(c, form.toString());
        String body = readOrLog(c);
        if (body == null) {
            return null;
        }
        JSONArray tr = new JSONObject(body).optJSONArray("translations");
        if (tr == null || tr.length() == 0) {
            return null;
        }
        return clean(tr.getJSONObject(0).optString("text", null));
    }

    private static String mapDeepL(String code) {
        if (code == null) {
            return "ZH";
        }
        if (code.equalsIgnoreCase("zh-CN") || code.equalsIgnoreCase("zh")) {
            return "ZH";
        }
        if (code.equalsIgnoreCase("zh-TW")) {
            return "ZH-HANT";
        }
        if (code.equalsIgnoreCase("en") || code.startsWith("en-")) {
            return "EN";
        }
        String base = code.contains("-") ? code.substring(0, code.indexOf('-')) : code;
        return base.toUpperCase(java.util.Locale.US);
    }

    // ============================================================ Microsoft

    private static String microsoft(String text, String from, String to) throws Exception {
        String key = Prefs.msKey();
        if (key.isEmpty()) {
            Logger.w("microsoft: key empty, skip");
            return null;
        }
        String region = Prefs.msRegion();
        String url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0"
                + "&to=" + enc(to);
        String src = normalizeAuto(from);
        if (!"auto".equals(src)) {
            url += "&from=" + enc(src);
        }
        JSONArray body = new JSONArray();
        body.put(new JSONObject().put("Text", text));
        HttpURLConnection c = open(url, "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Ocp-Apim-Subscription-Key", key.trim());
        if (!region.isEmpty()) {
            c.setRequestProperty("Ocp-Apim-Subscription-Region", region.trim());
        }
        write(c, body.toString());
        String resp = readOrLog(c);
        if (resp == null) {
            return null;
        }
        JSONArray arr = new JSONArray(resp);
        JSONArray tr = arr.optJSONObject(0) == null ? null : arr.getJSONObject(0).optJSONArray("translations");
        if (tr == null || tr.length() == 0) {
            return null;
        }
        return clean(tr.getJSONObject(0).optString("text", null));
    }

    // ============================================================ Custom AI

    private static String customAi(String text, String from, String to) throws Exception {
        String endpoint = normalizeEndpoint(Prefs.aiEndpoint());
        String key = Prefs.aiKey();
        String model = Prefs.aiModel();
        if (endpoint.isEmpty()) {
            Logger.w("custom AI: endpoint empty, skip");
            return null;
        }
        if (key.trim().isEmpty()) {
            Logger.w("custom AI: api key empty, skip");
            return null;
        }
        if (model.trim().isEmpty()) {
            model = "gpt-4o-mini";
        }
        String system = renderPrompt(Prefs.aiPrompt(), from, to);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.2d);
        body.put("stream", false);
        JSONArray messages = new JSONArray();
        if (system != null && !system.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", system));
        }
        messages.put(new JSONObject().put("role", "user").put("content", text));
        body.put("messages", messages);

        HttpURLConnection c = open(endpoint, "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key.trim());
        write(c, body.toString());
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String resp = streamToString(in);
        if (code < 200 || code >= 300) {
            Logger.w("custom AI HTTP " + code + " | endpoint=" + endpoint + " | body=" + shorten(resp));
            return null;
        }
        Logger.d("custom AI resp=" + shorten(resp));
        JSONArray choices = new JSONObject(resp).optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        return msg == null ? null : clean(msg.optString("content", null));
    }

    // =========================================================== text utils

    static String normalizeEndpoint(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return "";
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        if (v.endsWith("/chat/completions") || v.endsWith("/completions")) {
            return v;
        }
        return v + "/v1/chat/completions";
    }

    private static String renderPrompt(String template, String from, String to) {
        String tpl = template == null ? "" : template.trim();
        if (tpl.isEmpty()) {
            tpl = DEFAULT_AI_PROMPT;
        }
        String src = "auto".equals(normalizeAuto(from)) ? "自动检测语言" : from;
        return tpl.replace("${fromCode === 'auto' ? '自动检测语言' : fromCode}", src)
                .replace("${toCode}", to == null ? "zh-CN" : to);
    }

    private static String normalizeAuto(String from) {
        return (from == null || from.isEmpty()) ? "auto" : from;
    }

    private static String escapeHtmlForGoogle(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private static String extractFirstString(Object node) {
        if (node == null || node == JSONObject.NULL) {
            return null;
        }
        if (node instanceof String) {
            return (String) node;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                String got = extractFirstString(arr.opt(i));
                if (got != null) {
                    return got;
                }
            }
        }
        return null;
    }

    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.startsWith("```")) {
            int nl = v.indexOf('\n');
            if (nl >= 0) {
                v = v.substring(nl + 1);
            }
            if (v.endsWith("```")) {
                v = v.substring(0, v.length() - 3);
            }
            v = v.trim();
        }
        v = unescapeHtml(v);
        v = v.replaceAll("(?i)<br\\s*/?>", "\n");
        v = v.replaceAll("(?i)</p\\s*>", "\n");
        v = v.replaceAll("(?i)<p[^>]*>", "");
        v = v.replaceAll("\n{3,}", "\n\n");
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static String unescapeHtml(String s) {
        return s.replace("&nbsp;", " ").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    // ============================================================ networking

    /** */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setDoInput(true);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if ("POST".equals(method)) {
            c.setDoOutput(true);
        }
        return c;
    }

    private static void write(HttpURLConnection c, String body) throws Exception {
        byte[] bytes = body.getBytes(UTF8);
        c.setFixedLengthStreamingMode(bytes.length);
        OutputStream os = c.getOutputStream();
        try {
            os.write(bytes);
            os.flush();
        } finally {
            close(os);
        }
    }

    /** Returns the body on 2xx, otherwise logs the failure and returns null. */
    private static String readOrLog(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        String body = streamToString(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) {
            Logger.w("HTTP " + code + " " + c.getURL() + " | body=" + shorten(body));
            return null;
        }
        return body;
    }

    private static String streamToString(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        close(in);
        return new String(bos.toByteArray(), UTF8);
    }

    private static void close(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String md5(String in) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] d = md.digest(in.getBytes(UTF8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String shorten(String s) {
        if (s == null) {
            return "null";
        }
        String v = s.replace('\n', ' ');
        v = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return v.length() > 220 ? v.substring(0, 220) + "..." : v;
    }
}
