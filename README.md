# StatusBar Guard (modul Vector / LSPosed)

Menyembunyikan status bar per-aplikasi untuk mengurangi burn-in. Status bar hanya muncul
sementara saat swipe dari bezel atas, lalu hilang lagi setelah 3 detik.

Status: **belum dikompilasi dan belum diuji di perangkat.** Semua nama class/method internal AOSP
di bawah ditulis dari pengetahuan saya tentang source AOSP dan dipasang dengan pola gagal-aman.
Verifikasi lewat log Vector (cari tag `[SBGuard]`).

## Cara pakai

1. Buka folder ini di Android Studio (atau jalankan `gradle wrapper` lalu `./gradlew assembleRelease`).
2. Pasang APK. Buka Vector, aktifkan modul **StatusBar Guard**.
3. Scope: centang **System Framework** (untuk watchdog) dan **setiap aplikasi target**.
4. Buka Manager UI modul, centang aplikasi yang mau disembunyikan status bar-nya.
5. Force stop aplikasi target. Pilihan dibaca saat proses aplikasi dimulai.

Catatan repositori: Xposed API 82 diambil dari `https://api.xposed.info/`. Bila gagal diunduh,
letakkan `XposedBridge-api-82.jar` di `app/libs/` dan ganti dependency dengan
`compileOnly(files("libs/XposedBridge-api-82.jar"))`.

## Arsitektur multi-layer

| Lapisan | Titik hook | Fungsi |
|---|---|---|
| L1 app | `ViewRootImpl.setLayoutParams` | Memaksa `FLAG_FULLSCREEN` dan `layoutInDisplayCutoutMode = ALWAYS` pada window utama. Semua jalur (`Window.setFlags`, `setAttributes`, `updateViewLayout`) lewat sini, jadi reset flag per halaman tidak berpengaruh. |
| L2 app | `InsetsController.show` | Membuang bit `statusBars()` dari permintaan show milik app (TikTok, Instagram, browser). Bit nav bar dan IME tetap lewat. |
| L2 app | `InsetsController.setSystemBarsBehavior` | Memaksa `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` supaya bar hasil swipe bersifat transient. |
| L3 app | `Activity.onPostCreate/onResume/onWindowFocusChanged` | `hide(statusBars())` di awal dan ditegakkan ulang. Tidak ada kedip status bar saat app dibuka. |
| L4 app | `View.setSystemUiVisibility` | Jalur lama `SYSTEM_UI_FLAG_*` (WebView, app lama). |
| L5 app | `WindowInsets.getInsets` | Mengisi inset atas dengan `safeInsetTop` notch saat status bar hilang. |
| L6 system_server | `InsetsPolicy.showTransient` | Watchdog: menyembunyikan bar transient tepat `autohide_ms` (default 3000) setelah ditarik, hanya bila app fokus adalah target. |

### Mengapa tahan terhadap perbedaan ROM

- L1 dan L2 memakai class framework umum (`ViewRootImpl`, `InsetsController`) yang berada di
  proses aplikasi. OEM jarang memodifikasi jalur ini, karena Samsung, Xiaomi, OPPO dan Vivo
  memodifikasi SystemUI dan policy di system_server.
- `hookAllMethods` menutup perbedaan signature antar versi (mis. `show(int, boolean)` di API 31-32
  dan `show(int, boolean, ImeTracker.Token)` di API 33+).
- Swipe asli dari bezel dideteksi dan diproses `InsetsPolicy` di system_server. Menurut pembacaan
  saya, jalur itu tidak memanggil `InsetsController.show` dari sisi app, jadi tidak ikut terblokir.
  Ini perlu dikonfirmasi di perangkat.
- Edge-to-edge wajib di Android 15/16 tidak menjadi masalah karena modul memakai API insets, bukan
  opt-out edge-to-edge.
- Bila satu lapisan gagal, lapisan lain tetap aktif. Hasil per lapisan dicatat, contoh:
  `[SBGuard] com.zhiliaoapp.musically sdk=35: +layoutParams +insetsShow +behavior +lifecycle`.

### Catatan per versi

- API 31-32: `InsetsPolicy.mShowingTransientTypes` kemungkinan `IntArray`. Dibaca lewat refleksi, mendukung `int` maupun `IntArray`.
- API 33-34: `show` bertambah parameter `ImeTracker.Token`. Sudah tercakup.
- API 35-36: edge-to-edge dipaksa untuk app target SDK 35+. Padding notch ditangani L1 dan L5.
- API 37: belum menjadi prioritas dan belum ditinjau.

## Edge case

- **Timer 3 detik presisi:** SystemUI biasanya sudah menyembunyikan bar sekitar 3 detik. L6 menjadwalkan
  ulang setiap kali `showTransient` dipanggil dan memakai `HandlerThread` sendiri, bukan main thread
  system_server. Eksekusi hide dilakukan di dalam `mGlobalLock`. Nama method hide dicari lewat
  `hideTransient`, lalu `abortTransient`. Bila tidak ada, L6 mati sendiri dan dicatat di log.
- **Notch / cutout:** L1 memaksa `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` sehingga tidak ada bilah hitam.
- **Padding atas:** L5 hanya mengganti hasil `getInsets(statusBars())` yang bernilai 0 dengan
  `DisplayCutout.safeInsetTop`. Perangkat tanpa notch tidak berubah.
- **Anti-reset:** L1 menimpa flag di setiap perubahan atribut window, L2 memblokir `show(statusBars())`,
  L3 menegakkan ulang saat resume dan fokus.

## Batasan yang diketahui

- Entry point **libxposed modern (API 100)** belum disertakan. Vector menjalankan modul API legacy 82
  secara native, jadi satu entry `assets/xposed_init` cukup. Saya tidak menulis entry modern karena
  tidak bisa memverifikasi signature API-nya tanpa akses ke library.
- Hook system_server (L6) bergantung pada nama internal `InsetsPolicy`, `mDisplayContent`,
  `mCurrentFocus`, `mGlobalLock`. Cek log setelah reboot.
- Memaksa `FLAG_FULLSCREEN` pada app lama non-edge-to-edge membuat konten ikut memakai area status bar.
- Pilihan aplikasi dibaca saat proses dimulai. Perubahan butuh force stop.
- `MODE_WORLD_READABLE` hanya berhasil bila modul aktif di Vector. Bila tidak, Manager UI menampilkan peringatan.
