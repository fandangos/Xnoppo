# Xnoppo

Use **Emby as the interface** and an **OPPO UDP-20x** (e.g. 203) as the player: cast a movie
from any Emby client to the "Xnoppo" device and this bridge tells the OPPO to mount the media
over **SMB** and play it — best of both worlds.

This is a slimmed-down, headless, Docker-ready rebuild of the original Xnoppo. It supports
play / pause / stop / seek / chapter / audio track / subtitle track. No web UI, no AV-receiver
or TV integration.

## How it works

1. Connects to Emby over websocket and registers a controllable device named **Xnoppo**.
2. When you cast a title, it reads the item's path from Emby, derives the SMB
   `server / share / folder / file`, wakes the OPPO (UDP) and mounts the share over SMB
   (with credentials), then plays the file (or a BDMV/disc folder for Blu-ray/UHD rips).
3. Relays transport controls and reports playback progress back to Emby.

Most Emby libraries store **UNC paths** (`\\host\share\...`) which the OPPO can mount directly,
so **no path configuration is needed** — every library of the configured user works automatically.

## Run with Docker

```bash
docker run -d --name xnoppo \
  --network host \
  -e EMBY_SERVER="http://192.168.1.10:8096" \
  -e EMBY_USER="YourUser" \
  -e EMBY_PASSWORD="YourPassword" \
  -e OPPO_IP="192.168.1.20" \
  -e SMB_USER="your_smb_user" \
  -e SMB_PASSWORD="YourSmbPassword" \
  --restart unless-stopped \
  YOURDOCKERHUBUSER/xnoppo:latest
```

`--network host` is recommended so the container can reach the OPPO directly (control API on
TCP 436, UDP wake on 7624) and the SMB host. Bridge networking also works as long as those hosts
are routable.

### Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `EMBY_SERVER` | ✅ | — | Emby base URL incl. port, e.g. `http://192.168.1.10:8096` |
| `EMBY_USER` | ✅ | — | Emby username |
| `EMBY_PASSWORD` | ✅ | — | Emby password |
| `OPPO_IP` | ✅ | — | OPPO player IP address |
| `SMB_USER` | ⬜ | (empty) | SMB username for the share. Empty = anonymous/guest mount |
| `SMB_PASSWORD` | ⬜ | (empty) | SMB password |
| `KEEP_ON` | ⬜ | `true` | Keep the OPPO powered on after playback (turn it off with the OPPO remote). `false` = power off when playback ends |
| `LIBRARY_IDS` | ⬜ | `*` | `*` = all libraries. Or a comma-separated list of Emby library Ids to restrict |
| `PATH_MAPPING` | ⬜ | `[]` | JSON array of `{ "name", "Emby_Path", "Oppo_Path" }` mappings. Only needed for libraries Emby stores under a non-UNC mount point. Use `\\\\` for backslashes |
| `DEBUG` | ⬜ | `1` | Log level: `0` quiet, `1` info, `2` verbose |
| `OPPO_TIMEOUT_CONNECT` | ⬜ | `10` | Seconds to wait for the OPPO control port |
| `OPPO_TIMEOUT_MOUNT` | ⬜ | `60` | Seconds to wait for an SMB mount |
| `OPPO_TIMEOUT_PLAY` | ⬜ | `60` | Seconds to wait for playback to start |
| `SMBTRICK` | ⬜ | `false` | Legacy SMB workaround (leave off unless needed) |
| `AUTOSCRIPT` | ⬜ | `false` | Telnet-unmount the share after playback (leave off) |

`PATH_MAPPING` example (only if a library is NOT stored as a UNC path in Emby):

```
-e PATH_MAPPING='[{"name":"iso","Emby_Path":"/media","Oppo_Path":"\\\\192.168.1.30\\movies"}]'
```

## Unraid

1. **Add Container** → set Repository to `YOURDOCKERHUBUSER/xnoppo:latest`.
2. Set **Network Type** to `Host`.
3. Add the variables above as container variables (at minimum `EMBY_SERVER`, `EMBY_USER`,
   `EMBY_PASSWORD`, `OPPO_IP`, and `SMB_USER`/`SMB_PASSWORD` if your shares require auth).
4. Apply. Then in any Emby client, cast a movie to the **Xnoppo** device.

## Cast any stream (Nuvio / Stremio / AIOStreams) with subtitles

Besides Emby, Xnoppo can play **any HTTP(S) video URL** on the OPPO, with external
subtitles. A small Android app (phone or Android TV) registers as an external player:
pick it from Nuvio's "open with" list and it posts the stream to Xnoppo.

Why not plain DLNA? The OPPO *is* a DLNA renderer, but as a renderer it ignores external
subtitles entirely (it never even fetches them) and can't show MP4 `mov_text` tracks. Played
from an SMB share it loads `Movie.srt` next to `Movie.mkv`. So each cast becomes a tiny share
folder: the video file is a window onto the remote stream (rclone mount, reads are proxied to
the upstream with Range requests and cached on disk), and the subtitle sits beside it.

```
app ──POST /cast──► Xnoppo ── probe stream, fetch subtitles, publish <id>/<Title>.mkv + .srt
                    rclone mount (/cast) ◄── host Samba share "xnoppo_cast" ◄── OPPO mounts & plays
OPPO read ─► smbd ─► FUSE/rclone (disk cache /cache) ─► Xnoppo proxy ─► upstream URL (Range)
```

