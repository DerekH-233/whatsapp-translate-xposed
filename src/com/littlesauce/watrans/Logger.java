package com.littlesauce.watrans;

import de.robv.android.xposed.XposedBridge;

/** Thin logging wrapper so verbosity is controlled by a single pref. */
public final class Logger {

    private static final String TAG = "[LSTrans]";

    private Logger() {
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
            safe(TAG + " " + msg);
        }
    }

    /** Errors and HTTP failures are always logged - they are what you need
     *  when a translation silently doesn't show up. */
    public static void w(String msg) {
        safe(TAG + " " + msg);
    }

    private static void safe(String msg) {
        try {
            XposedBridge.log(msg);
        } catch (Throwable ignored) {
            // not running inside the framework (e.g. settings UI)
        }
    }
}
