package com.ats_tsalatsah.app

import java.io.File
import java.io.RandomAccessFile

class FileService : IFileService.Stub() {

    private val prefix = "com.revanstor"
    private val aman = Regex("[A-Za-z0-9._]+")

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
            val len = file.length()
            val maks = 200_000L
            val mulai = if (len > maks) len - maks else 0L
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(mulai)
                val buf = ByteArray((len - mulai).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
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
}
