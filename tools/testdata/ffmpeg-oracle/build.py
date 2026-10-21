"""Builds the ffmpeg oracle of Smalt's tests from pinned source releases.

Usage: python tools/testdata/ffmpeg-oracle/build.py --prefix DIR [--jobs N]

Builds ffmpeg 7.1.2 with every external library the tests need (features.tsv next to this script lists the
components), each library a static build of a pinned source release, into one prefix whose bin directory holds
ffmpeg and ffprobe. Runs on Linux (x64 and arm64), on macOS (arm64) and on Windows (x64) under MSYS2, where it is
started by MSYS2's own Python in a UCRT64 shell (tools/testdata/oracles.py does that), so that the configure scripts
run; no prebuilt ffmpeg 7.1.2 has all of the components.

The script installs the build tools it needs: nasm, the autotools, pkg-config, cmake and the zlib and bzip2
headers with the platform's package manager (apt, Homebrew or pacman), meson and ninja in a virtual environment of
its work directory (MSYS2's packages on Windows), and for rav1e a pinned Rust toolchain through rustup and the
cargo-c release binary (MSYS2's Rust and cargo-c on Windows). Every download is verified against its SHA-256.

A finished prefix holds .build-sha256, the SHA-256 of this script. When it matches and ffmpeg is present, the
script does nothing, so a CI cache keyed by the script's hash holds the build; any change to a pin or a recipe
changes the hash and rebuilds.

ffmpeg is a black-box test tool here: it is configured with --enable-gpl --enable-version3 because x264, x265,
Xvid and the opencore/vo AMR libraries need it, and its sources are compiled, never read.
"""
import argparse
import hashlib
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

# name: (version, source URL, SHA-256)
SOURCES = {
    # 7.1.1 decodes some small H.264 pictures wrongly on arm64, even with -cpuflags 0; 7.1.2 does not.
    'ffmpeg': ('7.1.2', 'https://ffmpeg.org/releases/ffmpeg-7.1.2.tar.xz',
               '089bc60fb59d6aecc5d994ff530fd0dcb3ee39aa55867849a2bbc4e555f9c304'),
    'lame': ('3.100', 'https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz',
             'ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e'),
    'opencore-amr': ('0.1.6',
                     'https://downloads.sourceforge.net/project/opencore-amr/opencore-amr/opencore-amr-0.1.6.tar.gz',
                     '483eb4061088e2b34b358e47540b5d495a96cd468e361050fae615b1809dc4a1'),
    'vo-amrwbenc': ('0.1.3',
                    'https://downloads.sourceforge.net/project/opencore-amr/vo-amrwbenc/vo-amrwbenc-0.1.3.tar.gz',
                    '5652b391e0f0e296417b841b02987d3fd33e6c0af342c69542cbb016a71d9d4e'),
    'libogg': ('1.3.5', 'https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.xz',
               'c4d91be36fc8e54deae7575241e03f4211eb102afb3fc0775fbbc1b740016705'),
    'libvorbis': ('1.3.7', 'https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.xz',
                  'b33cc4934322bcbf6efcbacf49e3ca01aadbea4114ec9589d1b1e9d20f72954b'),
    'libtheora': ('1.2.0', 'https://downloads.xiph.org/releases/theora/libtheora-1.2.0.tar.xz',
                  'ebdf77a8f5c0a8f7a9e42323844fa09502b34eb1d1fece7b5f54da41fe2122ec'),
    'speex': ('1.2.1', 'https://downloads.xiph.org/releases/speex/speex-1.2.1.tar.gz',
              '4b44d4f2b38a370a2d98a78329fefc56a0cf93d1c1be70029217baae6628feea'),
    'opus': ('1.5.2', 'https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz',
             '65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1'),
    'libvpx': ('1.15.2', 'https://codeload.github.com/webmproject/libvpx/tar.gz/refs/tags/v1.15.2',
               '26fcd3db88045dee380e581862a6ef106f49b74b6396ee95c2993a260b4636aa'),
    'libwebp': ('1.6.0', 'https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz',
                'e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564'),
    # The head of x264's stable branch (0.164.3108), through the project's GitHub mirror.
    'x264': ('31e19f9', 'https://codeload.github.com/mirror/x264/tar.gz/31e19f92f00c7003fa115047ce50978bc98c3a0d',
             'd053c9d86988d6bc78237ca5205865c5ddf99c98ef4cd9927eec8f6d388f6dd9'),
    'x265': ('4.1', 'https://bitbucket.org/multicoreware/x265_git/downloads/x265_4.1.tar.gz',
             'a31699c6a89806b74b0151e5e6a7df65de4b49050482fe5ebf8a4379d7af8f29'),
    'xvidcore': ('1.3.7', 'https://downloads.xvid.com/downloads/xvidcore-1.3.7.tar.gz',
                 'abbdcbd39555691dd1c9b4d08f0a031376a3b211652c0d8b3b8aa9be1303ce2d'),
    'aom': ('3.12.1', 'https://storage.googleapis.com/aom-releases/libaom-3.12.1.tar.gz',
            '9e9775180dec7dfd61a79e00bda3809d43891aee6b2e331ff7f26986207ea22e'),
    'dav1d': ('1.5.1', 'https://downloads.videolan.org/pub/videolan/dav1d/1.5.1/dav1d-1.5.1.tar.xz',
              '401813f1f89fa8fd4295805aa5284d9aed9bc7fc1fdbe554af4292f64cbabe21'),
    'rav1e': ('0.8.1', 'https://codeload.github.com/xiph/rav1e/tar.gz/refs/tags/v0.8.1',
              '06d1523955fb6ed9cf9992eace772121067cca7e8926988a1ee16492febbe01e'),
    'svt-av1': ('2.3.0', 'https://codeload.github.com/AOMediaCodec/SVT-AV1/tar.gz/refs/tags/v2.3.0',
                'cb5332146a5b407b79c254279053ebbe21c0cabe2141c469e45da1a9f0e6d924'),
    'zimg': ('3.0.6', 'https://codeload.github.com/sekrit-twc/zimg/tar.gz/refs/tags/release-3.0.6',
             'be89390f13a5c9b2388ce0f44a5e89364a20c1c57ce46d382b1fcc3967057577'),
    'libxml2': ('2.13.8', 'https://download.gnome.org/sources/libxml2/2.13/libxml2-2.13.8.tar.xz',
                '277294cb33119ab71b2bc81f2f445e9bc9435b893ad15bb2cd2b0e859a0ee84a'),
    'openjpeg': ('2.5.3', 'https://codeload.github.com/uclouvain/openjpeg/tar.gz/refs/tags/v2.5.3',
                 '368fe0468228e767433c9ebdea82ad9d801a3ad1e4234421f352c8b06e7aa707'),
}

