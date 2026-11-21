package com.bestwinesport.admin

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Start-screen video upload. The video is cut into small pieces and saved in Firebase (videoData/<id>/c0..cN);
 * the setting then holds "dbvid:<id>:<pieces>:<bytes>". Players download it once in the background and keep it on the phone.
 */
object VideoUpload {
    const val MAX_BYTES = 30L * 1024 * 1024
    private const val CHUNK = 150_000

    suspend fun upload(c: Context, uri: Uri, onProgress: (Int, Int) -> Unit): String = withContext(Dispatchers.IO) {
        val mime = c.contentResolver.getType(uri).orEmpty()
        if (!mime.startsWith("video/")) throw UploadException("Please choose a video file (MP4).")
        val tooBig = "Video is too large (max 30 MB). Choose a shorter clip or compress it."
        // Size known up front -> reject big files before anything is uploaded.
        var known = -1L
        try {
            c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst() && !cur.isNull(0)) known = cur.getLong(0)
            }
        } catch (_: Exception) { }
        if (known > MAX_BYTES) throw UploadException(tooBig)
        if (known == 0L) throw UploadException("This video is empty.")
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur <= 0 || r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") throw UploadException("This file cannot be played as a video.")
        } catch (e: UploadException) { throw e }
        catch (e: Exception) { throw UploadException("This file cannot be played as a video.") }
        finally { try { r.release() } catch (_: Exception) { } }
        val id = AR.db.child("videoData").push().key ?: throw UploadException("Could not create a video id.")
        val expected = if (known > 0) ((known + CHUNK - 1) / CHUNK).toInt() else 0
        var total = 0L
        var n = 0
        suspend fun cleanup() { try { AR.db.child("videoData/" + id).removeValue().await() } catch (_: Exception) { } }
        try {
            // The video is read and uploaded piece by piece, so even a 30 MB file never sits fully in memory.
            c.contentResolver.openInputStream(uri).use { i ->
                if (i == null) throw UploadException("Could not open this video.")
                val buf = ByteArray(CHUNK)
                while (true) {
                    var filled = 0
                    while (filled < CHUNK) { val rd = i.read(buf, filled, CHUNK - filled); if (rd < 0) break; filled += rd }
                    if (filled == 0) break
                    total += filled
                    if (total > MAX_BYTES) throw UploadException(tooBig)
                    AR.db.child("videoData/" + id + "/c" + n).setValue(Base64.encodeToString(buf, 0, filled, Base64.NO_WRAP)).await()
                    n++
                    onProgress(n, expected)
                    if (filled < CHUNK) break
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { cleanup(); throw e }
        catch (e: UploadException) { cleanup(); throw e }
        catch (e: Exception) {
            cleanup()
            throw UploadException("Could not save the video in Firebase. Publish the latest database.rules.json. (" + (e.message ?: "error") + ")")
        }
        if (n == 0) { cleanup(); throw UploadException("This video is empty.") }
        "dbvid:" + id + ":" + n + ":" + total
    }

    /** Size in MB of an uploaded video reference, or null. */
    fun sizeMb(ref: String): String? = ref.split(":").getOrNull(3)?.toLongOrNull()?.let { String.format(java.util.Locale.US, "%.1f", it / 1048576.0) }

    /** Best effort: removes the pieces of an old / removed video. */
    suspend fun deleteQuiet(ref: String) {
        if (!ref.startsWith("dbvid:")) return
        val id = ref.split(":").getOrNull(1).orEmpty()
        if (id.isBlank()) return
        try { AR.db.child("videoData/" + id).removeValue().await() } catch (_: Exception) { }
    }
}
