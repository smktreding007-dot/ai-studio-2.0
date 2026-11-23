package com.bestwinesport.admin

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay

private enum class Net { NONE, UNVERIFIED, OK }

/** NONE = no network at all, UNVERIFIED = connected to Wi-Fi/mobile but Android says there is no working internet yet, OK = internet works. */
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
 * Tracks live internet connectivity only while the app is in the foreground, with a short resume grace period so
 * backgrounding or brief socket handovers never flash a false "No Internet Connection" lock screen.
 */
@Composable
fun rememberOnline(recheckTick: Int = 0): Boolean {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var net by remember { mutableStateOf(netOf(ctx)) }
    var fb by remember { mutableStateOf(true) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var stableOnline by remember { mutableStateOf(true) }

    DisposableEffect(lifecycle, recheckTick) {
        val main = Handler(Looper.getMainLooper())
        fun refresh() {
            main.post { net = netOf(ctx) }
            main.postDelayed({ net = netOf(ctx) }, 450)
        }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refresh() }
            override fun onLost(network: Network) { refresh() }
            override fun onUnavailable() { refresh() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
        }
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) { }

        val ref = try { FirebaseDatabase.getInstance().getReference(".info/connected") } catch (_: Exception) { null }
        val fl = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { fb = s.getValue(Boolean::class.java) == true }
            override fun onCancelled(e: DatabaseError) { }
        }
        try { ref?.addValueEventListener(fl) } catch (_: Exception) { }

        val lo = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    try { FirebaseDatabase.getInstance().goOnline() } catch (_: Exception) { }
                    refresh()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(lo)
        refresh()
        onDispose {
            lifecycle.removeObserver(lo)
            main.removeCallbacksAndMessages(null)
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) { }
            try { ref?.removeEventListener(fl) } catch (_: Exception) { }
        }
    }

    val rawOnline = when (net) {
        Net.NONE -> false
        Net.OK -> true
        Net.UNVERIFIED -> fb
    }
    LaunchedEffect(rawOnline, net, resumed, recheckTick) {
        if (!resumed || rawOnline) {
            stableOnline = true
        } else {
            // Grace window handles Android's brief network/Firebase re-validation right after returning from background.
            delay(if (net == Net.NONE) 1800L else 4500L)
            if (resumed) stableOnline = false
        }
    }
    return stableOnline
}

fun restartApp(ctx: Context) {
    val act = ctx.findActivity() ?: return
    act.startActivity(Intent(act, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    act.finish()
}

/**
 * Shown only when internet is genuinely unavailable in foreground; hides seamlessly when internet returns without restarting
 * the activity so current admin forms and screens stay intact across background switches.
 */
@Composable
fun OfflineGate() {
    val ctx = LocalContext.current
    var retryTick by remember { mutableIntStateOf(0) }
    val online = rememberOnline(retryTick)
    LaunchedEffect(online) {
        if (online) {
            try { FirebaseDatabase.getInstance().goOnline() } catch (_: Exception) { }
        }
    }
    if (online) return
    BackHandler { ctx.findActivity()?.finish() }
    val appName = remember { try { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() } catch (_: Exception) { "DOUx24 Admin" } }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
        Text(appName, Modifier.fillMaxWidth().statusBarsPadding().padding(top = 18.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No Internet Connection.", color = Color.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Button(onClick = {
                try { FirebaseDatabase.getInstance().goOnline() } catch (_: Exception) { }
                retryTick++
            }, modifier = Modifier.testTag("offline_retry_button")) {
                Text("RETRY")
            }
        }
    }
}
