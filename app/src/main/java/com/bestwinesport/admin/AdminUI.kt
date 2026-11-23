package com.bestwinesport.admin

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.Flow

class Pal(val name: String, val dark: Boolean, val bg: Color, val card: Color, val field: Color, val accent: Color,
          val orange: Color, val red: Color, val green: Color, val bar: Color, val glass: Boolean = false)

/** false = all themes work. true = the admin app uses Liquid Glass only (the theme menu shows just that one). Set to false to bring the other themes back. */
const val FORCE_LIQUID_GLASS = false

object Themes {
    val list = listOf(
        // Normal: plain neutral dark (charcoal + white text + standard blue). Default for fresh installs.
        Pal("Normal", true, Color(0xFF121212), Color(0xFF1E1E1E), Color(0xFF2C2C2C), Color(0xFF3D7BFF), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF22A55B), Color(0xFF1A1A1A)),
        Pal("Royal Purple", true, Color(0xFF10002B), Color(0xFF1A0536), Color(0xFF2E1763), Color(0xFF673AD1), Color(0xFFE8731A), Color(0xFFF02D07), Color(0xFF1F8F55), Color(0xFF1A0036)),
        Pal("Ocean Blue", true, Color(0xFF06142E), Color(0xFF0C2250), Color(0xFF163A7A), Color(0xFF1E6BFF), Color(0xFFFFB300), Color(0xFFFF5252), Color(0xFF1FA971), Color(0xFF081C42)),
        Pal("Forest Green", true, Color(0xFF06180F), Color(0xFF0C2A1B), Color(0xFF14402B), Color(0xFF1FA45B), Color(0xFFF2A900), Color(0xFFE5533D), Color(0xFF2ECC71), Color(0xFF08251A)),
        Pal("Crimson", true, Color(0xFF1A0306), Color(0xFF2B0A10), Color(0xFF4A1520), Color(0xFFD81B3C), Color(0xFFFF8F00), Color(0xFFFF3B30), Color(0xFF2E9E5B), Color(0xFF2A060C)),
        Pal("Purple + Neon Blue", true, Color(0xFF0A0220), Color(0xFF160A3A), Color(0xFF24146A), Color(0xFF1FB5FF), Color(0xFFFFB300), Color(0xFFFF3B30), Color(0xFF1F8F55), Color(0xFF12063A)),
        Pal("Black + Red Fire", true, Color(0xFF050000), Color(0xFF160505), Color(0xFF2A0A0A), Color(0xFFFF2A1A), Color(0xFFFF9800), Color(0xFFFF3B30), Color(0xFF1F8F55), Color(0xFF0E0303)),
        Pal("Black + Gold", true, Color(0xFF050403), Color(0xFF14110A), Color(0xFF272014), Color(0xFFFFC21A), Color(0xFFFF9800), Color(0xFFFF3B30), Color(0xFF1F8F55), Color(0xFF0C0A06)),
        Pal("Cyberpunk Neon", true, Color(0xFF0A0118), Color(0xFF1A0833), Color(0xFF2D1058), Color(0xFF00F0FF), Color(0xFFFF2E9A), Color(0xFFFF3B30), Color(0xFF1F8F55), Color(0xFF130626)),
        Pal("Black + Orange Flame", true, Color(0xFF060300), Color(0xFF160B02), Color(0xFF2A1604), Color(0xFFFF7A00), Color(0xFFFFB020), Color(0xFFFF3B30), Color(0xFF1F8F55), Color(0xFF0E0701)),
        Pal("Dark Blue + Electric Blue", true, Color(0xFF020A1F), Color(0xFF081A44), Color(0xFF102C6E), Color(0xFF1E90FF), Color(0xFFFFB300), Color(0xFFFF3B30), Color(0xFF1F8F55), Color(0xFF051334)),
        Pal("Black + Neon Green", true, Color(0xFF010401), Color(0xFF071207), Color(0xFF0E240E), Color(0xFF39FF14), Color(0xFFFFC107), Color(0xFFFF3B30), Color(0xFF39FF14), Color(0xFF040A04)),
        Pal("Minimal Dark Premium", true, Color(0xFF08080C), Color(0xFF111118), Color(0xFF1C1C28), Color(0xFF6366F1), Color(0xFFF59E0B), Color(0xFFFF3B30), Color(0xFF1F8F55), Color(0xFF0C0C12)),
        Pal("Midnight Slate", true, Color(0xFF05070B), Color(0xFF0D111A), Color(0xFF151B29), Color(0xFF4CC5BE), Color(0xFFF5A94A), Color(0xFFFF4D4F), Color(0xFF40C060), Color(0xFF080B12)),
        Pal("Midnight Violet", true, Color(0xFF06070E), Color(0xFF0E1020), Color(0xFF181B33), Color(0xFFA78BFA), Color(0xFFFBBF24), Color(0xFFFB7185), Color(0xFF34D399), Color(0xFF090B18)),
        // Liquid Glass: gradient background + frosted translucent cards. Dialogs / menus stay solid (DialogC) so they are readable.
        Pal("Liquid Glass", true, Color(0xFF081433), Color(0x2EFFFFFF), Color(0x3DFFFFFF), Color(0xFF4FC3FF), Color(0xFFFFB84D), Color(0xFFFF5A5F), Color(0xFF2ECC71), Color(0xCC0B1B45), true),
        Pal("Clean Light", false, Color(0xFFF4F5FA), Color(0xFFFFFFFF), Color(0xFFE6E8F2), Color(0xFF5B3FD0), Color(0xFFD9660F), Color(0xFFD62E12), Color(0xFF1B7F4C), Color(0xFF3B2A8F))
    )
    val glassPal: Pal get() = list.first { it.glass }
    /** Themes shown in the theme menu. */
    val visible: List<Pal> get() = if (FORCE_LIQUID_GLASS) listOf(glassPal) else list
    var current by mutableStateOf(if (FORCE_LIQUID_GLASS) list.first { it.glass } else list[0])
    fun load(c: Context) { val n = c.getSharedPreferences("admin", Context.MODE_PRIVATE).getString("theme", ""); current = if (FORCE_LIQUID_GLASS) glassPal else (list.firstOrNull { it.name == n } ?: list[0]) }
    fun set(c: Context, p: Pal) { current = p; c.getSharedPreferences("admin", Context.MODE_PRIVATE).edit().putString("theme", p.name).apply() }
}

