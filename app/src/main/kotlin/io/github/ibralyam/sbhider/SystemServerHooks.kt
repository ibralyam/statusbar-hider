package io.github.ibralyam.sbhider

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * Watchdog auto-hide untuk status bar transient, dipasang di system_server (scope "android").
 *
 * Timer bawaan AOSP (SystemUI AutoHideController, sekitar 3 detik) bisa diubah OEM. Di sini
 * setiap kali InsetsPolicy menampilkan bar transient untuk app target, kita jadwalkan
 * penyembunyian paksa tepat setelah KEY_AUTOHIDE_MS (default 3000 ms).
 *
 * Semua akses internal memakai refleksi dan gagal-aman: bila nama method/field tidak ada di ROM,
 * lapisan ini mati sendiri dan lapisan app-process tetap bekerja.
 */
internal object SystemServerHooks {

    /** Urutan kandidat nama method penyembunyi transient (berbeda antar versi/ROM). */
    private val hideMethodNames = arrayOf("hideTransient", "abortTransient")

    private val worker: Handler by lazy {
        val thread = HandlerThread("sbhider-autohide").also { it.start() }
        Handler(thread.looper)
    }
    private val pending = AtomicReference<Runnable?>(null)
    private val prefs by lazy { XSharedPreferences(Prefs.MODULE_PKG, Prefs.FILE) }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = XposedHelpers.findClassIfExists("com.android.server.wm.InsetsPolicy", lpparam.classLoader)
        if (cls == null) {
            SbLog.i("system: InsetsPolicy tidak ada, watchdog mati")
            return
        }
        val hide = findHideMethod(cls)
        if (hide == null) {
            SbLog.i("system: method hide transient tidak ditemukan (sdk=${Build.VERSION.SDK_INT}), watchdog mati")
            return
        }
        val hooks = XposedBridge.hookAllMethods(
            cls, "showTransient",
            after("InsetsPolicy.showTransient") { p -> onTransientShown(p.thisObject, hide) },
        )
        SbLog.i("system: sdk=${Build.VERSION.SDK_INT} hide=${hide.name} showTransient=${hooks.size}")
    }

    private fun findHideMethod(cls: Class<*>): Method? {
        for (name in hideMethodNames) {
            val m = cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (m != null) {
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    /** Dipanggil di dalam lock WM (showTransient dieksekusi di bawah mGlobalLock). */
    private fun onTransientShown(policy: Any, hide: Method) {
        prefs.reload()
        if (!prefs.getBoolean(Prefs.KEY_SYSTEM_WATCHDOG, true)) return
        val targets = prefs.getStringSet(Prefs.KEY_TARGETS, null)?.toSet() ?: return
        val pkg = focusedPackage(policy) ?: return
        if (pkg !in targets) return
        val lock = lockOf(policy) ?: return
        val delay = try {
            prefs.getLong(Prefs.KEY_AUTOHIDE_MS, Prefs.DEFAULT_AUTOHIDE_MS)
        } catch (_: Throwable) {
            Prefs.DEFAULT_AUTOHIDE_MS
        }.coerceIn(1000L, 10000L)

        val task = Runnable { fire(policy, hide, lock, targets) }
        pending.getAndSet(task)?.let { worker.removeCallbacks(it) }
        worker.postDelayed(task, delay)
    }

    private fun fire(policy: Any, hide: Method, lock: Any, targets: Set<String>) {
        try {
            synchronized(lock) {
                if (!isTransientShowing(policy)) return
                val pkg = focusedPackage(policy)
                if (pkg == null || pkg !in targets) return
                hide.invoke(policy)
            }
        } catch (t: Throwable) {
            SbLog.e("system.fire", t)
        }
    }

    private fun lockOf(policy: Any): Any? = try {
        val dc = XposedHelpers.getObjectField(policy, "mDisplayContent")
        val wms = XposedHelpers.getObjectField(dc, "mWmService")
        XposedHelpers.getObjectField(wms, "mGlobalLock")
    } catch (t: Throwable) {
        SbLog.e("system.lock", t)
        null
    }

    private fun focusedPackage(policy: Any): String? {
        return try {
            val dc = XposedHelpers.getObjectField(policy, "mDisplayContent")
            val ws = XposedHelpers.getObjectField(dc, "mCurrentFocus") ?: return null
            XposedHelpers.callMethod(ws, "getOwningPackage") as? String
        } catch (t: Throwable) {
            SbLog.e("system.focus", t)
            null
        }
    }

    /** mShowingTransientTypes bisa Int (bitmask) atau android.util.IntArray tergantung versi. */
    private fun isTransientShowing(policy: Any): Boolean {
        return try {
            when (val v = XposedHelpers.getObjectField(policy, "mShowingTransientTypes")) {
                null -> true
                is Int -> v != 0
                else -> (XposedHelpers.callMethod(v, "size") as Int) > 0
            }
        } catch (_: Throwable) {
            true
        }
    }
}
