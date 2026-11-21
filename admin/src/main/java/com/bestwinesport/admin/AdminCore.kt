package com.bestwinesport.admin

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Session {
    var role: String = ""
    var uid: String = ""
    var permissions: Set<String> = emptySet()
    var gameScope: GameScope? = null
    fun clear() { role = ""; uid = ""; permissions = emptySet(); gameScope = null }
}

/** Games a staff member may work with (set by the admin in Add / Edit Staff). Session.gameScope == null -> every game. */
class GameScope(val games: Set<String>, val pvp: Set<String>)

/** users/<uid>/staffGames = { all: Boolean, games: { gameId: true }, pvp: { gameId: true } }. A missing node (old staff accounts) = every game. */
fun gameScopeOf(node: Any?): GameScope? {
    val n = mp(node)
    if (n.isEmpty() || n["all"] == true) return null
    return GameScope(mp(n["games"]).filterValues { it == true }.keys, mp(n["pvp"]).filterValues { it == true }.keys)
}

/** Tournament match game (FULL MAP, CS 1VS1 ...) allowed for the logged-in panel user? Admin = always. */
fun gameAllowed(gameId: String): Boolean {
    if (Session.role == "admin") return true
    val s = Session.gameScope ?: return true
    return gameId.isNotBlank() && gameId in s.games
}

/** Player-match game (LW 1vs1, CS 1vs1 ...) allowed for the logged-in panel user? Admin = always. */
fun pvpGameAllowed(gameId: String): Boolean {
    if (Session.role == "admin") return true
    val s = Session.gameScope ?: return true
    return gameId.isNotBlank() && gameId in s.pvp
}

fun <V> Map<String, V>.onlyAllowedGames(): Map<String, V> = if (Session.role == "admin" || Session.gameScope == null) this else filterKeys { gameAllowed(it) }
fun <V> Map<String, V>.onlyAllowedPvpGames(): Map<String, V> = if (Session.role == "admin" || Session.gameScope == null) this else filterKeys { pvpGameAllowed(it) }

@Suppress("UNCHECKED_CAST")
/** Leaderboard cycle id (IST) - must match the player app. */
fun lbKey(period: String): String {
    val tz = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    val c = java.util.Calendar.getInstance(tz).apply { firstDayOfWeek = java.util.Calendar.MONDAY }
    when (period) { "weekly" -> c.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY); "monthly" -> c.set(java.util.Calendar.DAY_OF_MONTH, 1) }
    val f = java.text.SimpleDateFormat(if (period == "monthly") "yyyyMM" else "yyyyMMdd", java.util.Locale.US).apply { timeZone = tz }
    return period + "_" + f.format(c.time)
}

fun mp(a: Any?): Map<String, Any?> = (a as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }?.toMap() ?: emptyMap()
fun str(a: Any?): String = if (a is Number && a.toDouble() % 1.0 == 0.0) a.toLong().toString() else a?.toString() ?: ""
fun num(a: Any?): Double = when (a) { is Number -> a.toDouble(); is String -> a.toDoubleOrNull() ?: 0.0; else -> 0.0 }
fun money(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else "%.2f".format(d)
fun fdate(a: Any?): String = if (num(a) == 0.0) "-" else SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(num(a).toLong()))

/** Accept both the documented 24-hour form and the common 12-hour AM/PM form. */
fun parseMatchDate(raw: String): Long? {
    val text = raw.trim().replace(Regex("\\s+"), " ")
        .replace(Regex("(?i)(\\d)(am|pm)\\b"), "$1 $2")
    if (text.isEmpty()) return null
    for (pattern in listOf("dd/MM/yyyy HH:mm", "dd/MM/yyyy h:mm a")) {
        val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val pos = ParsePosition(0)
        val date = fmt.parse(text, pos)
        if (date != null && pos.index == text.length) return date.time
    }
    return null
}

object AR {
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: DatabaseReference get() = FirebaseDatabase.getInstance().reference

