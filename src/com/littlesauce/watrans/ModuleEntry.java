package com.littlesauce.watrans;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Module entry point for the modern (libxposed) API.
 *
 * <p>Registered through {@code META-INF/xposed/java_init.list}; the framework
 * instantiates this class in every process of the scoped packages and calls the
 * lifecycle callbacks below. Nothing here may touch the legacy
 * {@code de.robv.android.xposed} API - targeting API 102 forbids it.</p>
 */
public class ModuleEntry extends XposedModule {

    private static final String TAG = "LSTrans";

    private static final String WA = "com.whatsapp";
    private static final String WA_B = "com.whatsapp.w4b";

    private static volatile ModuleEntry instance;

    static ModuleEntry get() {
        return instance;
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        instance = this;
        Logger.attach(this);
        Prefs.attach(this);
        log(Log.INFO, TAG, "loaded into process " + param.getProcessName()
                + " (api " + getApiVersion() + ")");
    }

    /**
     * Called once the package classloader exists. {@code onPackageReady} is the
     * API-29+ callback; {@code onPackageLoaded} covers older releases, so both
     * are wired and the install itself is idempotent.
     */
    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        install(param.getPackageName(), param.getClassLoader());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        install(param.getPackageName(), param.getDefaultClassLoader());
    }

    private void install(String pkg, ClassLoader loader) {
        if (!WA.equals(pkg) && !WA_B.equals(pkg)) {
            return;
        }
        if (loader == null) {
            return;
        }
        try {
            MessageHook.install(this, loader, pkg);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "MessageHook install failed", t);
        }
    }
}
