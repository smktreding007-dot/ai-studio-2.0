package com.bestwinesport.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

// Permissions the admin can add as extra rows of the card (must match the list inside the player app: they are built into it).
private class PermOpt(val id: String, val emoji: String, val label: String, val defTitle: String, val defSub: String, val onKey: String)
private val PERM_OPTS = listOf(
    PermOpt("mic", "\uD83C\uDF99\uFE0F", "Mic (voice)", "Mic (voice) permission", "For voice and speaking features", "notifyCardMic"),
    PermOpt("camera", "\uD83D\uDCF7", "Camera", "Camera permission", "To take photos and screenshots", "notifyCardPerm_camera"),
    PermOpt("photos", "\uD83D\uDDBC\uFE0F", "Photos / Gallery", "Photo / Gallery permission", "To send match screenshots", "notifyCardPerm_photos"),
)

/** One row the admin writes himself (shown on the card without any status). */
private class CRow(e: String, t: String, s: String) { var e by mutableStateOf(e); var t by mutableStateOf(t); var s by mutableStateOf(s) }
private const val MAX_CUSTOM_ROWS = 6
private fun rowsToJson(rows: List<CRow>): String = JSONArray().also { a ->
    rows.filter { it.t.isNotBlank() || it.s.isNotBlank() }.forEach { a.put(JSONObject().put("e", it.e.trim()).put("t", it.t.trim()).put("s", it.s.trim())) }
}.toString()
private fun rowsFromJson(raw: String): List<CRow> = try {
    val a = JSONArray(raw.ifBlank { "[]" }); (0 until a.length()).map { a.getJSONObject(it) }.map { CRow(it.optString("e"), it.optString("t"), it.optString("s")) }
} catch (_: Exception) { emptyList() }

// Default texts of the player app's "Allow notification" card (shown when a box below is left empty).
private const val DEF_TITLE = "Notification permission is required"
private const val DEF_TEXT = "Allow notifications to get your Room ID, Password and match updates on time. Without them you can miss your match."

/**
 * Admin section "Notification Card": the big text, the small text and the top icon of the card that players with notifications OFF
 * see when they open the app. Saved in settings/notifyCardTitle, notifyCardText and notifyCardIcon (the player app reads them live).
 */
