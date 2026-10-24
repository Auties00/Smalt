"""Writes tools/testdata/behavior/samples.tsv, the SHA-256 of every committed I/O behavior sample.

The samples are made by the scripts of this directory (see each row's generator); the behavior tests check
the committed files against these rows, so that a regenerated sample, whose golden dumps change with it,
shows up as a reviewed change of this manifest.

    python -I tools/testdata/behavior/pin-samples.py
"""
import hashlib
import os

ROOTS = ["core/src/test/resources/samples", "image/src/test/resources/samples", "video/src/test/resources/samples"]


def generator(root, relative):
    if root.startswith("image/"):
        return "make-image-samples.sh"
    if root.startswith("video/"):
        return "make-avif-samples.py"
    if relative == "mp4/motion.mj2":
        return "make-mj2-sample.py"
    if relative in ("amr/nb-stereo.amr", "amr/wb-3ch.awb"):
        return "make-amr-mc-samples.py"
    if relative.startswith("wav/") and relative not in ("wav/s16.wav", "wav/f32.wav"):
        return "make-wav-samples.py"
    if relative.startswith("subtitle/") and relative not in ("subtitle/cues.srt", "subtitle/cues.vtt"):
        return "make-subtitle-samples.py"
    return "make-samples.sh"


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repository = os.path.dirname(os.path.dirname(os.path.dirname(here)))
    rows = []
    for root in ROOTS:
        base = os.path.join(repository, root)
        for directory, _, files in os.walk(base):
            for name in files:
                path = os.path.join(directory, name)
                relative = os.path.relpath(path, base).replace(os.sep, "/")
                data = open(path, "rb").read()
                rows.append((root + "/" + relative, len(data), hashlib.sha256(data).hexdigest(),
                             generator(root, relative)))
    rows.sort()
    with open(os.path.join(here, "samples.tsv"), "w", newline="\n", encoding="ascii") as out:
        out.write("# The committed I/O behavior samples; regenerate with pin-samples.py. Columns:\n")
        out.write("# path\tsize\tsha256\tgenerator\n")
        for row in rows:
            out.write("%s\t%d\t%s\t%s\n" % row)


if __name__ == "__main__":
    main()
