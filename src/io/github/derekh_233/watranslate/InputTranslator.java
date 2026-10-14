package io.github.derekh_233.watranslate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Translates what you typed, on demand, from inside the chat box.
 *
 * <p>Deliberately adds no view of its own. WhatsApp already gives the send
 * button two states of meaning - tap sends, hold does nothing - so the hold is
 * borrowed: long-press it to turn the draft into the target language, long-press
 * again to get your own words back. Nothing is drawn, moved or tinted, so the
 * composer keeps looking exactly the way WhatsApp drew it.</p>
 *
 * <p>Both the box and the button are located by <b>resource entry name</b>
 * rather than by hard-coded id, using each WhatsApp build's own resource table.
 * Numeric ids are not stable across releases; names have been.</p>
 *
 * <p>Safety rules, in rough order of how much they matter:</p>
 * <ul>
 *   <li>the user's own words are never lost - the original draft is kept and a
 *       second long-press puts it back;</li>
 *   <li>if the box changed while a translation was in flight, the result is
 *       dropped rather than overwriting what the user has since typed;</li>
 *   <li>when no chat box can be found the long-press is passed straight through,
 *       so every other long-press in WhatsApp behaves as before.</li>
 * </ul>
 */
public final class InputTranslator {

    private static final String TAG = "LSTrans";

    /** Resource entry names that denote the message input box. */
    private static final String[] INPUT_NAMES = {"text_entry", "entry"};

    /** Resource entry names that denote the composer's send button. */
    private static final String[] SEND_NAMES = {"send", "send_btn", "send_button"};

    private static final String MSG_WORKING = "翻译中…";
    private static final String MSG_DONE = "已翻译，长按发送键可还原";
    private static final String MSG_RESTORED = "已还原你的原文";
    private static final String MSG_FAILED = "翻译失败，原文已保留";

    /** How far up, then down, we are willing to look for the input box. */
    private static final int MAX_UP = 8;
    private static final int MAX_DOWN = 4;
    private static final int MAX_VISITS = 600;

    /** Long-presses we will describe in the log before going quiet. */
    private static final int LOG_BUDGET = 30;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Map<EditText, Draft> DRAFTS =
            Collections.synchronizedMap(new WeakHashMap<EditText, Draft>());

    private static final AtomicInteger LOGGED = new AtomicInteger();

    private static volatile boolean installed;

    private InputTranslator() {
    }

    /** What the user typed, and what we replaced it with. */
    private static final class Draft {
        String original;
        String translated;
        boolean inflight;
    }

    // ------------------------------------------------------------- install

