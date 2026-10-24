#!/usr/bin/env python3
"""Builds the MPEG-4 Visual reference decoder of ISO/IEC 14496-5 as a black-box oracle: smalt-mpeg4ref.

The reference software of ISO/IEC 14496-5:2001 ("Microsoft MPEG-4 Visual Reference Software", version 2 FDAM1-2.3,
2000-12-13, the decoder of the Simple, Advanced Simple and the other version 1 and 2 profiles) was published in
ISO's Publicly Available Standards tree; that tree is gone, and the archive is downloaded from the Internet Archive's
copy, pinned by SHA-256. Its licence grants the use of the software "for use in hardware or software products
claiming conformance to the MPEG-4 Video" (the header of every source file); Smalt only runs the decoder in tests and
never ships it.

The sources predate standard C++, so they are compiled with compatibility headers (iostream.h and the like, which
include the standard headers and import the stream names into the global namespace), -fpermissive, and the textual
rewrites of modernize(), each explained where it is defined; none changes what the decoder computes.

Usage: python -I build.py <directory>     (with g++ or clang++ on PATH; an MSYS2 UCRT64 shell on Windows)
The decoder lands in <directory>/bin/smalt-mpeg4ref[.exe]; run it as
  smalt-mpeg4ref <bitstream> <output base name> <width> <height>
which writes the decoded pictures as planar 4:2:0 8-bit frames to <output base name>.yuv.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

URL = ('http://web.archive.org/web/20060707164348id_/http://standards.iso.org/ittf/PubliclyAvailableStandards/'
       'ISO_IEC_14496-5_2001_Software_Reference/video.zip')
SHA256 = '73b1aa5858786eeab526c6d04e95066a4a41e7f8b4c037070bbbfa7158fa499f'
ROOT = 'video/natural/microsoft-2.3-001213/'
DEFINES = ['-D__TRACE_AND_STATS_', '-D__DOUBLE_PRECISION_', '-D_OBSS_']
INCLUDES = ['sys', 'sys/encoder', 'sys/decoder', 'type', 'tools/entropy', 'tools', 'tools/sadct', 'vtc/include',
            'vtc/ztq', 'vtc/zte', 'vtc/wavelet', 'vtc/pezw', 'vtc/shape', 'vtc/main']
# The pre-standard headers the sources include, mapped to their standard counterparts.
SHIMS = {
    'iostream.h': ['iostream'],
    'fstream.h': ['fstream', 'iostream'],
    'ostream.h': ['ostream'],
    'istream.h': ['istream'],
    'strstream.h': ['strstream'],
    'strstrea.h': ['strstream'],
    'iomanip.h': ['iomanip'],
}
# macOS has no <malloc.h>; the wavelet and shape sources include it for malloc and free only
DARWIN_SHIMS = {
    'malloc.h': ['stdlib.h'],
}
STREAM_NAMES = ['ios', 'istream', 'ostream', 'iostream', 'ifstream', 'ofstream', 'fstream', 'istrstream',
                'ostrstream', 'strstream', 'streambuf', 'filebuf', 'cin', 'cout', 'cerr', 'clog', 'endl', 'ends',
                'flush', 'dec', 'hex', 'oct', 'strstreambuf', 'setw', 'setfill', 'setprecision', 'ws', 'streampos', 'streamoff',
                'streamsize']


def fetch(work):
    archive = os.path.join(work, 'video.zip')
    if not os.path.isfile(archive) or sha256(archive) != SHA256:
        request = urllib.request.Request(URL, headers={'User-Agent': 'smalt-testdata/1'})
        with urllib.request.urlopen(request, timeout=600) as response, open(archive + '.part', 'wb') as sink:
            shutil.copyfileobj(response, sink, 1 << 20)
        if sha256(archive + '.part') != SHA256:
            sys.exit('the reference software archive does not have the pinned SHA-256')
        os.replace(archive + '.part', archive)
    return archive


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def extract(archive, source):
    if os.path.isdir(source):
        shutil.rmtree(source)
    with zipfile.ZipFile(archive) as zip:
        for name in zip.namelist():
            if not name.startswith(ROOT) or name.endswith('/'):
                continue
            target = os.path.join(source, *name[len(ROOT):].split('/'))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            data = zip.read(name)
            if name.endswith(('.cpp', '.hpp', '.h', '.c')):
                data = modernize(data.replace(b'\r\n', b'\n').decode('latin-1')).encode('latin-1')
            with open(target, 'wb') as sink:
                sink.write(data)


# Syntax that C++98 compilers no longer accept, rewritten without changing what the code computes: a declaration
# whose storage class is followed directly by the declared name ("register i;", "static count = 0, total;") is an
# implicit int in C, which C++ rejects.
IMPLICIT_INT = re.compile(r'\b(register|static|extern|const)\s+(?=[a-z_]\w*\s*[=,;\[])')


# Forward declarations of the stream classes, which the standard library declares as typedefs in namespace std.
STREAM_DECLARATION = re.compile(r'^[ \t]*class[ \t]+(?:i|o|io|if|of|f|str)stream(?:buf)?[ \t]*;[ \t]*$', re.M)


# A friend class declaration once also declared the class in the enclosing scope ("friend injection"); the classes
# are declared up front instead.
FRIEND_CLASS = re.compile(r'\bfriend\s+class\s+([A-Za-z_]\w*)\s*;')


# Text replacements for constructs C++98 compilers reject. decideIntraPred returns the predictor block through
# "const BlockMemory& blkmRet", a reference to a pointer to const Int (BlockMemory is a macro for "Int*") that its
# callers bind to their own Int pointer: the compilers of the time bound the reference to that pointer, which a
# standard compiler refuses (it would bind a temporary, and the callers would never see the predictor), so the
# parameter becomes the reference to an Int pointer the code means.
#
# The other replacements undo the pre-standard scope of a variable declared in a for statement, which lasted to the
# end of the enclosing block: the loops whose variable is read after them declare it before them, and the loops that
# reuse such a variable, which they initialize, declare their own. ios::bin is the pre-standard ios::binary.
#
# The last two accept the version numbers of ISO/IEC 14496-2:2004: the conformance streams of the Advanced Simple
# profile of ISO/IEC 14496-4 signal visual_object_verid and video_object_layer_verid 5, whose syntax for that profile
# is the version 2 syntax this decoder implements, so it reads them as version 2.
REPLACEMENTS = [
    ('if (uiVSOVerID != VSO_VERID)', 'if (uiVSOVerID == 0 || uiVSOVerID > 5)'),
    ('m_volmd.uiVerID = m_pbitstrmIn -> getBits (4); // video_oject_layer_verid',
     'm_volmd.uiVerID = m_pbitstrmIn -> getBits (4); if (m_volmd.uiVerID > 2) m_volmd.uiVerID = 2;'),
    ('decideIntraPred (const BlockMemory& blkmRet', 'decideIntraPred (BlockMemory& blkmRet'),
    ('for( int iLocal = 0; *(m_piSlicePoint + iLocal) >= 0; iLocal++ ) {',
     'int iLocal; for( iLocal = 0; *(m_piSlicePoint + iLocal) >= 0; iLocal++ ) {'),
    ('for( int i = 0; *(m_piSlicePoint + i) >= 0; i++ );', 'int i; for( i = 0; *(m_piSlicePoint + i) >= 0; i++ );'),
    ('for (int i=Y_BLOCK1; i<U_BLOCK; i++) {', 'int i; for (i=Y_BLOCK1; i<U_BLOCK; i++) {'),
    ('for(j=0; j<(iHeightSrc-iExpandYRefFrame/iType);j++){', 'for(int j=0; j<(iHeightSrc-iExpandYRefFrame/iType);j++){'),
    ('for(j=0; j<(iHeightDst-iExpandYRefFrame/iType*2)', 'for(int j=0; j<(iHeightDst-iExpandYRefFrame/iType*2)'),
    ('#define IOSBINARY ios::bin\n', '#define IOSBINARY ios::binary\n'),
    # the decoder was written for a 32-bit long, the type of its coordinates and 32-bit words: with the 64-bit long
    # of Linux and macOS the global motion compensated streams vcon-asp14L0 and vcon-asp14L1 decode differently
    # from Windows, whose decode matches ffmpeg's
    ('typedef unsigned long U32;', 'typedef unsigned int U32;'),
    ('typedef long CoordI;', 'typedef int CoordI;'),
    # the output file names are formatted into 100-byte buffers, which a long output path overflows
    ('Char pchTmp [100];', 'Char pchTmp [4096];'),
    ('static Char pchYUV [100];', 'static Char pchYUV [4096];'),
    ('static Char pchYUV [100], pchSeg [100];', 'static Char pchYUV [4096], pchSeg [4096];'),
]


# A cast to a pointer to member function of a member function's bare name, which only -fpermissive of some compilers
# accepts: the pointer to member is formed with the address-of operator.
MEMBER_POINTER = re.compile(r'(\(Void\s*\(CVideoObjectDecoder::\*\)\([^()]*\)\)\s*)CVideoObjectDecoder::')


def modernize(text):
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    text = MEMBER_POINTER.sub(r'\1&CVideoObjectDecoder::', text)
    text = STREAM_DECLARATION.sub('', text)
    friends = sorted(set(FRIEND_CLASS.findall(text)))
    if friends:
        text = ''.join(f'class {name};\n' for name in friends) + text
    return IMPLICIT_INT.sub(lambda match: match.group(1) + ' int ', text)


def shims(directory):
    """Writes the compatibility headers, and a prelude every source includes first, so that the standard headers are
    read before the sources define their min and max macros."""
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, 'prelude.h'), 'w', encoding='ascii', newline='\n') as sink:
        for header in ('algorithm', 'fstream', 'iomanip', 'iostream', 'strstream', 'cstdio', 'cstdlib', 'cstring',
                       'cmath'):
            sink.write(f'#include <{header}>\n')
        # the stream names the pre-standard headers declared globally, and nothing else of namespace std, whose
        # count, min and max the sources define themselves
        for name in STREAM_NAMES:
            sink.write(f'using std::{name};\n')
    shims_here = dict(SHIMS, **(DARWIN_SHIMS if sys.platform == 'darwin' else {}))
    for name, headers in shims_here.items():
        with open(os.path.join(directory, name), 'w', encoding='ascii', newline='\n') as sink:
            sink.write('#pragma once\n')
            for header in headers:
                sink.write(f'#include <{header}>\n')


# The decoder reads members of objects it allocates with new before writing them (valgrind shows the decoder object
# itself, from its constructor to the B-VOPs), so that its decode would depend on what the allocator's memory last
# held. Every allocation is zeroed, which makes the decode deterministic.
ZERO_NEW = """#include <cstdlib>
#include <new>
void *operator new(std::size_t size) throw(std::bad_alloc) {
    void *memory = std::calloc(1, size ? size : 1);
    if (memory == 0) throw std::bad_alloc();
    return memory;
}
void *operator new[](std::size_t size) throw(std::bad_alloc) { return operator new(size); }
void operator delete(void *memory) throw() { std::free(memory); }
void operator delete[](void *memory) throw() { std::free(memory); }
"""


def decoder_sources(source):
    """Returns (object name, source, extra flags of its rule) for every object of the Makefile's DECODER_OBJECTS."""
    with open(os.path.join(source, 'Makefile'), encoding='latin-1') as makefile:
        text = makefile.read().replace('\r\n', '\n')
    block = re.search(r'^DECODER_OBJECTS\s*=\s*\\\n((?:.*\\\n)*.*\n)', text, re.M).group(1)
    objects = re.findall(r'\$\(OBJDIR\)/([A-Za-z0-9_]+)\.o', block)
    files = {}
    for directory, _, names in os.walk(source):
        for name in names:
            if name.endswith(('.cpp', '.c')):
                files.setdefault(os.path.splitext(name)[0], os.path.join(directory, name))
    rules = {}
    for name, path, extra in re.findall(r'^\$\(OBJDIR\)/([A-Za-z0-9_]+)\.o:\s*\$\(SRCDIR\)/(\S+)\n\t\$\(MK_OBJ\)(.*?)-o',
                                        text, re.M):
        rules[name] = (path, extra.split())
    sources = []
    for name in objects:
        if name in rules:
            path, extra = rules[name]
            sources.append((name, os.path.join(source, *path.split('/')), extra))
        elif name in files:
            sources.append((name, files[name], []))
        else:
            sys.exit(f'no source for {name}.o')
    return sources


