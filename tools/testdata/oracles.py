"""Installs and checks the black-box oracle tools of Smalt's tests, as tools/testdata/oracles.tsv pins them.

Usage:
  python tools/testdata/oracles.py install [--github]   install the tools of this platform
  python tools/testdata/oracles.py versions [--out F]   print (and write) the version of every installed tool
  python tools/testdata/oracles.py required             print the executables this platform provisions

The platform is linux-x64, linux-arm64, windows-x64 or macos-arm64. Downloads are verified against their SHA-256
and kept in <root>/downloads, extracted and built tools in <root>/<platform> (root: ~/.smalt/oracles, or
SMALT_ORACLES), so a CI cache of the root skips everything but the package managers. Package-manager tools
(apt, Homebrew, MSYS2) are installed on every run and their versions recorded by "versions".

With --github, install appends the tool directories to GITHUB_PATH (in increasing precedence, so the pinned
directories come first), and sets in GITHUB_ENV SMALT_TEST_REQUIRE_ORACLES to the provisioned executables, which
makes the test kit fail instead of skipping when one of them is missing, and the library path of tools whose
shared libraries are not found otherwise (Linux; macOS executables get an rpath). install fails when any tool of
the platform cannot be installed; versions fails when a provisioned executable is not on PATH or resolves outside
the directory that provides it, or when ffmpeg lacks, or cannot run, a component of
tools/testdata/ffmpeg-oracle/features.tsv (the checks of tools/testdata/ffmpeg-oracle/check.py).
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
import urllib.error
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, 'oracles.tsv')
# The library sources of libpng 1.6, and the macros that turn off its SIMD filters.
LIBPNG_SOURCES = ['png.c', 'pngerror.c', 'pngget.c', 'pngmem.c', 'pngpread.c', 'pngread.c', 'pngrio.c',
                  'pngrtran.c', 'pngrutil.c', 'pngset.c', 'pngtrans.c', 'pngwio.c', 'pngwrite.c', 'pngwtran.c',
                  'pngwutil.c']
LIBPNG_SIMD = ['PNG_ARM_NEON_OPT', 'PNG_INTEL_SSE_OPT', 'PNG_MIPS_MSA_OPT', 'PNG_MIPS_MMI_OPT',
               'PNG_POWERPC_VSX_OPT', 'PNG_LOONGARCH_LSX_OPT', 'PNG_RISCV_RVV_OPT']
COLUMNS = ['platform', 'package', 'version', 'method', 'source', 'sha256', 'path', 'provides']
WINDOWS = os.name == 'nt'
MSYS2 = os.environ.get('SMALT_MSYS2', 'C:/msys64')
EXE = '.exe' if WINDOWS else ''
PACKAGE_MANAGERS = ('apt', 'brew', 'msys2')
SYSTEM_DIRECTORIES = ('/bin', '/usr/bin', '/usr/local/bin')

# The version option of each executable; the first non-empty output line is recorded.
VERSION_ARGS = {
    'ffmpeg': ['-version'], 'ffprobe': ['-version'], 'cwebp': ['-version'], 'dwebp': ['-version'],
    'webpmux': ['-version'], 'img2webp': ['-version'], 'gif2webp': ['-version'], 'webpinfo': ['-version'],
    'cjxl': ['--version'], 'djxl': ['--version'], 'opj_compress': ['-h'], 'opj_decompress': ['-h'], 'opj_dump': ['-h'],
    'cjpeg': ['-version'], 'djpeg': ['-version'], 'jpegtran': ['-version'], 'exiftool': ['-ver'],
    'flac': ['--version'], 'opusenc': ['--version'], 'opusdec': ['--version'], 'opus_compare': [],
    'tiffcp': [], 'tiffinfo': ['-v'], 'tiffdump': [], 'tiff2rgba': [], 'MP4Box': ['-version'],
    'mkvmerge': ['--version'], 'mkvinfo': ['--version'], 'mkvalidator': ['--version'], 'tsp': ['--version'],
    'sox': ['--version'], 'smalt-pngdump': [], 'smalt-lcmsdump': [],
}
VERSION_MARKERS = ('version', 'libtiff', 'opj_', 'usage', 'exiftool', ' v', 'copyright')


def current_platform():
    machine = platform.machine().lower()
    arch = 'arm64' if machine in ('arm64', 'aarch64') else 'x64'
    if WINDOWS:
        return 'windows-' + arch
    if sys.platform == 'darwin':
        return 'macos-' + arch
    return 'linux-' + arch


def root():
    return os.environ.get('SMALT_ORACLES') or os.path.join(os.path.expanduser('~'), '.smalt', 'oracles')


def rows(target):
    found = []
    with open(MANIFEST, encoding='utf-8') as file:
        for line in file:
            line = line.rstrip('\r\n')
            if not line or line.startswith('#'):
                continue
            values = line.split('\t')
            if len(values) != len(COLUMNS):
                raise SystemExit('oracles.tsv: %d columns instead of %d: %s' % (len(values), len(COLUMNS), line))
            row = dict(zip(COLUMNS, values))
            if row['platform'] == target:
                found.append(row)
    if not found:
        raise SystemExit('oracles.tsv has no row for platform ' + target)
    return found


def provided(row):
    """Returns (name, file) pairs of the executables a row provides."""
    pairs = []
    for entry in row['provides'].split(','):
        name, _, file = entry.partition('=')
        pairs.append((name, file or name))
    return pairs


def run(command, cwd=None, env=None, shell=False):
    print('+ ' + (command if isinstance(command, str) else ' '.join(command)), flush=True)
    subprocess.run(command, cwd=cwd, env=env, shell=shell, check=True)


def sudo():
    return [] if WINDOWS or os.geteuid() == 0 else ['sudo']


def download(url, sha256):
    target_dir = os.path.join(root(), 'downloads', sha256)
    name = os.path.basename(url.rstrip('/'))
    if name == 'download':
        name = os.path.basename(url.rstrip('/')[:-len('/download')])
    target = os.path.join(target_dir, name)
    if os.path.isfile(target) and digest(target) == sha256:
        return target
    os.makedirs(target_dir, exist_ok=True)
    last = None
    for attempt in range(5):
        partial = target + '.part'
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'smalt-oracles'})
            with urllib.request.urlopen(request, timeout=120) as response, open(partial, 'wb') as out:
                shutil.copyfileobj(response, out, 1 << 20)
            actual = digest(partial)
            if actual != sha256:
                raise SystemExit('%s has SHA-256 %s, expected %s' % (url, actual, sha256))
            os.replace(partial, target)
            return target
        except (OSError, urllib.error.URLError) as error:
            last = error
            print('download of %s failed (%s), retrying' % (url, error), flush=True)
            import time
            time.sleep(10 * (attempt + 1))
    raise SystemExit('cannot download %s: %s' % (url, last))


def digest(path):
    sha = hashlib.sha256()
    with open(path, 'rb') as file:
        for block in iter(lambda: file.read(1 << 20), b''):
            sha.update(block)
    return sha.hexdigest()


def extract(archive, directory):
    """Extracts a zip or tar archive, keeping the executable bits of tar members."""
    os.makedirs(directory, exist_ok=True)
    if archive.endswith('.zip') or zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as zipped:
            for member in zipped.infolist():
                name = member.filename.replace('\\', '/')
                if name.startswith('/') or '..' in name.split('/'):
                    raise SystemExit('unsafe path in %s: %s' % (archive, name))
                path = os.path.join(directory, *name.split('/'))
                if name.endswith('/'):
                    os.makedirs(path, exist_ok=True)
                    continue
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with zipped.open(member) as source, open(path, 'wb') as out:
                    shutil.copyfileobj(source, out)
                mode = (member.external_attr >> 16) & 0o777
                if mode and not WINDOWS:
                    os.chmod(path, mode)
        return
    with tarfile.open(archive) as tarred:
        tarred.extractall(directory, filter='tar')


def home(row):
    return os.path.join(root(), row['platform'], '%s-%s' % (row['package'], row['version']))


def bin_dir(target):
    return os.path.join(root(), target, 'bin')


def install_archive(row):
    directory = home(row)
    marker = os.path.join(directory, '.installed')
    path, _, _ = row['path'].partition(';')
    if not os.path.isfile(marker) or open(marker).read() != row['sha256']:
        shutil.rmtree(directory, ignore_errors=True)
        extract(download(row['source'], row['sha256']), directory)
        inner, bang, rest = path.partition('!')
        if bang:
            extract(os.path.join(directory, inner), directory)
            path = rest
        library = library_dir(row)
        if library and sys.platform == 'darwin':
            tools = os.path.join(directory, *path.split('/'))
            for name, file in provided(row):
                executable = os.path.join(tools, file)
                rpath = '@executable_path/' + os.path.relpath(library, tools).replace(os.sep, '/')
                run(['install_name_tool', '-add_rpath', rpath, executable])
                run(['codesign', '--force', '--sign', '-', executable])
        with open(marker, 'w') as file:
            file.write(row['sha256'])
    else:
        path = path.partition('!')[2] or path
    tools = os.path.join(directory, *path.split('/'))
    for name, file in provided(row):
        source = os.path.join(tools, file + EXE)
        if not os.path.isfile(source) and not WINDOWS:
            source = os.path.join(tools, file)
        target = os.path.join(tools, name + EXE)
        if name != file and not os.path.isfile(target):
            shutil.copy2(source, target)
        if not WINDOWS and os.path.isfile(target):
            os.chmod(target, os.stat(target).st_mode | 0o755)
    return tools


def script_home(row):
    """Returns the prefix of a script row, next to the root so that its CI cache can be kept apart."""
    return os.path.join(os.path.dirname(root()), '%s-%s' % (row['package'], row['version']))


def install_script(row):
    """Runs the build script of a row (a path from the repository root) with the row's prefix; on Windows with
    MSYS2's own Python in a UCRT64 login shell, so that the script can run configure scripts."""
    script = os.path.join(os.path.dirname(os.path.dirname(HERE)), *row['source'].split('/'))
    home = script_home(row)
    if WINDOWS:
        bash = os.path.join(MSYS2, 'usr', 'bin', 'bash.exe')
        env = dict(os.environ, MSYSTEM='UCRT64', CHERE_INVOKING='1')
        run([bash, '-lc', 'pacman -S --noconfirm --needed python'], env=env)
        run([bash, '-lc', 'python3 "$(cygpath -u "$0")" --prefix "$(cygpath -u "$1")"', script, home], env=env)
    else:
        run([sys.executable, script, '--prefix', home])
    return os.path.join(home, 'bin')


