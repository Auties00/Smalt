#!/usr/bin/env python3
"""Fetches, checks and maintains the test data Smalt's tests read: conformance vectors, archive members and raw clips.

Four manifests live next to this script:

  vectors.tsv   tier, name, size, sha256, url
                The files Conformance.fetch asks for. They land in the conformance cache, by default
                ~/.smalt/cache/conformance (overridden by SMALT_CONFORMANCE_CACHE, as in the tests).
  archives.tsv  tier, name, size, cdsha256, url, members, licence
                Sets of members of large ZIP archives that Conformance.archive asks for (the Argon AV1
                streams, the DASH-IF livesim content): each row names an archive by URL and the SHA-256 of its
                central directory, and selects the members whose path matches the regular expression in
                members. Only those members are downloaded, by HTTP range requests; each is checked against the
                size and CRC-32 that the pinned central directory records. They land in
                <conformance cache>/archives/<archive>-<cdsha256 prefix>/<member path>, the central directory
                next to that directory as <archive>-<cdsha256 prefix>.cd. size is the members' total size.
  clips.tsv     tier, name, size, sha256, source_url, source_sha256, start, frames, filter
                Raw planar 4:2:0 clips the real-time encoder tests and reports read, derived with ffmpeg
                from Xiph's test sequences. They land in ~/.smalt/cache/clips (SMALT_TEST_CLIPS
                overrides it; the tests read the smalt.test.clips system property, same default).
  licences.tsv  url prefix, licence note
                The licence or terms of use of every source: each URL of vectors.tsv and clips.tsv must start
                with one of the prefixes (archives.tsv carries its own licence column). None of this data is
                committed to the repository.

Tiers nest: "smoke" (small enough for the GitHub Actions cache) is part of "full". A test whose data
is absent skips, so any subset of the manifests is a valid cache.

Commands:
  fetch [--tier smoke|full] [--no-clips]   downloads what the tier needs and is missing or corrupt
        [--clips-only] [--host H]...      only the clips; only (or with --exclude-host, all but) the
        [--exclude-host H]...             vectors downloaded from host H, so that CI can cache a part of
                                          a tier in one entry and download the rest
        [--no-archives] [--set GLOB]...   no archive sets; only the sets whose name matches GLOB
        [--exclude-set GLOB]...           (with --set, nothing but those sets); not the matching sets
  verify [--tier ...]                      checks the cached files against the manifests
  size                                     prints the size of each tier
  record FILE... [--check]                 merges the vectors that tests requested with
                                           -Dsmalt.conformance.record=FILE into vectors.tsv (new
                                           ones join the full tier); --check only fails when the
                                           manifest misses one, which is how CI catches drift
  archive NAME URL REGEX --tier T --licence TEXT
                                           pins an archive set: reads the archive's central directory, writes
                                           its SHA-256 and the size of the matching members into archives.tsv
  licences                                 fails when a vector, clip or archive set has no licence note
"""
import argparse
import collections
import concurrent.futures
import fnmatch
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
VECTORS = os.path.join(HERE, 'vectors.tsv')
ARCHIVES = os.path.join(HERE, 'archives.tsv')
CLIPS = os.path.join(HERE, 'clips.tsv')
LICENCES = os.path.join(HERE, 'licences.tsv')
TIERS = ['smoke', 'full']
VECTOR_COLUMNS = ['tier', 'name', 'size', 'sha256', 'url']
ARCHIVE_COLUMNS = ['tier', 'name', 'size', 'cdsha256', 'url', 'members', 'licence']
CLIP_COLUMNS = ['tier', 'name', 'size', 'sha256', 'source_url', 'source_sha256', 'start', 'frames', 'filter']
LICENCE_COLUMNS = ['prefix', 'licence']
USER_AGENT = 'smalt-testdata/1 (+https://github.com/Auties00/Smalt)'
# Pauses between attempts: hosts of conformance vectors answer 5xx for minutes at a time.
PAUSES = (10, 30, 60, 90, 120, None)


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


def line_ending(path):
    """Returns the line ending a manifest uses, so that a rewrite keeps it."""
    if os.path.exists(path):
        with open(path, 'rb') as source:
            if b'\r\n' in source.read(4096):
                return '\r\n'
    return '\n'


def write_rows(path, header, columns, rows):
    newline = line_ending(path)
    with open(path, 'w', encoding='utf-8', newline='') as sink:
        sink.write(header + newline)
        sink.write('# ' + '\t'.join(columns) + newline)
        for row in rows:
            sink.write('\t'.join(str(row[column]) for column in columns) + newline)


