import gzip
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from lib.cast import (Catalog, Entry, disposition_filename, normalize_headers,  # noqa: E402
                      parse_range, safe_name, sniff_ext, to_subtitle, vtt_to_srt)


def test_safe_name_ascii_and_punctuation():
    assert safe_name("Ação: Parte 1 / Fim?") == "Acao Parte 1 Fim"
    assert safe_name("Grey's Anatomy - S01E01") == "Greys Anatomy - S01E01"
    assert safe_name('He said "hi" \\ <x>') == "He said hi x"
    assert safe_name("") == "Xnoppo cast"
    assert safe_name("...") == "Xnoppo cast"
    assert len(safe_name("x" * 300)) == 80


def test_sniff_magic_bytes():
    assert sniff_ext(b"\x1a\x45\xdf\xa3" + b"\0" * 60) == "mkv"
    assert sniff_ext(b"\0\0\0\x20ftypisom" + b"\0" * 20) == "mp4"
    assert sniff_ext(b"\0\0\0\x14ftypqt  " + b"\0" * 20) == "mov"
    ts = bytearray(400)
    ts[0] = ts[188] = 0x47
    assert sniff_ext(bytes(ts)) == "ts"
    m2ts = bytearray(400)
    m2ts[4] = m2ts[196] = 0x47
    assert sniff_ext(bytes(m2ts)) == "m2ts"
    assert sniff_ext(b"RIFF\0\0\0\0AVI LIST") == "avi"


def test_sniff_falls_back_to_name_then_mkv():
    assert sniff_ext(b"garbage", "Movie.2160p.mp4") == "mp4"
    assert sniff_ext(b"garbage", "noext") == "mkv"
    assert sniff_ext(b"garbage", "weird.exe") == "mkv"


def test_disposition_filename():
    assert disposition_filename('inline; filename="A B.mp4"') == "A B.mp4"
    assert disposition_filename("attachment; filename*=UTF-8''A%20%C3%A7.mkv") == "A ç.mkv"
    assert disposition_filename("attachment; filename=plain.mkv") == "plain.mkv"
    assert disposition_filename(None) == ""


def test_normalize_headers_list_and_dict():
    assert normalize_headers(["User-Agent: X", "Referer: https://a/b:c", "junk"]) == {
        "User-Agent": "X", "Referer": "https://a/b:c"}
    assert normalize_headers({"Range": "bytes=0-1", "Cookie": "a=b"}) == {"Cookie": "a=b"}
    assert normalize_headers(None) == {}


def test_parse_range():
    assert parse_range(None, 100) is None
    assert parse_range("bytes=0-9", 100) == (0, 9)
    assert parse_range("bytes=90-", 100) == (90, 99)
    assert parse_range("bytes=95-500", 100) == (95, 99)
    assert parse_range("bytes=-10", 100) == (90, 99)
    with pytest.raises(ValueError):
        parse_range("bytes=100-", 100)
    with pytest.raises(ValueError):
        parse_range("bytes=-0", 100)


VTT = """WEBVTT
Kind: captions

NOTE a comment

STYLE
::cue { color: yellow }

intro
00:01.500 --> 00:03.000 align:start position:10%
<v Ana>Olá <i>mundo</i></v>

00:01:05.336 --> 00:01:06.437
<c.yellow>O que</c> você fez?<00:01:06.000>
"""


def test_vtt_to_srt():
    srt = vtt_to_srt(VTT)
    assert srt == ("1\n00:00:01,500 --> 00:00:03,000\nOlá <i>mundo</i>\n\n"
                   "2\n00:01:05,336 --> 00:01:06,437\nO que você fez?\n")


def test_to_subtitle_variants():
    ext, text = to_subtitle(VTT.encode("utf-8"))
    assert ext == "srt" and "\r\n" in text and text.startswith("1\r\n00:00:01,500")
    srt = "1\n00:00:01,000 --> 00:00:02,000\nAção\n".encode("cp1252")
    ext, text = to_subtitle(gzip.compress(srt))
    assert ext == "srt" and "Ação" in text
    ext, _ = to_subtitle(b"\xef\xbb\xbf[Script Info]\nTitle: x\n")
    assert ext == "ass"
    with pytest.raises(ValueError):
        to_subtitle(b"<html>not found</html>")


def test_catalog_keeps_newest():
    c = Catalog(keep=2)
    for cid in ("a", "b", "c"):
        c.publish(cid, [Entry(f"{cid}.mkv", 1, "video/x-matroska")])
    assert c.ids() == ["b", "c"]
    assert c.get("c", "c.mkv").size == 1
    assert c.files("a") is None
    c.publish("b", [Entry("b2.mkv", 2, "video/x-matroska")])  # re-publish moves to newest
    assert c.ids() == ["c", "b"]
