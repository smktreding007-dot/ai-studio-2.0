package com.bestwinesport.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@Composable
fun VirtualSlotScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val matchesN by rflow(init = null as Map<String, Any?>?) { AR.watch("matches") }
    val gamesAll by rflow(init = emptyMap<String, Any?>()) { AR.watch("games") }
    var gameF by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var nameCount by remember { mutableIntStateOf(1) }
    val names = remember { mutableStateListOf("") }
    var busy by remember { mutableStateOf(false) }
    var removeKey by remember { mutableStateOf<String?>(null) }

    BackHandler(selected != null) { selected = null }
    BackHandler(selected == null && gameF.isNotBlank()) { gameF = ""; query = "" }
    val matches = matchesN ?: return Column(Modifier.fillMaxSize()) { ABar("Virtual slots", nav); Loader() }
    val q = query.trim()
    val rows = matches.entries.filter { e ->
        val m = mp(e.value)
        (gameF.isBlank() || str(m["gameId"]) == gameF) && (q.isBlank() || str(m["matchNo"]).contains(q, true) || str(m["matchId"]).contains(q, true) || str(m["title"]).contains(q, true))
    }.sortedByDescending { num(mp(it.value)["startTime"]) }

    if (selected == null) {
        Column(Modifier.fillMaxSize()) {
            ABar("Virtual slot management", nav)
            Text("Only the Admin account can create or remove virtual slots.", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.Gray, fontSize = 12.sp)
            // Same as the Match screen: first the game tiles, tap a game to see only its matches.
            if (gameF.isBlank()) { GameTiles(gamesAll.onlyAllowedGames(), matches) { gameF = it }; return@Column }
            GameHeader(str(mp(gamesAll[gameF])["name"]).ifBlank { "Matches" }) { gameF = ""; query = "" }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 14.dp), label = { Text("Search match number (example: 50)") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
            if (rows.isEmpty()) Text("No match found in this game.", Modifier.padding(24.dp), color = Color.Gray)
            LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.key }) { entry ->
                    val m = mp(entry.value)
                    val filled = mp(m["slots"]).size
                    val total = num(m["maxPlayers"]).toInt().coerceAtLeast(1)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardC).clickable { selected = entry.key }.padding(14.dp)) {
                        Text("${str(m["title"])} #${str(m["matchNo"])}", fontWeight = FontWeight.Bold)
                        Text("${str(m["gameId"])} • ${str(m["mode"])} • ${str(m["status"])}", color = Purple, fontSize = 12.sp)
                        Text("Match ID: ${str(m["matchId"]).ifBlank { entry.key }}", color = Color.Gray, fontSize = 12.sp)
                        Text("Filled: $filled/$total  •  Empty: ${(total - filled).coerceAtLeast(0)}", color = if (filled >= total) Orange else Green, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    } else {
        VirtualSlotDetail(nav, selected!!, matches[selected!!].let { mp(it) }, names, nameCount, { n ->
            nameCount = n.coerceIn(1, 50)
            while (names.size < nameCount) names.add("")
            while (names.size > nameCount) names.removeAt(names.lastIndex)
        }, { i, v -> names[i] = v }, busy, { busy = it }, { msg -> ctx.toast(msg) }, { selected = null }, removeKey, { removeKey = it }, scope)
    }
}

@Composable
private fun VirtualSlotDetail(
    nav: NavController, mid: String, initial: Map<String, Any?>, names: SnapshotStateList<String>, nameCount: Int,
    setCount: (Int) -> Unit, setName: (Int, String) -> Unit, busy: Boolean, setBusy: (Boolean) -> Unit,
    toast: (String) -> Unit, close: () -> Unit, removeKey: String?, setRemoveKey: (String?) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val matchN by rflow(mid, init = initial) { AR.watch("matches/$mid") }
    val m = matchN
    val slots = mp(m["slots"])
    val total = num(m["maxPlayers"]).toInt().coerceAtLeast(1)
    val filled = slots.size
    Column(Modifier.fillMaxSize()) {
        ABar("Virtual slots • #${str(m["matchNo"])}", nav) { TextButton(close) { Text("CLOSE", color = Color.White) } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Text("${str(m["title"])}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Game: ${str(m["gameId"])} • Mode: ${str(m["mode"])} • Status: ${str(m["status"])}", color = Color.Gray, fontSize = 12.sp)
            Text("Match ID: ${str(m["matchId"]).ifBlank { mid }}", color = Color.Gray, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VStat(filled.toString(), "Filled", Modifier.weight(1f)); VStat(total.toString(), "Total", Modifier.weight(1f)); VStat((total - filled).coerceAtLeast(0).toString(), "Empty", Modifier.weight(1f))
            }
            Text("Current slots", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (slots.isEmpty()) Text("No booked slots yet.", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
            slots.entries.sortedBy { slotOrder(it.key) }.forEach { e ->
                val s = mp(e.value); val isVirtual = str(s["type"]).equals("VIRTUAL", true)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(CardC).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${slotLabel(e.key)}  •  ${str(s["username"])}", fontWeight = FontWeight.SemiBold)
                        Text(if (isVirtual) "VIRTUAL" else "REAL", color = if (isVirtual) Orange else Green, fontSize = 11.sp)
                    }
                    if (isVirtual) IconButton({ setRemoveKey(e.key) }) { Icon(Icons.Default.Delete, "Remove virtual slot", tint = Color(0xFFFF6B6B)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Fill virtual slots", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Names are assigned to the first available positions. They appear booked in the User App, but no Firebase user or notification target is created.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Number of slots", Modifier.weight(1f))
                OutlinedButton({ setCount(nameCount - 1) }, enabled = nameCount > 1) { Text("−") }
                Text("$nameCount", Modifier.padding(horizontal = 14.dp), fontWeight = FontWeight.Bold)
                OutlinedButton({ setCount(nameCount + 1) }, enabled = nameCount < (total - filled).coerceAtLeast(1)) { Text("+") }
            }
            repeat(nameCount) { i ->
                OutlinedTextField(names.getOrNull(i) ?: "", { setName(i, it.take(40)) }, Modifier.fillMaxWidth().padding(vertical = 3.dp), label = { Text("Virtual player ${i + 1}") }, singleLine = true)
            }
            ABtn(if (busy) "FILLING..." else "FILL SLOTS", Modifier.fillMaxWidth().padding(top = 8.dp), Green, enabled = !busy && filled < total) {
                setBusy(true)
                scope.launch {
                    val result = AR.fillVirtualSlots(mid, names.take(nameCount))
                    setBusy(false); toast(result)
                    if (result.startsWith("Added")) { names.replaceAll { "" }; setCount(1) }
                }
            }
        }
    }
    removeKey?.let { key ->
        ConfirmDialog("Remove virtual player from ${slotLabel(key)}? Real players are never removed by this action.", { setRemoveKey(null) }) {
            setRemoveKey(null); setBusy(true)
            scope.launch { val result = AR.removeVirtualSlot(mid, key); setBusy(false); toast(result) }
        }
    }
}

private fun slotOrder(key: String): Int = Regex("t(\\d+)_?(\\d*)").find(key)?.let { it.groupValues[1].toIntOrNull()?.times(10)?.plus(it.groupValues[2].toIntOrNull() ?: 0) } ?: Int.MAX_VALUE
private fun slotLabel(key: String): String = key.removePrefix("t").replace("_", "-").let { "Slot $it" }

@Composable
private fun VStat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(CardC).padding(10.dp)) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.Gray, fontSize = 11.sp)
    }
}
