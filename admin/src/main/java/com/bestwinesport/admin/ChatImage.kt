package com.bestwinesport.admin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Photos in the World chat. A picture is shrunk on the phone (max ~1024 px, JPEG, under ~200 KB) and saved in Firebase at worldChat/images/<imageId>
 * as a base64 data URL. The chat message (kind = "image", imageId) is written AFTER the picture is saved, so nobody ever sees an empty photo.
 * Every phone downloads a picture once and keeps it in its cache. Deleting the message also deletes the picture (chat admins / owner / staff).
 */
object ChatImages {
    private const val MAX_CHARS = 200_000
    private val ref: DatabaseReference get() = FirebaseDatabase.getInstance().getReference("worldChat/images")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    /** The picked picture as a small JPEG data URL, or null when it cannot be read. */
    suspend fun prepare(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        var src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return@withContext null
        // phone photos are often stored sideways: turn them upright
        try {
            val deg = ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            if (deg != 0f) {
                val r = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg) }, true)
                if (r !== src) { src.recycle(); src = r }
            }
        } catch (_: Exception) { }
        var maxSide = 1024
        var result: String? = null
        while (maxSide >= 320 && result == null) {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
            var q = 80
            while (q >= 40) {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
                val s = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                if (s.length < MAX_CHARS) { result = s; break }
                q -= 10
            }
            if (bmp !== src) bmp.recycle()
            maxSide = (maxSide * 0.75f).toInt()
        }
        result
    }

    /** Saves the picture and returns its id. */
    suspend fun upload(dataUrl: String): String {
        val id = ref.push().key ?: throw Exception("Could not create an id")
        ref.child(id).setValue(dataUrl).await()
        return id
    }

    private suspend fun bytes(ctx: Context, id: String): ByteArray? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "chatimg").apply { mkdirs() }
        val f = File(dir, safe(id))
        if (f.exists() && f.length() > 0) return@withContext f.readBytes()
        try {
            val v = ref.child(id).get().await().value?.toString() ?: return@withContext null
            val data = Base64.decode(v.substringAfter("base64,", v), Base64.DEFAULT)
            f.writeBytes(data)
            data
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    suspend fun bitmap(ctx: Context, id: String): ImageBitmap? = withContext(Dispatchers.IO) {
        bytes(ctx, id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
}

/** The picture inside a chat bubble. Tap = open big, long-press = the message menu (delete / mute ...). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatPhoto(imageId: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(imageId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(imageId) { mutableStateOf(false) }
    LaunchedEffect(imageId) {
        val b = try { ChatImages.bitmap(ctx, imageId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (b == null) failed = true else bmp = b
    }
    val b = bmp
    val ratio = if (b != null && b.height > 0) (b.width.toFloat() / b.height).coerceIn(0.6f, 1.8f) else 1f
    Box(Modifier.padding(top = 4.dp).width(220.dp).aspectRatio(ratio).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE3E6E8))
        .combinedClickable(onClick = { if (b != null) onClick() }, onLongClick = onLongClick), contentAlignment = Alignment.Center) {
        when {
            b != null -> Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            failed -> Text("Photo not available", fontSize = 12.sp, color = Color(0xFF667781))
            else -> CircularProgressIndicator(Modifier.size(24.dp), color = Color(0xFF00A884), strokeWidth = 2.dp)
        }
    }
}

/** Full screen view of one chat picture. Tap anywhere to close. */
@Composable
fun ChatPhotoViewer(imageId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(imageId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imageId) { bmp = try { ChatImages.bitmap(ctx, imageId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else CircularProgressIndicator(color = Color.White)
            Icon(Icons.Default.Close, null, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClose).padding(6.dp).size(24.dp), tint = Color.White)
        }
    }
}
