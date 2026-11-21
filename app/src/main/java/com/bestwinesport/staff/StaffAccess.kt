package com.bestwinesport.staff

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

data class StaffPermission(val key: String, val title: String, val group: String, val detail: String)

object StaffAccess {
    val catalog = listOf(
        StaffPermission("dashboard_view", "Dashboard", "Overview", "View operational summary."),
        StaffPermission("admin_theme", "Change own admin-panel theme", "Overview", "Changes only this device's admin-panel appearance."),
        StaffPermission("activity_view", "Activity log", "Overview", "View recent admin and staff actions."),
        StaffPermission("matches_manage", "Create and edit matches", "Tournaments", "Manage match details, schedules, and room credentials."),
        StaffPermission("matches_delete", "Delete matches", "Tournaments", "Permanently remove a match and its room credentials."),
        StaffPermission("results_manage", "Declare and pay results", "Tournaments", "Publish results, update rankings, and credit winnings."),
        StaffPermission("games_manage", "Create and edit games", "Content", "Manage game listings and visibility."),
        StaffPermission("games_delete", "Delete games", "Content", "Remove a game listing."),
        StaffPermission("banners_manage", "Create and edit banners", "Content", "Manage promotional banners and images."),
        StaffPermission("banners_delete", "Delete banners", "Content", "Remove a banner."),
        StaffPermission("announcements_manage", "Create and edit announcements", "Content", "Publish or update in-app announcements."),
        StaffPermission("announcements_delete", "Delete announcements", "Content", "Remove an announcement."),
        StaffPermission("notifications_send", "Send push notifications", "Content", "Send notifications to players, match groups, or all players."),
        StaffPermission("support_manage", "Manage customer support tickets", "Support", "Read the support inbox, reply to players, and change ticket status."),
        StaffPermission("media_upload", "Upload images", "Content", "Upload images through the configured image backend."),
        StaffPermission("media_delete", "Delete uploaded images", "Content", "Delete image files from the configured image backend."),
        StaffPermission("players_view", "View/search players", "Players", "View player profiles and account status."),
        StaffPermission("players_export", "Export player list", "Players", "Export player contact and account data to CSV."),
        StaffPermission("balance_adjust", "Adjust player balances", "Players", "Add or deduct deposited/winning balance."),
        StaffPermission("players_reward", "Give player rewards", "Players", "Credit a reward to a player."),
        StaffPermission("players_moderate", "Ban/unban and KYC actions", "Players", "Change player moderation and KYC flags."),
        StaffPermission("players_theme", "Change a player's theme", "Players", "Set a theme override for an individual player."),
        StaffPermission("transactions_view", "View player transactions", "Players", "Inspect a player's transaction history."),
        StaffPermission("withdrawals_review", "Process withdrawals", "Finance", "Mark withdrawals paid or reject and refund them."),
        StaffPermission("withdrawals_export", "Export withdrawal requests", "Finance", "Export withdrawal request data."),
        StaffPermission("settings_manage", "Edit app settings and default theme", "App controls", "Manage app content, support details, payment settings, and the default player theme."),
        StaffPermission("app_control", "Maintenance and version control", "App controls", "Enable maintenance mode and change app update settings."),
        StaffPermission("leaderboard_manage", "Edit leaderboard and automation", "App controls", "Manually set ranking scores and enable/disable automatic rank increments from match results."),
        StaffPermission("ranking_reset", "Reset weekly/monthly rankings", "App controls", "Reset ranking periods."),
        StaffPermission("spin_manage", "Manage Lucky Wheel (spin)", "App controls", "Turn the spin on/off, set the task, limits, reward lock, wheel rewards and per-player lock."),
    )
    val keys = catalog.map { it.key }.toSet()
    fun emptyGrants(): Map<String, Any?> = catalog.associate { it.key to false }
}

