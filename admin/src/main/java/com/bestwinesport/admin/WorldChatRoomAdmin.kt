package com.bestwinesport.admin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The World chat ROOM inside the admin panel (the chat itself, like WhatsApp). The moderation settings (ON/OFF, title, blocked words, reports ...) stay in
 * WorldChatAdminScreen, opened with the gear icon on top.
 *  - The admin account writes as OWNER (gold OWNER tag above the name). Staff with the notifications_send permission write as ADMIN.
 *  - Text, stickers (Stickers.kt) and voice messages (max 60 seconds).
 *  - Long-press a message: delete, mute the player, make the player a chat ADMIN / remove him (owner only).
 * Firebase: worldChat/messages/<id> = { uid, username, text, role, ts, kind: text|sticker|voice, sticker, voiceId, durationMs }
 *           worldChat/voice/<voiceId>/c0..cN = the audio (base64 pieces). The message is written LAST, after the audio is saved.
 */
object ChatVoice {
    private const val CHUNK = 150_000

    suspend fun upload(file: File, forcedId: String? = null): String = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) throw Exception("The recording is empty")
        val id = forcedId ?: (AR.db.child("worldChat/voice").push().key ?: throw Exception("Could not create an id"))
        val n = (bytes.size + CHUNK - 1) / CHUNK
        if (n > 6) throw Exception("The recording is too long")
        try {
            for (i in 0 until n) {
                val from = i * CHUNK
                val to = minOf(bytes.size, from + CHUNK)
                AR.db.child("worldChat/voice/$id/c$i").setValue(Base64.encodeToString(bytes, from, to - from, Base64.NO_WRAP)).await()
            }
        } catch (e: CancellationException) {
            try { AR.db.child("worldChat/voice/$id").removeValue() } catch (_: Exception) { }
            throw e
        } catch (e: Exception) {
            try { AR.db.child("worldChat/voice/$id").removeValue().await() } catch (_: Exception) { }
            throw Exception("Could not save the voice. Publish the latest database.rules.json. (" + (e.message ?: "error") + ")")
        }
        id
    }

    /** The audio as a file on this phone (downloaded once, then cached). */
    suspend fun audioFile(ctx: Context, id: String): File? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        val f = File(dir, "wc_" + id.filter { it.isLetterOrDigit() || it == '_' || it == '-' } + ".m4a")
        if (f.exists() && f.length() > 0) return@withContext f
        val snap = AR.db.child("worldChat/voice/$id").get().await()
        val parts = snap.children.mapNotNull { c -> c.key?.removePrefix("c")?.toIntOrNull()?.let { it to (c.value?.toString() ?: "") } }.sortedBy { it.first }
        if (parts.isEmpty()) return@withContext null
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { out.write(Base64.decode(it.second, Base64.NO_WRAP)) }
        f.writeBytes(out.toByteArray())
        f
    }
}

private data class ClipItem(val id: String, val name: String, val dur: Long, val ts: Long)

private data class RMsg(val id: String, val uid: String, val username: String, val text: String, val ts: Long, val role: String,
                        val kind: String, val sticker: String, val voiceId: String, val dur: Long, val imageId: String = "")

