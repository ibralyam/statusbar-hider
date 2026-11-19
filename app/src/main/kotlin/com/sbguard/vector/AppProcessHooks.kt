package com.sbguard.vector

import android.app.Activity
import android.graphics.Insets
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook di proses aplikasi target (Android 12 / API 31 sampai 16 / API 36).
 *
 * Setiap lapisan dipasang terpisah. Kegagalan satu lapisan (mis. method disembunyikan OEM)
 * tidak membatalkan lapisan lain, dan hasilnya dicatat di log Vector.
 */
internal object AppProcessHooks {

    private val STATUS = WindowInsets.Type.statusBars()
    private const val BEHAVIOR_TRANSIENT = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    private const val CUTOUT_ALWAYS = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    private const val FLAG_FULLSCREEN = WindowManager.LayoutParams.FLAG_FULLSCREEN
    private const val TYPE_BASE_APPLICATION = WindowManager.LayoutParams.TYPE_BASE_APPLICATION

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val prefs = XSharedPreferences(Prefs.MODULE_PKG, Prefs.FILE).apply { reload() }
        val targets = prefs.getStringSet(Prefs.KEY_TARGETS, null) ?: return
        if (lpparam.packageName !in targets) return

        val keepCutoutPadding = prefs.getBoolean(Prefs.KEY_KEEP_CUTOUT_PADDING, true)
        val report = StringBuilder()

        fun step(name: String, block: () -> Unit) {
            try {
                block()
                report.append(" +").append(name)
            } catch (t: Throwable) {
                report.append(" -").append(name)
                SbLog.e("$name@${lpparam.packageName}", t)
            }
        }

        step("layoutParams") { hookLayoutParams() }
        step("insetsShow") { hookInsetsControllerShow() }
        step("behavior") { hookInsetsControllerBehavior() }
        step("lifecycle") { hookActivityLifecycle() }
        step("legacyFlags") { hookLegacySystemUiVisibility() }
        if (keepCutoutPadding) step("cutoutPadding") { hookCutoutPadding() }

        SbLog.i("${lpparam.packageName} sdk=${Build.VERSION.SDK_INT}:$report")
    }

    /**
     * Titik tunggal tempat semua perubahan atribut window lewat sebelum dikirim ke system_server
     * (Window.setFlags/setAttributes -> WindowManager.updateViewLayout -> ViewRootImpl.setLayoutParams).
     * Memaksa FLAG_FULLSCREEN dan cutout mode ALWAYS di sini mencegah reset flag per halaman
     * dan mencegah bilah hitam di area notch.
     */
    private fun hookLayoutParams() {
        val vri = XposedHelpers.findClass("android.view.ViewRootImpl", XposedBridge.BOOTCLASSLOADER)
        val hooks = XposedBridge.hookAllMethods(
            vri, "setLayoutParams",
            before("ViewRootImpl.setLayoutParams") { p ->
                val lp = p.args.getOrNull(0) as? WindowManager.LayoutParams ?: return@before
                if (lp.type != TYPE_BASE_APPLICATION) return@before
                lp.flags = lp.flags or FLAG_FULLSCREEN
                lp.layoutInDisplayCutoutMode = CUTOUT_ALWAYS
            },
        )
        check(hooks.isNotEmpty()) { "ViewRootImpl.setLayoutParams tidak ditemukan" }
    }

    /**
     * Blokir permintaan app untuk menampilkan status bar (show(statusBars())).
     * Bit navigationBars/ime tetap diteruskan. Swipe dari bezel atas ditangani system_server
     * dan tidak memanggil show() dari sisi app, jadi gestur asli tetap berjalan.
     * Catatan: signature berbeda per versi (API 31-32: show(int, boolean),
     * API 33+: show(int, boolean, ImeTracker.Token)); hookAllMethods menutup semuanya.
     */
    private fun hookInsetsControllerShow() {
        val ic = XposedHelpers.findClass("android.view.InsetsController", XposedBridge.BOOTCLASSLOADER)
        val hooks = XposedBridge.hookAllMethods(
            ic, "show",
            before("InsetsController.show") { p ->
                val types = p.args.getOrNull(0) as? Int ?: return@before
                if (types and STATUS == 0) return@before
                val rest = types and STATUS.inv()
                if (rest == 0) p.result = null else p.args[0] = rest
            },
        )
        check(hooks.isNotEmpty()) { "InsetsController.show tidak ditemukan" }
    }

    /** Paksa behavior transient-by-swipe agar status bar yang ditarik tidak menetap. */
    private fun hookInsetsControllerBehavior() {
        val ic = XposedHelpers.findClass("android.view.InsetsController", XposedBridge.BOOTCLASSLOADER)
        val hooks = XposedBridge.hookAllMethods(
            ic, "setSystemBarsBehavior",
            before("InsetsController.setSystemBarsBehavior") { p -> p.args[0] = BEHAVIOR_TRANSIENT },
        )
        check(hooks.isNotEmpty()) { "setSystemBarsBehavior tidak ditemukan" }
    }

    private fun enforce(activity: Activity) {
        try {
            val controller = activity.window?.insetsController ?: return
            controller.systemBarsBehavior = BEHAVIOR_TRANSIENT
            controller.hide(STATUS)
        } catch (t: Throwable) {
            SbLog.e("enforce", t)
        }
    }

    /** Sembunyikan di awal dan tegakkan ulang setiap resume / fokus kembali. */
    private fun hookActivityLifecycle() {
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onPostCreate", Bundle::class.java,
            after("Activity.onPostCreate") { p -> enforce(p.thisObject as Activity) },
        )
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onResume",
            after("Activity.onResume") { p -> enforce(p.thisObject as Activity) },
        )
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onWindowFocusChanged", Boolean::class.javaPrimitiveType,
            after("Activity.onWindowFocusChanged") { p ->
                if (p.args[0] == true) enforce(p.thisObject as Activity)
            },
        )
    }

    /** Jalur lama (SYSTEM_UI_FLAG_*) yang masih dipakai banyak aplikasi dan WebView. */
    @Suppress("DEPRECATION")
    private fun hookLegacySystemUiVisibility() {
        XposedHelpers.findAndHookMethod(
            View::class.java, "setSystemUiVisibility", Int::class.javaPrimitiveType,
            before("View.setSystemUiVisibility") { p ->
                p.args[0] = (p.args[0] as Int) or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            },
        )
    }

    /**
     * Saat status bar tersembunyi, getInsets(statusBars()) bernilai 0 sehingga app yang hanya
     * membaca inset status bar menaruh UI di bawah lubang kamera. Isi top dengan safeInsetTop
     * dari DisplayCutout. Perangkat tanpa notch tidak terpengaruh (safeInsetTop = 0).
     */
    private fun hookCutoutPadding() {
        XposedHelpers.findAndHookMethod(
            WindowInsets::class.java, "getInsets", Int::class.javaPrimitiveType,
            after("WindowInsets.getInsets") { p ->
                val mask = p.args[0] as Int
                if (mask and STATUS == 0) return@after
                if (mask and WindowInsets.Type.displayCutout() != 0) return@after
                val current = p.result as? Insets ?: return@after
                if (current.top != 0) return@after
                val cutoutTop = (p.thisObject as WindowInsets).displayCutout?.safeInsetTop ?: 0
                if (cutoutTop > 0) {
                    p.result = Insets.of(current.left, cutoutTop, current.right, current.bottom)
                }
            },
        )
    }
}
