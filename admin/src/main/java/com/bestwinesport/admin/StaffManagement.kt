package com.bestwinesport.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Grouped permission checkboxes (same look as the Staff Permissions screen). Used by Add/Edit Staff and Create user. */
@Composable
fun PermissionPicker(selected: SnapshotStateMap<String, Boolean>) {
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ABtn("SELECT ALL", color = Purple, small = true) { StaffAccess.catalog.forEach { selected[it.key] = true } }
        ABtn("CLEAR ALL", color = Field, small = true) { StaffAccess.catalog.forEach { selected[it.key] = false } }
    }
    StaffAccess.catalog.groupBy { it.group }.forEach { (group, entries) ->
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(group.uppercase(), Modifier.weight(1f), color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            val all = entries.all { selected[it.key] == true }
            Text(if (all) "CLEAR GROUP" else "SELECT GROUP", Modifier.clickable { entries.forEach { selected[it.key] = !all } }, color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        entries.forEach { item ->
            Row(Modifier.fillMaxWidth().clickable { selected[item.key] = selected[item.key] != true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(item.detail, fontSize = 11.sp, color = Color.Gray)
                }
                Checkbox(checked = selected[item.key] == true, onCheckedChange = { selected[item.key] = it })
            }
            HorizontalDivider(color = CardC)
        }
    }
}

/** Game access for Add / Edit Staff: "All games" or a tick list of tournament games (FULL MAP ...) and player-match games (LW / CS ...). */
@Composable
fun GameScopePicker(allGames: Boolean, onAll: (Boolean) -> Unit, games: Map<String, Any?>, selGames: SnapshotStateMap<String, Boolean>, pvp: Map<String, Any?>, selPvp: SnapshotStateMap<String, Boolean>) {
    GameScopeRow("All games", "No restriction - every match of every game.", allGames, true, onAll)
    Text("TOURNAMENT GAMES (Full Map etc.)", Modifier.padding(top = 14.dp, bottom = 2.dp), color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val g = games.entries.sortedBy { num(mp(it.value)["order"]) }
    if (g.isEmpty()) Text("No tournament games yet.", color = Color.Gray, fontSize = 12.sp)
    g.forEach { e -> GameScopeRow(str(mp(e.value)["name"]).ifBlank { e.key }, null, selGames[e.key] == true, !allGames) { selGames[e.key] = it } }
    Text("PLAYER MATCH GAMES (LW / CS etc.)", Modifier.padding(top = 14.dp, bottom = 2.dp), color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val p = pvp.entries.sortedBy { num(mp(it.value)["order"]) }
    if (p.isEmpty()) Text("No player match games yet.", color = Color.Gray, fontSize = 12.sp)
    p.forEach { e -> GameScopeRow(str(mp(e.value)["name"]).ifBlank { e.key }, null, selPvp[e.key] == true, !allGames) { selPvp[e.key] = it } }
    if (!allGames && selGames.none { it.value } && selPvp.none { it.value })
        Text("No game ticked: this staff member will see no matches at all.", Modifier.padding(top = 10.dp), color = Red, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun GameScopeRow(title: String, detail: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, fontSize = 11.sp, color = Color.Gray)
        }
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    HorizontalDivider(color = CardC)
}

private fun fmtDate(ms: Long): String = if (ms <= 0) "-" else SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(ms))

// ---------------- Staff Management (list) ----------------
@Composable
fun StaffManagementScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val staff by rflow(init = emptyMap<String, Any?>()) { AR.watchQ(AR.db.child("users").orderByChild("role").equalTo("staff")) }
    var statusF by remember { mutableStateOf("all") }; var q by remember { mutableStateOf("") }
    var removeUid by remember { mutableStateOf<String?>(null) }; var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    val me = AR.auth.currentUser?.uid
    val team = staff.entries.map { it.key to mp(it.value) }
        .filter { (_, u) -> val dis = u["panelDisabled"] == true; statusF == "all" || (statusF == "disabled") == dis }
        .filter { (_, u) -> q.isBlank() || listOf("firstName", "lastName", "username", "email").joinToString(" ") { str(u[it]) }.contains(q.trim(), true) }
        .sortedByDescending { num(it.second["createdAt"]) }

    Column(Modifier.fillMaxSize()) {
        ABar("Staff Management", nav)
        Row(Modifier.padding(12.dp, 8.dp, 12.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth()) { SelectField("Status", statusF, listOf("all" to "All Status", "active" to "Active", "disabled" to "Disabled")) { statusF = it } }
        }
        Row(Modifier.padding(12.dp, 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), label = { Text("Search name / email") }, singleLine = true)
            ABtn("+ ADD STAFF", color = Green, small = true) { nav.go("staffform/new") }
        }
        if (team.isEmpty()) Text("No staff found. Tap ADD STAFF to create one.", Modifier.padding(20.dp), color = Color.Gray, fontSize = 13.sp)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(team, key = { it.first }) { (uid, u) ->
                val disabled = u["panelDisabled"] == true
                val role = str(u["role"])
                val count = mp(u["staffPermissions"]).values.count { it == true }
                val name = (str(u["firstName"]) + " " + str(u["lastName"])).trim().ifBlank { str(u["username"]) }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name + if (uid == me) " (you)" else "", fontWeight = FontWeight.SemiBold)
                            Text(str(u["email"]), color = Color.Gray, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Pill(role); Spacer(Modifier.height(3.dp))
                            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (disabled) Color(0xFF7A1F1F) else Green).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(if (disabled) "disabled" else "active", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                    Text("$count of ${StaffAccess.catalog.size} permissions", color = if (count == 0) Orange else Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    val gs = mp(u["staffGames"])
                    val gText = if (gs.isEmpty() || gs["all"] == true) "All games" else {
                        val n = mp(gs["games"]).values.count { it == true } + mp(gs["pvp"]).values.count { it == true }
                        if (n == 0) "No game (sees no matches)" else "$n game(s)"
                    }
                    Text("Games: $gText", color = if (gText.startsWith("No")) Orange else Color.Gray, fontSize = 11.sp)
                    Text("Created: ${fmtDate(num(u["createdAt"]).toLong())}", color = Color.Gray, fontSize = 11.sp)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ABtn("EDIT", color = Purple, small = true) { nav.go("staffform/$uid") }
                        if (uid != me) ABtn(if (disabled) "ENABLE" else "DISABLE", color = Field, small = true) { scope.launch { ctx.toast(AR.setStaffDisabled(uid, !disabled)) } }
                        ABtn("SHOW KEY", color = Field, small = true) { scope.launch { try { shown = "Login key: ${str(u["username"])}" to AR.showKey(uid) } catch (e: Exception) { ctx.toast("No key found") } } }
                        if (uid != me) ABtn("REMOVE", color = Color(0xFF7A1F1F), small = true) { removeUid = uid }
                    }
                }
            }
        }
    }
    shown?.let { (t, k) -> KeyDialog(t, k, "Share privately. Staff enter this 12-digit key on the Staff Login screen.") { shown = null } }
    removeUid?.let { uid -> ConfirmDialog("Remove panel access for this person?", { removeUid = null }) { scope.launch { AR.removeMember(uid); removeUid = null; ctx.toast("Access removed") } } }
}

