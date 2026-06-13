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
