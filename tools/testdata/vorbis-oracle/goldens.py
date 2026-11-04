"""Runs the libvorbis reference on every golden configuration of the Vorbis encoder tests and prints the goldens.

Usage: python -I tools/testdata/vorbis-oracle/goldens.py <vbm> <work dir>

<vbm> is the harness build.py compiles, <work dir> receives the generated signals and packets. Signals.java
writes the inputs (it needs a JDK: JAVA_HOME or java on PATH). Each configuration names the signal (shape,
channels, rate, length, seed), the libvorbis settings vbm.c takes and the matching VorbisEncoderOptions
settings. The printed lines are the CsvSource rows of VorbisEncoderTest.Golden ("vbr" lines) and of
VorbisBitrateManagementTest.Golden (the others): the SHA-256 of the identification header, the setup header and
every audio packet.
"""
import hashlib
import os
import struct
import subprocess
import sys

vbm, work = sys.argv[1], os.path.abspath(sys.argv[2])
here = os.path.dirname(os.path.abspath(__file__))
java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if "JAVA_HOME" in os.environ else "java"

# name | shape channels rate length seed | vbm settings | VorbisEncoderOptions settings
CONFIGS = [
    "vbr | MUSIC 2 44100 132300 1 | vbr q=0.4 | 4",
    "vbr | TRANSIENTS 2 48000 96000 2 | vbr q=0.6 | 6",
    "vbr | MUSIC 1 22050 44100 3 | vbr q=-0.1 | -1",
    "vbr | NOISE 1 8000 16000 4 | vbr q=0 | 0",
    "vbr | MUSIC 2 96000 96000 5 | vbr q=0.3 | 3",
    "vbr | SINE 1 32000 40000 6 | vbr q=1 | 10",
    "average | MUSIC 2 44100 132300 1 | managed nom=128000 | avg=128000",
    "average with limits | TRANSIENTS 2 48000 96000 2 | managed max=104000 nom=96000 min=88000"
    " | avg=96000 min=88000 max=104000",
    "limits at a nominal bitrate | TRANSIENTS 2 48000 96000 2 | managed max=128000 nom=96000 min=64000 noavg=1"
    " | nom=96000 min=64000 max=128000",
    "maximum at a quality | MUSIC 1 22050 66150 11 | qlim q=0.4 max=40 | q=4 max=40000",
    "truncating maximum | MUSIC 2 44100 132300 1 | qlim q=0.4 max=48 res=8000 | q=4 max=48000 res=8000",
    "padding minimum | MUSIC 2 44100 132300 1 | qlim q=0 min=200 res=8000 | q=0 min=200000 res=8000",
    "reservoir settings | MUSIC 1 32000 64000 7 | managed nom=48000 res=20000 bias=0.5 damp=0.5"
    " | avg=48000 res=20000 bias=0.5 damp=0.5",
    "5.1 average | MUSIC 6 48000 48000 8 | managed nom=256000 | avg=256000",
    "8 kHz narrow limits | NOISE 1 8000 24000 9 | managed max=20000 nom=16000 min=12000"
    " | avg=16000 min=12000 max=20000",
    # the 96 kHz setting has no typical bitrate, so the reservoir VorbisEncoder sizes automatically (twice the
    # maximum) is passed explicitly to libvorbis
    "96 kHz limits at a quality | MUSIC 2 96000 96000 5 | qlim q=0.3 max=160 min=64 res=320000"
    " | q=3 min=64000 max=160000",
    "silence with a minimum | SILENCE 2 44100 44100 10 | managed nom=64000 min=32000 | avg=64000 min=32000",
]


def packets(path):
    data = open(path, "rb").read()
    out = []
    i = 0
    while i < len(data):
        n = struct.unpack_from("<I", data, i)[0]
        out.append(data[i + 4:i + 4 + n])
        i += 4 + n
    return out


os.makedirs(work, exist_ok=True)
for config in CONFIGS:
    name, signal, settings, options = [part.strip() for part in config.split("|")]
    shape, channels, rate, length, seed = signal.split()
    source = os.path.join(work, "-".join(signal.split()) + ".f32")
    if not os.path.exists(source):
        subprocess.run([java, os.path.join(here, "Signals.java"), shape, channels, rate, length, seed, source],
                       check=True)
    output = os.path.join(work, "out.bin")
    subprocess.run([vbm, source, channels, rate, length, output] + settings.split(), check=True)
    stream = packets(output)
    digest = hashlib.sha256()
    digest.update(stream[0])
    digest.update(stream[2])
    for packet in stream[3:]:
        digest.update(packet)
    if name == "vbr":
        print('"%s, %s, %s, %s, %s, %s, %s",' % (shape, channels, rate, length, options, seed, digest.hexdigest()))
    else:
        print('"%s, %s, %s, %s, %s, %s, %s, " + "%s",' % (name, shape, channels, rate, length, seed, options,
                                                            digest.hexdigest()))
