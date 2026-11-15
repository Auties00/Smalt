"""Builds the opusfile stereo downmix harness and prints the downmix goldens.

Usage: python -I tools/testdata/opus-oracle/downmix.py <vector dir> <oracle dir> <work dir>

<oracle dir> is the directory build.py compiled libopus and msenc into. This script downloads libogg 1.3.5
and opusfile 0.12 (pinned by SHA-256) into <oracle dir>/download, compiles them with ofdown.c and the libopus
objects into <oracle dir>/ofdown(.exe), then, for each configuration (the fields of goldens.py), encodes the
input with msenc, muxes the packets into Ogg and decodes them with op_read_float_stereo. The printed line
appends the SHA-256 of the libopus bitstream, of opusfile's interleaved stereo float output (little endian)
after the pre-skip, and of its left channel alone: the goldens of OpusDownmixTest.
"""
import glob
import hashlib
import os
import re
import subprocess
import sys
import tarfile
import urllib.request

vec_dir, oracle, work = sys.argv[1], os.path.abspath(sys.argv[2]), sys.argv[3]
here = os.path.dirname(os.path.abspath(__file__))
exe = ".exe" if os.name == "nt" else ""
download = os.path.join(oracle, "download")
os.makedirs(download, exist_ok=True)
SOURCES = [
    ("https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.gz",
     "0eb4b4b9420a0f51db142ba3f9c64b333f826532dc0f48c6410ae51f4799b664"),
    ("https://downloads.xiph.org/releases/opus/opusfile-0.12.tar.gz",
     "118d8601c12dd6a44f52423e68ca9083cc9f2bfe72da7a8c1acb22a80ae3550b"),
]
for url, sha in SOURCES:
    tarball = os.path.join(download, url.rsplit("/", 1)[1])
    if not os.path.exists(tarball):
        urllib.request.urlretrieve(url, tarball)
    digest = hashlib.sha256(open(tarball, "rb").read()).hexdigest()
    if digest != sha:
        raise SystemExit(tarball + " has SHA-256 " + digest + ", expected " + sha)
    if not os.path.isdir(tarball[:-len(".tar.gz")]):
        with tarfile.open(tarball) as archive:
            archive.extractall(download, filter="data")
ogg = os.path.join(download, "libogg-1.3.5")
opusfile = os.path.join(download, "opusfile-0.12")
opus = os.path.join(download, "opus-1.5.2")
config = os.path.join(oracle, "ogg-config")
os.makedirs(os.path.join(config, "ogg"), exist_ok=True)
with open(os.path.join(config, "ogg", "config_types.h"), "w") as f:
    f.write("#ifndef __CONFIG_TYPES_H__\n#define __CONFIG_TYPES_H__\n#include <stdint.h>\n"
            "typedef int16_t ogg_int16_t;\ntypedef uint16_t ogg_uint16_t;\ntypedef int32_t ogg_int32_t;\n"
            "typedef uint32_t ogg_uint32_t;\ntypedef int64_t ogg_int64_t;\ntypedef uint64_t ogg_uint64_t;\n"
            "#endif\n")
flags = ["-O2", "-ffp-contract=off", "-fno-fast-math", "-msse2", "-mfpmath=sse", "-mno-fma", "-DNDEBUG",
         "-DOP_DISABLE_HTTP", "-I" + config, "-I" + os.path.join(ogg, "include"),
         "-I" + os.path.join(opus, "include"), "-I" + os.path.join(opusfile, "include")]
files = [os.path.join(ogg, "src", "bitwise.c"), os.path.join(ogg, "src", "framing.c")] + [
    os.path.join(opusfile, "src", n) for n in ("info.c", "internal.c", "opusfile.c", "stream.c")]
libopus = glob.glob(os.path.join(oracle, "obj", "*.o"))
harness = os.path.join(oracle, "ofdown" + exe)
subprocess.check_call(["gcc", os.path.join(here, "ofdown.c"), "-o", harness] + flags + files + libopus + ["-lm"])

CONFIGS = [
    "o30 surround3_4 48000 3 1 audio 128000 vbr 10 20000 auto auto 0 0 0 0",
    "o40 surround4_4 48000 4 1 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
    "o50 surround5_4 48000 5 1 audio 192000 vbr 10 20000 auto auto 0 0 0 0",
    "o51 surround6_4 48000 6 1 audio 256000 vbr 10 20000 auto auto 0 0 0 0",
    "o51_voip surround6_4 48000 6 1 voip 64000 vbr 10 20000 auto auto 0 0 0 0",
    "o61 surround7_4 48000 7 1 audio 224000 vbr 10 20000 auto auto 0 0 0 0",
    "o71 surround8_4 48000 8 1 audio 320000 vbr 10 20000 auto auto 0 0 0 0",
    "o71_40ms surround8_4 48000 8 1 audio 192000 cvbr 10 40000 auto auto 0 0 0 0",
]
os.makedirs(work, exist_ok=True)
for line in CONFIGS:
    f = line.split()
    pcm = os.path.join(work, f[1] + ".pcm")
    if not os.path.exists(pcm):
        subprocess.check_call([sys.executable, "-I", os.path.join(here, "gen.py"), vec_dir, f[1], pcm])
    bit = os.path.join(work, "downmix_" + f[0] + ".bit")
    info = subprocess.run([os.path.join(oracle, "msenc" + exe), pcm, bit] + f[2:], check=True,
                          capture_output=True, text=True).stdout
    s = re.search(r"streams=(\d+) coupled=(\d+) lookahead=(\d+) packets=\d+ mapping=([0-9,]+)", info)
    preskip = int(s.group(3)) * 48000 // int(f[2])
    out = os.path.join(work, "downmix_" + f[0] + ".f32")
    subprocess.run([harness, bit, out, f[3], f[4], s.group(1), s.group(2), s.group(4), str(preskip)], check=True,
                   capture_output=True)
    bit_digest = hashlib.sha256(open(bit, "rb").read()).hexdigest()
    stereo = open(out, "rb").read()
    left = b"".join(stereo[i:i + 4] for i in range(0, len(stereo), 8))
    digest = hashlib.sha256(stereo).hexdigest()
    print('"%s %s %s %s",' % (line, bit_digest, digest, hashlib.sha256(left).hexdigest()))