# cargo-c, which builds rav1e's C library, as the project's release binaries: platform: (URL, SHA-256).
CARGO_C = {
    'linux-x64': ('https://github.com/lu-zero/cargo-c/releases/download/v0.10.15/'
                  'cargo-c-x86_64-unknown-linux-musl.tar.gz',
                  '066ff231fffa7c5f22ede897dd968110eddee6f16eff6eaf9415231efce57dbb'),
    'linux-arm64': ('https://github.com/lu-zero/cargo-c/releases/download/v0.10.15/'
                    'cargo-c-aarch64-unknown-linux-musl.tar.gz',
                    '49b08122e215006cd3f4c6e20fa8b475a466b0e757093b3dfd274e63f53e1287'),
    'macos-arm64': ('https://github.com/lu-zero/cargo-c/releases/download/v0.10.15/cargo-c-macos.zip',
                    '0601e2019abe89c8d9a94a6bef57f93ad0cb77e61aac539c4e3aaa653c71ae7f'),
}
RUST_TOOLCHAIN = '1.89.0'
PYTHON_TOOLS = ['meson==1.6.1', 'ninja==1.11.1.1']
APT_PACKAGES = ['nasm', 'autoconf', 'automake', 'libtool', 'pkg-config', 'cmake', 'make', 'g++', 'perl',
                'zlib1g-dev', 'libbz2-dev', 'curl']
BREW_PACKAGES = ['nasm', 'autoconf', 'automake', 'libtool', 'pkgconf', 'cmake']
PACMAN_PACKAGES = ['make', 'autotools', 'diffutils', 'patch'] + ['mingw-w64-ucrt-x86_64-' + name for name in (
    'gcc', 'nasm', 'yasm', 'cmake', 'ninja', 'meson', 'pkgconf', 'rust', 'cargo-c', 'zlib', 'bzip2')]
