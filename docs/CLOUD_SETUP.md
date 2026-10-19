# MImic 3.1 — Google Drive & OneDrive music (setup, limits, test plan)

MImic can stream and download music straight from **your own** Google Drive or OneDrive folder.
No laptop, Docker, Navidrome or Tailscale is involved: the phone talks to Google / Microsoft
directly with official APIs and OAuth. Navidrome stays as an optional extra source.

Sign-in always happens in the provider's own UI (Google Play services account sheet, or the
Microsoft login page in a Custom Tab). MImic never sees your Google or Microsoft password, stores
no tokens itself (the provider SDKs keep them encrypted), and only ever *reads* the one folder you
pick — it never uploads, renames, moves, shares or deletes cloud files.

Both providers need a **one-time app registration in your own developer account**. These give
MImic public client identifiers; there is no client secret (a mobile app can't keep one).

Identity of the APK you install (must match both registrations):

| | value |
|---|---|
| Package name | `com.example.mimic` |
| Signing SHA-1 (debug keystore of this laptop) | `78:0E:4B:F7:03:93:19:BB:F0:99:D5:F7:A2:3B:C6:9C:00:5A:5F:F9` |
| Signing SHA-256 (same keystore) | `C0:3C:51:07:5A:49:A0:49:38:71:85:C7:75:62:60:74:36:8A:A9:F4:22:0E:B5:BA:0E:74:CC:4A:4F:88:08:7D` |
| Signature hash (base64 SHA-1, for Microsoft) | `eA5L9wOTGbvwmdX3ojvGnABaX/k=` |

Since 3.3 the app shows the fingerprints of the APK that is *actually installed* in its error and
setup messages (Settings → Music Sources), selectable for copying. If they differ from this table,
register the values the app shows.

If you ever sign MImic with a different keystore, both registrations must be updated.

---

## 1. OneDrive (Microsoft Graph + MSAL) — recommended first

1. Go to <https://entra.microsoft.com> → **App registrations** → **New registration**
   (sign in with the Microsoft account that owns your OneDrive is fine).
   - Name: `MImic`
   - Supported account types: **Personal Microsoft accounts only**
     (or "Accounts in any organizational directory and personal Microsoft accounts").
   - Redirect URI: leave empty here.
2. Open the new app → **Authentication** → **Add a platform** → **Android**:
   - Package name: `com.example.mimic`
   - Signature hash: `eA5L9wOTGbvwmdX3ojvGnABaX/k=`
   - Save. The portal shows the redirect URI
     `msauth://com.example.mimic/eA5L9wOTGbvwmdX3ojvGnABaX%2Fk%3D` — that is what MImic uses.
3. **API permissions** → Microsoft Graph → **Delegated** → add `Files.Read`
   (`User.Read`, `offline_access`, `openid`, `profile` are fine to keep). No admin consent is needed
   for personal accounts; you consent yourself at first sign-in.
4. Do **not** create a client secret.
   Optional: to also allow work/school accounts choose "any organizational directory and personal
   Microsoft accounts" in step 1 and add `msal.audience=common` to `cloud.properties`
   (`organizations` = work/school only; default `consumers` = personal only). It must match the
   registration or Microsoft rejects the sign-in.
5. Copy **Application (client) ID** from Overview, then create `cloud.properties` in the project
   root (it is gitignored):
   ```properties
   msal.clientId=00000000-0000-0000-0000-000000000000
   ```
6. Rebuild: `.\gradlew.bat :app:assembleDebug` and install the APK over the existing app.
   Settings → Music Sources → OneDrive → **Connect account** now opens Microsoft's login page.

What MImic does with it: reads your chosen folder with `/items/{id}/delta` (only changes after the
first sync), streams with `/items/{id}/content` → short-lived pre-authenticated URL (Range requests
go to that URL, never to Graph), and reads tags from OneDrive's own `audio` metadata.

Disconnect = MSAL signs out on the phone. Microsoft has no API for an app to revoke its own consent
for personal accounts; to remove the grant entirely use <https://account.live.com/consent/Manage>.

## 2. Google Drive (Drive API v3 + Google Identity Services AuthorizationClient)

1. <https://console.cloud.google.com> → create a project (e.g. `mimic-music`).
2. **APIs & Services → Library** → enable **Google Drive API**.
3. **OAuth consent screen** (Google Auth Platform):
   - User type **External**, publishing status **Testing**.
   - App name `MImic`, your email as support / developer contact.
   - **Data access / scopes**: add `https://www.googleapis.com/auth/drive.readonly`.
   - **Test users**: add the Gmail address whose Drive holds your music.
4. **Credentials → Create credentials → OAuth client ID → Android**:
   - Package name `com.example.mimic`
   - SHA-1 `78:0E:4B:F7:03:93:19:BB:F0:99:D5:F7:A2:3B:C6:9C:00:5A:5F:F9`
5. Nothing to put in the app: Google matches the installed APK by package + SHA-1. No client
   secret, no `google-services.json`, no Web client ID are needed for this flow (MImic does not
   use Google *sign-in* / ID tokens — only Drive authorization through `AuthorizationClient`).
   The Android OAuth client, the Drive API and the consent screen must all be in the **same**
   Cloud project. Changes can take from a few minutes to a few hours to apply.
   Settings → Music Sources → Google Drive → **Connect account** → pick your account → Google
   shows "Google hasn't verified this app" (expected for a Testing-mode app) → Continue → allow.

### Troubleshooting Google sign-in (MImic 3.3)

Before 3.3, MImic threw away the result of Google's account chooser whenever it was not
`RESULT_OK`, so a rejected app looked like "I picked my account and nothing happened". 3.3 always
reads the result and shows the reason on the Google Drive card:

| What MImic shows | Meaning / fix |
|---|---|
| *DEVELOPER_ERROR, code 10* | No Android OAuth client matches **package + SHA-1** of the installed APK. Create it (step 4) with the SHA-1 shown in the message, in the project that has the Drive API enabled. |
| *code 12500* | Consent screen incomplete (app name / support e-mail / scopes) or your Gmail address isn't a **test user** while the app is in Testing. |
| *Google didn't complete sign-in…* | The chooser closed without a status: either you cancelled, or (on some Play services versions) the same client mismatch as code 10. |
| *Google sign-in was cancelled* | You backed out (status 16). Nothing is stored. |
| *Google Drive API has not been used in project … or it is disabled* | Step 2 is missing in that project. |
| *Drive access wasn't granted* | The Drive checkbox on Google's consent screen was left unticked. |

States on the card: Not connected → Connecting… → Choose a folder → Syncing… → Connected;
Authorization required (sign-in failed), Connection expired (Google/Microsoft no longer issues a
token — tap Reconnect, which re-authorizes the *same* account and keeps its folder), Setup required
(no registration in this build).

`adb logcat -s MImicGoogleAuth` prints the Play services status code (never tokens or e-mails).

### Why `drive.readonly` and not `drive.file` (please read)

Your workflow is "upload songs through the Drive website, MImic finds them later". `drive.file`
only covers files the app created or that the user opened *with the app* through the Google Picker.
On Android there is no native Drive Picker any more (the Drive Android API was shut down), so a
web-uploaded folder and its future children are not reachable under `drive.file`.
**Status: NOT verified experimentally** — that needs your registered OAuth client and Google
account; the claim above is from Google's scope documentation and should be confirmed by trying it.

`drive.readonly` is a **restricted scope**:

- For **you and up to 100 test users** of an app in Testing mode it works without any review.
  Google may show the unverified-app warning and may ask you to re-consent from time to time.
- A **public** release (Play Store or anyone outside the test-user list) requires Google's
  restricted-scope verification: brand verification, a public privacy policy and homepage on a
  verified domain, a demo video, and an annual third-party security assessment (CASA). MImic does
  NOT have this. Do not publish the Google Drive feature publicly without it.

Disconnect = MImic calls `revokeAccess` for the Drive scope; you can also remove it at
<https://myaccount.google.com/connections>.

---

## 3. Using it

Upload music anywhere inside one folder, e.g.

```
MImic Music/
  Artist A/Album A/01 - Track.mp3
  Artist B/Album B/Track 01.flac
  Singles/Track.mp3
```

Settings → Music Sources → Connect → **Choose folder** → open `MImic Music` → *Use as my music
folder*. Metadata precedence: embedded tags (or OneDrive's audio metadata) → folders
(Artist/Album/Track; a single folder level is treated as an album, never as an artist) → file
name. Files are never renamed or rewritten.

Supported: MP3, M4A/AAC, FLAC, OGG/Opus, WAV. Skipped and counted as unsupported: WMA, AIFF, APE,
DRM-protected M4P, etc. Identical uploads (same content hash) are shown once.

Refresh: on app start and when opening the Library (if older than 10 min), pull-to-refresh on
Home/Library, **Sync now**, and a background refresh about every 6 hours (WorkManager; Android may
delay it — it is not instant push). Only changes are transferred after the first sync.

Data controls (Settings → Music Sources → Data use): Wi-Fi-only downloads; reading tags/artwork of
new files over mobile data (off by default — they are read on Wi-Fi with small range requests,
~100–500 KB per file); background refresh on/off.

## 4. Limits and costs

- These are storage services, not music CDNs. Streaming a 4-minute 320 kbps MP3 transfers ~10 MB
  each play (downloads avoid repeat transfers). Mobile data use is yours.
- **Google Drive API**: per-project and per-user request quotas (check the current numbers in
  Cloud Console → APIs & Services → Drive API → Quotas; a personal library stays far below them);
  throttling appears as 429 or 403 `userRateLimitExceeded` /
  `rateLimitExceeded`. Files can also hit a *download quota* if downloaded extremely often.
- **Microsoft Graph**: per-app/per-user throttling returns 429/503 with `Retry-After`.
- MImic: incremental sync only, Retry-After honoured (up to 2 min inline, otherwise the sync stops
  with a clear "slow down" error), exponential backoff with jitter for 5xx, at most two tag reads in
  parallel, one cloud download at a time, artwork cached on the phone (one file per cover).

## 5. Privacy / store notes

- MImic sends nothing to anyone but Google/Microsoft (for the account you connect) and your own
  Navidrome. No analytics, no tokens or URLs in logs, no public share links ever created.
- What stays on the phone: the folder's file list + tags (Room), artwork, your downloads,
  favorites, playlists and play history (local only — not synced to other devices in 3.1).
- Before any public distribution you would need a privacy policy describing the above, Google
  restricted-scope verification (Drive), and Microsoft publisher verification (recommended).

## 6. Real-phone test with the laptop OFF (please run)

1. Do steps 1 and/or 2 above, rebuild, install `app/build/outputs/apk/debug/app-debug.apk` over
   MImic (same package + signature: data is kept).
2. Settings → Music Sources → Connect → sign in → choose your music folder → wait for "N songs".
3. Upload a new MP3 into that folder from the OneDrive / Drive website or app.
4. Shut the laptop down completely. Turn Tailscale off on the phone. Use mobile data.
5. Open MImic → Library → pull down to refresh → the new song appears (filter: OneDrive / Google Drive).
6. Play it; drag the seek bar to the middle and near the end — playback should jump immediately.
7. ⋮ → Download for offline → wait for the ✓.
8. Turn on airplane mode → play the downloaded song (works) → play a non-downloaded cloud song
   (should show "Can't reach …" instead of freezing).

Only after this passes can laptop-independent playback be called verified.
