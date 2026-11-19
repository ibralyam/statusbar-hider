package io.github.ibralyam.sbhider

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

internal object SbLog {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun i(msg: String) = XposedBridge.log("[SBHider] $msg")

    /** Log error sekali per tag agar logcat tidak banjir. */
    fun e(tag: String, t: Throwable) {
        if (seen.add(tag)) XposedBridge.log("[SBHider] $tag gagal: $t")
    }
}

/** Hook "before" yang tidak pernah melempar exception ke aplikasi target. */
internal inline fun before(
    tag: String,
    crossinline block: (XC_MethodHook.MethodHookParam) -> Unit,
): XC_MethodHook = object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        try {
            block(param)
        } catch (t: Throwable) {
            SbLog.e(tag, t)
        }
    }
}

/** Hook "after" yang tidak pernah melempar exception ke aplikasi target. */
internal inline fun after(
    tag: String,
    crossinline block: (XC_MethodHook.MethodHookParam) -> Unit,
): XC_MethodHook = object : XC_MethodHook() {
    override fun afterHookedMethod(param: MethodHookParam) {
        try {
            block(param)
        } catch (t: Throwable) {
            SbLog.e(tag, t)
        }
    }
}
