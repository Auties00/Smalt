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
    if relative == "ts/av1-scte35.ts":
        return "make-samples.sh, make-ts-samples.py"
    if relative == "amr/nb-tags.amr":
        return "make-tag-samples.py"
    if relative in ("mp4/phone-keyed.mov", "mp4/location-xmp.mp4"):
        return "make-samples.sh, make-keyed-metadata-samples.py"
    if relative.split("/")[-1].startswith("dolby-vision-"):
        return "make-dolby-vision-samples.py"
    if relative == "matroska/ordered-parts.mkv":
        return "make-timeline-samples.py"
    if relative in ("matroska/editions.mkv", "mp4/timecode-spherical.mov", "wav/bext-cue.wav", "aiff/markers.aiff",
                    "flac/cuesheet.flac"):
        return "make-descriptive-samples.py"
    if relative in ("ogg/opus-ambisonic.opus", "mp4/ambisonic.mov", "wav/ambisonic-bformat.wav"):
        return "make-ambisonic-samples.py"
    if relative in ("aiff/ima4.aifc", "mp4/ima4.mov", "mp4/gsm.mov", "mp4/ms-adpcm.mov", "avi/ima-adpcm.avi",
                    "avi/ms-adpcm.avi", "avi/g726.avi", "matroska/ms-adpcm.mka", "matroska/ima-adpcm.mka",
                    "matroska/g722.mka"):
        return "make-adpcm-samples.py"
    if relative in ("wavpack/wavpack.wv", "matroska/wavpack.mka", "ogg/speex.spx", "matroska/ffv1.mkv",
                    "avi/ffv1.avi", "ogg/theora.ogv"):
        return "make-xiph-lossless-samples.py"
    if relative.startswith("wav/") and relative not in ("wav/s16.wav", "wav/f32.wav"):
        return "make-wav-samples.py"
    if relative in ("subtitle/pgs.sup", "subtitle/vobsub.sub", "ts/dvb-subtitles.ts", "ts/teletext.ts"):
        return "make-bitmap-caption-samples.py"
    if relative in ("matroska/pgs.mkv", "matroska/vobsub.mkv", "mp4/vobsub.mp4", "ts/h264-captions.ts", "ps/dvd.vob"):
        return "make-samples.sh, make-bitmap-caption-samples.py"
    if relative.startswith("mxf/"):
        return "make-mxf-samples.sh"
    if relative in ("ts/vc1.ts", "mp4/vc1.mp4", "avi/wmv3.avi", "matroska/wmv3.mkv"):
        return "make-vc1-samples.sh"
    if relative.startswith("flv/"):
        return "make-flv-samples.py"
    if relative.split("/")[0] in ("caf", "w64", "au", "loas", "adif"):
        return "make-audio-container-samples.py"
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