def library_dir(row):
    for part in row['path'].split(';')[1:]:
        key, _, value = part.partition('=')
        if key == 'lib':
            return os.path.join(home(row), *value.split('/'))
    return None


def install_deb(row):
    package = download(row['source'], row['sha256'])
    run(sudo() + ['apt-get', 'install', '-y', '--no-install-recommends', '--allow-downgrades', package])
    return row['path']


def install_macpkg(row):
    image = download(row['source'], row['sha256'])
    mount = tempfile.mkdtemp(prefix='smalt-oracle-')
    run(['hdiutil', 'attach', '-nobrowse', '-readonly', '-mountpoint', mount, image])
    try:
        packages = [name for name in os.listdir(mount) if name.endswith('.pkg')]
        if not packages:
            raise SystemExit('no package in ' + image)
        run(sudo() + ['installer', '-pkg', os.path.join(mount, packages[0]), '-target', '/'])
    finally:
        run(['hdiutil', 'detach', mount])
    return row['path']


def install_apt(row):
    run(sudo() + ['apt-get', 'update', '-q'])
    run(sudo() + ['apt-get', 'install', '-y', '-q', '--no-install-recommends'] + row['source'].split())
    return None if row['path'] == '-' else row['path']


def brew_env():
    env = dict(os.environ)
    env.update(HOMEBREW_NO_AUTO_UPDATE='1', HOMEBREW_NO_INSTALL_CLEANUP='1',
               HOMEBREW_NO_INSTALLED_DEPENDENTS_CHECK='1', HOMEBREW_NO_ENV_HINTS='1')
    return env