    // Firebase calls onDataChange on the MAIN thread; turning a big snapshot (matches, withdrawals...) into Maps there made the admin app lag.
    // Now only the raw snapshot is handed over, the conversion runs on a background thread, and if updates arrive quickly only the newest is converted.
    fun watchQ(q: Query): Flow<Map<String, Any?>> = callbackFlow<DataSnapshot> {
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { trySend(s) }
            override fun onCancelled(e: DatabaseError) { close(e.toException()) }
        }
        q.addValueEventListener(l)
        awaitClose { q.removeEventListener(l) }
    }.conflate().map { mp(it.value) }.flowOn(kotlinx.coroutines.Dispatchers.Default).catch { }

    fun watch(path: String) = watchQ(db.child(path))
    suspend fun get(path: String): Any? = db.child(path).get().await().value
    suspend fun role(): String = get("users/${auth.currentUser?.uid}/role")?.toString() ?: "user"

    suspend fun refreshSession(): Boolean {
        val current = auth.currentUser ?: run { Session.clear(); return false }
        val r = try { role() } catch (_: Exception) { "" }
        val keyed = try { get("panelLogin/${current.uid}") != null } catch (_: Exception) { false }
        val disabled = try { get("users/${current.uid}/panelDisabled") == true } catch (_: Exception) { false }
        if (r !in setOf("admin", "staff") || !keyed || disabled) { Session.clear(); return false }
        Session.uid = current.uid
        Session.role = r
        Session.permissions = if (r == "admin") StaffAccess.keys else {
            try { mp(get("users/${current.uid}/staffPermissions")).filterValues { it == true }.keys.intersect(StaffAccess.keys) }
            catch (_: Exception) { emptySet() }
        }
        // Game restriction of this staff member (admin = none). If it cannot be read, the staff member gets NO game (safe side).
        Session.gameScope = if (r == "admin") null else try { gameScopeOf(get("users/${current.uid}/staffGames")) } catch (_: Exception) { GameScope(emptySet(), emptySet()) }
        // Push-token registration runs in the background with a time limit: it must never block opening the panel.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try { kotlinx.coroutines.withTimeout(20_000) { AdminPush.registerCurrentDevice() } } catch (_: Exception) { }
        }
        return true
    }

    /** Every important admin/staff action is written to adminLogs so you can see who did what. */
    suspend fun logAct(action: String, target: String, detail: String = "") {
        try {
            val id = db.child("adminLogs").push().key ?: return
            db.child("adminLogs/$id").setValue(mapOf("by" to (auth.currentUser?.email ?: ""), "uid" to auth.currentUser?.uid, "action" to action,
                "target" to target, "detail" to detail, "ts" to System.currentTimeMillis())).await()
        } catch (_: Exception) { }
    }

    suspend fun saveAppConfig(m: Map<String, Any?>): String {
        if (!canAccess("app_control")) return "Permission denied"
        return try { db.child("appConfig").updateChildren(m).await(); logAct("app_config", "appConfig", "maintenance=${m["maintenance"]} latest=${m["latestCode"]} min=${m["minCode"]}"); "Saved" }
        catch (e: Exception) { e.message ?: "Failed" }
    }

    suspend fun cloneMatch(id: String): String {
        if (!canAccess("matches_manage")) return "Permission denied"
        try {
            val m = HashMap(mp(get("matches/$id")))
            if (m.isEmpty()) return "Match not found"
            if (!gameAllowed(str(m["gameId"]))) return "You do not have access to the game of this match"
            listOf("slots", "results", "resultDeclared", "refunded", "cancelledAt", "cancelReason", "cancelAt", "hasAccessCode").forEach { m.remove(it) }
            m["joined"] = 0L; m["status"] = "upcoming"; m["matchId"] = genMatchId()
            m["matchNo"] = reserveMatchNos(1).toString()
            val nid = db.child("matches").push().key!!
            db.child("matches/$nid").setValue(m).await()
            logAct("match_cloned", nid, "from $id")
            return "Match cloned. Open it to change time and details"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    /**
     * Adds named, non-account-backed players to the first free normal-match positions.
     * The slot claim is a Firebase transaction, so two Admin devices cannot reserve the same position.
     */
    suspend fun fillVirtualSlots(mid: String, rawNames: List<String>): String {
        if (Session.role != "admin") return "Only an admin can fill virtual slots"
        val names = rawNames.map { it.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty()) return "Enter at least one player name"
        if (names.size != names.distinctBy { it.lowercase() }.size) return "Duplicate virtual player names are not allowed"
        val match = mp(get("matches/$mid"))
        if (match.isEmpty()) return "Match not found"
        if (str(match["status"]) == "cancelled") return "Cancelled matches cannot receive virtual slots"
        val total = num(match["maxPlayers"]).toInt().coerceAtLeast(1)
        val mode = str(match["mode"]).lowercase().replace(" ", "")
        val perTeam = Regex("^(\\d+)vs\\d+$").find(mode)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 6) ?: when (mode) { "duo" -> 2; "squad" -> 4; else -> 1 }
        val keys = (1..((total + perTeam - 1) / perTeam)).flatMap { t -> (1..perTeam).map { p -> "t${t}_$p" } }.take(total)
        val current = mp(get("matches/$mid/slots"))
        val available = keys.filter { it !in current }
        if (names.size > available.size) return "Only ${available.size} slot(s) are available"
        val assigned = mutableListOf<String>()
        try {
            for (name in names) {
                var chosen: String? = null
                for (key in available) {
                    if (key in assigned) continue
                    val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { c ->
                        db.child("matches/$mid/slots/$key").runTransaction(object : Transaction.Handler {
                            override fun doTransaction(d: MutableData): Transaction.Result {
                                if (d.value != null) return Transaction.abort()
                                d.value = mapOf("uid" to "", "username" to name, "ign" to name, "ts" to ServerValue.TIMESTAMP,
                                    "type" to "VIRTUAL", "matchId" to mid, "slot" to key)
                                return Transaction.success(d)
                            }
                            override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) { c.resumeWith(Result.success(ok && e == null)) }
                        })
                    }
                    if (ok) { chosen = key; break }
                }
                if (chosen == null) {
                    assigned.forEach { db.child("matches/$mid/slots/$it").removeValue().await() }
                    return "A slot was taken concurrently. Try again"
                }
                assigned += chosen
            }
            val ups = hashMapOf<String, Any?>()
            assigned.forEachIndexed { i, key ->
                val name = names[i]
                val record = mapOf<String, Any?>("uid" to "", "username" to name, "ign" to name, "ts" to System.currentTimeMillis(), "type" to "VIRTUAL", "matchId" to mid, "slot" to key)
                ups["virtualSlots/$mid/$key"] = record
            }
            db.updateChildren(ups).await()
            db.child("matches/$mid/joined").runTransaction(object : Transaction.Handler {
                override fun doTransaction(d: MutableData): Transaction.Result { d.value = ((d.value as? Number)?.toLong() ?: 0L) + assigned.size; return Transaction.success(d) }
                override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) { }
            })
            logAct("virtual_slots_added", mid, "${assigned.size} slot(s): ${names.joinToString()}")
            return "Added ${assigned.size} virtual slot(s): ${assigned.joinToString()}"
        } catch (e: Exception) {
            assigned.forEach { try { db.child("matches/$mid/slots/$it").removeValue().await() } catch (_: Exception) { } }
            return e.message ?: "Could not fill virtual slots"
        }
    }

    suspend fun removeVirtualSlot(mid: String, key: String): String {
        if (Session.role != "admin") return "Only an admin can remove virtual slots"
        val slot = mp(get("matches/$mid/slots/$key"))
        if (slot.isEmpty()) return "Slot not found"
        if (!str(slot["type"]).equals("VIRTUAL", true)) return "Real player slots cannot be removed here"
        return try {
            db.updateChildren(hashMapOf<String, Any?>("matches/$mid/slots/$key" to null, "virtualSlots/$mid/$key" to null)).await()
            db.child("matches/$mid/joined").runTransaction(object : Transaction.Handler {
                override fun doTransaction(d: MutableData): Transaction.Result { d.value = (((d.value as? Number)?.toLong() ?: 0L) - 1L).coerceAtLeast(0L); return Transaction.success(d) }
                override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) { }
            })
            logAct("virtual_slot_removed", mid, key)
            "Virtual slot removed"
        } catch (e: Exception) { e.message ?: "Could not remove virtual slot" }
    }

    // ---------- Running match numbers + automatic matches ----------

    /** Highest numeric match number already used (to continue the numbering when the counter does not exist yet). */
    private suspend fun maxExistingMatchNo(): Long =
        mp(get("matches")).values.maxOfOrNull { str(mp(it)["matchNo"]).filter { c -> c.isDigit() }.toLongOrNull() ?: 0L } ?: 0L

    /** The number the next match will get (#656 ...). Shared by every game. */
    suspend fun peekMatchNo(): Long = try {
        val c = get("matchCounter")
        (if (c == null) maxExistingMatchNo() else num(c).toLong()) + 1
    } catch (_: Exception) { 1L }

    /** Admin sets where the numbering continues: the next match will be [n]. */
    suspend fun setNextMatchNo(n: Long): String {
        if (!canAccess("matches_manage")) return "Permission denied"
        if (n < 1) return "Enter a number from 1"
        return try { db.child("matchCounter").setValue(n - 1).await(); "The next match will be #$n" } catch (e: Exception) { e.message ?: "Failed" }
    }

    /** Reserves [count] running numbers in one atomic step and returns the FIRST one (a transaction, so two admins never get the same number). */
    suspend fun reserveMatchNos(count: Int): Long {
        val seed = if (get("matchCounter") == null) maxExistingMatchNo() else 0L
        return kotlinx.coroutines.suspendCancellableCoroutine { c ->
            var first = 0L
            db.child("matchCounter").runTransaction(object : Transaction.Handler {
                override fun doTransaction(d: MutableData): Transaction.Result {
                    val cur = (d.value as? Number)?.toLong() ?: seed
                    first = cur + 1; d.value = cur + count; return Transaction.success(d)
                }
                override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) { c.resumeWith(Result.success(if (ok && e == null) first else 0L)) }
            })
        }
    }

    /** Time at which the server checks the minimum players. 0 = this match never auto-cancels. */
    fun cancelAtOf(start: Long, minPlayers: Long, afterMin: Long): Long = if (minPlayers > 0 && start > 0) start + afterMin.coerceAtLeast(0) * 60_000L else 0L

    /**
     * Creates [count] matches in ONE write. Match k starts at firstStart + k x gap. Running numbers are reserved automatically.
     * Every match gets minPlayers / cancelAt, so the backend cancels + refunds it when too few players joined.
     */
    suspend fun createAutoMatches(base: Map<String, Any?>, count: Int, firstStart: Long, gapMs: Long, minPlayers: Long, afterMin: Long): String {
        if (!canAccess("matches_manage")) return "Permission denied"
        if (!gameAllowed(str(base["gameId"]))) return "You do not have access to this game"
        if (count < 1 || count > 100) return "Matches to create: 1 to 100"
        if (count > 1 && gapMs <= 0) return "Set the gap between matches (hours / minutes)"
        return try {
            val firstNo = reserveMatchNos(count)
            if (firstNo < 1) return "Could not get match numbers. Try again"
            val ups = HashMap<String, Any?>()
            for (i in 0 until count) {
                val id = db.child("matches").push().key!!
                val st = firstStart + i * gapMs
                val m = HashMap<String, Any?>(base)
                m["matchId"] = genMatchId(); m["matchNo"] = (firstNo + i).toString(); m["startTime"] = st
                m["joined"] = 0L; m["status"] = "upcoming"; m["auto"] = true
                m["minPlayers"] = minPlayers; m["cancelAfterMin"] = afterMin; m["cancelAt"] = cancelAtOf(st, minPlayers, afterMin)
                ups["matches/$id"] = m
            }
            db.updateChildren(ups).await()
            logAct("matches_auto_created", "matches", "$count x ${str(base["title"])} from #$firstNo")
            "$count matches created (#$firstNo to #${firstNo + count - 1})"
        } catch (e: Exception) { e.message ?: "Failed" }
    }

    /** Saves wallet, stats and flags edited on the Edit user screen. */
    suspend fun saveExtras(uid: String, old: Map<String, Any?>, nums: Map<String, String>, dob: String, gender: String, kyc: Boolean, banned: Boolean, banReason: String = ""): String? {
        if (Session.role != "admin") return "Only an admin can edit full player records"
        try {
            val ups = HashMap<String, Any?>(); val changes = mutableListOf<String>()
            val longKeys = setOf("wins", "kills", "matchesPlayed")
            for ((k, v) in nums) {
                val n = v.toDoubleOrNull() ?: return "Enter a valid number for $k"
                if (n < 0) return "$k cannot be negative"
                if (n != num(old[k])) {
                    ups["users/$uid/$k"] = if (k in longKeys) n.toLong() else n
                    changes += "$k ${money(num(old[k]))}->${money(n)}"
                    if (k == "deposited" || k == "winning") txRec(uid, "admin", n - num(old[k]), "Admin edit: $k").let { ups[it.first] = it.second }
                }
            }
            if (dob.trim() != str(old["dob"])) ups["users/$uid/dob"] = dob.trim()
            if (gender != str(old["gender"])) ups["users/$uid/gender"] = gender
            if (kyc != (old["kyc"] == true)) { ups["users/$uid/kyc"] = kyc; changes += "kyc=$kyc" }
            if (banned && banReason.isBlank()) return "Write the ban reason"
            if (banned != (old["banned"] == true)) { ups["users/$uid/banned"] = banned; changes += "banned=$banned" }
            val newReason = if (banned) banReason.trim() else ""
            if (newReason != str(old["banReason"]) || (banned != (old["banned"] == true))) { ups["users/$uid/banReason"] = if (newReason.isEmpty()) null else newReason; changes += "banReason" }
            if (ups.isNotEmpty()) { db.updateChildren(ups).await(); logAct("edit_user", uid, changes.joinToString("; ")) }
            return null
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    suspend fun dashboard(): List<Pair<String, String>> {
        check(canAccess("dashboard_view")) { "Permission denied" }
        val deps = mp(get("deposits")); val wds = mp(get("withdrawals")); val users = mp(get("users")); val matches = mp(get("matches"))
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val today = cal.timeInMillis
        fun rows(m: Map<String, Any?>, status: String, from: Long = 0) = m.values.map { mp(it) }.filter { it["status"] == status && num(it["ts"]) >= from }
        val depAll = rows(deps, "approved").sumOf { num(it["amount"]) }; val wdAll = rows(wds, "paid").sumOf { num(it["amount"]) }
        val pw = rows(wds, "pending")
        val held = users.values.sumOf { num(mp(it)["deposited"]) + num(mp(it)["winning"]) }
        fun mc(s: String) = matches.values.count { mp(it)["status"] == s }
        return listOf(
            "Players" to "${users.size}", "Banned" to "${users.values.count { mp(it)["banned"] == true }}",
            "KYC verified" to "${users.values.count { mp(it)["kyc"] == true }}", "Coins held by players" to money(held),
            "Deposits approved" to "₹${money(depAll)}", "Deposits today" to "₹${money(rows(deps, "approved", today).sumOf { num(it["amount"]) })}",
            "Withdrawals paid" to "₹${money(wdAll)}", "Withdrawals today" to "₹${money(rows(wds, "paid", today).sumOf { num(it["amount"]) })}",
            "Net (deposits - paid)" to "₹${money(depAll - wdAll)}",
            "Pending withdrawals" to "${pw.size} • ₹${money(pw.sumOf { num(it["amount"]) })}",
            "Matches upcoming" to "${mc("upcoming")}", "Matches live" to "${mc("ongoing")}", "Matches completed" to "${mc("completed")}")
    }

    fun genMatchId(): String { val r = java.security.SecureRandom(); return (1..8).joinToString("") { (if (it == 1) 1 + r.nextInt(9) else r.nextInt(10)).toString() } }
    fun genPassword(): String {
        val r = java.security.SecureRandom(); val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..10).joinToString("") { chars[r.nextInt(chars.length)].toString() }
    }

    class Created(val error: String? = null, val key: String? = null)

    /** Admin makes an account directly (no signup needed). The admin stays logged in. */
    suspend fun createAccount(ctx: Context, name: String, un0: String, email: String, pw: String, phone: String, role: String, perms: Map<String, Boolean>? = null, gameScope: Map<String, Any?>? = null, loginKey: String = ""): Created {
        try {
            if (Session.role != "admin") return Created("Only an admin can create accounts")
            val un = un0.trim().lowercase(); val em = email.trim(); val ph = phone.trim()
            if (name.isBlank()) return Created("Enter a name")
            if (!Regex("[a-z0-9_]{3,20}").matches(un)) return Created("Username: 3-20 chars (a-z, 0-9, _)")
            if (!em.contains("@")) return Created("Enter a valid email")
            if (pw.length < 6) return Created("Password min 6 characters")
            if (role != "user" && !validKey(loginKey)) return Created("Login key must be 12 digits")
            if (ph.isNotEmpty() && !Regex("[0-9]{10}").matches(ph)) return Created("Phone must be 10 digits")
            if (get("loginMap/$un") != null) return Created("Username already taken")
            if (ph.isNotEmpty() && get("loginMap/$ph") != null) return Created("Phone already used")
            val app2 = try { FirebaseApp.getInstance("creator") } catch (e: IllegalStateException) { FirebaseApp.initializeApp(ctx, FirebaseApp.getInstance().options, "creator") }
            val a2 = FirebaseAuth.getInstance(app2)
            val res = a2.createUserWithEmailAndPassword(em, pw).await()
            val uid = res.user!!.uid; val now = System.currentTimeMillis()
            val parts = name.trim().split(" ")
            val key = if (role == "user") null else loginKey
            // NOTE: Firebase rejects a multi-path update where one path is an ancestor of another
            // ("Path '/users/<uid>' is an ancestor of ..."), so staffPermissions / panelDisabled
            // must live INSIDE the users/$uid map, not as separate child paths.
            val userNode = hashMapOf<String, Any?>("firstName" to parts.first(), "lastName" to parts.drop(1).joinToString(" "), "username" to un, "phone" to ph, "email" to em,
                "role" to role, "deposited" to 0, "winning" to 0, "earnings" to 0, "payouts" to 0, "referralCode" to un, "createdAt" to now)
            if (role == "staff") userNode["staffPermissions"] = StaffAccess.catalog.associate { it.key to (perms?.get(it.key) == true) }
            if (role == "staff") userNode["staffGames"] = gameScope ?: mapOf("all" to false)
            if (role != "user") userNode["panelDisabled"] = false
            val ups = hashMapOf<String, Any?>("users/$uid" to userNode, "loginMap/$un" to em, "referralCodes/$un" to uid)
            if (ph.isNotEmpty()) ups["loginMap/$ph"] = em
            if (key != null) ups["panelInvites/$uid"] = mapOf("role" to role, "key" to key, "name" to name.trim(), "email" to em, "createdAt" to now, "createdBy" to auth.currentUser?.uid)
            try { db.updateChildren(ups).await() }
            catch (e: Exception) { res.user?.delete()?.await(); a2.signOut(); return Created("Could not save the account: " + friendlyError(e)) }
            a2.signOut()
            logAct("user_created", uid, "$role $un")
            return Created(null, key)
        } catch (e: Exception) { return Created(friendlyError(e)) }
    }

    /** Edit a player's name, username, phone, email and (with the Auth key) email/password. */
    suspend fun updateUser(ctx: Context, uid: String, old: Map<String, Any?>, first: String, last: String, username: String, phone: String, email: String, newPw: String): String? {
        try {
            if (Session.role != "admin") return "Only an admin can edit player login details"
            val oldUn = str(old["username"]); val oldEmail = str(old["email"]); val oldPhone = str(old["phone"])
            val un = username.trim().lowercase(); val em = email.trim(); val ph = phone.trim()
            if (!Regex("[a-z0-9_]{3,20}").matches(un)) return "Username: 3-20 chars (a-z, 0-9, _)"
            if (!em.contains("@")) return "Enter a valid email"
            if (ph.isNotEmpty() && !Regex("[0-9]{10}").matches(ph)) return "Phone must be 10 digits"
            if (newPw.isNotBlank() && newPw.length < 6) return "Password min 6 characters"
            if (un != oldUn && get("loginMap/$un") != null) return "Username already taken"
            if (ph.isNotEmpty() && ph != oldPhone && get("loginMap/$ph") != null) return "Phone already used"
            if (em != oldEmail || newPw.isNotBlank()) {
                val e = AuthAdmin.update(ctx, uid, if (em != oldEmail) em else null, newPw.ifBlank { null })
                if (e != null) return e
            }
            val ups = HashMap<String, Any?>()
            ups["users/$uid/firstName"] = first.trim(); ups["users/$uid/lastName"] = last.trim()
            ups["users/$uid/username"] = un; ups["users/$uid/phone"] = ph; ups["users/$uid/email"] = em
            if (un != oldUn) {
                ups["loginMap/$oldUn"] = null; ups["referralCodes/$oldUn"] = null; ups["referralCodes/$un"] = uid; ups["users/$uid/referralCode"] = un
                if (get("leaderboard/$uid") != null) ups["leaderboard/$uid/username"] = un
                if (get("referralStats/$uid") != null) ups["referralStats/$uid/username"] = un
            }
            ups["loginMap/$un"] = em
            if (oldPhone.isNotEmpty() && oldPhone != ph) ups["loginMap/$oldPhone"] = null
            if (ph.isNotEmpty()) ups["loginMap/$ph"] = em
            db.updateChildren(ups).await()
            logAct("user_edited", uid, "$oldUn -> $un")
            return null
        } catch (e: Exception) { return e.message ?: "Update failed" }
    }

    suspend fun resetEmail(email: String): String {
        return try { auth.sendPasswordResetEmail(email).await(); "Reset link sent to $email" } catch (e: Exception) { e.message ?: "Failed" }
    }

    /** Keys are never random: the admin types the 12-digit key himself (like a password). */
    fun validKey(k: String): Boolean = Regex("[0-9]{12}").matches(k)

    /** Login = email/username + password + the 12-digit key the admin gave you. The key is checked by Firebase rules. */
    suspend fun login(idf: String, pw: String, key: String, expectRole: String? = null): String? {
        try {
            var email = idf.trim()
            if (!email.contains("@")) email = get("loginMap/${email.lowercase()}")?.toString() ?: return "User not found"
            auth.signInWithEmailAndPassword(email, pw).await()
            val uid = auth.currentUser!!.uid
            val r = role()
            if (r != "admin" && r != "staff") { auth.signOut(); return "No panel access yet. Ask the admin to approve your account and give you a 12-digit key." }
            if (get("users/$uid/panelDisabled") == true) { auth.signOut(); return "This staff account is disabled. Ask the admin to enable it." }
            if (expectRole != null && r != expectRole) { auth.signOut(); return if (expectRole == "staff") "This is an admin account. Use Admin Login." else "This is a staff account. Staff must use the separate BESTWIN ESPORTS Staff app." }
            try { db.child("panelLogin/$uid").setValue(mapOf("key" to key, "ts" to ServerValue.TIMESTAMP)).await() }
            catch (e: Exception) { auth.signOut(); return "Wrong 12-digit login key" }
            Session.role = r
            if (!refreshSession()) { auth.signOut(); return "Could not load panel permissions. Ask the admin to check your account." }
            return null
        } catch (e: Exception) { return friendlyError(e) }
    }

    suspend fun logout() {
        try { AdminPush.unregisterCurrentDevice() } catch (_: Exception) { }
        try { db.child("panelLogin/${auth.currentUser?.uid}").removeValue().await() } catch (_: Exception) { }
        auth.signOut()
        Session.clear()
    }

    class SignupResult(val error: String? = null, val bootKey: String? = null)

    /**
     * Is the first-time admin setup still open?
     * true  = this Firebase has no admin yet -> show the Sign up page
     * false = an admin already exists -> show Login only (Sign up is hidden and closed by the rules)
     * null  = could not check (no internet / rules not published yet) -> screens fall back to showing both links
     */
    suspend fun setupOpen(): Boolean? = try {
        val snap = kotlinx.coroutines.withTimeoutOrNull(6_000) { db.child("system/bootstrapped").get().await() }
        if (snap == null) null else snap.value != true
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

    /**
     * First-time admin setup. The first account creates its own 12-digit login key.
     * - New email: a new Auth account + player record is made and it becomes the admin.
     * - Email that ALREADY has an account in this Firebase (old admin / old player): the app signs in with that same
     *   password and only adds the admin role. users/<uid> (wallet, history ...) and every other node are NOT touched or deleted.
     * Once an admin exists (system/bootstrapped) the rules close signup for everybody.
     */
    suspend fun signup(name: String, un0: String, email: String, pw: String, loginKey: String): SignupResult {
        var created: com.google.firebase.auth.FirebaseUser? = null   // only an account made right now may be rolled back, never an old one
        var firstWriteDone = false
        var existingAccount = false
        try {
            val un = un0.trim().lowercase(); val em = email.trim()
            if (!Regex("[a-z0-9_]{3,20}").matches(un)) return SignupResult("Username: 3-20 chars (a-z, 0-9, _)")
            if (!em.contains("@")) return SignupResult("Enter a valid email")
            if (!validKey(loginKey)) return SignupResult("Login key must be 12 digits")
            val takenBy = get("loginMap/$un")?.toString()
            if (takenBy != null && !takenBy.equals(em, true)) return SignupResult("Username already taken")
            try {
                created = auth.createUserWithEmailAndPassword(em, pw).await().user
            } catch (e: Exception) {
                val m = e.message ?: ""
                if (e is com.google.firebase.auth.FirebaseAuthUserCollisionException || m.contains("already in use", true) || m.contains("EMAIL_EXISTS", true)) {
                    try { auth.signInWithEmailAndPassword(em, pw).await(); existingAccount = true }
                    catch (e2: Exception) {
                        val f = friendlyError(e2)
                        return SignupResult(if (f.startsWith("Wrong")) "This email already has an account in this Firebase. Enter that account's own password to continue - all old data is kept." else f)
                    }
                } else throw e
            }
            val user = auth.currentUser ?: return SignupResult("Sign up failed. Try again.")
            val uid = user.uid; val now = System.currentTimeMillis()
            val old = mp(get("users/$uid"))                       // an old account keeps all its data
            val finalUn = if (old.isNotEmpty() && str(old["username"]).isNotBlank()) str(old["username"]) else un
            val displayName = if (old.isNotEmpty() && str(old["firstName"]).isNotBlank()) (str(old["firstName"]) + " " + str(old["lastName"])).trim() else name.trim()
            // The first admin's own login key is the one-time bootstrap proof.
            val ups = hashMapOf<String, Any?>("adminSignups/$uid" to mapOf("key" to loginKey, "name" to displayName, "username" to finalUn, "email" to em, "ts" to now))
            if (old.isEmpty()) ups["users/$uid"] = mapOf("firstName" to name.trim(), "lastName" to "", "username" to un, "phone" to "", "email" to em,
                "role" to "user", "deposited" to 0, "winning" to 0, "earnings" to 0, "payouts" to 0, "referralCode" to un, "createdAt" to now)
            if (get("loginMap/$finalUn") == null) ups["loginMap/$finalUn"] = em
            try { db.updateChildren(ups).await() }
            catch (e: Exception) {
                return SignupResult(if (e.message?.contains("denied", true) == true) "Signup was rejected by Firebase Rules. Publish the latest database.rules.json, then try again." else friendlyError(e))
            }
            firstWriteDone = true
            // the admin role: rules allow this only while no admin exists
            val bk = loginKey
            try {
                db.updateChildren(mapOf(
                    "users/$uid/role" to "admin",
                    "panelInvites/$uid" to mapOf("role" to "admin", "key" to bk, "name" to displayName, "email" to em, "createdAt" to now, "createdBy" to uid),
                    "system/bootstrapped" to true
                )).await()
                db.child("panelLogin/$uid").setValue(mapOf("key" to bk, "ts" to ServerValue.TIMESTAMP)).await()
                Session.role = "admin"
                Session.uid = uid
                return SignupResult(null, bk)
            } catch (e: Exception) { auth.signOut(); return SignupResult(null, null) }
        } catch (e: Exception) {
            return SignupResult(friendlyError(e))
        } finally {
            if (!firstWriteDone) {
                // nothing was saved: remove only the account created a moment ago; an old account is just signed out
                try {
                    if (existingAccount) auth.signOut()
                    else created?.delete()?.await()
                } catch (_: Exception) { }
            }
        }
    }

    /** Admin changes his OWN password from the panel. Needs the current password. The 12-digit login key and the session stay as they are. */
    suspend fun changeMyPassword(current: String, newPw: String): String? {
        val u = auth.currentUser ?: return "Please login again"
        val email = u.email ?: return "This account has no email"
        if (Session.role != "admin") return "Only an admin can change the password here"
        if (current.isBlank()) return "Enter your current password"
        if (newPw.length < 6) return "New password: min 6 characters"
        if (newPw == current) return "New password must be different from the current one"
        return try {
            u.reauthenticate(com.google.firebase.auth.EmailAuthProvider.getCredential(email, current)).await()
            u.updatePassword(newPw).await()
            logAct("password_changed", u.uid)
            null
        } catch (e: Exception) {
            val f = friendlyError(e)
            if (f.startsWith("Wrong")) "Current password is wrong" else f
        }
    }

    // ---- Team management (admin only) ----
    /** Turns a Firebase error into a sentence that says what to fix. */
    fun friendlyError(e: Exception): String {
        val m = e.message ?: "Failed"
        return when {
            m.contains("OPERATION_NOT_ALLOWED", true) || m.contains("sign-in provider is disabled", true) -> "Email/Password sign-in is OFF in Firebase. Firebase Console -> Authentication -> Sign-in method -> enable Email/Password."
            m.contains("already in use", true) || m.contains("EMAIL_EXISTS", true) -> "This email already has an account. Use \"Already a player?\" below to make that player staff/admin, or use another email."
            m.contains("permission", true) && m.contains("denied", true) -> "Database rules block this. Publish the latest database.rules.json in Firebase Realtime Database -> Rules."
            m.contains("INVALID_LOGIN_CREDENTIALS", true) || m.contains("credential is incorrect", true) || m.contains("no user record", true) || m.contains("password is invalid", true) -> "Wrong email/username or password."
            m.contains("blocked", true) && (m.contains("android client", true) || m.contains("API key", true)) || m.contains("API key not valid", true) || m.contains("API_KEY", true) ->
                "Firebase is blocking this app (API key). Firebase Console -> Project settings -> add an Android app with package com.bestwinesport.admin.app and download its google-services.json into the admin folder."
            m.contains("CONFIGURATION_NOT_FOUND", true) -> "Firebase Authentication is not set up yet. Firebase Console -> Authentication -> Get started -> enable Email/Password."
            m.contains("Firebase Database", true) && m.contains("not", true) -> "Realtime Database is not created in this Firebase project. Firebase Console -> Realtime Database -> Create database, then publish database.rules.json."
            m.contains("network", true) || m.contains("unable to resolve", true) -> "No internet connection."
            else -> m
        }
    }

    /** Make a player who already has an account staff or admin (found by username). Returns the login key text. */
    suspend fun promoteExisting(username: String, role: String, key: String): String {
        if (Session.role != "admin") return "Only an admin can add staff"
        if (!validKey(key)) return "Login key must be 12 digits"
        return try {
            val un = username.trim().lowercase()
            val uid = get("referralCodes/$un")?.toString().orEmpty()
            if (uid.isBlank()) return "No player with the username \"$un\""
            val u = mp(get("users/$uid"))
            if (str(u["role"]) == "admin" || str(u["role"]) == "staff") return "That person is already in the team (use Team & Keys)"
            addMember(uid, role, (str(u["firstName"]) + " " + str(u["lastName"])).trim(), str(u["email"]), key)
            "DONE|" + key
        } catch (e: Exception) { friendlyError(e) }
    }

    suspend fun addMember(uid: String, role: String, name: String, email: String, key: String): String {
        check(Session.role == "admin") { "Only an admin can add staff" }
        check(validKey(key)) { "Login key must be 12 digits" }
        val updates = hashMapOf<String, Any?>(
            "users/$uid/role" to role,
            "panelInvites/$uid" to mapOf("role" to role, "key" to key, "name" to name, "email" to email, "createdAt" to System.currentTimeMillis(), "createdBy" to auth.currentUser?.uid),
            "panelLogin/$uid" to null
        )
        if (role == "staff") updates["users/$uid/staffPermissions"] = StaffAccess.emptyGrants()
        else updates["users/$uid/staffPermissions"] = null
        updates["users/$uid/staffGames"] = if (role == "staff") mapOf("all" to false) else null
        db.updateChildren(updates).await()
        logAct("team_added", uid, "$role $email")
        return key
    }
    suspend fun setKey(uid: String, key: String): String {
        check(Session.role == "admin") { "Only an admin can change staff keys" }
        check(validKey(key)) { "Login key must be 12 digits" }
        db.updateChildren(mapOf("panelInvites/$uid/key" to key, "panelLogin/$uid" to null)).await()
        logAct("team_new_key", uid)
        return key
    }
    /** Promote a staff member to admin, or make an admin a staff member. The person must log in again with their key. */
    suspend fun changeRole(uid: String, newRole: String): String {
        if (Session.role != "admin") return "Only an admin can change roles"
        if (newRole != "admin" && newRole != "staff") return "Invalid role"
        if (uid == auth.currentUser?.uid) return "You cannot change your own role"
        return try {
            val current = str(get("users/$uid/role"))
            if (current == newRole) return "Already ${newRole}"
            if (current != "admin" && current != "staff") return "This person is not in the team"
            if (current == "admin" && newRole == "staff") {
                val admins = db.child("users").orderByChild("role").equalTo("admin").get().await().childrenCount
                if (admins <= 1) return "At least one admin must remain"
            }
            val ups = hashMapOf<String, Any?>(
                "users/$uid/role" to newRole,
                "panelInvites/$uid/role" to newRole,
                "panelLogin/$uid" to null,
                "users/$uid/staffPermissions" to (if (newRole == "staff") StaffAccess.emptyGrants() else null),
                "users/$uid/staffGames" to (if (newRole == "staff") mapOf("all" to false) else null)
            )
            db.updateChildren(ups).await()
            logAct("team_role_changed", uid, "$current -> $newRole")
            if (newRole == "admin") "Now an admin. They must log in again with their key." else "Now staff. Set their permissions, they must log in again."
        } catch (e: Exception) { e.message ?: "Failed" }
    }


    /** Enable / disable a staff or admin login. Disabling also logs them out at once (their panelLogin is removed). */
    suspend fun setStaffDisabled(uid: String, disabled: Boolean): String {
        if (Session.role != "admin") return "Only an admin can change staff status"
        if (uid == auth.currentUser?.uid) return "You cannot disable your own account"
        return try {
            val ups = hashMapOf<String, Any?>("users/$uid/panelDisabled" to disabled)
            if (disabled) ups["panelLogin/$uid"] = null
            db.updateChildren(ups).await()
            logAct(if (disabled) "staff_disabled" else "staff_enabled", uid)
            if (disabled) "Staff disabled" else "Staff enabled"
        } catch (e: Exception) { friendlyError(e) }
    }

    /** Edit a team member: name, role, status and permissions in one go. Returns null on success, else an error text. */
    suspend fun updateStaff(uid: String, name: String, newRole: String, disabled: Boolean, perms: Map<String, Boolean>, gameScope: Map<String, Any?>? = null): String? {
        if (Session.role != "admin") return "Only an admin can edit staff"
        if (name.isBlank()) return "Enter a name"
        return try {
            val current = str(get("users/$uid/role"))
            if (current != "admin" && current != "staff") return "This person is not in the team"
            val parts = name.trim().split(" ")
            db.updateChildren(mapOf("users/$uid/firstName" to parts.first(), "users/$uid/lastName" to parts.drop(1).joinToString(" "), "panelInvites/$uid/name" to name.trim())).await()
            if (current != newRole) { val r = changeRole(uid, newRole); if (!r.startsWith("Now")) return r }
            if (newRole == "staff") { val r = saveStaffPermissions(uid, perms); if (r != "Permissions saved") return r }
            if (newRole == "staff" && gameScope != null) db.child("users/$uid/staffGames").setValue(gameScope).await()
            if (uid != auth.currentUser?.uid) {
                val wasDisabled = get("users/$uid/panelDisabled") == true
                if (wasDisabled != disabled) { val r = setStaffDisabled(uid, disabled); if (!r.startsWith("Staff")) return r }
            }
            logAct("staff_edited", uid, "$newRole " + if (disabled) "disabled" else "active")
            null
        } catch (e: Exception) { friendlyError(e) }
    }

    suspend fun removeMember(uid: String) {
        check(Session.role == "admin") { "Only an admin can remove staff" }
        db.updateChildren(mapOf("users/$uid/role" to "user", "users/$uid/staffPermissions" to null, "users/$uid/staffGames" to null, "panelInvites/$uid" to null, "panelLogin/$uid" to null, "adminSignups/$uid" to null)).await()
        logAct("team_removed", uid)
    }
    suspend fun showKey(uid: String): String = str(get("panelInvites/$uid/key"))
    suspend fun setSignupKey(k: String): String { check(Session.role == "admin") { "Only an admin can change the signup key" }; check(validKey(k)) { "Signup key must be 12 digits" }; db.child("adminConfig/signupKey").setValue(k).await(); return k }

    suspend fun saveStaffPermissions(uid: String, selected: Map<String, Boolean>): String {
        if (Session.role != "admin") return "Only an admin can assign permissions"
        return try {
            val role = str(get("users/$uid/role"))
            if (role != "staff") return "This account is not a staff member"
            val grants = StaffAccess.catalog.associate { it.key to (selected[it.key] == true) }
            db.child("users/$uid/staffPermissions").setValue(grants).await()
            logAct("staff_permissions_updated", uid, grants.filterValues { it }.keys.joinToString(",").ifBlank { "No permissions" })
            "Permissions saved"
        } catch (e: Exception) { e.message ?: "Could not save permissions" }
    }

    suspend fun setPlayerTheme(uid: String, theme: String): String {
        if (!canAccess("players_theme")) return "Permission denied"
        return try {
            db.child("users/$uid/theme").setValue(theme).await()
            logAct("player_theme_changed", uid, theme.ifBlank { "global default" })
            "Player theme updated"
        } catch (e: Exception) { e.message ?: "Could not update player theme" }
    }

    private fun txRec(uid: String, type: String, amt: Double, note: String): Pair<String, Map<String, Any?>> {
        val tid = db.child("transactions/$uid").push().key!!
        return "transactions/$uid/$tid" to mapOf("id" to tid, "type" to type, "amount" to amt, "note" to note, "ts" to System.currentTimeMillis(), "status" to "success")
    }

    suspend fun withdraw(id: String, pay: Boolean): String {
        if (!canAccess("withdrawals_review")) return "Permission denied"
        try {
            val w = mp(get("withdrawals/$id"))
            if (w["status"] != "pending") return "Already processed"
            val uid = str(w["uid"]); val amt = num(w["amount"])
            val ups = HashMap<String, Any?>()
            if (pay) {
                ups["withdrawals/$id/status"] = "paid"; ups["users/$uid/payouts"] = ServerValue.increment(amt); ups["transactions/$uid/$id/status"] = "success"
            } else {
                ups["withdrawals/$id/status"] = "rejected"; ups["users/$uid/winning"] = ServerValue.increment(amt); ups["transactions/$uid/$id/status"] = "rejected"
            }
            db.updateChildren(ups).await()
            logAct(if (pay) "withdraw_paid" else "withdraw_rejected", id, "${str(w["username"])} ₹${money(amt)}")
            return if (pay) "Marked as paid" else "Rejected, amount refunded"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    /** Approve or reject a manual UPI deposit request. Approving adds the amount to the player's deposited balance. */
    suspend fun reviewDeposit(uid: String, id: String, approve: Boolean): String {
        if (!canAccess("balance_adjust")) return "Permission denied"
        try {
            val r = mp(get("depositRequests/$uid/$id"))
            if (r["status"] != "pending") return "Already processed"
            val amt = num(r["amount"])
            val ups = HashMap<String, Any?>()
            ups["depositRequests/$uid/$id/status"] = if (approve) "approved" else "rejected"
            ups["depositRequests/$uid/$id/reviewedAt"] = System.currentTimeMillis()
            if (approve) {
                ups["users/$uid/deposited"] = ServerValue.increment(amt)
                txRec(uid, "deposit", amt, "Deposit approved (UPI ${str(r["utr"])})").let { ups[it.first] = it.second }
            }
            db.updateChildren(ups).await()
            logAct(if (approve) "deposit_approved" else "deposit_rejected", id, "${str(r["username"])} ₹${money(amt)} UTR ${str(r["utr"])}")
            return if (approve) "Deposit approved, wallet credited" else "Deposit rejected"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    suspend fun setImgbbKey(key: String): String {
        if (Session.role != "admin") return "Only an admin can change the imgbb key"
        return try {
            db.child("panelSecrets/imgbbKey").setValue(if (key.isBlank()) null else key.trim()).await()
            logAct("imgbb_key", "settings", if (key.isBlank()) "reset to default" else "updated")
            if (key.isBlank()) "imgbb key reset to the built-in key" else "imgbb key saved"
        } catch (e: Exception) { e.message ?: "Failed" }
    }

    /** Switches ZapUPI automatic deposits on/off (players see manual UPI when off). */
    suspend fun setZapupiEnabled(on: Boolean): String {
        if (Session.role != "admin") return "Only an admin can change this"
        return try {
            db.child("settings/zapupiEnabled").setValue(if (on) "true" else "false").await()
            logAct("zapupi_enabled", "settings", on.toString())
            if (on) "ZapUPI payments ENABLED" else "ZapUPI payments disabled"
        } catch (e: Exception) { "Could not save: " + (e.message ?: "error") }
    }

    /** Asks the payment server what is missing (key / enabled / address) and explains it in plain words. */
    suspend fun zapupiTest(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val base = BackendConfig.BASE_URL.trimEnd('/')
            val token = auth.currentUser?.getIdToken(true)?.await()?.token ?: return@withContext "Log in again, then retry."
            val c = (java.net.URL("$base/api/payments/zapupi/diagnose").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
                setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Content-Type", "application/json")
            }
            try {
                c.outputStream.use { it.write("{}".toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val j = try { org.json.JSONObject(text) } catch (e: Exception) { org.json.JSONObject() }
                if (code in 200..299 && j.optBoolean("success")) {
                    if (j.optBoolean("ready")) "READY: server online, key saved, payments enabled. Players can pay now."
                    else "Server is online, but: " + (0 until j.optJSONArray("problems")!!.length()).joinToString("; ") { j.optJSONArray("problems")!!.getString(it) }
                } else if (code == 404 || code in 502..504 || text.isBlank())
                    "Payment server is NOT running (answer $code). The server (backend folder) must be deployed and online - see backend/README.md."
                else j.optString("message").ifBlank { "Server answered $code" }
            } finally { c.disconnect() }
        } catch (e: java.io.IOException) { "Cannot reach the payment server. It is offline or the app has no internet." }
        catch (e: Exception) { "Test failed: " + (e.message ?: "error") }
    }

    /** Saves (or removes, when blank) the ZapUPI API key. The key is stored in secrets/zapupi (admin-only) and read by the Railway backend. */
    suspend fun setZapupiKey(key: String): String {
        if (Session.role != "admin") return "Only an admin can change the ZapUPI key"
        try {
            db.updateChildren(mapOf<String, Any?>("secrets/zapupi/apiKey" to key.trim(), "settings/zapupiKeySet" to (if (key.isBlank()) "false" else "true"))).await()
            logAct("zapupi_key", "settings", if (key.isBlank()) "removed" else "updated")
            return if (key.isBlank()) "ZapUPI key removed" else "ZapUPI key saved"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    class Agg(var name: String, var kills: Long = 0, var prize: Double = 0.0)

    suspend fun declare(mid: String, kills: Map<String, String>, prizes: Map<String, String>): String {
        if (!canAccess("results_manage")) return "Permission denied"
        try {
            val m = mp(get("matches/$mid"))
            if (!gameAllowed(str(m["gameId"]))) return "You do not have access to the game of this match"
            if (m["resultDeclared"] == true) return "Result already declared"
            val slots = mp(m["slots"])
            if (slots.isEmpty()) return "No players joined"
            val autoRank = try { get("leaderboardConfig/auto") != false } catch (_: Exception) { true }
            val lbk = lbKey(lbPeriod())
            val ups = HashMap<String, Any?>(); val per = HashMap<String, Agg>()
            for ((k, v) in slots) {
                val s = mp(v); val kl = kills[k]?.toLongOrNull() ?: 0L; val pr = prizes[k]?.toDoubleOrNull() ?: 0.0
                ups["matches/$mid/results/$k"] = mapOf("username" to s["username"], "ign" to s["ign"], "kills" to kl, "prize" to pr)
                val a = per.getOrPut(str(s["uid"])) { Agg(str(s["username"])) }; a.kills += kl; a.prize += pr
            }
            for ((uid, a) in per) {
                ups["users/$uid/matchesPlayed"] = ServerValue.increment(1L)
                ups["users/$uid/kills"] = ServerValue.increment(a.kills)
                ups["leaderboard/$uid/username"] = a.name
                if (a.prize > 0) {
                    ups["users/$uid/winning"] = ServerValue.increment(a.prize)
                    ups["users/$uid/earnings"] = ServerValue.increment(a.prize)
                    ups["users/$uid/wins"] = ServerValue.increment(1L)
                    if (autoRank) {
                        ups["leaderboardCycle/$lbk/$uid/username"] = a.name; ups["leaderboardCycle/$lbk/$uid/score"] = ServerValue.increment(a.prize)
                        // Per-game Top Players list (player app -> Top Players -> game card)
                        val gid = str(m["gameId"])
                        if (gid.isNotBlank()) { ups["leaderboardGame/$lbk/$gid/$uid/username"] = a.name; ups["leaderboardGame/$lbk/$gid/$uid/score"] = ServerValue.increment(a.prize) }
                    }
                    txRec(uid, "win", a.prize, "Winnings: ${str(m["title"])} #${str(m["matchNo"])}").let { ups[it.first] = it.second }
                }
            }
            ups["matches/$mid/status"] = "completed"; ups["matches/$mid/resultDeclared"] = true
            db.updateChildren(ups).await()
            logAct("result_declared", mid, "${str(m["title"])} #${str(m["matchNo"])}")
            return "Result declared"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    /**
     * AFTER the result is already declared: gives winnings (and kills) to players the admin missed the first time.
     * Only INCREASES are allowed (nothing is ever taken back), and only the extra amount is credited - a player who was already
     * paid is not paid twice. Each extra credit gets its own "win" transaction, the match result is updated in the same atomic write.
     */
    suspend fun addMissedWinnings(mid: String, kills: Map<String, String>, prizes: Map<String, String>): String {
        if (!canAccess("results_manage")) return "Permission denied"
        try {
            val m = mp(get("matches/$mid"))
            if (m.isEmpty()) return "Match not found"
            if (!gameAllowed(str(m["gameId"]))) return "You do not have access to the game of this match"
            if (m["resultDeclared"] != true) return "Result is not declared yet - use DECLARE RESULT AND PAY"
            val slots = mp(m["slots"]); val old = mp(m["results"])
            val autoRank = try { get("leaderboardConfig/auto") != false } catch (_: Exception) { true }
            val lbk = lbKey(lbPeriod())
            val ups = HashMap<String, Any?>(); val per = HashMap<String, Agg>()
            val oldPrize = HashMap<String, Double>(); val counted = HashSet<String>()
            for ((k, v) in slots) {
                val uid = str(mp(v)["uid"]); val o = mp(old[k])
                if (o.isNotEmpty()) { counted.add(uid); oldPrize[uid] = (oldPrize[uid] ?: 0.0) + num(o["prize"]) }
            }
            for ((k, v) in slots) {
                val s = mp(v); val o = mp(old[k]); val uid = str(s["uid"])
                val ok = num(o["kills"]).toLong(); val op = num(o["prize"])
                val nk = kills[k]?.toLongOrNull() ?: ok; val np = prizes[k]?.toDoubleOrNull() ?: op
                val who = "${str(s["username"])} (${k.replace("t", "Team ").replace("_", " - ")})"
                if (nk < ok) return "$who: kills cannot be reduced after the result is declared"
                if (np < op - 1e-9) return "$who: prize cannot be reduced after the result is declared"
                if (nk == ok && Math.abs(np - op) < 1e-9) continue
                ups["matches/$mid/results/$k"] = mapOf("username" to s["username"], "ign" to s["ign"], "kills" to nk, "prize" to np)
                val a = per.getOrPut(uid) { Agg(str(s["username"])) }; a.kills += nk - ok; a.prize += np - op
            }
            if (per.isEmpty()) return "Nothing changed - enter kills / prize for the missed players first"
            var total = 0.0
            for ((uid, a) in per) {
                if (uid !in counted) ups["users/$uid/matchesPlayed"] = ServerValue.increment(1L)
                if (a.kills > 0) ups["users/$uid/kills"] = ServerValue.increment(a.kills)
                ups["leaderboard/$uid/username"] = a.name
                if (a.prize > 1e-9) {
                    total += a.prize
                    ups["users/$uid/winning"] = ServerValue.increment(a.prize)
                    ups["users/$uid/earnings"] = ServerValue.increment(a.prize)
                    if ((oldPrize[uid] ?: 0.0) <= 1e-9) ups["users/$uid/wins"] = ServerValue.increment(1L)
                    if (autoRank) {
                        ups["leaderboardCycle/$lbk/$uid/username"] = a.name; ups["leaderboardCycle/$lbk/$uid/score"] = ServerValue.increment(a.prize)
                        val gid = str(m["gameId"])
                        if (gid.isNotBlank()) { ups["leaderboardGame/$lbk/$gid/$uid/username"] = a.name; ups["leaderboardGame/$lbk/$gid/$uid/score"] = ServerValue.increment(a.prize) }
                    }
                    txRec(uid, "win", a.prize, "Winnings: ${str(m["title"])} #${str(m["matchNo"])}").let { ups[it.first] = it.second }
                }
            }
            db.updateChildren(ups).await()
            logAct("result_updated", mid, "${str(m["title"])} #${str(m["matchNo"])} +₹${money(total)} to ${per.size} player(s)")
            return "Result updated: ₹${money(total)} added to ${per.size} player(s)"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    /**
     * Cancels a match and refunds every player who joined: the coins go back to the same wallet part they were paid from
     * (deposit -> deposit, winning -> winning; the split is saved in each slot when the player joins). Slots of older joins
     * without a saved split are refunded to the deposit wallet. Everything is written in ONE atomic update together with
     * status=cancelled and refunded=true, so it can never refund twice or half-way. Returns an error text, or null on success.
     */
    suspend fun cancelMatchRefund(mid: String): String? {
        if (Session.role != "admin" && !(canAccess("matches_manage") && canAccess("balance_adjust")))
            return "Permission denied. Cancelling a match refunds coins, so it needs Matches manage + Balance adjust"
        try {
            val m = mp(get("matches/$mid"))
            if (m.isEmpty()) return "Match not found"
            if (!gameAllowed(str(m["gameId"]))) return "You do not have access to the game of this match"
            if (m["refunded"] == true) return null
            if (m["resultDeclared"] == true || str(m["status"]) == "completed") return "A completed match cannot be cancelled"
            val fee = if (str(m["type"]).equals("FREE", true)) 0.0 else num(m["entryFee"])
            val dep = HashMap<String, Double>(); val win = HashMap<String, Double>()
            for ((_, v) in mp(m["slots"])) {
                val s = mp(v); val uid = str(s["uid"]); if (uid.isBlank()) continue
                val d = num(s["dep"]); val w = num(s["win"])
                if (s.containsKey("dep") || s.containsKey("win")) { dep[uid] = (dep[uid] ?: 0.0) + d; win[uid] = (win[uid] ?: 0.0) + w }
                else dep[uid] = (dep[uid] ?: 0.0) + fee
            }
            val ups = HashMap<String, Any?>()
            var players = 0; var total = 0.0
            for (uid in (dep.keys + win.keys).toSet()) {
                val d = dep[uid] ?: 0.0; val w = win[uid] ?: 0.0
                if (d + w <= 1e-9) continue
                if (d > 1e-9) ups["users/$uid/deposited"] = ServerValue.increment(d)
                if (w > 1e-9) ups["users/$uid/winning"] = ServerValue.increment(w)
                txRec(uid, "refund", d + w, "Refund: ${str(m["title"])} #${str(m["matchNo"])} cancelled").let { ups[it.first] = it.second }
                players++; total += d + w
            }
            ups["matches/$mid/status"] = "cancelled"; ups["matches/$mid/refunded"] = true; ups["matches/$mid/cancelledAt"] = System.currentTimeMillis()
            db.updateChildren(ups).await()
            logAct("match_cancelled", mid, "${str(m["title"])} #${str(m["matchNo"])} refunded $players players, ${money(total)} coins")
            return null
        } catch (e: Exception) { return e.message ?: "Refund failed" }
    }

    // ---------- Player vs Player matches ----------

    /** Winning for a player match: MUST stay the same as Pvp.prize() in the player app. The admin app recomputes it, so a tampered stored value is never paid. */
    fun pvpPrize(fee: Double, players: Int, commissionPct: Double): Double {
        if (fee <= 0.0 || players < 2) return 0.0
        if (commissionPct > 0.0) return Math.floor(fee * players * (100.0 - commissionPct.coerceAtMost(100.0))) / 100.0
        val table = mapOf(5 to 8, 10 to 16, 20 to 35, 35 to 60, 50 to 85, 100 to 170, 150 to 250)
        val base = if (fee % 1.0 == 0.0 && table.containsKey(fee.toInt())) table.getValue(fee.toInt()).toDouble()
        else { val k = table.keys.filter { it <= fee }.maxOrNull() ?: table.keys.minOrNull()!!; Math.floor(fee * table.getValue(k) / k) }
        return base * players / 2.0
    }

    /** Locks the match for payment: true only for the first admin who gets here, so two admins can never pay the same match twice. */
    private suspend fun claimPvp(mid: String): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        db.child("pvpMatches/$mid/resultDeclared").runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result { if (d.value == true) return Transaction.abort(); d.value = true; return Transaction.success(d) }
            override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) { c.resumeWith(Result.success(ok && e == null)) }
        })
    }

    /** Pays the chosen winner of a player match and closes it. Everything is written in ONE atomic update, so it can never pay twice. */
    suspend fun declarePvp(mid: String, winnerUid: String): String {
        if (!canAccess("results_manage")) return "Permission denied"
        try {
            val m = mp(get("pvpMatches/$mid"))
            if (m.isEmpty()) return "Match not found"
            if (!pvpGameAllowed(str(m["gameId"]))) return "You do not have access to the game of this match"
            if (m["resultDeclared"] == true || str(m["status"]) == "completed") return "Result already declared"
            if (str(m["status"]) == "cancelled") return "This match is cancelled"
            val players = mp(m["players"]).values.map { mp(it) }
            val winner = players.firstOrNull { str(it["uid"]) == winnerUid } ?: return "Winner is not a player of this match"
            if (players.size < 2) return "Only one player joined. Cancel the match to refund instead"
            // Paid by the players who really joined (a match that did not fill up pays less than the full-room amount).
            val prize = pvpPrize(num(m["entryFee"]), players.size, num(m["commission"]))
            val ups = HashMap<String, Any?>()
            val autoRank = try { get("leaderboardConfig/auto") != false } catch (_: Exception) { true }
            val lbk = lbKey(lbPeriod())
            val title = pvpLabel(m)
            for (p in players) { val u = str(p["uid"]); if (u.isNotBlank()) ups["users/$u/matchesPlayed"] = ServerValue.increment(1L) }
            if (prize > 0) {
                ups["users/$winnerUid/winning"] = ServerValue.increment(prize)
                ups["users/$winnerUid/earnings"] = ServerValue.increment(prize)
                ups["users/$winnerUid/wins"] = ServerValue.increment(1L)
                if (autoRank) { ups["leaderboardCycle/$lbk/$winnerUid/username"] = str(winner["username"]); ups["leaderboardCycle/$lbk/$winnerUid/score"] = ServerValue.increment(prize) }
                txRec(winnerUid, "win", prize, "Winnings: $title").let { ups[it.first] = it.second }
            }
            ups["pvpMatches/$mid/status"] = "completed"; ups["pvpMatches/$mid/resultDeclared"] = true
            ups["pvpMatches/$mid/winnerUid"] = winnerUid; ups["pvpMatches/$mid/winnerName"] = str(winner["username"])
            ups["pvpMatches/$mid/prize"] = prize; ups["pvpMatches/$mid/declaredAt"] = System.currentTimeMillis()
            if (!claimPvp(mid)) return "Result already declared"
            try { db.updateChildren(ups).await() } catch (e: Exception) { try { db.child("pvpMatches/$mid/resultDeclared").setValue(false).await() } catch (_: Exception) { }; throw e }
            logAct("pvp_result_declared", mid, "$title winner ${str(winner["username"])} ₹${money(prize)}")
            return "Winner declared, ₹${money(prize)} added to ${str(winner["username"])}'s wallet"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    /** Cancels a player match and refunds every seat to the wallet part it was paid from (deposit -> deposit, winning -> winning). */
    suspend fun cancelPvp(mid: String): String {
        if (Session.role != "admin" && !(canAccess("results_manage") && canAccess("balance_adjust"))) return "Permission denied. Refunding needs Results manage + Balance adjust"
        try {
            val m = mp(get("pvpMatches/$mid"))
            if (m.isEmpty()) return "Match not found"
            if (!pvpGameAllowed(str(m["gameId"]))) return "You do not have access to the game of this match"
            if (m["resultDeclared"] == true || str(m["status"]) == "completed") return "A completed match cannot be cancelled"
            if (m["refunded"] == true || str(m["status"]) == "cancelled") return "Already cancelled"
            val dep = HashMap<String, Double>(); val win = HashMap<String, Double>()
            for ((_, v) in mp(m["players"])) {
                val s = mp(v); val uid = str(s["uid"]); if (uid.isBlank()) continue
                if (s.containsKey("dep") || s.containsKey("win")) { dep[uid] = (dep[uid] ?: 0.0) + num(s["dep"]); win[uid] = (win[uid] ?: 0.0) + num(s["win"]) }
                else dep[uid] = (dep[uid] ?: 0.0) + num(m["entryFee"])
            }
            val ups = HashMap<String, Any?>(); var total = 0.0
            for (uid in (dep.keys + win.keys).toSet()) {
                val d = dep[uid] ?: 0.0; val w = win[uid] ?: 0.0
                if (d + w <= 1e-9) continue
                if (d > 1e-9) ups["users/$uid/deposited"] = ServerValue.increment(d)
                if (w > 1e-9) ups["users/$uid/winning"] = ServerValue.increment(w)
                txRec(uid, "refund", d + w, "Refund: ${pvpLabel(m)} cancelled").let { ups[it.first] = it.second }
                total += d + w
            }
            ups["pvpMatches/$mid/status"] = "cancelled"; ups["pvpMatches/$mid/refunded"] = true; ups["pvpMatches/$mid/cancelledAt"] = System.currentTimeMillis()
            db.updateChildren(ups).await()
            logAct("pvp_cancelled", mid, "${pvpLabel(m)} refunded ₹${money(total)}")
            return "Match cancelled, ₹${money(total)} refunded"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    suspend fun adjust(uid: String, field: String, amt: Double, note: String): String {
        if (!canAccess("balance_adjust")) return "Permission denied"
        try {
            val ups = hashMapOf<String, Any?>("users/$uid/$field" to ServerValue.increment(amt))
            txRec(uid, "admin", amt, note).let { ups[it.first] = it.second }
            db.updateChildren(ups).await(); logAct("balance_adjust", uid, "$field ${money(amt)} ($note)"); return "Balance updated"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    suspend fun reward(uid: String, title: String, amt: Double): String {
        if (!canAccess("players_reward")) return "Permission denied"
        try {
            val rid = db.child("rewards/$uid").push().key!!
            val ups = hashMapOf<String, Any?>("rewards/$uid/$rid" to mapOf("title" to title, "amount" to amt, "ts" to System.currentTimeMillis()),
                "users/$uid/deposited" to ServerValue.increment(amt))
            txRec(uid, "reward", amt, "Reward: $title").let { ups[it.first] = it.second }
            db.updateChildren(ups).await(); logAct("reward", uid, "$title ${money(amt)}"); return "Reward given"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    suspend fun setUser(uid: String, field: String, v: Any): String {
        val permission = when (field) { "kyc", "banned" -> "players_moderate"; "theme" -> "players_theme"; else -> "players_view" }
        if (field !in setOf("kyc", "banned", "theme") && Session.role != "admin") return "Only an admin can change this field"
        if (!canAccess(permission)) return "Permission denied"
        try { db.child("users/$uid/$field").setValue(v).await(); logAct("user_$field", uid, v.toString()); return "Updated" } catch (e: Exception) { return e.message ?: "Failed" }
    }

    suspend fun setBan(uid: String, ban: Boolean, reason: String): String {
        if (!canAccess("players_moderate")) return "Permission denied"
        if (ban && reason.isBlank()) return "Write the ban reason"
        try {
            db.updateChildren(hashMapOf<String, Any?>("users/$uid/banned" to ban, "users/$uid/banReason" to (if (ban) reason.trim() else null))).await()
            logAct(if (ban) "user_banned" else "user_unbanned", uid, reason.trim()); return if (ban) "Player banned" else "Player unbanned"
        } catch (e: Exception) { return e.message ?: "Failed" }
    }

    suspend fun lbPeriod(): String = try { (get("leaderboardConfig/period") as? String)?.takeIf { it in setOf("daily", "weekly", "monthly") } ?: "weekly" } catch (_: Exception) { "weekly" }

    suspend fun setLeaderboardPeriod(period: String): String {
        if (!canAccess("leaderboard_manage")) return "Permission denied"
        if (period !in setOf("daily", "weekly", "monthly")) return "Invalid period"
        return try {
            db.child("leaderboardConfig/period").setValue(period).await()
            logAct("leaderboard_period", "leaderboard", "reset cycle=$period")
            "Leaderboard resets $period"
        } catch (e: Exception) { e.message ?: "Failed" }
    }

    /** Clears the persistent leaderboard only when an authorized admin explicitly resets it. */
    suspend fun resetLB(): String {
        if (!canAccess("ranking_reset") && !canAccess("leaderboard_manage")) return "Permission denied"
        return try {
            db.child("leaderboardCycle").removeValue().await()
            db.child("leaderboardGame").removeValue().await()
            logAct("leaderboard_reset", "leaderboard", "all cycles"); "Leaderboard reset"
        } catch (e: Exception) { e.message ?: "Failed" }
    }

    suspend fun setLeaderboardScore(uid: String, score: Double): String {
        if (!canAccess("leaderboard_manage")) return "Permission denied"
        if (uid.isBlank() || !score.isFinite() || score < 0) return "Invalid score"
        return try {
            val k = lbKey(lbPeriod())
            db.child("leaderboardCycle/$k/$uid/score").setValue(score).await()
            logAct("leaderboard_score_set", uid, "$k=${money(score)}")
            "Leaderboard score saved"
        } catch (e: Exception) { e.message ?: "Failed" }
    }

    /** Manually adds a player to one game's ranking and optionally credits the non-withdrawable join wallet. */
    suspend fun addGameLeaderboardEntry(gameId: String, uid: String, username: String, score: Double, walletBonus: Double): String {
        if (!canAccess("leaderboard_manage")) return "Permission denied"
        if (walletBonus > 0 && !canAccess("balance_adjust")) return "Permission denied: balance adjustment required"
        if (gameId.isBlank() || uid.isBlank() || username.isBlank() || !score.isFinite() || score <= 0 || !walletBonus.isFinite() || walletBonus < 0)
            return "Enter a valid player, score, and wallet amount"
        return try {
            val cycle = lbKey(lbPeriod())
            val ups = hashMapOf<String, Any?>(
                "leaderboardGame/$cycle/$gameId/$uid/username" to username,
                "leaderboardGame/$cycle/$gameId/$uid/score" to ServerValue.increment(score)
            )
            if (walletBonus > 0) {
                ups["users/$uid/deposited"] = ServerValue.increment(walletBonus)
                txRec(uid, "admin", walletBonus, "Leaderboard bonus").let { ups[it.first] = it.second }
            }
            db.updateChildren(ups).await()
            logAct("leaderboard_player_added", uid, "game=$gameId score=+$score wallet=+$walletBonus")
            if (walletBonus > 0) "Player ranked and ₹${money(walletBonus)} added to join wallet" else "Player added to leaderboard"
        } catch (e: Exception) { e.message ?: "Failed" }
    }

    /**
     * Sets ONE player's exact score on ONE game's Top Players list. Only the leaderboard changes - the player's wallet is NOT touched.
     * The list adds up every cycle, so the old entries of that player/game in other cycles are removed and the new total is written to the
     * current cycle. Score 0 removes the player from the list.
     */
    suspend fun setGameLeaderboardScore(gameId: String, uid: String, username: String, score: Double): String {
        if (!canAccess("leaderboard_manage")) return "Permission denied"
        if (gameId.isBlank() || uid.isBlank() || !score.isFinite() || score < 0) return "Enter a valid score"
        return try {
            val cycle = lbKey(lbPeriod())
            val ups = hashMapOf<String, Any?>()
            mp(get("leaderboardGame")).forEach { (c, v) -> if (c != cycle && mp(mp(v)[gameId])[uid] != null) ups["leaderboardGame/$c/$gameId/$uid"] = null }
            if (score > 0) {
                ups["leaderboardGame/$cycle/$gameId/$uid/username"] = username
                ups["leaderboardGame/$cycle/$gameId/$uid/score"] = score
            } else ups["leaderboardGame/$cycle/$gameId/$uid"] = null
            db.updateChildren(ups).await()
            logAct("leaderboard_game_score_set", uid, "game=$gameId score=${money(score)} (wallet not changed)")
            "Leaderboard score saved"
        } catch (e: Exception) { e.message ?: "Failed" }
    }

    suspend fun creditLeaderboardWallet(uid: String, amt: Double, gameId: String): String {
        if (!canAccess("balance_adjust")) return "Permission denied"
        if (uid.isBlank() || !amt.isFinite() || amt <= 0) return "Enter a positive coin amount"
        return adjust(uid, "deposited", amt, "Leaderboard bonus ($gameId)")
    }

    /** How many players each game's Top Players list shows in the player app (1-100, default 10). */
    suspend fun setLeaderboardTopN(n: Int): String {
        if (!canAccess("leaderboard_manage")) return "Permission denied"
        if (n !in 1..100) return "Enter a number from 1 to 100"
        return try {
            db.child("leaderboardConfig/topN").setValue(n).await()
            logAct("leaderboard_topn", "leaderboard", "players per game=$n"); "Top Players per game set to $n"
        } catch (e: Exception) { e.message ?: "Failed" }
    }

    suspend fun setLeaderboardAuto(enabled: Boolean): String {
        if (!canAccess("leaderboard_manage")) return "Permission denied"
        return try {
            db.child("leaderboardConfig/auto").setValue(enabled).await()
            logAct("leaderboard_auto", "leaderboard", "enabled=$enabled; new match-result prizes ${if (enabled) "add to" else "do not add to"} rank scores")
            "Automatic leaderboard updates ${if (enabled) "enabled" else "disabled"}"
        } catch (e: Exception) { e.message ?: "Failed" }
    }
}