val Bg: Color get() = Themes.current.bg
val CardC: Color get() = Themes.current.card
val Field: Color get() = Themes.current.field
val Purple: Color get() = Themes.current.accent
val Orange: Color get() = Themes.current.orange
val Red: Color get() = Themes.current.red
val Green: Color get() = Themes.current.green
val Bar: Color get() = Themes.current.bar
/** Solid colour for dialogs / menus (the glass card colour is see-through). Same as CardC in every other theme. */
val DialogC: Color get() = if (Themes.current.glass) Color(0xFF16295A) else Themes.current.card
/** Screen background: gradient for Liquid Glass, the plain theme colour otherwise. */
val BgBrush: Brush get() = if (Themes.current.glass)
    Brush.linearGradient(listOf(Color(0xFF081433), Color(0xFF2A1A6B), Color(0xFF0A5C8C), Color(0xFF0B1F4D)))
else SolidColor(Themes.current.bg)

@Composable
fun AdminTheme(content: @Composable () -> Unit) {
    val p = Themes.current
    val scheme = if (p.dark) darkColorScheme(primary = p.accent, background = p.bg, surface = if (p.glass) Color(0xFF16295A) else p.card, onBackground = Color.White, onSurface = Color.White, onPrimary = Color.White)
    else lightColorScheme(primary = p.accent, background = p.bg, surface = p.card, onBackground = Color(0xFF15151F), onSurface = Color(0xFF15151F), onPrimary = Color.White)
    // Default text/icon colour for every screen. Without this, Text() outside a coloured Surface falls back to BLACK
    // (the root Surface is transparent), which made titles invisible on dark themes.
    MaterialTheme(colorScheme = scheme) { CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content) }
}

@Composable
fun ThemeDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, containerColor = DialogC, title = { Text("Choose theme", fontSize = 16.sp) }, confirmButton = { TextButton(onDismiss) { Text("DONE") } },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Themes.visible.forEach { p ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (p == Themes.current) Color(0xFFDDDDDD) else Color.Transparent)
                    .clickable { Themes.set(ctx, p) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(p.bg, p.accent, p.red).forEach { Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(it).border(1.dp, Color.Gray, RoundedCornerShape(11.dp))); Spacer(Modifier.width(4.dp)) }
                    Spacer(Modifier.width(8.dp)); Text(p.name, Modifier.weight(1f)); if (p == Themes.current) Text("✓")
                }
            }
        } })
}

