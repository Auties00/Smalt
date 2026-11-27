#!/usr/bin/env python3
"""Checks and publishes the test data Smalt's tests read: conformance vectors, archive members and raw clips.

The project hosts all of its test data, and the tests read nothing else: nothing here downloads from the
sources the manifests name, which are kept for provenance and licences only. The data lives in one directory,
SMALT_TESTDATA (default ~/.smalt/testdata), laid out as

  vectors/<name>             one file per row of vectors.tsv
  archives/<set>.idx         the members of a row of archives.tsv, "path<TAB>size<TAB>crc32" lines
  archives/<id>/<member>     those members; <id> is the archive's file name without .zip, a dash and the first 12
                             hex digits of the SHA-256 of its source URL
  clips/<name>               one file per row of clips.tsv

and is published as one OCI artifact per area on GHCR, pinned in artifacts.tsv. CI pulls the pinned artifacts
into SMALT_TESTDATA before the tests run.

Manifests:
  vectors.tsv    area, name, size, sha256, source
  archives.tsv   area, name, size, index_sha256, source, members, licence
  clips.tsv      name, size, sha256, source_url, source_sha256, start, frames, filter
  licences.tsv   prefix, licence: the terms of every source of vectors.tsv and clips.tsv, by URL prefix
  artifacts.tsv  area, reference: the published artifact of each area, by digest

Commands:
  verify [--area A]...          checks the data directory against the manifests
  size                          prints the size of each area
  licences                      fails when a vector or clip has no licence note
  add AREA FILE SOURCE [--name N]
                                copies a local file into the data directory and lists it in vectors.tsv
  pack [--area A]... --out DIR  writes the tar shards of each area's artifact into DIR/<area>/
  push [--area A]... --out DIR  pushes the packed shards with oras and pins the digests in artifacts.tsv
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
VECTORS = os.path.join(HERE, 'vectors.tsv')
ARCHIVES = os.path.join(HERE, 'archives.tsv')
CLIPS = os.path.join(HERE, 'clips.tsv')
LICENCES = os.path.join(HERE, 'licences.tsv')
ARTIFACTS = os.path.join(HERE, 'artifacts.tsv')
VECTOR_COLUMNS = ['area', 'name', 'size', 'sha256', 'source']
ARCHIVE_COLUMNS = ['area', 'name', 'size', 'index_sha256', 'source', 'members', 'licence']
CLIP_COLUMNS = ['name', 'size', 'sha256', 'source_url', 'source_sha256', 'start', 'frames', 'filter']
LICENCE_COLUMNS = ['prefix', 'licence']
ARTIFACT_COLUMNS = ['area', 'reference']
REPOSITORY = 'ghcr.io/auties00/smalt-testdata'
SOURCE = 'org.opencontainers.image.source=https://github.com/Auties00/Smalt'
SHARD = 1 << 30
LAYER = 'application/vnd.oci.image.layer.v1.tar'
ARTIFACT_TYPE = 'application/vnd.smalt.testdata.v1'


def data_dir():
    configured = os.environ.get('SMALT_TESTDATA', '').strip()
    return configured or os.path.join(os.path.expanduser('~'), '.smalt', 'testdata')


def vector(name):
    """Returns the hosted copy of a vector of vectors.tsv, for the oracle scripts; exits when it is not there."""
    path = os.path.join(data_dir(), 'vectors', name)
    if not os.path.isfile(path):
        sys.exit(f'{name} is not in {data_dir()}: pull the hosted test data (see tools/testdata/README.md)')
    return path


def member(set_name, path):
    """Returns the hosted copy of a member of an archive set of archives.tsv, for the oracle scripts: the member
    named path, or ending in /path; exits when it is not there."""
    for row in read_tsv(ARCHIVES, ARCHIVE_COLUMNS):
        if row['name'] != set_name:
            continue
        if not os.path.isfile(os.path.join(data_dir(), 'archives', set_name + '.idx')):
            break
        for candidate, _, _ in index(row):
            if candidate == path or candidate.endswith('/' + path):
                file = os.path.join(data_dir(), 'archives', archive_id(row['source']), *storage(candidate).split('/'))
                if not os.path.isfile(file):
                    break
                return file
        sys.exit(f'{path} of {set_name} is not in {data_dir()}: pull the hosted test data (see tools/testdata/README.md)')
    sys.exit(f'{set_name} is not an archive set of archives.tsv, or is not in {data_dir()}')


def cache_dir(name):
    """Returns a directory of the writable cache, created if needed: SMALT_CACHE (default ~/.smalt/cache)/<name>.

    It is the directory Conformance.cache(name) gives the tests, so oracle outputs written here are where the
    tests look for them."""
    configured = os.environ.get('SMALT_CACHE', '').strip()
    path = os.path.join(configured or os.path.join(os.path.expanduser('~'), '.smalt', 'cache'), name)
    os.makedirs(path, exist_ok=True)
    return path


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


def header_of(path):
    with open(path, encoding='utf-8') as source:
        return ''.join(line for line in source if line.startswith('#'))


def write_tsv(path, columns, rows, key):
    header = header_of(path)
    with open(path, 'w', encoding='utf-8', newline='\n') as sink:
        sink.write(header)
        for row in sorted(rows, key=key):
            sink.write('\t'.join(str(row[column]) for column in columns) + '\n')


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1 << 22), b''):
            digest.update(block)
    return digest.hexdigest()


def crc32(path):
    value = 0
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1 << 22), b''):
            value = zlib.crc32(block, value)
    return value


def archive_id(source):
    path = re.sub(r'[?#].*$', '', source)
    base = re.sub(r'\.zip$', '', path[path.rfind('/') + 1:], flags=re.IGNORECASE)
    base = re.sub(r'[^A-Za-z0-9._()+-]', '_', base)
    return base + '-' + hashlib.sha256(source.encode('utf-8')).hexdigest()[:12]


def storage(member):
    return '/'.join(segment if segment not in ('', '.', '..') else '_' for segment in member.split('/'))


def index(row):
    """Returns the (member, size, crc32) entries of an archive set's index."""
    path = os.path.join(data_dir(), 'archives', row['name'] + '.idx')
    with open(path, encoding='utf-8') as source:
        return [(fields[0], int(fields[1]), int(fields[2], 16))
                for fields in (line.rstrip('\n').split('\t') for line in source if line.strip())]


