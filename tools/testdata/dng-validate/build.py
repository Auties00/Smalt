#!/usr/bin/env python3
"""Builds dng_validate, the validator of Adobe's DNG SDK, as a black-box oracle of the DNG encoder's tests.

The DNG SDK 1.7.1 (build 2768) is downloaded from Adobe, pinned by SHA-256. Its licence (LICENSE.txt in the archive)
grants a "non-exclusive, worldwide, royalty free license to use, reproduce, prepare derivative works from, publicly
display, publicly perform, distribute and sublicense the Software for any purpose"; Smalt only runs the validator in
tests and never ships it. The archive bundles the libraries the validator links: IJG libjpeg 9c (the JPEG
previews), zlib 1.2.11 (the XMP SDK's copy, for Deflate tiles; macOS links its own), libjxl (JPEG XL tiles and
previews, with its highway, brotli and skcms copies) and the XMP SDK. The XMP SDK is left out (qDNGUseXMP=0: the validator then neither
reads nor checks XMP packets, which the encoder does not write; the two statements of dng_jxl.cpp that use XMP
without that guard are put under it, and the two sources that need the XMP SDK whole are not compiled); libjxl is
built with CMake, libjpeg, zlib and the SDK are compiled directly with the defines of the SDK projects' "Validate
Release" configuration, on the SDK's POSIX platform everywhere.

Usage: python -I build.py --prefix <directory>     (with a C and C++ compiler and CMake on PATH; an MSYS2 UCRT64
shell on Windows)
The validator lands in <directory>/bin/dng_validate[.exe]; run it as
  dng_validate [-v] <file.dng>
It prints "Validation complete" and exits 0 when the file passes; warnings and errors are printed as lines starting
with "*** Warning" and "*** Error".
"""
import argparse
import concurrent.futures
import hashlib
import os
import platform
import shutil
import subprocess
import sys
import urllib.request
import zipfile

URL = 'https://download.adobe.com/pub/adobe/dng/dng_sdk_1_7_1_2768_20261013.zip'
SHA256 = 'c1d3a545ed45b150e59af2e6aa0695c1551f0e2bed34ad87436898a05194d2b1'
ROOT = 'dng_sdk_1_7_1/'
# the libjpeg 9c library sources (filelist.txt, "the JPEG library proper"), without the alternative memory managers
LIBJPEG = ['jaricom', 'jcapimin', 'jcapistd', 'jcarith', 'jccoefct', 'jccolor', 'jcdctmgr', 'jchuff', 'jcinit',
           'jcmainct', 'jcmarker', 'jcmaster', 'jcomapi', 'jcparam', 'jcprepct', 'jcsample', 'jctrans', 'jdapimin',
           'jdapistd', 'jdarith', 'jdatadst', 'jdatasrc', 'jdcoefct', 'jdcolor', 'jddctmgr', 'jdhuff', 'jdinput',
           'jdmainct', 'jdmarker', 'jdmaster', 'jdmerge', 'jdpostct', 'jdsample', 'jdtrans', 'jerror', 'jfdctflt',
           'jfdctfst', 'jfdctint', 'jidctflt', 'jidctfst', 'jidctint', 'jmemmgr', 'jmemnobs', 'jquant1', 'jquant2',
           'jutils']
# the zlib 1.2.11 library sources the XMP SDK's copy holds, for the Deflate tiles
ZLIB = ['adler32', 'compress', 'crc32', 'deflate', 'infback', 'inffast', 'inflate', 'inftrees', 'trees', 'uncompr',
        'zutil']
ZLIB_DIR = 'xmp/toolkit/third-party/zlib/'
# the CMake options of a static libjxl without its tools, tests and optional codecs
JXL_OPTIONS = ['-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=OFF', '-DBUILD_TESTING=OFF',
               '-DJPEGXL_ENABLE_TOOLS=OFF', '-DJPEGXL_ENABLE_DOXYGEN=OFF', '-DJPEGXL_ENABLE_MANPAGES=OFF',
               '-DJPEGXL_ENABLE_BENCHMARK=OFF', '-DJPEGXL_ENABLE_EXAMPLES=OFF', '-DJPEGXL_ENABLE_JNI=OFF',
               '-DJPEGXL_ENABLE_SJPEG=OFF', '-DJPEGXL_ENABLE_OPENEXR=OFF', '-DJPEGXL_ENABLE_JPEGLI=OFF',
               '-DJPEGXL_ENABLE_SKCMS=ON', '-DJPEGXL_BUNDLE_LIBPNG=OFF', '-DJPEGXL_ENABLE_TRANSCODE_JPEG=ON',
               '-DCMAKE_POSITION_INDEPENDENT_CODE=ON']
