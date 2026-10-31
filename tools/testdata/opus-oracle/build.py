"""Builds the libopus 1.5.2 reference harnesses behind the Opus goldens.

Usage: python -I tools/testdata/opus-oracle/build.py <work directory>

Downloads opus-1.5.2.tar.gz (pinned by SHA-256) into <work directory>/download, unpacks it there,
and compiles the float build without SIMD or FMA contraction (gcc, -O2 -ffp-contract=off) together
with msenc.c (encoder) and msdec.c (lossy decoder) into <work directory>/msenc(.exe) and
<work directory>/msdec(.exe). It then compiles a second build with the neural extensions
(ENABLE_DEEP_PLC, ENABLE_DRED, ENABLE_OSCE, the sources of lpcnet_sources.mk) whose dnn/ files are compiled
without the x86 SIMD macros, so that vec.h selects its generic C code, and links it with dnn.c into
<work directory>/dnn(.exe). On Windows run it from an MSYS2 UCRT64 shell.
"""
import hashlib
import os
import re
import subprocess
import sys
import tarfile
import urllib.request

URL = "https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz"
SHA256 = "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"

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

dnn_objects = os.path.join(work, "dnn-obj")
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
