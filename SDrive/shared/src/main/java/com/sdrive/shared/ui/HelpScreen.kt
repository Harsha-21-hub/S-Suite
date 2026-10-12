package com.sdrive.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class HelpTopic(val title: String, val steps: List<String>)

private val topics = listOf(
    HelpTopic(
        "PC → watch",
        listOf(
            "PC: double-click pc\\S-Drive-PC.bat. It opens the watch's S Drive page in your browser (no window, no debugging needed).",
            "Or on the watch: S Drive → Transfer → Receive, then open the link shown there (e.g. http://192.168.1.23:8080) and enter the PIN.",
            "Page → Transfer → Send to watch: drag files or folders, or click Upload.",
            "Files land in the folder you have open on the page (or Download/S-Drive).",
        ),
    ),
    HelpTopic(
        "Watch → PC",
        listOf(
            "Watch: Transfer → Send → Choose files to send.",
            "Long-press files → Send → Offer to PC browser.",
            "They are saved to Downloads\\S-Drive on the PC (when the page was opened with S-Drive-PC.bat). Or click any file on the page.",
            "Page → Stop: closes the watch's port and exits S Drive on the PC. Run the .bat again to start.",
        ),
    ),
    HelpTopic(
        "Phone → watch",
        listOf(
            "Watch: Transfer → Receive (same Wi-Fi as the phone).",
            "Phone: Transfer → Send → Choose files → long-press → Send → the watch → enter the watch PIN.",
            "Or Gallery / Files: Share → S Drive → the watch.",
            "No Wi-Fi: Send → Bluetooth (slower).",
        ),
    ),
    HelpTopic(
        "Watch → phone",
        listOf(
            "Phone: Transfer → Receive.",
            "Watch: Transfer → Send → Choose files → long-press → Send → the phone.",
        ),
    ),
    HelpTopic(
        "Transfer ON / OFF",
        listOf(
            "Transfer → Transfer: ON/OFF (same switch on the PC page).",
            "OFF: nothing can be sent or received until it is turned ON again.",
            "Received files always go to Download/S-Drive.",
        ),
    ),
    HelpTopic(
        "Which is fastest?",
        listOf(
            "S Drive over Wi-Fi: fastest. Up to ~4 MB/s on 2.4 GHz, more on 5 GHz with a strong signal.",
            "Weak signal (below -70 dBm) slows it a lot. Move the watch closer to the router.",
            "Bluetooth: about 0.1-0.3 MB/s, works without Wi-Fi.",
            "Any file type works.",
        ),
    ),
    HelpTopic(
        "Permissions + smooth app",
        listOf(
            "First launch asks for notifications and no battery limits. Allow both.",
            "Wi-Fi / Bluetooth transfers never need debugging.",
            "Only ONCE after installing: Wireless debugging ON, pc\\S-Drive-Tools.bat → 1 Pair, 2 Connect (grants all-files access + smooth UI). Or phone Terminal → Connect.",
            "Then turn Wireless debugging (and Developer options) OFF again: payment apps work normally, the grants stay.",
        ),
    ),
)

/** How to move files in every direction + which method is fastest. */
@Composable
fun HelpScreen() {
    val watch = LocalIsWatch.current
    WList {
        item { Txt("HELP", size = if (watch) 16.sp else 22.sp, font = DotFont) }
        topics.forEach { t ->
            item {
                Column(
                    Modifier.curved().fillMaxWidth().clip(RoundedCornerShape(if (watch) 22.dp else 14.dp))
                        .background(Pal.surface).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Txt(t.title, size = if (watch) 13.sp else 15.sp, weight = FontWeight.Bold, lines = 2)
                    t.steps.forEachIndexed { i, step ->
                        Row {
                            Txt("${i + 1}", Modifier.width(if (watch) 12.dp else 16.dp), size = 11.sp, color = Pal.red)
                            Txt(step, size = 11.sp, color = Pal.dim, lines = 5)
                        }
                    }
                }
            }
        }
        item { Txt("Any file type can be sent", Modifier.padding(8.dp), size = 11.sp, color = Pal.dim, align = TextAlign.Center) }
    }
}
