# Listen Together (MImic 4.5)

Start a party, invite friends, and everyone hears the same song at the same moment. Each phone
plays the song from **its own** source — its own file, its own Navidrome, its own download or its
own YouTube Music stream. No audio and no login tokens travel through the party; only the song's
title, artist, length and (for online songs) its YouTube id and public artwork link.

## 1. Run the party server (once, on your PC)

The server is BitChord's Listen Together backend (Go, GPL-3.0), in `server/party`. It keeps
parties in memory only — no database, no music, no accounts, no secrets.

```bash
docker compose -f server/party/docker-compose.yml up -d --build
curl http://localhost:8000/healthz      # {"ok":true,...}
```

It is separate from the Navidrome compose file, so it never touches the music server. It
restarts with Docker Desktop (start Docker Desktop after a reboot, as for Navidrome).

## 2. Give MImic the server address

Settings → Listen Together → Party server → **Check & save**.

| Who is listening | Address to use |
|---|---|
| Phones on your home Wi-Fi | `http://<PC LAN IP>:8000`, e.g. `http://192.168.0.178:8000` |
| You and friends on your Tailscale network | `http://<pc-name>.<tailnet>.ts.net:8000` |
| Friends anywhere (no Tailscale) | Tailscale Funnel: `tailscale funnel 8000` on the PC → `https://<pc-name>.<tailnet>.ts.net` — set `MIMIC_PARTY_PUBLIC_ORIGIN` to that https address so invite links use it |

Funnel makes the party server reachable from the internet. That is a choice for you to make, so
MImic doesn't turn it on; the server holds no data, but anyone with a party code can join that party.

## 3. Start / join

- **Start a Party**: you become the host; what you're playing becomes the party's song.
- **Invite**: shares a code and two links — a web page (`http://server/invite/CODE`, works in any
  browser and opens MImic) and an app link (`mimic://party/CODE?server=…`).
- **Join**: tap the link, or type the code (the invite shows who's in the party first).
- Your name: your MImic Google account name/photo if signed in, otherwise the name you type. No
  extra account is needed.

## In a party

- Shared queue (Add songs, remove, tap a song to play it for everyone); Add to queue / Play next
  anywhere in MImic go to the party queue.
- Play / pause / skip / seek from MImic, the notification, the lock screen or Bluetooth move the
  whole party. A call or another app taking the audio pauses only your phone.
- **Vote to skip**: a majority of connected phones skips the song.
- Host: "Only I control the music" (others can still vote and pause their own phone), remove
  listeners, **End party**.
- A song that isn't on your phone (and can't be found online) shows "Not available on this phone";
  you rejoin on the next song.
- Lost connection: reconnects by itself. The server keeps your place for 45 seconds; if MImic is
  closed for longer, join again with the code.

## How sync works

The server holds "position P was true at server time A"; each phone measures its clock offset to
the server (best of several round trips) and computes where it should be. Resumes are scheduled a
moment ahead so everyone starts together; drift above 1.2 s is corrected by a seek, at most once
every 6 s. Measured on two emulators: the phones played within ~20 ms of each other.
