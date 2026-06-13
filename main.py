import sys
import os
import time
import json
import threading
import logging
import logging.handlers

# Add current directory to path for lib imports
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib.Emby_ws import xnoppo_ws


def main():
    cwd = os.path.dirname(os.path.abspath(__file__))
    config_file = os.path.join(cwd, "config.json")

    # Load config
    with open(config_file, 'r') as f:
        config = json.load(f)
    f.close()

    # Set up logging
    logfile = os.path.join(cwd, "xnoppo.log")
    if config.get("DebugLevel", 1) >= 2:
        rfh = logging.handlers.RotatingFileHandler(
            filename=logfile, mode='a',
            maxBytes=5 * 1024 * 1024, backupCount=2,
        )
        logging.basicConfig(
            format='%(asctime)s %(levelname)s: %(message)s',
            datefmt='%d/%m/%Y %I:%M:%S %p',
            level=logging.DEBUG, handlers=[rfh]
        )
    elif config.get("DebugLevel", 1) >= 1:
        rfh = logging.handlers.RotatingFileHandler(
            filename=logfile, mode='a',
            maxBytes=50 * 1024 * 1024, backupCount=2,
        )
        logging.basicConfig(
            format='%(asctime)s %(levelname)s: %(message)s',
            datefmt='%d/%m/%Y %I:%M:%S %p',
            level=logging.INFO, handlers=[rfh]
        )
    else:
        logging.basicConfig(
            format='%(asctime)s %(levelname)s: %(message)s',
            datefmt='%d/%m/%Y %I:%M:%S %p',
            level=logging.CRITICAL
        )

    # Initialize WebSocket client
    emby_wsocket = xnoppo_ws()
    emby_wsocket.ws_config = config
    emby_wsocket.config_file = config_file

    # Minimal language strings. The lib code (lib/Xnoppo.py) indexes these
    # keys directly when sending status messages back to Emby, so an empty
    # dict would raise KeyError and kill the play thread. No web UI is shipped,
    # so we embed the English strings instead of loading web/lang/en-US/lang.js.
    emby_wsocket.ws_lang = {
        "x_msg_init_oppo": "Launching on OPPO",
        "x_msg_wait_for_mount": "Waiting to mount folder..",
        "x_msg_wait_for_play": "Waiting to start playing: ",
        "x_msg_timeout_play": "Timeout Playing",
        "x_msg_init_play": "Playback started",
        "x_msg_error_play": "It was not possible to play the file ",
        "x_msg_error_mount": "It was not possible to mount the folder ",
        "x_msg_error_no_oppo": "OPPO is not available",
    }

    # Start WebSocket thread
    ws_thread = threading.Thread(target=emby_wsocket.run, daemon=True)
    ws_thread.start()
    logging.info('Xnoppo container started - waiting for Emby play events')

    # Keep alive
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        logging.info('Shutting down')
        emby_wsocket.stop()
        sys.exit(0)


if __name__ == "__main__":
    main()
