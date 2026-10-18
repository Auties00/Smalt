#!/usr/bin/env python3
"""Fetches, checks and maintains the test data Smalt's tests read: conformance vectors and raw clips.

Two manifests live next to this script:

  vectors.tsv  tier, name, size, sha256, url
               The files Conformance.fetch asks for. They land in the conformance cache, by default
               ~/.smalt/cache/conformance (overridden by SMALT_CONFORMANCE_CACHE, as in the tests).
  clips.tsv    tier, name, size, sha256, source_url, source_sha256, start, frames, filter
               Raw planar 4:2:0 clips the real-time encoder tests and reports read, derived with ffmpeg
               from Xiph's test sequences. They land in ~/.smalt/cache/clips (SMALT_TEST_CLIPS
               overrides it; the tests read the smalt.test.clips system property, same default).

Tiers nest: "smoke" (small enough for the GitHub Actions cache) is part of "full". A test whose data
is absent skips, so any subset of the manifest is a valid cache.

Commands:
  fetch [--tier smoke|full] [--no-clips]   downloads what the tier needs and is missing or corrupt
        [--clips-only] [--host H]...      only the clips; only (or with --exclude-host, all but) the
        [--exclude-host H]...             vectors downloaded from host H, so that CI can cache a part of
                                          a tier in one entry and download the rest
  verify [--tier ...]                      checks the cached files against the manifest
  size                                     prints the size of each tier
  record FILE... [--check]                 merges the vectors that tests requested with
                                           -Dsmalt.conformance.record=FILE into vectors.tsv (new
                                           ones join the full tier); --check only fails when the
                                           manifest misses one, which is how CI catches drift
"""
import argparse
import concurrent.futures
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
VECTORS = os.path.join(HERE, 'vectors.tsv')
CLIPS = os.path.join(HERE, 'clips.tsv')
TIERS = ['smoke', 'full']
VECTOR_COLUMNS = ['tier', 'name', 'size', 'sha256', 'url']
CLIP_COLUMNS = ['tier', 'name', 'size', 'sha256', 'source_url', 'source_sha256', 'start', 'frames', 'filter']
USER_AGENT = 'smalt-testdata/1 (+https://github.com/Auties00/Smalt)'


def conformance_dir():
    configured = os.environ.get('SMALT_CONFORMANCE_CACHE', '').strip()
    if configured:
        return configured
    return os.path.join(os.path.expanduser('~'), '.smalt', 'cache', 'conformance')


def clips_dir():
    configured = os.environ.get('SMALT_TEST_CLIPS', '').strip()
    return configured or os.path.join(os.path.expanduser('~'), '.smalt', 'cache', 'clips')


def read_tsv(path, columns):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, encoding='utf-8') as source:
        for line in source:
            line = line.rstrip('\r\n')
            if not line or line.startswith('#'):
                continue
            values = line.split('\t')
            if len(values) != len(columns):
                sys.exit(f'{os.path.basename(path)}: expected {len(columns)} columns: {line}')
            rows.append(dict(zip(columns, values)))
    return rows


def write_vectors(rows):
    rows = sorted(rows, key=lambda row: (TIERS.index(row['tier']), row['name']))
    with open(VECTORS, 'w', encoding='utf-8', newline='\n') as sink:
        sink.write('# Conformance vectors requested through Conformance.fetch; see testdata.py. Columns:\n')
        sink.write('# ' + '\t'.join(VECTOR_COLUMNS) + '\n')
        for row in rows:
            sink.write('\t'.join(str(row[column]) for column in VECTOR_COLUMNS) + '\n')


def in_tier(row, tier):
    return TIERS.index(row['tier']) <= TIERS.index(tier)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def valid(path, expected):
    return os.path.isfile(path) and sha256(path) == expected


