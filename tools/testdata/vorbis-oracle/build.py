"""Builds the libvorbis 1.3.7 reference harness behind the Vorbis encoder goldens.

Usage: python -I tools/testdata/vorbis-oracle/build.py <work directory>

Downloads libvorbis-1.3.7.tar.gz and libogg-1.3.5.tar.gz (BSD-3, pinned by SHA-256) and the fdlibm 5.3
sources the harness needs (netlib, Sun's permissive notice, pinned by SHA-256) into <work directory>/download,
and compiles vbm.c with libvorbis into <work directory>/vbm(.exe), with gcc -O2 -ffp-contract=off. Two changes
make libvorbis compute what VorbisEncoder computes:
- os.h defines rint(x) as floor(x + 0.5f) on Windows, rounding exact halves up; the copy built here drops that
  definition, so rint rounds halves to even, as Math.rint and the C library elsewhere do.
- sin, cos, atan, exp, log and pow resolve to fdlibm, the library java.lang.StrictMath specifies, so the results
  do not depend on the platform's C library.
On Windows run it from an MSYS2 UCRT64 shell.
"""
import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ARCHIVES = {
    "libvorbis-1.3.7.tar.gz": ("https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.gz",
                               "0e982409a9c3fc82ee06e08205b1355e5c6aa4c36bca58146ef399621b0ce5ab"),
    "libogg-1.3.5.tar.gz": ("https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.gz",
                            "0eb4b4b9420a0f51db142ba3f9c64b333f826532dc0f48c6410ae51f4799b664"),
}
FDLIBM = {
    "fdlibm.h": "64113c472c2676499288bc35a821b0867916552930bc52c7445d2c50dddf0e71",
    "k_sin.c": "75d391f3c124806ce14893cd038ae5dd10663e40430ffa174d5fddcb33012167",
    "k_cos.c": "841086c75288f3f9fef03bae2a9dc24e99c3cdb2035e170c69452013a7b5befc",
    "e_rem_pio2.c": "3a08dd4a38f9f9fc9478a33ca05cb81a6566f1010ab14412e97cae091bede16b",
    "k_rem_pio2.c": "5796209ea3fe1af88e22badeaed0f6a6aa923914e097e227c410527cb5815740",
    "s_sin.c": "4d5edb75368e4f8e0ac18682d3ae4b6944ba06801637cf0df6f378d6211f4c81",
    "s_cos.c": "bdcbea3c83b2d4aa521b9a5341f3e58b8cb9c8d326fcc4abb715f3704cf17799",
    "e_exp.c": "04c96195b7ced914d659b5fd204724c182f091af8631fe42016ad6e27cb302ef",
    "e_log.c": "a2b1bd1b367026a35f36da53425cd618386c3bf69d23ac224a40791aa9855ac9",
    "e_pow.c": "763b86baa63eb3518b43f7f3e44e5ed10992b62650f4a45c3a2ea6015e6d5122",
    "s_atan.c": "8583c253aed6318dee2a48135e3f3b1328d838a390372a69414ac41eb5ce5ce1",
    "s_scalbn.c": "9ad1bb374fc2951ec086474c82373b45bccfdecd34d631974dccf8633f40d99e",
    "s_copysign.c": "459c18858c944a4e7062d38ab4dfb596edbe76706d24029c2dac766dcd2be3fd",
    "s_fabs.c": "c6389f3e0472ec0157d648b3e4e80b405038acfaf3e85b961363ea483e3259f1",
}
CONFIG_TYPES = """#ifndef __CONFIG_TYPES_H__
#define __CONFIG_TYPES_H__
#include <stdint.h>
typedef int16_t ogg_int16_t;
typedef uint16_t ogg_uint16_t;
typedef int32_t ogg_int32_t;
typedef uint32_t ogg_uint32_t;
typedef int64_t ogg_int64_t;
typedef uint64_t ogg_uint64_t;
#endif
"""
WRAPPERS = """double __ieee754_exp(double), __ieee754_log(double), __ieee754_pow(double, double);
double fd_exp(double x) { return __ieee754_exp(x); }
double fd_log(double x) { return __ieee754_log(x); }
double fd_pow(double x, double y) { return __ieee754_pow(x, y); }
"""
LIBVORBIS = ["analysis", "bitrate", "block", "codebook", "envelope", "floor0", "floor1", "info", "lookup", "lpc",
             "lsp", "mapping0", "mdct", "psy", "registry", "res0", "sharedbook", "smallft", "synthesis", "window",
             "vorbisenc"]


