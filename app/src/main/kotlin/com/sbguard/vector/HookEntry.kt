package com.sbguard.vector

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            when (lpparam.packageName) {
                Prefs.MODULE_PKG -> hookSelf(lpparam)
                "android" -> if (lpparam.processName == "android") SystemServerHooks.install(lpparam)
                else -> AppProcessHooks.install(lpparam)
            }
        } catch (t: Throwable) {
            SbLog.e("entry:${lpparam.packageName}", t)
        }
    }

    private fun hookSelf(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            ModuleState::class.java.name,
            lpparam.classLoader,
            "isActive",
            XC_MethodReplacement.returnConstant(true),
        )
    }
}
