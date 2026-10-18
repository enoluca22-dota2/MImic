package com.example.mimic.data

import com.example.mimic.data.db.FavoriteDao
import com.example.mimic.data.db.FavoriteEntity
import com.example.mimic.data.db.FavoriteGroupDao
import com.example.mimic.data.db.FavoriteGroupEntity
import com.example.mimic.data.db.PendingStarDao
import com.example.mimic.data.db.PendingStarEntity
import com.example.mimic.data.server.SubsonicClient
import com.example.mimic.data.server.remoteStableId
import kotlinx.coroutines.flow.Flow

/**
 * Favorites (MImic 3.0). Room is always the source of truth for what the UI shows, so a heart
 * tap updates Home and Library at once, online or offline. For server tracks the change is also
 * sent to the server (Subsonic star/unstar); if that fails — offline, server down — the intent is
 * queued in `pending_server_ops` for that account and flushed on the next connection. Nothing is
 * silently dropped.
 */
class FavoritesRepository(
    private val favoriteDao: FavoriteDao,
    private val groupDao: FavoriteGroupDao,
    private val pendingDao: PendingStarDao,
) {
    val favoriteIds: Flow<List<Long>> = favoriteDao.favoriteIds()
    val groups: Flow<List<FavoriteGroupEntity>> = groupDao.all()
    val pendingCount: Flow<Int> = pendingDao.count()

    /**
     * Toggle a song. [client] is the live server session (null when signed out); [accountKey]
     * names the account a server-track change belongs to even when the session is offline.
     */
    suspend fun toggleSong(song: Song, client: SubsonicClient?, accountKey: String?): Boolean {
        val nowFavorite = !favoriteDao.isFavorite(song.id)
        if (nowFavorite) favoriteDao.add(FavoriteEntity(song.id, System.currentTimeMillis()))
        else favoriteDao.remove(song.id)

        // Only Navidrome has a server-side "starred" list; cloud drives have no music favorites,
        // so a cloud track's favorite lives in Room only (per song id = per account + file).
        val remoteId = song.remoteId?.takeIf { song.provider == MusicProvider.NAVIDROME }
        val account = client?.accountKey ?: accountKey
        if (remoteId != null && account != null) {
            val sent = client != null &&
                (if (nowFavorite) client.star(remoteId) else client.unstar(remoteId)).isSuccess
            if (sent) pendingDao.remove(remoteId, account)
            else pendingDao.put(PendingStarEntity(remoteId, account, nowFavorite, System.currentTimeMillis()))
        }
        return nowFavorite
    }

    /**
     * On (re)connect: push queued changes for this account, then import songs starred on the
     * server (e.g. from another client) into local favorites. Import is additive only — a local
     * un-favorite is never overridden by a stale server state because it was pushed first.
     */
    suspend fun syncWithServer(client: SubsonicClient) {
        for (op in pendingDao.forAccount(client.accountKey)) {
            val r = if (op.star) client.star(op.remoteId) else client.unstar(op.remoteId)
            if (r.isSuccess) pendingDao.remove(op.remoteId, op.accountKey) else return // still offline
        }
        val starred = client.starredSongIds().getOrNull() ?: return
        for (remoteId in starred) {
            val id = remoteStableId(remoteId)
            if (!favoriteDao.isFavorite(id)) favoriteDao.add(FavoriteEntity(id, System.currentTimeMillis()))
        }
    }

    suspend fun toggleGroup(kind: String, key: String, name: String, subtitle: String): Boolean {
        val now = !groupDao.isFavorite(kind, key)
        if (now) groupDao.add(FavoriteGroupEntity(kind, key, name, subtitle, System.currentTimeMillis()))
        else groupDao.remove(kind, key)
        return now
    }
}