@Composable
fun ShareDialog(title: String, text: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(onDismissRequest = {}, containerColor = DialogC, title = { Text(title, fontSize = 16.sp) }, text = { Text(text, fontSize = 14.sp) },
        confirmButton = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(text)); ctx.toast("Copied") }) { Text("COPY") }
            TextButton({ ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }, "Share")) }) { Text("SHARE") }
            TextButton(onDone) { Text("DONE") }
        } })
}

fun Context.toast(s: String) = Toast.makeText(this, tx(s), Toast.LENGTH_SHORT).show()

@Composable
fun <T> rflow(vararg k: Any?, init: T, f: () -> Flow<T>): State<T> {
    val fl = remember(k.toList()) { f() }
    return fl.collectAsState(init)
}

@Composable
fun AdminRotatingLoadingLogo(size: Int = 60, modifier: Modifier = Modifier) {
    val settings by rflow(init = emptyMap<String, Any?>()) { AR.watch("settings") }
    val logoRef = str(settings["loadingLogo"])
    val remoteUrl = logoRef.takeIf { it.startsWith("tgimg:") || it.startsWith("dbimg:") || it.startsWith("http://") || it.startsWith("https://") }
        ?.let { BackendConfig.resolve(it) }
    val transition = rememberInfiniteTransition(label = "admin-loading-logo")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
        label = "admin-loading-logo-rotation"
    )
    Box(modifier.size(size.dp).graphicsLayer { rotationZ = rotation }, contentAlignment = Alignment.Center) {
        // only the logo (round) spins - no ring / white arcs around it (same as the player app)
        Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            if (remoteUrl.isNullOrBlank()) {
                androidx.compose.foundation.Image(painterResource(R.drawable.bestwin_loading_logo), "Loading", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                androidx.compose.runtime.key(remoteUrl) {
                    AsyncImage(model = remoteUrl, contentDescription = "Loading", modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop, placeholder = null, error = painterResource(R.drawable.bestwin_loading_logo))
                }
            }
        }
    }
}

private class StepHolder(var depth: Int, var started: Boolean = false)

/**
 * Same slide as the rest of the app for pages inside one screen (list -> game -> edit form ...): when [depth] goes up the page
 * slides in from the right, when it goes down (back) it slides in from the left edge, 220 ms like every other screen change.
 * Call it ONCE at the top of the screen and use the returned Modifier on the root of every page of that screen.
 */
@Composable
fun Modifier.stepSlide(depth: Int): Modifier {
    val holder = remember { StepHolder(depth) }
    val st = remember(depth) {
        val changed = holder.started && holder.depth != depth
        val d = if (depth > holder.depth) 1 else -1
        holder.depth = depth; holder.started = true
        androidx.compose.animation.core.Animatable(if (changed) 1f else 0f) to d
    }
    val anim = st.first; val dir = st.second
    LaunchedEffect(anim) { if (anim.value > 0f) anim.animateTo(0f, tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    return this.graphicsLayer {
        val p = anim.value
        if (p > 0f) { translationX = if (dir > 0) size.width * p else -size.width * 0.25f * p; alpha = 1f - 0.4f * p }
    }
}

@Composable
fun Loader() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    AdminRotatingLoadingLogo()
}

@Composable
fun ABtn(text: String, modifier: Modifier = Modifier, color: Color = Purple, small: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.height(if (small) 34.dp else 44.dp), enabled = enabled, shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = if (small) 12.dp else 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
        Text(text, fontSize = if (small) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ABar(title: String, nav: NavController?, actions: @Composable RowScope.() -> Unit = {}) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(Modifier.fillMaxWidth().background(Bar).statusBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (nav != null) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(22.dp).clickable { nav.back() }); Spacer(Modifier.width(14.dp)) }
            Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            actions()
        }
    }
}