JXL_LIBRARIES = ['lib/libjxl.a', 'lib/libjxl_cms.a', 'lib/libjxl_threads.a', 'third_party/brotli/libbrotlienc.a',
                 'third_party/brotli/libbrotlidec.a', 'third_party/brotli/libbrotlicommon.a',
                 'third_party/highway/libhwy.a']


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as file:
        for block in iter(lambda: file.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def fetch(work):
    archive = os.path.join(work, 'dng_sdk.zip')
    if not os.path.isfile(archive) or sha256(archive) != SHA256:
        request = urllib.request.Request(URL, headers={'User-Agent': 'smalt-testdata/1'})
        with urllib.request.urlopen(request, timeout=600) as response, open(archive + '.part', 'wb') as sink:
            shutil.copyfileobj(response, sink)
        os.replace(archive + '.part', archive)
        if sha256(archive) != SHA256:
            raise SystemExit('the DNG SDK archive does not match its SHA-256')
    return archive


def extract(archive, work):
    tree = os.path.join(work, 'sdk')
    if os.path.isdir(tree):
        shutil.rmtree(tree)
    with zipfile.ZipFile(archive) as source:
        for name in source.namelist():
            if name.startswith((ROOT + 'dng_sdk/source/', ROOT + 'libjpeg/', ROOT + 'libjxl/', ROOT + ZLIB_DIR)) \
                    or name == ROOT + 'LICENSE.txt':
                source.extract(name, tree)
    return os.path.join(tree, ROOT.rstrip('/'))


# The sources that need the XMP SDK as a whole, which the validator does not use: the XMP SDK bridge and the
# metadata updater of DNG files in place
XMP_SOURCES = ['dng_xmp_sdk.cpp', 'dng_update_meta.cpp']


def wrap_block(text, anchor):
    """Puts the statement that starts at anchor, up to its closing brace, under #if qDNGUseXMP."""
    start = text.index(anchor)
    line = text.rindex('\n', 0, start) + 1
    depth = 0
    position = text.index('{', start)
    while True:
        if text[position] == '{':
            depth += 1
        elif text[position] == '}':
            depth -= 1
            if depth == 0:
                break
        position += 1
    end = text.index('\n', position) + 1
    return text[:line] + '#if qDNGUseXMP\n' + text[line:end] + '#endif\n' + text[end:]


def wrap_lines(text, first, last):
    """Puts the lines from the one holding first to the one holding last under #if qDNGUseXMP."""
    start = text.rindex('\n', 0, text.index(first)) + 1
    end = text.index('\n', text.index(last, start)) + 1
    return text[:start] + '#if qDNGUseXMP\n' + text[start:end] + '#endif\n' + text[end:]


def without_xmp(source):
    """Guards the XMP code of the JPEG XL support, which the SDK leaves unguarded, with qDNGUseXMP."""
    path = os.path.join(source, 'dng_jxl.cpp')
    with open(path, encoding='utf-8', errors='surrogateescape') as file:
        text = file.read()
    text = wrap_block(text, 'if (includeXMP && metadata && metadata->GetXMP ())')
    for statement in ('dng_xmp xmp (host.Allocator ());', 'xmp.Parse (host, data.data (), count);'):
        text = wrap_lines(text, statement, statement)
    with open(path, 'w', encoding='utf-8', errors='surrogateescape', newline='') as file:
        file.write(text)


def run(command, **kwargs):
    print('+ ' + ' '.join(command), flush=True)
    subprocess.run(command, check=True, **kwargs)


# MinGW lacks posix_memalign; its malloc already returns 16-byte aligned blocks, the alignment the SDK asks for, and
# the SDK frees them with free
MINGW_SHIM = """#include <stdlib.h>
static inline int posix_memalign(void **pointer, size_t alignment, size_t size) {
    (void) alignment;
    *pointer = malloc(size);
    return *pointer ? 0 : 12;
}
"""


def platform_define(work, windows):
    """Returns the platform defines: the SDK's POSIX platform (qLinux) everywhere, which needs nothing beyond
    POSIX, rather than its macOS and Windows platforms, which need Apple's frameworks and MSVC."""
    if windows:
        # The SDK's Windows build emulates pthreads, which clashes with MinGW's winpthreads
        shim = os.path.join(work, 'mingw-shim.h')
        with open(shim, 'w', encoding='ascii') as file:
            file.write(MINGW_SHIM)
        return ['-DqLinux=1', '-include', shim]
    return ['-DqLinux=1']


def compile_all(jobs, compiler, flags, sources, objects, env):
    os.makedirs(objects, exist_ok=True)
    outputs = []
    commands = []
    for source in sources:
        output = os.path.join(objects, os.path.splitext(os.path.basename(source))[0] + '.o')
        outputs.append(output)
        commands.append([compiler] + flags + ['-c', source, '-o', output])
    with concurrent.futures.ThreadPoolExecutor(jobs) as pool:
        failures = [command for command, code in zip(commands, pool.map(
            lambda command: subprocess.run(command, env=env).returncode, commands)) if code != 0]
    if failures:
        raise SystemExit('compilation failed: ' + ' '.join(failures[0]))
    return outputs


def build(archive, work, executable, jobs=None, env=None, cc=None, cxx=None):
    """Builds dng_validate from the SDK archive (already checked against SHA256) into executable."""
    jobs = jobs or os.cpu_count() or 2
    environment = dict(env or os.environ)
    cc = cc or environment.get('CC', 'gcc')
    cxx = cxx or environment.get('CXX', 'g++')
    tree = extract(archive, work)
    jxl_build = os.path.join(work, 'jxl')
    generator = ['-G', 'Ninja'] if shutil.which('ninja', path=environment.get('PATH')) else []
    run(['cmake', '-S', os.path.join(tree, 'libjxl', 'libjxl'), '-B', jxl_build] + generator
        + ['-DCMAKE_C_COMPILER=' + cc, '-DCMAKE_CXX_COMPILER=' + cxx] + JXL_OPTIONS, env=environment)
    run(['cmake', '--build', jxl_build, '-j', str(jobs), '--target', 'jxl', 'jxl_threads'], env=environment)
    libjpeg = os.path.join(tree, 'libjpeg')
    shutil.copy2(os.path.join(libjpeg, 'jconfig.txt'), os.path.join(libjpeg, 'jconfig.h'))
    jpeg_objects = compile_all(jobs, cc, ['-O2', '-w', '-I' + libjpeg],
                               [os.path.join(libjpeg, name + '.c') for name in LIBJPEG],
                               os.path.join(work, 'jpeg-objects'), environment)
    zlib = os.path.join(tree, *ZLIB_DIR.rstrip('/').split('/'))
    darwin = sys.platform == 'darwin'
    # macOS uses its own zlib: the copy's zutil.h takes the platform for the classic Mac OS and breaks stdio.h
    zlib_objects = [] if darwin else compile_all(jobs, cc, ['-O2', '-w', '-I' + zlib], [
        os.path.join(zlib, name + '.c') for name in ZLIB], os.path.join(work, 'zlib-objects'), environment)
    source = os.path.join(tree, 'dng_sdk', 'source')
    without_xmp(source)
    sdk_sources = sorted(os.path.join(source, name) for name in os.listdir(source)
                         if name.endswith('.cpp') and name not in XMP_SOURCES)
    windows = sys.platform == 'win32' or environment.get('MSYSTEM')
    defines = ['-DqDNGValidateTarget=1', '-DqDNGUseLibJPEG=1', '-DqDNGUseXMP=0', '-DqDNGXMPFiles=0',
               '-DqDNGXMPDocOps=0', '-DqDNGThreadSafe=1', '-DNDEBUG'] + platform_define(work, windows)
    includes = (['-I' + source, '-I' + libjpeg] + ([] if darwin else ['-I' + zlib])
                + ['-I' + os.path.join(tree, 'libjxl', 'libjxl', 'lib', 'include'),
                   '-I' + os.path.join(tree, 'libjxl', 'client_projects', 'include'),
                   '-I' + os.path.join(jxl_build, 'lib', 'include')])
    sdk_objects = compile_all(jobs, cxx, ['-O2', '-std=c++17', '-w'] + defines + includes, sdk_sources,
                              os.path.join(work, 'sdk-objects'), environment)
    os.makedirs(os.path.dirname(executable), exist_ok=True)
    libraries = [os.path.join(jxl_build, *library.split('/')) for library in JXL_LIBRARIES]
    if windows:
        system = ['-static', '-lpthread']
    elif darwin:
        system = ['-lz', '-lm']
    else:
        system = ['-lpthread', '-lm']
    run([cxx, '-o', executable] + sdk_objects + jpeg_objects + zlib_objects + libraries + system, env=environment)
    shutil.copy2(os.path.join(tree, 'LICENSE.txt'), os.path.join(os.path.dirname(executable), 'LICENSE-dng-sdk.txt'))
    print('built ' + executable + ' on ' + platform.machine())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--prefix', required=True, help='the installation directory')
    parser.add_argument('--work', help='the build directory (default: <prefix>/build)')
    parser.add_argument('--jobs', type=int, default=os.cpu_count() or 2, help='the parallel compilations')
    arguments = parser.parse_args()
    prefix = os.path.abspath(arguments.prefix)
    work = os.path.abspath(arguments.work or os.path.join(prefix, 'build'))
    os.makedirs(work, exist_ok=True)
    windows = sys.platform == 'win32' or os.environ.get('MSYSTEM')
    build(fetch(work), work, os.path.join(prefix, 'bin', 'dng_validate' + ('.exe' if windows else '')),
          arguments.jobs)
    if not arguments.work:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    main()
