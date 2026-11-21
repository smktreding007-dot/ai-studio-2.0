package com.bestwinesport.staff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * World chat stickers. They are drawn by the app itself (hexagon badge + text), so there are no picture files to upload.
 * A sticker message only stores the sticker id (worldChat/messages/<id>/sticker). The SAME list must exist in the player app, the admin app and the staff app.
 * To add a sticker: add one line here (and in the other two apps). Never change an existing id.
 */
data class StickerDef(val id: String, val emoji: String, val top: String, val bottom: String, val c1: Color, val c2: Color)

object Stickers {
    val all: List<StickerDef> = listOf(
        StickerDef("love", "\u2764\uFE0F", "LOVE BESTWIN", "BEST APP INDIA", Color(0xFFD32F2F), Color(0xFF6D0F1A)),
        StickerDef("gg", "\uD83E\uDD1D", "GG", "WELL PLAYED", Color(0xFF2E7D32), Color(0xFF0B3D14)),
        StickerDef("booyah", "\uD83C\uDFC6", "BOOYAH", "CHAMPION", Color(0xFFF9A825), Color(0xFF7A4B00)),
        StickerDef("onetap", "\uD83C\uDFAF", "ONE TAP", "HEADSHOT", Color(0xFFC62828), Color(0xFF3B0A0A)),
        StickerDef("room", "\uD83D\uDEAA", "ROOM", "JOIN KARO", Color(0xFF1565C0), Color(0xFF0A2F5E)),
        StickerDef("ready", "\u2694\uFE0F", "2V2", "READY?", Color(0xFF6A1B9A), Color(0xFF2E0A47)),
        StickerDef("winner", "\uD83C\uDF57", "WINNER", "CHICKEN DINNER", Color(0xFFEF6C00), Color(0xFF6B2E00)),
        StickerDef("rush", "\uD83D\uDD25", "FULL RUSH", "GO GO GO", Color(0xFFD84315), Color(0xFF5A1500)),
        StickerDef("king", "\uD83D\uDC51", "KING", "OF LOBBY", Color(0xFFFFB300), Color(0xFF6B4A00)),
        StickerDef("pro", "\uD83D\uDE0E", "PRO", "PLAYER", Color(0xFF00838F), Color(0xFF00363B)),
        StickerDef("noob", "\uD83D\uDE05", "NOOB", "SLOW BRO", Color(0xFF558B2F), Color(0xFF1F3A0E)),
        StickerDef("lag", "\uD83D\uDCF6", "LAG", "NETWORK ISSUE", Color(0xFF455A64), Color(0xFF14222A)),
        StickerDef("thanks", "\uD83D\uDE4F", "THANKS", "BHAI", Color(0xFF00897B), Color(0xFF00352F)),
        StickerDef("sorry", "\uD83D\uDE14", "SORRY", "GALTI HO GAYI", Color(0xFF5D4037), Color(0xFF241713)),
        StickerDef("teamup", "\uD83D\uDC65", "TEAM UP", "KARO", Color(0xFF283593), Color(0xFF0E1340)),
        StickerDef("wait", "\u23F3", "WAIT", "AA RAHA HU", Color(0xFF8D6E00), Color(0xFF3A2D00)),
        StickerDef("help", "\uD83C\uDD98", "HELP", "BACHAO", Color(0xFFAD1457), Color(0xFF4A0624)),
        StickerDef("revenge", "\uD83D\uDE08", "REVENGE", "TIME", Color(0xFF4A148C), Color(0xFF1A0536)),
        StickerDef("lol", "\uD83D\uDE02", "LOL", "HASI ROKO", Color(0xFFF57F17), Color(0xFF6B3500)),
        StickerDef("start", "\u23F0", "MATCH TIME", "START NOW", Color(0xFF1B5E20), Color(0xFF082C0D)),
        StickerDef("fire", "\uD83D\uDCA5", "ON FIRE", "TODAY", Color(0xFFBF360C), Color(0xFF4A1303)),
        StickerDef("bestmate", "\uD83E\uDD1C", "BEST", "TEAMMATE", Color(0xFF0277BD), Color(0xFF023A5E)),
        StickerDef("diamond", "\uD83D\uDC8E", "DIAMOND", "SQUAD", Color(0xFF0097A7), Color(0xFF004149)),
        StickerDef("bestwin", "\uD83C\uDFAE", "BESTWIN", "ESPORTS", Color(0xFF7B1FA2), Color(0xFF2B0A3A))
    )

    fun find(id: String): StickerDef? = all.firstOrNull { it.id == id }
}

/** One sticker: a hexagon badge with a gold border, emoji on top, title and a small line below. */
@Composable
fun StickerView(s: StickerDef, size: Dp) {
    val gold = Color(0xFFFFD54F)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val p = Path().apply {
                moveTo(w * 0.5f, h * 0.02f); lineTo(w * 0.95f, h * 0.26f); lineTo(w * 0.95f, h * 0.74f)
                lineTo(w * 0.5f, h * 0.98f); lineTo(w * 0.05f, h * 0.74f); lineTo(w * 0.05f, h * 0.26f); close()
            }
            drawPath(p, Brush.verticalGradient(listOf(s.c1, s.c2)))
            drawPath(p, gold, style = Stroke(width = w * 0.035f))
        }
        Column(Modifier.fillMaxWidth(0.72f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.emoji, fontSize = (size.value * 0.2f).sp)
            Text(s.top, fontSize = (size.value * 0.105f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (size.value * 0.115f).sp)
            Box(Modifier.padding(vertical = 2.dp).fillMaxWidth(0.6f).height(1.5.dp).background(gold))
            Text(s.bottom, fontSize = (size.value * 0.065f).sp, fontWeight = FontWeight.Bold, color = gold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (size.value * 0.075f).sp)
        }
    }
}