@Composable
fun Pill(text: String) {
    val c = when (text) { "pending" -> Color(0xFF7A4B00); "approved", "paid", "completed" -> Green; "rejected", "cancelled" -> Color(0xFF7A1F1F); "ongoing" -> Color(0xFF0F4C81); else -> Field }
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(c).padding(horizontal = 8.dp, vertical = 2.dp)) { Text(text, fontSize = 11.sp, color = Color.White) }
}

@Composable
fun AskDialog(title: String, hint: String, number: Boolean = false, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = DialogC, title = { Text(title, fontSize = 16.sp) },
        text = { OutlinedTextField(v, { v = it }, label = { Text(hint) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text)) },
        confirmButton = { TextButton({ if (v.isNotBlank()) onOk(v.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("CANCEL") } })
}

@Composable
fun ConfirmDialog(text: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = DialogC, title = { Text("Confirm") }, text = { Text(text, fontSize = 17.sp) },
        confirmButton = { TextButton(onOk) { Text("YES") } }, dismissButton = { TextButton(onDismiss) { Text("NO") } })
}

@Composable
fun SelectField(label: String, value: String, opts: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(opts.firstOrNull { it.first == value }?.second ?: value, {}, readOnly = true, label = { Text(label) },
            modifier = Modifier.fillMaxWidth(), trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) { opts.forEach { o -> DropdownMenuItem({ Text(o.second) }, { onPick(o.first); open = false }) } }
    }
}

@Composable
fun ImageField(label: String, value: String, maxSide: Int = 1280, type: String = "image", refId: String = "",
               autoSavePath: String? = null, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val canUpload = canAccess("media_upload")
    val canDelete = canAccess("media_delete")
    var state by remember { mutableStateOf("idle") }
    var err by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<Uri?>(null) }
    fun go(uri: Uri) {
        last = uri; state = "uploading"
        scope.launch {
            try {
                val old = value
                val ref = ImgUpload.upload(ctx, uri, type, refId, maxSide)
                if (autoSavePath != null) AR.db.child(autoSavePath).setValue(ref).await()   // existing item: update Firebase right away
                onChange(ref)
                if (autoSavePath != null && canDelete) ImgUpload.deleteQuiet(old)
                state = "done"
            } catch (e: Exception) { err = e.message ?: "Upload failed"; state = "failed"; Toast.makeText(ctx, err, Toast.LENGTH_LONG).show() }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) go(uri) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, color = Color.Gray)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Field), contentAlignment = Alignment.Center) {
                if (value.isNotBlank()) androidx.compose.runtime.key(value) {
                    AsyncImage(BackendConfig.resolve(value), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            when (state) {
                "uploading" -> { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Red); Text("Uploading...", fontSize = 14.sp) }
                "failed" -> Column {
                    Text("Upload failed", color = Red, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(err, fontSize = 12.sp, color = Color.White)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canUpload) ABtn("RETRY", color = Red, small = true) { last?.let { go(it) } }
                        if (canUpload) ABtn("SELECT ANOTHER", color = Field, small = true) { pick.launch("image/*") }
                    }
                }
                else -> {
                    if (canUpload) ABtn(if (value.isBlank()) "SELECT IMAGE" else "CHANGE IMAGE", color = Field, small = true) { pick.launch("image/*") }
                    else Text("Image upload permission is not assigned", fontSize = 11.sp, color = Color.Gray)
                    if (state == "done") Text("✓ Uploaded", color = Color(0xFF2ECC71), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    else if (value.isNotBlank() && canDelete) ABtn("REMOVE", color = Color(0xFF7A1F1F), small = true) {
                        val old = value
                        onChange("")
                        if (autoSavePath != null) scope.launch { try { AR.db.child(autoSavePath).setValue("").await(); if (canDelete) ImgUpload.deleteQuiet(old) } catch (_: Exception) { } }
                    }
                }
            }
        }
    }
}