def area_files(area):
    """Returns the data-directory paths (relative, with forward slashes) of an area, sorted."""
    files = [f'vectors/{row["name"]}' for row in read_tsv(VECTORS, VECTOR_COLUMNS) if row['area'] == area]
    for row in read_tsv(ARCHIVES, ARCHIVE_COLUMNS):
        if row['area'] == area:
            files.append(f'archives/{row["name"]}.idx')
            files += [f'archives/{archive_id(row["source"])}/{storage(member)}' for member, _, _ in index(row)]
    if area == 'clips':
        files = [f'clips/{row["name"]}' for row in read_tsv(CLIPS, CLIP_COLUMNS)]
    return sorted(set(files))


def areas():
    found = {row['area'] for row in read_tsv(VECTORS, VECTOR_COLUMNS)}
    found |= {row['area'] for row in read_tsv(ARCHIVES, ARCHIVE_COLUMNS)}
    return sorted(found) + ['clips']


def command_verify(arguments):
    root = data_dir()
    wanted = set(arguments.area)
    bad = checked = 0

    def problem(message):
        nonlocal bad
        bad += 1
        print(message)

    for row in read_tsv(VECTORS, VECTOR_COLUMNS):
        if wanted and row['area'] not in wanted:
            continue
        path = os.path.join(root, 'vectors', row['name'])
        checked += 1
        if not os.path.isfile(path):
            problem(f'missing vectors/{row["name"]}')
        elif os.path.getsize(path) != int(row['size']) or sha256(path) != row['sha256']:
            problem(f'corrupt vectors/{row["name"]}')
    for row in read_tsv(ARCHIVES, ARCHIVE_COLUMNS):
        if wanted and row['area'] not in wanted:
            continue
        path = os.path.join(root, 'archives', row['name'] + '.idx')
        if not os.path.isfile(path):
            problem(f'missing archives/{row["name"]}.idx')
            continue
        if sha256(path) != row['index_sha256']:
            problem(f'corrupt archives/{row["name"]}.idx')
            continue
        for member, size, crc in index(row):
            checked += 1
            file = os.path.join(root, 'archives', archive_id(row['source']), *storage(member).split('/'))
            if not os.path.isfile(file):
                problem(f'missing member {member} of {row["name"]}')
            elif os.path.getsize(file) != size or crc32(file) != crc:
                problem(f'corrupt member {member} of {row["name"]}')
    if not wanted or 'clips' in wanted:
        for row in read_tsv(CLIPS, CLIP_COLUMNS):
            path = os.path.join(root, 'clips', row['name'])
            checked += 1
            if not os.path.isfile(path):
                problem(f'missing clips/{row["name"]}')
            elif sha256(path) != row['sha256']:
                problem(f'corrupt clips/{row["name"]}')
    print(f'{checked} files checked, {bad} problem(s) in {root}')
    sys.exit(1 if bad else 0)


