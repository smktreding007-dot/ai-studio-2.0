package com.bestwinesport.staff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class UploadException(message: String) : Exception(message)

/** Uploads a photo to the Railway backend, which stores it in the private Telegram channel. Returns "tgimg:<imageId>". */
object ImgUpload {
    private suspend fun base(): String {
        val b = BackendConfig.BASE_URL.trim().trimEnd('/')
        if (b.contains("YOUR-BACKEND") || !b.startsWith("http")) throw UploadException("Backend URL is not set. Edit BackendConfig.kt and rebuild the app.")
        return b
    }

    private suspend fun token(force: Boolean): String =
        AR.auth.currentUser?.getIdToken(force)?.await()?.token ?: throw UploadException("Authentication failed. Log in again.")

    /** Saves the photo. Settings -> Image storage: auto (default) = imgbb, then Firebase, then the Railway backend. */
    suspend fun upload(c: Context, uri: Uri, type: String, refId: String, maxSide: Int): String = withContext(Dispatchers.IO) {
        val mode = try { AR.get("settings/imageStorage")?.toString()?.trim()?.lowercase().orEmpty().ifBlank { "auto" } } catch (e: Exception) { "auto" }
        val order = when (mode) { "imgbb" -> listOf("imgbb"); "firebase" -> listOf("firebase"); "backend" -> listOf("backend"); else -> listOf("imgbb", "firebase", "backend") }
        val errors = ArrayList<String>()
        for (name in order) {
            try {
                return@withContext when (name) {
                    "imgbb" -> uploadToImgbb(c, uri, maxSide)
                    "firebase" -> uploadToFirebase(c, uri, maxSide)
                    else -> uploadToBackend(c, uri, type, refId, maxSide)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { errors.add(name + ": " + (e.message ?: "error")) }
        }
        throw UploadException(errors.joinToString("\n"))
    }

    /** imgbb key saved by the admin (Settings -> imgbb API key), otherwise the built-in key. */
    private suspend fun imgbbKey(): String = try { AR.get("panelSecrets/imgbbKey")?.toString()?.trim().orEmpty().ifBlank { BackendConfig.IMGBB_KEY } } catch (e: Exception) { BackendConfig.IMGBB_KEY }

    /** Uploads to imgbb.com and returns the public https link. */
    private suspend fun uploadToImgbb(c: Context, uri: Uri, maxSide: Int): String = withContext(Dispatchers.IO) {
        val bytes = compress(c, uri, maxSide)
        val boundary = "----b25" + System.currentTimeMillis()
        val conn = URL("https://api.imgbb.com/1/upload?key=" + imgbbKey()).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            val png = bytes.isNotEmpty() && bytes[0] == 0x89.toByte()
            val mime = if (png) "image/png" else "image/jpeg"
            conn.outputStream.use { o ->
                o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"upload." + (if (png) "png" else "jpg") + "\"\r\nContent-Type: " + mime + "\r\n\r\n").toByteArray())
                o.write(bytes)
                o.write(("\r\n--" + boundary + "--\r\n").toByteArray())
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { null }
            val link = json?.optJSONObject("data")?.optString("url").orEmpty()
            if (code in 200..299 && link.startsWith("http")) link
            else throw UploadException("imgbb: " + (json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: ("HTTP " + code)))
        } finally { conn.disconnect() }
    }

    /** Stores the compressed image inside Firebase Realtime Database (imageData/<id>) and returns "dbimg:<id>". */
    private suspend fun uploadToFirebase(c: Context, uri: Uri, maxSide: Int): String {
        var side = minOf(maxSide, 1280)
        var data: String
        while (true) {
            val bytes = compress(c, uri, side)
            val mime = if (bytes.isNotEmpty() && bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"
            data = "data:" + mime + ";base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            if (data.length <= 700_000 || side <= 320) break
            side = (side * 0.75f).toInt()
        }
        if (data.length > 700_000) throw UploadException("Image is too large. Choose a smaller image.")
        val id = AR.db.child("imageData").push().key ?: throw UploadException("Could not create an image id.")
        try { AR.db.child("imageData/" + id).setValue(data).await() }
        catch (e: Exception) { throw UploadException("Could not save the image in Firebase. Publish the latest database.rules.json. (" + (e.message ?: "error") + ")") }
        return "dbimg:" + id
    }

    private suspend fun uploadToBackend(c: Context, uri: Uri, type: String, refId: String, maxSide: Int): String = withContext(Dispatchers.IO) {
        val bytes = compress(c, uri, maxSide)
        var res = send(bytes, type, refId, token(false))
        if (res.first == 401) res = send(bytes, type, refId, token(true))
        val (code, text) = res
        val json = try { JSONObject(text) } catch (e: Exception) { null }
        if (code in 200..299 && json?.optBoolean("success") == true) return@withContext "tgimg:" + json.getString("imageId")
        throw UploadException(json?.optString("message")?.takeIf { it.isNotBlank() } ?: when (code) {
            401, 403 -> "Authentication failed. Log in again."
            413 -> "Image is too large."
            415 -> "This is not a valid image."
            in 500..599 -> "Backend is not available right now. Try again."
            else -> "Upload failed ($code)."
        })
    }

    private suspend fun send(bytes: ByteArray, type: String, refId: String, token: String): Pair<Int, String> {
        val boundary = "----BestwinBoundary" + System.currentTimeMillis()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) { body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()) }
        field("type", type); field("refId", refId)
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
        body.write(bytes); body.write("\r\n--$boundary--\r\n".toByteArray())
        val data = body.toByteArray()
        try {
            val con = URL(base() + "/api/upload/image").openConnection() as HttpURLConnection
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 15000; con.readTimeout = 45000
            con.setRequestProperty("Authorization", "Bearer $token")
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            con.setFixedLengthStreamingMode(data.size)
            con.outputStream.use { it.write(data) }
            val code = con.responseCode
            val text = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            return code to text
        } catch (e: UploadException) { throw e }
        catch (e: UnknownHostException) { throw UploadException("No internet connection.") }
        catch (e: ConnectException) { throw UploadException("Cannot reach the backend. Check internet or the backend.") }
        catch (e: SocketTimeoutException) { throw UploadException("Upload timed out. Try again.") }
        catch (e: IOException) { throw UploadException("Network error. Try again.") }
    }

    /** Best effort: removes an old image from Telegram + Firebase metadata after it was replaced. */
    suspend fun deleteQuiet(ref: String) {
        if (ref.startsWith("dbimg:")) { try { AR.db.child("imageData/" + ref.removePrefix("dbimg:")).removeValue().await() } catch (_: Exception) { }; return }
        if (!ref.startsWith("tgimg:")) return
        try {
            val t = token(false)
            withContext(Dispatchers.IO) {
                val con = URL(base() + "/api/images/" + ref.removePrefix("tgimg:")).openConnection() as HttpURLConnection
                con.requestMethod = "DELETE"; con.connectTimeout = 10000; con.readTimeout = 15000
                con.setRequestProperty("Authorization", "Bearer $t")
                con.responseCode; con.disconnect()
            }
        } catch (_: Exception) { }
    }

    private fun compress(c: Context, uri: Uri, maxSide: Int): ByteArray {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 2400) s *= 2
        val bmp = c.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
            ?: throw UploadException("This is not a valid image.")
        val sc = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        if (out.hasAlpha()) out.compress(Bitmap.CompressFormat.PNG, 100, bos) else out.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        return bos.toByteArray()
    }
}