def install_brew(row):
    run(['brew', 'install', '--quiet'] + row['source'].split(), env=brew_env())
    if row['path'] == '-':
        return None
    if row['path'].startswith('brew:'):
        formula, _, rest = row['path'][len('brew:'):].partition('/')
        prefix = subprocess.run(['brew', '--prefix', formula], check=True, capture_output=True, text=True,
                                env=brew_env()).stdout.strip()
        return os.path.join(prefix, rest)
    return row['path']


def install_msys2(row):
    # The package database of the runner image is used as it is: refreshing it would make these packages newer than
    # the toolchain the ffmpeg build installed from the same database, which pacman refuses as a partial upgrade.
    pacman = os.path.join(MSYS2, 'usr', 'bin', 'pacman.exe')
    run([pacman, '-S', '--noconfirm', '--needed'] + row['source'].split())
    return row['path']


def toolchain_env(target):
    env = dict(os.environ)
    if target.startswith('windows'):
        env['PATH'] = os.path.join(MSYS2, 'ucrt64', 'bin') + os.pathsep + os.path.join(MSYS2, 'usr', 'bin') \
            + os.pathsep + env.get('PATH', '')
        env['MSYSTEM'] = 'UCRT64'
    return env


def compiler(target):
    if target.startswith('windows'):
        return [os.path.join(MSYS2, 'ucrt64', 'bin', 'gcc.exe'), '-static']
    return ['cc']