/** Start-screen video picker (same permissions as images). Uploading saves the setting right away. */
@Composable
fun VideoField(label: String, value: String, autoSavePath: String?, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val canUpload = canAccess("media_upload")
    val canDelete = canAccess("media_delete")
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true; progress = "Preparing..."
            scope.launch {
                try {
                    val old = value
                    val ref = VideoUpload.upload(ctx, uri) { d, t -> progress = if (t > 0) "Uploading $d / $t" else "Uploading $d" }
                    if (autoSavePath != null) AR.db.child(autoSavePath).setValue(ref).await()
                    onChange(ref)
                    if (old.isNotBlank() && old != ref && canDelete) VideoUpload.deleteQuiet(old)
                    Toast.makeText(ctx, "Video uploaded. Turn the video switch ON and save.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) { Toast.makeText(ctx, e.message ?: "Upload failed", Toast.LENGTH_LONG).show() }
                finally { busy = false; progress = "" }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, color = Color.Gray)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Red); Text(progress, fontSize = 14.sp)
            } else {
                Text(if (value.isBlank()) "No video" else "✓ Video uploaded" + (VideoUpload.sizeMb(value)?.let { " ($it MB)" } ?: ""),
                    fontSize = 14.sp, color = if (value.isBlank()) Color.Gray else Color(0xFF2ECC71), modifier = Modifier.weight(1f, fill = false))
                if (canUpload) ABtn(if (value.isBlank()) "SELECT VIDEO" else "CHANGE VIDEO", color = Field, small = true) { pick.launch("video/*") }
                else Text("Upload permission is not assigned", fontSize = 11.sp, color = Color.Gray)
                if (value.isNotBlank() && canDelete) ABtn("REMOVE", color = Color(0xFF7A1F1F), small = true) {
                    val old = value
                    onChange("")
                    scope.launch { try { if (autoSavePath != null) AR.db.child(autoSavePath).setValue("").await(); VideoUpload.deleteQuiet(old) } catch (_: Exception) { } }
                }
            }
        }
    }
}

@Composable
fun FilterRow(sel: String, opts: List<String>, onSel: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp, 10.dp, 12.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { o ->
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (o == sel) Purple else Field).clickable { onSel(o) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(o.replaceFirstChar { it.uppercase() }, fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

fun shareCsv(ctx: Context, name: String, header: String, rows: List<List<String>>) {
    try {
        val text = header + "\n" + rows.joinToString("\n") { r -> r.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } }
        val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = java.io.File(dir, "$name-${System.currentTimeMillis()}.csv"); f.writeText(text)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Export $name"))
    } catch (e: Exception) { ctx.toast("Export failed") }
}

// ---------------- Dialog look (same as the player app's "Join Match" box) ----------------
// One place restyles EVERY dialog of this app: near-square white card, black title bar, dark CANCEL/NO + teal confirm button.
// This AlertDialog has the same name and parameters as material3's, and a function in the package wins over the material3.* import,
// so all existing AlertDialog(...) calls (and ConfirmDialog / AskDialog / ShareDialog ...) pick it up without any other change.
val LocalDlgBtn = androidx.compose.runtime.compositionLocalOf<Color?> { null }
private val DlgCancelC = Color(0xFF1B0035)
private val DlgOkC = Color(0xFF5CCFB3)

/** Normal text button everywhere; inside a dialog's button slots it becomes a big solid button (dark = cancel, teal = confirm). */
@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val c = LocalDlgBtn.current
    if (c == null) androidx.compose.material3.TextButton(onClick, modifier, enabled, content = content)
    else Button(onClick, modifier.fillMaxWidth().height(52.dp), enabled = enabled, shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c, contentColor = Color.White, disabledContainerColor = c.copy(alpha = .5f), disabledContentColor = Color.White),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp), contentPadding = PaddingValues(horizontal = 8.dp), content = content)
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    containerColor: Color = Color.White,
) {
    val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.86f).dp
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier.fillMaxWidth().padding(horizontal = 36.dp).heightIn(max = maxH).clip(RoundedCornerShape(4.dp)).background(containerColor).padding(14.dp)) {
            if (title != null) Box(Modifier.fillMaxWidth().background(Color.Black).padding(vertical = 18.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)) { title() }
                }
            }
            // The body is always light, so it gets a light material theme: text fields and texts stay readable on white in every admin theme.
            MaterialTheme(colorScheme = lightColorScheme(primary = DlgCancelC)) {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    if (text != null) Column(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())) { text() }
                    else Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                        if (dismissButton != null) Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalDlgBtn provides DlgCancelC) { dismissButton() } }
                        Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalDlgBtn provides DlgOkC) { confirmButton() } }
                    }
                }
            }
        }
    }
}
