package com.ats_tsalatsah.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var web: WebView

    private val izinShizuku = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { segarkan() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0A1428")
        window.navigationBarColor = Color.parseColor("#0A1428")

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0A1428"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "App")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        try {
            Shizuku.addRequestPermissionResultListener(izinShizuku)
        } catch (e: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        segarkan()
    }

    private fun segarkan() {
        web.evaluateJavascript("if(window.segarkan)segarkan()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(izinShizuku)
        } catch (e: Throwable) {
        }
        super.onDestroy()
    }

    inner class Bridge {

        @JavascriptInterface
        fun apps(): String {
            val arr = JSONArray()
            try {
                val pm = packageManager
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val daftar = pm.queryIntentActivities(i, 0)
                    .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                    .filter { it.second != packageName }
                    .distinctBy { it.second }
                    .sortedWith(compareBy({ it.first.lowercase() }, { it.second }))
                for (d in daftar) {
                    val o = JSONObject()
                    o.put("n", d.first)
                    o.put("p", d.second)
                    arr.put(o)
                }
            } catch (e: Exception) {
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun start(json: String): String {
            var hasil = "ok"
            try {
                val o = JSONObject(json)
                val ps = o.getJSONArray("pkgs")
                if (ps.length() == 0) {
                    hasil = "Pilih aplikasi dulu"
                } else if (RunnerState.running) {
                    hasil = "Masih berjalan"
                } else {
                    val pk = Array(ps.length()) { ps.getString(it) }
                    val i = Intent(this@MainActivity, RunnerService::class.java)
                    i.putExtra("pkgs", pk)
                    i.putExtra("d1", o.optLong("d1", 5520L))
                    i.putExtra("d2", o.optLong("d2", 5300L))
                    i.putExtra("back", o.optBoolean("back", true))
                    i.putExtra("target", o.optInt("target", 0))
                    startForegroundService(i)
                }
            } catch (e: Exception) {
                hasil = "Gagal: " + e.message
            }
            return hasil
        }

        @JavascriptInterface
        fun stop() {
            RunnerState.stop = true
        }

        @JavascriptInterface
        fun status(): String = RunnerState.json()

        @JavascriptInterface
        fun izin(): String {
            val o = JSONObject()
            o.put("overlay", Settings.canDrawOverlays(this@MainActivity))
            val pm = getSystemService(PowerManager::class.java)
            o.put("baterai", pm.isIgnoringBatteryOptimizations(packageName))
            var s = 0
            try {
                if (Shizuku.pingBinder()) {
                    s = if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) 2 else 1
                }
            } catch (e: Throwable) {
                s = 0
            }
            o.put("shizuku", s)
            return o.toString()
        }

        @JavascriptInterface
        fun overlay() {
            runOnUiThread {
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(i)
            }
        }

        @JavascriptInterface
        fun battery() {
            runOnUiThread {
                val pm = getSystemService(PowerManager::class.java)
                val i = if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                }
                startActivity(i)
            }
        }

        @JavascriptInterface
        fun shizuku() {
            runOnUiThread {
                try {
                    if (Shizuku.pingBinder()) {
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(100)
                        }
                    } else {
                        val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (i != null) startActivity(i)
                    }
                } catch (e: Throwable) {
                }
            }
        }
    }
}
