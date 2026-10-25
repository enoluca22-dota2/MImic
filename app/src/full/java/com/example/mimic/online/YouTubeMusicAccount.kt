package com.example.mimic.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mimic.online.auth.WebSessionMode
import com.example.mimic.online.auth.YtMusicLoginScreen
import com.example.mimic.online.data.YtMusicRepository
import com.example.mimic.online.data.innertube.Innertube

/**
 * Settings → Accounts → YouTube Music (full edition, optional).
 *
 * This is NOT the MImic account: it only lets MImic make YouTube Music requests as you (your
 * library, likes, personalised results). Sign-in happens on Google's own page inside a WebView
 * (BitChord's flow: passwords, 2-step verification and passkeys are typed into accounts.google.com,
 * never into MImic). What MImic keeps is the resulting session cookie, in encrypted app storage;
 * it is never logged, synced, backed up or put in diagnostics. Local music, Navidrome and online
 * search all work without it.
 */
@Composable
fun YouTubeMusicAccountSection() {
    var signingIn by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(OnlineMusic.youTubeMusicConnected) }
    var who by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connected) {
        who = if (connected) YtMusicRepository.account().getOrNull()?.let { a -> listOf(a.name, a.email).filter { it.isNotBlank() }.joinToString(" · ") } else null
    }
    if (signingIn) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sign in on Google's page", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
                TextButton(onClick = { signingIn = false }) { Text("Cancel") }
            }
            Box(Modifier.fillMaxWidth().padding(4.dp).let { it.then(Modifier.fillMaxSize()) }) {
                YtMusicLoginScreen(
                    mode = WebSessionMode.SIGN_IN,
                    onCaptured = { s ->
                        if (s.loggedIn) {
                            OnlineMusic.onYouTubeMusicSession(s.cookie)
                            Innertube.selectChannel(s.pageId, s.dataSyncId, s.authUser)
                            connected = OnlineMusic.youTubeMusicConnected
                            note = if (connected) null else "Google didn't return a YouTube Music session. Try again."
                        } else note = "Sign-in was not completed."
                        signingIn = false
                    },
                )
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("YouTube Music", style = MaterialTheme.typography.titleMedium)
        Text(
            if (connected) "Connected" + (who?.let { " as $it" } ?: "") + ". Used only for YouTube Music requests."
            else "Optional. Connect to use your YouTube Music library and likes. Not needed for search, streaming, downloads, your own music or Navidrome.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) OutlinedButton(onClick = { OnlineMusic.onYouTubeMusicSession(null); connected = false }) { Text("Disconnect YouTube Music") }
            else Button(onClick = { note = null; signingIn = true }) { Text("Connect YouTube Music") }
        }
    }
}
