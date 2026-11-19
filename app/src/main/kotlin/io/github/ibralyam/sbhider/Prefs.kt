package io.github.ibralyam.sbhider

/** Konstanta bersama antara Manager UI (menulis) dan hook (membaca via XSharedPreferences). */
object Prefs {
    const val MODULE_PKG = "io.github.ibralyam.sbhider"
    const val FILE = "config"

    /** Set<String> nama paket target. */
    const val KEY_TARGETS = "targets"

    /** Boolean: pertahankan padding atas setara tinggi notch saat status bar tersembunyi. */
    const val KEY_KEEP_CUTOUT_PADDING = "keep_cutout_padding"

    /** Boolean: aktifkan watchdog auto-hide 3 detik di system_server. */
    const val KEY_SYSTEM_WATCHDOG = "system_watchdog"

    /** Long (ms): durasi sebelum status bar transient disembunyikan paksa. */
    const val KEY_AUTOHIDE_MS = "autohide_ms"
    const val DEFAULT_AUTOHIDE_MS = 3000L
}

/** Dihook oleh modul agar Manager UI tahu modul sedang aktif. */
object ModuleState {
    @JvmStatic
    fun isActive(): Boolean = false
}
