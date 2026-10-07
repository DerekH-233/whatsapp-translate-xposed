package com.littlesauce.watrans;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Module-process half of the settings bridge.
 *
 * <p>The settings UI writes to the ordinary local preferences; this class
 * mirrors every write into the framework's remote preferences through
 * {@link XposedService}, which is what the hooked WhatsApp process reads.
 * It replaces the legacy {@code xposedsharedprefs} world-readable file
 * workaround.</p>
 *
 * <p>If the framework never hands us a service (module not activated) the UI
 * still works - settings simply do not reach anything.</p>
 */
public final class PrefsBridge {

    private static final String TAG = "LSTrans";

    private static volatile SharedPreferences remote;
    private static volatile boolean listenerRegistered;

    private PrefsBridge() {
    }

    /** Idempotent; call from the settings Activity before the first write. */
    public static synchronized void start(final Context ctx) {
        if (listenerRegistered) {
            return;
        }
        listenerRegistered = true;
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    try {
                        remote = service.getRemotePreferences(Prefs.NAME);
                        Log.i(TAG, "framework service bound; remote preferences ready");
                        // Seed the framework with whatever is already configured,
                        // so a fresh install propagates its defaults too.
                        pushAll(ctx);
                    } catch (Throwable t) {
                        remote = null;
                        Log.w(TAG, "getRemotePreferences failed", t);
                    }
                }

                @Override
                public void onServiceDied(XposedService service) {
                    remote = null;
                    Log.i(TAG, "framework service died");
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "registerListener failed", t);
        }
    }

    /** True once the framework has handed us its preference store. */
    public static boolean ready() {
        return remote != null;
    }

    /** Mirror a single edit; safe to call when the framework is absent. */
    public static void push(Map<String, ?> values) {
        SharedPreferences target = remote;
        if (target == null || values == null || values.isEmpty()) {
            return;
        }
        try {
            SharedPreferences.Editor ed = target.edit();
            for (Map.Entry<String, ?> e : values.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (v instanceof Integer) {
                    ed.putInt(k, (Integer) v);
                } else if (v instanceof Boolean) {
                    ed.putBoolean(k, (Boolean) v);
                } else if (v instanceof Float) {
                    ed.putFloat(k, (Float) v);
                } else {
                    ed.putString(k, String.valueOf(v));
                }
            }
            ed.commit();
        } catch (Throwable t) {
            Log.w(TAG, "push failed", t);
        }
    }

    private static void pushAll(Context ctx) {
        SharedPreferences local = Prefs.get(ctx);
        if (local == null) {
            return;
        }
        push(local.getAll());
    }
}
