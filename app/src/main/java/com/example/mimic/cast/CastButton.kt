package com.example.mimic.cast

import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteButton
import com.example.mimic.MimicApplication
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * Google's Cast button: lists Chromecast / Google TV receivers on the same Wi-Fi; when connected
 * it opens Google's controller (TV volume, stop casting). Always visible — with no receiver on
 * the network it explains that instead of hiding. Without Google Play services it shows a
 * disabled icon that says why.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun CastButton(tint: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cast = remember { (context.applicationContext as MimicApplication).container.castController }
    val ok = remember { cast.ensureInitialized() }
    if (!ok) {
        IconButton(
            onClick = {
                val reason = (cast.status.value as? CastController.Status.Unsupported)?.reason ?: "Casting isn't available on this phone."
                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            },
            modifier = modifier,
        ) { Icon(Icons.Default.Cast, contentDescription = "Cast (unavailable)", tint = tint.copy(alpha = 0.4f)) }
        return
    }
    // Light icon on dark backgrounds, dark icon on light ones.
    val theme = if (tint.luminance() > 0.5f) androidx.appcompat.R.style.Theme_AppCompat else androidx.appcompat.R.style.Theme_AppCompat_Light
    AndroidView(
        factory = { ctx ->
            MediaRouteButton(ContextThemeWrapper(ctx, theme)).apply {
                CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, this)
                setAlwaysVisible(true)
                contentDescription = "Cast to a TV or speaker"
            }
        },
        modifier = modifier.size(48.dp),
    )
}