def write_vectors(rows):
    rows = sorted(rows, key=lambda row: (TIERS.index(row['tier']), row['name']))
    write_rows(VECTORS, '# Conformance vectors requested through Conformance.fetch; see testdata.py. Columns:',
               VECTOR_COLUMNS, rows)


def write_archives(rows):
    rows = sorted(rows, key=lambda row: (TIERS.index(row['tier']), row['name']))
    write_rows(ARCHIVES, '# Archive member sets requested through Conformance.archive; see testdata.py. Columns:',
               ARCHIVE_COLUMNS, rows)


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


def retrying(action):
    """Runs action, retrying an OSError with the pauses of PAUSES.

    A client error (a dead link, a redirect loop) does not heal with time: only 5xx, 429 and network errors are
    retried."""
    for pause in PAUSES:
        try:
            return action()
        except OSError as exception:
            status = getattr(exception, 'code', None)
            if pause is None or (isinstance(status, int) and status < 500 and status != 429):
                raise
            time.sleep(pause)


def download(url, target, expected):
    """Downloads url to target through a temporary file, verifying the SHA-256."""
    os.makedirs(os.path.dirname(target), exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=os.path.dirname(target), prefix='download-', suffix='.part')
    os.close(handle)
    try:
        request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})

        def copy():
            with urllib.request.urlopen(request, timeout=120) as response, open(temporary, 'wb') as sink:
                shutil.copyfileobj(response, sink, 1 << 20)
                # a connection closed early ends the body without an error: compare with the announced length
                announced = response.headers.get('Content-Length')
                if announced is not None and announced.isdigit() and sink.tell() != int(announced):
                    raise ConnectionError(f'short download of {url}: {sink.tell()} of {announced} bytes')

        retrying(copy)
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


# ZIP archives read by range requests (APPNOTE.TXT 6.3: end of central directory, ZIP64 records, central
# directory headers, local file headers).

Entry = collections.namedtuple('Entry', 'name method flags crc csize usize offset')


def http_range(url, start, end):
    """Returns bytes [start, end) of url."""
    request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT, 'Range': f'bytes={start}-{end - 1}'})

    def read():
        with urllib.request.urlopen(request, timeout=300) as response:
            if response.status != 206:
                raise OSError(f'HTTP {response.status} for a range of {url}')
            data = response.read()
            if len(data) != end - start:
                raise OSError(f'short range read of {url}: {len(data)} of {end - start} bytes')
            return data

    return retrying(read)


def http_size(url):
    request = urllib.request.Request(url, method='HEAD', headers={'User-Agent': USER_AGENT})

    def head():
        with urllib.request.urlopen(request, timeout=120) as response:
            return int(response.headers['Content-Length'])

    return retrying(head)


def read_directory(url):
    """Downloads the central directory of a ZIP archive: returns (bytes from its start to the end of the file,
    offset of its start)."""
    size = http_size(url)
    tail_start = max(0, size - (65535 + 22 + 20 + 56))
    tail = http_range(url, tail_start, size)
    eocd = tail.rfind(b'PK\x05\x06')
    if eocd < 0:
        raise ValueError(f'{url} is not a ZIP archive')
    entries, cd_size, cd_offset = struct.unpack_from('<10xHII', tail, eocd)
    if cd_offset == 0xFFFFFFFF or entries == 0xFFFF or cd_size == 0xFFFFFFFF:
        locator = eocd - 20
        if locator < 0 or tail[locator:locator + 4] != b'PK\x06\x07':
            raise ValueError(f'{url}: missing ZIP64 end of central directory locator')
        (record,) = struct.unpack_from('<Q', tail, locator + 8)
        if record >= tail_start:
            header = tail[record - tail_start:record - tail_start + 56]
        else:
            header = http_range(url, record, record + 56)
        if header[:4] != b'PK\x06\x06':
            raise ValueError(f'{url}: corrupt ZIP64 end of central directory record')
        (cd_offset,) = struct.unpack_from('<Q', header, 48)
    if cd_offset >= tail_start:
        return tail[cd_offset - tail_start:], cd_offset
    return http_range(url, cd_offset, tail_start) + tail, cd_offset


