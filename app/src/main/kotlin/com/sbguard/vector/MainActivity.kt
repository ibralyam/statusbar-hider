package com.sbguard.vector

import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

/** Manager UI: pilih aplikasi yang status bar-nya disembunyikan. */
class MainActivity : Activity() {

    private class AppItem(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var prefs: SharedPreferences
    private val allApps = ArrayList<AppItem>()
    private val shown = ArrayList<AppItem>()
    private val selected = LinkedHashSet<String>()
    private var query = ""
    private var prefsReadable = true
    private lateinit var adapter: AppAdapter

    @Suppress("DEPRECATION", "WorldReadableFiles")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MODE_WORLD_READABLE hanya berhasil bila modul aktif di Vector (XSharedPreferences baru).
        prefs = try {
            getSharedPreferences(Prefs.FILE, MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            prefsReadable = false
            getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        }
        selected.addAll(prefs.getStringSet(Prefs.KEY_TARGETS, emptySet()) ?: emptySet())

        adapter = AppAdapter()
        setContentView(buildUi())
        loadApps()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val status = TextView(this).apply {
            textSize = 14f
            text = when {
                !ModuleState.isActive() -> "Modul belum aktif. Aktifkan di Vector lalu pilih scope aplikasi."
                !prefsReadable -> "Modul aktif, tetapi pengaturan tidak bisa dibaca hook."
                else -> "Modul aktif. Force stop aplikasi setelah mengubah pilihan."
            }
        }
        root.addView(status)

        root.addView(
            Switch(this).apply {
                text = "Pertahankan padding notch"
                isChecked = prefs.getBoolean(Prefs.KEY_KEEP_CUTOUT_PADDING, true)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean(Prefs.KEY_KEEP_CUTOUT_PADDING, on).apply()
                }
            },
        )
        root.addView(
            Switch(this).apply {
                text = "Watchdog auto-hide 3 detik (system_server)"
                isChecked = prefs.getBoolean(Prefs.KEY_SYSTEM_WATCHDOG, true)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean(Prefs.KEY_SYSTEM_WATCHDOG, on).apply()
                }
            },
        )

        root.addView(
            EditText(this).apply {
                hint = "Cari aplikasi"
                setSingleLine()
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString()?.trim().orEmpty()
                        applyFilter()
                    }
                })
            },
        )

        root.addView(
            ListView(this).apply {
                adapter = this@MainActivity.adapter
                setOnItemClickListener { _, _, position, _ -> toggle(shown[position].pkg) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun toggle(pkg: String) {
        if (!selected.add(pkg)) selected.remove(pkg)
        prefs.edit().putStringSet(Prefs.KEY_TARGETS, HashSet(selected)).apply()
        adapter.notifyDataSetChanged()
    }

    @Suppress("DEPRECATION")
    private fun installedApps(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getInstalledApplications(0)
        }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val items = installedApps(pm)
                .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppItem(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            runOnUiThread {
                allApps.clear()
                allApps.addAll(items)
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = query.lowercase()
        val filtered = allApps
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            .sortedWith(compareByDescending<AppItem> { it.pkg in selected }.thenBy { it.label.lowercase() })
        shown.clear()
        shown.addAll(filtered)
        adapter.notifyDataSetChanged()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val item = shown[position]
            (row.getChildAt(0) as ImageView).setImageDrawable(item.icon)
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = item.label
            (texts.getChildAt(1) as TextView).text = item.pkg
            (row.getChildAt(2) as CheckBox).isChecked = item.pkg in selected
            return row
        }

        private fun buildRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(40), dp(40)))
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
            }
            texts.addView(TextView(this@MainActivity).apply { textSize = 16f })
            texts.addView(TextView(this@MainActivity).apply { textSize = 12f; alpha = 0.7f })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                CheckBox(this@MainActivity).apply {
                    isClickable = false
                    isFocusable = false
                },
            )
            return row
        }
    }
}
