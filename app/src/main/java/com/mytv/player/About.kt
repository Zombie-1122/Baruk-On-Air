package com.mytv.player

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ---- About page content: edit here ----
const val ABOUT_WEBSITE = "https://kp1kmn88s5uzugttuuxzug.on.drv.tw/www.barukonair.com/"
const val ABOUT_TELEGRAM = "https://t.me/barukonair"
private const val ABOUT_TEAM = "Tieng  •  Cubi  •  Ongkie  •  Yayung  •  Hanyoo  •  NyukNyek  •  Zolle"
private val ABOUT_CONTRIB = listOf("Core Development", "Feature Design", "System Optimization")
private const val ABOUT_COPY = "Baruk On Air © 2026\nAll Rights Reserved."

@Composable
fun AboutTab() {
    val ctx = LocalContext.current
    var showQr by remember { mutableStateOf(false) }

    fun open(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun share() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Try Baruk On Air - live TV and radio, all in one app.\n$ABOUT_TELEGRAM")
        runCatching { ctx.startActivity(Intent.createChooser(send, "Share Baruk On Air")) }
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- brand ----
        Image(
            painterResource(R.drawable.about_logo),
            contentDescription = "Baruk On Air",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(150.dp),
        )
        Text("Version ${BuildConfig.VERSION_NAME}  (build ${BuildConfig.VERSION_CODE})", color = Dim, fontSize = 12.sp)

        // ---- website / telegram ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Website", false, { open(ABOUT_WEBSITE) })
            Pill("Telegram", false, { open(ABOUT_TELEGRAM) })
        }

        // ---- support ----
        Section("Support the Project") {
            Text(
                "Love using Baruk On Air? Buy us a coffee and help keep the project growing!",
                color = Dim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Pill("Donate", true, { showQr = true }, Modifier.padding(top = 12.dp))
        }

        // ---- credits ----
        Section("Credits & Acknowledgments") {
            Label("Development Team")
            Text(ABOUT_TEAM, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Label("Our Contributions")
            Column(Modifier.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (c in ABOUT_CONTRIB) Text(c, fontSize = 14.sp, modifier = Modifier.padding(vertical = 1.dp))
            }
        }

        // ---- share ----
        Section("Grow With Us") {
            Text(
                "Enjoying the experience? Share Baruk On Air with your friends and help us reach more people!",
                color = Dim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Pill("Share", false, { share() }, Modifier.padding(top = 12.dp))
        }

        Text(
            ABOUT_COPY,
            color = Dim,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
    }

    if (showQr) DonateDialog { showQr = false }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = AccentLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

/** The donation QR on a white card (QR codes need a light background to scan). Tap anywhere or press Back to close. */
@Composable
private fun DonateDialog(onClose: () -> Unit) {
    val h = LocalConfiguration.current.screenHeightDp
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .clickable(onClick = onClose)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Donate with PayPal", color = Color(0xFF16212C), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("chez sid", color = Color(0xFF5A6877), fontSize = 13.sp)
            Image(
                painterResource(R.drawable.donate_qr),
                contentDescription = "PayPal donation QR code",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height((h * 0.62f).dp.coerceAtMost(380.dp)),
            )
            Text(
                "Scan with the PayPal app. Thank you for your support!",
                color = Color(0xFF5A6877),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}
