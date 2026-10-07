package de.robv.android.xposed;

/** Compile-time stub of the Xposed API hook callback. See XposedHelpers. */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object getResult() {
            throw new UnsupportedOperationException("stub");
        }
    }

    public static final class Unhook {
    }
}
