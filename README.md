# StatusBar Hider (Vector / LSPosed module)

Hides the status bar per app to help reduce burn-in. The status bar only shows for a moment when you swipe from the top edge, then hides again after 3 seconds.

Supports **Android 12–16** based on my tests.

Status: **I'm only testing this on an AOSP and ONEUI ROM.** All internal AOSP class and method names below come from what I know about AOSP source code and use a fail-safe setup.
Check Vector logs to verify (look for the `[SBHider]` tag).

## How to use

1. Open this folder in Android Studio (or run the gradle wrapper, then `./gradlew assembleRelease`).
2. Install the APK. Open Vector and turn on the **StatusBar Hider** module.
3. Scope: check **System Framework** (for the watchdog) and **each target app**. In Vector, **Select All** apps is recommended so you do not have to keep switching between the StatusBar Hider app and Vector just to hook new apps.
4. Open **StatusBar Hider** and check the apps where you want to hide the status bar.
5. Force stop the target app. App choices are read when the app process starts.

Repo note: Xposed API 82 comes from `https://api.xposed.info/`. If download fails, put `XposedBridge-api-82.jar` in `app/libs/` and change the dependency to `compileOnly(files("libs/XposedBridge-api-82.jar"))`.

## Preview

<!-- Drag and drop screenshots here, or paste the uploaded image links below -->
<img width="280" alt="photo_6339085706008924909_w" src="https://github.com/user-attachments/assets/e231fb40-edc0-4349-8ed7-5ee2bfce3329" />
<img width="280" alt="Status bar hidden in app" src="https://github.com/user-attachments/assets/41169ca4-66f1-4735-88f6-7902fc446597" />



## Multi-layer design

| Layer | Hook point | What it does |
|---|---|---|
| L1 app | `ViewRootImpl.setLayoutParams` | Forces `FLAG_FULLSCREEN` and `layoutInDisplayCutoutMode = ALWAYS` on the main window. Paths like `Window.setFlags`, `setAttributes`, and `updateViewLayout` go through here, so per-screen flag resets do not stick. |
| L2 app | `InsetsController.show` | Removes the `statusBars()` bit from app show calls (TikTok, Instagram, browsers). Nav bar and IME bits still go through. |
| L2 app | `InsetsController.setSystemBarsBehavior` | Forces `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` so bars shown by a swipe act as transient bars. |
| L3 app | `Activity.onPostCreate/onResume/onWindowFocusChanged` | Calls `hide(statusBars())` early and re-applies it. Helps stop status bar flicker when opening an app. |
| L4 app | `View.setSystemUiVisibility` | Handles old `SYSTEM_UI_FLAG_*` calls (WebView, older apps). |
| L5 app | `WindowInsets.getInsets` | Fills the top inset with notch `safeInsetTop` when the status bar is hidden. |
| L6 system_server | `InsetsPolicy.showTransient` | Watchdog: hides transient bars after `autohide_ms` (default 3000 ms) from pull-down, only if the focused app is in your target list. |

### Why it holds up across different ROMs

- L1 and L2 use common framework classes (`ViewRootImpl`, `InsetsController`) inside the app process. OEMs rarely change these paths, since Samsung, Xiaomi, OPPO, and Vivo mostly change SystemUI and policy inside system_server.
- `hookAllMethods` covers signature differences across versions (for example `show(int, boolean)` on API 31–32 and `show(int, boolean, ImeTracker.Token)` on API 33+).
- Real swipes from the top edge are caught and handled by `InsetsPolicy` in system_server. Based on AOSP source code, that path does not call `InsetsController.show` on the app side, so it should not get blocked. This still needs a real device check.
- Mandatory edge-to-edge on Android 15/16 is not an issue because this module uses insets APIs instead of opting out of edge-to-edge.
- If one layer fails, other layers can still work. Per-layer results are logged, for example: `[SBHider] com.zhiliaoapp.musically sdk=35: +layoutParams +insetsShow +behavior +lifecycle`.

### Notes by Android version

- API 31–32: `InsetsPolicy.mShowingTransientTypes` may be an `IntArray`. Read via reflection, supporting both `int` and `IntArray`.
- API 33–34: `show` adds an `ImeTracker.Token` parameter. Already handled.
- API 35–36: edge-to-edge is forced for apps targeting SDK 35+. Top notch spacing is handled by L1 and L5.
- API 37: not a priority yet and not reviewed.

## Edge cases

- **3-second timer accuracy:** SystemUI often hides bars on its own in about 3 seconds. L6 reschedules on every `showTransient` call and uses its own `HandlerThread`, not the main system_server thread. Hiding runs inside `mGlobalLock`. It looks for `hideTransient` first, then `abortTransient`. If neither exists, L6 turns itself off and logs it.
- **Notch / cutout:** L1 forces `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` to avoid black bars.
- **Top padding:** L5 only replaces a `0` from `getInsets(statusBars())` with `DisplayCutout.safeInsetTop`. Phones with no notch stay as-is.
- **Anti-reset:** L1 re-applies flags on window attribute changes, L2 blocks `show(statusBars())`, and L3 re-applies on resume and focus.

## Known limits

- **Modern libxposed (API 100)** entry point is not included yet. Vector runs legacy API 82 modules natively, so one `assets/xposed_init` entry is enough. I skipped the modern entry because I could not verify its API signatures without the library.
- The system_server hook (L6) relies on internal names like `InsetsPolicy`, `mDisplayContent`, `mCurrentFocus`, and `mGlobalLock`. Check logs after reboot.
- Forcing `FLAG_FULLSCREEN` on older non-edge-to-edge apps makes content use the status bar area too.
- App selection is read when the process starts. Changes need a force stop.
- `MODE_WORLD_READABLE` only works when the module is active in Vector. Otherwise, the Manager UI shows a warning.
