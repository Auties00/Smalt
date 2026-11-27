"""Builds the libopus 1.5.2 reference harnesses behind the Opus goldens.

Usage: python -I tools/testdata/opus-oracle/build.py <work directory>

Downloads opus-1.5.2.tar.gz (pinned by SHA-256) into <work directory>/download, unpacks it there,
and compiles the float build without SIMD or FMA contraction (gcc, -O2 -ffp-contract=off) together
with msenc.c (encoder) and msdec.c (lossy decoder) into <work directory>/msenc(.exe) and
<work directory>/msdec(.exe). It then compiles a second build with the neural extensions
(ENABLE_DEEP_PLC, ENABLE_DRED, ENABLE_OSCE, the sources of lpcnet_sources.mk) whose dnn/ files are compiled
without the x86 SIMD macros, so that vec.h selects its generic C code, and links it with dnn.c and dnnunit.c into
<work directory>/dnn(.exe) and <work directory>/dnnunit(.exe). On Windows run it from an MSYS2 UCRT64 shell.

The neural build is libopus 1.5.2 with the standardized DRED of libopus 1.6.1 (draft-ietf-mlcodec-opus-dred,
payload version 12), as Smalt has it: it also downloads opus-1.6.1.tar.gz (pinned by SHA-256) and compiles a copy
of the 1.5.2 tree in <work directory>/download/opus-1.5.2-dred12 whose DRED_FILES come from 1.6.1. These are the
DRED models, statistics, configuration and networks, and nnet.c, whose convolution scratch 1.6.1 sizes for every
model now that the DRED constants no longer bound the other networks' convolutions.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request

URL = "https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz"
SHA256 = "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"
DRED_URL = "https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz"
DRED_SHA256 = "6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1"
DRED_FILES = ["dnn/" + f for f in (
    "dred_config.h", "dred_rdovae_constants.h", "dred_decoder.c", "dred_decoder.h", "dred_encoder.c",
    "dred_rdovae_dec.c", "dred_rdovae_enc.c", "dred_rdovae_dec_data.c", "dred_rdovae_dec_data.h",
    "dred_rdovae_enc_data.c", "dred_rdovae_enc_data.h", "dred_rdovae_stats_data.c", "dred_rdovae_stats_data.h",
    "nnet.c")]

work = os.path.abspath(sys.argv[1])
here = os.path.dirname(os.path.abspath(__file__))
download = os.path.join(work, "download")
objects = os.path.join(work, "obj")
os.makedirs(download, exist_ok=True)
os.makedirs(objects, exist_ok=True)
tarball = os.path.join(download, "opus-1.5.2.tar.gz")
if not os.path.exists(tarball):
    urllib.request.urlretrieve(URL, tarball)
digest = hashlib.sha256(open(tarball, "rb").read()).hexdigest()
if digest != SHA256:
    raise SystemExit("opus-1.5.2.tar.gz has SHA-256 " + digest + ", expected " + SHA256)
src = os.path.join(download, "opus-1.5.2")
if not os.path.isdir(src):
    with tarfile.open(tarball) as archive:
        archive.extractall(download, filter="data")


def sources(mk, var):
    text = open(os.path.join(src, mk), encoding="utf-8").read()
    match = re.search(r"^" + var + r"\s*=\s*((?:.*\\\n)*.*)$", text, re.M)
    return [s for s in match.group(1).replace("\\\n", " ").split() if s.endswith(".c")]


files = (sources("celt_sources.mk", "CELT_SOURCES")
         + sources("silk_sources.mk", "SILK_SOURCES")
         + sources("silk_sources.mk", "SILK_SOURCES_FLOAT")
         + sources("opus_sources.mk", "OPUS_SOURCES")
         + sources("opus_sources.mk", "OPUS_SOURCES_FLOAT"))
flags = ["-O2", "-ffp-contract=off", "-fno-fast-math", "-msse2", "-mfpmath=sse", "-mno-fma",
         "-DOPUS_BUILD", "-DUSE_ALLOCA", "-DHAVE_LRINTF", "-DHAVE_LRINT", "-DNDEBUG",
         "-I" + os.path.join(src, "include"), "-I" + os.path.join(src, "celt"),
         "-I" + os.path.join(src, "silk"), "-I" + os.path.join(src, "silk", "float"),
         "-I" + os.path.join(src, "src"), "-I" + src]
compiled = []
for f in files:
    o = os.path.join(objects, f.replace("/", "_").replace(".c", ".o"))
    compiled.append(o)
    if not os.path.exists(o):
        subprocess.check_call(["gcc", "-c", os.path.join(src, f), "-o", o] + flags)
for harness in ("msenc", "msdec"):
    exe = os.path.join(work, harness + ".exe" if os.name == "nt" else harness)
    subprocess.check_call(["gcc", os.path.join(here, harness + ".c"), "-o", exe] + flags + compiled + ["-lm"])
    print("built", exe)

dred_tarball = os.path.join(download, "opus-1.6.1.tar.gz")
if not os.path.exists(dred_tarball):
    urllib.request.urlretrieve(DRED_URL, dred_tarball)
digest = hashlib.sha256(open(dred_tarball, "rb").read()).hexdigest()
if digest != DRED_SHA256:
    raise SystemExit("opus-1.6.1.tar.gz has SHA-256 " + digest + ", expected " + DRED_SHA256)
dred_src = os.path.join(download, "opus-1.6.1")
if not os.path.isdir(dred_src):
    with tarfile.open(dred_tarball) as archive:
        archive.extractall(download, filter="data")
merged = os.path.join(download, "opus-1.5.2-dred12")
if not os.path.isdir(merged):
    shutil.copytree(src, merged)
    for f in DRED_FILES:
        shutil.copyfile(os.path.join(dred_src, f), os.path.join(merged, f))
src = merged
flags = [f.replace(os.path.join(download, "opus-1.5.2"), merged) if f.startswith("-I") else f for f in flags]

dnn_objects = os.path.join(work, "dnn-obj-dred12")
os.makedirs(dnn_objects, exist_ok=True)
dnn_files = (files + sources("lpcnet_sources.mk", "DEEP_PLC_SOURCES") + sources("lpcnet_sources.mk", "DRED_SOURCES")
             + sources("lpcnet_sources.mk", "OSCE_SOURCES"))
dnn_flags = flags + ["-DENABLE_DEEP_PLC", "-DENABLE_DRED", "-DENABLE_OSCE", "-I" + os.path.join(src, "dnn")]
dnn_compiled = []
for f in dnn_files:
    o = os.path.join(dnn_objects, f.replace("/", "_").replace(".c", ".o"))
    dnn_compiled.append(o)
    if not os.path.exists(o):
        extra = ["-U__SSE__", "-U__SSE2__", "-U__AVX__", "-U__AVX2__", "-U__FMA__"] if f.startswith("dnn/") else []
        subprocess.check_call(["gcc", "-c", os.path.join(src, f), "-o", o] + dnn_flags + extra)
for harness in ("dnn", "dnnunit"):
    exe = os.path.join(work, harness + ".exe" if os.name == "nt" else harness)
    subprocess.check_call(["gcc", os.path.join(here, harness + ".c"), "-o", exe] + dnn_flags
                          + ["-U__SSE__", "-U__SSE2__", "-U__AVX__", "-U__AVX2__", "-U__FMA__"] + dnn_compiled
                          + ["-lm"])
    print("built", exe)
