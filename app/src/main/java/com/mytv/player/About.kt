package com.mytv.player

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- edit these to change the About page ----
const val ABOUT_WEBSITE = ""   // e.g. "https://example.com"
const val ABOUT_TELEGRAM = ""  // e.g. "https://t.me/yourchannel"
const val ABOUT_DONATE = ""    // e.g. "https://www.buymeacoffee.com/yourname"
private const val ABOUT_LEAD = "Lead Contributor: Tieng,Cubi,Ongkie,Yayung & Hanyoo"
private const val ABOUT_CONTRIB = "Contributions: Core development, feature design, optimization."
private const val ABOUT_COPY = "© 2025 Baruk OnAir. All rights reserved."
private const val ABOUT_SHARE = "Please share about Baruk On Air to others ."

@Composable
fun AboutTab() {
    val ctx = LocalContext.current
    fun open(url: String) {
        if (url.isBlank()) {
            Toast.makeText(ctx, "Link not set yet", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Buy Me A Coffee",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open(ABOUT_DONATE) }.padding(8.dp),
        )
        Text("Credits", fontStyle = FontStyle.Italic, fontSize = 15.sp)
        Text(ABOUT_LEAD, fontStyle = FontStyle.Italic, fontSize = 15.sp, textAlign = TextAlign.Center)
        Text(ABOUT_CONTRIB, fontStyle = FontStyle.Italic, fontSize = 15.sp, textAlign = TextAlign.Center)
        Text(ABOUT_COPY, fontStyle = FontStyle.Italic, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp))
        Text(ABOUT_SHARE, fontStyle = FontStyle.Italic, fontSize = 15.sp, textAlign = TextAlign.Center)
        Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", color = Dim, fontSize = 12.sp)

        Text("More Information :", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinkItem("Website") { open(ABOUT_WEBSITE) }
            Box(Modifier.width(1.dp).height(28.dp).background(Hair))
            LinkItem("Telegram") { open(ABOUT_TELEGRAM) }
            Box(Modifier.width(1.dp).height(28.dp).background(Hair))
            LinkItem("Donate") { open(ABOUT_DONATE) }
        }
    }
}

@Composable
private fun LinkItem(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .width(120.dp)
            .clip(shape)
            .tvFocus(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    }
}
