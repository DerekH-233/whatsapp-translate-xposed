package com.littlesauce.watrans;

import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Bilingual rendering pipeline - the core feature.
 *
 * <ol>
 *   <li>hook {@code TextView.setText(CharSequence)};</li>
 *   <li>act only on WhatsApp's message-text views, whose resource ids are
 *       resolved by <em>name</em> at runtime (no hardcoded version-specific
 *       values);</li>
 *   <li>translate asynchronously, then re-set the view text as
 *       {@code original + "\n" + translation} with the translation slice wrapped
 *       in a {@link ForegroundColorSpan} (user-chosen colour) and a
 *       {@link RelativeSizeSpan} (user-chosen scale) - the grey sub-line;</li>
 *   <li>keep it re-entrancy safe and recycle safe with per-view additional
 *       fields via {@link XposedHelpers}, plus a thread-local marker so our own
 *       {@code setText} is ignored.</li>
 * </ol>
 */
public final class MessageHook {

    private static final String TAG = "[LSTrans]";

    private static final String F_SOURCE = "lst_source";
    private static final String F_RENDERED = "lst_rendered";
    private static final String F_INFLIGHT = "lst_inflight";

    private static final ThreadLocal<Boolean> SELF_SET = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<String, String>();

    private static volatile boolean installed;
    private static volatile List<Integer> replyIds = Collections.emptyList();
    private static volatile List<Integer> quoteIds = Collections.emptyList();

    private MessageHook() {
    }