WINDOWS = sys.platform in ('msys', 'cygwin')
EXE = '.exe' if WINDOWS else ''

# The ffmpeg libraries, in the order they are built (a library is built after those it depends on).
FFMPEG_LIBRARIES = ['libmp3lame', 'libopencore-amrnb', 'libopencore-amrwb', 'libvo-amrwbenc', 'libspeex', 'libopus',
                    'libvorbis', 'libtheora', 'libvpx', 'libwebp', 'libx264', 'libx265', 'libxvid', 'libaom',
                    'libdav1d', 'librav1e', 'libsvtav1', 'libopenjpeg', 'libzimg', 'libxml2']


def current_platform():
    machine = platform.machine().lower()
    arch = 'arm64' if machine in ('arm64', 'aarch64') else 'x64'
    if WINDOWS:
        return 'windows-' + arch
    if sys.platform == 'darwin':
        return 'macos-' + arch
    if sys.platform.startswith('linux'):
        return 'linux-' + arch
    raise SystemExit('the ffmpeg oracle is built on Linux, macOS and Windows (MSYS2) only; on Windows run this '
                     'script with MSYS2 Python in a UCRT64 shell')


def native(path):
    """Returns a path as the native Windows programs of MSYS2 (cmake, meson, cargo) take it, or the path itself."""
    if not WINDOWS:
        return path
    return subprocess.run(['cygpath', '-m', path], capture_output=True, text=True, check=True).stdout.strip()


def run(command, cwd=None, env=None):
    print('+ ' + ' '.join(command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def sudo():
    return [] if WINDOWS or os.geteuid() == 0 else ['sudo']


def digest(path):
    sha = hashlib.sha256()
    with open(path, 'rb') as file:
        for block in iter(lambda: file.read(1 << 20), b''):
            sha.update(block)
    return sha.hexdigest()


def download(url, sha256, directory):
    """Downloads a URL into a directory of its own, failing unless its SHA-256 matches."""
    os.makedirs(directory, exist_ok=True)
    target = os.path.join(directory, 'download')
    last = None
    for attempt in range(5):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'smalt-ffmpeg-oracle'})
            with urllib.request.urlopen(request, timeout=300) as response, open(target, 'wb') as out:
                shutil.copyfileobj(response, out, 1 << 20)
            actual = digest(target)
            if actual != sha256:
                raise SystemExit('%s has SHA-256 %s, expected %s' % (url, actual, sha256))
            return target
        except (OSError, urllib.error.URLError) as error:
            last = error
            print('download of %s failed (%s), retrying' % (url, error), flush=True)
            time.sleep(15 * (attempt + 1))
    raise SystemExit('cannot download %s: %s' % (url, last))


