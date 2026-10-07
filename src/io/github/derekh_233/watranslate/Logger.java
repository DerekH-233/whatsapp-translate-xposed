package io.github.derekh_233.watranslate;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * Thin logging wrapper so verbosity is controlled by a single pref.
 *
 * <p>Inside a hooked process the messages go to the Xposed log through the
 * modern {@link XposedModule#log} API. In the module's own process (settings
 * UI, data-table loading) they fall back to logcat, since no framework
 * interface is attached there.</p>
 */
public final class Logger {

    private static final String TAG = "LSTrans";

    private static volatile XposedModule module;

    private Logger() {
    }

    static void attach(XposedModule m) {
        module = m;
    }

    public static boolean on() {
        try {
            return Prefs.debugLog();
        } catch (Throwable t) {
            return true;
        }
    }

    public static void d(String msg) {
        if (on()) {
            emit(Log.DEBUG, msg);
        }
    }

    /** Errors and HTTP failures are always logged - they are what you need
     *  when a translation silently doesn't show up. */
    public static void w(String msg) {
        emit(Log.WARN, msg);
    }

    private static void emit(int priority, String msg) {
        XposedModule m = module;
        try {
            if (m != null) {
                m.log(priority, TAG, msg);
            } else {
                Log.println(priority, TAG, msg);
            }
        } catch (Throwable ignored) {
            // never let logging break the hook
        }
    }
}