def command_size(_):
    vectors = read_tsv(VECTORS, VECTOR_COLUMNS)
    archives = read_tsv(ARCHIVES, ARCHIVE_COLUMNS)
    total = 0
    for area in areas():
        if area == 'clips':
            size = sum(int(row['size']) for row in read_tsv(CLIPS, CLIP_COLUMNS))
            print(f'clips: {len(read_tsv(CLIPS, CLIP_COLUMNS))} clips, {size / 1e9:.2f} GB')
        else:
            v = [row for row in vectors if row['area'] == area]
            a = [row for row in archives if row['area'] == area]
            size = sum(int(row['size']) for row in v) + sum(int(row['size']) for row in a)
            print(f'{area}: {len(v)} vectors, {len(a)} archive sets, {size / 1e9:.2f} GB')
        total += size
    print(f'total: {total / 1e9:.2f} GB')


def command_licences(_):
    prefixes = [row['prefix'] for row in read_tsv(LICENCES, LICENCE_COLUMNS)]
    sources = [(row['name'], row['source']) for row in read_tsv(VECTORS, VECTOR_COLUMNS)]
    sources += [(row['name'], row['source_url']) for row in read_tsv(CLIPS, CLIP_COLUMNS)]
    missing = [(name, url) for name, url in sources if not any(url.startswith(prefix) for prefix in prefixes)]
    unnoted = [row['name'] for row in read_tsv(ARCHIVES, ARCHIVE_COLUMNS) if not row['licence'].strip()]
    for name, url in missing[:50]:
        print(f'no licence note for {name}: {url}')
    for name in unnoted:
        print(f'no licence note for the archive set {name}')
    used = {prefix for prefix in prefixes if any(url.startswith(prefix) for _, url in sources)}
    for prefix in prefixes:
        if prefix not in used:
            print(f'unused licence prefix: {prefix}')
    print(f'{len(sources)} sources: {len(missing)} without a licence note, {len(unnoted)} archive sets without one')
    sys.exit(1 if missing or unnoted else 0)


def command_add(arguments):
    name = arguments.name or os.path.basename(arguments.file)
    if '\t' in name or '/' in name or '\\' in name:
        sys.exit('a vector name may not hold tabs or path separators')
    rows = [row for row in read_tsv(VECTORS, VECTOR_COLUMNS) if row['name'] != name]
    target = os.path.join(data_dir(), 'vectors', name)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    shutil.copyfile(arguments.file, target)
    rows.append({'area': arguments.area, 'name': name, 'size': os.path.getsize(target), 'sha256': sha256(target),
                 'source': arguments.source})
    write_tsv(VECTORS, VECTOR_COLUMNS, rows, lambda row: (row['area'], row['name']))
    print(f'{name}: listed in the {arguments.area} area; push the area to publish it')