def download(url, target, expected):
    """Downloads url to target through a temporary file, verifying the SHA-256."""
    os.makedirs(os.path.dirname(target), exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=os.path.dirname(target), prefix='download-', suffix='.part')
    os.close(handle)
    try:
        request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=120) as response, open(temporary, 'wb') as sink:
                    shutil.copyfileobj(response, sink, 1 << 20)
                break
            except OSError:
                if attempt == 2:
                    raise
        actual = sha256(temporary)
        if actual != expected:
            raise ValueError(f'SHA-256 mismatch for {url}: expected {expected}, got {actual}')
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)


def host(url):
    return urllib.parse.urlparse(url).netloc.lower()


def fetch_vectors(tier, jobs, hosts=(), excluded=()):
    directory = conformance_dir()
    wanted = [row for row in read_tsv(VECTORS, VECTOR_COLUMNS) if in_tier(row, tier)
              and (not hosts or host(row['url']) in hosts) and host(row['url']) not in excluded]
    missing = [row for row in wanted if not valid(os.path.join(directory, row['name']), row['sha256'])]
    print(f'vectors: {len(wanted)} in the {tier} tier, {len(missing)} to download into {directory}')
    failures = []

    def one(row):
        try:
            download(row['url'], os.path.join(directory, row['name']), row['sha256'])
            return None
        except Exception as exception:  # reported below; one dead link must not stop the others
            return f'{row["name"]}: {exception}'

    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        for result in pool.map(one, missing):
            if result:
                failures.append(result)
                print('  failed', result, file=sys.stderr)
    return failures


def fetch_clips(tier):
    directory = clips_dir()
    wanted = [row for row in read_tsv(CLIPS, CLIP_COLUMNS) if in_tier(row, tier)]
    missing = [row for row in wanted if not os.path.isfile(os.path.join(directory, row['name']))]
    print(f'clips: {len(wanted)} in the {tier} tier, {len(missing)} to derive into {directory}')
    if not missing:
        return []
    if shutil.which('ffmpeg') is None:
        return ['clips need ffmpeg on PATH']
    failures = []
    sources = {}
    with tempfile.TemporaryDirectory(prefix='smalt-clips-') as work:
        for row in missing:
            try:
                source = sources.get(row['source_url'])
                if source is None:
                    name = row['source_url'].rsplit('/', 1)[-1]
                    cached = os.path.join(conformance_dir(), name)
                    source = cached if valid(cached, row['source_sha256']) else os.path.join(work, name)
                    if source != cached:
                        print('  downloading', row['source_url'])
                        download(row['source_url'], source, row['source_sha256'])
                    sources[row['source_url']] = source
                derive(source, row, directory)
            except Exception as exception:
                failures.append(f'{row["name"]}: {exception}')
                print('  failed', failures[-1], file=sys.stderr)
    return failures


def derive(source, row, directory):
    """Writes a clip: frames [start, start + frames) of the source, through the filter, as raw yuv420p."""
    os.makedirs(directory, exist_ok=True)
    target = os.path.join(directory, row['name'])
    temporary = target + '.part'
    chain = [f'trim=start_frame={row["start"]}']
    if row['filter'] != '-':
        chain.append(row['filter'])
    # Passthrough timing: a constant-rate output would duplicate or drop frames around the trim.
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', source, '-vf', ','.join(chain), '-fps_mode', 'passthrough',
                    '-frames:v', row['frames'], '-pix_fmt', 'yuv420p', '-f', 'rawvideo', temporary], check=True)
    actual = sha256(temporary)
    os.replace(temporary, target)
    if actual != row['sha256']:
        # Only scaled clips depend on ffmpeg's swscale version; they feed quality reports and encoder
        # tests that check self-consistency, so a slightly different scaler output is still usable.
        print(f'  warning: {row["name"]} differs from the reference derivation (ffmpeg version?): {actual}',
              file=sys.stderr)