    public static void install(ClassLoader loader) {
        if (installed) {
            return;
        }
        installed = true;
        resolveIds(loader);

        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (Boolean.TRUE.equals(SELF_SET.get())) {
                        return;
                    }
                    Object self = param.thisObject;
                    if (!(self instanceof TextView)) {
                        return;
                    }
                    onSetText((TextView) self);
                } catch (Throwable t) {
                    Logger.w("onSetText error: " + t);
                }
            }
        });
        XposedBridge.log(TAG + " installed; replyIds=" + replyIds + " quoteIds=" + quoteIds);
    }

    // ------------------------------------------------------------ view ids

    private static void resolveIds(ClassLoader loader) {
        List<Integer> reply = new ArrayList<Integer>();
        List<Integer> quote = new ArrayList<Integer>();

        // 1) Preferred: look the names up in the target app's own resource table,
        //    so a WhatsApp version bump does not break us.
        android.content.res.Resources res = null;
        String pkg = null;
        try {
            android.app.Application app =
                    (android.app.Application) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.AndroidAppHelper", loader),
                            "currentApplication");
            if (app != null) {
                res = app.getResources();
                pkg = app.getPackageName();
            }
        } catch (Throwable t) {
            Logger.d("app resources unavailable: " + t);
        }
        if (res != null && pkg != null) {
            int id = res.getIdentifier("message_text", "id", pkg);
            if (id != 0) {
                reply.add(id);
            }
            id = res.getIdentifier("caption", "id", pkg);
            if (id != 0) {
                reply.add(id);
            }
            id = res.getIdentifier("quoted_text", "id", pkg);
            if (id != 0) {
                quote.add(id);
            }
        }

        // 2) Also read the R class when it is reachable.
        try {
            Class<?> rid = XposedHelpers.findClass("com.whatsapp.R$id", loader);
            for (Field f : rid.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                    continue;
                }
                String name = f.getName();
                if (!name.contains("text") && !name.contains("caption")) {
                    continue;
                }
                int val = f.getInt(null);
                if (val == 0) {
                    continue;
                }
                if ("message_text".equals(name) || "caption".equals(name)) {
                    reply.add(val);
                } else if (name.contains("quoted")) {
                    quote.add(val);
                }
            }
        } catch (Throwable t) {
            Logger.d("R$id lookup skipped: " + t);
        }

        // 3) Last resort: ids verified against WhatsApp's own resource table
        //    (id/message_text, id/caption, id/quoted_text).
        reply.add(0x7f0b26b8);
        reply.add(0x7f0b0bad);
        quote.add(0x7f0b32d9);

        replyIds = Collections.unmodifiableList(dedup(reply));
        quoteIds = Collections.unmodifiableList(dedup(quote));
        Logger.d("resolved replyIds=" + replyIds + " quoteIds=" + quoteIds);
    }

    private static List<Integer> dedup(List<Integer> in) {
        List<Integer> out = new ArrayList<Integer>();
        for (Integer v : in) {
            if (v != null && v != 0 && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean isTargetId(int id) {
        return id != 0 && (replyIds.contains(id) || quoteIds.contains(id));
    }

    // ------------------------------------------------------------- pipeline

    private static void onSetText(TextView tv) {
        if (!Prefs.incomingEnabled()) {
            return;
        }
        if (!isTargetId(tv.getId())) {
            return;
        }
        CharSequence current = tv.getText();
        if (TextUtils.isEmpty(current)) {
            return;
        }
        final String source = current.toString().replace('\u200b', ' ').trim();
        if (source.isEmpty() || source.length() > 1500) {
            return;
        }
        Object rendered = XposedHelpers.getAdditionalInstanceField(tv, F_RENDERED);
        if (rendered != null && source.equals(rendered.toString())) {
            return;
        }
        if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(tv, F_INFLIGHT))) {
            return;
        }
        final String engine = Prefs.engine();
        final String target = Prefs.targetLang();
        final String cacheKey = engine + '\u0000' + target + '\u0000' + source;
        String cached = CACHE.get(cacheKey);
        if (cached != null) {
            render(tv, source, cached);
            return;
        }

        XposedHelpers.setAdditionalInstanceField(tv, F_INFLIGHT, Boolean.TRUE);
        XposedHelpers.setAdditionalInstanceField(tv, F_SOURCE, source);
        final TextView view = tv;

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final String result;
                try {
                    result = Translators.translate(source, "auto", target);
                } catch (Throwable t) {
                    Logger.w("translate threw: " + t);
                    clearInflight(view);
                    return;
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            XposedHelpers.setAdditionalInstanceField(view, F_INFLIGHT, Boolean.FALSE);
                            if (result == null || result.isEmpty() || result.equals(source)) {
                                return;
                            }
                            Object latest = XposedHelpers.getAdditionalInstanceField(view, F_SOURCE);
                            if (!source.equals(latest)) {
                                return; // view was recycled onto another message
                            }
                            CACHE.put(cacheKey, result);
                            render(view, source, result);
                        } catch (Throwable t) {
                            Logger.w("render stage error: " + t);
                        }
                    }
                });
            }
        });
    }

    private static void clearInflight(final TextView view) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                XposedHelpers.setAdditionalInstanceField(view, F_INFLIGHT, Boolean.FALSE);
            }
        });
    }

    /** {@code original + "\n" + translation}, translation styled and coloured. */
    static void render(TextView tv, String source, String translation) {
        SpannableString span = new SpannableString(source + "\n" + translation);
        int start = source.length() + 1;
        int end = span.length();
        span.setSpan(new ForegroundColorSpan(Prefs.translationColor()), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new RelativeSizeSpan(Prefs.translationScale()), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (Prefs.translationItalic()) {
            span.setSpan(new ItalicSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        XposedHelpers.setAdditionalInstanceField(tv, F_RENDERED, span.toString());
        SELF_SET.set(Boolean.TRUE);
        try {
            tv.setText(span);
        } finally {
            SELF_SET.set(Boolean.FALSE);
        }
        Logger.d("rendered -> " + (translation.length() > 60
                ? translation.substring(0, 60) + "..." : translation));
    }

    /** Italic without depending on android.text.style.StyleSpan. */
    private static final class ItalicSpan extends MetricAffectingSpan {
        @Override
        public void updateMeasureState(TextPaint p) {
            p.setTextSkewX(-0.25f);
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setTextSkewX(-0.25f);
        }
    }
}
