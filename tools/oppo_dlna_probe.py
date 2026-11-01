#!/usr/bin/env python3
"""Push test media to the OPPO UDP-203 DLNA renderer and read back what tracks it sees.

Runs on odin: serves the test files over HTTP (with Range support + request log),
drives AVTransport over SOAP, reads subtitle/audio menus via the OPPO :436 API.
"""
import http.server, json, os, socket, sys, threading, time, urllib.request
from xml.sax.saxutils import escape

OPPO = "10.27.33.191"
DIR = os.path.dirname(os.path.abspath(__file__))
PORT = 8765
ME = [l for l in os.popen(f"ip -4 route get {OPPO}").read().split() if l.count(".") == 3][-1]
BASE = f"http://{ME}:{PORT}"
LOG = []
MIME = {".mp4": "video/mp4", ".mkv": "video/x-matroska", ".srt": "text/srt"}


class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def _serve(self, body):
        path = os.path.join(DIR, os.path.basename(self.path.split("?")[0]))
        rng = self.headers.get("Range")
        LOG.append((round(time.time() - T0, 1), self.command, self.path, rng, self.headers.get("User-Agent", "")))
        if not os.path.isfile(path):
            self.send_response(404); self.end_headers(); return
        size = os.path.getsize(path); start, end = 0, size - 1
        if rng and rng.startswith("bytes="):
            a, b = rng[6:].split(",")[0].split("-")
            start = int(a) if a else size - int(b)
            end = int(b) if (a and b) else size - 1
            self.send_response(206); self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        ext = os.path.splitext(path)[1]
        self.send_header("Content-Type", MIME.get(ext, "application/octet-stream"))
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("transferMode.dlna.org", "Streaming")
        self.send_header("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        self.end_headers()
        if not body: return
        with open(path, "rb") as f:
            f.seek(start); left = end - start + 1
            try:
                while left > 0:
                    chunk = f.read(min(1 << 20, left))
                    if not chunk: break
                    self.wfile.write(chunk); left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def do_GET(self): self._serve(True)
    def do_HEAD(self): self._serve(False)


def soap(service, action, args=""):
    body = (f'<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            f's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            f'<u:{action} xmlns:u="urn:schemas-upnp-org:service:{service}:1"><InstanceID>0</InstanceID>{args}</u:{action}>'
            f'</s:Body></s:Envelope>').encode()
    req = urllib.request.Request(f"http://{OPPO}:49152/control/{service}", body, {
        "Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": f'"urn:schemas-upnp-org:service:{service}:1#{action}"'})
    try:
        return urllib.request.urlopen(req, timeout=8).read().decode(errors="ignore")
    except Exception as e:
        return f"ERR {e}"


def api(ep):
    try:
        return json.loads(urllib.request.urlopen(f"http://{OPPO}:436/{ep}", timeout=5).read())
    except Exception as e:
        return {"err": str(e)}


def api_login():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    for _ in range(6):
        s.sendto(b"NOTIFY OREMOTE LOGIN", (OPPO, 7624))
        try:
            socket.create_connection((OPPO, 436), timeout=1).close(); break
        except OSError:
            time.sleep(1)
    api('signin?%7B%22appIconType%22%3A1%2C%22appIpAddress%22%3A%22' + ME + '%22%7D')


def didl(title, url, mime, sub=None, forms=("sec", "pv", "res")):
    sec = pv = res = ""
    if sub:
        if "sec" in forms:
            sec = (f'<sec:CaptionInfoEx sec:type="srt">{sub}</sec:CaptionInfoEx>'
                   f'<sec:CaptionInfo sec:type="srt">{sub}</sec:CaptionInfo>')
        if "pv" in forms:
            pv = f' pv:subtitleFileType="SRT" pv:subtitleFileUri="{sub}"'
        if "res" in forms:
            res = f'<res protocolInfo="http-get:*:text/srt:*">{sub}</res>'
    return ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
            'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dc="http://purl.org/dc/elements/1.1/" '
            'xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/" xmlns:sec="http://www.sec.co.kr/" '
            'xmlns:pv="http://www.pv.com/pvns/">'
            f'<item id="t" parentID="0" restricted="1"><dc:title>{escape(title)}</dc:title>'
            f'<upnp:class>object.item.videoItem</upnp:class>{sec}'
            f'<res protocolInfo="http-get:*:{mime}:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"{pv}>{url}</res>'
            f'{res}</item></DIDL-Lite>')


def run(name, fname, mime, sub=None, forms=("sec", "pv", "res")):
    url = f"{BASE}/{fname}"
    subu = f"{BASE}/{sub}" if sub else None
    mark = len(LOG)
    r1 = soap("AVTransport", "SetAVTransportURI",
              f"<CurrentURI>{escape(url)}</CurrentURI><CurrentURIMetaData>{escape(didl(name, url, mime, subu, forms))}</CurrentURIMetaData>")
    r2 = soap("AVTransport", "Play", "<Speed>1</Speed>")
    subs, aud, state = None, None, ""
    for _ in range(14):
        time.sleep(1.5)
        api_login()
        subs = api("getsubtitlemenulist").get("subtitle_list")
        aud = api("getaudiomenulist").get("audio_list")
        state = soap("AVTransport", "GetTransportInfo").split("<CurrentTransportState>")[-1].split("<")[0]
        if subs: break
    reqs = [(t, m, p, r) for t, m, p, r, ua in LOG[mark:]]
    srt_reqs = [x for x in reqs if x[2].endswith(".srt")]
    print(f"\n### {name}  ({fname}, {mime}, ext sub: {sub or '-'} {'via ' + '+'.join(forms) if sub else ''})")
    print(f"  SetURI ok: {'ERR' not in r1 and 'Fault' not in r1}   Play ok: {'ERR' not in r2 and 'Fault' not in r2}   state: {state}")
    print(f"  OPPO subtitle_list: {subs}")
    print(f"  OPPO audio_list: {[a.get('name') for a in (aud or [])]}")
    print(f"  .srt requests from OPPO: {srt_reqs or 'NONE'}")
    print(f"  requests ({len(reqs)}):")
    for x in reqs[:14]: print("    ", x)
    if 'Fault' in r1: print("  SetURI fault:", r1[:400])
    soap("AVTransport", "Stop")
    time.sleep(2)


T0 = time.time()
srv = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
print("serving", BASE, "UA seen later in log")
api_login()
tests = [a for a in sys.argv[1:] if "," not in a] or ([] if any("," in a for a in sys.argv[1:]) else ["A", "B", "C", "D"])
for spec in [a for a in sys.argv[1:] if "," in a]:
    f, m, *rest = spec.split(",")
    run(f"{f} as {m}" + (f" + {rest[0]}" if rest else ""), f, m, rest[0] if rest else None)
if "A" in tests: run("A mp4 + external srt", "a.mp4", "video/mp4", "a.srt")
if "B" in tests: run("B mkv + external srt", "b.mkv", "video/x-matroska", "b.srt")
if "C" in tests: run("C mkv embedded srt", "c_emb.mkv", "video/x-matroska")
if "D" in tests: run("D mp4 embedded mov_text", "d_emb.mp4", "video/mp4")
uas = sorted(set(ua for *_, ua in LOG))
print("\nUser-Agents seen:", uas)
srv.shutdown()
