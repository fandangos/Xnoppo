"""
Xnoppo cast: play any HTTP(S) video stream on the OPPO, with external subtitles.

Why this exists
---------------
The OPPO UDP-20x is a DLNA renderer, but as a renderer it ignores external
subtitles completely (tested 2026-11-01: sec:CaptionInfoEx, sec:CaptionInfo,
pv:subtitleFileUri and text/srt <res> entries are all ignored - the player never
even fetches the .srt) and it can't show MP4 mov_text tracks. Played from an SMB
share, however, it loads "<name>.srt" sitting next to "<name>.mkv".

So a cast is turned into a tiny SMB folder:

  app (Nuvio "open with") --POST /cast--> CastService
      probe upstream (size, container), fetch subtitles, publish in the Catalog:
          <id>/<Title>.<ext>   (proxied to the upstream URL, never downloaded)
          <id>/<Title>.srt     (held in memory)
  rclone mount :http: (reads our 127.0.0.1 /files/ listing)  ->  CAST_MOUNT
  host Samba shares CAST_MOUNT as  \\\\CAST_SMB_SERVER\\CAST_SMB_SHARE
  OPPO mounts <share>/<id> and plays /mnt/cifs1/<Title>.<ext>   (lib/Xnoppo.py)

  each OPPO read:  smbd -> FUSE (rclone, disk cache in CAST_CACHE_DIR)
                   -> GET /files/<id>/<name> + Range  ->  FileServer
                   -> upstream GET + Range (with the caller's headers)

HTTP API (CAST_PORT, default 8089):
  POST /cast     {"url", "title"?, "headers"? (dict or ["K: V"]), "subtitles"?:
                  [{"url"|"data", "lang"?, "name"?}], "position_ms"?, "dry_run"?}
  GET  /status   current cast state (+ live OPPO position while playing)
  POST /control  {"action": play|pause|stop|next|prev|ffwd|rew|power_off|
                  seek (+"position" seconds)|subtitle (+"index")|audio (+"index")|
                  key (+"key" raw OPPO code)}
  GET  /health
"""

import gzip
import hashlib
import html
import json
import logging
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import threading
import time
import unicodedata
import urllib.parse
from collections import OrderedDict
from dataclasses import dataclass, field
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

from . import Xnoppo as X

log = logging.getLogger("xnoppo.cast")

UA = "Xnoppo-cast/1.0"
CHUNK = 256 * 1024
# Upstreams that ignore Range force us to read-and-discard up to the wanted
# offset. Fine for the header/probe reads; refuse anything bigger (a seek).
MAX_SKIP = 64 * 1024 * 1024

MIME = {
    "mkv": "video/x-matroska", "mp4": "video/mp4", "m4v": "video/mp4",
    "mov": "video/quicktime", "ts": "video/mp2t", "m2ts": "video/mp2t",
    "avi": "video/x-msvideo", "mpg": "video/mpeg", "webm": "video/webm",
    "srt": "application/x-subrip", "ass": "text/x-ssa", "ssa": "text/x-ssa",
}
VIDEO_EXTS = {"mkv", "mp4", "m4v", "mov", "ts", "m2ts", "avi", "mpg", "webm", "iso"}


class CastError(Exception):
    """A cast step failed with a message meant for the user."""


# ─── config ──────────────────────────────────────────────────────────────────

def _env(name, default=""):
    return os.environ.get(name, default).strip()


def _env_bool(name, default):
    v = _env(name)
    if not v:
        return default
    return v.lower() in ("1", "true", "yes", "on")


def detect_lan_ip(towards):
    """The local address the kernel would use to reach `towards` (the OPPO)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect((towards, 9))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return ""


class CastConfig:
    def __init__(self, xconfig):
        self.x = xconfig  # the Xnoppo config dict: Oppo_IP, timeouts, smb_*, ...
        self.enabled = _env_bool("CAST_ENABLE", True)
        self.bind = _env("CAST_BIND", "0.0.0.0")
        self.port = int(_env("CAST_PORT", "8089"))
        self.files_port = int(_env("CAST_FILES_PORT", "8090"))
        self.smb_server = _env("CAST_SMB_SERVER") or detect_lan_ip(xconfig["Oppo_IP"])
        self.smb_share = _env("CAST_SMB_SHARE", "xnoppo_cast").strip("/\\")
        self.smb_user = _env("CAST_SMB_USER") or xconfig.get("smb_user", "")
        self.smb_password = _env("CAST_SMB_PASSWORD") or xconfig.get("smb_password", "")
        # rclone mounts in a SUBFOLDER of the /cast volume: if the container is killed,
        # the dead FUSE mount left on the host sits below the bind source instead of on
        # it, so Docker can still start the container and _unmount_stale() clears it.
        self.mount = _env("CAST_MOUNT", "/cast/media")
        self.manage_rclone = _env_bool("CAST_RCLONE", True)
        self.cache_dir = _env("CAST_CACHE_DIR", "/cache")
        self.cache_max = _env("CAST_CACHE_MAX", "150G")
        self.cache_age = _env("CAST_CACHE_AGE", "6h")
        self.read_ahead = _env("CAST_READ_AHEAD", "512M")
        self.rclone_extra = shlex.split(_env("CAST_RCLONE_ARGS"))
        self.rc_addr = _env("CAST_RCLONE_RC", "127.0.0.1:5572")
        self.uid = _env("CAST_UID", "99")
        self.gid = _env("CAST_GID", "100")
        self.sub_langs = [l.lower() for l in re.split(r"[,\s]+", _env("CAST_SUB_LANGS")) if l]
        self.sub_max = int(_env("CAST_SUB_MAX", "3"))
        self.sub_encoding = _env("CAST_SUB_ENCODING", "utf-8")
        self.token = _env("CAST_TOKEN")
        self.keep = int(_env("CAST_KEEP", "3"))

    def summary(self):
        return (f"api={self.bind}:{self.port} files=127.0.0.1:{self.files_port} "
                f"smb=\\\\{self.smb_server}\\{self.smb_share} "
                f"auth={'user ' + self.smb_user if self.smb_user else 'guest'} "
                f"mount={self.mount} rclone={'managed' if self.manage_rclone else 'external'} "
                f"cache={self.cache_dir} max={self.cache_max} age={self.cache_age} "
                f"sub_langs={','.join(self.sub_langs) or '(caller order)'}")


# ─── helpers: names, sniffing, headers, subtitles ────────────────────────────

def safe_name(s, maxlen=80):
    """ASCII, filesystem/JSON-safe. Xnoppo builds the OPPO's JSON by string
    concatenation and the OPPO's own path handling is old, so be conservative."""
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode()
    s = s.replace("'", "").replace("`", "")
    s = re.sub(r"[^A-Za-z0-9 ._(),\-]+", " ", s)
    s = re.sub(r"\s+", " ", s).strip(" .-")
    if len(s) > maxlen:
        s = s[:maxlen].rstrip(" .-")
    return s or "Xnoppo cast"