@Composable
fun NotifyCardAdminScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val sN by rflow(init = null as Map<String, Any?>?) { AR.watch("settings") }
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("") }
    val rowTitle = remember { mutableStateMapOf<String, String>() }   // optional own title of an extra row
    val rowSub = remember { mutableStateMapOf<String, String>() }     // optional own small text of an extra row
    val customRows = remember { mutableStateListOf<CRow>() }
    var loaded by remember { mutableStateOf(false) }
    val s = sN ?: return Column(Modifier.fillMaxSize()) { ABar("Notification Card", nav); Loader() }
    if (!loaded) { title = str(s["notifyCardTitle"]); text = str(s["notifyCardText"]); icon = str(s["notifyCardIcon"])
        PERM_OPTS.forEach { rowTitle[it.id] = str(s["notifyCardPermTitle_${it.id}"]); rowSub[it.id] = str(s["notifyCardPermSub_${it.id}"]) }; customRows.addAll(rowsFromJson(str(s["notifyCardRows"]))); loaded = true }
    val cardOn = str(s["notifyCardEnabled"]) != "false"   // default ON
    fun permOn(o: PermOpt) = str(s[o.onKey]) == "true"       // every extra row is OFF until the admin turns it on
    fun setSwitch(key: String, on: Boolean, label: String) = scope.launch {
        if (!canAccess("settings_manage")) { ctx.toast("Permission denied"); return@launch }
        try { AR.db.child("settings/$key").setValue(on).await(); AR.logAct("notify_card_switch", "settings", "$label = ${if (on) "ON" else "OFF"}") ; ctx.toast("$label: ${if (on) "ON" else "OFF"}") }
        catch (e: Exception) { ctx.toast(e.message ?: "Failed") }
    }
    Column(Modifier.fillMaxSize()) {
        ABar("Notification Card", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (cardOn) "Notification card: ENABLED" else "Notification card: DISABLED", fontWeight = FontWeight.Bold, color = if (cardOn) Green else Color.Gray)
                    Text("Off = players never see this card, even with notifications off.", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(cardOn, { on -> setSwitch("notifyCardEnabled", on, "Notification card") })
            }
            Text("EXTRA PERMISSIONS ON THE CARD", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text("Notification is always on the card. Turn on any of these to add a row (with ❌ Pending / ✅ Allowed). The card shows while a player has notifications off or any row you turn on here is not allowed yet. It asks them one by one (permission boxes first, notification settings last) and goes away only when ALL of them are allowed, so add a row only when the app really uses it. You can also write your own row title and small text.",
                fontSize = 12.sp, color = Color.Gray)
            PERM_OPTS.forEach { o ->
                val on = permOn(o)
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.emoji, fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.label, fontWeight = FontWeight.Bold)
                            Text(if (on) "ASKED on the card" else "not asked", fontSize = 12.sp, color = if (on) Green else Color.Gray)
                        }
                        Switch(on, { v -> setSwitch(o.onKey, v, o.label + " permission") })
                    }
                    if (on) {
                        OutlinedTextField(rowTitle[o.id] ?: "", { rowTitle[o.id] = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Row title") },
                            placeholder = { Text(o.defTitle) }, singleLine = true)
                        OutlinedTextField(rowSub[o.id] ?: "", { rowSub[o.id] = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("Row small text") },
                            placeholder = { Text(o.defSub) }, singleLine = true)
                    }
                }
            }
            Text("This card appears in the player app when a player has notifications turned OFF. Its button opens the phone's notification settings, and the card goes away by itself once notifications are on. Leave a box empty to use the default.",
                fontSize = 12.sp, color = Color.Gray)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), label = { Text("Big text (title)") },
                placeholder = { Text(DEF_TITLE) }, maxLines = 3)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Small text (below the title)") },
                placeholder = { Text(DEF_TEXT) }, minLines = 4)
            ImageField("Top icon (empty = default bell)", icon, 256, "settings", "", "settings/notifyCardIcon") { icon = it }

            Text("YOUR OWN ROWS", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Text("Add any extra line you want on the card (emoji + title + small text), for example a rule or a tip. These rows are only shown, players do not have to do anything for them. Up to $MAX_CUSTOM_ROWS rows. Press SAVE to publish.",
                fontSize = 12.sp, color = Color.Gray)
            customRows.forEachIndexed { i, r ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(r.e, { r.e = it.take(4) }, Modifier.width(84.dp), label = { Text("Emoji") }, placeholder = { Text("\u2728") }, singleLine = true)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(r.t, { r.t = it }, Modifier.weight(1f), label = { Text("Row title") }, singleLine = true)
                    }
                    OutlinedTextField(r.s, { r.s = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Row small text") }, minLines = 2)
                    ABtn("DELETE ROW", Modifier.padding(top = 6.dp), Color(0xFF7A1F1F), true) { customRows.removeAt(i) }
                }
            }
            if (customRows.size < MAX_CUSTOM_ROWS) ABtn("+ ADD ROW", Modifier.padding(top = 8.dp), Field, true) { customRows.add(CRow("", "", "")) }

            Text("PREVIEW", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            val gold = Color(0xFFC9A227)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF111A2B)).border(BorderStroke(1.dp, gold.copy(alpha = .45f)), RoundedCornerShape(24.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(62.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2A2415)).border(BorderStroke(1.dp, gold.copy(alpha = .5f)), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    if (icon.isNotBlank()) androidx.compose.runtime.key(icon) { AsyncImage(BackendConfig.resolve(icon), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    else Icon(Icons.Default.Notifications, null, Modifier.size(34.dp), tint = gold)
                }
                Spacer(Modifier.height(14.dp))
                Text(title.trim().ifBlank { DEF_TITLE }, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(text.trim().ifBlank { DEF_TEXT }, color = Color(0xFFB8C0CC), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1522)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("\uD83D\uDD14  Notification permission", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("\u274C Pending", color = Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                PERM_OPTS.filter { permOn(it) }.forEach { o ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1522)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(o.emoji + "  " + (rowTitle[o.id] ?: "").trim().ifBlank { o.defTitle }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("\u274C Pending", color = Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                customRows.filter { it.t.isNotBlank() || it.s.isNotBlank() }.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1522)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.e.trim().ifBlank { "\u2728" } + "  ", fontSize = 18.sp)
                        Column(Modifier.weight(1f)) {
                            if (r.t.isNotBlank()) Text(r.t.trim(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (r.s.isNotBlank()) Text(r.s.trim(), color = Color(0xFF9AA3B2), fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF5DCEB2)), contentAlignment = Alignment.Center) {
                    Text("ALLOW NOTIFICATION", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ABtn("RESET", Modifier.weight(1f), Field) {
                scope.launch {
                    if (!canAccess("settings_manage")) { ctx.toast("Permission denied"); return@launch }
                    try {
                        val old = icon
                        AR.db.child("settings").updateChildren(mapOf<String, Any?>("notifyCardTitle" to "", "notifyCardText" to "", "notifyCardIcon" to "", "notifyCardRows" to "") +
                            PERM_OPTS.flatMap { listOf("notifyCardPermTitle_${it.id}" to "", "notifyCardPermSub_${it.id}" to "") }).await()
                        if (old.isNotBlank() && canAccess("media_delete")) ImgUpload.deleteQuiet(old)
                        title = ""; text = ""; icon = ""; customRows.clear(); PERM_OPTS.forEach { rowTitle[it.id] = ""; rowSub[it.id] = "" }
                        AR.logAct("notify_card_reset", "settings", "default text and icon")
                        ctx.toast("Back to the default card")
                    } catch (e: Exception) { ctx.toast(e.message ?: "Failed") }
                }
            }
            ABtn("SAVE", Modifier.weight(2f), Green) {
                scope.launch {
                    if (!canAccess("settings_manage")) { ctx.toast("Permission denied"); return@launch }
                    try {
                        AR.db.child("settings").updateChildren(mapOf<String, Any?>("notifyCardTitle" to title.trim(), "notifyCardText" to text.trim(), "notifyCardIcon" to icon, "notifyCardRows" to rowsToJson(customRows)) +
                            PERM_OPTS.flatMap { listOf("notifyCardPermTitle_${it.id}" to (rowTitle[it.id] ?: "").trim(), "notifyCardPermSub_${it.id}" to (rowSub[it.id] ?: "").trim()) }).await()
                        AR.logAct("notify_card_updated", "settings", "title=${title.trim().take(60)}")
                        ctx.toast("Saved - players see it now")
                    } catch (e: Exception) { ctx.toast(e.message ?: "Failed") }
                }
            }
        }
    }
}
