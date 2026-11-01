# rclone is a static Go binary; take it from the official image (pinned).
FROM rclone/rclone:1.75.1 AS rclone

FROM python:3.11-slim

# fuse3 = fusermount3, which rclone mount needs (casting; see lib/cast.py)
RUN apt-get update \
 && apt-get install -y --no-install-recommends fuse3 \
 && rm -rf /var/lib/apt/lists/* \
 && ln -sf /usr/bin/fusermount3 /usr/bin/fusermount
COPY --from=rclone /usr/local/bin/rclone /usr/local/bin/rclone

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY lib/ ./lib/
COPY main.py .
COPY entrypoint.sh .
RUN chmod +x entrypoint.sh

LABEL org.opencontainers.image.title="Xnoppo" \
      org.opencontainers.image.description="Cast from Emby to an OPPO UDP-20x player over SMB" \
      org.opencontainers.image.source="https://github.com/fandangos/Xnoppo"

# Declared so Unraid / Community Applications auto-populates these as editable
# variables in the "Add Container" UI (it reads the image's ENV list). The
# entrypoint reads them at startup to build config.json. Empty = required and
# left blank for the user to fill; the rest carry sensible defaults.
ENV EMBY_SERVER="" \
    EMBY_USER="" \
    EMBY_PASSWORD="" \
    OPPO_IP="" \
    SMB_USER="" \
    SMB_PASSWORD="" \
    KEEP_ON="true" \
    LIBRARY_IDS="*" \
    PATH_MAPPING="[]" \
    DEBUG="1" \
    OPPO_TIMEOUT_CONNECT="10" \
    OPPO_TIMEOUT_MOUNT="60" \
    OPPO_TIMEOUT_PLAY="60" \
    SMBTRICK="false" \
    AUTOSCRIPT="false" \
    CAST_ENABLE="true" \
    CAST_PORT="8089" \
    CAST_SMB_SERVER="" \
    CAST_SMB_SHARE="xnoppo_cast" \
    CAST_SUB_LANGS="" \
    CAST_CACHE_MAX="150G" \
    CAST_CACHE_AGE="6h" \
    CAST_TOKEN="" \
    PYTHONUNBUFFERED="1"

# Casting needs (Unraid "Extra Parameters"):
#   --device /dev/fuse --cap-add SYS_ADMIN --security-opt apparmor:unconfined
# and two paths:
#   /cast  -> host folder, access mode RW/Shared; rclone mounts at /cast/media and Unraid's
#             Samba shares <host folder>/media as CAST_SMB_SHARE
#   /cache -> rclone's disk cache on the cache pool (sized for remuxes, see CAST_CACHE_MAX)
EXPOSE 8089

ENTRYPOINT ["/app/entrypoint.sh"]
