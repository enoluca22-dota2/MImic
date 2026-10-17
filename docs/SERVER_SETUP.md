# MImic Music Server (Navidrome) — Setup & Troubleshooting

Navidrome hosts your music on your Windows laptop and speaks the **Subsonic/OpenSubsonic**
API that the MImic Android app connects to. Your audio files are mounted **read-only** — the
server never modifies them.

## 1. Start the server (Docker Desktop is already installed)

From a terminal in the `server/` folder, point `MIMIC_MUSIC_DIR` at your music and start it:

PowerShell:
```
$env:MIMIC_MUSIC_DIR = "C:\Users\Eno\Music"   # <-- your music folder
docker compose up -d
```
Git Bash:
```
MIMIC_MUSIC_DIR="/c/Users/Eno/Music" docker compose up -d
```

First run downloads the image and scans your library (a few minutes for large collections).

## 2. Create the admin account
Open http://localhost:4533 in a browser. The first account you create is the **admin**.
Add more users later under **Settings → Users** (admin-created accounts; there is no public
sign-up — MImic matches that model).

## 3. Connect your phone (same Wi-Fi)
Find your laptop's LAN IP:
```
ipconfig   # look for IPv4 Address, e.g. 192.168.1.20
```
In MImic: **Settings → Music Server**, enter `http://192.168.1.20:4533`, your username and
password, tap **Test connection**, then **Connect**.

> Cleartext http is fine on your own LAN. For access away from home, use a private network
> (Tailscale) — see `docs/REMOTE_ACCESS.md` (planned), not public port-forwarding.

## 4. Operations
- **New music**: drop files into your music folder; Navidrome rescans hourly (or Settings →
  "Scan" / `docker exec mimic-navidrome ...`).
- **Backups**: copy the `server/navidrome-data` folder while the container is stopped.
- **Stop / start**: `docker compose stop` / `docker compose start`.
- **Auto-start on boot**: `restart: unless-stopped` is already set; Docker Desktop must be
  allowed to start at login (Docker Desktop → Settings → General).
- **Logs**: `docker compose logs -f`.

## Troubleshooting
- Phone can't connect: confirm both devices are on the same Wi-Fi; Windows Firewall may
  prompt to allow Docker/port 4533 the first time — allow it for **Private** networks. (Do
  not open the port to the public internet.)
- Empty library: check `MIMIC_MUSIC_DIR` really points at audio files and re-run
  `docker compose up -d`.
- The laptop must be **running and awake** for the phone to stream or download.

## Status (honest)
These files are prepared and the compose config is valid, but this setup has **not been
started or streamed from** in this environment — run the steps above on your laptop and
verify with your phone. Nothing is "working" until you've played a real track.
