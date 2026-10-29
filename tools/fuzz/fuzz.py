#!/usr/bin/env python3
"""Runs Smalt's coverage-guided fuzz targets (Jazzer @FuzzTest methods) outside the normal build.

The targets are the @FuzzTest methods of the */src/test/java/**/fuzz/*FuzzTest.java classes of the codec
modules. A normal Maven build replays their seeds and saved findings as plain tests; this script fuzzes them
with Jazzer's standalone driver on the test class path of their module (see README.md next to it).

Commands:
  list [--select SPEC]                 prints the targets, as module:Class.method
  build [--online]                     compiles the fuzzed modules and records their test class paths
  seeds [--select SPEC]                gathers extra seeds from the conformance cache (smoke tier and any
                                       other vector cached) into target/fuzz/<target>/seeds
  run TARGET [--time S] [--no-build]   fuzzes one target; findings land in target/fuzz/<target>/findings
  budget [--time S] [--select SPEC]    fuzzes every selected target for S seconds and prints a summary;
                                       exits with 1 when a target found something
  reproduce TARGET FILE...             runs inputs once through a target and prints what they throw
  save TARGET FILE [--name NAME]       copies a (minimized) finding to the module's fuzz-regressions, where
                                       every build replays it
  minimize TARGET FILE                 shrinks a crashing input with libFuzzer's -minimize_crash
  package --out DIR [--select SPEC]    lays the targets out for ClusterFuzzLite: a JDK, the class paths, one
                                       launcher per target, seed corpus zips and dictionaries

SPEC is a comma-separated list of modules, module:Class, module:Class.method or shard=K/N (every Nth
target, starting at the Kth); a file name starting with @ reads the list from that file. Maven and the JDK
come from PATH and JAVA_HOME (Java 26).
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'target' / 'fuzz'
MODULES = ['core', 'image', 'audio', 'video', 'mpeg', 'raw']
CLASSPATH_FILE = 'fuzz.classpath'
DRIVER = 'com.code_intelligence.jazzer.Jazzer'
JVM_FLAGS = ['-XX:+EnableDynamicAgentLoading', '--add-modules', 'jdk.incubator.vector',
             '--enable-native-access=ALL-UNNAMED', '-XX:+UseSerialGC']
INSTRUMENT = '--instrumentation_includes=com.github.auties00.smalt.**'
# FUZZ-TAG separates the packets of a FuzzPackets input, FUZZ-ALP the color and alpha frames of a packet.
MARKER_DICTIONARY = '"FUZZ-TAG"\n"FUZZ-ALP"\n'
# libFuzzer's artifact prefixes; a slow-unit- artifact records an input slower than -report_slow_units, which is
# reported but is not a finding.
FINDING_PREFIXES = ('crash-', 'timeout-', 'oom-', 'leak-')
# The seconds one input may take before it is a finding, as on OSS-Fuzz and ClusterFuzzLite.
DEFAULT_TIMEOUT = 25
# A seed larger than this slows every mutation of its target; libFuzzer's -max_len follows the corpus.
MAX_SEED_BYTES = 256 * 1024
# The caps of a packet seed converted from a container track: a few packets reach most of a decoder, and a short
# seed keeps each execution fast.
CONVERTED_MAX_BYTES = 64 * 1024
CONVERTED_MAX_PACKETS = 32
# The smoke-tier and cached vectors each input target starts from, by file extension.
SEED_EXTENSIONS = {
    'image:ImageFuzzTest.jpeg': ['.jpg', '.jpeg'],
    'image:ImageFuzzTest.png': ['.png', '.apng'],
    'image:ImageFuzzTest.gif': ['.gif'],
    'image:ImageFuzzTest.webp': ['.webp'],
    'image:ImageFuzzTest.bmp': ['.bmp'],
    'image:ImageFuzzTest.tiff': ['.tif', '.tiff'],
    'image:ImageFuzzTest.ico': ['.ico', '.cur'],
    'image:ImageFuzzTest.jp2': ['.jp2', '.j2k', '.j2c', '.jpx', '.jpf'],
    'image:ImageFuzzTest.jxl': ['.jxl'],
    'image:ImageFuzzTest.jp2Channels': ['.jp2', '.j2k', '.j2c', '.jpx', '.jpf'],
    'image:ImageFuzzTest.jxlFloat': ['.jxl'],
    'image:ImageFuzzTest.jxlJpeg': ['.jxl'],
    'image:ImageMetadataFuzzTest.webpContainer': ['.webp'],
    'image:ImageMetadataFuzzTest.metadata': ['.jpg', '.png', '.webp', '.tif', '.gif', '.heic', '.avif'],
    'image:ImageMetadataFuzzTest.jpegTransform': ['.jpg', '.jpeg'],
    'video:VideoFuzzTest.avif': ['.avif'],
    'mpeg:MpegFuzzTest.heic': ['.heic', '.heif'],
    'mpeg:MpegFuzzTest.elementaryStream': ['.m4v', '.cmp', '.263', '.h263'],
    'mpeg:MpegFuzzTest.h264Info': ['.h264', '.264', '.jsv', '.avc', '.26l'],
    'core:RemuxFuzzTest.mp4': ['.mp4', '.mov', '.m4a', '.3gp', '.mkv', '.webm', '.ts', '.ogg', '.opus', '.avi'],
    'core:ColorFuzzTest.icc': ['.icc', '.icm'],
    'core:ProbeFuzzTest.probe': ['.mp4', '.mkv', '.webm', '.ogg', '.flac', '.ivf', '.mov', '.3gp', '.jpg', '.png',
                                 '.webp', '.tif', '.jp2', '.jxl', '.avif', '.heic', '.dng'],
    'core:TagFuzzTest.tags': ['.mp3', '.flac', '.ogg', '.opus', '.m4a', '.mp4'],
    'core:DemuxerFuzzTest.mp4': ['.mp4', '.mov', '.m4a', '.3gp', '.m4v'],
    'core:DemuxerFuzzTest.matroska': ['.mkv', '.webm', '.mka'],
    'core:DemuxerFuzzTest.ogg': ['.ogg', '.opus', '.oga', '.ogv', '.spx'],
    'core:DemuxerFuzzTest.mpegAudio': ['.mp3', '.mp2'],
    'core:DemuxerFuzzTest.adts': ['.aac', '.adts'],
    'core:DemuxerFuzzTest.wav': ['.wav'],
    'core:DemuxerFuzzTest.flac': ['.flac'],
    'core:DemuxerFuzzTest.wavpack': ['.wv'],
    'core:DemuxerFuzzTest.amr': ['.amr', '.awb'],
    'core:DemuxerFuzzTest.aiff': ['.aif', '.aiff', '.aifc'],
    'core:DemuxerFuzzTest.ivf': ['.ivf'],
    'core:DemuxerFuzzTest.avi': ['.avi'],
    'core:DemuxerFuzzTest.mpegTs': ['.ts', '.m2ts', '.mts'],
    'core:DemuxerFuzzTest.ac3': ['.ac3', '.eac3', '.ec3'],
    'core:DemuxerFuzzTest.subRip': ['.srt'],
    'core:DemuxerFuzzTest.webVtt': ['.vtt'],
    'core:SubtitleFuzzTest.read': ['.srt', '.vtt', '.mkv'],
}
# The packet decoder targets and the codec directories FuzzSeedConverter writes for them.
SEED_CODECS = {
    'image:ImageVideoFuzzTest.mjpeg': ['mjpeg'], 'image:ImageVideoFuzzTest.vp8': ['vp8'],
    'audio:AudioFuzzTest.opus': ['opus'], 'audio:AudioFuzzTest.vorbis': ['vorbis'],
    'audio:AudioFuzzTest.flac': ['flac'], 'audio:AudioFuzzTest.alac': ['alac'],
    'audio:AudioFuzzTest.mp3': ['mp3'], 'audio:AudioFuzzTest.mp2': ['mp2'],
    'audio:AudioFuzzTest.speex': ['speex'], 'audio:AudioFuzzTest.wavpack': ['wavpack'],
    'video:VideoFuzzTest.vp9': ['vp9'], 'video:VideoFuzzTest.av1': ['av1'],
    'video:VideoFuzzTest.proRes': ['prores'], 'video:VideoFuzzTest.dnxhd': ['dnxhd'],
    'video:VideoFuzzTest.theora': ['theora'], 'video:VideoFuzzTest.ffv1': ['ffv1'],
    'mpeg:MpegFuzzTest.h264': ['h264'], 'mpeg:MpegFuzzTest.hevc': ['hevc'], 'mpeg:MpegFuzzTest.hevcLayers': ['hevc'],
    'mpeg:MpegFuzzTest.mpeg4': ['mpeg4_part2'], 'mpeg:MpegFuzzTest.h263': ['h263'],
    'mpeg:MpegFuzzTest.mpeg1Video': ['mpeg1video'], 'mpeg:MpegFuzzTest.mpeg2Video': ['mpeg2video'],
    'mpeg:MpegFuzzTest.aac': ['aac'], 'mpeg:MpegFuzzTest.amrNb': ['amr_nb'], 'mpeg:MpegFuzzTest.amrWb': ['amr_wb'],
    'mpeg:MpegFuzzTest.ac3': ['ac3'], 'mpeg:MpegFuzzTest.eac3': ['eac3'],
}
CONTAINER_EXTENSIONS = ['.ivf', '.webm', '.mkv', '.mp4', '.mov', '.ogg', '.opus', '.flac', '.mp3', '.m4a',
                        '.ac3', '.eac3', '.aac', '.amr', '.avi', '.ts', '.3gp', '.ogv', '.spx', '.wv', '.mka']


class Target:
    def __init__(self, module, package, cls, method, framed):
        self.module, self.package, self.cls, self.method, self.framed = module, package, cls, method, framed

    @property
    def name(self):
        return f'{self.module}:{self.cls}.{self.method}'

    @property
    def qualified(self):
        return f'{self.package}.{self.cls}'

    @property
    def slug(self):
        return f'smalt_{self.module}_{self.cls}_{self.method}'

    @property
    def inputs(self):
        return (ROOT / self.module / 'src' / 'test' / 'resources' / self.package.replace('.', '/')
                / f'{self.cls}Inputs' / self.method)

    @property
    def regressions(self):
        return ROOT / self.module / 'src' / 'test' / 'resources' / 'fuzz-regressions' / self.cls / self.method

    @property
    def work(self):
        return WORK / self.slug


def discover():
    found = []
    for module in MODULES:
        for source in sorted((ROOT / module / 'src' / 'test' / 'java').rglob('fuzz/*FuzzTest.java')):
            text = source.read_text(encoding='utf-8')
            package = re.search(r'^package\s+([\w.]+);', text, re.M).group(1)
            for match in re.finditer(r'@FuzzTest[^\n]*\n\s*(?:public\s+)?void\s+(\w+)\s*\(byte\[\]\s+\w+\)\s*\{', text):
                body = text[match.end():text.find('\n    }', match.end())]
                framed = ('FuzzMedia.video(' in body or 'FuzzMedia.videoAlpha(' in body or 'FuzzMedia.audio(' in body
                          or 'FuzzPackets' in body or 'FuzzPresentations' in body)
                found.append(Target(module, package, source.stem, match.group(1), framed))
    return found


def select(targets, spec):
    if not spec:
        return targets
    if spec.startswith('@'):
        spec = ','.join(line.strip() for line in Path(spec[1:]).read_text().splitlines()
                        if line.strip() and not line.startswith('#'))
    chosen = []
    for item in [part.strip() for part in spec.split(',') if part.strip()]:
        shard = re.fullmatch(r'shard=(\d+)/(\d+)', item)
        if shard:
            k, n = int(shard.group(1)), int(shard.group(2))
            matches = [t for i, t in enumerate(targets) if i % n == k % n]
        elif ':' in item:
            module, rest = item.split(':', 1)
            matches = [t for t in targets if t.module == module
                       and (f'{t.cls}.{t.method}' == rest or t.cls == rest)]
        else:
            matches = [t for t in targets if t.module == item]
        if not matches:
            sys.exit(f'no target matches {item}')
        chosen.extend(t for t in matches if t not in chosen)
    return chosen


def java():
    home = os.environ.get('JAVA_HOME')
    executable = 'java.exe' if os.name == 'nt' else 'java'
    return str(Path(home) / 'bin' / executable) if home else executable


def maven(arguments, online):
    command = ['mvn.cmd' if os.name == 'nt' else 'mvn', '-B', '-ntp', '-q'] + ([] if online else ['-o']) + arguments
    print('+', ' '.join(command), flush=True)
    subprocess.run(command, cwd=ROOT, check=True)


def build(modules, online=False):
    maven(['-pl', ','.join(modules), '-am', 'test-compile', 'dependency:build-classpath',
           '-Dmdep.includeScope=test', f'-Dmdep.outputFile=target/{CLASSPATH_FILE}'], online)


def classpath(module):
    recorded = ROOT / module / 'target' / CLASSPATH_FILE
    if not recorded.is_file():
        sys.exit(f'{recorded} is missing: run "fuzz.py build" first')
    entries = [ROOT / module / 'target' / 'test-classes', ROOT / module / 'target' / 'classes']
    for entry in recorded.read_text(encoding='utf-8').strip().split(os.pathsep):
        if not entry:
            continue
        path = Path(entry)
        smalt = re.fullmatch(r'smalt-(\w+)-[^/\\]*?(-tests)?\.jar', path.name)
        if smalt:
            # a sibling module outside the reactor run: use this tree's output, never an installed jar
            path = ROOT / smalt.group(1) / 'target' / ('test-classes' if smalt.group(2) else 'classes')
        if path not in entries:
            entries.append(path)
    return entries


def command(target, arguments, memory=1536, cp=None, java_binary=None):
    cp = cp if cp is not None else os.pathsep.join(str(entry) for entry in classpath(target.module))
    return ([java_binary or java()] + JVM_FLAGS + [f'-Xmx{memory}m', '-cp', cp, DRIVER,
            f'--target_class={target.qualified}', f'--target_method={target.method}', INSTRUMENT] + arguments)


def conformance_cache():
    configured = os.environ.get('SMALT_CONFORMANCE_CACHE', '').strip()
    return Path(configured) if configured else Path.home() / '.smalt' / 'cache' / 'conformance'


def cached_files(extensions):
    cache = conformance_cache()
    if not cache.is_dir():
        return []
    files = [path for path in cache.iterdir() if path.is_file() and path.suffix.lower() in extensions
             and path.stat().st_size <= MAX_SEED_BYTES * 16]
    archive = cache / 'PngSuite-2017jul19.tgz'
    if '.png' in extensions and archive.is_file():
        extracted = WORK / 'pngsuite'
        if not extracted.is_dir():
            extracted.mkdir(parents=True)
            with tarfile.open(archive) as tar:
                members = [member for member in tar.getmembers() if member.isfile()
                           and member.name.endswith('.png') and '..' not in member.name]
                for member in members:
                    member.name = Path(member.name).name
                    tar.extract(member, extracted)
        files.extend(sorted(extracted.rglob('*.png')))
    return sorted(files)


def gather_seeds(targets):
    converted = WORK / 'converted'
    framed = [t for t in targets if t.name in SEED_CODECS]
    if framed and not converted.is_dir():
        containers = [str(path) for path in cached_files(set(CONTAINER_EXTENSIONS))]
        if containers:
            converted.mkdir(parents=True)
            cp = os.pathsep.join(str(entry) for entry in classpath('core'))
            for start in range(0, len(containers), 200):
                subprocess.run([java()] + JVM_FLAGS + ['-cp', cp,
                                'com.github.auties00.smalt.testkit.fuzz.FuzzSeedConverter', str(converted),
                                str(CONVERTED_MAX_BYTES), str(CONVERTED_MAX_PACKETS)]
                           + containers[start:start + 200], check=False)
    for target in targets:
        seeds = target.work / 'seeds'
        shutil.rmtree(seeds, ignore_errors=True)
        seeds.mkdir(parents=True)
        sources = []
        if target.name in SEED_EXTENSIONS:
            sources = cached_files(set(SEED_EXTENSIONS[target.name]))
        for codec in SEED_CODECS.get(target.name, []):
            directory = converted / codec
            if directory.is_dir():
                sources.extend(sorted(directory.iterdir()))
        count = 0
        for source in sources:
            if source.stat().st_size <= MAX_SEED_BYTES:
                shutil.copyfile(source, seeds / source.name)
                count += 1
        print(f'{target.name}: {count} extra seeds')


def corpus_dirs(target):
    dirs = [target.work / 'corpus']
    for extra in (target.inputs, target.regressions, target.work / 'seeds'):
        if extra.is_dir() and any(extra.iterdir()):
            dirs.append(extra)
    return dirs


def fuzz(target, seconds, timeout, memory):
    corpus = target.work / 'corpus'
    findings = target.work / 'findings'
    corpus.mkdir(parents=True, exist_ok=True)
    findings.mkdir(parents=True, exist_ok=True)
    before = set(findings.iterdir())
    arguments = [str(path) for path in corpus_dirs(target)] + [
        f'-max_total_time={seconds}', f'-timeout={timeout}', f'-rss_limit_mb={memory + 1024}',
        f'-artifact_prefix={findings}{os.sep}', '-print_final_stats=1']
    if target.framed:
        dictionary = target.work / 'marker.dict'
        dictionary.write_text(MARKER_DICTIONARY)
        arguments.append(f'-dict={dictionary}')
    log = target.work / 'fuzz.log'
    started = time.monotonic()
    with open(log, 'w', encoding='utf-8', errors='replace') as out:
        result = subprocess.run(command(target, arguments, memory), stdout=out, stderr=subprocess.STDOUT)
    elapsed = time.monotonic() - started
    created = sorted(set(findings.iterdir()) - before)
    new = [path for path in created if path.name.startswith(FINDING_PREFIXES)]
    slow = [path for path in created if path.name.startswith('slow-unit-')]
    text = log.read_text(encoding='utf-8', errors='replace')
    runs = re.findall(r'stat::number_of_executed_units:\s*(\d+)', text)
    coverage = re.findall(r'cov: (\d+)', text)
    return {'target': target, 'code': result.returncode, 'findings': new, 'slow': slow, 'seconds': elapsed,
            'runs': runs[-1] if runs else '?', 'cov': coverage[-1] if coverage else '?', 'log': log}


def report(result):
    status = 'ok' if result['code'] == 0 and not result['findings'] else f'FINDING (exit {result["code"]})'
    slow = f' slow={len(result["slow"])}' if result['slow'] else ''
    print(f'{result["target"].name:48} {status:20} runs={result["runs"]:>9} cov={result["cov"]:>6} '
          f'{result["seconds"]:6.0f}s{slow}', flush=True)
    for finding in result['findings']:
        print(f'    {finding}')
    if result['findings'] or result['code'] != 0:
        print(f'    log: {result["log"]}')


def by_name(targets, name):
    matches = [t for t in targets if t.name == name or t.slug == name]
    if not matches:
        sys.exit(f'unknown target {name}; see "fuzz.py list"')
    return matches[0]


def package(targets, out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    jdk = out / 'jdk'
    if not jdk.is_dir():
        home = os.environ.get('JAVA_HOME')
        if not home:
            sys.exit('JAVA_HOME must name the Java 26 JDK to bundle')
        shutil.copytree(home, jdk, symlinks=True)
    lib = out / 'lib'
    jars = lib / 'jars'
    jars.mkdir(parents=True, exist_ok=True)
    paths = {}
    for module in sorted({t.module for t in targets}):
        entries = []
        for entry in classpath(module):
            if entry.is_dir():
                relative = entry.relative_to(ROOT)
                destination = lib / relative
                if not destination.is_dir():
                    # Jazzer adds a <Class>Inputs directory it finds on the class path to every run, which would
                    # break the fixed-run checks of ClusterFuzzLite; the seeds go into the seed corpus zips instead
                    shutil.copytree(entry, destination, ignore=shutil.ignore_patterns('*Inputs', 'fuzz-regressions'))
                entries.append(relative)
            elif entry.is_file():
                destination = jars / entry.name
                if not destination.exists():
                    shutil.copyfile(entry, destination)
                entries.append(Path('jars') / entry.name)
        paths[module] = ':'.join('$this_dir/lib/' + str(entry).replace(os.sep, '/') for entry in entries)
    for target in targets:
        launcher = out / target.slug
        flags = ' '.join(JVM_FLAGS)
        launcher.write_text(f'''#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
# Fuzzes {target.name} with Jazzer on the bundled JDK (tools/fuzz/fuzz.py package).
this_dir=$(dirname "$0")
if [[ "$@" =~ (^| )-runs=[0-9]+($| ) ]]; then memory=1536; else memory=2048; fi
exec "$this_dir/jdk/bin/java" {flags} -Xmx${{memory}}m -cp "{paths[target.module]}" {DRIVER} \\
  --target_class={target.qualified} --target_method={target.method} '{INSTRUMENT}' "$@"
''', newline='\n')
        launcher.chmod(0o755)
        seeds = [path for directory in corpus_dirs(target)[1:] for path in sorted(directory.iterdir())
                 if path.is_file()]
        with zipfile.ZipFile(out / f'{target.slug}_seed_corpus.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
            for index, seed in enumerate(seeds):
                archive.write(seed, f'{index:04d}-{seed.name}')
        if target.framed:
            (out / f'{target.slug}.dict').write_text(MARKER_DICTIONARY, newline='\n')
    print(f'packaged {len(targets)} targets into {out}')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('list')
    p.add_argument('--select')
    p = commands.add_parser('build')
    p.add_argument('--online', action='store_true')
    p.add_argument('--select')
    p = commands.add_parser('seeds')
    p.add_argument('--select')
    for name in ('run', 'budget'):
        p = commands.add_parser(name)
        if name == 'run':
            p.add_argument('target')
        else:
            p.add_argument('--select')
        p.add_argument('--time', type=int, default=60 if name == 'run' else 30)
        p.add_argument('--timeout', type=int, default=DEFAULT_TIMEOUT, help='seconds one input may take')
        p.add_argument('--memory', type=int, default=1536, help='JVM heap in MiB')
        p.add_argument('--no-build', action='store_true')
        p.add_argument('--no-seeds', action='store_true')
        p.add_argument('--online', action='store_true')
    p = commands.add_parser('reproduce')
    p.add_argument('target')
    p.add_argument('files', nargs='+')
    p = commands.add_parser('minimize')
    p.add_argument('target')
    p.add_argument('file')
    p.add_argument('--time', type=int, default=120)
    p = commands.add_parser('save')
    p.add_argument('target')
    p.add_argument('file')
    p.add_argument('--name')
    p = commands.add_parser('package')
    p.add_argument('--out', required=True)
    p.add_argument('--select')
    p.add_argument('--no-build', action='store_true')
    p.add_argument('--online', action='store_true')
    args = parser.parse_args()

    targets = discover()
    if args.command == 'list':
        for target in select(targets, args.select):
            print(target.name + ('  (packets)' if target.framed else ''))
    elif args.command == 'build':
        build(sorted({t.module for t in select(targets, args.select)}), args.online)
    elif args.command == 'seeds':
        gather_seeds(select(targets, args.select))
    elif args.command in ('run', 'budget'):
        chosen = [by_name(targets, args.target)] if args.command == 'run' else select(targets, args.select)
        if not args.no_build:
            build(sorted({t.module for t in chosen} | {'core'}), args.online)
        if not args.no_seeds:
            gather_seeds(chosen)
        results = []
        for target in chosen:
            result = fuzz(target, args.time, args.timeout, args.memory)
            report(result)
            results.append(result)
        failed = [r for r in results if r['findings'] or r['code'] != 0]
        print(f'{len(results) - len(failed)} of {len(results)} targets found nothing')
        sys.exit(1 if failed else 0)
    elif args.command == 'reproduce':
        target = by_name(targets, args.target)
        result = subprocess.run(command(target, [str(Path(f).resolve()) for f in args.files]))
        sys.exit(result.returncode)
    elif args.command == 'minimize':
        target = by_name(targets, args.target)
        output = target.work / 'minimized'
        output.mkdir(parents=True, exist_ok=True)
        result = subprocess.run(command(target, ['-minimize_crash=1', f'-max_total_time={args.time}',
                                                 f'-exact_artifact_path={output / Path(args.file).name}',
                                                 str(Path(args.file).resolve())]))
        print(f'minimized input: {output / Path(args.file).name}')
        sys.exit(0 if result.returncode in (0, 1) else result.returncode)
    elif args.command == 'save':
        target = by_name(targets, args.target)
        target.regressions.mkdir(parents=True, exist_ok=True)
        destination = target.regressions / (args.name or Path(args.file).name)
        shutil.copyfile(args.file, destination)
        print(f'saved {destination}')
    elif args.command == 'package':
        chosen = select(targets, args.select)
        if not args.no_build:
            build(sorted({t.module for t in chosen} | {'core'}), args.online)
        package(chosen, args.out)


if __name__ == '__main__':
    main()
