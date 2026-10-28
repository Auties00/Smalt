"""Regenerates the ADPCM and telephony samples of the I/O behavior harness outside WAVE.

ffmpeg encodes 0.6 s of its pink noise source into IMA ADPCM in Apple's ima4 layout (AIFF-C and QuickTime),
GSM 06.10 (QuickTime agsm, through libgsm), Microsoft ADPCM (QuickTime ms\\0\\x02, AVI and Matroska A_MS/ACM),
IMA ADPCM in Microsoft's block layout (AVI and Matroska), G.726 (AVI) and G.722 (Matroska). As for make-samples.sh,
the committed files are the reference their golden dumps are tied to; the ffmpeg that wrote them was a 2026 build
with libgsm.

    python -I tools/testdata/behavior/make-adpcm-samples.py [samples directory]
"""
import os
import subprocess
import sys

SOURCE = "anoisesrc=color=pink:amplitude=0.3:sample_rate=8000:seed=7:duration=0.6"

SAMPLES = [
    ("aiff/ima4.aifc", ["-ac", "2", "-c:a", "adpcm_ima_qt", "-f", "aiff"]),
    ("mp4/ima4.mov", ["-ac", "2", "-c:a", "adpcm_ima_qt"]),
    ("mp4/gsm.mov", ["-c:a", "libgsm"]),
    ("mp4/ms-adpcm.mov", ["-ac", "2", "-c:a", "adpcm_ms"]),
    ("avi/ima-adpcm.avi", ["-ac", "2", "-c:a", "adpcm_ima_wav"]),
    ("avi/ms-adpcm.avi", ["-c:a", "adpcm_ms"]),
    ("avi/g726.avi", ["-c:a", "g726", "-b:a", "24k"]),
    ("matroska/ms-adpcm.mka", ["-ac", "2", "-c:a", "adpcm_ms"]),
    ("matroska/ima-adpcm.mka", ["-c:a", "adpcm_ima_wav"]),
    ("matroska/g722.mka", ["-ar", "16000", "-c:a", "g722"]),
]


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join("core", "src", "test", "resources", "samples")
    for path, arguments in SAMPLES:
        target = os.path.join(root, path)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-bitexact", "-f", "lavfi", "-i",
                        SOURCE, *arguments, target], check=True)
        print(target, os.path.getsize(target))


if __name__ == "__main__":
    main()
