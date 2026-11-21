package com.bestwinesport.staff

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Voice messages (admin -> players).
 * The recording (AAC .m4a, mono, about 240 KB per minute) is cut in small pieces and saved in Firebase:
 *   voiceData/<id>/c0..cN   the audio pieces (base64)
 *   voiceMessages/<id>      info for "all players" / "players of one match"
 *   voiceDirect/<uid>/<id>  info for "one player"
 * The info node is written LAST, so a player never sees a half uploaded message. The player app lists them in Account -> Voice messages
 * and the push notification (type=voice) opens that list.
 */
object VoiceStore {
    const val MAX_MS = 60_000L
    /** A voice message lives 24 hours (like a normal notification). Nobody has to delete it by hand. */
    const val TTL_MS = 24L * 60 * 60 * 1000
    private const val CHUNK = 150_000

    fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

    suspend fun upload(file: File, durationMs: Long, title: String, body: String, target: String, matchKey: String, toUid: String, toUser: String): String = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) throw Exception("The recording is empty")
        val id = AR.db.child("voiceMessages").push().key ?: throw Exception("Could not create an id")
        val n = (bytes.size + CHUNK - 1) / CHUNK
        try {
            for (i in 0 until n) {
                val from = i * CHUNK; val to = minOf(bytes.size, from + CHUNK)
                AR.db.child("voiceData/$id/c$i").setValue(Base64.encodeToString(bytes, from, to - from, Base64.NO_WRAP)).await()
            }
            val meta = hashMapOf<String, Any?>("title" to title.trim(), "body" to body.trim(), "durationMs" to durationMs, "chunks" to n, "size" to bytes.size,
                "target" to target, "matchKey" to matchKey, "toUid" to toUid, "toUser" to toUser, "ts" to System.currentTimeMillis(), "by" to (AR.auth.currentUser?.email ?: ""))
            AR.db.child(if (target == "user") "voiceDirect/$toUid/$id" else "voiceMessages/$id").setValue(meta).await()
        } catch (e: CancellationException) {
            try { AR.db.child("voiceData/$id").removeValue() } catch (_: Exception) { }
            throw e
        } catch (e: Exception) {
            try { AR.db.child("voiceData/$id").removeValue().await() } catch (_: Exception) { }
            throw Exception("Could not save the voice message. Publish the latest database.rules.json. (" + (e.message ?: "error") + ")")
        }
        id
    }

    /** Not used by the screens any more (players delete voices from their own list). Kept for manual clean-up. */
    suspend fun delete(id: String, direct: Boolean, toUid: String) {
        val ups = hashMapOf<String, Any?>("voiceData/$id" to null)
        ups[if (direct) "voiceDirect/$toUid/$id" else "voiceMessages/$id"] = null
        AR.db.updateChildren(ups).await()
        AR.logAct("voice_deleted", id)
    }

    /** Removes every voice message older than 24 hours (info + audio). Runs by itself when the Send screen opens and after each send. */
    suspend fun purgeExpired() {
        try {
            val cutoff = System.currentTimeMillis() - TTL_MS
            fun ts(v: Any?): Long = ((v as? Map<*, *>)?.get("ts") as? Number)?.toLong() ?: 0L
            val ups = hashMapOf<String, Any?>()
            AR.db.child("voiceMessages").get().await().children.forEach { c -> val id = c.key ?: return@forEach; if (ts(c.value) < cutoff) { ups["voiceMessages/$id"] = null; ups["voiceData/$id"] = null } }
            AR.db.child("voiceDirect").get().await().children.forEach { u -> val uid = u.key ?: return@forEach
                u.children.forEach entry@ { c -> val id = c.key ?: return@entry; if (ts(c.value) < cutoff) { ups["voiceDirect/$uid/$id"] = null; ups["voiceData/$id"] = null } } }
            if (ups.isNotEmpty()) AR.db.updateChildren(ups).await()
        } catch (_: Exception) { }
    }

    /** The audio as a file on this phone (downloaded once, then cached). */
    suspend fun audioFile(ctx: Context, id: String): File? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        val f = File(dir, id.filter { it.isLetterOrDigit() || it == '_' || it == '-' } + ".m4a")
        if (f.exists() && f.length() > 0) return@withContext f
        val snap = AR.db.child("voiceData/$id").get().await()
        val parts = snap.children.mapNotNull { c -> c.key?.removePrefix("c")?.toIntOrNull()?.let { it to (c.value?.toString() ?: "") } }.sortedBy { it.first }
        if (parts.isEmpty()) return@withContext null
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { out.write(Base64.decode(it.second, Base64.NO_WRAP)) }
        f.writeBytes(out.toByteArray())
        f
    }
}

