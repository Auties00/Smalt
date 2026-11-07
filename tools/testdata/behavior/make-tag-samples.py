"""Regenerates the tagged raw stream samples of the I/O behavior harness.

The demuxers of raw streams read the ID3v2 tags before the frames and the APE and ID3v1 tags after them, and
exclude the tags from the audio. This script wraps the committed single-channel sample amr/nb.amr (made by
make-samples.sh) in such tags:

- amr/nb-tags.amr: an ID3v2.4 tag (TIT2 "Behavior", TPE1 "Smalt"), the frames of nb.amr, an APEv2 tag with a
  header and a footer (Album "Samples") and an ID3v1.1 tag (title "Behavior", artist "Smalt", album "Samples",
  year 2026, no genre).

As for make-samples.sh, the committed files are the reference their golden dumps are tied to.

    python -I tools/testdata/behavior/make-tag-samples.py [samples directory]
"""
import os
import struct
import sys


def syncsafe(value):
    return bytes([(value >> 21) & 0x7F, (value >> 14) & 0x7F, (value >> 7) & 0x7F, value & 0x7F])


def id3v2(frames):
    body = b"".join(identifier.encode("ascii") + syncsafe(len(text) + 1) + b"\x00\x00" + b"\x03"
                    + text.encode("utf-8") for identifier, text in frames)
    return b"ID3\x04\x00\x00" + syncsafe(len(body)) + body


def ape(items):
    body = b"".join(struct.pack("<II", len(value), 0) + key.encode("ascii") + b"\x00" + value.encode("utf-8")
                    for key, value in items)
    size = len(body) + 32
    header = b"APETAGEX" + struct.pack("<IIII", 2000, size, len(items), 0xA0000000) + bytes(8)
    footer = b"APETAGEX" + struct.pack("<IIII", 2000, size, len(items), 0x80000000) + bytes(8)
    return header + body + footer


def id3v1(title, artist, album, year):
    tag = bytearray(128)
    tag[0:3] = b"TAG"
    for offset, length, text in ((3, 30, title), (33, 30, artist), (63, 30, album), (93, 4, year)):
        data = text.encode("latin-1")[:length]
        tag[offset:offset + len(data)] = data
    tag[127] = 255
    return bytes(tag)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repository = os.path.dirname(os.path.dirname(os.path.dirname(here)))
    samples = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repository, "core", "src", "test", "resources",
                                                                 "samples")
    amr = os.path.join(samples, "amr")
    frames = open(os.path.join(amr, "nb.amr"), "rb").read()
    tagged = (id3v2([("TIT2", "Behavior"), ("TPE1", "Smalt")]) + frames + ape([("Album", "Samples")])
              + id3v1("Behavior", "Smalt", "Samples", "2026"))
    open(os.path.join(amr, "nb-tags.amr"), "wb").write(tagged)


if __name__ == "__main__":
    main()