def parse_directory(directory):
    """Returns the entries of a central directory (the bytes read_directory returns)."""
    entries = []
    position = 0
    while directory[position:position + 4] == b'PK\x01\x02':
        (flags, method, crc, csize, usize, name_length, extra_length, comment_length,
         offset) = struct.unpack_from('<8xHH4xIIIHHH8xI', directory, position)
        name = directory[position + 46:position + 46 + name_length].decode('utf-8' if flags & 0x800 else 'cp437')
        extra = directory[position + 46 + name_length:position + 46 + name_length + extra_length]
        cursor = 0
        while cursor + 4 <= len(extra):
            tag, length = struct.unpack_from('<HH', extra, cursor)
            if tag == 1:
                values = extra[cursor + 4:cursor + 4 + length]
                index = 0
                if usize == 0xFFFFFFFF:
                    (usize,) = struct.unpack_from('<Q', values, index)
                    index += 8
                if csize == 0xFFFFFFFF:
                    (csize,) = struct.unpack_from('<Q', values, index)
                    index += 8
                if offset == 0xFFFFFFFF:
                    (offset,) = struct.unpack_from('<Q', values, index)
            cursor += 4 + length
        entries.append(Entry(name, method, flags, crc, csize, usize, offset))
        position += 46 + name_length + extra_length + comment_length
    return entries


def archive_id(row):
    base = os.path.basename(urllib.parse.urlparse(row['url']).path)
    return re.sub(r'\.zip$', '', base, flags=re.IGNORECASE) + '-' + row['cdsha256'][:16]


def archive_directory(row):
    """Returns the verified central directory of an archive set, from the cache or downloaded into it, and the
    offset of the directory in the archive."""
    cache = os.path.join(conformance_dir(), 'archives')
    path = os.path.join(cache, archive_id(row) + '.cd')
    if os.path.isfile(path):
        with open(path, 'rb') as source:
            data = source.read()
        if hashlib.sha256(data).hexdigest() == row['cdsha256']:
            return data, directory_offset(data)
    data, offset = read_directory(row['url'])
    actual = hashlib.sha256(data).hexdigest()
    if actual != row['cdsha256']:
        raise ValueError(f'central directory SHA-256 mismatch for {row["url"]}: expected {row["cdsha256"]}, '
                         f'got {actual}')
    os.makedirs(cache, exist_ok=True)
    with open(path + '.part', 'wb') as sink:
        sink.write(data)
    os.replace(path + '.part', path)
    return data, offset


def directory_offset(data):
    """Returns the offset in the archive of a central directory, read from the end of central directory record
    (or its ZIP64 counterpart) that follows the directory headers in data."""
    position = 0
    while data[position:position + 4] == b'PK':
        name_length, extra_length, comment_length = struct.unpack_from('<HHH', data, position + 28)
        position += 46 + name_length + extra_length + comment_length
    if data[position:position + 4] == b'PK':
        return struct.unpack_from('<Q', data, position + 48)[0]
    if data[position:position + 4] == b'PK':
        return struct.unpack_from('<I', data, position + 16)[0]
    raise ValueError('no end of central directory record after the directory')


def selected(row, entries):
    pattern = re.compile(row['members'])
    return [entry for entry in entries if not entry.name.endswith('/') and pattern.search(entry.name)]


def member_path(row, name):
    return os.path.join(conformance_dir(), 'archives', archive_id(row), *name.split('/'))


def member_valid(path, entry):
    if not os.path.isfile(path) or os.path.getsize(path) != entry.usize:
        return False
    crc = 0
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1 << 20), b''):
            crc = zlib.crc32(block, crc)
    return crc == entry.crc


def extract(entry, data):
    """Returns the content of a member from the bytes that start at its local file header."""
    if data[:4] != b'PK\x03\x04':
        raise ValueError(f'{entry.name}: no local file header')
    name_length, extra_length = struct.unpack_from('<HH', data, 26)
    start = 30 + name_length + extra_length
    payload = data[start:start + entry.csize]
    if entry.flags & 1:
        raise ValueError(f'{entry.name}: encrypted member')
    if entry.method == 0:
        content = payload
    elif entry.method == 8:
        content = zlib.decompress(payload, -15)
    else:
        raise ValueError(f'{entry.name}: compression method {entry.method}')
    if len(content) != entry.usize or zlib.crc32(content) != entry.crc:
        raise ValueError(f'{entry.name}: size or CRC-32 differs from the central directory')
    return content