def source_tree(row, work):
    archive = download(row['source'], row['sha256'])
    extract(archive, work)
    entries = [entry for entry in os.listdir(work) if os.path.isdir(os.path.join(work, entry))]
    return os.path.join(work, entries[0]) if len(entries) == 1 else work


def cmake(target):
    """Returns the CMake executable: MSYS2's on Windows, so that the MinGW builds find its toolchain."""
    return os.path.join(MSYS2, 'ucrt64', 'bin', 'cmake.exe') if target.startswith('windows') else 'cmake'


def configure(tree, build_dir, target=None):
    """Returns the CMake configure command of a Release build; old minimum versions are accepted by CMake 4.

    With a Windows target the build uses MSYS2's MinGW toolchain with Ninja and links the executables statically."""
    command = [cmake(target or ''), '-S', tree, '-B', build_dir, '-DCMAKE_BUILD_TYPE=Release',
               '-DCMAKE_POLICY_VERSION_MINIMUM=3.5']
    if target and target.startswith('windows'):
        ucrt = os.path.join(MSYS2, 'ucrt64', 'bin')
        command += ['-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + os.path.join(ucrt, 'ninja.exe'),
                    '-DCMAKE_C_COMPILER=' + os.path.join(ucrt, 'gcc.exe'),
                    '-DCMAKE_CXX_COMPILER=' + os.path.join(ucrt, 'g++.exe'),
                    '-DCMAKE_ASM_NASM_COMPILER=' + os.path.join(ucrt, 'nasm.exe'),
                    '-DCMAKE_EXE_LINKER_FLAGS=-static']
    return command


def build_all(build_dir, target, env, *targets):
    """Builds a configured CMake tree, the given targets or all of them."""
    command = [cmake(target), '--build', build_dir, '--config', 'Release', '--parallel']
    if targets:
        command += ['--target'] + list(targets)
    run(command, env=env)