def shards(area):
    """Splits an area's files into shards of about SHARD bytes, in path order."""
    root = data_dir()
    groups, current, size = [], [], 0
    for path in area_files(area):
        length = os.path.getsize(os.path.join(root, *path.split('/')))
        if current and size + length > SHARD:
            groups.append(current)
            current, size = [], 0
        current.append(path)
        size += length
    if current:
        groups.append(current)
    return groups


def command_pack(arguments):
    root = data_dir()
    for area in arguments.area or areas():
        directory = os.path.join(arguments.out, area)
        shutil.rmtree(directory, ignore_errors=True)
        os.makedirs(directory)
        groups = shards(area)
        for number, group in enumerate(groups):
            target = os.path.join(directory, f'{area}-{number:03d}.tar')
            # reproducible: fixed order, owner, mode and time, so that an unchanged shard keeps its digest
            with tarfile.open(target, 'w', format=tarfile.PAX_FORMAT) as tar:
                for path in group:
                    info = tar.gettarinfo(os.path.join(root, *path.split('/')), arcname=path)
                    info.uid = info.gid = 0
                    info.uname = info.gname = ''
                    info.mode = 0o644
                    info.mtime = 0
                    with open(os.path.join(root, *path.split('/')), 'rb') as source:
                        tar.addfile(info, source)
        print(f'{area}: {len(groups)} shard(s) in {directory}')


def command_push(arguments):
    if shutil.which('oras') is None:
        sys.exit('push needs oras on PATH (https://oras.land)')
    pinned = {row['area']: row for row in read_tsv(ARTIFACTS, ARTIFACT_COLUMNS)}
    for area in arguments.area or areas():
        directory = os.path.join(arguments.out, area)
        files = sorted(f for f in os.listdir(directory) if f.endswith('.tar'))
        if not files:
            sys.exit(f'nothing packed for {area} in {directory}')
        reference = f'{REPOSITORY}:{area}'
        # The source annotation links the package to the repository, whose workflow token can then read it.
        result = subprocess.run(['oras', 'push', '--artifact-type', ARTIFACT_TYPE, '--annotation', SOURCE,
                                 '--format', 'go-template', '--template', '{{.digest}}', reference]
                                + [f'{f}:{LAYER}' for f in files],
                                cwd=directory, check=True, capture_output=True, text=True)
        digest = result.stdout.strip().splitlines()[-1]
        pinned[area] = {'area': area, 'reference': f'{REPOSITORY}@{digest}'}
        print(f'{area}: {pinned[area]["reference"]}')
    if not os.path.exists(ARTIFACTS):
        with open(ARTIFACTS, 'w', encoding='utf-8', newline='\n') as sink:
            sink.write('# The published test data, one OCI artifact per area; see testdata.py. Columns:\n'
                       '# area\treference\n')
    write_tsv(ARTIFACTS, ARTIFACT_COLUMNS, list(pinned.values()), lambda row: row['area'])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    verify = commands.add_parser('verify')
    verify.add_argument('--area', action='append', default=[])
    verify.set_defaults(handler=command_verify)
    size = commands.add_parser('size')
    size.set_defaults(handler=command_size)
    licences = commands.add_parser('licences')
    licences.set_defaults(handler=command_licences)
    add = commands.add_parser('add')
    add.add_argument('area')
    add.add_argument('file')
    add.add_argument('source')
    add.add_argument('--name')
    add.set_defaults(handler=command_add)
    pack = commands.add_parser('pack')
    pack.add_argument('--area', action='append', default=[])
    pack.add_argument('--out', required=True)
    pack.set_defaults(handler=command_pack)
    push = commands.add_parser('push')
    push.add_argument('--area', action='append', default=[])
    push.add_argument('--out', required=True)
    push.set_defaults(handler=command_push)
    arguments = parser.parse_args()
    arguments.handler(arguments)


if __name__ == '__main__':
    main()
