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
    private var tirai: View? = null
    private var kapsul: TextView? = null
    private var alasan = ""
    private val polaZona = Regex("iZoneId:\\s*(\\d+)")
    private val polaMtime = Regex("MTIME:(\\d+)")
    private val zonaAwal = 57092L
    private val batasPindah = 3000L

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
        val d1 = intent.getLongExtra("d1", 5520L)
        val d2 = intent.getLongExtra("d2", 5300L)
        val kembali = intent.getBooleanExtra("back", true)
        val target = intent.getIntExtra("target", 0)

        RunnerState.reset()
        RunnerState.running = true
        Thread {
            try {
                jalankan(pkgs, d1, d2, kembali, target)
            } catch (e: Throwable) {
                RunnerState.log("Error: " + e.message)
            } finally {
                hapusTirai()
                RunnerState.running = false
                if (RunnerState.fase.startsWith("Putaran")) RunnerState.fase = "Berhenti"
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun jalankan(pkgs: List<String>, d1: Long, d2: Long, kembali: Boolean, targetIn: Int) {
        val target = if (targetIn <= 0 || targetIn > pkgs.size) pkgs.size else targetIn
        RunnerState.log("Mulai: target $target server dari ${pkgs.size} apk cadangan, jeda 1 = $d1 ms, jeda 2 = $d2 ms")
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

        RunnerState.fase = "Putaran 1"
        RunnerState.total = target
        RunnerState.done = 0

        for (pkg in pkgs) {
            if (RunnerState.stop) break
            if (sisa.size >= target) break

            val z = cobaApk(pkg, d1, dipakai, dibuka)
            if (RunnerState.stop) break

            if (z != null) {
                dipakai[z] = pkg
                zona[pkg] = z
                RunnerState.zona(pkg, z)
                sisa.add(pkg)
                RunnerState.done = sisa.size
                RunnerState.log("$pkg : Zone ID $z (server ${sisa.size}/$target)")
            }
        }

        if (!RunnerState.stop) {
            if (sisa.size < target) {
                RunnerState.log("Target $target, tercapai ${sisa.size}. Apk cadangan habis, lanjut ke putaran 2.")
            }
            infoBolong(zona, target)

            val urut = sisa.sortedBy { zona[it]?.toLongOrNull() ?: Long.MAX_VALUE }
            RunnerState.fase = "Putaran 2"
            RunnerState.total = urut.size
            RunnerState.done = 0
            RunnerState.log("Urutan putaran 2: " + urut.joinToString(", ") { zona[it] ?: "?" })

            for (pkg in urut) {
                if (RunnerState.stop) break
                pindahPutaran2(pkg, d2, dibuka)
                if (RunnerState.stop) break
                RunnerState.done++
            }
        }

        if (RunnerState.stop) {
            RunnerState.fase = "Dihentikan"
            RunnerState.log("Dihentikan")
        } else {
            RunnerState.fase = "Selesai"
            RunnerState.log("Selesai, ${dibuka.size} app dibuka")
            hapusTirai()
            if (kembali) kembaliKeAplikasi()
            tampilKapsul("Selesai · ${dibuka.size} app dibuka")
            tidur(2800)
        }
    }

    private fun cobaApk(
        pkg: String,
        d1: Long,
        dipakai: Map<String, String>,
        dibuka: MutableSet<String>
    ): String? {
        gelap()
        if (RunnerState.stop) return null

        val awal = snap(pkg)
        if (!buka(pkg)) {
            terang()
            return null
        }
        dibuka.add(pkg)

        val mulai = SystemClock.elapsedRealtime()
        var tampak = false
        var z: String? = null
        var cadangan: String? = null

        while (SystemClock.elapsedRealtime() - mulai < d1) {
            if (RunnerState.stop) return null

            if (!tampak) {
                val lewat = SystemClock.elapsedRealtime() - mulai
                if (puncak() == pkg || lewat >= batasPindah) {
                    terang()
                    tampak = true
                }
            }

            if (z == null) {
                val s = snap(pkg)
                val zz = s?.zona
                if (s != null && zz != null) {
                    val baru = awal == null ||
                        (s.mtime > awal.mtime && (s.jumlah != awal.jumlah || zz != awal.zona))
                    if (baru) {
                        z = zz
                        val o = dipakai[zz]
                        if (o != null && o != pkg) break
                    } else if (awal != null && s.mtime > awal.mtime) {
                        cadangan = zz
                    }
                }
            }

            if (!tidur(200)) return null
        }

        if (!tampak) terang()
        if (z == null) z = cadangan

        if (z == null) {
            RunnerState.log("$pkg : Zone ID tidak terbaca sampai jeda habis, ditutup")
            if (!tutup(pkg)) RunnerState.log("Tidak bisa menutup $pkg")
            return null
        }

        val o = dipakai[z]
        if (o != null && o != pkg) {
            RunnerState.log("$pkg : Zone ID $z kembar dengan $o, ditutup")
            if (!tutup(pkg)) RunnerState.log("Tidak bisa menutup $pkg")
            return null
        }
        return z
    }

    private fun pindahPutaran2(pkg: String, d2: Long, dibuka: MutableSet<String>) {
        gelap()
        if (RunnerState.stop) return
        if (!buka(pkg)) {
            terang()
            tidur(d2)
            return
        }
        dibuka.add(pkg)

        val mulai = SystemClock.elapsedRealtime()
        val batas = if (d2 < batasPindah) d2 else batasPindah
        while (SystemClock.elapsedRealtime() - mulai < batas) {
            if (RunnerState.stop) return
            if (puncak() == pkg) break
            if (!tidur(150)) return
        }
        terang()

        val sisaWaktu = d2 - (SystemClock.elapsedRealtime() - mulai)
        if (sisaWaktu > 0) tidur(sisaWaktu)
    }

    private fun infoBolong(zona: Map<String, String>, target: Int) {
        if (target <= 0) return
        val akhir = zonaAwal + target - 1
        val ada = zona.values.mapNotNull { it.toLongOrNull() }.toSet()
        val hilang = (zonaAwal..akhir).filter { it !in ada }
        if (hilang.isEmpty()) return
        val luar = ada.filter { it < zonaAwal || it > akhir }.sorted()
        var teks = "Urutan $zonaAwal-$akhir tidak penuh. Tidak dapat: " + hilang.joinToString(", ")
        if (luar.isNotEmpty()) teks += ". Di luar rentang: " + luar.joinToString(", ")
        RunnerState.log(teks)
    }

    private fun puncak(): String {
        return try {
            fs?.topPackage() ?: ""
        } catch (e: Exception) {
            ""
        }
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
                .version(2)
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

    private fun gelap() {
        if (!Settings.canDrawOverlays(this)) return
        main.post {
            try {
                var v = tirai
                if (v == null) {
                    v = View(this)
                    v.setBackgroundColor(Color.BLACK)
                    v.alpha = 0f
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    )
                    if (Build.VERSION.SDK_INT >= 28) {
                        lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    wm().addView(v, lp)
                    tirai = v
                }
                v.animate().cancel()
                v.animate().alpha(1f).setDuration(250).start()
            } catch (e: Exception) {
            }
        }
        tidur(300)
    }

    private fun terang() {
        main.post {
            try {
                val v = tirai
                if (v != null) {
                    v.animate().cancel()
                    v.animate().alpha(0f).setDuration(300).start()
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun hapusTirai() {
        val kerja = Runnable {
            val v = tirai
            tirai = null
            if (v != null) {
                try {
                    v.animate().cancel()
                    wm().removeView(v)
                } catch (e: Exception) {
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) kerja.run() else main.post(kerja)
    }

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
        hapusTirai()
        hapusTitik()
        try {
            val a = args
            if (a != null) Shizuku.unbindUserService(a, conn, true)
        } catch (e: Throwable) {
        }
        super.onDestroy()
    }
}