def find_file(directory, name):
    """Returns the first file of a name under a directory, failing when there is none."""
    for parent, _, files in os.walk(directory):
        if name in files:
            return os.path.join(parent, name)
    raise SystemExit('the build produced no ' + name)


def build(row, target):
    out = bin_dir(target)
    os.makedirs(out, exist_ok=True)
    name = row['package']
    executable = os.path.join(out, name + EXE)
    stamp = os.path.join(out, '.' + name)
    key = '%s %s %s' % (row['version'], row['sha256'], digest(__file__))
    outputs = [os.path.join(out, tool + EXE) for tool, _ in provided(row)]
    if all(os.path.isfile(path) for path in outputs) and os.path.isfile(stamp) and open(stamp).read() == key:
        return out
    env = toolchain_env(target)
    with tempfile.TemporaryDirectory(prefix='smalt-oracle-') as work:
        if name == 'opus_compare':
            tree = source_tree(row, work)
            run(compiler(target) + ['-O2', '-o', executable, os.path.join(tree, 'src', 'opus_compare.c'), '-lm'],
                env=env)
        elif name == 'smalt-lcmsdump':
            tree = source_tree(row, work)
            sources = sorted(os.path.join(tree, 'src', file) for file in os.listdir(os.path.join(tree, 'src'))
                             if file.endswith('.c'))
            run(compiler(target) + ['-O2', '-I' + os.path.join(tree, 'include'), '-o', executable,
                                    os.path.join(HERE, 'icc-hdr', 'smalt-lcmsdump.c')] + sources + ['-lm'], env=env)
        elif name == 'smalt-pngdump':
            # libpng is compiled into the program with its prebuilt configuration and without the SIMD filters,
            # so that every platform runs the same library code; zlib is the system's.
            program = os.path.join(HERE, 'png-oracle', 'smalt-pngdump.c')
            tree = source_tree(row, work)
            shutil.copy2(os.path.join(tree, 'scripts', 'pnglibconf.h.prebuilt'), os.path.join(tree, 'pnglibconf.h'))
            sources = [os.path.join(tree, file) for file in LIBPNG_SOURCES]
            run(compiler(target) + ['-O2', '-Wall', '-I' + tree] + ['-D%s=0' % flag for flag in LIBPNG_SIMD]
                + ['-o', executable, program] + sources + ['-lz', '-lm'], env=env)
        elif name == 'openjpeg':
            tree = source_tree(row, work)
            build_dir = os.path.join(work, 'build')
            # The irreversible wavelet is float arithmetic: OpenJPEG's Release configuration adds -ffast-math, and
            # a compiler that fuses multiply-adds rounds differently too, so the oracle is a RelWithDebInfo build
            # without contraction, the arithmetic of the official Windows build. The image format libraries are
            # left out: the tests exchange PNM and raw codestreams with the tools.
            command = configure(tree, build_dir)
            command[command.index('-DCMAKE_BUILD_TYPE=Release')] = '-DCMAKE_BUILD_TYPE=RelWithDebInfo'
            run(command + ['-DBUILD_SHARED_LIBS=OFF', '-DBUILD_CODEC=ON', '-DBUILD_TESTING=OFF',
                           '-DCMAKE_C_FLAGS=-ffp-contract=off', '-DCMAKE_DISABLE_FIND_PACKAGE_TIFF=ON',
                           '-DCMAKE_DISABLE_FIND_PACKAGE_PNG=ON', '-DCMAKE_DISABLE_FIND_PACKAGE_LCMS2=ON',
                           '-DCMAKE_DISABLE_FIND_PACKAGE_LCMS=ON'], env=env)
            run(['cmake', '--build', build_dir, '--parallel', '--target', 'opj_compress', 'opj_decompress',
                 'opj_dump'], env=env)
            for tool in ('opj_compress', 'opj_decompress', 'opj_dump'):
                shutil.copy2(find_file(build_dir, tool + EXE), os.path.join(out, tool + EXE))
        elif name == 'openjph':
            tree = source_tree(row, work)
            build_dir = os.path.join(work, 'build')
            # The scalar kernels give every platform the same arithmetic; TIFF input and output are left out: the
            # tests exchange PGM, PPM and raw codestreams with the tools.
            run(configure(tree, build_dir, target) + ['-DBUILD_SHARED_LIBS=OFF', '-DOJPH_ENABLE_TIFF_SUPPORT=OFF',
                                                      '-DOJPH_BUILD_TESTS=OFF', '-DOJPH_BUILD_EXECUTABLES=ON',
                                                      '-DOJPH_DISABLE_SIMD=ON'], env=env)
            build_all(build_dir, target, env, 'ojph_compress', 'ojph_expand')
            for tool in ('ojph_compress', 'ojph_expand'):
                shutil.copy2(find_file(build_dir, tool + EXE), os.path.join(out, tool + EXE))
        elif name == 'libjxl':
            tree = source_tree(row, work)
            build_dir = os.path.join(work, 'build')
            run(configure(tree, build_dir) + [
                '-DBUILD_SHARED_LIBS=OFF', '-DBUILD_TESTING=OFF', '-DJPEGXL_ENABLE_TOOLS=ON',
                '-DJPEGXL_FORCE_SYSTEM_BROTLI=ON', '-DJPEGXL_FORCE_SYSTEM_HWY=ON', '-DJPEGXL_FORCE_SYSTEM_LCMS=ON',
                '-DJPEGXL_ENABLE_SKCMS=OFF', '-DJPEGXL_ENABLE_SJPEG=OFF', '-DJPEGXL_ENABLE_OPENEXR=OFF',
                '-DJPEGXL_ENABLE_BENCHMARK=OFF', '-DJPEGXL_ENABLE_EXAMPLES=OFF', '-DJPEGXL_ENABLE_MANPAGES=OFF',
                '-DJPEGXL_ENABLE_JNI=OFF', '-DJPEGXL_ENABLE_DOXYGEN=OFF', '-DJPEGXL_ENABLE_JPEGLI=OFF',
                '-DJPEGXL_ENABLE_PLUGINS=OFF', '-DJPEGXL_ENABLE_VIEWERS=OFF', '-DJPEGXL_ENABLE_DEVTOOLS=OFF',
                '-DJPEGXL_ENABLE_FUZZERS=OFF'], env=env)
            run(['cmake', '--build', build_dir, '--parallel', '--target', 'cjxl', 'djxl'], env=env)
            for tool in ('cjxl', 'djxl'):
                shutil.copy2(find_file(build_dir, tool + EXE), os.path.join(out, tool + EXE))
        elif name == 'libtiff':
            tree = source_tree(row, work)
            build_dir = os.path.join(work, 'build')
            # The codecs are those every platform's package manager provides (oracles.tsv): zlib, libjpeg, LZMA,
            # Zstandard, WebP and libdeflate; JBIG and LERC are left out everywhere.
            run(configure(tree, build_dir, target) + ['-DBUILD_SHARED_LIBS=OFF', '-Dtiff-docs=OFF',
                                                      '-Dtiff-tests=OFF', '-Dtiff-contrib=OFF', '-Djbig=OFF',
                                                      '-Dlerc=OFF', '-Dzlib=ON', '-Djpeg=ON', '-Dlzma=ON',
                                                      '-Dzstd=ON', '-Dwebp=ON', '-Dlibdeflate=ON'], env=env)
            build_all(build_dir, target, env)
            for tool in ('tiffcp', 'tiffinfo', 'tiffdump', 'tiff2rgba'):
                shutil.copy2(find_file(build_dir, tool + EXE), os.path.join(out, tool + EXE))
        elif name == 'flac':
            tree = source_tree(row, work)
            build_dir = os.path.join(work, 'build')
            run(configure(tree, build_dir, target) + ['-DBUILD_SHARED_LIBS=OFF', '-DBUILD_DOCS=OFF',
                                                      '-DBUILD_EXAMPLES=OFF', '-DBUILD_TESTING=OFF',
                                                      '-DBUILD_CXXLIBS=OFF', '-DINSTALL_MANPAGES=OFF',
                                                      '-DWITH_OGG=OFF'], env=env)
            build_all(build_dir, target, env)
            shutil.copy2(find_file(build_dir, 'flac' + EXE), executable)
        elif name == 'libjpeg-turbo':
            tree = source_tree(row, work)
            build_dir = os.path.join(work, 'build')
            run(configure(tree, build_dir, target) + ['-DENABLE_SHARED=OFF', '-DENABLE_STATIC=ON',
                                                      '-DWITH_TURBOJPEG=OFF'], env=env)
            build_all(build_dir, target, env)
            # A static-only build names the programs <tool>-static.
            for tool in ('cjpeg', 'djpeg', 'jpegtran'):
                shutil.copy2(find_file(build_dir, tool + '-static' + EXE), os.path.join(out, tool + EXE))
        elif name == 'mkvalidator':
            tree = source_tree(row, work)
            build_dir = os.path.join(work, 'build')
            # Windows builds with the runner's Visual Studio, as the project's own CI does (its entry point
            # does not link with MinGW).
            run(configure(tree, build_dir),
                env=None if target.startswith('windows') else env)
            run(['cmake', '--build', build_dir, '--config', 'Release', '--target', 'mkvalidator', '--parallel'],
                env=None if target.startswith('windows') else env)
            shutil.copy2(find_file(build_dir, 'mkvalidator' + EXE), executable)
        else:
            raise SystemExit('no build recipe for ' + name)
    with open(stamp, 'w') as file:
        file.write(key)
    return out


