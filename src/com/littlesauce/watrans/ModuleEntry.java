package com.littlesauce.watrans;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.IXposedHookLoadPackage;

public class ModuleEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[LSTrans]";
    private static final String WA = "com.whatsapp";
    private static final String WA_B = "com.whatsapp.w4b";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!WA.equals(lpparam.packageName) && !WA_B.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + " ===== hooked target: " + lpparam.packageName + " =====");
        try {
            MessageHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " MessageHook install failed: " + t);
            XposedBridge.log(t);
        }
    }
}