// ---------------- Add / Edit Staff (uid == "new" -> add) ----------------
@Composable
fun StaffFormScreen(nav: NavController, uid: String) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val isNew = uid == "new"
    val selected = remember { mutableStateMapOf<String, Boolean>().also { m -> StaffAccess.catalog.forEach { m[it.key] = false } } }
    var name by remember { mutableStateOf("") }; var un by remember { mutableStateOf("") }; var em by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf(if (isNew) AR.genPassword() else "") }
    var lkey by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("staff") }; var disabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(isNew) }; var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var oldRole by remember { mutableStateOf("staff") }
    // Game access: which games this staff member may add matches / declare results for (see GameScopePicker).
    var allGames by remember { mutableStateOf(false) }
    val selGames = remember { mutableStateMapOf<String, Boolean>() }; val selPvp = remember { mutableStateMapOf<String, Boolean>() }
    val gamesAll by rflow(init = emptyMap<String, Any?>()) { AR.watch("games") }
    val pvpAll by rflow(init = emptyMap<String, Any?>()) { AR.watch("pvpGames") }
    val scopeMap = {
        val m = hashMapOf<String, Any?>("all" to allGames)
        if (!allGames) {
            val g = selGames.filterValues { it }.mapValues { true }; val p = selPvp.filterValues { it }.mapValues { true }
            if (g.isNotEmpty()) m["games"] = g
            if (p.isNotEmpty()) m["pvp"] = p
        }
        m
    }
    
    LaunchedEffect(uid) {
        if (!isNew) try {
            val u = mp(AR.get("users/$uid"))
            name = (str(u["firstName"]) + " " + str(u["lastName"])).trim(); un = str(u["username"]); em = str(u["email"])
            role = str(u["role"]).ifBlank { "staff" }; oldRole = role; disabled = u["panelDisabled"] == true
            val g = mp(u["staffPermissions"]); StaffAccess.catalog.forEach { selected[it.key] = g[it.key] == true }
            val gs = mp(u["staffGames"]); allGames = gs.isEmpty() || gs["all"] == true   // old staff accounts without the node had every game
            mp(gs["games"]).forEach { (k, v) -> selGames[k] = v == true }; mp(gs["pvp"]).forEach { (k, v) -> selPvp[k] = v == true }
            loaded = true
        } catch (e: Exception) { ctx.toast(e.message ?: "Could not load staff") }
    }
    if (Session.role != "admin") return AccessDenied(nav)
    if (!loaded) return Column(Modifier.fillMaxSize()) { ABar("Edit Staff", nav); Loader() }

    Column(Modifier.fillMaxSize()) {
        ABar(if (isNew) "Add Staff" else "Edit Staff", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Staff name") }, singleLine = true)
            OutlinedTextField(un, { un = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Username") }, singleLine = true, enabled = isNew)
            OutlinedTextField(em, { em = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Email address") }, singleLine = true, enabled = isNew, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            if (isNew) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(pw, { pw = it }, Modifier.weight(1f).padding(vertical = 4.dp), label = { Text("Password (min 6)") }, singleLine = true)
                Spacer(Modifier.width(8.dp)); ABtn("GENERATE", color = Field, small = true) { pw = AR.genPassword() }
            } else {
                var newPw by remember { mutableStateOf("") }
                val hasAuthKey = remember { AuthAdmin.key(ctx).isNotBlank() }
                if (hasAuthKey) {
                    OutlinedTextField(newPw, { newPw = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("New password (leave empty to keep)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (newPw.isNotBlank()) ABtn("CHANGE PASSWORD NOW", color = Field, small = true) {
                        if (newPw.length < 6) ctx.toast("Password min 6 characters") else scope.launch { val e = AuthAdmin.update(ctx, uid, null, newPw); ctx.toast(e ?: "Password changed"); if (e == null) newPw = "" }
                    }
                }
                ABtn("SEND PASSWORD RESET EMAIL", Modifier.padding(top = 4.dp), Field, true) { scope.launch { ctx.toast(AR.resetEmail(em)) } }
            }
            if (isNew) KeyField(lkey, "12-digit login key (you choose)") { lkey = it }
            if (!isNew) SelectField("Status", if (disabled) "disabled" else "active", listOf("active" to "Active", "disabled" to "Disabled (cannot log in)")) { disabled = it == "disabled" }
            Text("ALLOWED PERMISSIONS", Modifier.padding(top = 16.dp), color = Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            PermissionPicker(selected)
            Text("GAME ACCESS", Modifier.padding(top = 18.dp), color = Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Works together with the match permissions above. Tick only the games this staff member may edit, delete, run results for or refund. Matches of other games stay hidden for them.", Modifier.padding(top = 4.dp), color = Color.Gray, fontSize = 11.sp)
            GameScopePicker(allGames, { allGames = it }, gamesAll, selGames, pvpAll, selPvp)
            if (isNew) Text("You type the 12-digit login key yourself (nothing is random). The staff member logs in from the Staff Login screen with email/username + password + that key. Staff can never create accounts.", Modifier.padding(top = 12.dp), color = Color.Gray, fontSize = 12.sp)
        }
        ABtn(if (busy) "SAVING..." else "SAVE STAFF SETTINGS", Modifier.fillMaxWidth().padding(14.dp), Orange, enabled = !busy) {
            if (busy) return@ABtn
            busy = true
            scope.launch {
                if (isNew) {
                    val r = AR.createAccount(ctx, name, un, em, pw, "", "staff", selected.toMap(), scopeMap(), lkey); busy = false
                    if (r.error != null) ctx.toast(r.error) else done = "Username: ${un.trim().lowercase()}\nEmail: ${em.trim()}\nPassword: $pw\nLogin key (Staff Login): ${r.key}"
                } else {
                    val e = AR.updateStaff(uid, name, "staff", disabled, selected.toMap(), scopeMap()); busy = false
                    ctx.toast(e ?: "Staff updated"); if (e == null) nav.popBackStack()
                }
            }
        }
    }
    done?.let { ShareDialog("Staff created", it) { done = null; nav.popBackStack() } }
}