    public static synchronized void install(XposedModule module, String pkg) {
        if (installed) {
            return;
        }
        Method target;
        try {
            target = View.class.getDeclaredMethod("performLongClick");
        } catch (Throwable t) {
            Logger.w("View.performLongClick not found: " + t);
            return;
        }
        installed = true;

        XposedInterface.Hooker hooker = new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof View && handleLongPress((View) self)) {
                        // Swallow the press: we acted on it.
                        return Boolean.TRUE;
                    }
                } catch (Throwable t) {
                    Logger.w("long-press handling failed: " + t);
                }
                return chain.proceed();
            }
        };

        try {
            module.hook(target)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            Logger.w("input translation installed on " + pkg);
        } catch (Throwable t) {
            Logger.w("hook performLongClick failed: " + t);
        }
    }

    // ---------------------------------------------------------- long press

    /** @return true when the press was ours and should not reach WhatsApp. */
    private static boolean handleLongPress(View pressed) {
        if (!Prefs.inputTranslateEnabled()) {
            return false;
        }
        if (!isSendButton(pressed)) {
            describe(pressed);
            return false;
        }
        final EditText box = findInput(pressed);
        if (box == null) {
            Logger.w("send button held but no input box found nearby");
            return false;
        }
        CharSequence cs = box.getText();
        if (TextUtils.isEmpty(cs)) {
            return false;
        }
        final String current = cs.toString();
        if (current.trim().isEmpty()) {
            return false;
        }

        // Second hold on an untouched translation gives the user their words back.
        Draft existing = DRAFTS.get(box);
        if (existing != null && existing.translated != null
                && existing.translated.equals(current)) {
            DRAFTS.remove(box);
            box.setText(existing.original);
            box.setSelection(box.getText().length());
            toast(box, MSG_RESTORED);
            return true;
        }

        Draft d = existing;
        if (d == null) {
            d = new Draft();
            DRAFTS.put(box, d);
        }
        if (d.inflight) {
            return true;
        }
        d.original = current;
        d.inflight = true;

        final Draft draft = d;
        final String target = resolveTarget();
        toast(box, MSG_WORKING);

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                String result = null;
                try {
                    result = Translators.translate(current, "auto", target);
                } catch (Throwable t) {
                    Logger.w("input translate threw: " + t);
                }
                final String out = result;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        draft.inflight = false;
                        CharSequence now = box.getText();
                        if (now == null || !current.equals(now.toString())) {
                            // Typed over while we were working - never clobber it.
                            draft.original = null;
                            return;
                        }
                        if (out == null || out.isEmpty() || out.equals(current)) {
                            draft.original = null;
                            toast(box, MSG_FAILED);
                            return;
                        }
                        draft.translated = out;
                        box.setText(out);
                        box.setSelection(box.getText().length());
                        toast(box, doneMessage(target));
                    }
                });
            }
        });
        return true;
    }

    /**
     * Which language to aim the draft at.
     *
     * <p>Follows the person being written to when that can be worked out, so
     * talking to several countries needs no switching at all. Falls back to the
     * configured language for groups, for the number-hiding {@code @lid}
     * identifiers, and whenever the dialling code is not in the table.</p>
     */
    private static String resolveTarget() {
        if (Prefs.followPeer()) {
            String peerLanguage = ChatContext.peerLanguage();
            if (peerLanguage != null && !peerLanguage.isEmpty()) {
                return peerLanguage;
            }
        }
        return Prefs.outLang();
    }

    /** Success message, naming the peer when one was resolved. */
    private static String doneMessage(String target) {
        String label = LocaleInfo.labelOf(target);
        String peer = ChatContext.peerDescription();
        if (peer == null) {
            return "已翻译成" + label + "，长按发送键可还原";
        }
        return "已翻译成" + label + "（对方 " + peer + "），长按可还原";
    }

    // ------------------------------------------------------------ view ids

    private static boolean isSendButton(View v) {
        return matches(v, SEND_NAMES);
    }

    private static boolean matches(View v, String[] names) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return false;
        }
        String entry = entryName(v, id);
        if (entry == null) {
            return false;
        }
        for (String n : names) {
            if (n.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    private static String entryName(View v, int id) {
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The box sits beside the button, not under it, so walk a few parents up and
     * search the subtree at each step. Bounded, because this runs on the UI
     * thread inside a long-press.
     */
    private static EditText findInput(View anchor) {
        int[] budget = {MAX_VISITS};
        View node = anchor;
        for (int up = 0; up < MAX_UP && node != null; up++) {
            EditText found = search(node, 0, budget);
            if (found != null) {
                return found;
            }
            ViewParent parent = node.getParent();
            node = (parent instanceof View) ? (View) parent : null;
        }
        return null;
    }

    private static EditText search(View node, int depth, int[] budget) {
        if (budget[0]-- <= 0) {
            return null;
        }
        if (node instanceof EditText && matches(node, INPUT_NAMES)) {
            return (EditText) node;
        }
        if (depth >= MAX_DOWN || !(node instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) node;
        for (int i = 0; i < group.getChildCount(); i++) {
            EditText found = search(group.getChildAt(i), depth + 1, budget);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- helpers

    /**
     * Record what gets held down for the first few presses of a process. If a
     * future WhatsApp renames the send button this says so, instead of leaving
     * the feature silently dead.
     */
    private static void describe(View v) {
        if (LOGGED.get() >= LOG_BUDGET || !Prefs.debugLog()) {
            return;
        }
        int id = v.getId();
        String entry = id == View.NO_ID ? "(no id)" : entryName(v, id);
        if (entry == null) {
            return;
        }
        LOGGED.incrementAndGet();
        Logger.d("long-press on " + v.getClass().getSimpleName() + " id=" + entry);
    }

    private static void toast(final View anchor, final String text) {
        try {
            Context ctx = anchor.getContext();
            if (ctx == null) {
                return;
            }
            Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Logger.d("toast skipped: " + t);
        }
    }
}
