package com.example.mimic.ui.party

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.mimic.party.ListenTogether
import com.example.mimic.party.PartyInvite
import com.example.mimic.party.PartyMember
import com.example.mimic.party.PartyPreview
import com.example.mimic.party.PartyTrack
import com.example.mimic.player.PlayerViewModel

/**
 * Listen Together (MImic 4.5): start or join a party, invite friends with a code or link, and
 * listen to the same music at the same moment — each phone playing from its own source.
 * Works in every theme (dark, light, AMOLED, glass) through the app's colour scheme.
 */
@Composable
fun ListenTogetherScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val party by vm.party.collectAsStateWithLifecycle()
    val invite by vm.pendingInvite.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
            Text("Listen Together", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                "Same song, same second, on everyone's phone. Each phone plays from its own music, Navidrome or YouTube Music — no audio is sent between you.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        party.error?.let { msg -> item { ErrorCard(msg, onDismiss = vm::clearPartyError) } }
        if (party.inParty) {
            partyContent(vm, party)
        } else {
            item { AccountCard(vm) }
            item { ServerCard(vm) }
            invite?.let { inv -> item { InviteCard(vm, inv) } }
            item { StartCard(vm) }
            item { JoinCard(vm) }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) { Column(Modifier.padding(18.dp)) { content() } }
}