def command_install(arguments):
    target = current_platform()
    directories = []
    managed = []
    libraries = []
    for row in rows(target):
        method = row['method']
        print('== %s %s (%s)' % (row['package'], row['version'], method), flush=True)
        if method == 'archive':
            directory = install_archive(row)
        elif method == 'deb':
            directory = install_deb(row)
        elif method == 'macpkg':
            directory = install_macpkg(row)
        elif method == 'apt':
            directory = install_apt(row)
        elif method == 'brew':
            directory = install_brew(row)
        elif method == 'msys2':
            directory = install_msys2(row)
        elif method == 'build':
            directory = build(row, target)
        elif method == 'script':
            directory = install_script(row)
        else:
            raise SystemExit('unknown method ' + method)
        # A package that installs into a system directory (TSDuck's into /usr/bin) is on PATH already; putting that
        # directory first would shadow the tools the workflow set up, such as its JDK.
        if directory and os.path.normpath(directory) not in SYSTEM_DIRECTORIES:
            (managed if method in PACKAGE_MANAGERS else directories).append(os.path.normpath(directory))
        library = library_dir(row)
        if library:
            libraries.append(library)
    # The pinned rows take precedence in manifest order, the package managers' directories (which may hold other
    # builds of the same tools as dependencies) come last; GITHUB_PATH gives precedence to later entries.
    ordered = list(dict.fromkeys(directories + managed))
    if arguments.github:
        with open(os.environ['GITHUB_PATH'], 'a', encoding='utf-8') as file:
            for directory in reversed(ordered):
                file.write(directory + '\n')
        with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as file:
            file.write('SMALT_TEST_REQUIRE_ORACLES=%s\n' % ','.join(required(target)))
            # macOS binaries get an rpath instead: System Integrity Protection drops DYLD_* for shell scripts
            if libraries and target.startswith('linux'):
                variable = 'LD_LIBRARY_PATH'
                existing = os.environ.get(variable)
                file.write('%s=%s\n' % (variable, os.pathsep.join(libraries + ([existing] if existing else []))))
    print('tool directories, highest precedence first:')
    for directory in ordered:
        print('  ' + directory)
    return 0