### API (port `CAST_PORT`, default 8089)

| Call | Body | |
|---|---|---|
| `POST /cast` | `{"url", "title"?, "headers"?, "subtitles"?: [{"url" or "data", "lang"?}], "position_ms"?, "dry_run"?}` | Start a cast (202). `headers` = `{"K":"V"}` or `["K: V"]`. `dry_run` publishes the files without touching the OPPO |
| `GET /status` | | Stage (`preparing` → `mounting` → `playing` → `ended`/`error`), position, duration |
| `POST /control` | `{"action": play\|pause\|stop\|next\|prev\|ffwd\|rew\|power_off\|seek\|subtitle\|audio\|tracks\|key}` | `seek` takes `"position"` (s); `subtitle`/`audio` take `"index"`; `key` sends a raw OPPO code |
| `GET /health` | | `ok:false` + reason when FUSE/rclone aren't available |

Set `CAST_TOKEN` to require an `X-Xnoppo-Token` header (or `?token=`).

### Cast variables

| Variable | Default | Description |
|---|---|---|
| `CAST_ENABLE` | `true` | Turn the cast service off entirely |
| `CAST_PORT` | `8089` | API port |
| `CAST_SMB_SERVER` | auto | Address the OPPO uses to reach the share (the Unraid IP) |
| `CAST_SMB_SHARE` | `xnoppo_cast` | Samba share name exporting the `/cast` mount |
| `CAST_SMB_USER` / `CAST_SMB_PASSWORD` | `SMB_USER` / `SMB_PASSWORD` | Credentials the OPPO mounts the share with (empty = guest) |
| `CAST_SUB_LANGS` | (caller order) | Preferred subtitle languages, e.g. `pob,por`. The first subtitle becomes `<Title>.srt`, the one the OPPO auto-loads |
| `CAST_SUB_MAX` | `3` | Subtitles to fetch per cast |
| `CAST_SUB_ENCODING` | `utf-8` | Encoding written to disk (`cp1252` if your OPPO shows garbage) |
| `CAST_CACHE_MAX` / `CAST_CACHE_AGE` | `150G` / `6h` | rclone disk cache limits. A file being played is never evicted, so a 100 GB remux can exceed the cap while it plays |
| `CAST_READ_AHEAD` | `512M` | rclone read-ahead into the cache |
| `CAST_RCLONE_ARGS` | | Extra `rclone mount` flags |
| `CAST_MOUNT` | `/cast/media` | Where rclone mounts inside the container |

### Unraid setup for casting

1. Container → **Extra Parameters**: `--device /dev/fuse --cap-add SYS_ADMIN --security-opt apparmor:unconfined`
2. Add path **/cast** → e.g. `/mnt/disks/xnoppo_cast`, access mode **RW/Shared**. rclone mounts at
   `/cast/media`, so the host sees it at `/mnt/disks/xnoppo_cast/media` (a subfolder on purpose: after a
   crash the dead mount sits *below* the bind source, so the container can still start and clears it)
3. Add path **/cache** → a folder on the cache pool, e.g. `/mnt/cache/appdata/xnoppo/cache`
4. Share the host folder over SMB (Settings → SMB → SMB Extras, i.e. `/boot/config/smb-extra.conf`):
   ```
   [xnoppo_cast]
     path = /mnt/disks/xnoppo_cast/media
     browseable = yes
     read only = yes
     valid users = YOUR_SMB_USER
   ```
5. Set `CAST_SMB_SERVER` to the Unraid **NetBIOS name** (e.g. `TOWER`), not its IP: the OPPO's SMB
   login resolves servers through NetBIOS browsing, the same way Emby UNC paths (`\\TOWER\...`) reach it.
   The share must be browseable for the same reason. Check `http://UNRAID:8089/health`.

Test without the app (`dry_run` stops before the OPPO; drop it to really play):

```bash
curl -X POST http://UNRAID:8089/cast -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/movie.mkv","title":"Movie (2024)",
       "subtitles":[{"url":"https://example.com/movie.pt.srt","lang":"por"}],"dry_run":true}'
curl http://UNRAID:8089/status
```

`tools/cast_localtest.py` runs the whole file layer locally (fake upstream → real rclone
FUSE mount → byte-for-byte checks), no OPPO needed.

## Building / publishing

The included GitHub Actions workflow (`.github/workflows`) builds a multi-arch
(`linux/amd64`, `linux/arm64`) image and pushes it to Docker Hub on every push to `main`
and on `v*` tags. Set repository secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` first.

Build locally instead:

```bash
docker build -t YOURDOCKERHUBUSER/xnoppo:latest .
docker push YOURDOCKERHUBUSER/xnoppo:latest
```

## Notes

- The OPPO's SMB credential store can occasionally get stuck (a folder that mounted before
  starts returning `id_error`). Rebooting the OPPO clears it.
- Changing audio/subtitle mid-playback works on the OPPO and updates Emby's backend, but some
  Emby clients don't live-refresh the now-playing track label (cosmetic; re-open the now-playing
  screen to refresh). Selecting the track *before* pressing Play is always reliable.