def fetch_archive(row, jobs):
    """Downloads the missing members of an archive set; returns the failures."""
    try:
        directory, cd_offset = archive_directory(row)
    except Exception as exception:
        return [f'{row["name"]}: {exception}']
    entries = parse_directory(directory)
    ends = {}
    ordered = sorted(entries, key=lambda entry: entry.offset)
    for index, entry in enumerate(ordered):
        ends[entry.name] = ordered[index + 1].offset if index + 1 < len(ordered) else cd_offset
    wanted = selected(row, entries)
    missing = sorted((entry for entry in wanted if not member_valid(member_path(row, entry.name), entry)),
                     key=lambda entry: entry.offset)
    print(f'archive set {row["name"]}: {len(wanted)} members, {len(missing)} to download')
    # Neighboring members are read in one range request.
    groups = []
    for entry in missing:
        if groups and entry.offset - ends[groups[-1][-1].name] < (1 << 20) \
                and ends[entry.name] - groups[-1][0].offset < (32 << 20):
            groups[-1].append(entry)
        else:
            groups.append([entry])

    def one(group):
        try:
            start = group[0].offset
            data = http_range(row['url'], start, ends[group[-1].name])
            for entry in group:
                content = extract(entry, data[entry.offset - start:ends[entry.name] - start])
                target = member_path(row, entry.name)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target + '.part', 'wb') as sink:
                    sink.write(content)
                os.replace(target + '.part', target)
            return None
        except Exception as exception:
            return f'{row["name"]}: {group[0].name} and {len(group) - 1} more: {exception}'

    failures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(jobs, 8)) as pool:
        for result in pool.map(one, groups):
            if result:
                failures.append(result)
                print('  failed', result, file=sys.stderr)
    return failures


def archive_rows(tier, sets=(), excluded=()):
    return [row for row in read_tsv(ARCHIVES, ARCHIVE_COLUMNS) if in_tier(row, tier)
            and (not sets or any(fnmatch.fnmatch(row['name'], glob) for glob in sets))
            and not any(fnmatch.fnmatch(row['name'], glob) for glob in excluded)]


def fetch_clips(tier, strict=False):
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
                derive(source, row, directory, strict)
            except Exception as exception:
                failures.append(f'{row["name"]}: {exception}')
                print('  failed', failures[-1], file=sys.stderr)
    return failures


def derive(source, row, directory, strict=False):
    """Writes a clip: frames [start, start + frames) of the source, through the filter, as raw yuv420p.

    A clip whose SHA-256 differs from its pin is kept with a warning, or, when strict, deleted and reported as a
    failure: the encoder golden tests pin the bitstreams of the pinned clips, and swscale's scaled output differs
    between ffmpeg versions and between its x86 and arm64 kernels (CI derives the clips on Linux x64 once and shares
    them with every runner through a cross-OS cache entry)."""
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
    if actual != row['sha256'] and strict:
        os.remove(temporary)
        raise ValueError(f'the derivation has SHA-256 {actual}, not the pinned {row["sha256"]} (ffmpeg version or '
                         f'architecture?)')
    os.replace(temporary, target)
    if actual != row['sha256']:
        print(f'  warning: {row["name"]} differs from the reference derivation (ffmpeg version?): {actual}',
              file=sys.stderr)


def command_fetch(arguments):
    failures = []
    sets_only = bool(arguments.set) and not arguments.host
    if not arguments.clips_only and not sets_only:
        failures += fetch_vectors(arguments.tier, arguments.jobs, {h.lower() for h in arguments.host},
                                  {h.lower() for h in arguments.exclude_host})
    if not arguments.clips_only and not arguments.no_archives:
        # a set named with --set is fetched whatever its tier
        tier = 'full' if arguments.set else arguments.tier
        for row in archive_rows(tier, arguments.set, arguments.exclude_set):
            failures += fetch_archive(row, arguments.jobs)
    if not arguments.no_clips and not sets_only:
        failures += fetch_clips(arguments.tier, arguments.strict)
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
    for row in archive_rows('full' if arguments.set else arguments.tier, arguments.set, arguments.exclude_set):
        path = os.path.join(directory, 'archives', archive_id(row) + '.cd')
        if not os.path.isfile(path):
            print('missing the central directory of', row['name'])
            bad += 1
            continue
        with open(path, 'rb') as source:
            data = source.read()
        if hashlib.sha256(data).hexdigest() != row['cdsha256']:
            print('corrupt central directory of', row['name'])
            bad += 1
            continue
        for entry in selected(row, parse_directory(data)):
            if not member_valid(member_path(row, entry.name), entry):
                print('missing or corrupt', row['name'], entry.name)
                bad += 1
    print(f'{bad} problem(s) in {directory}')
    sys.exit(1 if bad else 0)


def command_size(_):
    vectors = read_tsv(VECTORS, VECTOR_COLUMNS)
    archives = read_tsv(ARCHIVES, ARCHIVE_COLUMNS)
    clips = read_tsv(CLIPS, CLIP_COLUMNS)
    for tier in TIERS:
        v = [row for row in vectors if in_tier(row, tier)]
        a = [row for row in archives if in_tier(row, tier)]
        c = [row for row in clips if in_tier(row, tier)]
        size = sum(int(row['size']) for row in v)
        archive_size = sum(int(row['size']) for row in a)
        clip_size = sum(int(row['size']) for row in c)
        print(f'{tier}: {len(v)} vectors, {size / 1e6:.1f} MB; {len(a)} archive sets, {archive_size / 1e6:.1f} MB; '
              f'{len(c)} clips, {clip_size / 1e6:.1f} MB')


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


