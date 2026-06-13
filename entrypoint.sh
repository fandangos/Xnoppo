#!/bin/bash
set -e

# ─── Required env vars ───────────────────────────────────────────────
for v in EMBY_SERVER EMBY_USER EMBY_PASSWORD OPPO_IP; do
  if [ -z "${!v}" ]; then
    echo "ERROR: $v not set"
    exit 1
  fi
done

# ─── Helper: parse boolean ──────────────────────────────────────────
parse_bool() {
  case "$(echo "$1" | tr '[:upper:]' '[:lower:]')" in
    true|1|yes) echo "true" ;;
    *) echo "false" ;;
  esac
}

# ─── Debug level (integer 0/1/2; also accept true/false) ────────────
case "$(echo "${DEBUG:-1}" | tr '[:upper:]' '[:lower:]')" in
  2)           DEBUG_LEVEL=2 ;;
  true|yes)    DEBUG_LEVEL=2 ;;
  0|false|no)  DEBUG_LEVEL=0 ;;
  *)           DEBUG_LEVEL=1 ;;
esac

# ─── Libraries ───────────────────────────────────────────────────────
# Default "*" = every library plays, no per-library config needed.
# Otherwise pass a comma-separated list of Emby library Ids to restrict.
LIBRARY_IDS="${LIBRARY_IDS:-*}"
if [ "$LIBRARY_IDS" = "*" ]; then
  LIB_JSON="[]"
  ENABLE_ALL="true"
else
  LIB_JSON="["
  first=true
  IFS=',' read -ra IDS <<< "$LIBRARY_IDS"
  for id in "${IDS[@]}"; do
    id="$(echo "$id" | xargs)"  # trim whitespace
    if [ -n "$id" ]; then
      if [ "$first" = true ]; then first=false; else LIB_JSON+=","; fi
      LIB_JSON+="{\"Name\":\"\",\"Id\":\"$id\",\"Active\":true}"
    fi
  done
  LIB_JSON+="]"
  ENABLE_ALL="false"
fi

# ─── Path mapping (optional) ─────────────────────────────────────────
# Emby usually reports UNC paths (\\host\share\...) the OPPO can mount
# directly, so no mapping is needed. Provide PATH_MAPPING (a JSON array)
# only for libraries Emby stores under a non-UNC internal mount point, e.g.
#   [{"name":"x","Emby_Path":"/media","Oppo_Path":"\\\\10.0.0.5\\movies"}]
PATH_MAPPING="${PATH_MAPPING:-[]}"

# ─── Build config.json ──────────────────────────────────────────────
cat > /app/config.json <<EOF
{
    "emby_server": "${EMBY_SERVER}",
    "user_name": "${EMBY_USER}",
    "user_password": "${EMBY_PASSWORD}",
    "Oppo_IP": "${OPPO_IP}",
    "smb_user": "${SMB_USER:-}",
    "smb_password": "${SMB_PASSWORD:-}",
    "timeout_oppo_conection": ${OPPO_TIMEOUT_CONNECT:-10},
    "timeout_oppo_playitem": ${OPPO_TIMEOUT_PLAY:-60},
    "timeout_oppo_mount": ${OPPO_TIMEOUT_MOUNT:-60},
    "smbtrick": $(parse_bool "${SMBTRICK:-false}"),
    "Autoscript": $(parse_bool "${AUTOSCRIPT:-false}"),
    "Always_ON": $(parse_bool "${KEEP_ON:-true}"),
    "enable_all_libraries": ${ENABLE_ALL},
    "Libraries": ${LIB_JSON},
    "servers": ${PATH_MAPPING},
    "MonitoredDevice": "",
    "DebugLevel": ${DEBUG_LEVEL}
}
EOF

echo "Config generated (passwords redacted):"
sed -E 's/("user_password"|"smb_password")( *): *"[^"]*"/\1\2: "***"/' /app/config.json

# ─── Run ─────────────────────────────────────────────────────────────
exec python /app/main.py
