package com.bestwinesport.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Admin -> Lucky Wheel. Everything the player sees in the Lucky Wheel is controlled from here:
 * on/off, the match task, daily limit, minimum days between spins, the reward lock (all players / one player),
 * the wheel rewards, and the games where the Spin banner is shown.
 * Data: spinConfig (settings), spinUserCfg/<uid> (one-player lock), spinLog (recent spins, written by the backend).
 */
private const val DEFAULT_SEGMENTS_TEXT = "2:30\n5:25\n10:18\n25:12\n50:8\n100:4\n500:2\n1000:1"

private fun spinSegText(raw: Any?): String {
    val list: List<Any?> = when (raw) {
        is List<*> -> raw
        is Map<*, *> -> raw.values.toList()
        else -> emptyList()
    }
    val lines = list.mapNotNull { item ->
        val m = mp(item)
        val a = num(m["amount"])
        if (a <= 0.0) null else money(a) + ":" + money(num(m["weight"]))
    }
    return if (lines.size >= 2) lines.joinToString("\n") else DEFAULT_SEGMENTS_TEXT
}

/** "amount:chance" per line -> list for the database, or null when something is wrong. */
private fun parseSegText(text: String): List<Map<String, Any>>? {
    val out = ArrayList<Map<String, Any>>()
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val parts = line.split(':', ',', ' ').filter { it.isNotBlank() }
        val amount = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val weight = if (parts.size >= 2) (parts[1].toDoubleOrNull() ?: return null) else 1.0
        if (amount <= 0.0 || amount > 100000.0 || weight < 0.0) return null
        out.add(mapOf("amount" to amount, "weight" to weight))
    }
    return if (out.size in 2..12 && out.any { (it["weight"] as Double) > 0.0 }) out else null
}