def unpack(archive, directory):
    """Extracts an archive into an empty directory and returns its single top-level directory, or the directory."""
    os.makedirs(directory, exist_ok=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as zipped:
            for member in zipped.infolist():
                name = member.filename
                if name.startswith('/') or '..' in name.split('/'):
                    raise SystemExit('unsafe path in %s: %s' % (archive, name))
                zipped.extract(member, directory)
                mode = (member.external_attr >> 16) & 0o777
                if mode:
                    os.chmod(os.path.join(directory, name), mode)
    else:
        with tarfile.open(archive) as tarred:
            tarred.extractall(directory, filter='tar')
    entries = os.listdir(directory)
    if len(entries) == 1 and os.path.isdir(os.path.join(directory, entries[0])):
        return os.path.join(directory, entries[0])
    return directory


class Build:
    """The state of one build: the prefix, the work directory, the environment and the platform."""

    def __init__(self, prefix, work, jobs):
        self.prefix = prefix
        self.work = work
        self.jobs = str(jobs)
        self.target = current_platform()
        self.macos = self.target.startswith('macos')
        self.windows = self.target.startswith('windows')
        self.arm = self.target.endswith('arm64')
        self.env = dict(os.environ)

    def path(self, *parts):
        return os.path.join(self.prefix, *parts)

    def setup_environment(self):
        include = self.path('include')
        library = self.path('lib')
        flags = '-O2 -fPIC -I' + include
        self.env.update({
            'PKG_CONFIG_PATH': os.path.join(library, 'pkgconfig'),
            'CFLAGS': flags, 'CXXFLAGS': flags, 'CPPFLAGS': '-I' + include, 'LDFLAGS': '-L' + library,
            'PATH': os.pathsep.join([self.path('bin'), os.path.join(self.work, 'venv', 'bin'),
                                     os.path.join(self.work, 'cargo-c'), self.env.get('PATH', '')]),
        })
        self.env.pop('PKG_CONFIG_LIBDIR', None)

    def install_tools(self):
        if self.windows:
            run(['pacman', '-S', '--noconfirm', '--needed'] + PACMAN_PACKAGES)
            return
        if self.macos:
            brew = dict(self.env, HOMEBREW_NO_AUTO_UPDATE='1', HOMEBREW_NO_INSTALL_CLEANUP='1',
                        HOMEBREW_NO_INSTALLED_DEPENDENTS_CHECK='1')
            run(['brew', 'install', '--quiet'] + BREW_PACKAGES, env=brew)
        else:
            run(sudo() + ['apt-get', 'update', '-q'])
            run(sudo() + ['apt-get', 'install', '-y', '-q', '--no-install-recommends'] + APT_PACKAGES)
        run([sys.executable, '-m', 'venv', os.path.join(self.work, 'venv')])
        run([os.path.join(self.work, 'venv', 'bin', 'python'), '-m', 'pip', 'install', '-q'] + PYTHON_TOOLS)

    def source(self, name):
        """Downloads and extracts a pinned source, returning its tree."""
        _, url, sha256 = SOURCES[name]
        archive = download(url, sha256, os.path.join(self.work, 'downloads', name))
        return unpack(archive, os.path.join(self.work, 'src', name))

    def refresh_config_scripts(self, tree):
        """Replaces the config.guess and config.sub of an autotools tree with automake's, which know arm64 macOS."""
        libdir = subprocess.run(['automake', '--print-libdir'], capture_output=True, text=True, check=True,
                                env=self.env).stdout.strip()
        for parent, _, files in os.walk(tree):
            for script in ('config.guess', 'config.sub'):
                if script in files:
                    shutil.copy2(os.path.join(libdir, script), os.path.join(parent, script))

    def autotools(self, tree, *arguments, shared_flag=True, env=None):
        self.refresh_config_scripts(tree)
        if self.macos:
            # The Xiph configure scripts pass -force_cpusubtype_ALL on Darwin, which Apple's current linker rejects.
            script = os.path.join(tree, 'configure')
            with open(script, encoding='utf-8', errors='surrogateescape') as file:
                text = file.read()
            if '-force_cpusubtype_ALL' in text:
                with open(script, 'w', encoding='utf-8', errors='surrogateescape') as file:
                    file.write(text.replace('-force_cpusubtype_ALL', ''))
        env = env or self.env
        command = ['./configure', '--prefix=' + self.prefix]
        if shared_flag:
            command += ['--disable-shared', '--enable-static', '--with-pic']
        run(command + list(arguments), cwd=tree, env=env)
        run(['make', '-j' + self.jobs], cwd=tree, env=env)
        run(['make', 'install'], cwd=tree, env=env)

    def cmake(self, source, build, *arguments, install=True):
        # MSYS2's cmake would pick a Visual Studio generator; Ninja builds with the UCRT64 compilers.
        generator = ['-G', 'Ninja'] if self.windows else []
        run(['cmake', '-S', native(source), '-B', native(build)] + generator + [
            '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_INSTALL_PREFIX=' + native(self.prefix), '-DCMAKE_INSTALL_LIBDIR=lib',
            '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', '-DBUILD_SHARED_LIBS=OFF', '-DCMAKE_POLICY_VERSION_MINIMUM=3.5',
            '-DCMAKE_PREFIX_PATH=' + native(self.prefix)] + list(arguments), env=self.env)
        run(['cmake', '--build', native(build), '--parallel', self.jobs], env=self.env)
        if install:
            run(['cmake', '--install', native(build)], env=self.env)

    def lame(self):
        self.autotools(self.source('lame'), '--disable-frontend', '--disable-decoder')

    def wrapping(self):
        """Returns the environment with -fwrapv: the 3GPP fixed-point code of the AMR libraries relies on signed
        overflow wrapping, which Apple's clang otherwise optimizes differently from GCC (one AMR-NB frame in a hundred
        came out different on macOS)."""
        return dict(self.env, CFLAGS=self.env['CFLAGS'] + ' -fwrapv', CXXFLAGS=self.env['CXXFLAGS'] + ' -fwrapv')

    def opencore_amr(self):
        self.autotools(self.source('opencore-amr'), env=self.wrapping())

    def vo_amrwbenc(self):
        self.autotools(self.source('vo-amrwbenc'), env=self.wrapping())

    def xiph(self):
        self.autotools(self.source('libogg'))
        self.autotools(self.source('libvorbis'))
        # The assembly of libtheora is left out: it does not build on every target, and the fixtures need none.
        self.autotools(self.source('libtheora'), '--disable-asm', '--disable-examples', '--disable-oggtest',
                       '--disable-vorbistest', '--disable-sdltest', '--disable-spec', '--disable-doc')
        self.autotools(self.source('speex'), '--disable-binaries')
        self.autotools(self.source('opus'), '--disable-doc', '--disable-extra-programs')

    def libvpx(self):
        tree = self.source('libvpx')
        command = ['./configure', '--prefix=' + self.prefix, '--enable-static', '--disable-shared', '--enable-pic',
                   '--enable-vp9-highbitdepth', '--disable-examples', '--disable-tools', '--disable-docs',
                   '--disable-unit-tests']
        if self.macos:
            # libvpx names its macOS targets by Darwin version; the newest one it knows serves later releases too.
            command.append('--target=arm64-darwin23-gcc')
        elif self.windows:
            command.append('--target=x86_64-win64-gcc')
        run(command, cwd=tree, env=self.env)
        run(['make', '-j' + self.jobs], cwd=tree, env=self.env)
        run(['make', 'install'], cwd=tree, env=self.env)

    def libwebp(self):
        self.autotools(self.source('libwebp'), '--enable-libwebpmux', '--enable-libwebpdemux', '--disable-gl',
                       '--disable-sdl', '--disable-png', '--disable-jpeg', '--disable-tiff', '--disable-gif',
                       '--disable-wic')

    def x264(self):
        tree = self.source('x264')
        self.refresh_config_scripts(tree)
        run(['./configure', '--prefix=' + self.prefix, '--enable-static', '--enable-pic', '--disable-cli',
             '--bit-depth=all'], cwd=tree, env=self.env)
        run(['make', '-j' + self.jobs], cwd=tree, env=self.env)
        run(['make', 'install'], cwd=tree, env=self.env)

    def x265(self):
        """Builds x265 for 8, 10 and 12 bits and links the three into one libx265.a, as x265's multilib script does."""
        tree = self.source('x265')
        source = os.path.join(tree, 'source')
        lists = os.path.join(source, 'CMakeLists.txt')
        with open(lists, encoding='utf-8') as file:
            text = file.read()
        # CMake 4 rejects OLD for these policies; the compiler test is widened to the id NEW reports for Apple Clang.
        text = text.replace('cmake_policy(SET CMP0025 OLD)', 'cmake_policy(SET CMP0025 NEW)')
        text = text.replace('cmake_policy(SET CMP0054 OLD)', 'cmake_policy(SET CMP0054 NEW)')
        text = text.replace('if(${CMAKE_CXX_COMPILER_ID} STREQUAL "Clang")',
                            'if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")')
        for compiler in ('Intel', 'GNU'):
            text = text.replace('if(${CMAKE_CXX_COMPILER_ID} STREQUAL "%s")' % compiler,
                                'if(CMAKE_CXX_COMPILER_ID STREQUAL "%s")' % compiler)
        with open(lists, 'w', encoding='utf-8') as file:
            file.write(text)
        common = ['-DENABLE_SHARED=OFF', '-DENABLE_CLI=OFF']
        high = ['-DHIGH_BIT_DEPTH=ON', '-DEXPORT_C_API=OFF'] + common
        if self.arm:
            # x265's arm64 assembly covers 8 bits only.
            high.append('-DENABLE_ASSEMBLY=OFF')
        build12 = os.path.join(self.work, 'x265-12')
        build10 = os.path.join(self.work, 'x265-10')
        build8 = os.path.join(self.work, 'x265-8')
        self.cmake(source, build12, '-DMAIN12=ON', *high, install=False)
        self.cmake(source, build10, *high, install=False)
        os.makedirs(build8, exist_ok=True)
        shutil.copy2(os.path.join(build10, 'libx265.a'), os.path.join(build8, 'libx265_main10.a'))
        shutil.copy2(os.path.join(build12, 'libx265.a'), os.path.join(build8, 'libx265_main12.a'))
        self.cmake(source, build8, '-DEXTRA_LIB=x265_main10.a;x265_main12.a', '-DEXTRA_LINK_FLAGS=-L.',
                   '-DLINKED_10BIT=ON', '-DLINKED_12BIT=ON', *common, install=False)
        os.replace(os.path.join(build8, 'libx265.a'), os.path.join(build8, 'libx265_main.a'))
        parts = ['libx265_main.a', 'libx265_main10.a', 'libx265_main12.a']
        if self.macos:
            run(['libtool', '-static', '-o', 'libx265.a'] + parts, cwd=build8, env=self.env)
        else:
            script = 'CREATE libx265.a\n' + ''.join('ADDLIB %s\n' % part for part in parts) + 'SAVE\nEND\n'
            print('+ ar -M (%s)' % ', '.join(parts), flush=True)
            subprocess.run(['ar', '-M'], input=script, text=True, cwd=build8, env=self.env, check=True)
        run(['cmake', '--install', build8], env=self.env)

    def xvid(self):
        tree = self.source('xvidcore')
        generic = os.path.join(tree, 'build', 'generic')
        self.refresh_config_scripts(tree)
        # Xvid's assembly is x86 only; the C build encodes the same way on every platform. Xvid defines its own
        # bool, which C23 (the default of GCC 15) makes a keyword.
        env = dict(self.env, CFLAGS=self.env['CFLAGS'] + ' -std=gnu17')
        run(['./configure', '--prefix=' + self.prefix, '--disable-assembly'], cwd=generic, env=env)
        run(['make'], cwd=generic, env=env)
        run(['make', 'install'], cwd=generic, env=env)
        # Xvid always builds a shared library too; removing it makes ffmpeg link the static one, which MinGW
        # builds name xvidcore.a.
        library = self.path('lib')
        if os.path.isfile(os.path.join(library, 'xvidcore.a')):
            os.replace(os.path.join(library, 'xvidcore.a'), os.path.join(library, 'libxvidcore.a'))
        for directory in (library, self.path('bin')):
            for name in os.listdir(directory) if os.path.isdir(directory) else ():
                if 'xvidcore' in name and name != 'libxvidcore.a':
                    os.remove(os.path.join(directory, name))

    def aom(self):
        # libaom's nasm check does not recognize the help text of MSYS2's nasm 3, so it assembles with yasm there.
        assembler = ['-DENABLE_NASM=OFF'] if self.windows else []
        self.cmake(self.source('aom'), os.path.join(self.work, 'aom-build'), '-DENABLE_DOCS=0', '-DENABLE_EXAMPLES=0',
                   '-DENABLE_TESTS=0', '-DENABLE_TESTDATA=0', '-DENABLE_TOOLS=0', *assembler)

    def dav1d(self):
        tree = self.source('dav1d')
        build = os.path.join(self.work, 'dav1d-build')
        run(['meson', 'setup', native(build), native(tree), '--prefix=' + native(self.prefix), '--libdir=lib',
             '--buildtype=release', '--default-library=static', '-Denable_tools=false', '-Denable_tests=false'],
            env=self.env)
        run(['ninja', '-C', native(build), 'install'], env=self.env)

    def ensure_rust(self):
        """Puts the pinned Rust toolchain on PATH through rustup, installing rustup when the runner has none."""
        cargo_bin = os.path.join(os.path.expanduser('~'), '.cargo', 'bin')
        self.env['PATH'] = cargo_bin + os.pathsep + self.env['PATH']
        if shutil.which('rustup', path=self.env['PATH']) is None:
            installer = os.path.join(self.work, 'rustup-init.sh')
            urllib.request.urlretrieve('https://sh.rustup.rs', installer)
            run(['sh', installer, '-y', '--profile', 'minimal', '--default-toolchain', 'none', '--no-modify-path'],
                env=self.env)
        run(['rustup', 'toolchain', 'install', RUST_TOOLCHAIN, '--profile', 'minimal'], env=self.env)
        self.env['RUSTUP_TOOLCHAIN'] = RUST_TOOLCHAIN

    def rav1e(self):
        if not self.windows:
            self.ensure_rust()
            url, sha256 = CARGO_C[self.target]
            cargo_c = os.path.join(self.work, 'cargo-c')
            unpack(download(url, sha256, os.path.join(self.work, 'downloads', 'cargo-c')), cargo_c)
            for name in os.listdir(cargo_c):
                os.chmod(os.path.join(cargo_c, name), 0o755)
        tree = self.source('rav1e')
        run(['cargo', 'cinstall', '--release', '--locked', '--prefix=' + native(self.prefix),
             '--libdir=' + native(self.path('lib')), '--library-type=staticlib'], cwd=tree, env=self.env)

    def svt_av1(self):
        self.cmake(self.source('svt-av1'), os.path.join(self.work, 'svt-build'), '-DBUILD_APPS=OFF', '-DBUILD_DEC=OFF',
                   '-DBUILD_TESTING=OFF')

    def openjpeg(self):
        self.cmake(self.source('openjpeg'), os.path.join(self.work, 'openjpeg-build'), '-DBUILD_CODEC=OFF',
                   '-DBUILD_TESTING=OFF')

    def zimg(self):
        tree = self.source('zimg')
        run(['./autogen.sh'], cwd=tree, env=self.env)
        self.autotools(tree)

    def libxml2(self):
        # The DASH demuxer is all ffmpeg needs it for: UTF-8 manifests, no compression, no network.
        self.autotools(self.source('libxml2'), '--without-python', '--without-zlib', '--without-lzma',
                       '--without-icu', '--without-iconv', '--without-http', '--without-ftp', '--without-readline',
                       '--without-modules')

    def ffmpeg(self):
        tree = self.source('ffmpeg')
        runtime = '-lc++' if self.macos else '-lstdc++ -lpthread -lm'
        # A static executable on Windows needs none of the MinGW runtime DLLs at run time.
        static = ' -static' if self.windows else ''
        # Autodetection is off, so that the build has the same components on every runner whatever else the runner
        # has installed; zlib (PNG, TIFF deflate) and bzip2 come from the system.
        command = ['./configure', '--prefix=' + self.prefix, '--pkg-config-flags=--static',
                   '--extra-cflags=-I' + self.path('include'), '--extra-ldflags=-L' + self.path('lib') + static,
                   '--extra-libs=' + runtime, '--enable-gpl', '--enable-version3', '--disable-shared',
                   '--enable-static', '--disable-debug', '--disable-doc', '--disable-ffplay', '--disable-autodetect',
                   '--enable-zlib', '--enable-bzlib'] + ['--enable-' + library for library in FFMPEG_LIBRARIES]
        run(command, cwd=tree, env=self.env)
        run(['make', '-j' + self.jobs], cwd=tree, env=self.env)
        run(['make', 'install'], cwd=tree, env=self.env)
        # The bin directory goes on PATH: only ffmpeg and ffprobe may be there, not the tools some libraries install.
        for name in os.listdir(self.path('bin')):
            if name not in ('ffmpeg' + EXE, 'ffprobe' + EXE):
                os.remove(self.path('bin', name))

    def build(self):
        self.install_tools()
        self.setup_environment()
        self.lame()
        self.opencore_amr()
        self.vo_amrwbenc()
        self.xiph()
        self.libvpx()
        self.libwebp()
        self.x264()
        self.x265()
        self.xvid()
        self.aom()
        self.dav1d()
        self.rav1e()
        self.svt_av1()
        self.openjpeg()
        self.zimg()
        self.libxml2()
        self.ffmpeg()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--prefix', required=True)
    parser.add_argument('--jobs', type=int, default=os.cpu_count() or 2)
    arguments = parser.parse_args()
    prefix = os.path.abspath(os.path.expanduser(arguments.prefix))
    stamp = os.path.join(prefix, '.build-sha256')
    key = digest(os.path.abspath(__file__))
    executables = [os.path.join(prefix, 'bin', name + EXE) for name in ('ffmpeg', 'ffprobe')]
    if os.path.isfile(stamp) and open(stamp).read().strip() == key and all(map(os.path.isfile, executables)):
        print('the ffmpeg oracle in %s is up to date' % prefix)
        return 0
    shutil.rmtree(prefix, ignore_errors=True)
    os.makedirs(prefix)
    with tempfile.TemporaryDirectory(prefix='smalt-ffmpeg-') as work:
        Build(prefix, work, arguments.jobs).build()
    run([executables[0], '-hide_banner', '-version'])
    with open(stamp, 'w') as file:
        file.write(key + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
