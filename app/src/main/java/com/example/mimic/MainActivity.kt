package com.example.mimic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.MimicApp
import com.example.mimic.ui.theme.MImicTheme

/** FragmentActivity (a ComponentActivity) so Google's Cast device picker can show its dialog (4.1). */
class MainActivity : androidx.fragment.app.FragmentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initCast()
        if (savedInstanceState == null) handleSharedLink(intent)
        enableEdgeToEdge()
        setContent {
            val viewModel: PlayerViewModel = playerViewModel
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val activeTheme by viewModel.activeTheme.collectAsStateWithLifecycle()
            val adaptiveSeed by viewModel.adaptiveSeed.collectAsStateWithLifecycle()
            MImicTheme(theme = activeTheme, quality = settings.glassQuality, adaptiveSeed = adaptiveSeed) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Root(viewModel)
                }
            }
        }
    }

    /** Cast: initialize on the main thread so an existing TV session is resumed after a restart. */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun initCast() {
        (application as MimicApplication).container.castController.ensureInitialized()
    }

    override fun onStart() {
        super.onStart()
        playerViewModel.setUiVisible(true)
    }

    override fun onStop() {
        playerViewModel.setUiVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedLink(intent)
    }

    /** Full edition: a YouTube/TikTok link shared or opened into MImic goes to the Downloads tab. */
    private fun handleSharedLink(intent: android.content.Intent?) {
        com.example.mimic.downloader.Downloader.sharedText(intent)?.let(playerViewModel::offerSharedLink)
        if (intent?.getBooleanExtra(com.example.mimic.update.UpdateCheckWorker.EXTRA_OPEN_UPDATES, false) == true) {
            intent.removeExtra(com.example.mimic.update.UpdateCheckWorker.EXTRA_OPEN_UPDATES)
            playerViewModel.requestOpenUpdates()
        }
    }

}

private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

@Composable
private fun Root(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* playback works regardless of the result */ }

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (granted) {
        LaunchedEffect(Unit) {
            viewModel.initController()
            viewModel.loadLibrary()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        MimicApp(viewModel)
    } else {
        PermissionPrompt(onGrant = { audioLauncher.launch(audioPermission) })
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Let the app see your music",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            "It reads the audio files already on this device so it can play them. " +
                "Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onGrant) { Text("Allow access") }
    }
}