@Composable
fun SpinAdminScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cN by rflow(init = null as Map<String, Any?>?) { AR.watch("spinConfig") }
    val games by rflow(init = emptyMap<String, Any?>()) { AR.watch("games") }
    val overrides by rflow(init = emptyMap<String, Any?>()) { AR.watch("spinUserCfg") }
    val bonusMap by rflow(init = emptyMap<String, Any?>()) { AR.watch("spinBonus") }
    val log by rflow(init = emptyMap<String, Any?>()) { AR.watchQ(AR.db.child("spinLog").orderByChild("ts").limitToLast(30)) }
    val vals = remember { mutableStateMapOf<String, String>() }
    var countPast by remember { mutableStateOf(false) }
    var creditTo by remember { mutableStateOf("deposited") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, Map<String, Any?>>>>(emptyList()) }
    var editUid by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editLock by remember { mutableStateOf("") }
    var editBlocked by remember { mutableStateOf(false) }
    var giveSpins by remember { mutableStateOf("") }
    var histUid by remember { mutableStateOf<String?>(null) }
    var histName by remember { mutableStateOf("") }
    var histRows by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var histState by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var histLoading by remember { mutableStateOf(false) }
    val bonusNames = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(bonusMap.keys.toList()) {
        // Look up the username of every player holding free spins (the list would otherwise show a long id).
        for (uid in bonusMap.keys) {
            if (bonusNames.containsKey(uid)) continue
            try {
                val n = AR.db.child("users").child(uid).child("username").get().await().value?.toString().orEmpty()
                bonusNames[uid] = n.ifBlank { uid.take(8) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                bonusNames[uid] = uid.take(8)
            }
        }
    }
    val c = cN ?: return Column(Modifier.fillMaxSize()) { ABar("Lucky Wheel", nav); Loader() }

    if (!loaded) {
        // What is already live is filled in, so you only change what you need and press SAVE.
        vals["matchesRequired"] = str(c["matchesRequired"]).ifBlank { "8" }
        vals["minEntryFee"] = str(c["minEntryFee"]).ifBlank { "10" }
        vals["dailyLimit"] = str(c["dailyLimit"]).ifBlank { "3" }
        vals["cooldownDays"] = str(c["cooldownDays"]).ifBlank { "0" }
        vals["lockAll"] = str(c["lockAll"]).ifBlank { "0" }
        vals["segments"] = spinSegText(c["segments"])
        countPast = c["countPast"] == true
        creditTo = if (c["creditTo"] == "winning") "winning" else "deposited"
        loaded = true
    }
    val gameOn = mp(c["games"])

    suspend fun write(m: Map<String, Any?>, okText: String, logDetail: String) {
        if (!canAccess("spin_manage")) { ctx.toast("Permission denied"); return }
        busy = true
        try {
            AR.db.child("spinConfig").updateChildren(m).await()
            AR.logAct("spin_config", "spinConfig", logDetail)
            ctx.toast(okText)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ctx.toast(e.message ?: "Could not save")
        } finally {
            busy = false
        }
    }

    fun saveRules() {
        val mr = vals["matchesRequired"]?.trim()?.toDoubleOrNull()
        val fee = vals["minEntryFee"]?.trim()?.toDoubleOrNull()
        val daily = vals["dailyLimit"]?.trim()?.toDoubleOrNull()
        val cool = vals["cooldownDays"]?.trim()?.toDoubleOrNull()
        val lock = vals["lockAll"]?.trim()?.toDoubleOrNull()
        if (mr == null || mr < 0 || mr > 100) { ctx.toast("Matches to play: enter 0 to 100"); return }
        if (fee == null || fee < 0) { ctx.toast("Entry fee: enter 0 or more"); return }
        if (daily == null || daily < 0 || daily > 100) { ctx.toast("Daily spins: enter 0 to 100 (0 = no limit)"); return }
        if (cool == null || cool < 0 || cool > 365) { ctx.toast("Minimum days between spins: enter 0 to 365"); return }
        if (lock == null || lock < 0) { ctx.toast("Reward lock: enter 0 or more (0 = no lock)"); return }
        val segs = parseSegText(vals["segments"] ?: "")
        if (segs == null) { ctx.toast("Wheel rewards: 2 to 12 lines like  5:25  (coin:chance). Coins must be more than 0"); return }
        scope.launch {
            write(
                mapOf(
                    "matchesRequired" to mr.toLong(), "minEntryFee" to fee, "dailyLimit" to daily.toLong(), "cooldownDays" to cool,
                    "lockAll" to lock, "maxReward" to segs.maxOf { (it["amount"] as? Double) ?: 0.0 }, "countPast" to countPast, "creditTo" to creditTo, "segments" to segs
                ),
                "Saved", "matches=${mr.toLong()} fee=$fee daily=${daily.toLong()} cooldown=$cool lock=$lock"
            )
        }
    }

    fun runSearch() {
        val q = search.trim()
        if (q.length < 2) { ctx.toast("Type at least 2 letters of the username"); return }
        scope.launch {
            try {
                var found: List<Pair<String, Map<String, Any?>>> = emptyList()
                for (term in listOf(q, q.lowercase()).distinct()) {
                    val snap = AR.db.child("users").orderByChild("username").startAt(term).endAt(term + "\uf8ff").limitToFirst(15).get().await()
                    found = snap.children.mapNotNull { ch -> ch.key?.let { k -> k to mp(ch.value) } }
                    if (found.isNotEmpty()) break
                }
                results = found
                if (found.isEmpty()) ctx.toast("No player found")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.toast("Search failed. You need permission to view players")
            }
        }
    }

    fun openEdit(uid: String, name: String) {
        val o = mp(overrides[uid])
        editUid = uid
        editName = name
        editLock = if (num(o["lock"]) > 0.0) str(o["lock"]) else ""
        editBlocked = o["blocked"] == true
        giveSpins = ""
    }

    // Admin sees every spin of one player (latest 100) with the total he won.
    fun openHistory(uid: String, name: String) {
        histUid = uid; histName = name; histRows = emptyList(); histState = emptyMap(); histLoading = true
        scope.launch {
            try {
                val h = AR.db.child("spinHistory").child(uid).orderByChild("ts").limitToLast(100).get().await()
                histRows = h.children.mapNotNull { ch -> ch.key?.let { k -> mp(ch.value) + ("id" to k) } }.sortedByDescending { num(it["ts"]) }
                histState = mp(AR.db.child("spinState").child(uid).get().await().value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.toast("Could not load spin history")
            } finally {
                histLoading = false
            }
        }
    }

    // Admin gives free spins to one player. They skip the match task, daily limit and cooldown; the spin must still be ON and the player not blocked.
    fun giveBonus(remove: Boolean) {
        val uid = editUid ?: return
        val name = editName
        val n = giveSpins.trim().toLongOrNull()
        if (!remove && (n == null || n < 1 || n > 100)) { ctx.toast("Spins to give: enter a number from 1 to 100"); return }
        scope.launch {
            if (!canAccess("spin_manage")) { ctx.toast("Permission denied"); return@launch }
            try {
                val ref = AR.db.child("spinBonus").child(uid)
                // Read the CURRENT value of this player (do not trust the list, it may be empty/old), then write the exact new number.
                val have = num(ref.get().await().value).toLong()
                if (remove && have <= 0L) { ctx.toast("This player has no free spins"); return@launch }
                val total = have + (n ?: 0L)
                if (!remove && total > 1000L) { ctx.toast("A player can hold at most 1000 free spins (has $have)"); return@launch }
                if (remove) ref.removeValue().await() else ref.setValue(total).await()
                try { AR.logAct("spin_bonus", uid, "user=$name " + (if (remove) "removed all free spins (had $have)" else "gave $n free spins (had $have, now $total)")) } catch (_: Exception) { }
                ctx.toast(if (remove) "Free spins removed" else "$n free spin(s) given to $name (now $total)")
                giveSpins = ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val m = e.message.orEmpty()
                ctx.toast(if (m.contains("permission", true)) "Permission denied. Publish the new database.rules.json in Firebase (Realtime Database > Rules) and log in again" else "Could not give spins: " + m)
            }
        }
    }

    fun saveEdit() {
        val uid = editUid ?: return
        val lockText = editLock.trim()
        val lock = if (lockText.isEmpty()) 0.0 else lockText.toDoubleOrNull()
        if (lock == null || lock < 0) { ctx.toast("Lock: enter a coin amount (empty = follow the lock for all players)"); return }
        val blocked = editBlocked
        val name = editName
        scope.launch {
            if (!canAccess("spin_manage")) { ctx.toast("Permission denied"); return@launch }
            try {
                val ref = AR.db.child("spinUserCfg").child(uid)
                if (lock <= 0.0 && !blocked) ref.removeValue().await()
                else ref.setValue(mapOf("lock" to lock, "blocked" to blocked, "username" to name)).await()
                AR.logAct("spin_user", uid, "user=$name lock=$lock blocked=$blocked")
                ctx.toast("Saved")
                editUid = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.toast(e.message ?: "Could not save")
            }
        }
    }

    @Composable
    fun Head(t: String) = Text(t, Modifier.padding(top = 18.dp, bottom = 2.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Orange)

    @Composable
    fun Help(t: String) = Text(t, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))

    @Composable
    fun Num(label: String, key: String, decimal: Boolean = false) =
        OutlinedTextField(vals[key] ?: "", { vals[key] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))

    val gameList = games.entries.map { (id, g) -> id to mp(g) }.sortedBy { num(it.second["order"]) }
    val logList = log.values.map { mp(it) }.sortedByDescending { num(it["ts"]) }

    Column(Modifier.fillMaxSize()) {
        ABar("Lucky Wheel", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            // ---- live summary + master switch ----
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                Text("CURRENTLY LIVE", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val lockLive = num(c["lockAll"])
                Text(
                    "Spin: ${if (c["enabled"] == true) "ON" else "OFF"}   •   Task: play ${str(c["matchesRequired"]).ifBlank { "8" }} matches (entry fee ${str(c["minEntryFee"]).ifBlank { "10" }}+)",
                    fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    "Daily limit: ${str(c["dailyLimit"]).ifBlank { "3" }}   •   Min days between spins: ${str(c["cooldownDays"]).ifBlank { "0" }}   •   Lock for all: ${if (lockLive > 0.0) money(lockLive) else "none (any reward)"}",
                    fontSize = 12.sp, color = Color.Gray
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (c["enabled"] == true) "Lucky Wheel: ENABLED" else "Lucky Wheel: DISABLED", fontWeight = FontWeight.SemiBold, color = if (c["enabled"] == true) Green else Color.Gray)
                    Text("Off = players cannot spin and the Spin banner is hidden everywhere. Takes effect at once.", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(c["enabled"] == true, { on -> scope.launch { write(mapOf("enabled" to on), if (on) "Lucky Wheel is ON" else "Lucky Wheel is OFF", "enabled=$on") } }, enabled = !busy)
            }

            // ---- task ----
            Head("TASK (how a player unlocks a spin)")
            Help("A match counts when the player joined it and it is ONGOING (or finished) and its entry fee is at least the amount below. Each spin uses up this many matches.")
            Num("Matches to play for 1 spin (0 = no task)", "matchesRequired")
            Num("Minimum entry fee of those matches (coins)", "minEntryFee", decimal = true)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Count matches played before the first spin", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Off = only matches played from now on count (recommended).", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(countPast, { countPast = it })
            }

            // ---- limits ----
            Head("LIMITS")
            Num("Spins per day (0 = no limit)", "dailyLimit")
            Num("Minimum days between two spins (0 = none, 0.5 = 12 hours)", "cooldownDays", decimal = true)

            // ---- lock ----
            Head("REWARD LOCK (for ALL players)")
            Help("The highest coin a player can win. Example: lock 5 = player gets only 2 or 5. Lock 100 = anything up to 100. 0 = no lock, any reward on the wheel can come.")
            Num("Lock for all players (coins, 0 = no lock)", "lockAll", decimal = true)
            SelectField("Reward is added to (recommended: Join wallet)", creditTo, listOf("deposited" to "Join wallet (used for entry fees, cannot be withdrawn)", "winning" to "Winning wallet (can be withdrawn)")) { creditTo = it }

            // ---- wheel ----
            Head("WHEEL REWARDS")
            Help("One line per piece:  coin:chance  (example  5:25). A bigger chance number = comes more often. Chance 0 = never. 2 to 12 pieces.")
            OutlinedTextField(vals["segments"] ?: "", { vals["segments"] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Pieces (coin:chance)") }, minLines = 5)
            ABtn(if (busy) "SAVING..." else "SAVE SETTINGS", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { saveRules() }

            // ---- games ----
            Head("GAMES WITH SPIN BANNER")
            Help("The Lucky Wheel banner is shown only inside the games you switch on here.")
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ABtn("ALL ON", Modifier.weight(1f), small = true, enabled = !busy && gameList.isNotEmpty()) {
                    scope.launch { write(mapOf("games" to gameList.associate { it.first to true }), "Spin ON in all games", "games=all") }
                }
                ABtn("ALL OFF", Modifier.weight(1f), small = true, color = Field, enabled = !busy) {
                    scope.launch { write(mapOf("games" to null), "Spin OFF in all games", "games=none") }
                }
            }
            if (gameList.isEmpty()) Text("No games yet. Add games first.", color = Color.Gray, fontSize = 13.sp)
            gameList.forEach { (id, g) ->
                val on = gameOn[id] == true
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(CardC).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(str(g["name"]).ifBlank { id }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (g["active"] == false) Text("game is hidden", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(on, { v -> scope.launch { write(mapOf("games/$id" to (if (v) true else null)), if (v) "Spin ON for ${str(g["name"])}" else "Spin OFF for ${str(g["name"])}", "game=$id on=$v") } }, enabled = !busy)
                }
            }

            // ---- per player ----
            Head("PLAYER CONTROL (free spins, lock, block)")
            Help("Search a player to GIVE HIM FREE SPINS, to give him his own lock (he can win at most that many coins) or to block his spin completely. A player lock beats the lock for all players.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(search, { search = it }, Modifier.weight(1f), label = { Text("Username") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                ABtn("SEARCH", small = true) { runSearch() }
            }
            results.forEach { (uid, u) ->
                val name = str(u["username"]).ifBlank { uid }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(CardC).clickable { openEdit(uid, name) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(listOf(str(u["firstName"]), str(u["lastName"])).filter { it.isNotBlank() }.joinToString(" "), fontSize = 12.sp, color = Color.Gray)
                    }
                    Text("SPIN / LOCK", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            val bonusRows = bonusMap.entries.filter { num(it.value) > 0.0 }
            if (bonusRows.isNotEmpty()) {
                Text("Players holding free spins (from admin)", Modifier.padding(top = 12.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                bonusRows.forEach { (uid, v) ->
                    val nm = bonusNames[uid] ?: str(mp(overrides[uid])["username"]).ifBlank { uid.take(8) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(CardC).clickable { openEdit(uid, nm) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(nm, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(num(v).toLong().toString() + " free spin(s)", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (overrides.isNotEmpty()) {
                Text("Players with their own lock / block", Modifier.padding(top = 12.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                overrides.entries.forEach { (uid, v) ->
                    val o = mp(v)
                    val name = str(o["username"]).ifBlank { uid }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(CardC).clickable { openEdit(uid, name) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            if (o["blocked"] == true) "BLOCKED" else "Lock " + money(num(o["lock"])),
                            color = if (o["blocked"] == true) Red else Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ---- recent spins ----
            Head("RECENT SPINS")
            if (logList.isEmpty()) Text("No spins yet.", color = Color.Gray, fontSize = 13.sp)
            logList.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(CardC).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(str(l["username"]).ifBlank { str(l["uid"]).take(8) }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(fdate(l["ts"]), fontSize = 11.sp, color = Color.Gray)
                    }
                    Text("+" + money(num(l["amount"])), color = Green, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (editUid != null) {
        AlertDialog(
            onDismissRequest = { editUid = null }, containerColor = DialogC,
            title = { Text("Lucky Wheel: $editName", fontSize = 16.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Free spins now: " + num(bonusMap[editUid ?: ""]).toLong(), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Green)
                    OutlinedTextField(
                        giveSpins, { giveSpins = it.filter { ch -> ch.isDigit() }.take(3) }, Modifier.fillMaxWidth().padding(top = 6.dp),
                        label = { Text("Give free spins (1-100)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text("Free spins need no matches and ignore the daily limit. The reward is still picked by the wheel and the lock below.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        TextButton({ giveBonus(false) }) { Text("GIVE SPINS") }
                        TextButton({ giveBonus(true) }) { Text("REMOVE ALL", color = Red) }
                    }
                    TextButton({ val u = editUid; if (u != null) openHistory(u, editName) }) { Text("VIEW SPIN HISTORY") }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        editLock, { editLock = it }, Modifier.fillMaxWidth(), label = { Text("Lock for this player (coins)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Text("He can win at most this many coins. Empty = same lock as all players.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Block this player's spin", Modifier.weight(1f), fontSize = 14.sp)
                        Switch(editBlocked, { editBlocked = it })
                    }
                }
            },
            confirmButton = { TextButton({ saveEdit() }) { Text("SAVE") } },
            dismissButton = { TextButton({ editUid = null }) { Text("CANCEL") } }
        )
    }

    if (histUid != null) {
        AlertDialog(
            onDismissRequest = { histUid = null }, containerColor = DialogC,
            title = { Text("Spin history: $histName", fontSize = 16.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (histLoading) { Text("Loading...", color = Color.Gray, fontSize = 13.sp) }
                    else {
                        val shown = histRows.sumOf { num(it["amount"]) }
                        Text(
                            "Total spins: " + num(histState["totalSpins"]).toLong() + "   •   Total won: " + money(num(histState["totalEarned"])),
                            fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Green
                        )
                        Text("Free spins left: " + num(bonusMap[histUid ?: ""]).toLong() + "   •   Last " + histRows.size + " spins add up to " + money(shown), fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                        if (histRows.isEmpty()) Text("This player has not spun yet.", color = Color.Gray, fontSize = 13.sp)
                        histRows.forEach { r ->
                            val wal = if (str(r["wallet"]) == "winning") "Winning wallet" else if (str(r["wallet"]) == "deposited") "Join wallet" else ""
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x14000000)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(fdate(r["ts"]), fontSize = 12.sp)
                                    val tags = listOf(if (r["bonus"] == true) "FREE SPIN (admin)" else "", wal).filter { it.isNotBlank() }.joinToString("  •  ")
                                    if (tags.isNotBlank()) Text(tags, fontSize = 11.sp, color = Color.Gray)
                                }
                                Text("+" + money(num(r["amount"])), color = Green, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ histUid = null }) { Text("CLOSE") } }
        )
    }
}
