package com.bestwinesport.staff

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Net { NONE, UNVERIFIED, OK }

/** NONE = no network at all, UNVERIFIED = connected to Wi-Fi/mobile but Android says there is no working internet, OK = internet works. */
private fun netOf(ctx: Context): Net {
    return try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return Net.NONE
        val caps = cm.getNetworkCapabilities(n) ?: return Net.NONE
        when {
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Net.NONE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Net.OK
            else -> Net.UNVERIFIED
        }
    } catch (_: Exception) { Net.OK }
}

fun Context.findActivity(): android.app.Activity? {
    var c = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}

/**
 * true while the phone really has internet. Reacts at once when the network is switched off, and also when Wi-Fi / mobile data is
 * connected but there is no internet behind it. If Android has not verified the network yet, the live Firebase connection decides.
 */
@Composable
fun rememberOnline(): Boolean {
    val ctx = LocalContext.current
    var net by remember { mutableStateOf(netOf(ctx)) }
    var fb by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        fun refresh() { main.post { net = netOf(ctx) }; main.postDelayed({ net = netOf(ctx) }, 400) }   // second check: Android may still list the lost network for a moment
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refresh() }
            override fun onLost(network: Network) { refresh() }
            override fun onUnavailable() { refresh() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
        }
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) { }
        val ref = try { com.google.firebase.database.FirebaseDatabase.getInstance().getReference(".info/connected") } catch (_: Exception) { null }
        val fl = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(s: com.google.firebase.database.DataSnapshot) { fb = s.getValue(Boolean::class.java) == true }
            override fun onCancelled(e: com.google.firebase.database.DatabaseError) { }
        }
        try { ref?.addValueEventListener(fl) } catch (_: Exception) { }
        refresh()
        onDispose {
            main.removeCallbacksAndMessages(null)
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) { }
            try { ref?.removeEventListener(fl) } catch (_: Exception) { }
        }
    }
    return when (net) { Net.NONE -> false; Net.OK -> true; Net.UNVERIFIED -> fb }
}

fun restartApp(ctx: Context) {
    val act = ctx.findActivity() ?: return
    act.startActivity(Intent(act, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    act.finish()
}

/**
 * Full-screen page shown at once whenever the internet is gone, on every screen (same page as the player app):
 * black page, app name on top, red "No Internet Connection." in the middle. When the internet is back the app restarts by itself.
 */
@Composable
fun OfflineGate() {
    val ctx = LocalContext.current
    val online = rememberOnline()
    var wasOffline by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (!online) wasOffline = true
        else if (wasOffline) restartApp(ctx)
    }
    if (online) return
    BackHandler { ctx.findActivity()?.finish() }
    val appName = remember { try { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() } catch (_: Exception) { "BESTWIN ESPORTS" } }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
        Text(appName, Modifier.fillMaxWidth().statusBarsPadding().padding(top = 18.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("No Internet Connection.", Modifier.align(Alignment.Center), color = Color.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
