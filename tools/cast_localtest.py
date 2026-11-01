#!/usr/bin/env python3
"""End-to-end test of the cast file layer, no OPPO needed.

  fake upstream (127.0.0.1, Range, 302 redirect, required header)
     <- CastService /files/ proxy <- real `rclone mount` (FUSE) <- this script reads

Casts with dry_run, then checks the mounted video byte-for-byte (full read and
random seeks) and the published subtitles. Needs `rclone` on PATH and FUSE.

  python3 tools/cast_localtest.py --media DIR [--file c_emb.mkv]

DIR must contain the video given by --file (default: the first .mkv/.mp4 found).
"""
import argparse
import hashlib
import http.server
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

VTT = "WEBVTT\n\n00:01.000 --> 00:04.000\n<v Ana>Legenda em <i>português</i>: ação!\n"


def upstream_server(media):
    class H(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        hits = []

        def log_message(self, *a):
            pass

        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, body):
            H.hits.append((self.path, self.headers.get("Range")))
            p = self.path
            if p.startswith("/redir/"):  # debrid-style: 302 to the real location
                self.send_response(302)
                self.send_header("Location", "/real/" + p[len("/redir/"):])
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if p.startswith("/real/") and self.headers.get("X-Auth") != "secret":
                self.send_response(403)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            path = os.path.join(media, os.path.basename(p))
            if not os.path.isfile(path):
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            size = os.path.getsize(path)
            start, end = 0, size - 1
            rng = self.headers.get("Range")
            if rng:
                a, _, b = rng[6:].partition("-")
                start, end = int(a), min(int(b) if b else size - 1, size - 1)
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Content-Disposition", f'inline; filename="{os.path.basename(path)}"')
            self.end_headers()
            if body:
                with open(path, "rb") as f:
                    f.seek(start)
                    left = end - start + 1
                    try:
                        while left:
                            chunk = f.read(min(1 << 20, left))
                            self.wfile.write(chunk)
                            left -= len(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        pass

    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, H


def http_json(url, body=None):
    req = urllib.request.Request(url, json.dumps(body).encode() if body is not None else None,
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def sha256(path, limit=None):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--media", required=True)
    ap.add_argument("--file")
    args = ap.parse_args()
    media = os.path.abspath(args.media)
    fname = args.file or next(f for f in sorted(os.listdir(media)) if f.endswith((".mkv", ".mp4")))
    src = os.path.join(media, fname)
    if not shutil.which("rclone"):
        sys.exit("rclone not on PATH")

    work = tempfile.mkdtemp(prefix="xnoppo-cast-test-")
    up, H = upstream_server(media)
    base = f"http://127.0.0.1:{up.server_address[1]}"
    # a second subtitle served by URL (SRT, cp1252 - exercises decoding)
    with open(os.path.join(media, "_eng.srt"), "wb") as f:
        f.write("1\n00:00:05,000 --> 00:00:07,000\nEnglish line, caf\xe9\n".encode("cp1252"))

    os.environ.update({
        "CAST_BIND": "127.0.0.1", "CAST_PORT": "18089", "CAST_FILES_PORT": "18090",
        "CAST_RCLONE_RC": "127.0.0.1:15572", "CAST_MOUNT": os.path.join(work, "mnt"),
        "CAST_CACHE_DIR": os.path.join(work, "cache"), "CAST_SMB_SERVER": "127.0.0.1",
        "CAST_CACHE_MAX": "2G", "CAST_SUB_LANGS": "por,eng",
    })
    from lib.cast import CastService, setup_console_logging
    setup_console_logging()
    svc = CastService({"Oppo_IP": "127.0.0.1", "smb_user": "", "smb_password": "",
                       "DebugLevel": 0, "timeout_oppo_conection": 1})
    ok = True
    try:
        svc.start()
        mnt = svc.cfg.mount
        for _ in range(40):
            if os.path.ismount(mnt):
                break
            time.sleep(0.25)
        assert os.path.ismount(mnt), "rclone never mounted"

        t0 = time.time()
        r = http_json("http://127.0.0.1:18089/cast", {
            "url": f"{base}/redir/{fname}", "title": "Teste Ação: Parte 1",
            "headers": ["X-Auth: secret", "Range: bytes=0-1"],
            # subtitle URLs are fetched WITHOUT the stream's headers (addon links don't need them)
            "subtitles": [{"url": f"{base}/subs/_eng.srt", "lang": "eng"},
                          {"data": VTT, "lang": "por", "name": "PT"}],
            "dry_run": True})
        assert r.get("ok"), r
        while True:
            st = http_json("http://127.0.0.1:18089/status")
            if st["stage"] in ("ready", "error"):
                break
            time.sleep(0.2)
        print("status:", json.dumps(st, indent=1))
        assert st["stage"] == "ready", st.get("error")
        print(f"cast ready in {time.time() - t0:.2f}s")

        d = os.path.join(mnt, st["cast_id"])
        listing = sorted(os.listdir(d))
        print("mounted folder:", listing)
        video = os.path.join(d, st["file"])
        assert st["file"] == "Teste Acao Parte 1." + os.path.splitext(fname)[1][1:], st["file"]
        assert os.path.getsize(video) == os.path.getsize(src)

        # random seeks first (cold cache), then a full sequential read
        size = os.path.getsize(src)
        rnd = random.Random(7)
        with open(src, "rb") as a, open(video, "rb") as b:
            for off in [size - 4096, 0, size // 2] + [rnd.randrange(0, size - 65536) for _ in range(12)]:
                a.seek(off)
                b.seek(off)
                n = rnd.randrange(1, 65536)
                assert a.read(n) == b.read(n), f"mismatch at offset {off}"
        print("random seeks: OK (15 reads)")
        t1 = time.time()
        assert sha256(src) == sha256(video), "full read differs"
        print(f"full read sha256: OK ({size / 1e6:.1f} MB in {time.time() - t1:.2f}s)")

        subs = {n: open(os.path.join(d, n), "rb").read().decode("utf-8") for n in listing if n.endswith(".srt")}
        main_srt = subs.get("Teste Acao Parte 1.srt", "")
        assert "Legenda em <i>português</i>: ação!" in main_srt and "00:00:01,000 --> 00:00:04,000" in main_srt, main_srt
        print("primary subtitle (por, from inline VTT): OK")
        eng = subs.get("Teste Acao Parte 1.eng.srt")
        print("secondary subtitle (eng, by URL):", "OK" if eng and "café" in eng else f"MISSING {list(subs)}")
        ok = ok and bool(eng and "café" in eng)

        if shutil.which("ffprobe"):
            out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name",
                                  "-of", "csv=p=0", video], capture_output=True, text=True, timeout=60).stdout
            print("ffprobe via mount:", out.strip().replace("\n", " | "))
        redirects = sum(1 for p, _ in H.hits if p.startswith("/redir/"))
        print(f"upstream hits: {len(H.hits)} ({redirects} via redirect - the rest reused the resolved URL)")
    except AssertionError as e:
        ok = False
        print("FAIL:", e)
    finally:
        svc.stop()
        up.shutdown()
        os.remove(os.path.join(media, "_eng.srt"))
        shutil.rmtree(work, ignore_errors=True)
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
