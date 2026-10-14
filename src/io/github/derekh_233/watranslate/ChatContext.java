package io.github.derekh_233.watranslate;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Knows which conversation is on screen.
 *
 * <p>Exists so the outgoing language can follow the person being written to
 * instead of being re-picked by hand for every country. The conversation screen
 * receives its target as an intent extra, so the jid is read once per screen and
 * turned into a country, language and local time through the offline table.</p>
 *
 * <p>Nothing here is written back: the hooked side of the framework is
 * read-only, and a value derived on the spot needs no storage anyway.</p>
 */
public final class ChatContext {

    /** Extras we describe in the log, then stop, so the log stays readable. */
    private static final int LOG_BUDGET = 6;

    private static volatile String chatJid;
    private static volatile LocaleInfo.Info peer;
    private static volatile boolean installed;
    private static volatile int logged;

    private ChatContext() {
    }

    public static synchronized void install(XposedModule module, ClassLoader loader) {
        if (installed) {
            return;
        }
        Method target;
        try {
            target = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        } catch (Throwable t) {
            Logger.w("Activity.onCreate not found: " + t);
            return;
        }
        installed = true;

        try {
            module.hook(target)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof Activity) {
                                    capture((Activity) self);
                                }
                            } catch (Throwable t) {
                                Logger.d("chat capture skipped: " + t);
                            }
                            return chain.proceed();
                        }
                    });
            Logger.w("conversation tracking installed");
        } catch (Throwable t) {
            Logger.w("hook Activity.onCreate failed: " + t);
        }
    }

    private static void capture(Activity activity) {
        String cls = activity.getClass().getName();
        if (cls == null || !cls.contains("Conversation")) {
            return;
        }
        Intent intent = activity.getIntent();
        Bundle extras = intent == null ? null : intent.getExtras();
        if (extras == null) {
            if (logged < LOG_BUDGET) {
                logged++;
                Logger.d("conversation screen opened with no extras: " + cls);
            }
            return;
        }
        for (String key : extras.keySet()) {
            Object value;
            try {
                value = extras.get(key);
            } catch (Throwable t) {
                continue;
            }
            if (!(value instanceof String)) {
                continue;
            }
            String s = (String) value;
            if (s.endsWith("@s.whatsapp.net")) {
                adopt(s);
                if (logged < LOG_BUDGET) {
                    logged++;
                    Logger.d("conversation identified via extra <" + key + ">");
                }
                return;
            }
        }
        // Nothing looked like a jid. Say what was there, so a WhatsApp release
        // that renames or reshapes the extra can be dealt with from the log
        // instead of guesswork.
        if (logged < LOG_BUDGET) {
            logged++;
            StringBuilder sb = new StringBuilder("conversation extras without a jid: ");
            for (String key : extras.keySet()) {
                Object v;
                try {
                    v = extras.get(key);
                } catch (Throwable t) {
                    continue;
                }
                sb.append(key).append('=')
                        .append(v == null ? "null" : String.valueOf(v));
                if (sb.length() > 400) {
                    break;
                }
                sb.append("; ");
            }
            Logger.d(sb.toString());
        }
    }

    private static void adopt(String jid) {
        chatJid = jid;
        // "number:device@s.whatsapp.net" - drop the device suffix.
        String user = jid.substring(0, jid.indexOf('@'));
        int colon = user.indexOf(':');
        if (colon > 0) {
            user = user.substring(0, colon);
        }
        // Groups have no single country, and @lid is not a phone number, so
        // those simply leave the peer unknown and the setting falls back.
        peer = LocaleInfo.forPhone(user);
        if (peer != null) {
            Logger.d("peer resolved: " + LocaleInfo.describe(peer));
        }
    }

    /** The peer's language code for the conversation on screen, or null. */
    public static String peerLanguage() {
        LocaleInfo.Info p = peer;
        if (p == null || p.langCode == null || p.langCode.isEmpty()) {
            return null;
        }
        return p.langCode;
    }

    /** A one-line description of the peer, or null when unknown. */
    public static String peerDescription() {
        return LocaleInfo.describe(peer);
    }

    /** True when the conversation on screen is a 1:1 chat we resolved. */
    public static boolean hasPeer() {
        return peer != null;
    }

    static String rawJid() {
        return chatJid;
    }
}
