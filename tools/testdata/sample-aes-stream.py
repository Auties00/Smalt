#!/usr/bin/env python3
"""Fetches the SAMPLE-AES presentation of another packager that smalt-core's opt-in HlsSampleAesStreamTest compares
with ffmpeg's decryption.

The presentation is the first four segments of the lowest H.264 variant of Unified Streaming's public demo
tears-of-steel-sample-aes (Unified Streaming Platform 1.13.0, H.264 and AAC, the media sequence number as the vector),
with its key. It is third-party content: it is downloaded (key and segments pinned by SHA-256, as fetched 2026-11-04)
and read locally, never committed. The script writes local.m3u8, which lists the four segments behind the key, and
ffmpeg-clear.ts, which the ffmpeg on PATH writes from them as a black-box oracle:

    ffmpeg -allowed_extensions ALL -i local.m3u8 -map 0 -c copy -f mpegts ffmpeg-clear.ts

    python -I tools/testdata/sample-aes-stream.py <directory>
    mvn -pl core -am verify -Dtest=HlsSampleAesStreamTest -Dsmalt.test.sampleAesStream=<directory>
"""
import hashlib
import os
import subprocess
import sys
import urllib.parse
import urllib.request

MASTER = ('https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/'
          'tears-of-steel-sample-aes.ism/.m3u8')

# The SHA-256 of the key and of the segments, in playlist order, as fetched 2026-11-04.
PINNED = {
    'aes.key': '84c0546cf3ddbd26ccd0bc9a38de811670bfddf8619a6f2c262aa7c86915bcff',
    's1.ts': '4c0ab1b7fa90a92b49f394dcc119cf089a7d2a1e7ea3f4b9c686ad4e69d1e012',
    's2.ts': '6c24851ac18d341d2d5586eaa5de9ed0baf1cba61b00ff3ef1c407b2e62b28c1',
    's3.ts': '15ddba3c3031cae89141ff090896124c2b24eab0f163e017e49481d75a35a7ca',
    's4.ts': '7200e7df28fed976218a504632c97bad3439d2ccf549e088cd70dfdd96fe5c69',
}

SEGMENTS = 4


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'smalt-testdata'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def lines(url):
    return fetch(url).decode('utf-8').splitlines()


def store(directory, name, data):
    digest = hashlib.sha256(data).hexdigest()
    if digest != PINNED[name]:
        sys.exit('%s has SHA-256 %s, not the pinned %s: the demo presentation changed' % (name, digest, PINNED[name]))
    with open(os.path.join(directory, name), 'wb') as out:
        out.write(data)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    master = lines(MASTER)
    # the first variant of the master playlist is the lowest H.264 one
    variant = None
    for index, line in enumerate(master):
        if line.startswith('#EXT-X-STREAM-INF:') and index + 1 < len(master):
            variant = urllib.parse.urljoin(MASTER, master[index + 1].strip())
            break
    if variant is None:
        sys.exit('the master playlist lists no variant')
    key = None
    segments = []
    durations = []
    playlist = lines(variant)
    for index, line in enumerate(playlist):
        if line.startswith('#EXT-X-KEY:') and 'METHOD=SAMPLE-AES' in line and key is None:
            uri = line.split('URI="', 1)[1].split('"', 1)[0]
            key = urllib.parse.urljoin(variant, uri)
        elif line.startswith('#EXTINF:') and len(segments) < SEGMENTS:
            durations.append(line[len('#EXTINF:'):].split(',', 1)[0])
            segments.append(urllib.parse.urljoin(variant, playlist[index + 1].strip()))
    if key is None or len(segments) < SEGMENTS:
        sys.exit('the variant playlist has no SAMPLE-AES key or fewer than %d segments' % SEGMENTS)
    store(directory, 'aes.key', fetch(key))
    local = ['#EXTM3U', '#EXT-X-VERSION:5', '#EXT-X-MEDIA-SEQUENCE:1', '#EXT-X-TARGETDURATION:4',
             '#EXT-X-KEY:METHOD=SAMPLE-AES,URI="aes.key"']
    for number, (url, duration) in enumerate(zip(segments, durations), 1):
        name = 's%d.ts' % number
        store(directory, name, fetch(url))
        local += ['#EXTINF:%s,' % duration, name]
    local.append('#EXT-X-ENDLIST')
    with open(os.path.join(directory, 'local.m3u8'), 'w', newline='\n') as out:
        out.write('\n'.join(local) + '\n')
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-allowed_extensions', 'ALL', '-i', 'local.m3u8', '-map', '0',
                    '-c', 'copy', '-f', 'mpegts', 'ffmpeg-clear.ts'], cwd=directory, check=True)
    print('wrote the presentation and ffmpeg-clear.ts to %s' % directory)


if __name__ == '__main__':
    main()