object PlayerThemeChoices {
    /** Old theme IDs (renamed to BESTWIN) that may still be saved in Firebase: mapped to the new ID. */
    private val legacyIds = mapOf("arise_battle" to "bestwin_black_red", "clash_orange" to "bestwin_black_orange", "victory_gold" to "bestwin_gold_purple", "warx_fire" to "bestwin_fire", "esports_blue" to "bestwin_blue", "skillwar_violet" to "bestwin_violet_neon", "supremex_indigo" to "bestwin_indigo_tiles")
    fun canonical(id: String): String = legacyIds[id] ?: id
    /** true = the player app is Liquid Glass only: the admin picker lists just that theme. */
    const val forced = false
    val defaultId = if (forced) "liquid_glass" else "royal_purple"
    private val allChoices = listOf(
        "royal_purple" to "Royal Purple",
        "ocean_blue" to "Ocean Blue",
        "forest_green" to "Forest Green",
        "crimson" to "Crimson",
        "emerald_luxe" to "Emerald Luxe",
        "rose_pink" to "Rose Pink",
        "amethyst" to "Amethyst",
        "royal_gold" to "Royal Gold",
        "ice_teal" to "Ice Teal",
        "sky_blue" to "Sky Blue",
        "midnight_black" to "Midnight Black",
        "bestwin_black_red" to "DOUx24 (Black & Red)",
        "bestwin_gold" to "DOUx24 Gold",
        "neon_cyber" to "Neon Cyber",
        "sunset_orange" to "Sunset Orange",
        "blood_moon" to "Blood Moon",
        "steel_blue" to "Steel Blue",
        "toxic_lime" to "Toxic Lime",
        "yellow_flame" to "Yellow Flame",
        "bestwin_black_orange" to "DOUx24 (Black & Orange)",
        "bestwin_gold_purple" to "DOUx24 (Gold & Purple)",
        "bestwin_red_gold" to "DOUx24 (Black, Gold & Red)",
        "bestwin_fire" to "DOUx24 Fire",
        "bestwin_blue" to "DOUx24 Blue",
        "legends_navy" to "Legends Navy",
        "domain_violet" to "Domain Violet",
        "hot_pink" to "Hot Pink",
        "aqua_mint" to "Aqua Mint",
        "purple_neon_blue" to "Purple + Neon Blue",
        "black_red_fire" to "Black + Red Fire",
        "black_gold" to "Black + Gold",
        "cyberpunk_neon" to "Cyberpunk Neon (Purple + Cyan + Pink)",
        "black_orange_flame" to "Black + Orange Flame",
        "dark_blue_electric" to "Dark Blue + Electric Blue",
        "black_neon_green" to "Black + Neon Green",
        "minimal_indigo" to "Minimal Dark Premium (Black + Indigo)",
        "midnight_teal" to "Midnight Slate (Black Navy + Teal)",
        "midnight_violet" to "Midnight Violet (Black Navy + Violet)",
        "bestwin_violet_neon" to "DOUx24 (Black Violet + Neon Outline)",
        "bestwin_indigo_tiles" to "DOUx24 (Black + Indigo Tiles)",
        "neon_pink_outline" to "Neon Pink Outline (Black Plum + Hot Pink)",
        "royal_gold_outline" to "Royal Gold Outline (Black + Gold Edge)",
        "bestwin_silver" to "DOUx24 Silver (Black + White/Silver, new logo)",
        "liquid_glass" to "Liquid Glass (gradient + frosted cards)",
        "clear_glass" to "Clear Glass (transparent - see-through cards + bar)",
        "custom" to "Custom colors"
    )
    val choices = if (forced) allChoices.filter { it.first == "liquid_glass" } else allChoices
    /** background, card, accent - used only for the preview tiles in the admin theme gallery. */
    val swatches: Map<String, Triple<Long, Long, Long>> = mapOf(
        "royal_purple" to Triple(0xFF10002B, 0xFF1A0536, 0xFF673AD1),
        "ocean_blue" to Triple(0xFF06142E, 0xFF0C2250, 0xFF1E6BFF), "forest_green" to Triple(0xFF06180F, 0xFF0C2A1B, 0xFF1FA45B),
        "crimson" to Triple(0xFF1A0306, 0xFF2B0A10, 0xFFD81B3C),
        "emerald_luxe" to Triple(0xFF02120E, 0xFF072A21, 0xFF00C98D),
        "rose_pink" to Triple(0xFF14040C, 0xFF260918, 0xFFFF3D8B), "amethyst" to Triple(0xFF0B0220, 0xFF170838, 0xFF9D4EDD), "royal_gold" to Triple(0xFF0E0720, 0xFF1B0F3A, 0xFFFFC83D),
        "ice_teal" to Triple(0xFF03100F, 0xFF082422, 0xFF2DD4BF),
        "sky_blue" to Triple(0xFF02070F, 0xFF081A2E, 0xFF38BDF8),
        "midnight_black" to Triple(0xFF000000, 0xFF121212, 0xFF7C4DFF),
        "bestwin_black_red" to Triple(0xFF060507, 0xFF14101A, 0xFFE11D2E),
        "bestwin_gold" to Triple(0xFF0A0805, 0xFF17120A, 0xFFF5B301),
        "neon_cyber" to Triple(0xFF02060F, 0xFF08142A, 0xFF00D1FF),
        "sunset_orange" to Triple(0xFF120703, 0xFF231009, 0xFFFF6A13),
        "blood_moon" to Triple(0xFF090000, 0xFF1A0505, 0xFFFF1F1F),
        "steel_blue" to Triple(0xFF080B10, 0xFF111A26, 0xFF4F8CFF),
        "toxic_lime" to Triple(0xFF060A02, 0xFF101A06, 0xFF7ED321),
        "yellow_flame" to Triple(0xFF0B0A02, 0xFF1A1704, 0xFFFACC15),
        "bestwin_black_orange" to Triple(0xFF050505, 0xFF141414, 0xFFFF7A00),
        "bestwin_gold_purple" to Triple(0xFF0A0414, 0xFF1A0B2E, 0xFFFFB800),
        "bestwin_red_gold" to Triple(0xFF070707, 0xFF151210, 0xFFE2B13C),
        "bestwin_fire" to Triple(0xFF0A0200, 0xFF1C0A04, 0xFFFF4D1A),
        "bestwin_blue" to Triple(0xFF03081A, 0xFF0A1640, 0xFF3B82F6),
        "legends_navy" to Triple(0xFF020617, 0xFF0B1530, 0xFF60A5FA),
        "domain_violet" to Triple(0xFF0A0212, 0xFF1A0833, 0xFFA855F7),
        "hot_pink" to Triple(0xFF12020A, 0xFF2A0817, 0xFFFF2E88),
        "aqua_mint" to Triple(0xFF011110, 0xFF06302C, 0xFF34E0A1),
        "purple_neon_blue" to Triple(0xFF0A0220, 0xFF160A3A, 0xFF1FB5FF), "black_red_fire" to Triple(0xFF050000, 0xFF160505, 0xFFE01B10),
        "black_gold" to Triple(0xFF050403, 0xFF14110A, 0xFFFFC21A), "cyberpunk_neon" to Triple(0xFF0A0118, 0xFF1A0833, 0xFF00F0FF),
        "black_orange_flame" to Triple(0xFF060300, 0xFF160B02, 0xFFFF7A00), "dark_blue_electric" to Triple(0xFF020A1F, 0xFF081A44, 0xFF1E90FF),
        "black_neon_green" to Triple(0xFF010401, 0xFF071207, 0xFF39FF14), "minimal_indigo" to Triple(0xFF08080C, 0xFF111118, 0xFF6366F1),
        "midnight_teal" to Triple(0xFF05070B, 0xFF0D111A, 0xFF4CC5BE),
        "midnight_violet" to Triple(0xFF06070E, 0xFF0E1020, 0xFFA78BFA),
        "bestwin_violet_neon" to Triple(0xFF07010F, 0xFF11052B, 0xFF9B5CFF),
        "bestwin_indigo_tiles" to Triple(0xFF03010A, 0xFF0E0A26, 0xFF6C4DFF),
        "neon_pink_outline" to Triple(0xFF0B0210, 0xFF1A0620, 0xFFFF4DA6),
        "royal_gold_outline" to Triple(0xFF050403, 0xFF110E08, 0xFFFFC83D),
        "bestwin_silver" to Triple(0xFF000000, 0xFF121214, 0xFFE6E8EC),
        "liquid_glass" to Triple(0xFF081433, 0xFF2A1A6B, 0xFF4FC3FF),
        "clear_glass" to Triple(0xFF0B86B5, 0xFF3A1B8F, 0xFF5CD6FF)
    )
    val perUserChoices = listOf("" to "Use global default") + choices
}

fun canAccess(permission: String): Boolean =
    Session.role == "admin" || (Session.role == "staff" && permission in Session.permissions)

fun canAccessAny(vararg permissions: String): Boolean = permissions.any(::canAccess)

@Composable
fun AccessDenied(nav: NavController) {
    Column(Modifier.fillMaxSize()) {
        ABar("Access restricted", nav)
        Text("Your staff account does not have permission for this feature. Ask an admin to grant access.", Modifier.padding(24.dp), fontSize = 14.sp)
    }
}

@Composable
fun PermissionGate(nav: NavController, permission: String, content: @Composable () -> Unit) {
    if (canAccess(permission)) content() else AccessDenied(nav)
}

@Composable
fun PermissionAnyGate(nav: NavController, vararg permissions: String, content: @Composable () -> Unit) {
    if (canAccessAny(*permissions)) content() else AccessDenied(nav)
}
