#!/usr/bin/env python3
"""Assembles the SAMPLE-AES presentation of another packager that smalt-core's opt-in HlsSampleAesStreamTest compares
with ffmpeg's decryption.

The presentation is the first four segments of the lowest H.264 variant of Unified Streaming's public demo
tears-of-steel-sample-aes (Unified Streaming Platform 1.13.0, H.264 and AAC, the media sequence number as the vector,
https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel-sample-aes.ism/.m3u8),
with its key. It is third-party content, read from the hosted test data (SMALT_TESTDATA, see tools/testdata/README.md)
as the vectors named PREFIX + the names of PINNED, and the variant playlist PREFIX + variant.m3u8 for the segment
durations; nothing is downloaded. The script writes, into <directory> (default <cache>/sample-aes-stream, where
<cache> is SMALT_CACHE, default ~/.smalt/cache), the key and segments, local.m3u8, which lists the four segments behind
the key, and ffmpeg-clear.ts, which the ffmpeg on PATH writes from them as a black-box oracle:

    ffmpeg -allowed_extensions ALL -i local.m3u8 -map 0 -c copy -f mpegts ffmpeg-clear.ts

    python -I tools/testdata/sample-aes-stream.py [<directory>]
    mvn -pl core -am verify -Dtest=HlsSampleAesStreamTest -Dsmalt.test.sampleAesStream=<directory>
"""
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import testdata  # noqa: E402

PREFIX = 'usp-tears-of-steel-sample-aes-'

# The SHA-256 of the key and of the segments, in playlist order, as fetched 2026-11-04.
PINNED = {
    'aes.key': '84c0546cf3ddbd26ccd0bc9a38de811670bfddf8619a6f2c262aa7c86915bcff',
    's1.ts': '4c0ab1b7fa90a92b49f394dcc119cf089a7d2a1e7ea3f4b9c686ad4e69d1e012',
    's2.ts': '6c24851ac18d341d2d5586eaa5de9ed0baf1cba61b00ff3ef1c407b2e62b28c1',
    's3.ts': '15ddba3c3031cae89141ff090896124c2b24eab0f163e017e49481d75a35a7ca',
    's4.ts': '7200e7df28fed976218a504632c97bad3439d2ccf549e088cd70dfdd96fe5c69',
}

SEGMENTS = 4


def store(directory, name):
    source = testdata.vector(PREFIX + name)
    digest = testdata.sha256(source)
    if digest != PINNED[name]:
        sys.exit('%s has SHA-256 %s, not the pinned %s' % (PREFIX + name, digest, PINNED[name]))
    shutil.copyfile(source, os.path.join(directory, name))


def main():
    if len(sys.argv) > 2 or (len(sys.argv) == 2 and sys.argv[1].startswith('-')):
        sys.exit(__doc__)
    if len(sys.argv) == 2:
        directory = sys.argv[1]
        os.makedirs(directory, exist_ok=True)
    else:
        directory = testdata.cache_dir('sample-aes-stream')
    with open(testdata.vector(PREFIX + 'variant.m3u8'), encoding='utf-8') as source:
        playlist = source.read().splitlines()
    durations = [line[len('#EXTINF:'):].split(',', 1)[0] for line in playlist if line.startswith('#EXTINF:')]
    if len(durations) < SEGMENTS:
        sys.exit('the variant playlist has fewer than %d segments' % SEGMENTS)
    store(directory, 'aes.key')
    local = ['#EXTM3U', '#EXT-X-VERSION:5', '#EXT-X-MEDIA-SEQUENCE:1', '#EXT-X-TARGETDURATION:4',
             '#EXT-X-KEY:METHOD=SAMPLE-AES,URI="aes.key"']
    for number, duration in enumerate(durations[:SEGMENTS], 1):
        name = 's%d.ts' % number
        store(directory, name)
        local += ['#EXTINF:%s,' % duration, name]
    local.append('#EXT-X-ENDLIST')
    with open(os.path.join(directory, 'local.m3u8'), 'w', newline='\n') as out:
        out.write('\n'.join(local) + '\n')
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-allowed_extensions', 'ALL', '-i', 'local.m3u8', '-map', '0',
                    '-c', 'copy', '-f', 'mpegts', 'ffmpeg-clear.ts'], cwd=directory, check=True)
    print('wrote the presentation and ffmpeg-clear.ts to %s' % directory)


if __name__ == '__main__':
    main()