def command_fetch(arguments):
    failures = []
    if not arguments.clips_only:
        failures += fetch_vectors(arguments.tier, arguments.jobs, {h.lower() for h in arguments.host},
                                  {h.lower() for h in arguments.exclude_host})
    if not arguments.no_clips:
        failures += fetch_clips(arguments.tier)
    if failures:
        print(f'{len(failures)} item(s) could not be fetched; the tests that need them will skip', file=sys.stderr)
        if arguments.strict:
            sys.exit(1)


def command_verify(arguments):
    directory = conformance_dir()
    bad = 0
    for row in read_tsv(VECTORS, VECTOR_COLUMNS):
        if not in_tier(row, arguments.tier):
            continue
        path = os.path.join(directory, row['name'])
        if not os.path.isfile(path):
            print('missing', row['name'])
            bad += 1
        elif sha256(path) != row['sha256']:
            print('corrupt', row['name'])
            bad += 1
    print(f'{bad} problem(s) in {directory}')
    sys.exit(1 if bad else 0)


def command_size(_):
    vectors = read_tsv(VECTORS, VECTOR_COLUMNS)
    clips = read_tsv(CLIPS, CLIP_COLUMNS)
    for tier in TIERS:
        v = [row for row in vectors if in_tier(row, tier)]
        c = [row for row in clips if in_tier(row, tier)]
        size = sum(int(row['size']) for row in v)
        clip_size = sum(int(row['size']) for row in c)
        print(f'{tier}: {len(v)} vectors, {size / 1e6:.1f} MB; {len(c)} clips, {clip_size / 1e6:.1f} MB')


def command_record(arguments):
    rows = {row['name']: row for row in read_tsv(VECTORS, VECTOR_COLUMNS)}
    requested = {}
    for path in arguments.files:
        with open(path, encoding='utf-8') as source:
            for line in source:
                fields = line.rstrip('\r\n').split('\t')
                if len(fields) == 3:
                    requested[fields[0]] = fields
    added, changed = [], []
    directory = conformance_dir()
    for name, (_, url, digest) in sorted(requested.items()):
        row = rows.get(name)
        if row is None:
            path = os.path.join(directory, name)
            size = os.path.getsize(path) if os.path.isfile(path) else 0
            rows[name] = {'tier': 'full', 'name': name, 'size': size, 'sha256': digest, 'url': url}
            added.append(name)
        elif row['url'] != url or row['sha256'] != digest:
            row['url'], row['sha256'] = url, digest
            changed.append(name)
    unused = sorted(set(rows) - set(requested))
    print(f'{len(requested)} requested: {len(added)} new, {len(changed)} changed, {len(unused)} in the manifest '
          f'but not requested by this run')
    for name in added:
        print('  new', name)
    for name in changed:
        print('  changed', name)
    if arguments.check:
        sys.exit(1 if added or changed else 0)
    write_vectors(list(rows.values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    fetch = commands.add_parser('fetch')
    fetch.add_argument('--tier', choices=TIERS, default='smoke')
    fetch.add_argument('--no-clips', action='store_true')
    fetch.add_argument('--clips-only', action='store_true')
    fetch.add_argument('--host', action='append', default=[], help='only the vectors downloaded from this host')
    fetch.add_argument('--exclude-host', action='append', default=[], help='not the vectors from this host')
    fetch.add_argument('--jobs', type=int, default=4)
    fetch.add_argument('--strict', action='store_true', help='fail when an item cannot be fetched')
    fetch.set_defaults(handler=command_fetch)
    verify = commands.add_parser('verify')
    verify.add_argument('--tier', choices=TIERS, default='full')
    verify.set_defaults(handler=command_verify)
    size = commands.add_parser('size')
    size.set_defaults(handler=command_size)
    record = commands.add_parser('record')
    record.add_argument('files', nargs='+')
    record.add_argument('--check', action='store_true')
    record.set_defaults(handler=command_record)
    arguments = parser.parse_args()
    arguments.handler(arguments)


if __name__ == '__main__':
    main()
