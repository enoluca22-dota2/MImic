# MImic — Remote Access (reach your laptop server from anywhere)

MImic's **Account → Server address** field is free-form, so no app change is needed to switch
between local and remote access — you just enter a different address. **Never expose Navidrome
directly to the public internet without TLS + a reverse proxy.** The options below, easiest first.

## Status (as of 2.7)
- Local LAN: ✅ works — enter `http://<laptop-LAN-IP>:4533` while on the same Wi-Fi.
- Tailscale: ❌ **not installed on this laptop yet** (verified: not on PATH, no service). Recommended.
- Public HTTPS: ❌ not configured. Only do this with a proper reverse proxy (see below).

## Option A — Tailscale (recommended: private, encrypted, no port-forwarding)
1. Install Tailscale on the **laptop** and sign in: https://tailscale.com/download/windows
2. Install the **Tailscale** app on the Android phone and sign in with the **same account**.
3. On the laptop run `tailscale ip -4` to get its tailnet IP (looks like `100.x.y.z`).
4. In MImic → Account, set **Server address** to `http://100.x.y.z:4533` and sign in.
5. This works from any network (cellular included) because both devices are on your private
   tailnet. Navidrome stays bound to the LAN/tailnet — it is **not** public.
   - Acceptance: turn off Wi-Fi on the phone, use cellular, confirm sign-in + streaming. **Not
     claimed working until tested from outside the home network.**

## Option B — Public HTTPS via reverse proxy (advanced, only if you need a public URL)
- Put Caddy/Traefik/nginx in front of Navidrome, terminate TLS with a real cert (Let's Encrypt),
  and proxy `https://music.example.com` → `127.0.0.1:4533`.
- Keep Navidrome bound to loopback; only the proxy is exposed. Enforce strong passwords.
- In MImic, set the server address to the `https://` URL.
- Do **not** forward port 4533 directly from your router to Navidrome — that exposes it unencrypted.

## Emulator note (development only)
The Android emulator cannot reach host-published Docker ports via `10.0.2.2` reliably. For local
dev testing use `adb reverse tcp:4533 tcp:4533` and set the server address to `http://localhost:4533`.
This was used during 2.7 verification; it is a dev convenience, not a user-facing feature.
