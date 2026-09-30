package com.ats_tsalatsah.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RunnerState {
    @Volatile var running = false
    @Volatile var stop = false
    @Volatile var total = 0
    @Volatile var done = 0
    @Volatile var fase = "Siap"
    private val logs = ArrayList<String>()
    private val zones = LinkedHashMap<String, String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized fun log(s: String) {
        logs.add(fmt.format(Date()) + "  " + s)
        if (logs.size > 500) logs.removeAt(0)
    }

    @Synchronized fun reset() {
        logs.clear()
        zones.clear()
        done = 0
        total = 0
        stop = false
    }

    @Synchronized fun zona(pkg: String, z: String) {
        zones[pkg] = z
    }

    @Synchronized fun json(): String {
        val o = JSONObject()
        o.put("running", running)
        o.put("done", done)
        o.put("total", total)
        o.put("fase", fase)
        o.put("logs", JSONArray(logs))
        val z = JSONObject()
        for ((k, v) in zones) z.put(k, v)
        o.put("zones", z)
        return o.toString()
    }
}

class RunnerService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var fs: IFileService? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var latch = CountDownLatch(1)
    private var titik: View? = null
    private var kapsul: TextView? = null
    private val polaZona = Regex("iZoneId:\\s*(\\d+)")

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            fs = if (binder != null && binder.pingBinder()) IFileService.Stub.asInterface(binder) else null
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fs = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buatNotif()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (intent == null || RunnerState.running) return START_NOT_STICKY

        val pkgs = intent.getStringArrayExtra("pkgs")?.toList() ?: emptyList()
        val manual = intent.getStringArrayExtra("zones")?.toList() ?: emptyList()
        val d1 = intent.getLongExtra("d1", 5000L)
        val d2 = intent.getLongExtra("d2", 7000L)
        val kembali = intent.getBooleanExtra("back", true)

        RunnerState.reset()
        RunnerState.running = true
        Thread {
            try {
                jalankan(pkgs, manual, d1, d2, kembali)
            } catch (e: Throwable) {
                RunnerState.log("Error: " + e.message)
            } finally {
                RunnerState.running = false
                if (RunnerState.fase.startsWith("Putaran")) RunnerState.fase = "Berhenti"
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun jalankan(pkgs: List<String>, manual: List<String>, d1: Long, d2: Long, kembali: Boolean) {
        RunnerState.log("Mulai: ${pkgs.size} aplikasi, jeda 1 = $d1 ms, jeda 2 = $d2 ms")
        pasangTitik()
        val shizukuSiap = sambungShizuku()
        RunnerState.log(
            if (shizukuSiap) "Shizuku tersambung"
            else "Shizuku belum siap, memakai Zone ID manual"
        )

        val dibuka = LinkedHashSet<String>()
        val zona = HashMap<String, String>()
        val dipakai = HashMap<String, String>()
        val sisa = ArrayList<String>()

        RunnerState.fase = "Putaran 1"
        RunnerState.total = pkgs.size
        RunnerState.done = 0

        for ((i, pkg) in pkgs.withIndex()) {
            if (RunnerState.stop) break
            if (buka(pkg)) dibuka.add(pkg)
            if (!tidur(d1)) break

            var z: String? = if (shizukuSiap) bacaZona(pkg) else null
            if (z == null) {
                val m = manual.getOrNull(i)?.trim().orEmpty()
                if (m.isNotEmpty()) z = m
            }

            if (z != null) {
                val awal = dipakai[z]
                if (awal != null && awal != pkg) {
                    RunnerState.log("Zone ID $z kembar dengan $awal, menutup $pkg")
                    if (tutup(pkg)) RunnerState.log("Ditutup: $pkg")
                    else RunnerState.log("Tidak bisa menutup $pkg, dicoret dari putaran 2")
                    RunnerState.done++
                    continue
                }
                dipakai[z] = pkg
                zona[pkg] = z
                RunnerState.zona(pkg, z)
                RunnerState.log("$pkg : Zone ID $z")
            } else {
                RunnerState.log("$pkg : Zone ID belum terbaca")
            }
            sisa.add(pkg)
            RunnerState.done++
        }

        if (!RunnerState.stop) {
            val terbaca = sisa.filter { zona.containsKey(it) }
                .sortedBy { zona[it]!!.toLongOrNull() ?: Long.MAX_VALUE }
            val belum = sisa.filter { !zona.containsKey(it) }
            val urut = terbaca + belum

            RunnerState.fase = "Putaran 2"
            RunnerState.total = urut.size
            RunnerState.done = 0
            for (pkg in urut) {
                if (RunnerState.stop) break
                if (buka(pkg)) dibuka.add(pkg)
                if (!tidur(d2)) break
                RunnerState.done++
            }
        }

        if (RunnerState.stop) {
            RunnerState.fase = "Dihentikan"
            RunnerState.log("Dihentikan")
        } else {
            RunnerState.fase = "Selesai"
            RunnerState.log("Selesai, ${dibuka.size} app dibuka")
            if (kembali) kembaliKeAplikasi()
            tampilKapsul("Selesai · ${dibuka.size} app dibuka")
            tidur(2800)
        }
    }

    private fun buka(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            RunnerState.log("Tidak bisa membuka $pkg")
            return false
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(i)
            RunnerState.log("Buka $pkg")
            true
        } catch (e: Exception) {
            RunnerState.log("Gagal membuka $pkg: " + e.message)
            false
        }
    }

    private fun tutup(pkg: String): Boolean {
        return try {
            fs?.forceStop(pkg) ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun bacaZona(pkg: String): String? {
        return try {
            val teks = fs?.readLatestLog(pkg) ?: return null
            if (teks.isEmpty()) return null
            polaZona.findAll(teks).lastOrNull()?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun tidur(ms: Long): Boolean {
        var sisa = ms
        while (sisa > 0) {
            if (RunnerState.stop) return false
            val s = if (sisa < 100L) sisa else 100L
            try {
                Thread.sleep(s)
            } catch (e: InterruptedException) {
                return false
            }
            sisa -= s
        }
        return !RunnerState.stop
    }

    private fun sambungShizuku(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
            val a = Shizuku.UserServiceArgs(ComponentName(packageName, FileService::class.java.name))
                .daemon(false)
                .processNameSuffix("file")
                .version(1)
            args = a
            latch = CountDownLatch(1)
            Shizuku.bindUserService(a, conn)
            latch.await(6, TimeUnit.SECONDS)
            fs != null
        } catch (e: Throwable) {
            false
        }
    }

    private fun kembaliKeAplikasi() {
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun wm(): WindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pasangTitik() {
        if (!Settings.canDrawOverlays(this)) {
            RunnerState.log("Izin tampil di atas aplikasi lain belum aktif")
            return
        }
        main.post {
            try {
                val v = View(this)
                v.setBackgroundColor(Color.argb(2, 0, 0, 0))
                val lp = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                wm().addView(v, lp)
                titik = v
            } catch (e: Exception) {
            }
        }
        try {
            Thread.sleep(250)
        } catch (e: InterruptedException) {
        }
    }

    private fun tampilKapsul(teks: String) {
        if (!Settings.canDrawOverlays(this)) return
        main.post {
            try {
                val tv = TextView(this)
                tv.text = teks
                tv.setTextColor(Color.WHITE)
                tv.textSize = 15f
                tv.setPadding(dp(22), dp(12), dp(22), dp(12))
                val bg = GradientDrawable()
                bg.cornerRadius = dp(40).toFloat()
                bg.setColor(0xF20D47A1.toInt())
                tv.background = bg
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = resources.displayMetrics.heightPixels * 15 / 100
                tv.setOnClickListener { hapusKapsul() }
                wm().addView(tv, lp)
                kapsul = tv
                main.postDelayed({ hapusKapsul() }, 2500)
            } catch (e: Exception) {
            }
        }
    }

    private fun hapusKapsul() {
        val k = kapsul
        kapsul = null
        if (k != null) {
            try {
                wm().removeView(k)
            } catch (e: Exception) {
            }
        }
    }

    private fun hapusTitik() {
        val t = titik
        titik = null
        if (t != null) {
            try {
                wm().removeView(t)
            } catch (e: Exception) {
            }
        }
    }

    private fun buatNotif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("runner", "Pembuka aplikasi", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, "runner")
            .setContentTitle("Cabang otomatis")
            .setContentText("Sedang membuka aplikasi")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onDestroy() {
        hapusKapsul()
        hapusTitik()
        try {
            val a = args
            if (a != null) Shizuku.unbindUserService(a, conn, true)
        } catch (e: Throwable) {
        }
        super.onDestroy()
    }
}