private val RoomNameColors = listOf(Color(0xFF1F9D55), Color(0xFF8E44AD), Color(0xFFD35400), Color(0xFF2980B9), Color(0xFFC0392B), Color(0xFF16A085), Color(0xFFB7950B), Color(0xFF6C5CE7))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorldChatRoomScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val raw by rflow(init = emptyMap<String, Any?>()) { AR.watchQ(AR.db.child("worldChat/messages").orderByChild("ts").limitToLast(100)) }
    val settings by rflow(init = emptyMap<String, Any?>()) { AR.watch("settings") }
    val online by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/online") }
    val members by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/members") }
    val chatAdmins by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/admins") }
    val reports by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/reports") }
    val player = rememberVoicePlayer()
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showStickers by remember { mutableStateOf(false) }
    var myName by remember { mutableStateOf("") }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var actionFor by remember { mutableStateOf<RMsg?>(null) }
    var confirmDel by remember { mutableStateOf<RMsg?>(null) }
    var muteFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var showClips by remember { mutableStateOf(false) }
    var newClip by remember { mutableStateOf(false) }
    var clipAction by remember { mutableStateOf<ClipItem?>(null) }
    var renameFor by remember { mutableStateOf<ClipItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    val clipsRaw by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/clips") }

    val isOwner = Session.role == "admin"

    // One time (v2): the 26 voice clips are re-cut from the reference video (res/raw/vw01..vw26) in the SAME order as the buttons in the video.
    // The older, wrongly cut clips (vv01..vv26: started too early, cut off at the end, two clips joined, two missing) are removed first.
    // Clips the owner added himself are never touched.
    LaunchedEffect(isOwner) {
        if (!isOwner) return@LaunchedEffect
        val sp = ctx.getSharedPreferences("chat_clip_seed", Context.MODE_PRIVATE)
        if (sp.getBoolean("vw_v2", false)) return@LaunchedEffect
        try {
            // 1) remove the old wrong clips (tile + audio)
            val old = hashMapOf<String, Any?>()
            for (i in 1..26) { val k = "vv%02d".format(i); old["worldChat/clips/$k"] = null; old["worldChat/voice/clip_$k"] = null }
            AR.db.updateChildren(old).await()
            // 2) add the 26 correct clips in order (Voice 1 = first button of the video ... Voice 26 = last)
            var added = 0
            for (i in 1..26) {
                val key = "vw%02d".format(i)
                if (AR.get("worldChat/clips/$key") != null) continue
                val resId = ctx.resources.getIdentifier(key, "raw", ctx.packageName)
                if (resId == 0) continue
                if (added == 0) ctx.toast("Fixing the 26 voice clips...")
                val f = File(ctx.cacheDir, "seed_$key.m4a")
                ctx.resources.openRawResource(resId).use { inp -> f.outputStream().use { o -> inp.copyTo(o) } }
                val ms = withContext(Dispatchers.IO) {
                    val mmr = android.media.MediaMetadataRetriever()
                    try { mmr.setDataSource(f.absolutePath); mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                    finally { try { mmr.release() } catch (_: Exception) { } }
                }
                ChatVoice.upload(f, "clip_$key")
                AR.db.child("worldChat/clips/$key").setValue(mapOf("name" to "Voice $i", "durationMs" to ms, "ts" to ServerValue.TIMESTAMP)).await()
                try { f.delete() } catch (_: Exception) { }
                added++
            }
            sp.edit().putBoolean("vw_v2", true).apply()
            if (added > 0) ctx.toast("26 voice clips fixed")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ctx.toast("Voice clips: " + (e.message ?: "could not add")) }
    }
    val myUid = Session.uid
    val off = !str(settings["worldChatOn"]).trim().equals("true", true)
    val title = str(settings["worldChatTitle"]).ifBlank { "World chat" }
    val icon = str(settings["worldChatIcon"])
    val onlineNow = online.values.count { num(it) > nowTick - 120_000.0 }

    val list = remember(raw) {
        raw.entries.mapNotNull { (id, v) ->
            val m = mp(v)
            if (m.isEmpty()) return@mapNotNull null
            RMsg(id, str(m["uid"]), str(m["username"]), str(m["text"]), num(m["ts"]).toLong(), str(m["role"]),
                str(m["kind"]).ifBlank { "text" }, str(m["sticker"]), str(m["voiceId"]), num(m["durationMs"]).toLong(), str(m["imageId"]))
        }.sortedBy { it.ts }
    }

    val clips = remember(clipsRaw) { clipsRaw.entries.map { (id, v) -> ClipItem(id, str(mp(v)["name"]), num(mp(v)["durationMs"]).toLong(), num(mp(v)["ts"]).toLong()) }.sortedBy { it.ts } }

    LaunchedEffect(Unit) {
        try { myName = str(AR.get("users/$myUid/username")) } catch (_: Exception) { }
        while (true) { nowTick = System.currentTimeMillis(); delay(20_000) }
    }

    suspend fun post(kind: String, label: String, extra: Map<String, Any?> = emptyMap()): Boolean {
        if (myName.isBlank()) { ctx.toast("Your admin account has no username, so it cannot chat"); return false }
        val id = AR.db.child("worldChat/messages").push().key ?: return false
        val msg = hashMapOf<String, Any?>("uid" to myUid, "username" to myName, "text" to label.take(200), "role" to (if (isOwner) "owner" else "admin"),
            "kind" to kind, "ts" to ServerValue.TIMESTAMP)
        msg.putAll(extra)
        return try {
            AR.db.updateChildren(hashMapOf<String, Any?>("worldChat/messages/$id" to msg, "worldChat/last/$myUid" to ServerValue.TIMESTAMP)).await()
            listState.scrollToItem(0)
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ctx.toast(if (off) "Chat is OFF. Turn it ON first." else "Could not send. Wait 3 seconds and try again."); false }
    }

    fun sendText() {
        val t = text.trim()
        if (t.isBlank() || sending) return
        sending = true
        scope.launch { if (post("text", t)) text = ""; sending = false }
    }

    fun sendSticker(id: String) {
        if (sending) return
        sending = true
        showStickers = false
        scope.launch { post("sticker", "Sticker", mapOf("sticker" to id)); sending = false }
    }

    fun sendClip(c: ClipItem) {
        if (sending) return
        sending = true
        showClips = false
        scope.launch { post("voice", "Voice message", mapOf("voiceId" to "clip_" + c.id, "durationMs" to c.dur)); sending = false }
    }
    fun deleteClip(c: ClipItem) = scope.launch {
        try {
            AR.db.updateChildren(hashMapOf<String, Any?>("worldChat/clips/${c.id}" to null, "worldChat/voice/clip_${c.id}" to null)).await()
            AR.logAct("chat_clip_deleted", c.id, c.name); ctx.toast("Clip deleted")
        } catch (e: Exception) { ctx.toast(e.message ?: "Could not delete") }
    }

    // ---- photos ----
    var viewImage by remember { mutableStateOf("") }
    fun sendPhoto(uri: android.net.Uri) {
        if (sending) return
        sending = true; showStickers = false; showClips = false
        scope.launch {
            try {
                val data = ChatImages.prepare(ctx, uri)
                if (data == null) { ctx.toast("Could not read this picture"); return@launch }
                val imgId = ChatImages.upload(data)
                if (!post("image", "Photo", mapOf("imageId" to imgId))) { try { AR.db.child("worldChat/images/$imgId").removeValue() } catch (_: Exception) { } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ctx.toast("Could not send the photo. Publish the latest database.rules.json.") }
            finally { sending = false }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) sendPhoto(uri) }

    // ---- voice recording ----
    val rec = remember { VoiceRec(ctx) }
    fun beginRec() {
        player.stop()
        val err = rec.start()
        if (err != null) { ctx.toast(err); return }
        showStickers = false; showClips = false
        elapsed = 0L; recording = true
    }
    fun cancelRec() { if (!recording) return; recording = false; rec.cancel() }
    fun finishRec() {
        if (!recording) return
        recording = false
        val ms = rec.stop()
        val f = rec.file
        if (ms < 700L || f == null) { ctx.toast("Recording is too short. Speak a little longer."); return }
        sending = true
        scope.launch {
            try {
                val vid = ChatVoice.upload(f)
                post("voice", "Voice message", mapOf("voiceId" to vid, "durationMs" to ms))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ctx.toast(e.message ?: "Could not send the voice message") }
            finally { try { f.delete() } catch (_: Exception) { }; sending = false }
        }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) beginRec() else ctx.toast("Allow the microphone to record a voice message")
    }
    fun tapMic() {
        if (sending) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRec()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(recording) {
        if (recording) {
            val t0 = System.currentTimeMillis()
            while (recording) {
                elapsed = System.currentTimeMillis() - t0
                if (elapsed >= VoiceStore.MAX_MS) { finishRec(); break }
                delay(200)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { rec.cancel() } }

    // ---- moderation ----
    fun deleteMessage(m: RMsg) = scope.launch {
        try {
            val ups = hashMapOf<String, Any?>("worldChat/messages/${m.id}" to null)
            if (m.voiceId.isNotBlank() && !m.voiceId.startsWith("clip_")) ups["worldChat/voice/${m.voiceId}"] = null
            if (m.imageId.isNotBlank()) ups["worldChat/images/${m.imageId}"] = null
            reports.filter { str(mp(it.value)["msgId"]) == m.id }.keys.forEach { ups["worldChat/reports/$it"] = null }
            AR.db.updateChildren(ups).await(); AR.logAct("chat_message_deleted", m.id); ctx.toast("Message deleted")
        } catch (e: Exception) { ctx.toast(e.message ?: "Could not delete") }
    }
    fun mute(uid: String, name: String, hours: Long) = scope.launch {
        try {
            val until = if (hours <= 0) 9_999_999_999_999L else System.currentTimeMillis() + hours * 60 * 60 * 1000
            AR.db.child("worldChat/mutes/$uid").setValue(mapOf("until" to until, "name" to name, "by" to (AR.auth.currentUser?.email ?: ""), "ts" to System.currentTimeMillis())).await()
            AR.logAct("chat_mute", uid, name); ctx.toast("$name muted")
        } catch (e: Exception) { ctx.toast(e.message ?: "Could not mute") }
    }
    fun makeChatAdmin(uid: String, name: String) = scope.launch {
        try {
            AR.db.child("worldChat/admins/$uid").setValue(mapOf("name" to name.lowercase(), "ts" to System.currentTimeMillis())).await()
            AR.logAct("chat_admin_add", uid, name); ctx.toast("$name is now a chat STAFF")
        } catch (e: Exception) { ctx.toast(e.message ?: "Failed") }
    }
    fun removeChatAdmin(uid: String, name: String) = scope.launch {
        try {
            AR.db.child("worldChat/admins/$uid").removeValue().await()
            AR.logAct("chat_admin_remove", uid, name); ctx.toast("$name is no longer a chat STAFF")
        } catch (e: Exception) { ctx.toast(e.message ?: "Failed") }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFEFEAE2)).imePadding()) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Row(Modifier.fillMaxWidth().background(Bar).statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(22.dp).clickable { nav.back() })
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF128C7E)), contentAlignment = Alignment.Center) {
                    if (icon.isNotBlank()) AsyncImage(BackendConfig.resolve(icon), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text("\uD83D\uDCAC", fontSize = 16.sp)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Online: $onlineNow  \u2022  Members: ${members.size}", fontSize = 11.sp, maxLines = 1)
                }
                Icon(Icons.Default.Settings, "Chat settings", Modifier.size(26.dp).clickable { nav.go("worldchatadmin") })
            }
        }
        if (off) Row(Modifier.fillMaxWidth().background(Color(0xFFFFE0B2)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat is OFF. Players cannot see it and nobody can write.", Modifier.weight(1f), fontSize = 12.sp, color = Color(0xFF7A4B00))
            if (isOwner) ABtn("TURN ON", color = Green, small = true) {
                scope.launch { try { AR.db.child("settings/worldChatOn").setValue("true").await(); AR.logAct("chat_toggle", "on") } catch (e: Exception) { ctx.toast(e.message ?: "Failed") } }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) Text("No messages yet.", Modifier.align(Alignment.Center).padding(30.dp), color = Color(0xFF667781), textAlign = TextAlign.Center)
            else LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list.asReversed(), key = { it.id }) { m ->
                    val mine = m.uid == myUid
                    val isOwnerMsg = m.role == "owner"
                    val isAdm = m.role == "admin" || m.role == "staff" || m.uid in chatAdmins.keys
                    val special = isOwnerMsg || isAdm
                    val isSticker = m.kind == "sticker" && Stickers.find(m.sticker) != null
                    val bg = if (isSticker) Color.Transparent else if (isOwnerMsg) Color(0xFFFFF3C4) else if (isAdm) Color(0xFFE8FBF5) else if (mine) Color(0xFFD9FDD3) else Color.White
                    val shape = RoundedCornerShape(12.dp)
                    val timeText = (if (m.ts > 0) timeFmt.format(java.util.Date(m.ts)) else "")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(min = 70.dp, max = 300.dp).clip(shape).background(bg)
                            .then(if (special && !isSticker) (if (isOwnerMsg) Modifier.border(1.dp, Color(0xFFF2B01E), shape) else Modifier.border(1.5.dp, Color(0xFF6FD6BD), shape).drawBehind { drawRect(Color(0xFF1FA67A), size = Size(5.dp.toPx(), size.height)) }) else Modifier)
                            .combinedClickable(onClick = { }, onLongClick = { actionFor = m })
                            .padding(horizontal = 10.dp, vertical = 6.dp)) {
                            if (isOwnerMsg) Box(Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFFFFD54F)).padding(horizontal = 7.dp, vertical = 1.dp)) {
                                Text("\uD83D\uDC51 OWNER", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF7A4B00))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (mine) m.username + " (You)" else m.username, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                    color = if (isOwnerMsg) Color(0xFFB26A00) else if (isAdm) Color(0xFF0B6B57) else if (mine) Color(0xFF1F9D55) else RoomNameColors[Math.abs(m.username.hashCode()) % RoomNameColors.size])
                                if (!isOwnerMsg && isAdm) { Spacer(Modifier.width(6.dp)); Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFC83D)).padding(horizontal = 8.dp, vertical = 2.dp)) { Text("\uD83D\uDEE1\uFE0F Staff", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5A3A00)) } }
                            }
                            when {
                                isSticker -> Box(Modifier.padding(top = 2.dp)) { StickerView(Stickers.find(m.sticker)!!, 120.dp) }
                                m.kind == "image" && m.imageId.isNotBlank() -> ChatPhoto(m.imageId, onClick = { viewImage = m.imageId }, onLongClick = { actionFor = m })
                                m.kind == "voice" && m.voiceId.isNotBlank() -> {
                                    val key = "v_" + m.id
                                    val playing = player.playingId == key
                                    val loading = player.loadingId == key
                                    val progress = if (playing && m.dur > 0) (player.posMs.toFloat() / m.dur).coerceIn(0f, 1f) else 0f
                                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF00A884)).clickable { player.toggle(key) { ChatVoice.audioFile(ctx, m.voiceId) } }, contentAlignment = Alignment.Center) {
                                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                            else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(26.dp), tint = Color.White)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            LinearProgressIndicator(progress = { progress }, Modifier.width(150.dp).height(4.dp).clip(RoundedCornerShape(2.dp)), color = Color(0xFF00A884), trackColor = Color(0xFFCFD8DC))
                                            Text((if (playing) VoiceStore.fmt(player.posMs.toLong()) else "0:00") + " / " + VoiceStore.fmt(m.dur), Modifier.padding(top = 3.dp), fontSize = 11.sp, color = Color(0xFF667781))
                                        }
                                    }
                                }
                                else -> Text(m.text, fontSize = 15.sp, color = Color(0xFF111B21))
                            }
                            Text(timeText + (if (mine) "  \u2713" else ""), Modifier.align(Alignment.End), fontSize = 10.sp, color = Color(0xFF667781))
                        }
                    }
                }
            }
        }

        if (showStickers && !recording) {
            Column(Modifier.fillMaxWidth().background(Color(0xFFF7F8FA))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("\uD83D\uDD25 Stickers (tap to send)", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111B21))
                    Icon(Icons.Default.Close, null, Modifier.size(22.dp).clickable { showStickers = false }, tint = Color(0xFF111B21))
                }
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().height(250.dp), contentPadding = PaddingValues(6.dp)) {
                    gridItems(Stickers.all, key = { it.id }) { s ->
                        Box(Modifier.padding(3.dp).clickable { sendSticker(s.id) }, contentAlignment = Alignment.Center) { StickerView(s, 80.dp) }
                    }
                }
            }
        }

        if (showClips && !recording) Column(Modifier.fillMaxWidth().background(Color(0xFFF7F8FA))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83C\uDF99\uFE0F Voice clips  \u2022  tap = listen, long-press = send", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B6B57))
                Icon(Icons.Default.Close, null, Modifier.size(22.dp).clickable { showClips = false }, tint = Color(0xFF111B21))
            }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().height(250.dp), contentPadding = PaddingValues(6.dp)) {
                if (isOwner) item { ClipTileBox("\uFF0B NEW CLIP", false, false, Color(0xFFE6F4EF), { newClip = true }, { newClip = true }) }
                gridItems(clips, key = { it.id }) { c ->
                    val ck = "c_" + c.id
                    ClipTileBox(c.name + "  " + VoiceStore.fmt(c.dur), player.playingId == ck, player.loadingId == ck, Color(0xFFEDEDED),
                        { player.toggle(ck) { ChatVoice.audioFile(ctx, "clip_" + c.id) } }, { clipAction = c })
                }
            }
            if (clips.isEmpty()) Text(if (isOwner) "No clips yet. Press NEW CLIP to record one." else "No voice clips yet.", Modifier.fillMaxWidth().padding(bottom = 8.dp), fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }

        if (recording) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Delete, "Cancel", Modifier.clip(CircleShape).clickable { cancelRec() }.padding(6.dp).size(24.dp), tint = Color(0xFFC0392B))
                Spacer(Modifier.width(8.dp))
                Text("\uD83D\uDD34 Recording...  ${VoiceStore.fmt(elapsed)} / 1:00", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF111B21))
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF00A884)).clickable { finishRec() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send voice", Modifier.size(22.dp), tint = Color.White)
            }
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showStickers) "\u2328\uFE0F" else "\uD83D\uDE0A", Modifier.clip(CircleShape).clickable { showStickers = !showStickers; if (showStickers) showClips = false }.padding(horizontal = 4.dp), fontSize = 22.sp)
                Text("\uD83C\uDF99\uFE0F", Modifier.clip(CircleShape).clickable { showClips = !showClips; if (showClips) showStickers = false }.padding(horizontal = 4.dp), fontSize = 20.sp)
                Text("\uD83D\uDCF7", Modifier.clip(CircleShape).clickable { if (!sending) pickPhoto.launch("image/*") }.padding(horizontal = 4.dp), fontSize = 20.sp)
                Spacer(Modifier.width(6.dp))
                BasicTextField(text, { if (it.length <= 200) text = it }, Modifier.weight(1f), textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF111B21)), maxLines = 4, cursorBrush = SolidColor(Color(0xFF00A884)),
                    decorationBox = { inner -> if (text.isEmpty()) Text(if (isOwner) "Message as OWNER..." else "Message as STAFF...", fontSize = 16.sp, color = Color(0xFF8696A0)); inner() })
            }
            Spacer(Modifier.width(8.dp))
            val hasText = text.isNotBlank()
            Box(Modifier.size(50.dp).clip(CircleShape).background(if (sending) Color(0xFF8BC9B8) else Color(0xFF00A884)).clickable { if (hasText) sendText() else tapMic() }, contentAlignment = Alignment.Center) {
                if (hasText) Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(22.dp), tint = Color.White)
                else Icon(Icons.Default.Mic, "Record voice", Modifier.size(26.dp), tint = Color.White)
            }
        }
    }

    if (newClip) NewClipDialog({ newClip = false }) { newClip = false }
    clipAction?.let { c ->
        Dialog(onDismissRequest = { clipAction = null }) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(CardC).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\uD83C\uDF99\uFE0F " + c.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                ABtn("SEND IN CHAT", Modifier.fillMaxWidth(), Green, true) { clipAction = null; sendClip(c) }
                if (isOwner) ABtn("RENAME CLIP", Modifier.fillMaxWidth(), Purple, true) { clipAction = null; renameText = c.name; renameFor = c }
                if (isOwner) ABtn("DELETE CLIP", Modifier.fillMaxWidth(), Color(0xFF7A1F1F), true) { clipAction = null; deleteClip(c) }
                ABtn("CANCEL", Modifier.fillMaxWidth(), Field, true) { clipAction = null }
            }
        }
    }
    renameFor?.let { c ->
        Dialog(onDismissRequest = { renameFor = null }) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(CardC).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rename clip", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                OutlinedTextField(renameText, { if (it.length <= 30) renameText = it }, Modifier.fillMaxWidth(), label = { Text("Clip name") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ABtn("CANCEL", Modifier.weight(1f), Field, true) { renameFor = null }
                    ABtn("SAVE", Modifier.weight(1f), Purple, true, enabled = renameText.isNotBlank()) {
                        val n = renameText.trim(); renameFor = null
                        scope.launch {
                            try { AR.db.child("worldChat/clips/${c.id}/name").setValue(n).await(); AR.logAct("chat_clip_renamed", c.id, n); ctx.toast("Clip renamed") }
                            catch (e: Exception) { ctx.toast(e.message ?: "Could not rename") }
                        }
                    }
                }
            }
        }
    }
    confirmDel?.let { m -> ConfirmDialog("Delete this message for everybody?", { confirmDel = null }) { confirmDel = null; deleteMessage(m) } }
    muteFor?.let { (uid, name) ->
        Dialog(onDismissRequest = { muteFor = null }) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(CardC).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mute $name for", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                listOf("1 hour" to 1L, "24 hours" to 24L, "7 days" to 168L, "Forever" to 0L).forEach { (label, h) ->
                    ABtn(label, Modifier.fillMaxWidth(), Purple, true) { muteFor = null; mute(uid, name, h) }
                }
                ABtn("CANCEL", Modifier.fillMaxWidth(), Field, true) { muteFor = null }
            }
        }
    }
    if (viewImage.isNotBlank()) ChatPhotoViewer(viewImage) { viewImage = "" }
    actionFor?.let { m ->
        val own = m.uid == myUid
        val targetIsPanel = m.role == "owner" || m.role == "staff"
        val targetIsChatAdmin = m.uid in chatAdmins.keys || m.role == "admin"
        Dialog(onDismissRequest = { actionFor = null }) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(CardC).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.username + when { m.role == "owner" -> "  (OWNER)"; targetIsChatAdmin || m.role == "staff" -> "  (STAFF)"; else -> "" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(if (m.kind == "voice") "\uD83C\uDF99\uFE0F Voice message" else if (m.kind == "sticker") "Sticker" else if (m.kind == "image") "\uD83D\uDCF7 Photo" else m.text, fontSize = 13.sp, color = Color.Gray, maxLines = 3, overflow = TextOverflow.Ellipsis)
                ABtn("DELETE MESSAGE", Modifier.fillMaxWidth(), Color(0xFF7A1F1F), true) { actionFor = null; confirmDel = m }
                if (!own && !targetIsPanel && !targetIsChatAdmin) ABtn("MUTE PLAYER", Modifier.fillMaxWidth(), Purple, true) { actionFor = null; muteFor = m.uid to m.username }
                if (isOwner && !own && !targetIsPanel) {
                    if (m.uid in chatAdmins.keys) ABtn("REMOVE CHAT STAFF", Modifier.fillMaxWidth(), Color(0xFF7A4B00), true) { actionFor = null; removeChatAdmin(m.uid, m.username) }
                    else ABtn("MAKE CHAT STAFF", Modifier.fillMaxWidth(), Green, true) { actionFor = null; makeChatAdmin(m.uid, m.username) }
                }
                ABtn("CANCEL", Modifier.fillMaxWidth(), Field, true) { actionFor = null }
            }
        }
    }
}

