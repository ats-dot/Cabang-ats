package com.ats_tsalatsah.app

import java.io.File
import java.io.RandomAccessFile

class FileService : IFileService.Stub() {

    private val prefix = "com.revanstor"
    private val aman = Regex("[A-Za-z0-9._]+")
    private val polaTop = Regex("topResumedActivity=ActivityRecord\\{[^ ]+ u\\d+ ([A-Za-z0-9._]+)/")
    private val polaFokus = Regex("mCurrentFocus=Window\\{[^ ]+ u\\d+ ([A-Za-z0-9._]+)/")
    private val polaAnim = Regex("^(null|[0-9]+(\\.[0-9]+)?)$")
    private val kunciAnim = arrayOf("window_animation_scale", "transition_animation_scale")

    private fun paketBoleh(pkg: String): Boolean =
        pkg.startsWith(prefix) && aman.matches(pkg)

    override fun destroy() {
        System.exit(0)
    }

    override fun readLatestLog(pkg: String): String {
        if (!paketBoleh(pkg)) return ""
        return try {
            val dir = File("/storage/emulated/0/Android/data/$pkg/files/Flog")
            val file = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() } ?: return ""
            val waktu = file.lastModified()
            val len = file.length()
            val maks = 200_000L
            val mulai = if (len > maks) len - maks else 0L
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(mulai)
                val buf = ByteArray((len - mulai).toInt())
                raf.readFully(buf)
                "MTIME:" + waktu + "\n" + String(buf, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }

    override fun forceStop(pkg: String): Boolean {
        if (!paketBoleh(pkg)) return false
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("am", "force-stop", pkg))
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun topPackage(): String {
        var hasil = cari(arrayOf("dumpsys", "activity", "activities"), polaTop)
        if (hasil.isEmpty()) {
            hasil = cari(arrayOf("dumpsys", "window"), polaFokus)
        }
        return if (hasil.startsWith(prefix) && aman.matches(hasil)) hasil else ""
    }

    override fun getAnim(): String {
        val hasil = ArrayList<String>()
        for (k in kunciAnim) {
            val v = baca(arrayOf("settings", "get", "global", k))
            hasil.add(if (polaAnim.matches(v)) v else "null")
        }
        return hasil.joinToString("|")
    }

    override fun setAnim(nilai: String): Boolean {
        val bagian = nilai.split("|")
        if (bagian.size != kunciAnim.size) return false
        for (b in bagian) {
            if (!polaAnim.matches(b)) return false
        }
        var ok = true
        for (i in kunciAnim.indices) {
            val cmd = if (bagian[i] == "null") {
                arrayOf("settings", "delete", "global", kunciAnim[i])
            } else {
                arrayOf("settings", "put", "global", kunciAnim[i], bagian[i])
            }
            if (!jalan(cmd)) ok = false
        }
        return ok
    }

    private fun baca(cmd: Array<String>): String {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(cmd)
            val teks = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            teks.trim()
        } catch (e: Exception) {
            ""
        } finally {
            try {
                p?.destroy()
            } catch (e: Exception) {
            }
        }
    }

    private fun jalan(cmd: Array<String>): Boolean {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(cmd)
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        } finally {
            try {
                p?.destroy()
            } catch (e: Exception) {
            }
        }
    }

    private fun cari(cmd: Array<String>, pola: Regex): String {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(cmd)
            var hasil = ""
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val baris = r.readLine() ?: break
                    val m = pola.find(baris)
                    if (m != null) {
                        hasil = m.groupValues[1]
                        break
                    }
                }
            }
            hasil
        } catch (e: Exception) {
            ""
        } finally {
            try {
                p?.destroy()
            } catch (e: Exception) {
            }
        }
    }
}
