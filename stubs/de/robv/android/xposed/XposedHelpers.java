package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-time stub of the Xposed API.
 *
 * At runtime the Xposed / LSPosed framework injects the real
 * de.robv.android.xposed.* classes into every hooked process *ahead of*
 * the module's own dex, so these stubs are never actually executed.
 * They exist only so the module can be compiled without pulling the
 * official Xposed API jar.
 */
public final class XposedHelpers {

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        throw new UnsupportedOperationException("stub");
    }
}
