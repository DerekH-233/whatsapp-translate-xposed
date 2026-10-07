package io.github.derekh_233.watranslate;

import android.content.res.Resources;
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Bilingual rendering pipeline - the core feature.
 *
 * <ol>
 *   <li>hook {@code TextView.setText};</li>
 *   <li>act only on WhatsApp's message-text views, whose resource ids are
 *       resolved from the target app's own resource table at runtime (with ids
 *       verified against WhatsApp's {@code resources.arsc} as fallback);</li>
 *   <li>translate asynchronously, then re-set the view text as
 *       {@code original + "\n" + translation} with the translation slice wrapped
 *       in a {@link ForegroundColorSpan} (user-chosen colour) and a
 *       {@link RelativeSizeSpan} (user-chosen scale) - the small sub-line;</li>
 *   <li>stay re-entrancy safe with a thread-local marker, and recycle safe with
 *       a per-view state snapshot so a recycled view never shows a stale
 *       translation.</li>
 * </ol>
 *
 * <p>Uses only the modern API: hooks go through
 * {@link XposedModule#hook(java.lang.reflect.Executable)} and the interceptor
 * chain. Per-view state lives in this class rather than in framework-provided
 * additional fields, which the modern API does not offer.</p>
 */
public final class MessageHook {

    private static final String TAG = "LSTrans";

    private static final ThreadLocal<Boolean> SELF_SET = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<String, String>();

    /** Per-view bookkeeping; weak keys so recycled/destroyed views are collectable. */
    private static final Map<TextView, ViewState> STATES =
            Collections.synchronizedMap(new WeakHashMap<TextView, ViewState>());

    private static volatile boolean installed;
    private static volatile boolean idsResolved;
    private static volatile List<Integer> replyIds = Collections.emptyList();
    private static volatile List<Integer> quoteIds = Collections.emptyList();

    private MessageHook() {
    }

    private static final class ViewState {
        String source;
        String rendered;
        boolean inflight;
    }

    private static ViewState stateOf(TextView tv) {
        synchronized (STATES) {
            ViewState s = STATES.get(tv);
            if (s == null) {
                s = new ViewState();
                STATES.put(tv, s);
            }
            return s;
        }
    }

    public static synchronized void install(XposedModule module, ClassLoader loader, String pkg) {
        if (installed) {
            return;
        }
        if (module == null) {
            throw new IllegalStateException("module instance required");
        }
        installed = true;
        resolveIdsFromLoader(loader, pkg);

        XposedInterface.Hooker hooker = new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    if (!Boolean.TRUE.equals(SELF_SET.get())) {
                        Object self = chain.getThisObject();
                        if (self instanceof TextView) {
                            onSetText((TextView) self);
                        }
                    }
                } catch (Throwable t) {
                    Logger.w("onSetText error: " + t);
                }
                return result;
            }
        };

        int hooked = 0;
        for (Method m : setTextMethods()) {
            try {
                module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(hooker);
                hooked++;
            } catch (Throwable t) {
                Logger.w("hook " + m + " failed: " + t);
            }
        }
        Logger.w("installed on " + pkg + "; " + hooked + " setText overload(s); replyIds="
                + replyIds + " quoteIds=" + quoteIds);
    }

    /** The {@code setText} overloads that take text; the int/char[] ones cannot match. */
    private static List<Method> setTextMethods() {
        List<Method> out = new ArrayList<Method>();
        for (Method m : TextView.class.getDeclaredMethods()) {
            if (!"setText".equals(m.getName())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length >= 1 && p[0] == CharSequence.class) {
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ view ids

    /**
     * Resolve the message view ids before any view exists, using the R class
     * when reachable. Name-based lookup against the live resource table happens
     * lazily in {@link #ensureIdsFromView}.
     */
    private static void resolveIdsFromLoader(ClassLoader loader, String pkg) {
        List<Integer> reply = new ArrayList<Integer>();
        List<Integer> quote = new ArrayList<Integer>();

        for (String cls : new String[]{pkg + ".R$id", "com.whatsapp.R$id",
                "com.whatsapp.w4b.R$id"}) {
            try {
                Class<?> rid = Class.forName(cls, false, loader);
                for (Field f : rid.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                        continue;
                    }
                    String name = f.getName();
                    int val = f.getInt(null);
                    if (val == 0) {
                        continue;
                    }
                    if ("message_text".equals(name) || "caption".equals(name)) {
                        reply.add(val);
                    } else if (name.contains("quoted") && name.contains("text")) {
                        quote.add(val);
                    }
                }
                Logger.d("R$id read from " + cls);
                break;
            } catch (Throwable t) {
                Logger.d("R$id not available via " + cls);
            }
        }

        // Ids verified against WhatsApp's own resources.arsc (id/message_text,
        // id/caption, id/quoted_text); kept as the last line of defence.
        reply.add(0x7f0b26b8);
        reply.add(0x7f0b0bad);
        quote.add(0x7f0b32d9);

        replyIds = Collections.unmodifiableList(dedup(reply));
        quoteIds = Collections.unmodifiableList(dedup(quote));
    }

    /**
     * Ask the target app's own resource table, which is available from any view
     * in that process. Cheap and version-proof, so it is attempted once.
     */
    private static void ensureIdsFromView(TextView tv) {
        if (idsResolved) {
            return;
        }
        synchronized (MessageHook.class) {
            if (idsResolved) {
                return;
            }
            idsResolved = true;
            try {
                Resources res = tv.getResources();
                android.content.Context ctx = tv.getContext();
                if (res == null || ctx == null) {
                    return;
                }
                String pkg = ctx.getPackageName();
                List<Integer> reply = new ArrayList<Integer>(replyIds);
                List<Integer> quote = new ArrayList<Integer>(quoteIds);
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
                replyIds = Collections.unmodifiableList(dedup(reply));
                quoteIds = Collections.unmodifiableList(dedup(quote));
                Logger.d("resource lookup in " + pkg + " -> replyIds=" + replyIds
                        + " quoteIds=" + quoteIds);
            } catch (Throwable t) {
                Logger.d("resource lookup skipped: " + t);
            }
        }
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
        ensureIdsFromView(tv);
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

        final ViewState st = stateOf(tv);
        if (source.equals(st.rendered) || st.inflight) {
            return;
        }

        final String engine = Prefs.engine();
        final String target = Prefs.targetLang();
        final String cacheKey = engine + '\u0000' + target + '\u0000' + source;
        String cached = CACHE.get(cacheKey);
        if (cached != null) {
            render(tv, st, source, cached);
            return;
        }

        st.inflight = true;
        st.source = source;
        final TextView view = tv;

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final String result;
                try {
                    result = Translators.translate(source, "auto", target);
                } catch (Throwable t) {
                    Logger.w("translate threw: " + t);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            st.inflight = false;
                        }
                    });
                    return;
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            st.inflight = false;
                            if (result == null || result.isEmpty() || result.equals(source)) {
                                return;
                            }
                            if (!source.equals(st.source)) {
                                return; // view was recycled onto another message
                            }
                            CACHE.put(cacheKey, result);
                            render(view, st, source, result);
                        } catch (Throwable t) {
                            Logger.w("render stage error: " + t);
                        }
                    }
                });
            }
        });
    }

    /** {@code original + "\n" + translation}, translation styled and coloured. */
    private static void render(TextView tv, ViewState st, String source, String translation) {
        if (tv == null) {
            Logger.w("render called without a view; skipping");
            return;
        }
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
        if (st != null) {
            st.rendered = span.toString();
        }
        if (tv == null) {
            Logger.w("render called without a view; skipping setText");
            return;
        }
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