@Composable
private fun ErrorCard(msg: String, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

// ------------------------------------------------------------------- not in a party --

@Composable
private fun AccountCard(vm: PlayerViewModel) {
    val account by vm.accountState.collectAsStateWithLifecycle()
    val signed = account as? com.example.mimic.data.account.MimicAccount.State.SignedIn
    var name by remember { mutableStateOf(vm.partyNickname()) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name.ifBlank { signed?.name.orEmpty() }, signed?.photoUrl, 52.dp, connected = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { signed?.name ?: "Choose your name" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (signed != null) "Signed in with your MImic account" else "No account needed — friends see this name",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40); vm.setPartyNickname(name) },
            label = { Text(if (signed != null) "Party name (optional)" else "Your name in parties") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        if (signed == null) {
            Text(
                "Signed in with Google (Settings → Accounts)? Your Google name and photo are used instead.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ServerCard(vm: PlayerViewModel) {
    val server by vm.partyServer.collectAsStateWithLifecycle()
    var text by remember(server) { mutableStateOf(server) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val health by produceState<Result<Long>?>(null, server) { value = if (server.isBlank()) null else vm.checkPartyServer() }
    SectionCard {
        Text("Party server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            when {
                server.isBlank() -> "Not set. Run MImic's party server on your PC (server/party) and enter its Tailscale address."
                health == null -> "Checking $server…"
                health!!.isSuccess -> "Online · ${health!!.getOrNull()} ms · $server"
                else -> "Not reachable: $server"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (health?.isFailure == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        OutlinedTextField(text, { text = it; result = null }, label = { Text("Address, e.g. http://my-pc.tail1234.ts.net:8000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            FilledTonalButton(onClick = {
                checking = true
                vm.setPartyServer(text) { err -> checking = false; result = err ?: "Saved — the server answered." }
            }, enabled = !checking && text.isNotBlank()) { Text("Check & save") }
            if (checking) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun StartCard(vm: PlayerViewModel) {
    val busy by vm.partyBusy.collectAsStateWithLifecycle()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Start a Party", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("You're the host. Whatever you're playing becomes the party's song.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = vm::startParty, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp), shape = RoundedCornerShape(26.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Start a Party", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun JoinCard(vm: PlayerViewModel) {
    val busy by vm.partyBusy.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    SectionCard {
        Text("Join a Party", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Enter the 6-character code, or paste the invite link a friend sent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            code, { code = it.take(200) },
            label = { Text("Party code or link") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedButton(
            onClick = {
                val inv = PartyInvite.parse(code)
                if (inv != null) vm.openInvite(inv) else vm.joinParty(code)
            },
            enabled = !busy && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp), shape = RoundedCornerShape(24.dp),
        ) { Text("Join Party") }
    }
}

@Composable
private fun InviteCard(vm: PlayerViewModel, invite: PartyInvite) {
    val busy by vm.partyBusy.collectAsStateWithLifecycle()
    val preview by produceState<Result<PartyPreview>?>(null, invite) { value = vm.previewParty(invite.code, invite.server) }
    SectionCard {
        Text("You're invited", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Party ${invite.code}" + (invite.server?.let { " on $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val p = preview) {
            null -> CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp))
            else -> p.fold(
                onSuccess = { pv ->
                    Text("${pv.hostName.ifBlank { "A friend" }}'s party · ${pv.memberCount}/${pv.maxMembers} listening", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        items(pv.members) { m -> Avatar(m.displayName, m.avatarUrl, 40.dp, connected = true, host = m.isHost) }
                    }
                    if (pv.isFull) Text("This party is full.", color = MaterialTheme.colorScheme.error)
                },
                onFailure = { Text(it.message ?: "Couldn't look up that party.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) },
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.joinParty(invite.code, invite.server); vm.consumeInvite() }, enabled = !busy && preview?.getOrNull()?.isFull != true) { Text("Join") }
            TextButton(onClick = vm::consumeInvite) { Text("Not now") }
        }
    }
}

// ------------------------------------------------------------------------ in a party --

private fun androidx.compose.foundation.lazy.LazyListScope.partyContent(vm: PlayerViewModel, party: ListenTogether.State) {
    item { PartyHeader(vm, party) }
    item { NowPlayingCard(vm, party) }
    item {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Shared queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            AddSongsButton(vm, party)
        }
    }
    val items = party.queue.items
    val current = com.example.mimic.party.partyQueueIndexOf(party.queue, party.playback, party.playback.track?.videoId)
    if (items.isEmpty()) {
        item { Text("Nothing queued yet. Add songs, or play something — it becomes the party's song.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    itemsIndexed(items, key = { i, t -> "$i:${t.videoId}" }) { i, t ->
        QueueRow(t, isCurrent = i == current, played = current >= 0 && i < current, canEdit = !party.controlsLocked,
            onPlay = { vm.playPartyTrack(t) }, onRemove = { vm.removeFromParty(t) })
    }
    if (party.isHost) item { HostCard(vm, party) }
    item { MembersCard(vm, party) }
    item { ActivityCard(vm) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = vm::leaveParty, modifier = Modifier.weight(1f)) { Text("Leave party") }
            if (party.isHost) {
                var confirm by remember { mutableStateOf(false) }
                Button(onClick = { confirm = true }, modifier = Modifier.weight(1f), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("End party") }
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("End the party?") },
                        text = { Text("Everyone leaves and the code stops working.") },
                        confirmButton = { TextButton(onClick = { confirm = false; vm.endParty() }) { Text("End party") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PartyHeader(vm: PlayerViewModel, party: ListenTogether.State) {
    val context = LocalContext.current
    val code = party.code.orEmpty()
    val server = party.serverUrl.orEmpty()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (party.isHost) "Your party" else "Party", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(code, style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 6.sp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            ConnectionChip(vm, party)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            items(party.members, key = { it.memberId }) { m ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                    Avatar(m.displayName, m.avatarUrl, 48.dp, connected = m.connected, host = m.isHost)
                    Text(if (m.memberId == party.you?.memberId) "You" else m.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("${party.members.size} of ${party.maxMembers} · ${party.members.count { it.connected }} connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            FilledTonalButton(onClick = {
                val text = "Listen with me on MImic! Party code $code\n${PartyInvite.webLink(code, server)}\n${PartyInvite.appLink(code, server)}"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite friends"))
            }) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Invite") }
            OutlinedButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MImic party", PartyInvite.appLink(code, server)))
            }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy link") }
        }
    }
}

@Composable
private fun ConnectionChip(vm: PlayerViewModel, party: ListenTogether.State) {
    val status by vm.partySyncStatus.collectAsStateWithLifecycle()
    val (label, color) = when (party.connection) {
        ListenTogether.Connection.LIVE -> (if (party.clockSynced) "Live · ${party.roundTripMs} ms" else "Live · syncing clock") to Color(0xFF2E7D32)
        ListenTogether.Connection.CONNECTING -> (if (party.reconnects > 0) "Reconnecting…" else "Connecting…") to Color(0xFFF9A825)
        ListenTogether.Connection.OFFLINE -> "Offline" to MaterialTheme.colorScheme.error
    }
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        status.driftMs?.let { d ->
            Text("In sync ±${kotlin.math.abs(d)} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun NowPlayingCard(vm: PlayerViewModel, party: ListenTogether.State) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val status by vm.partySyncStatus.collectAsStateWithLifecycle()
    val waiting by vm.partyAwaitingStart.collectAsStateWithLifecycle()
    val track = party.playback.track
    SectionCard {
        if (track == null) {
            Text("Nothing playing yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (party.controlsLocked) "The host will choose the first song." else "Play any song, or add songs to the queue — it starts the party.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.thumbnailUrl ?: ui.currentSong?.albumArtUri, 72.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                party.playback.startedByName?.let { Text("Chosen by $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        val line = when {
            status.unavailableTitle != null -> "Not available on this phone — you'll rejoin on the next song."
            status.resolving -> "Finding this song on your phone…"
            status.detached -> "Another app is playing. Press play to rejoin the party."
            else -> status.sourceLabel
        }
        line?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (status.unavailableTitle != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
        val duration = track.durationMs ?: ui.durationMs
        if (duration > 0) {
            LinearProgressIndicator(progress = { (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val locked = party.controlsLocked
            IconButton(onClick = vm::previous, enabled = !locked) { Icon(Icons.Default.SkipPrevious, "Previous") }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = vm::partyTogglePlay), contentAlignment = Alignment.Center) {
                if (waiting) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Icon(if (party.playback.isPlaying && !status.detached) Icons.Default.Pause else Icons.Default.PlayArrow, if (party.playback.isPlaying) "Pause" else "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = vm::next, enabled = !locked) { Icon(Icons.Default.SkipNext, "Next") }
        }
        if (locked(party)) Text("The host controls the music. Your play/pause only affects your phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val voted = party.youVotedSkip
            OutlinedButton(onClick = vm::voteSkip, enabled = !voted) {
                Icon(Icons.Default.ThumbUp, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(if (voted) "You voted to skip" else "Vote to skip")
            }
            Spacer(Modifier.width(10.dp))
            if (party.playback.skipVotesNeeded > 0) {
                Text("${party.playback.skipVotes} of ${party.playback.skipVotesNeeded} votes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun locked(party: ListenTogether.State) = party.controlsLocked

@Composable
private fun QueueRow(t: PartyTrack, isCurrent: Boolean, played: Boolean, canEdit: Boolean, onPlay: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(enabled = canEdit && !isCurrent, onClick = onPlay).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t.thumbnailUrl, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (played) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text((if (isCurrent) "Now playing · " else "") + t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (canEdit && !isCurrent && !played) IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove from the party queue") }
    }
}

@Composable
private fun AddSongsButton(vm: PlayerViewModel, party: ListenTogether.State) {
    var open by remember { mutableStateOf(false) }
    FilledTonalButton(onClick = { open = true }, enabled = !party.controlsLocked) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add songs") }
    if (open) AddSongsDialog(vm, onDismiss = { open = false })
}

@Composable
private fun AddSongsDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val shown = remember(songs, query) {
        val q = query.trim().lowercase()
        (if (q.isEmpty()) songs else songs.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }).take(200)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth().padding(16.dp).height(560.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add to the party", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                OutlinedTextField(query, { query = it }, label = { Text("Search your library") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Online songs: find them in Search → YouTube Music, then ⋮ → Add to queue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.id }) { s ->
                        Row(Modifier.fillMaxWidth().clickable { vm.addToParty(s) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(s.albumArtUri, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist + " · " + s.provider.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.Add, "Add")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostCard(vm: PlayerViewModel, party: ListenTogether.State) {
    SectionCard {
        Text("Host settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Only I control the music")
                Text("Friends can still vote to skip and pause their own phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = party.hostOnlyControl, onCheckedChange = vm::setPartyHostOnly)
        }
    }
}

@Composable
private fun MembersCard(vm: PlayerViewModel, party: ListenTogether.State) {
    SectionCard {
        Text("Listeners", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        party.members.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Avatar(m.displayName, m.avatarUrl, 36.dp, connected = m.connected, host = m.isHost)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.displayName + if (m.memberId == party.you?.memberId) " (you)" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (m.isHost) "Host · " else "") + if (m.connected) "Connected" else "Reconnecting…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (party.isHost && m.memberId != party.you?.memberId) TextButton(onClick = { vm.kickFromParty(m.memberId) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun ActivityCard(vm: PlayerViewModel) {
    val activity by vm.partyActivity.collectAsStateWithLifecycle()
    if (activity.isEmpty()) return
    SectionCard {
        Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        activity.take(6).forEach { a ->
            Text("${a.by}: ${a.detail.ifBlank { a.action }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Avatar(name: String, url: String?, size: androidx.compose.ui.unit.Dp, connected: Boolean, host: Boolean = false) {
    Box {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                .border(2.dp, if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) AsyncImage(url, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            else Text(name.trim().take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = (size.value * 0.4f).sp)
        }
        if (host) {
            Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Star, "Host", tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun Artwork(url: String?, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Suppress("unused")
private fun PartyMember.label() = displayName
