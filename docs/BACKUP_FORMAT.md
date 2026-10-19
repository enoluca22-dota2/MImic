# MImic backup file (format version 1)

Settings → **Backup & Restore** writes one UTF-8 JSON file (`MImic-backup-YYYY-MM-DD.json`) to a
place you choose with Android's file picker. You can also restore from any place the file picker
can open. Code: `app/src/main/java/com/example/mimic/data/backup/`.

## What's in it

| Key | Contents |
|---|---|
| `format`, `version` | always `"mimic-backup"` and `1`. A newer version is refused with "Update MImic". |
| `createdAt`, `app` | creation time (ms) and the MImic version that wrote the file |
| `songs` | every song mentioned below: `ref`, `id`, `source`, `remoteId`, `title`, `artist`, `album`, `durationMs` |
| `favorites` | `{song: ref, addedAt}` |
| `favoriteGroups` | favorite albums and artists: `{kind, key, name, subtitle, addedAt}` |
| `playlists` | `{name, createdAt, songs: [ref, …]}` in playlist order |
| `history` | plays as `[ref, playedAt]` |
| `themes` | custom Theme Studio themes |
| `settings` | app settings as `{key: {t: type, v: value}}` (types: s, i, l, b, f, d, ss) |
| `radio` | `countries` (ISO codes, in order) and favorite or recently played `stations` |
| `server` | Navidrome address and user name only |

**Never included:** passwords, tokens, or any key whose name looks like a secret (pass, token,
secret, salt, cookie, credential, auth). Music files and downloads are never included either.
A restore ignores secret-looking keys even if someone adds them to the file by hand.

## Song identity

A phone song's id comes from Android's media database, and it changes on another phone or after
a reinstall. On restore, each backed-up song is matched against the library on that phone:

1. The same Navidrome track id. Navidrome ids are the same everywhere. A Navidrome song that
   wasn't loaded when the backup was made is stored with its id only.
2. The same id **and** the same title and artist (the same phone).
3. Otherwise, exactly one song with the same title and artist (ignoring case, accents and
   punctuation) whose length is within 3 seconds.

If a song is ambiguous or missing, it is skipped and counted. It is never matched with a guess.

## Restore rules

- Favorites, history, album and artist favorites, and radio stations are **added**. Nothing
  you already have is removed or duplicated, so restoring the same file twice changes nothing.
- A playlist with a new name is created. If a playlist with the same name already exists, it
  only gets the songs it's missing, added at the end.
- Custom themes are added if their id isn't present yet.
- Settings from the backup **replace** the same settings on the phone. The restore dialog says so,
  and each part (music data, settings & themes, radio) can be left out.
- The Navidrome address fills the sign-in screen only if no server is set up. You sign in with
  your password yourself.