/** Microphone recorder. start() -> null when recording, else an error text. stop() -> length in ms, or -1 when the recording is too short / failed. */
class VoiceRec(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var startedAt = 0L
    var file: File? = null
        private set

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    fun start(): String? {
        cancel()
        return try {
            val f = File(ctx.cacheDir, "rec_" + System.currentTimeMillis() + ".m4a")
            val r = newRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration((VoiceStore.MAX_MS + 1000).toInt())
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = System.currentTimeMillis()
            null
        } catch (e: Exception) {
            try { rec?.release() } catch (_: Exception) { }
            rec = null; file = null
            "Could not start the microphone"
        }
    }

    fun stop(): Long {
        val r = rec ?: return -1
        rec = null
        val spoken = System.currentTimeMillis() - startedAt
        return try {
            r.stop(); r.release()
            minOf(realLength(file) ?: spoken, VoiceStore.MAX_MS)
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            file?.delete(); file = null
            -1
        }
    }

    /** Stops a running recording and throws it away. */
    fun cancel() {
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (_: Exception) { }
        try { r.release() } catch (_: Exception) { }
        file?.delete(); file = null
    }

    private fun realLength(f: File?): Long? {
        if (f == null) return null
        val m = MediaMetadataRetriever()
        return try { m.setDataSource(f.absolutePath); m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
        catch (_: Exception) { null } finally { try { m.release() } catch (_: Exception) { } }
    }
}

/** One voice plays at a time (preview of the new recording, or a sent message). */
class VoicePlayer(private val ctx: Context, private val scope: CoroutineScope) {
    var playingId by mutableStateOf<String?>(null)
        private set
    var loadingId by mutableStateOf<String?>(null)
        private set
    var posMs by mutableStateOf(0)
        private set
    private var mp: MediaPlayer? = null
    private var job: Job? = null

    fun toggle(key: String, load: suspend () -> File?) {
        if (playingId == key || loadingId == key) { stop(); return }
        stop()
        loadingId = key
        job = scope.launch {
            val f = try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ensureActive()
            if (f == null) { loadingId = null; ctx.toast("Could not load the voice message"); return@launch }
            val p = MediaPlayer()
            try {
                p.setDataSource(f.absolutePath)
                p.setOnCompletionListener { stop() }
                p.setOnErrorListener { _, _, _ -> stop(); true }
                p.prepare()
                mp = p
                p.start()
                playingId = key; loadingId = null
                while (mp === p) {
                    try { if (!p.isPlaying) break; posMs = p.currentPosition } catch (_: Exception) { break }
                    delay(200)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (mp !== p) { try { p.release() } catch (_: Exception) { } }; stop(); ctx.toast("This voice cannot be played") }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        val p = mp; mp = null
        if (p != null) { try { p.stop() } catch (_: Exception) { }; try { p.release() } catch (_: Exception) { } }
        playingId = null; loadingId = null; posMs = 0
    }
}

@Composable
fun rememberVoicePlayer(): VoicePlayer {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { VoicePlayer(ctx, scope) }
    DisposableEffect(p) { onDispose { p.stop() } }
    return p
}

/** The green recorder box of the Send notification screen: record (max 60 s) -> listen -> re-record / remove -> SEND. */
@Composable
fun VoiceRecorderCard(file: File?, durationMs: Long, player: VoicePlayer, onChange: (File?, Long) -> Unit) {
    val ctx = LocalContext.current
    val rec = remember { VoiceRec(ctx) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }

    fun begin() {
        player.stop()
        file?.delete()
        onChange(null, 0L)
        val err = rec.start()
        if (err != null) { ctx.toast(err); return }
        elapsed = 0L; recording = true
    }
    fun finish() {
        if (!recording) return
        recording = false
        val ms = rec.stop()
        val f = rec.file
        if (ms < 700L || f == null) { ctx.toast("Recording is too short. Speak a little longer."); onChange(null, 0L) } else onChange(f, ms)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else ctx.toast("Allow the microphone to record a voice message")
    }
    fun tapRecord() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(recording) {
        if (recording) {
            val t0 = System.currentTimeMillis()
            while (recording) {
                elapsed = System.currentTimeMillis() - t0
                if (elapsed >= VoiceStore.MAX_MS) { finish(); break }
                delay(200)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { rec.cancel() } }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(CardC)
        .border(1.dp, Color(0xFF1F7A5A), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("\uD83C\uDF99\uFE0F Voice message (max 60 seconds)", color = Orange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        when {
            recording -> {
                Text("\uD83D\uDD34 Recording... speak now", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color.Gray)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${VoiceStore.fmt(elapsed)} / 1:00", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    ABtn("STOP RECORDING", color = Red, small = true) { finish() }
                }
            }
            file != null -> {
                val playing = player.playingId == "preview"
                Text("\u2705 Voice ready (${VoiceStore.fmt(durationMs)}). Listen to it, or press SEND.", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color.Gray)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ABtn(if (player.loadingId == "preview") "..." else if (playing) "STOP" else "PLAY", color = Purple, small = true) { player.toggle("preview") { file } }
                    ABtn("RE-RECORD", color = Green, small = true) { tapRecord() }
                    ABtn("REMOVE", color = Color(0xFF7A1F1F), small = true) { player.stop(); file.delete(); onChange(null, 0L) }
                    if (playing) Text("${VoiceStore.fmt(player.posMs.toLong())} / ${VoiceStore.fmt(durationMs)}", fontSize = 12.sp, color = Color.Gray)
                }
            }
            else -> {
                Text("Press the button and speak. Players get it like a normal notification and can listen again in Account -> Voice messages. Only the player can delete it from his own list.", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color.Gray)
                ABtn("RECORD VOICE", Modifier.padding(top = 8.dp), Green, true) { tapRecord() }
            }
        }
    }
}