def required(target):
    names = []
    for row in rows(target):
        names.extend(name for name, _ in provided(row))
    return list(dict.fromkeys(names))


def owner(target):
    """Maps each executable to the directory it must resolve in, for the rows that put a directory on PATH."""
    owners = {}
    for row in rows(target):
        if row['method'] == 'archive':
            path = row['path'].partition(';')[0]
            path = path.partition('!')[2] or path
            directory = os.path.join(home(row), *path.split('/'))
        elif row['method'] == 'build':
            directory = bin_dir(target)
        elif row['method'] == 'script':
            directory = os.path.join(script_home(row), 'bin')
        elif row['method'] in ('deb', 'macpkg'):
            directory = row['path']
        else:
            continue
        for name, _ in provided(row):
            owners[name] = os.path.normcase(os.path.normpath(directory))
    return owners


def ffmpeg_check():
    """Loads tools/testdata/ffmpeg-oracle/check.py, the checks of the ffmpeg components."""
    import importlib.util
    spec = importlib.util.spec_from_file_location('ffmpeg_check', os.path.join(HERE, 'ffmpeg-oracle', 'check.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def command_versions(arguments):
    target = current_platform()
    owners = owner(target)
    lines = []
    failures = []
    for name in required(target):
        found = shutil.which(name)
        if found is None:
            failures.append('%s is not on PATH' % name)
            lines.append('%s\t-\tmissing' % name)
            continue
        directory = os.path.normcase(os.path.normpath(os.path.dirname(os.path.realpath(found))))
        expected = owners.get(name)
        if expected and directory != os.path.normcase(os.path.realpath(expected)):
            failures.append('%s resolves to %s, not to %s' % (name, found, expected))
        try:
            result = subprocess.run([found] + VERSION_ARGS.get(name, []), capture_output=True, timeout=60,
                                    stdin=subprocess.DEVNULL)
            output = (result.stdout + result.stderr).decode('utf-8', 'replace')
        except (OSError, subprocess.TimeoutExpired) as error:
            output = 'cannot run: %s' % error
            failures.append('%s cannot run: %s' % (name, error))
        text = [line.strip() for line in output.splitlines() if line.strip()]
        version = next((line for line in text if any(marker in line.lower() for marker in VERSION_MARKERS)),
                       text[0] if text else '?')
        lines.append('%s\t%s\t%s' % (name, found, version[:160]))
        if name == 'ffmpeg':
            check = ffmpeg_check()
            missing = check.missing_features(found)
            if not missing:
                missing = check.failing_features(found)
            failures.extend(missing)
            listed = os.path.relpath(check.FFMPEG_FEATURES, os.path.dirname(os.path.dirname(HERE)))
            listed = listed.replace(os.sep, '/')
            summary = '%d missing or failing' % len(missing) if missing else 'all %d present and working' % len(
                check.ffmpeg_features())
            lines.append('ffmpeg features\t%s\t%s' % (listed, summary))
    report = '\n'.join(['# tool\tpath\tversion'] + lines) + '\n'
    print(report)
    if arguments.out:
        with open(arguments.out, 'w', encoding='utf-8') as file:
            file.write('# oracle versions on %s\n' % target)
            file.write(report)
    for failure in failures:
        print('error: ' + failure)
    return 1 if failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('command', choices=['install', 'versions', 'required'])
    parser.add_argument('--github', action='store_true')
    parser.add_argument('--out')
    arguments = parser.parse_args()
    if arguments.command == 'install':
        return command_install(arguments)
    if arguments.command == 'versions':
        return command_versions(arguments)
    print(','.join(required(current_platform())))
    return 0


if __name__ == '__main__':
    sys.exit(main())
