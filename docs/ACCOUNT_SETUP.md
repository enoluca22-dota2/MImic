# MImic account (Google sign-in + sync) — setup

MImic 4.4 contains the code for an optional **MImic account**: "Continue with Google" through
Android Credential Manager, Firebase Authentication, and sync of playlists / favorites / history /
radio countries / themes between phones through Cloud Firestore. It stays **off** ("not set up in
this build") until a Firebase project is connected, because no credentials can be invented.

This account is separate from **YouTube Music** (Settings → Accounts & online music → Connect
YouTube Music), which signs in on Google's own web page and only affects YouTube Music requests.

## One-time setup (about 10 minutes)

1. Open https://console.firebase.google.com → **Add project** (e.g. `mimic-personal`).
2. **Build → Authentication → Get started → Sign-in method → Google → Enable.** Save.
   Copy the **Web client ID** shown under "Web SDK configuration" (ends in
   `.apps.googleusercontent.com`).
3. **Project settings → Your apps → Add app → Android**
   - Package name: `com.example.mimic`
   - SHA-1: `78:0E:4B:F7:03:93:19:BB:F0:99:D5:F7:A2:3B:C6:9C:00:5A:5F:F9`
     (the certificate every MImic release is signed with; check with
     `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`)
   - You do NOT need to add google-services.json to the project (MImic reads the values below).
4. **Build → Firestore Database → Create database** (production mode, a nearby region).
   Then **Rules** → paste `docs/firebase/firestore.rules` → **Publish**.
5. Create `firebase.properties` in the project root (it is gitignored — never commit it):

   ```properties
   firebase.apiKey=AIza...            # Project settings → General → Web API key
   firebase.appId=1:1234567890:android:abcdef...   # Project settings → Your apps → App ID
   firebase.projectId=mimic-personal
   google.webClientId=1234567890-xxxx.apps.googleusercontent.com
   ```
6. Build and release as usual (`scripts/release.sh`). Settings → Accounts & online music now
   shows **Continue with Google**.

The API key and app id are not secrets in the Firebase model (every Firebase Android app ships
them); access is controlled by the Firestore rules, which let a signed-in user read and write
only `users/{their uid}/…`.

## What is synced, and what never is

| Synced (metadata only)                         | Never uploaded                                   |
|-----------------------------------------------|--------------------------------------------------|
| MImic playlists and their song order          | Audio files / downloads                          |
| Favorites (songs, albums, artists)            | Navidrome password, server address and username |
| Play history (newest 5000 plays)              | YouTube Music cookies / sessions                 |
| Radio countries and favorite stations         | Google tokens, signing keys                      |
| Custom themes and settings (applied only when you tap "Restore settings") | Anything from other apps            |

Each phone writes its own document (`users/{uid}/devices/{random id}`); merging is additive
(nothing local is ever deleted, repeats add nothing twice). Songs are matched across phones by
Navidrome id, YouTube id, or unique title + artist + length; songs that aren't on the other phone
are counted as "not on this phone". YouTube songs from another phone appear with their metadata
and can be downloaded again there.

Known limitation: removing a playlist or a favorite on one phone does not remove it on the others.
