@file:androidx.media3.common.util.UnstableApi

package com.bestwinesport.staff

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavType
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    var pendingAdminRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readAdminRoute(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("admin_notifications", MODE_PRIVATE)
            if (!prefs.getBoolean("permission_prompted", false)) {
                prefs.edit().putBoolean("permission_prompted", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4107)
            }
        }
        Themes.load(this)
        setContent { AdminTheme { Surface(Modifier.fillMaxSize().background(BgBrush), color = androidx.compose.ui.graphics.Color.Transparent) { Box(Modifier.fillMaxSize()) { AdminNav(); OfflineGate() } } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAdminRoute(intent)
    }

    private fun readAdminRoute(intent: Intent?) {
        pendingAdminRoute = intent?.getStringExtra("admin_route")?.takeIf { it in setOf("withdrawals", "supporttickets") }
    }
}

@Composable
fun AdminNav() {
    val nav = rememberNavController()
    val activity = LocalContext.current as? MainActivity
    val pendingRoute = activity?.pendingAdminRoute
    val currentEntry by nav.currentBackStackEntryAsState()
    LaunchedEffect(pendingRoute, currentEntry?.destination?.route) {
        val route = activity?.pendingAdminRoute
        if (route != null && Session.role in setOf("admin", "staff") && currentEntry?.destination?.route != "splash") {
            nav.navigate(route) { launchSingleTop = true }
            activity.pendingAdminRoute = null
        }
    }
    NavHost(nav, "splash",
        enterTransition = { slideIntoContainer(androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) },
        exitTransition = { slideOutOfContainer(androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing), targetOffset = { it / 4 }) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)) },
        popEnterTransition = { slideIntoContainer(androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing), initialOffset = { it / 4 }) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) },
        popExitTransition = { slideOutOfContainer(androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)) }
    ) {
        composable("splash") { AdminSplash(nav) }
        composable("login") { StaffLogin(nav) }
        composable("home") { AdminHome(nav) }
        composable("dashboard") { PermissionGate(nav, "dashboard_view") { DashboardScreen(nav) } }
        composable("appcontrol") { PermissionGate(nav, "app_control") { AppControlScreen(nav) } }
        composable("logs") { PermissionGate(nav, "activity_view") { LogsScreen(nav) } }
        composable("usertx/{uid}") { entry -> PermissionGate(nav, "transactions_view") { UserTxScreen(nav, entry.arguments?.getString("uid") ?: "") } }
        composable("notify?mid={mid}&user={user}", arguments = listOf(
            navArgument("mid") { type = NavType.StringType; defaultValue = "" }, navArgument("user") { type = NavType.StringType; defaultValue = "" })) {
            val entry = it
            PermissionGate(nav, "notifications_send") { NotifyScreen(nav, entry.arguments?.getString("mid") ?: "", entry.arguments?.getString("user") ?: "") } }
        composable("crud/{k}") {
            val cfg = CRUDS[it.arguments?.getString("k")]!!
            val permissions = when (cfg.key) {
                "matches" -> arrayOf("matches_manage", "matches_delete")
                "games", "pvpgames", "sections" -> arrayOf("games_manage", "games_delete")
                "banners" -> arrayOf("banners_manage", "banners_delete")
                "announcements" -> arrayOf("announcements_manage", "announcements_delete")
                else -> arrayOf("settings_manage")
            }
            PermissionAnyGate(nav, *permissions) { CrudScreen(nav, cfg) }
        }
        composable("automatch") { PermissionGate(nav, "matches_manage") { AutoMatchScreen(nav) } }
        composable("pvpresults") { PermissionGate(nav, "results_manage") { PvpResultsScreen(nav) } }
        composable("pvpresult/{id}") { entry -> PermissionGate(nav, "results_manage") { PvpResultScreen(nav, entry.arguments?.getString("id") ?: "") } }
        composable("resultsqueue") { PermissionGate(nav, "results_manage") { ResultsQueueScreen(nav) } }
        composable("results/{id}") { entry -> PermissionGate(nav, "results_manage") { ResultsScreen(nav, entry.arguments?.getString("id") ?: "") } }
        composable("leaderboard") { PermissionAnyGate(nav, "leaderboard_manage", "ranking_reset") { LeaderboardAdminScreen(nav) } }
        composable("spin") { PermissionGate(nav, "spin_manage") { SpinAdminScreen(nav) } }
        composable("themeeditor") { PermissionGate(nav, "settings_manage") { ThemeEditorScreen(nav) } }
        composable("worldchatroom") { PermissionGate(nav, "notifications_send") { WorldChatRoomScreen(nav) } }
        composable("worldchatadmin") { PermissionGate(nav, "notifications_send") { WorldChatAdminScreen(nav) } }
        composable("notifycard") { PermissionGate(nav, "settings_manage") { NotifyCardAdminScreen(nav) } }
        composable("deposits") { PermissionGate(nav, "balance_adjust") { DepositRequestsScreen(nav) } }
        composable("withdrawals") { PermissionAnyGate(nav, "withdrawals_review", "withdrawals_export") { WithdrawalsScreen(nav) } }
        composable("users") { PermissionAnyGate(nav, "players_view", "players_export") { UsersScreen(nav) } }
        composable("settings") { PermissionGate(nav, "settings_manage") { SettingsScreen(nav) } }
        composable("supporttickets") { PermissionGate(nav, "support_manage") { SupportTicketsAdminScreen(nav) } }
        composable("supportticket/{uid}/{ticketId}") { entry -> PermissionGate(nav, "support_manage") {
            SupportTicketAdminDetailScreen(nav, entry.arguments?.getString("uid") ?: "", entry.arguments?.getString("ticketId") ?: "")
        } }
    }
}

