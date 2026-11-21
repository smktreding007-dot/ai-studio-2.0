package com.bestwinesport.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * World chat moderation. Firebase: worldChat/messages (the chat), worldChat/reports (players' reports), worldChat/mutes/<uid> (muted players),
 * settings/worldChatOn ("false" = chat is off), settings/chatBlockedWords (extra blocked words, comma separated).
 * Old messages (older than 7 days, or beyond the newest 200) are removed automatically when this screen opens.
 */
private const val KEEP_MESSAGES = 200
private const val KEEP_DAYS = 7L

private suspend fun purgeOldChat() {
    try {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000
        val snapAll = AR.db.child("worldChat/messages").get().await()
        val rows = snapAll.children.mapNotNull { c ->
            val id = c.key ?: return@mapNotNull null
            id to num((c.value as? Map<*, *>)?.get("ts"))
        }.sortedByDescending { it.second }
        val voiceOf = HashMap<String, String>()
        snapAll.children.forEach { c -> val v = (c.value as? Map<*, *>)?.get("voiceId")?.toString().orEmpty(); if (v.isNotBlank() && !v.startsWith("clip_")) voiceOf[c.key ?: ""] = v }
        val ups = hashMapOf<String, Any?>()
        rows.forEachIndexed { i, (id, ts) ->
            if (i >= KEEP_MESSAGES || ts < cutoff) { ups["worldChat/messages/$id"] = null; voiceOf[id]?.let { ups["worldChat/voice/$it"] = null } }
        }
        if (ups.isNotEmpty()) AR.db.updateChildren(ups).await()
    } catch (_: Exception) { }
}

@Composable
fun WorldChatAdminScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/messages") }
    val reports by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/reports") }
    val mutes by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/mutes") }
    val chatOn by rflow(init = "") { AR.watch("settings").let { f -> kotlinx.coroutines.flow.flow { f.collect { emit(str(it["worldChatOn"])) } } } }
    var words by remember { mutableStateOf("") }
    var wordsLoaded by remember { mutableStateOf(false) }
    var muteFor by remember { mutableStateOf<Pair<String, String>?>(null) }   // uid to username
    var confirmDel by remember { mutableStateOf<String?>(null) }
    val off = !chatOn.trim().equals("true", true)
    val isOwner = Session.role == "admin"
    val online by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/online") }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    var title by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    val members by rflow(init = emptyMap<String, Any?>()) { AR.watch("worldChat/members") }
    var ownerText by remember { mutableStateOf("") }
    val onlineNow = online.values.count { num(it) > nowTick - 120_000.0 }

    LaunchedEffect(Unit) {
        purgeOldChat()
        try { words = str(AR.get("settings/chatBlockedWords")); title = str(AR.get("settings/worldChatTitle")); icon = str(AR.get("settings/worldChatIcon")); desc = str(AR.get("settings/worldChatDesc")) } catch (_: Exception) { }
        wordsLoaded = true
    }
    LaunchedEffect(Unit) { while (true) { nowTick = System.currentTimeMillis(); kotlinx.coroutines.delay(20_000) } }

    fun deleteMessage(id: String) = scope.launch {
        try {
            val ups = hashMapOf<String, Any?>("worldChat/messages/$id" to null)
            str(mp(messages[id])["voiceId"]).takeIf { it.isNotBlank() && !it.startsWith("clip_") }?.let { ups["worldChat/voice/$it"] = null }
            reports.filter { str(mp(it.value)["msgId"]) == id }.keys.forEach { ups["worldChat/reports/$it"] = null }
            AR.db.updateChildren(ups).await(); AR.logAct("chat_message_deleted", id); ctx.toast("Message deleted")
        } catch (e: Exception) { ctx.toast(e.message ?: "Could not delete") }
    }
    fun mute(uid: String, name: String, hours: Long) = scope.launch {
        try {
            val until = if (hours <= 0) 9_999_999_999_999L else System.currentTimeMillis() + hours * 60 * 60 * 1000
            AR.db.child("worldChat/mutes/$uid").setValue(mapOf("until" to until, "name" to name, "by" to (AR.auth.currentUser?.email ?: ""), "ts" to System.currentTimeMillis())).await()
            AR.logAct("chat_mute", uid, name); ctx.toast("$name muted")
        } catch (e: Exception) { ctx.toast(e.message ?: "Could not mute") }
    }

    fun sendAsOwner() = scope.launch {
        val t = ownerText.trim()
        if (t.isBlank()) return@launch
        try {
            val uid = Session.uid
            val uname = str(AR.get("users/$uid/username"))
            if (uname.isBlank()) { ctx.toast("Your admin account has no username, so it cannot chat"); return@launch }
            val id = AR.db.child("worldChat/messages").push().key ?: return@launch
            AR.db.updateChildren(hashMapOf<String, Any?>(
                "worldChat/messages/$id" to mapOf("uid" to uid, "username" to uname, "text" to t.take(200), "role" to "owner", "ts" to com.google.firebase.database.ServerValue.TIMESTAMP),
                "worldChat/last/$uid" to com.google.firebase.database.ServerValue.TIMESTAMP)).await()
            ownerText = ""
        } catch (e: Exception) { ctx.toast("Could not send. Wait 3 seconds and try again.") }
    }

    Column(Modifier.fillMaxSize()) {
        ABar("World chat", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                Text(if (off) "Chat is OFF (players see the globe icon)" else "Chat is ON (chat icon replaces the globe)", fontWeight = FontWeight.Bold, color = if (off) Red else Color(0xFF2ECC71))
                ABtn(if (off) "TURN CHAT ON" else "TURN CHAT OFF", Modifier.padding(top = 8.dp), if (off) Green else Red, true) {
                    scope.launch { try { AR.db.child("settings/worldChatOn").setValue(if (off) "true" else "false").await(); AR.logAct("chat_toggle", if (off) "on" else "off") } catch (e: Exception) { ctx.toast(e.message ?: "Failed") } }
                }
            }
            Text("Online now (chat screen open): $onlineNow   •   Joined members: ${members.size}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2ECC71))
            if (isOwner) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                Text("Chat look", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("The title and round icon players see on top of the chat (and the icon in the Home header).", fontSize = 11.sp, color = Color.Gray)
                OutlinedTextField(title, { if (it.length <= 40) title = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Chat title (empty = World Chat)") }, singleLine = true)
                OutlinedTextField(desc, { if (it.length <= 400) desc = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Group info text / rules (shown in Group info)") }, minLines = 3, maxLines = 8)
                ABtn("SAVE TITLE & TEXT", Modifier.padding(top = 6.dp), Purple, true, enabled = wordsLoaded) {
                    scope.launch { try { AR.db.child("settings/worldChatTitle").setValue(title.trim()).await(); AR.db.child("settings/worldChatDesc").setValue(desc.trim()).await(); ctx.toast("Saved") } catch (e: Exception) { ctx.toast(e.message ?: "Failed") } }
                }
                ImageField("Chat icon (round picture)", icon, 256, autoSavePath = "settings/worldChatIcon") { icon = it }
            }
            if (isOwner) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                Text("Write in chat as OWNER", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                OutlinedTextField(ownerText, { if (it.length <= 200) ownerText = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Message") }, maxLines = 3)
                ABtn("SEND", Modifier.padding(top = 6.dp), Green, true) { sendAsOwner() }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                Text("Extra blocked words", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Separate with a comma. Links, phone numbers and common bad words are already blocked.", fontSize = 11.sp, color = Color.Gray)
                OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("word1, word2, word3") }, minLines = 2)
                ABtn("SAVE WORDS", Modifier.padding(top = 6.dp), Purple, true, enabled = wordsLoaded) {
                    scope.launch { try { AR.db.child("settings/chatBlockedWords").setValue(words.trim()).await(); ctx.toast("Saved") } catch (e: Exception) { ctx.toast(e.message ?: "Failed") } }
                }
            }

            Text("REPORTED MESSAGES (${reports.size})", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (reports.isEmpty()) Text("No reports.", fontSize = 13.sp, color = Color.Gray)
            reports.entries.sortedByDescending { num(mp(it.value)["ts"]) }.forEach { (rid, v) ->
                val r = mp(v)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(10.dp)) {
                    Text(str(r["ofName"]) + ": " + str(r["text"]), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Reported by ${str(r["byName"])}  •  ${fdate(r["ts"])}", fontSize = 11.sp, color = Color.Gray)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ABtn("DELETE MSG", color = Color(0xFF7A1F1F), small = true) { deleteMessage(str(r["msgId"])) }
                        ABtn("MUTE", color = Purple, small = true) { muteFor = str(r["ofUid"]) to str(r["ofName"]) }
                        ABtn("IGNORE", color = Field, small = true) { scope.launch { try { AR.db.child("worldChat/reports/$rid").removeValue().await() } catch (e: Exception) { ctx.toast(e.message ?: "Failed") } } }
                    }
                }
            }

            Text("MUTED PLAYERS (${mutes.size})", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            if (mutes.isEmpty()) Text("Nobody is muted.", fontSize = 13.sp, color = Color.Gray)
            mutes.entries.forEach { (uid, v) ->
                val m = mp(v); val until = num(m["until"])
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(str(m["name"]).ifBlank { uid }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(if (until > 9_000_000_000_000.0) "Muted forever" else if (until < System.currentTimeMillis()) "Mute finished" else "Muted until ${fdate(until)}", fontSize = 11.sp, color = Color.Gray)
                    }
                    ABtn("UNMUTE", color = Green, small = true) { scope.launch { try { AR.db.child("worldChat/mutes/$uid").removeValue().await(); AR.logAct("chat_unmute", uid) } catch (e: Exception) { ctx.toast(e.message ?: "Failed") } } }
                }
            }

            Text("LATEST MESSAGES", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            val rows = messages.entries.sortedByDescending { num(mp(it.value)["ts"]) }.take(100)
            if (rows.isEmpty()) Text("No messages yet.", fontSize = 13.sp, color = Color.Gray)
            rows.forEach { (id, v) ->
                val m = mp(v)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(10.dp)) {
                    Text(str(m["username"]) + (when (str(m["role"])) { "owner" -> "  (OWNER)"; "admin", "staff" -> "  (STAFF)"; else -> "" }), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Purple)
                    Text(str(m["text"]), fontSize = 14.sp)
                    Text(fdate(m["ts"]), fontSize = 11.sp, color = Color.Gray)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ABtn("DELETE", color = Color(0xFF7A1F1F), small = true) { confirmDel = id }
                        ABtn("MUTE", color = Purple, small = true) { muteFor = str(m["uid"]) to str(m["username"]) }
                    }
                }
            }
        }
    }
    confirmDel?.let { id -> ConfirmDialog("Delete this message for everybody?", { confirmDel = null }) { confirmDel = null; deleteMessage(id) } }
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
}
