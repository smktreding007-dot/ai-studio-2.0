package com.bestwinesport.staff

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Start-screen settings + the optional start-screen VIDEO (all controlled from Admin -> App settings).
 *
 * Everything the start screen needs is read from what is saved on the phone, so the screen never waits for the
 * internet and never changes while it is open. The settings are refreshed in the background for the NEXT start.
 * The video is downloaded once in the background (stored as chunks in Firebase "videoData/<id>/c0..cN"), checked
 * that it really plays, and only then used - a broken or half-downloaded file is never shown.
 */
object SplashVideo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("splash_video", Context.MODE_PRIVATE)
    fun file(c: Context) = File(c.applicationContext.filesDir, "splash_video.mp4")

    /** Admin switches (default ON = exactly like before). */
    fun imageOn(c: Context) = prefs(c).getBoolean("imgOn", true)
    fun logoOn(c: Context) = prefs(c).getBoolean("logoOn", true)

    /** The video to play now, or null (admin switch off, nothing downloaded yet, or file missing). */
    fun playable(c: Context): File? {
        val p = prefs(c)
        if (!p.getBoolean("on", false) || p.getString("readyRef", "").isNullOrBlank()) return null
        val f = file(c)
        return if (f.exists() && f.length() > 0) f else null
    }

    /** Reads the admin settings in the background (never blocks the start screen) and downloads a new video if needed. */
    fun refreshAsync(c: Context) {
        val app = c.applicationContext
        scope.launch {
            try {
                val s = (withTimeout(4000) { AR.get("settings") } as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return@launch
                fun v(k: String) = s[k]?.toString()?.trim().orEmpty()
                val ref = v("splashVideo")
                val enabled = v("splashVideoEnabled").equals("true", ignoreCase = true) && ref.isNotBlank()
                val p = prefs(app)
                p.edit().putBoolean("imgOn", !v("splashImageEnabled").equals("false", ignoreCase = true))
                    .putBoolean("logoOn", !v("splashLogoEnabled").equals("false", ignoreCase = true))
                    .putBoolean("on", enabled).putString("ref", ref).apply()
                if (ref.isBlank()) {            // video removed by the admin -> free the space
                    file(app).delete(); p.edit().remove("readyRef").remove("badRef").apply()
                    return@launch
                }
                if (enabled && ref != p.getString("readyRef", "") && ref != p.getString("badRef", "")) download(app, ref)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private suspend fun download(app: Context, ref: String) {
        if (!busy.compareAndSet(false, true)) return
        val tmp = File(app.filesDir, "splash_video.tmp")
        try {
            tmp.delete()
            val ok = if (ref.startsWith("dbvid:")) fromFirebase(ref, tmp) else if (ref.startsWith("http")) fromUrl(ref, tmp) else false
            val p = prefs(app)
            if (!ok) return                                         // network problem: try again at the next start
            if (!isPlayable(tmp)) { p.edit().putString("badRef", ref).apply(); return }   // finished but not a playable video: never retry this one
            val dest = file(app)
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true) }
            p.edit().putString("readyRef", ref).remove("badRef").apply()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        finally { tmp.delete(); busy.set(false) }
    }

    /** "dbvid:<id>:<chunks>:<bytes>" */
    private suspend fun fromFirebase(ref: String, out: File): Boolean {
        val parts = ref.split(":")
        val id = parts.getOrNull(1).orEmpty(); val n = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val size = parts.getOrNull(3)?.toLongOrNull() ?: -1L
        if (id.isBlank() || n <= 0) return false
        out.outputStream().use { o ->
            for (i in 0 until n) {
                var data: String? = null
                for (attempt in 0..1) {
                    data = try { withTimeout(30_000) { AR.db.child("videoData/$id/c$i").get().await().value as? String } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (data != null) break
                }
                if (data == null) return false
                o.write(Base64.decode(data, Base64.DEFAULT))
            }
        }
        return out.length() > 0 && (size < 0 || out.length() == size)
    }

    private fun fromUrl(url: String, out: File): Boolean = try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 10_000; con.readTimeout = 20_000
        if (con.responseCode !in 200..299) false
        else { con.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }; out.length() > 0 }
    } catch (_: Exception) { false }

    private fun isPlayable(f: File): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            dur > 0 && r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } catch (_: Exception) { false } finally { try { r.release() } catch (_: Exception) { } }
    }
}