@androidx.media3.common.util.UnstableApi
@Composable
fun AdminSplash(nav: NavController) {
    val activity = LocalContext.current as? MainActivity
    val ctx = LocalContext.current.applicationContext
    // Everything is decided from what is saved on the phone (same video the admin uploads for the player app).
    val videoFile = remember { SplashVideo.playable(ctx) }
    var dest by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fadeOut = remember { androidx.compose.animation.core.Animatable(0f) }
    var videoStarted by remember { mutableStateOf(false) }
    var videoMs by remember { mutableStateOf(0) }
    var videoDone by remember { mutableStateOf(videoFile == null) }
    var stopped by remember { mutableStateOf(false) }   // video + its sound are switched OFF the moment SKIP / end / error happens
    fun goNext() {
        val d = dest ?: return
        if (left) return
        left = true
        nav.navigate(d) { popUpTo("splash") { inclusive = true } }
        activity?.pendingAdminRoute = null
    }
    fun finishVideo() {
        if (videoDone || stopped) return
        stopped = true                                   // stop the sound + picture immediately (not after the fade)
        scope.launch { fadeOut.animateTo(1f, androidx.compose.animation.core.tween(200)); videoDone = true }
    }
    LaunchedEffect(Unit) {
        SplashVideo.refreshAsync(ctx)          // background: latest video + settings for the next start
        val u = AR.auth.currentUser
        var d = "login"
        if (u != null) {
            // Never wait forever: a slow / broken network must not freeze the start screen. null = timed out -> go to login, keep the account signed in.
            val ok = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                try { AR.refreshSession() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            }
            if (ok == true) d = activity?.pendingAdminRoute ?: "home"
            else if (ok == false) { try { AR.auth.signOut() } catch (_: Exception) { }; Session.clear() }
        }
        if (videoFile == null) kotlinx.coroutines.delay(900)
        dest = d
    }
    LaunchedEffect(dest, videoDone) { if (dest != null && videoDone) goNext() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(15_000); if (dest == null) dest = "login" }   // last safety net
    if (videoFile != null && !videoDone) {
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(12_000); if (!videoStarted) finishVideo() }
        LaunchedEffect(videoStarted, videoMs) { if (videoStarted) { kotlinx.coroutines.delay(videoMs + 3000L); finishVideo() } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SplashVideoPlayer(videoFile, stopped, onStarted = { ms -> videoMs = ms; videoStarted = true }, onDone = { finishVideo() })
            if (!videoStarted) CircularProgressIndicator(Modifier.align(Alignment.Center).size(34.dp), color = Color.White, strokeWidth = 3.dp)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fadeOut.value }.background(Color.Black))
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(14.dp).clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.45f)).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                .clickable { finishVideo() }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                Text("SKIP", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AdminRotatingLoadingLogo(size = 76)
            Spacer(Modifier.height(12.dp))
            Text("DOUx24 STAFF", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Red)
            Spacer(Modifier.height(10.dp)); Text("Loading...", color = Color.Gray, fontSize = 13.sp)
        }
    }
}

/** Full-screen, lifecycle-aware player for the locally cached start-screen video (same as the player app). */
@androidx.media3.common.util.UnstableApi
@Composable
private fun SplashVideoPlayer(file: java.io.File, stopped: Boolean, onStarted: (Int) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val currentOnStarted by rememberUpdatedState(onStarted)
    val currentOnDone by rememberUpdatedState(onDone)
    val currentStopped by rememberUpdatedState(stopped)
    val player = remember(file.absolutePath) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    DisposableEffect(player, lifecycle) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY -> {
                        val durationMs = player.duration.takeIf { it > 0L } ?: 1_000L
                        currentOnStarted(durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        if (!currentStopped) player.playWhenReady = true
                    }
                    androidx.media3.common.Player.STATE_BUFFERING, androidx.media3.common.Player.STATE_IDLE -> Unit
                    androidx.media3.common.Player.STATE_ENDED -> currentOnDone()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { currentOnDone() }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> player.pause()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (!currentStopped) player.play()
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            try { player.stop() } catch (_: Exception) { }
            player.release()
        }
    }
    LaunchedEffect(stopped) { if (stopped) { try { player.volume = 0f; player.pause() } catch (_: Exception) { } } }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            androidx.media3.ui.PlayerView(c).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { it.player = player }
    )
}
