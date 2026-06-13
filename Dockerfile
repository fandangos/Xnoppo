FROM python:3.11-slim

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
    AUTOSCRIPT="false"

ENTRYPOINT ["/app/entrypoint.sh"]
