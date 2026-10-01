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
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
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

internal class Snap(val mtime: Long, val jumlah: Int, val zona: String?)

class RunnerService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var fs: IFileService? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var latch = CountDownLatch(1)
    private var titik: View? = null
    private var kapsul: TextView? = null
    private var gagalBuka = false
    private var alasan = ""
    private val polaZona = Regex("iZoneId:\\s*(\\d+)")
    private val polaMtime = Regex("MTIME:(\\d+)")

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
        val d1 = intent.getLongExtra("d1", 5000L)
        val d2 = intent.getLongExtra("d2", 7000L)
        val kembali = intent.getBooleanExtra("back", true)

        RunnerState.reset()
        RunnerState.running = true
        Thread {
            try {
                jalankan(pkgs, d1, d2, kembali)
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

    private fun jalankan(pkgs: List<String>, d1: Long, d2: Long, kembali: Boolean) {
        RunnerState.log("Mulai: ${pkgs.size} aplikasi, jeda 1 = $d1 ms, jeda 2 = $d2 ms")
        pasangTitik()

        if (!sambungShizuku()) {
            RunnerState.fase = "Shizuku belum siap"
            RunnerState.log("PERINGATAN: $alasan. Proses dibatalkan, tidak ada apk yang dibuka.")
            if (kembali) kembaliKeAplikasi()
            peringatan("Shizuku belum tersambung: $alasan")
            tidur(3000)
            return
        }
        RunnerState.log("Shizuku tersambung")

        val dibuka = LinkedHashSet<String>()
        val zona = HashMap<String, String>()
        val dipakai = HashMap<String, String>()
        val sisa = ArrayList<String>()

        fun kembar(z: String?, pkg: String): Boolean {
            if (z == null) return false
            val o = dipakai[z]
            return o != null && o != pkg
        }

        RunnerState.fase = "Putaran 1"
        RunnerState.total = pkgs.size
        RunnerState.done = 0

        for (pkg in pkgs) {
            if (RunnerState.stop) break

            gagalBuka = false

            // Percobaan 1
            var z: String? = bukaDanBaca(pkg, d1, dibuka)
            if (RunnerState.stop) break
            if (gagalBuka) {
                RunnerState.done++
                continue
            }

            // Percobaan 2: hanya jika tidak terbaca atau kembar; tutup dulu, langsung buka
            if (z == null || kembar(z, pkg)) {
                if (z == null) {
                    RunnerState.log("$pkg : Zone ID belum terbaca (percobaan 1), tutup lalu buka ulang")
                } else {
                    RunnerState.log("$pkg : Zone ID $z kembar dengan ${dipakai[z]} (percobaan 1), tutup lalu buka ulang")
                }
                z = ulang(pkg, d1, dibuka)
                if (RunnerState.stop) break
                if (z != null) RunnerState.log("$pkg : Zone ID percobaan 2 = $z")
            }

            if (z == null || kembar(z, pkg)) {
                if (z == null) {
                    RunnerState.log("$pkg : Zone ID tetap tidak terbaca setelah 2 percobaan, ditutup dan dicoret dari putaran 2")
                } else {
                    RunnerState.log("$pkg : Zone ID $z tetap kembar setelah 2 percobaan, ditutup dan dicoret dari putaran 2")
                }
                if (!tutup(pkg)) RunnerState.log("Tidak bisa menutup $pkg")
                RunnerState.done++
                continue
            }

            dipakai[z] = pkg
            zona[pkg] = z
            RunnerState.zona(pkg, z)
            RunnerState.log("$pkg : Zone ID $z")
            sisa.add(pkg)
            RunnerState.done++
        }

        if (!RunnerState.stop) {
            for (pkg in sisa) {
                val zb = snap(pkg)?.zona
                if (zb != null && zb != zona[pkg]) {
                    RunnerState.log("$pkg : Zone ID berubah ${zona[pkg]} -> $zb")
                    zona[pkg] = zb
                    RunnerState.zona(pkg, zb)
                }
            }
            val urut = sisa.sortedBy { zona[it]?.toLongOrNull() ?: Long.MAX_VALUE }

            RunnerState.fase = "Putaran 2"
            RunnerState.total = urut.size
            RunnerState.done = 0
            RunnerState.log("Urutan putaran 2: " + urut.joinToString(", ") { (zona[it] ?: "?") })
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

    private fun bukaDanBaca(pkg: String, batas: Long, dibuka: MutableSet<String>): String? {
        val awal = snap(pkg)
        if (buka(pkg)) {
            dibuka.add(pkg)
        } else {
            gagalBuka = true
            return null
        }
        return tungguZona(pkg, batas, awal)
    }

    private fun ulang(pkg: String, batas: Long, dibuka: MutableSet<String>): String? {
        if (!tutup(pkg)) {
            RunnerState.log("Gagal menutup $pkg, tidak dibuka ulang")
            return null
        }
        RunnerState.log("Ditutup: $pkg")
        return bukaDanBaca(pkg, batas, dibuka)
    }

    private fun snap(pkg: String): Snap? {
        return try {
            val teks = fs?.readLatestLog(pkg) ?: return null
            if (teks.isEmpty()) return null
            val m = polaMtime.find(teks)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val semua = polaZona.findAll(teks).map { it.groupValues[1] }.toList()
            Snap(m, semua.size, semua.lastOrNull())
        } catch (e: Exception) {
            null
        }
    }

    private fun tungguZona(pkg: String, batas: Long, awal: Snap?): String? {
        val mulai = SystemClock.elapsedRealtime()
        var cadangan: String? = null
        while (SystemClock.elapsedRealtime() - mulai < batas) {
            if (RunnerState.stop) return null
            val s = snap(pkg)
            if (s != null && s.zona != null) {
                val baru = awal == null ||
                    (s.mtime > awal.mtime && (s.jumlah != awal.jumlah || s.zona != awal.zona))
                if (baru) return s.zona
                if (awal != null && s.mtime > awal.mtime) cadangan = s.zona
            }
            if (!tidur(200)) return null
        }
        return cadangan
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
            if (!Shizuku.pingBinder()) {
                alasan = "Shizuku belum jalan, nyalakan dulu"
                return false
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                alasan = "izin Shizuku belum diberikan"
                return false
            }
            val a = Shizuku.UserServiceArgs(ComponentName(packageName, FileService::class.java.name))
                .daemon(false)
                .processNameSuffix("file")
                .version(1)
            args = a
            latch = CountDownLatch(1)
            Shizuku.bindUserService(a, conn)
            latch.await(6, TimeUnit.SECONDS)
            if (fs == null) {
                alasan = "gagal tersambung ke layanan Shizuku"
                false
            } else {
                true
            }
        } catch (e: Throwable) {
            alasan = "Shizuku error"
            false
        }
    }

    private fun peringatan(teks: String) {
        main.post {
            try {
                Toast.makeText(applicationContext, teks, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
            }
        }
        tampilKapsul("Shizuku belum tersambung")
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