/** One grey tile of the voice clip grid: tap = listen (turns green while playing), long-press = second action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipTileBox(label: String, playing: Boolean, loading: Boolean, idle: Color, onTap: () -> Unit, onLong: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.padding(3.dp).fillMaxWidth().height(46.dp).clip(shape).background(if (playing) Color(0xFF00A884) else idle)
        .border(1.dp, Color(0xFFC8C8C8), shape).combinedClickable(onClick = onTap, onLongClick = onLong).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF00A884), strokeWidth = 2.dp)
        else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = if (playing) Color.White else Color(0xFF00A884))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (playing) Color.White else Color(0xFF111B21), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Owner: record a short voice clip (max 15 seconds) or pick an audio file (max 30 seconds) and give it a name. It then shows in the clip panel of every player and admin. */
@Composable
private fun NewClipDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec = remember { VoiceRec(ctx) }
    val player = rememberVoicePlayer()
    var name by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var file by remember { mutableStateOf<File?>(null) }
    var dur by remember { mutableLongStateOf(0L) }
    var saving by remember { mutableStateOf(false) }
    val maxMs = 15_000L

    fun stopRec() {
        if (!recording) return
        recording = false
        val ms = rec.stop(); val f = rec.file
        if (ms < 500L || f == null) { ctx.toast("Recording is too short"); file = null } else { file = f; dur = ms }
    }
    fun beginRec() {
        player.stop(); file?.delete(); file = null
        val err = rec.start()
        if (err != null) { ctx.toast(err); return }
        elapsed = 0L; recording = true
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) beginRec() else ctx.toast("Allow the microphone to record") }
    fun tapRecord() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRec() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Owner can also add a voice clip from an audio file already on the phone (max 30 s).
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val f = File(ctx.cacheDir, "clip_import_" + System.currentTimeMillis() + ".m4a")
                ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val mmr = android.media.MediaMetadataRetriever()
                val ms = try { mmr.setDataSource(f.absolutePath); mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { try { mmr.release() } catch (_: Exception) { } }
                if (ms < 500L) { f.delete(); ctx.toast("This is not a valid audio file") }
                else if (ms > 30_000L || f.length() > 800_000L) { f.delete(); ctx.toast("Audio is too long (max 30 seconds / 800 KB)") }
                else { player.stop(); file?.delete(); file = f; dur = ms }
            } catch (e: Exception) { ctx.toast("Could not read this audio file") }
        }
    }
    LaunchedEffect(recording) {
        if (recording) {
            val t0 = System.currentTimeMillis()
            while (recording) { elapsed = System.currentTimeMillis() - t0; if (elapsed >= maxMs) { stopRec(); break }; delay(200) }
        }
    }
    DisposableEffect(Unit) { onDispose { rec.cancel() } }

    Dialog(onDismissRequest = { if (!saving) { rec.cancel(); onDismiss() } }) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(CardC).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New voice clip", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(name, { if (it.length <= 30) name = it }, Modifier.fillMaxWidth(), label = { Text("Clip name") }, singleLine = true)
            when {
                recording -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("\uD83D\uDD34 ${VoiceStore.fmt(elapsed)} / 0:15", fontWeight = FontWeight.Bold)
                    ABtn("STOP", color = Red, small = true) { stopRec() }
                }
                file != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ABtn(if (player.playingId == "preview") "STOP" else "PLAY", color = Purple, small = true) { player.toggle("preview") { file } }
                    ABtn("RE-RECORD", color = Green, small = true) { tapRecord() }
                    ABtn("PICK FILE", color = Green, small = true) { pickAudio.launch("audio/*") }
                    Text(VoiceStore.fmt(dur), fontSize = 12.sp, color = Color.Gray)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ABtn("RECORD (max 15 s)", Modifier.fillMaxWidth(), Green, true) { tapRecord() }
                    ABtn("PICK AUDIO FILE (max 30 s)", Modifier.fillMaxWidth(), Purple, true) { pickAudio.launch("audio/*") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ABtn("CANCEL", Modifier.weight(1f), Field, true, enabled = !saving) { rec.cancel(); player.stop(); file?.delete(); onDismiss() }
                ABtn(if (saving) "SAVING..." else "SAVE", Modifier.weight(1f), Purple, true, enabled = file != null && name.isNotBlank() && !saving && !recording) {
                    val f = file ?: return@ABtn
                    saving = true; player.stop()
                    scope.launch {
                        try {
                            val key = AR.db.child("worldChat/clips").push().key ?: throw Exception("Could not create an id")
                            ChatVoice.upload(f, "clip_$key")
                            AR.db.child("worldChat/clips/$key").setValue(mapOf("name" to name.trim(), "durationMs" to dur, "ts" to ServerValue.TIMESTAMP)).await()
                            AR.logAct("chat_clip_added", key, name.trim())
                            try { f.delete() } catch (_: Exception) { }
                            ctx.toast("Clip saved"); onSaved()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { ctx.toast(e.message ?: "Could not save the clip") }
                        finally { saving = false }
                    }
                }
            }
        }
    }
}