def compiler():
    for candidate in ('g++', 'clang++'):
        if shutil.which(candidate):
            return candidate
    sys.exit('g++ or clang++ is needed')


def build(archive, work, cxx, binary, static=False, env=None):
    """Builds the decoder from the archive in the directory work into binary, with the C++ compiler cxx."""
    source = os.path.join(work, 'src')
    extract(archive, source)
    shim = os.path.join(work, 'shim')
    shims(shim)
    objects = os.path.join(work, 'obj')
    os.makedirs(objects, exist_ok=True)
    flags = ['-std=gnu++98', '-fpermissive', '-fno-operator-names', '-w', '-O2', '-fno-strict-aliasing', '-fwrapv']
    flags += DEFINES + ['-include', os.path.join(shim, 'prelude.h'), '-I' + shim]
    flags += ['-I' + os.path.join(source, *path.split('/')) for path in INCLUDES]
    built = []
    failed = []
    for name, path, extra in decoder_sources(source):
        target = os.path.join(objects, name + '.o')
        language = ['-x', 'c++'] if path.endswith('.c') else []
        command = [cxx] + language + flags + extra + ['-c', path, '-o', target]
        result = subprocess.run(command, capture_output=True, text=True, env=env)
        if result.returncode != 0:
            sys.stderr.write(result.stdout + result.stderr)
            failed.append(path)
            continue
        built.append(target)
    zero_new = os.path.join(shim, 'zero_new.cpp')
    with open(zero_new, 'w', encoding='ascii', newline='\n') as sink:
        sink.write(ZERO_NEW)
    target = os.path.join(objects, 'zero_new.o')
    result = subprocess.run([cxx, '-std=gnu++98', '-w', '-O2', '-c', zero_new, '-o', target], capture_output=True,
                            text=True, env=env)
    if result.returncode != 0:
        sys.stderr.write(result.stdout + result.stderr)
        failed.append(zero_new)
    else:
        built.append(target)
    if failed:
        raise SystemExit('compiling failed: ' + ', '.join(failed))
    os.makedirs(os.path.dirname(binary), exist_ok=True)
    link = [cxx, '-o', binary] + built + ['-lm'] + (['-static'] if static else [])
    subprocess.run(link, check=True, env=env)
    return binary


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    work = os.path.abspath(sys.argv[1])
    os.makedirs(work, exist_ok=True)
    windows = os.name == 'nt' or sys.platform == 'msys'
    binary = os.path.join(work, 'bin', 'smalt-mpeg4ref' + ('.exe' if windows else ''))
    print(build(fetch(work), work, compiler(), binary, static=windows))


if __name__ == '__main__':
    main()