def sniff_ext(head, filename=""):
    """Container from magic bytes, falling back to the upstream file name."""
    if head[:4] == b"\x1a\x45\xdf\xa3":
        return "mkv"  # Matroska (WebM too - the OPPO keys off the extension)
    if head[4:8] == b"ftyp":
        return "mov" if head[8:12] == b"qt  " else "mp4"
    if len(head) > 376 and head[0] == 0x47 and head[188] == 0x47:
        return "ts"
    if len(head) > 392 and head[4] == 0x47 and head[196] == 0x47:
        return "m2ts"
    if head[:4] == b"RIFF" and head[8:12] == b"AVI ":
        return "avi"
    if head[:4] == b"\x00\x00\x01\xba":
        return "mpg"
    ext = os.path.splitext(filename or "")[1].lower().lstrip(".")
    return ext if ext in VIDEO_EXTS else "mkv"


def disposition_filename(value):
    if not value:
        return ""
    m = re.search(r"filename\*\s*=\s*[^']*'[^']*'([^;]+)", value)
    if m:
        return urllib.parse.unquote(m.group(1).strip().strip('"'))
    m = re.search(r'filename\s*=\s*"([^"]+)"', value) or re.search(r"filename\s*=\s*([^;]+)", value)
    return m.group(1).strip() if m else ""


def normalize_headers(h):
    """Accept {"K": "V"} or Nuvio/MX-style ["K: V", ...]."""
    out = {}
    if isinstance(h, dict):
        out = {str(k): str(v) for k, v in h.items()}
    elif isinstance(h, list):
        for item in h:
            if isinstance(item, str) and ":" in item:
                k, v = item.split(":", 1)
                out[k.strip()] = v.strip()
    # these are per-request, never forwarded from the caller
    for k in list(out):
        if k.lower() in ("range", "host", "content-length", "accept-encoding", "connection"):
            del out[k]
    return out


def parse_range(value, size):
    """'bytes=a-b' -> (start, end) inclusive; None = whole file; ValueError = 416."""
    if not value or not value.startswith("bytes="):
        return None
    spec = value[6:].split(",")[0].strip()
    a, _, b = spec.partition("-")
    if a == "":
        n = int(b)
        if n <= 0:
            raise ValueError("empty suffix range")
        start, end = max(0, size - n), size - 1
    else:
        start = int(a)
        end = min(int(b), size - 1) if b else size - 1
    if start >= size or start > end:
        raise ValueError("unsatisfiable range")
    return start, end