def command_archive(arguments):
    re.compile(arguments.members)
    if '\t' in arguments.licence or '\t' in arguments.members:
        sys.exit('the members and the licence may not hold tabs')
    pinned = [other for other in read_tsv(ARCHIVES, ARCHIVE_COLUMNS) if other['url'] == arguments.url]
    directory = archive_directory(pinned[0])[0] if pinned else read_directory(arguments.url)[0]
    row = {'tier': arguments.tier, 'name': arguments.name, 'cdsha256': hashlib.sha256(directory).hexdigest(),
           'url': arguments.url, 'members': arguments.members, 'licence': arguments.licence}
    members = selected(row, parse_directory(directory))
    if not members:
        sys.exit(f'no member of {arguments.url} matches {arguments.members}')
    row['size'] = sum(entry.usize for entry in members)
    rows = [other for other in read_tsv(ARCHIVES, ARCHIVE_COLUMNS) if other['name'] != arguments.name]
    write_archives(rows + [row])
    print(f'{arguments.name}: {len(members)} members, {row["size"] / 1e6:.1f} MB, central directory '
          f'{row["cdsha256"]}')


def command_licences(_):
    prefixes = [row['prefix'] for row in read_tsv(LICENCES, LICENCE_COLUMNS)]
    urls = [(row['name'], row['url']) for row in read_tsv(VECTORS, VECTOR_COLUMNS)]
    urls += [(row['name'], row['source_url']) for row in read_tsv(CLIPS, CLIP_COLUMNS)]
    missing = [(name, url) for name, url in urls if not any(url.startswith(prefix) for prefix in prefixes)]
    unnoted = [row['name'] for row in read_tsv(ARCHIVES, ARCHIVE_COLUMNS) if not row['licence'].strip()]
    for name, url in missing[:50]:
        print(f'no licence note for {name}: {url}')
    for name in unnoted:
        print(f'no licence note for the archive set {name}')
    used = {prefix for prefix in prefixes if any(url.startswith(prefix) for _, url in urls)}
    for prefix in prefixes:
        if prefix not in used:
            print(f'unused licence prefix: {prefix}')
    print(f'{len(urls)} sources: {len(missing)} without a licence note, {len(unnoted)} archive sets without one')
    sys.exit(1 if missing or unnoted else 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    fetch = commands.add_parser('fetch')
    fetch.add_argument('--tier', choices=TIERS, default='smoke')
    fetch.add_argument('--no-clips', action='store_true')
    fetch.add_argument('--clips-only', action='store_true')
    fetch.add_argument('--host', action='append', default=[], help='only the vectors downloaded from this host')
    fetch.add_argument('--exclude-host', action='append', default=[], help='not the vectors from this host')
    fetch.add_argument('--no-archives', action='store_true', help='no archive sets')
    fetch.add_argument('--set', action='append', default=[], help='only the archive sets matching this glob')
    fetch.add_argument('--exclude-set', action='append', default=[], help='not the archive sets matching this glob')
    fetch.add_argument('--jobs', type=int, default=4)
    fetch.add_argument('--strict', action='store_true', help='fail when an item cannot be fetched')
    fetch.set_defaults(handler=command_fetch)
    verify = commands.add_parser('verify')
    verify.add_argument('--tier', choices=TIERS, default='full')
    verify.add_argument('--set', action='append', default=[])
    verify.add_argument('--exclude-set', action='append', default=[])
    verify.set_defaults(handler=command_verify)
    size = commands.add_parser('size')
    size.set_defaults(handler=command_size)
    record = commands.add_parser('record')
    record.add_argument('files', nargs='+')
    record.add_argument('--check', action='store_true')
    record.set_defaults(handler=command_record)
    archive = commands.add_parser('archive')
    archive.add_argument('name')
    archive.add_argument('url')
    archive.add_argument('members')
    archive.add_argument('--tier', choices=TIERS, default='full')
    archive.add_argument('--licence', required=True)
    archive.set_defaults(handler=command_archive)
    licences = commands.add_parser('licences')
    licences.set_defaults(handler=command_licences)
    arguments = parser.parse_args()
    arguments.handler(arguments)


if __name__ == '__main__':
    main()