def fetch(url, path, sha256):
    if not os.path.exists(path):
        urllib.request.urlretrieve(url, path)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if digest != sha256:
        raise SystemExit(os.path.basename(path) + " has SHA-256 " + digest + ", expected " + sha256)


work = os.path.abspath(sys.argv[1])
here = os.path.dirname(os.path.abspath(__file__))
download = os.path.join(work, "download")
build = os.path.join(work, "build")
os.makedirs(download, exist_ok=True)
os.makedirs(os.path.join(download, "fdlibm"), exist_ok=True)
os.makedirs(build, exist_ok=True)
for name, (url, sha256) in ARCHIVES.items():
    tarball = os.path.join(download, name)
    fetch(url, tarball, sha256)
    if not os.path.isdir(tarball[:-len(".tar.gz")]):
        with tarfile.open(tarball) as archive:
            archive.extractall(download, filter="data")
for name, sha256 in FDLIBM.items():
    fetch("https://www.netlib.org/fdlibm/" + name, os.path.join(download, "fdlibm", name), sha256)

ogg = os.path.join(download, "libogg-1.3.5")
with open(os.path.join(ogg, "include", "ogg", "config_types.h"), "w") as out:
    out.write(CONFIG_TYPES)
lib = os.path.join(build, "lib")
if os.path.isdir(lib):
    shutil.rmtree(lib)
shutil.copytree(os.path.join(download, "libvorbis-1.3.7", "lib"), lib)
os_h = os.path.join(lib, "os.h")
text = open(os_h).read()
windows_rint = "#  include <malloc.h>\n#  define rint(x)   (floor((x)+0.5f))\n"
if windows_rint not in text:
    raise SystemExit("os.h does not have the expected Windows rint definition")
open(os_h, "w").write(text.replace(windows_rint, "#  include <malloc.h>\n"))
with open(os.path.join(build, "fdwrap.c"), "w") as out:
    out.write(WRAPPERS)

renames = ["-Dsin=fd_sin", "-Dcos=fd_cos", "-Datan=fd_atan"]
objects = []
for name in FDLIBM:
    if name.endswith(".c"):
        obj = os.path.join(build, "fd_" + name[:-2] + ".o")
        subprocess.run(["gcc", "-O0", "-fno-strict-aliasing", "-fno-builtin", "-w", "-D__LITTLE_ENDIAN",
                        "-Dfabs=fd_fabs", "-Dcopysign=fd_copysign", "-Dscalbn=fd_scalbn"] + renames
                       + ["-c", os.path.join(download, "fdlibm", name), "-o", obj], check=True)
        objects.append(obj)
exe = os.path.join(work, "vbm.exe" if os.name == "nt" else "vbm")
subprocess.run(["gcc", "-O2", "-ffp-contract=off", "-fno-builtin", "-w"] + renames
               + ["-Dexp=fd_exp", "-Dlog=fd_log", "-Dpow=fd_pow",
                  "-I" + os.path.join(ogg, "include"), "-I" + os.path.join(download, "libvorbis-1.3.7", "include"),
                  "-I" + lib, "-o", exe, os.path.join(here, "vbm.c"), os.path.join(build, "fdwrap.c")]
               + [os.path.join(lib, f + ".c") for f in LIBVORBIS]
               + [os.path.join(ogg, "src", "bitwise.c"), os.path.join(ogg, "src", "framing.c")] + objects + ["-lm"],
               check=True)
print("wrote " + exe)