def decode_text(raw):
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    for enc in ("utf-8-sig", "cp1252"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1")


_TS = re.compile(r"(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})")


def _srt_ts(t):
    m = _TS.fullmatch(t.strip())
    if not m:
        raise ValueError(f"bad timestamp {t!r}")
    h, mnt, s, ms = m.groups()
    return f"{int(h or 0):02d}:{int(mnt):02d}:{int(s):02d},{(ms + '00')[:3]}"


def vtt_to_srt(text):
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    out = []
    for block in re.split(r"\n[ \t]*\n", text):
        lines = [l for l in block.split("\n") if l.strip() != ""]
        idx = next((i for i, l in enumerate(lines) if "-->" in l), None)
        if idx is None:
            continue  # WEBVTT header, NOTE, STYLE, REGION
        start, _, rest = lines[idx].partition("-->")
        try:
            t0, t1 = _srt_ts(start), _srt_ts(rest.split()[0])
        except (ValueError, IndexError):
            continue
        body = []
        for l in lines[idx + 1:]:
            l = re.sub(r"<\d{2}:\d{2}[:.\d]*>", "", l)          # karaoke timestamps
            l = re.sub(r"</?(?:c|v|lang|ruby|rt)(?:[.\s][^>]*)?>", "", l)  # voice/class tags
            body.append(l)
        out.append(f"{len(out) + 1}\n{t0} --> {t1}\n" + "\n".join(body))
    return "\n\n".join(out) + "\n"


def to_subtitle(raw):
    """bytes (srt/vtt/ass, maybe gzipped, any common encoding) -> (ext, text)."""
    text = decode_text(raw).lstrip("﻿")
    stripped = text.lstrip()
    if stripped.startswith("WEBVTT"):
        text, ext = vtt_to_srt(text), "srt"
    elif stripped.startswith("[Script Info]"):
        ext = "ass"
    else:
        ext = "srt"
    text = text.replace("\r\n", "\n").replace("\r", "\n").strip("\n") + "\n"
    if ext == "srt" and "-->" not in text:
        raise ValueError("not a subtitle file")
    return ext, text.replace("\n", "\r\n")


# ─── container index location (so prewarm reads what the player parses first) ─

def _read_at(f, off, n):
    f.seek(off)
    return f.read(n)


def mp4_index_range(f, size):
    """(start, end) of the 'moov' box: walk top-level boxes by their headers only."""
    off = 0
    for _ in range(64):
        if off + 8 > size:
            return None
        h = _read_at(f, off, 16)
        if len(h) < 8:
            return None
        bsize, btype, hdr = int.from_bytes(h[0:4], "big"), h[4:8], 8
        if bsize == 1:
            if len(h) < 16:
                return None
            bsize, hdr = int.from_bytes(h[8:16], "big"), 16
        elif bsize == 0:
            bsize = size - off
        if bsize < hdr:
            return None
        if btype == b"moov":
            return off, min(size, off + bsize)
        off += bsize
    return None


def _vint(b, i, keep_marker=False):
    """EBML variable-size integer at b[i] -> (value, length)."""
    first, mask, length = b[i], 0x80, 1
    while length <= 8 and not first & mask:
        mask >>= 1
        length += 1
    if length > 8:
        raise ValueError("bad EBML vint")
    val = first if keep_marker else first & (mask - 1)
    for k in range(1, length):
        val = (val << 8) | b[i + k]
    return val, length


def mkv_index_range(f, size):
    """(start, end) of the Cues element, found through the SeekHead in the first 256 KB."""
    head = _read_at(f, 0, 256 * 1024)
    try:
        i = 0
        _, l = _vint(head, i, True); i += l           # EBML header element
        esz, l = _vint(head, i); i += l + esz
        sid, l = _vint(head, i, True); i += l          # Segment
        if sid != 0x18538067:
            return None
        _, l = _vint(head, i); i += l
        seg_data = j = i
        while j < len(head) - 16:
            cid, l1 = _vint(head, j, True)
            csz, l2 = _vint(head, j + l1)
            body = j + l1 + l2
            if cid == 0x1F43B675:                       # Cluster: past the metadata
                return None
            if cid == 0x114D9B74:                       # SeekHead
                k = body
                while k < min(body + csz, len(head) - 8):
                    eid, a = _vint(head, k, True)
                    esz2, b2 = _vint(head, k + a)
                    m, target, pos = k + a + b2, None, None
                    while eid == 0x4DBB and m < k + a + b2 + esz2:   # Seek
                        xid, c = _vint(head, m, True)
                        xsz, d = _vint(head, m + c)
                        val = head[m + c + d:m + c + d + xsz]
                        if xid == 0x53AB:
                            target = int.from_bytes(val, "big")
                        elif xid == 0x53AC:
                            pos = int.from_bytes(val, "big")
                        m += c + d + xsz
                    if target == 0x1C53BB6B and pos is not None:      # Cues
                        off = seg_data + pos
                        h = _read_at(f, off, 16)
                        cid2, a2 = _vint(h, 0, True)
                        csz2, b3 = _vint(h, a2)
                        if cid2 != 0x1C53BB6B:
                            return None
                        return off, min(size, off + a2 + b3 + csz2)
                    k += a + b2 + esz2
                return None
            j = body + csz
    except (ValueError, IndexError):
        return None
    return None


# ─── catalog: what the /files/ tree contains ────────────────────────────────

@dataclass
class Entry:
    name: str
    size: int
    mime: str
    mtime: float = field(default_factory=time.time)
    url: str = None            # remote entry: proxied to this upstream
    headers: dict = field(default_factory=dict)
    data: bytes = None         # blob entry: served from memory
    resolved: str = None       # last redirect target of url (reused for ranges)


class Catalog:
    """cast id -> {file name -> Entry}; keeps the newest `keep` casts."""

    def __init__(self, keep):
        self.keep = max(1, keep)
        self._dirs = OrderedDict()
        self._lock = threading.Lock()

    def publish(self, cid, entries):
        with self._lock:
            self._dirs.pop(cid, None)
            self._dirs[cid] = {e.name: e for e in entries}
            while len(self._dirs) > self.keep:
                self._dirs.popitem(last=False)

    def ids(self):
        with self._lock:
            return list(self._dirs)

    def files(self, cid):
        with self._lock:
            d = self._dirs.get(cid)
            return None if d is None else list(d.values())

    def get(self, cid, name):
        with self._lock:
            return self._dirs.get(cid, {}).get(name)


# ─── upstream access ─────────────────────────────────────────────────────────

class Upstream:
    def __init__(self):
        self.s = requests.Session()
        adapter = requests.adapters.HTTPAdapter(pool_connections=8, pool_maxsize=32)
        self.s.mount("http://", adapter)
        self.s.mount("https://", adapter)

    def _headers(self, extra, rng):
        h = {"User-Agent": UA}
        h.update(extra or {})
        h["Accept-Encoding"] = "identity"
        if rng:
            h["Range"] = rng
        return h

    def probe(self, url, headers):
        """Size, container and file name, from one small ranged GET."""
        try:
            r = self.s.get(url, headers=self._headers(headers, "bytes=0-65535"),
                           stream=True, timeout=(10, 30))
        except requests.RequestException as e:
            raise CastError(f"can't reach the stream: {e}") from e
        try:
            if r.status_code not in (200, 206):
                raise CastError(f"stream answered HTTP {r.status_code} (expired link?)")
            head = b""
            for chunk in r.iter_content(16384):
                head += chunk
                if len(head) >= 65536:
                    break
            size, ranges = 0, False
            if r.status_code == 206:
                m = re.search(r"/(\d+)\s*$", r.headers.get("Content-Range", ""))
                size, ranges = (int(m.group(1)), True) if m else (0, False)
            elif r.headers.get("Content-Length", "").isdigit():
                size = int(r.headers["Content-Length"])
            ctype = r.headers.get("Content-Type", "").lower()
            fname = (disposition_filename(r.headers.get("Content-Disposition"))
                     or urllib.parse.unquote(urllib.parse.urlsplit(r.url).path.rsplit("/", 1)[-1]))
            final = r.url
        finally:
            r.close()
        if head.lstrip()[:7] == b"#EXTM3U":
            raise CastError("HLS playlists (.m3u8) can't be played as a file")
        if "text/html" in ctype or head.lstrip()[:1] == b"<":
            raise CastError("the link returned a web page, not a video (expired or login needed?)")
        if size <= 0:
            raise CastError("the stream doesn't report its size, so it can't be shown as a file")
        if not ranges:
            log.warning("upstream ignores Range requests - seeking will not work")
        ext = sniff_ext(head, fname)
        return {"size": size, "ext": ext, "mime": MIME.get(ext, "application/octet-stream"),
                "filename": fname, "ranges": ranges, "final_url": final}

    def open(self, e, start, end):
        """-> (response, bytes_to_discard). Tries the cached redirect target first."""
        urls = [e.resolved, e.url] if e.resolved and e.resolved != e.url else [e.url]
        last = "no attempt"
        for url in urls:
            try:
                r = self.s.get(url, headers=self._headers(e.headers, f"bytes={start}-{end}"),
                               stream=True, timeout=(10, 60))
            except requests.RequestException as ex:
                last = str(ex)
                continue
            if r.status_code == 206:
                e.resolved = r.url
                return r, 0
            if r.status_code == 200:
                if start > MAX_SKIP:
                    r.close()
                    raise CastError("upstream ignores Range; can't seek this far")
                e.resolved = r.url
                return r, start
            last = f"HTTP {r.status_code}"
            r.close()
        raise CastError(f"upstream read failed: {last}")

    def fetch_small(self, url, headers=None, limit=8 * 1024 * 1024):
        r = self.s.get(url, headers=self._headers(headers, None), timeout=(10, 30), stream=True)
        try:
            r.raise_for_status()
            data = b""
            for chunk in r.iter_content(65536):
                data += chunk
                if len(data) > limit:
                    raise CastError("subtitle file too large")
            return data
        finally:
            r.close()


# ─── /files/ server (127.0.0.1 only; rclone's :http: remote reads it) ───────

class _FileHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "XnoppoCast"

    def log_message(self, fmt, *args):
        log.debug("files %s - " + fmt, self.client_address[0], *args)

    def do_HEAD(self):
        self._handle(body=False)

    def do_GET(self):
        self._handle(body=True)

    def _plain(self, code, msg=""):
        data = msg.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _listing(self, names, body):
        links = "".join(f'<a href="{urllib.parse.quote(n)}">{html.escape(n)}</a>\n' for n in names)
        data = f"<!DOCTYPE html><html><body>\n{links}</body></html>\n".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if body:
            self.wfile.write(data)

    def _handle(self, body):
        cat = self.server.catalog
        path = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)
        if not path.startswith("/files/"):
            return self._plain(404, "not found")
        parts = [p for p in path[len("/files/"):].split("/") if p]
        if not parts:
            return self._listing([cid + "/" for cid in cat.ids()], body)
        if len(parts) == 1:
            files = cat.files(parts[0])
            if files is None:
                return self._plain(404, "no such cast")
            return self._listing([e.name for e in files], body)
        if len(parts) != 2:
            return self._plain(404, "not found")
        e = cat.get(parts[0], parts[1])
        if e is None:
            return self._plain(404, "no such file")
        try:
            rng = parse_range(self.headers.get("Range"), e.size)
        except ValueError:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{e.size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        start, end = rng if rng else (0, e.size - 1)
        upstream = discard = None
        if body and e.data is None:
            try:
                upstream, discard = self.server.upstream.open(e, start, end)
            except CastError as ex:
                log.warning("read %s/%s [%d-%d]: %s", parts[0], e.name, start, end, ex)
                return self._plain(502, str(ex))
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", e.mime)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Last-Modified", formatdate(e.mtime, usegmt=True))
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{e.size}")
        self.end_headers()
        if not body:
            return
        if e.data is not None:
            self.wfile.write(e.data[start:end + 1])
            return
        self._pump(upstream, discard, end - start + 1, e.name)

    def _pump(self, r, discard, remaining, name):
        try:
            for chunk in r.iter_content(CHUNK):
                if discard:
                    if len(chunk) <= discard:
                        discard -= len(chunk)
                        continue
                    chunk, discard = chunk[discard:], 0
                if len(chunk) > remaining:
                    chunk = chunk[:remaining]
                self.wfile.write(chunk)
                remaining -= len(chunk)
                if remaining <= 0:
                    break
        except (BrokenPipeError, ConnectionResetError):
            remaining = 0  # reader went away (seek / close): normal
            self.close_connection = True
        except (requests.RequestException, OSError) as ex:
            log.warning("upstream stream for %s broke: %s", name, ex)
        finally:
            r.close()
        if remaining > 0:
            log.warning("short upstream read for %s (%d bytes missing)", name, remaining)
            self.close_connection = True


