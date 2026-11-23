package com.bestwinesport.admin

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Language of the admin panel: English or Hindi. Saved on the phone. Every Text() / toast in this app goes through tx() (see AdminText.kt),
 * so a text that has a Hindi version in AdminHindi.kt is shown in Hindi, and any other text simply stays in English.
 */
object AdminLanguage {
    var current by mutableStateOf("en")
        private set

    fun load(ctx: Context) {
        val saved = ctx.getSharedPreferences("admin_preferences", Context.MODE_PRIVATE).getString("language", "en")
        current = if (saved == "hi") "hi" else "en"
    }

    fun select(ctx: Context, code: String) {
        current = if (code == "hi") "hi" else "en"
        ctx.getSharedPreferences("admin_preferences", Context.MODE_PRIVATE).edit().putString("language", current).apply()
    }
}

private val numberRun = Regex("\\d+")

/** English text -> the text in the selected language. Numbers inside a text are kept ("Online: 3" uses the entry "Online: #"). */
fun tx(text: String): String {
    if (AdminLanguage.current != "hi" || text.isBlank()) return text
    HiAdmin[text]?.let { return it }
    if (text.any { it.isDigit() }) {
        val nums = numberRun.findAll(text).map { it.value }.toList()
        val hi = HiAdmin[numberRun.replace(text, "#")]
        if (hi != null) {
            var i = 0
            return hi.map { ch -> if (ch == '#' && i < nums.size) nums[i++] else ch.toString() }.joinToString("")
        }
    }
    val cut = text.indexOf(": ")
    if (cut in 1..45) HiAdmin[text.substring(0, cut + 1)]?.let { return it + text.substring(cut + 1) }
    return text
}

@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(CardC).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose language", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            listOf("en" to "English", "hi" to "\u0939\u093F\u0928\u094D\u0926\u0940").forEach { (code, name) ->
                val sel = AdminLanguage.current == code
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) Purple.copy(alpha = 0.25f) else Field)
                    .clickable { AdminLanguage.select(ctx, code); onDismiss() }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sel, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            ABtn("CANCEL", Modifier.fillMaxWidth(), Field, true) { onDismiss() }
        }
    }
}
