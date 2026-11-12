"""Regenerates the MP4 and QuickTime samples that carry phone metadata.

Phones store where and with what a video was made in metadata the tag editor and the demuxer read: QuickTime keyed
metadata (a meta box of handler mdta holding a keys box and an ilst whose items are named by key index, Apple
QuickTime File Format), QuickTime user data text atoms, the 3GPP location information box (3GPP TS 26.244 clause
8.10) and XMP (XMP Specification Part 3). This script adds them to two committed samples made by make-samples.sh,
whose movie box is the last box, so that nothing but the movie box changes:

- mp4/phone-keyed.mov (from mp4/quicktime.mov): a moov/meta keyed metadata box in QuickTime form (no version and
  flags) as an iPhone writes it, with text keys (location, accuracy, make, model, software, creation date), a
  UTF-16 key, a float, a signed and an unsigned integer and an opaque binary item; the udta text atoms (c)xyz,
  (c)mak and (c)mod; and a udta/XMP_ atom holding a packet with a title, GPS coordinates and a city.
- mp4/location-xmp.mp4 (from mp4/moov-last.mp4): a udta/loci box, a udta/meta keyed metadata box in ISO form (a
  full box, as ffmpeg writes it) with Android keys, and a top-level XMP uuid box after the movie box.

As for make-samples.sh, the committed files are the reference their golden dumps are tied to.

    python -I tools/testdata/behavior/make-keyed-metadata-samples.py [samples directory]
"""
import os
import struct
import sys

XMP_UUID = bytes.fromhex("BE7ACFCB97A942E89C71999491E3AFAC")

XMP = ("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
       "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
       "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
       " xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\""
       " xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:CreatorTool=\"Behavior\" photoshop:City=\"Milano\""
       " exif:GPSLatitude=\"45,27.852N\" exif:GPSLongitude=\"9,11.4E\">"
       "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Phone</rdf:li></rdf:Alt></dc:title>"
       "</rdf:Description></rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>").encode("utf-8")


def box(kind, payload):
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def data_atom(kind, payload, locale=0):
    return box(b"data", struct.pack(">II", kind, locale) + payload)


def keyed_meta(items, full_box):
    handler = box(b"hdlr", bytes(8) + b"mdta" + bytes(12) + b"\x00")
    keys = box(b"keys", struct.pack(">II", 0, len(items)) + b"".join(
        struct.pack(">I", 8 + len(key)) + b"mdta" + key for key, _ in items))
    ilst = box(b"ilst", b"".join(box(struct.pack(">I", index + 1), b"".join(data_atom(kind, payload)
                                                                           for kind, payload in atoms))
                                 for index, (_, atoms) in enumerate(items)))
    return box(b"meta", (bytes(4) if full_box else b"") + handler + keys + ilst)


def text_atom(code, text):
    encoded = text.encode("utf-8")
    return box(code, struct.pack(">HH", len(encoded), 0x15C7) + encoded)


def loci(name, latitude, longitude, altitude):
    fixed = [round(value * 65536) for value in (longitude, latitude, altitude)]
    return box(b"loci", struct.pack(">IH", 0, 0x55C4) + name.encode("utf-8") + b"\x00" + b"\x00"
               + struct.pack(">iii", *fixed) + b"earth\x00" + b"\x00")


def children(data, start, end):
    result = []
    while end - start >= 8:
        size, = struct.unpack(">I", data[start:start + 4])
        result.append((data[start + 4:start + 8], start, size))
        start += size
    return result


def extend_moov(data, moov_children, udta_children):
    top = children(data, 0, len(data))
    kind, offset, size = top[-1]
    if kind != b"moov" or offset + size != len(data):
        raise SystemExit("the movie box is not the last box")
    body = b""
    has_udta = False
    for child, start, length in children(data, offset + 8, offset + size):
        chunk = data[start:start + length]
        if child == b"udta":
            chunk = box(b"udta", chunk[8:] + udta_children)
            has_udta = True
        body += chunk
    if not has_udta:
        body += box(b"udta", udta_children)
    return data[:offset] + box(b"moov", body + moov_children)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repository = os.path.dirname(os.path.dirname(os.path.dirname(here)))
    samples = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repository, "core", "src", "test", "resources",
                                                                 "samples")
    mp4 = os.path.join(samples, "mp4")
    iphone = keyed_meta([
        (b"com.apple.quicktime.location.accuracy.horizontal", [(1, b"14.172235")]),
        (b"com.apple.quicktime.location.ISO6709", [(1, b"+45.4642+009.1900+120.000/")]),
        (b"com.apple.quicktime.make", [(1, b"Apple")]),
        (b"com.apple.quicktime.model", [(1, b"iPhone 15 Pro")]),
        (b"com.apple.quicktime.software", [(1, b"17.4.1")]),
        (b"com.apple.quicktime.creationdate", [(1, b"2024-05-01T12:00:00+0200")]),
        (b"com.apple.quicktime.author", [(2, "Behavior è".encode("utf-16-be"))]),
        (b"com.apple.quicktime.live-photo.vitality-score", [(23, struct.pack(">f", 0.9375))]),
        (b"com.apple.quicktime.full-frame-rate-playback-intent", [(21, struct.pack(">b", -1))]),
        (b"com.apple.quicktime.camera.focal_length.35mm_equivalent", [(22, struct.pack(">H", 24))]),
        (b"com.apple.quicktime.cinematic-video", [(0, bytes([1, 2, 3, 4]))]),
    ], False)
    user_data = (text_atom(b"\xa9xyz", "+45.4642+009.1900/") + text_atom(b"\xa9mak", "Apple")
                 + text_atom(b"\xa9mod", "iPhone 15 Pro") + box(b"XMP_", XMP))
    source = open(os.path.join(mp4, "quicktime.mov"), "rb").read()
    open(os.path.join(mp4, "phone-keyed.mov"), "wb").write(extend_moov(source, iphone, user_data))

    android = keyed_meta([
        (b"com.android.version", [(1, b"14")]),
        (b"com.android.capture.fps", [(23, struct.pack(">f", 30.0))]),
    ], True)
    source = open(os.path.join(mp4, "moov-last.mp4"), "rb").read()
    tagged = extend_moov(source, b"", loci("Milano", 45.4642, 9.19, 120) + android)
    open(os.path.join(mp4, "location-xmp.mp4"), "wb").write(tagged + box(b"uuid", XMP_UUID + XMP))


if __name__ == "__main__":
    main()