class _Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request, client_address):
        # rclone drops idle keep-alive connections and aborts reads on seek;
        # socketserver would print a full traceback for each of those.
        if isinstance(sys.exc_info()[1], (ConnectionResetError, BrokenPipeError, TimeoutError)):
            return
        log.exception("unhandled error serving %s", client_address)


# ─── rclone mount supervisor ─────────────────────────────────────────────────

class RcloneMount:
    def __init__(self, cfg):
        self.cfg = cfg
        self.proc = None
        self.stopping = False

    def available(self):
        if not shutil.which("rclone"):
            return "rclone binary not found"
        if not os.path.exists("/dev/fuse"):
            return "/dev/fuse missing (add --device /dev/fuse --cap-add SYS_ADMIN)"
        return None

    def cmd(self):
        c = self.cfg
        cmd = ["rclone", "mount", ":http:", c.mount,
               "--http-url", f"http://127.0.0.1:{c.files_port}/files/",
               "--read-only",
               # only matters if CAST_MOUNT is set to the bind mount itself (stacking on it);
               # safe because a dead rclone mount is removed first (_unmount_stale)
               "--allow-non-empty",
               "--dir-cache-time", "5s", "--attr-timeout", "5s",
               "--vfs-cache-mode", "full", "--cache-dir", c.cache_dir,
               "--vfs-cache-max-size", c.cache_max, "--vfs-cache-max-age", c.cache_age,
               "--vfs-read-ahead", c.read_ahead, "--buffer-size", "0",
               "--file-perms", "0444", "--dir-perms", "0555",
               "--rc", "--rc-addr", c.rc_addr, "--rc-no-auth",
               "--log-level", "NOTICE"]
        if os.geteuid() == 0:
            # smbd reads as the connecting user, so others must see the mount
            cmd += ["--allow-other", "--uid", c.uid, "--gid", c.gid]
        return cmd + c.rclone_extra

    def _top_fstype(self):
        """fstype of the top-most mount sitting exactly on CAST_MOUNT ('' if none)."""
        target = os.path.normpath(os.path.abspath(self.cfg.mount))  # no stat: may be a dead mount
        fstype = ""
        try:
            with open("/proc/self/mountinfo") as f:
                for line in f:
                    left, _, right = line.partition(" - ")
                    fields = left.split()
                    # mountinfo escapes spaces etc. as octal (\040)
                    mnt = fields[4].encode().decode("unicode_escape") if len(fields) > 4 else ""
                    if mnt == target:
                        fstype = right.split()[0] if right else ""  # later lines stack on top
        except OSError:
            pass
        return fstype

    def _unmount_stale(self):
        # Only ever remove OUR FUSE mount (a dead rclone left by a crash). As root,
        # fusermount -u unmounts anything - including the host bind mount that
        # /cast IS on Unraid, which silently cut the mount off from the host's Samba.
        if not self._top_fstype().startswith("fuse"):
            return
        for tool in ("fusermount3", "fusermount"):
            if shutil.which(tool):
                subprocess.run([tool, "-uz", self.cfg.mount], capture_output=True)
                return

    def _pipe(self, stream):
        for line in iter(stream.readline, b""):
            log.info("rclone: %s", line.decode(errors="replace").rstrip())

    def run(self):
        while not self.stopping:
            self._unmount_stale()
            try:
                os.makedirs(self.cfg.mount, exist_ok=True)
                os.makedirs(self.cfg.cache_dir, exist_ok=True)
            except OSError as e:
                log.error("can't prepare %s / %s: %s; retrying in 10s", self.cfg.mount, self.cfg.cache_dir, e)
                time.sleep(10)
                continue
            log.info("starting rclone mount at %s", self.cfg.mount)
            self.proc = subprocess.Popen(self.cmd(), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            threading.Thread(target=self._pipe, args=(self.proc.stdout,), daemon=True).start()
            rc = self.proc.wait()
            if self.stopping:
                break
            log.error("rclone mount exited (code %s); restarting in 5s", rc)
            time.sleep(5)

    def stop(self):
        self.stopping = True
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()  # rclone unmounts cleanly on SIGTERM
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self._unmount_stale()

    def refresh(self):
        try:
            requests.post(f"http://{self.cfg.rc_addr}/vfs/refresh", timeout=5)
        except requests.RequestException as e:
            log.debug("rclone rc refresh failed: %s", e)


# ─── OPPO :436 calls with timeouts ───────────────────────────────────────────
# lib/Xnoppo.py's helpers build the same URLs but some have no timeout at all,
# and the OPPO's SMB handlers can block indefinitely - a cast must never hang.

def oppo_call(x, endpoint, payload=None, timeout=10):
    """GET http://OPPO:436/<endpoint>?<json> -> dict ({"timeout": True} / {"error": ...})."""
    url = f"http://{x['Oppo_IP']}:436/{endpoint}"
    if payload is not None:
        url += "?" + urllib.parse.quote(json.dumps(payload, separators=(",", ":")), safe='{}:,"/')
    try:
        return _json(requests.get(url, timeout=timeout).text)
    except requests.Timeout:
        return {"success": False, "timeout": True, "retInfo": f"no answer in {timeout}s"}
    except requests.RequestException as e:
        return {"success": False, "error": str(e), "retInfo": str(e)}


def oppo_activeapp(x):
    return oppo_call(x, "getglobalinfo", timeout=5).get("activeapp", "?")


# ─── the service ─────────────────────────────────────────────────────────────

class CastService:
    def __init__(self, xconfig):
        self.cfg = CastConfig(xconfig)
        self.catalog = Catalog(self.cfg.keep)
        self.upstream = Upstream()
        self.rclone = RcloneMount(self.cfg)
        self.mount_problem = None
        self._lock = threading.Lock()
        self._oppo_lock = threading.Lock()   # one cast talks to the OPPO at a time
        self._gen = 0
        self.state = {"stage": "idle"}

    # -- lifecycle --
    def start(self):
        log.info("cast: %s", self.cfg.summary())
        fs = _Server(("127.0.0.1", self.cfg.files_port), _FileHandler)
        fs.catalog, fs.upstream = self.catalog, self.upstream
        threading.Thread(target=fs.serve_forever, name="cast-files", daemon=True).start()
        if self.cfg.manage_rclone:
            self.mount_problem = self.rclone.available()
            if self.mount_problem:
                log.error("cast disabled until fixed: %s", self.mount_problem)
            else:
                threading.Thread(target=self.rclone.run, name="cast-rclone", daemon=True).start()
        api = _Server((self.cfg.bind, self.cfg.port), _ApiHandler)
        api.svc = self
        threading.Thread(target=api.serve_forever, name="cast-api", daemon=True).start()
        log.info("cast API listening on %s:%d", self.cfg.bind, self.cfg.port)

    def stop(self):
        if self.cfg.manage_rclone:
            self.rclone.stop()

    # -- state --
    def _set(self, gen, **kv):
        with self._lock:
            if gen != self._gen:
                return False  # superseded by a newer cast
            self.state.update(kv, updated=time.time())
            return True

    def status(self):
        with self._lock:
            return {k: v for k, v in self.state.items() if k != "url"}

    # -- casting --
    def cast(self, req):
        url = (req.get("url") or "").strip()
        if not re.match(r"https?://", url, re.I):
            raise CastError("'url' must be an http(s) URL")
        if self.mount_problem:
            raise CastError(self.mount_problem)
        with self._lock:
            # The same stream twice within seconds (app recreated, double tap) must not
            # start a second play: a play command reaching the OPPO while it is still
            # setting up the first one crashed its MP4 handler (PbTask0).
            st = self.state
            if (st.get("url") == url and st.get("stage") not in ("error", "ended")
                    and time.time() - st.get("started", 0) < 60):
                log.info("duplicate cast request for the cast in progress (id %s) ignored", st.get("id"))
                return {"ok": True, "id": st.get("id"), "duplicate": True}
            self._gen += 1
            gen = self._gen
            self.state = {"stage": "preparing", "id": gen, "title": req.get("title") or "",
                          "started": time.time(), "updated": time.time(), "url": url}
        threading.Thread(target=self._run, args=(gen, req), name=f"cast-{gen}", daemon=True).start()
        return {"ok": True, "id": gen}

    def _run(self, gen, req):
        try:
            prep = self.prepare(req)
            if not self._set(gen, stage="publishing", cast_id=prep["cid"], file=prep["video"],
                             subtitles=prep["subs"], size=prep["size"], container=prep["ext"]):
                return
            self.wait_visible(prep)
            if req.get("dry_run"):
                self.prewarm(gen, prep)
                self._set(gen, stage="ready", note="dry run: files published, OPPO not touched")
                return
            if not self._oppo_lock.acquire(timeout=180):
                raise CastError("another cast is still talking to the OPPO")
            try:
                if not self._set(gen):
                    return   # superseded while waiting
                self.play(gen, prep, float(req.get("position_ms") or 0) / 1000.0)
            finally:
                self._oppo_lock.release()
            self.monitor(gen)
        except CastError as e:
            log.warning("cast %d failed: %s", gen, e)
            self._set(gen, stage="error", error=str(e))
        except Exception as e:  # keep the service alive whatever happens
            log.exception("cast %d crashed", gen)
            self._set(gen, stage="error", error=f"internal error: {e}")

    def prepare(self, req):
        url = req["url"].strip()
        headers = normalize_headers(req.get("headers"))
        info = self.upstream.probe(url, headers)
        base = safe_name(req.get("title") or os.path.splitext(info["filename"])[0])
        video = f"{base}.{info['ext']}"
        subs = self.fetch_subtitles(req.get("subtitles") or [], base)
        digest = hashlib.sha1(url.encode())
        for s in subs:
            digest.update(s.data)
        cid = digest.hexdigest()[:10]
        entries = [Entry(video, info["size"], info["mime"], url=url, headers=headers,
                         resolved=info["final_url"])] + subs
        self.catalog.publish(cid, entries)
        log.info("cast %s: %s (%s, %.2f GB, ranges=%s) + subs %s", cid, video, info["ext"],
                 info["size"] / 1e9, info["ranges"], [s.name for s in subs])
        return {"cid": cid, "video": video, "size": info["size"], "ext": info["ext"],
                "subs": [s.name for s in subs]}

    def fetch_subtitles(self, items, base):
        items = [i for i in items if isinstance(i, dict)]
        if self.cfg.sub_langs:
            def rank(i):
                lang = str(i.get("lang") or "").lower()
                return self.cfg.sub_langs.index(lang) if lang in self.cfg.sub_langs else len(self.cfg.sub_langs)
            items = sorted(items, key=rank)  # stable: keeps the caller's order within a rank
        out, used = [], set()
        for it in items:
            if len(out) >= self.cfg.sub_max:
                break
            src = it.get("url") or "(inline)"
            try:
                if it.get("data") is not None:
                    d = it["data"]
                    raw = d.encode("utf-8") if isinstance(d, str) else bytes(d)
                elif re.match(r"https?://", str(it.get("url") or ""), re.I):
                    raw = self.upstream.fetch_small(it["url"])
                else:
                    log.warning("subtitle skipped (needs http(s) url or inline data): %s", src)
                    continue
                ext, text = to_subtitle(raw)
            except (requests.RequestException, CastError, ValueError, OSError) as e:
                log.warning("subtitle skipped (%s): %s", e, src)
                continue
            lang = safe_name(str(it.get("lang") or ""), 12).replace(" ", "").lower()
            # the first one gets the bare name: that's the one the OPPO auto-loads
            name = f"{base}.{ext}" if not out else f"{base}.{lang or 'sub'}.{ext}"
            n = 2
            while name in used:
                name = f"{base}.{lang or 'sub'}{n}.{ext}"
                n += 1
            used.add(name)
            data = text.encode(self.cfg.sub_encoding, errors="replace")
            out.append(Entry(name, len(data), MIME[ext], data=data))
        return out

    def wait_visible(self, prep, timeout=20):
        """Make sure the FUSE view shows the new folder before the OPPO looks."""
        if not self.cfg.mount or not os.path.isdir(self.cfg.mount):
            log.warning("mount %s not present - skipping visibility check", self.cfg.mount)
            return
        if self.cfg.manage_rclone:
            self.rclone.refresh()
        target = os.path.join(self.cfg.mount, prep["cid"], prep["video"])
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                if os.stat(target).st_size == prep["size"]:
                    return
            except OSError:
                pass
            time.sleep(0.5)
        raise CastError(f"file never appeared in the mount ({target}) - is rclone running?")

    # -- OPPO side (reuses lib/Xnoppo.py) --
    def _oppo_ready(self):
        x = self.cfg.x
        X.sendnotifyremote(x["Oppo_IP"])
        if X.check_socket(x) != 0:
            raise CastError("OPPO not reachable (powered off without network standby?)")
        # The OPPO pushes status to <appIpAddress>:4360 every few seconds; lib/Xnoppo.py's
        # OppoSignin hard-codes 192.168.1.135, so give it our real LAN address instead.
        me = detect_lan_ip(x["Oppo_IP"]) or "127.0.0.1"
        oppo_call(x, "signin", {"appIconType": 1, "appIpAddress": me}, timeout=8)

    def _ensure_discovered(self, gen):
        """The OPPO only logs in to SMB servers its media center has DISCOVERED, and the
        network search only runs when /getdevicelist is called (the original Xnoppo
        test-mount looped on it; Emby's flow calls it 3x, which is why its 2nd press
        works). The OPPO's screensaver also pauses the media center. So: wake it, then
        poll getdevicelist until our server is listed as a "cifs" device.
        Seen on the console: login with an empty list fails in <1ms (login cifs
        failed:-2, then mount "ID error"); after discovery: "CIFS auth success!"."""
        x, c = self.cfg.x, self.cfg
        if oppo_activeapp(x) == "scrn_svr":
            log.info("OPPO screensaver is up (pauses the media center): pressing Return")
            oppo_call(x, "sendremotekey", {"key": "RET"}, timeout=5)
            time.sleep(1.5)
        want = c.smb_server.upper()
        is_ip = bool(re.fullmatch(r"[\d.]+", c.smb_server))
        deadline = time.time() + int(_env("CAST_DISCOVERY_TIMEOUT", "25"))
        seen = []
        while time.time() < deadline:
            d = oppo_call(x, "getdevicelist", timeout=8)
            seen = [str(e.get("name", "")) for e in d.get("devicelist") or [] if e.get("sub_type") == "cifs"]
            if want in (n.upper() for n in seen) or (is_ip and seen):
                log.info("OPPO sees SMB servers %s", seen)
                return True
            if not self._set(gen):
                return None
            time.sleep(2)
        log.warning("OPPO network search never listed %s (saw %s); trying anyway", c.smb_server, seen)
        return False

    def _mount(self, gen, accept_existing=True):
        """Mount the share ROOT on the OPPO (each cast lives in <share>/<id>/).
        -> "mounted" | "existing" | None (superseded by a newer cast).

        The OPPO's first SMB connection to a server is slow: the :436 call answers
        "failed" or times out while the session is still being set up, and a later
        attempt then succeeds at once - the "press play twice" every Xnoppo/Emby user
        knows. So retry instead of failing. Mounting the root (not <id>) lets later
        casts reuse the live mount. The OPPO answers "id_error" when the share is
        ALREADY mounted: that's "existing" - just play from it."""
        x, c = self.cfg.x, self.cfg
        xq = dict(x, timeout_oppo_mount=int(_env("CAST_MOUNT_TIMEOUT", "20")))
        attempts = int(_env("CAST_MOUNT_ATTEMPTS", "5"))
        last = ""
        for attempt in range(1, attempts + 1):
            if attempt > 1:
                if not self._set(gen, stage=f"mounting (attempt {attempt}/{attempts})"):
                    return None
                time.sleep(3)
            if attempt == 1 or last in ("failed", "id_error") or "timeout" in last:
                if self._ensure_discovered(gen) is None:
                    return None
            login_timeout = int(_env("CAST_LOGIN_TIMEOUT", "25"))
            if c.smb_user and _env("CAST_SMB_LOGIN", "id").lower() == "id":
                # firmware JSON keys (bdpprog strings): serverName/userName/"psssword" (sic)
                lg = oppo_call(x, "loginSambaWithID", {"serverName": c.smb_server, "userName": c.smb_user,
                                                       "psssword": c.smb_password, "bRememberID": 1},
                               timeout=login_timeout)
            else:
                lg = oppo_call(x, "loginSambaWithOutID", {"serverName": c.smb_server}, timeout=login_timeout)
            log.info("OPPO login (attempt %d, activeapp=%s): %s", attempt, oppo_activeapp(x),
                     {k: lg.get(k) for k in ("success", "retInfo", "timeout") if k in lg})
            folder_payload = {"server": c.smb_server, "bWithID": 1 if c.smb_user else 0,
                              "folder": c.smb_share, "userName": c.smb_user or "",
                              "password": c.smb_password or "", "bRememberID": 1 if c.smb_user else 0}
            m = oppo_call(x, "mountSharedFolder", folder_payload, timeout=xq["timeout_oppo_mount"])
            resp = json.dumps(m)
            if m.get("success"):
                log.info("OPPO mounted \\\\%s\\%s (attempt %d)", c.smb_server, c.smb_share, attempt)
                return "mounted"
            last = m.get("retInfo") or m.get("msg") or str(resp)[:200]
            # a failed login also makes the mount answer "ID error" - only a mount refused
            # AFTER a successful login means "already mounted"
            if last == "id_error" and accept_existing and lg.get("success"):
                log.info("OPPO: id_error = share already mounted; playing from the existing mount")
                return "existing"
            log.info("OPPO mount attempt %d/%d failed: %s", attempt, attempts, last)
        raise CastError(f"OPPO couldn't mount \\\\{c.smb_server}\\{c.smb_share} "
                        f"after {attempts} attempts: {last}")

    def _play_and_wait(self, gen, path):
        """-> True playing | False didn't start | None superseded."""
        x, c = self.cfg.x, self.cfg
        p = oppo_call(x, "playnormalfile", {"path": f"/mnt/cifs1/{path}", "index": 0, "type": 1,
                                            "appDeviceType": 2, "extraNetPath": c.smb_server,
                                            "playMode": 0}, timeout=x.get("timeout_oppo_playitem", 60))
        if not p.get("success"):
            log.info("playnormalfile %s refused: %s", path, p)
            return False
        deadline = time.time() + x.get("timeout_oppo_playitem", 60)
        while not oppo_call(x, "getglobalinfo", timeout=5).get("is_video_playing"):
            if time.time() > deadline:
                log.info("OPPO accepted %s but never started playing", path)
                return False
            if not self._set(gen):
                return None
            time.sleep(1)
        return True

    def prewarm(self, gen, prep):
        """Usenet (nzbdav) and debrid pay seconds for every region nobody has read yet,
        and the OPPO parses the container index before anything else: MP4 'moov'
        (anywhere in the file; tens of MB for long 4K titles) or MKV 'Cues' (found via
        the SeekHead). A slow index read is also what lets the OPPO's MP4 handler crash
        (subtitle setup races the parse: "Invalid memory reference" in
        CLpMp4MediaManagerProxy::mrStateMachine, seen with a 7 GB usenet MP4).
        So read the head and the real index through our FUSE mount, in parallel, into
        rclone's cache; fall back to the last few MB when no index is found."""
        path = os.path.join(self.cfg.mount, prep["cid"], prep["video"])
        size = prep["size"]
        if not os.path.isfile(path) or not size:
            return
        head = min(size, int(_env("CAST_PREWARM_HEAD_MB", "8")) << 20)
        cap = int(_env("CAST_PREWARM_INDEX_MB", "96")) << 20
        index = None
        try:
            with open(path, "rb", buffering=0) as f:
                if prep["ext"] in ("mp4", "m4v", "mov"):
                    index = mp4_index_range(f, size)
                elif prep["ext"] == "mkv":
                    index = mkv_index_range(f, size)
        except OSError as e:
            log.warning("index lookup failed: %s", e)
        regions = [(0, head)]
        if index and index[1] > head:
            start, end = max(index[0], head), min(index[1], index[0] + cap)
            regions.append((start, end))
            log.info("%s index at %d-%d (%.1f MB)", prep["ext"], index[0], index[1], (index[1] - index[0]) / 1e6)
        elif not index and size > head:
            regions.append((max(head, size - (int(_env("CAST_PREWARM_TAIL_MB", "4")) << 20)), size))

        def read(start, end):
            try:
                with open(path, "rb", buffering=0) as f:
                    f.seek(start)
                    left = end - start
                    while left > 0:
                        b = f.read(min(1 << 20, left))
                        if not b:
                            break
                        left -= len(b)
            except OSError as e:
                log.warning("prewarm read %d-%d failed: %s", start, end, e)

        t0 = time.time()
        deadline = t0 + int(_env("CAST_PREWARM_TIMEOUT", "45"))
        threads = [threading.Thread(target=read, args=r, daemon=True) for r in regions]
        for t in threads:
            t.start()
        for t in threads:
            t.join(max(0.0, deadline - time.time()))
        done = not any(t.is_alive() for t in threads)
        log.info("prewarmed %s: %s in %.1fs%s", prep["video"],
                 ", ".join(f"{(b - a) / 1e6:.1f} MB@{a}" for a, b in regions), time.time() - t0,
                 "" if done else " (timed out, continuing)")

    def select_external_subtitle(self, prep):
        """The OPPO loads <name>.srt but leaves subtitles Off. Embedded tracks are listed
        with a language ("2/4 English"), external files without one ("1/1  "), so turn on
        the first nameless track. CAST_SUB_AUTO=off disables this."""
        if not prep["subs"] or _env("CAST_SUB_AUTO", "external").lower() == "off":
            return
        base = f"http://{self.cfg.x['Oppo_IP']}:436"
        for _ in range(5):  # the menu fills in a moment after playback starts
            time.sleep(1.5)
            try:
                lst = _json(requests.get(f"{base}/getsubtitlemenulist", timeout=5).text).get("subtitle_list") or []
            except requests.RequestException:
                continue
            tracks = [t for t in lst if t.get("index", 0) > 0]
            if not tracks:
                continue
            ext = [t for t in tracks if not re.sub(r"^\s*\d+/\d+", "", t.get("name", "")).strip()]
            log.info("OPPO subtitle menu: %s", [t.get("name") for t in lst])
            if not ext:
                log.info("no nameless (external) subtitle track found; leaving the choice to the user")
                return
            pick = ext[0]["index"]
            try:
                requests.get(f'{base}/setsubttmenulist?{{"cur_index":{int(pick)}}}', timeout=5)
                log.info("enabled external subtitle (menu index %s)", pick)
            except requests.RequestException as e:
                log.warning("couldn't enable subtitle: %s", e)
            return

    def play(self, gen, prep, position_s):
        x, c = self.cfg.x, self.cfg
        # warm the cache while the OPPO wakes up and mounts (both take seconds)
        warm = threading.Thread(target=self.prewarm, args=(gen, prep), daemon=True)
        warm.start()
        self._set(gen, stage="waking OPPO")
        self._oppo_ready()
        X.getglobalinfo(x)
        self._set(gen, stage="finding the server", share=f"\\\\{c.smb_server}\\{c.smb_share}\\{prep['cid']}")
        how = self._mount(gen)
        if how is None:
            return
        if warm.is_alive():
            self._set(gen, stage="buffering")
            warm.join(int(_env("CAST_PREWARM_TIMEOUT", "45")) + 5)
        self._set(gen, stage="starting playback")
        path = f"{prep['cid']}/{prep['video']}"   # relative to /mnt/cifs1 = share root
        ok = self._play_and_wait(gen, path)
        if ok is None:
            return
        if not ok:
            # The OPPO accepted the file but its playback never started. Seen when its
            # playback thread crashed (then Stop is refused - the "prohibited" icon - and
            # only a power cycle recovers), so don't send anything more, just report.
            raise CastError(f"the OPPO accepted {prep['video']} but never started playing it - if it stays stuck, power-cycle the OPPO")
        if position_s > 5:
            X.setplaytime(x, position_s * 10_000_000)
        self._set(gen, stage="playing", position=position_s)
        self.select_external_subtitle(prep)

    def monitor(self, gen):
        x = self.cfg.x
        misses = 0
        while True:
            time.sleep(2)
            try:
                g = X.getglobalinfo(x)
                if '"is_video_playing":true' in g:
                    misses = 0
                    t = _json(X.getplayingtime(x))
                    if not self._set(gen, stage="playing", position=t.get("cur_time"),
                                     duration=t.get("total_time")):
                        return
                    continue
            except requests.RequestException:
                pass  # :436 hiccup; count it as a miss
            misses += 1
            if misses >= 3:
                self._set(gen, stage="ended")
                if not x.get("Always_ON", True):
                    X.sendremotekey("POF", x)
                return
            if not self._set(gen):
                return

    # -- remote control --
    KEYS = {"play": "PLA", "pause": "PAU", "stop": "STP", "next": "NXT", "prev": "PRE",
            "ffwd": "FWD", "rew": "REV", "power_off": "POF"}

    def control(self, req):
        action = str(req.get("action") or "").lower()
        x = self.cfg.x
        self._oppo_ready()
        base = f"http://{x['Oppo_IP']}:436"
        if action in self.KEYS:
            return _json(X.sendremotekey(self.KEYS[action], x))
        if action == "key" and re.fullmatch(r"[A-Z0-9]{2,4}", str(req.get("key") or "")):
            return _json(X.sendremotekey(req["key"], x))
        if action == "seek":
            return _json(X.setplaytime(x, float(req["position"]) * 10_000_000))
        if action in ("subtitle", "audio"):
            ep = "setsubttmenulist" if action == "subtitle" else "setaudiomenulist"
            r = requests.get(f'{base}/{ep}?{{"cur_index":{int(req["index"])}}}', timeout=5)
            return _json(r.text)
        if action == "tracks":
            subs = requests.get(f"{base}/getsubtitlemenulist", timeout=5).text
            aud = requests.get(f"{base}/getaudiomenulist", timeout=5).text
            return {"subtitles": _json(subs).get("subtitle_list"), "audio": _json(aud).get("audio_list")}
        raise CastError(f"unknown action {action!r}")


def _json(text):
    try:
        return json.loads(text)
    except (TypeError, ValueError):
        return {"raw": str(text)[:300]}


# ─── public API server ───────────────────────────────────────────────────────

class _ApiHandler(BaseHTTPRequestHandler):
    server_version = "XnoppoCast"

    def log_message(self, fmt, *args):
        log.debug("api %s - " + fmt, self.client_address[0], *args)

    def _send(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        token = self.server.svc.cfg.token
        if not token:
            return True
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        return self.headers.get("X-Xnoppo-Token") == token or q.get("token", [""])[0] == token

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/health":
            svc = self.server.svc
            return self._send(200, {"ok": svc.mount_problem is None, "problem": svc.mount_problem})
        if not self._authorized():
            return self._send(401, {"ok": False, "error": "bad token"})
        if path in ("/", "/status"):
            return self._send(200, self.server.svc.status())
        self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if not self._authorized():
            return self._send(401, {"ok": False, "error": "bad token"})
        path = urllib.parse.urlsplit(self.path).path
        try:
            n = int(self.headers.get("Content-Length") or 0)
            if n > 4 * 1024 * 1024:
                return self._send(413, {"ok": False, "error": "body too large"})
            req = json.loads(self.rfile.read(n) or b"{}")
            if not isinstance(req, dict):
                raise ValueError("expected a JSON object")
        except ValueError as e:
            return self._send(400, {"ok": False, "error": f"bad JSON: {e}"})
        svc = self.server.svc
        try:
            if path == "/cast":
                return self._send(202, svc.cast(req))
            if path == "/control":
                return self._send(200, {"ok": True, "result": svc.control(req)})
            if path == "/stop":
                return self._send(200, {"ok": True, "result": svc.control({"action": "stop"})})
        except CastError as e:
            return self._send(409, {"ok": False, "error": str(e)})
        except (requests.RequestException, KeyError, ValueError) as e:
            return self._send(502, {"ok": False, "error": f"{type(e).__name__}: {e}"})
        self._send(404, {"ok": False, "error": "not found"})


def setup_console_logging():
    """Xnoppo logs to a file; cast events also go to stdout (docker logs)."""
    if any(getattr(h, "_xnoppo_cast", False) for h in log.handlers):
        return
    h = logging.StreamHandler(sys.stdout)
    h.setFormatter(logging.Formatter("%(asctime)s cast %(levelname)s: %(message)s", "%H:%M:%S"))
    h._xnoppo_cast = True
    log.addHandler(h)
    log.setLevel(logging.DEBUG if os.environ.get("DEBUG", "1") == "2" else logging.INFO)
