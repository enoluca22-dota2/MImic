# MImic — Remote Access (reach your laptop server from anywhere)

MImic's **Account → Server address** field is free-form, so switching between local and remote is
just a different address — no app change needed. **Never expose Navidrome directly to the public
internet.** This project uses Tailscale (private, WireGuard-encrypted mesh), not port forwarding.

## Current status (MImic 2.8, verified 2026-10-17)
- **Tailscale**: installed (v1.104.1), service running, this laptop signed in as node **`eno`**
  (tailnet `tailee647d.ts.net`, account `enoluca22@`). MagicDNS name: **`eno.tailee647d.ts.net`**.
- ✅ **Direct tailnet access WORKS**: from any device on the tailnet,
  **`http://eno.tailee647d.ts.net:4533`** (or `http://100.64.255.5:4533`) reaches Navidrome
  — verified: `/app/` → HTTP 200, API returns `type=navidrome 0.64.2`. Transport is
  WireGuard-encrypted and restricted to your authorized devices. No public ports. No Funnel.
- ⏳ **Serve HTTPS (TLS hostname `https://eno.tailee647d.ts.net`) is PENDING**: provisioning a
  cert fails with *"your Tailscale account does not support getting TLS certs"* — **HTTPS
  Certificates must be enabled in the admin console** (see below). Once enabled, run
  `tailscale serve --bg 4533` and the URL becomes `https://eno.tailee647d.ts.net` (no `:4533`).

## Enable Serve HTTPS (one-time, admin console)
1. Open https://login.tailscale.com/admin/dns
2. Ensure **MagicDNS** is on (it is), then under **HTTPS Certificates** click **Enable HTTPS**.
3. Back on the laptop: `& "C:\Program Files\Tailscale\tailscale.exe" serve --bg 4533`
4. `tailscale serve status` should list `https://eno.tailee647d.ts.net` → `http://127.0.0.1:4533`.
   Use that HTTPS URL in MImic → Account. (Serve config persists across reboots.)

## Use it in MImic
- **At home (same Wi-Fi):** `http://<laptop-LAN-IP>:4533`
- **Anywhere, now (tailnet):** `http://eno.tailee647d.ts.net:4533` (both devices on the tailnet)
- **Anywhere, after HTTPS enabled:** `https://eno.tailee647d.ts.net`
Local on-device music always plays without any account/server.

## Reliable startup after a Windows reboot
- Navidrome container: `restart: unless-stopped` (set) — **requires Docker Desktop to auto-start**.
  In Docker Desktop → Settings → General, enable **"Start Docker Desktop when you sign in"**.
- Tailscale: installed as a Windows service (auto-starts). Serve config persists once set.

## Security notes
- Tailnet access is private to your devices; nothing is published to the public internet.
- Do **not** use `tailscale funnel` (that would expose it publicly) and do not port-forward 4533.
- Do not expose the Navidrome admin UI or any file-management service over Funnel.
- Credentials are stored encrypted at rest on Android (Keystore AES-GCM; `password_enc`).
- The music mount stays read-only (`:ro`).

## Emulator note (development only)
The Android emulator isn't on the tailnet. For local dev use `adb reverse tcp:4533 tcp:4533`
and server address `http://localhost:4533`. True worldwide testing needs a real tailnet device
off the home network (e.g. your phone on cellular with the Tailscale app connected).
