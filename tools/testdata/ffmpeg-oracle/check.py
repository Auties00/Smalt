"""Checks that an ffmpeg executable has, and can run, every component of features.tsv next to this script.

tools/testdata/oracles.py runs it for the ffmpeg of every CI runner ("oracles.py versions"); it is kept apart from
oracles.py so that a change to the checks does not invalidate the cached oracle builds, whose key hashes that file.
"""
import os
import subprocess
import sys
import tempfile

FFMPEG_FEATURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'features.tsv')


def ffmpeg_features():
    """Returns the (kind, name) pairs of tools/testdata/ffmpeg-oracle/features.tsv."""
    features = []
    with open(FFMPEG_FEATURES, encoding='utf-8') as file:
        for line in file:
            line = line.rstrip('\r\n')
            if not line or line.startswith('#'):
                continue
            kind, name = line.split('\t')[:2]
            if kind not in ('encoder', 'decoder', 'filter', 'muxer', 'demuxer', 'pixfmt'):
                raise SystemExit('features.tsv: unknown kind ' + kind)
            features.append((kind, name))
    return features


def ffmpeg_listing(ffmpeg, option):
    """Returns the component names of an ffmpeg listing (-encoders, -decoders, -filters, -muxers, -demuxers)."""
    output = subprocess.run([ffmpeg, '-hide_banner', option], capture_output=True, text=True, timeout=60,
                            stdin=subprocess.DEVNULL).stdout
    names = set()
    for line in output.splitlines():
        fields = line.split()
        # The legend lines end with "=" and the header ends with "------"; component lines are "<flags> <name> ..."
        if len(fields) > 1 and fields[1] != '=' and not fields[0].startswith('-'):
            names.add(fields[1])
    return names


def encoder_pixel_formats(ffmpeg, encoder):
    """Returns the pixel formats "ffmpeg -h encoder=<encoder>" lists as supported."""
    output = subprocess.run([ffmpeg, '-hide_banner', '-h', 'encoder=' + encoder], capture_output=True, text=True,
                            timeout=60, stdin=subprocess.DEVNULL).stdout
    for line in output.splitlines():
        if line.strip().startswith('Supported pixel formats:'):
            return set(line.split(':', 1)[1].split())
    return set()


def missing_features(ffmpeg):
    """Returns a description of every feature of features.tsv the ffmpeg executable lacks."""
    listings = {}
    formats = {}
    missing = []
    for kind, name in ffmpeg_features():
        if kind == 'pixfmt':
            encoder, _, pixel_format = name.partition(':')
            if encoder not in formats:
                formats[encoder] = encoder_pixel_formats(ffmpeg, encoder)
            if pixel_format not in formats[encoder]:
                missing.append('ffmpeg encoder %s does not accept %s' % (encoder, pixel_format))
            continue
        if kind not in listings:
            listings[kind] = ffmpeg_listing(ffmpeg, '-' + kind + 's')
        if name not in listings[kind]:
            missing.append('ffmpeg has no %s %s' % (name, kind))
    return missing


# The sample rate each audio encoder of features.tsv is run at (mono); every other encoder is a video encoder.
SMOKE_AUDIO_RATES = {'libmp3lame': 44100, 'libopencore_amrnb': 8000, 'libvo_amrwbenc': 16000, 'libspeex': 16000,
                     'libopus': 48000, 'libvorbis': 44100}
# Options a video encoder needs to finish a smoke run quickly, the settings the tests use.
SMOKE_VIDEO_OPTIONS = {'libaom-av1': ['-cpu-used', '8', '-usage', 'realtime'], 'librav1e': ['-speed', '10'],
                       'libsvtav1': ['-preset', '12'], 'libx265': ['-x265-params', 'log-level=error']}
SMOKE_VIDEO = ['-f', 'lavfi', '-i', 'testsrc2=size=160x120:rate=30:duration=0.5']


def smoke_audio(rate):
    return ['-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=%d:duration=0.5' % rate, '-ac', '1']


def smoke_encode(encoder, pixel_format=None):
    """Returns the arguments that encode the smoke input with an encoder of features.tsv."""
    if encoder in SMOKE_AUDIO_RATES:
        return smoke_audio(SMOKE_AUDIO_RATES[encoder]) + ['-c:a', encoder]
    arguments = SMOKE_VIDEO + ['-c:v', encoder] + SMOKE_VIDEO_OPTIONS.get(encoder, [])
    return arguments + (['-pix_fmt', pixel_format] if pixel_format else [])


def smoke_runs(work):
    """Returns (feature, [argument lists]) for every row of features.tsv: each encoder encodes, each decoder decodes
    what an encoder of the file made, the filter filters, the muxer writes and the demuxer reads back what ffmpeg
    wrote; a row without a recipe here has None instead of the list. A read-back must print no warning."""
    runs = []
    decoders = {'libdav1d': ('libaom-av1', 'ivf'), 'libopencore_amrnb': ('libopencore_amrnb', 'amr'),
                'libopencore_amrwb': ('libvo_amrwbenc', 'amr'), 'libvpx': ('libvpx', 'webm'),
                'libvpx-vp9': ('libvpx-vp9', 'webm'), 'libopus': ('libopus', 'ogg')}
    for kind, name in ffmpeg_features():
        feature = '%s %s' % (kind, name)
        if kind == 'encoder':
            runs.append((feature, [smoke_encode(name) + ['-f', 'null', '-']]))
        elif kind == 'pixfmt':
            encoder, _, pixel_format = name.partition(':')
            runs.append((feature, [smoke_encode(encoder, pixel_format) + ['-f', 'null', '-']]))
        elif kind == 'decoder' and name in decoders:
            encoder, extension = decoders[name]
            sample = os.path.join(work, '%s.%s' % (name, extension))
            runs.append((feature, [smoke_encode(encoder) + [sample],
                                   ['-c:' + ('a' if encoder in SMOKE_AUDIO_RATES else 'v'), name, '-i', sample,
                                    '-xerror', '-f', 'null', '-']]))
        elif kind == 'filter' and name == 'zscale':
            runs.append((feature, [SMOKE_VIDEO + ['-vf', 'format=yuv420p,zscale=matrixin=709:matrix=709:rangein=limited'
                                                  ':range=full,format=yuv444p12le', '-f', 'null', '-']]))
        elif kind == 'muxer' and name == 'avif':
            runs.append((feature, [SMOKE_VIDEO + ['-frames:v', '1', '-c:v', 'libaom-av1', '-cpu-used', '8',
                                                  '-still-picture', '1', os.path.join(work, 'smoke.avif')]]))
        elif kind == 'demuxer' and name == 'dash':
            # The DASH demuxer resolves segment names against the manifest's URL, which a Windows path breaks.
            manifest = os.path.join(work, 'dash', 'smoke.mpd').replace(os.sep, '/')
            os.makedirs(os.path.dirname(manifest), exist_ok=True)
            runs.append((feature, [SMOKE_VIDEO + ['-c:v', 'libx264', '-b:v', '200k', '-f', 'dash', manifest],
                                   ['-xerror', '-i', manifest, '-map', '0', '-f', 'null', '-']]))
        else:
            runs.append((feature, None))
    # The fixtures run a video and an audio encoder at once, each on its own ffmpeg thread; the fixtures of this
    # pair are repeated, since a race shows up only now and then.
    together = []
    for size, seconds, extension in (('160x120', '1.5', 'mp4'), ('128x96', '1.0', 'webm')) * 4:
        together.append(['-f', 'lavfi', '-i', 'testsrc2=size=%s:rate=30:duration=%s' % (size, seconds), '-f', 'lavfi',
                         '-i', 'sine=frequency=440:sample_rate=48000:duration=' + seconds, '-c:v', 'libaom-av1']
                        + SMOKE_VIDEO_OPTIONS['libaom-av1'] + ['-g', '15', '-c:a', 'libopus',
                                                               os.path.join(work, 'together.' + extension)])
    runs.append(('encoders libaom-av1 and libopus together', together))
    return runs


def crash_report():
    """Returns the head of the newest macOS crash report of ffmpeg, or an empty string."""
    directory = os.path.expanduser('~/Library/Logs/DiagnosticReports')
    if sys.platform != 'darwin' or not os.path.isdir(directory):
        return ''
    reports = sorted((os.path.join(directory, name) for name in os.listdir(directory) if name.startswith('ffmpeg')),
                     key=os.path.getmtime)
    if not reports:
        return ''
    with open(reports[-1], encoding='utf-8', errors='replace') as file:
        return '\n'.join(file.read().splitlines()[:120])


def crashed(returncode):
    """Returns whether a return code reports a crash: a signal on Unix, an access violation on Windows."""
    return returncode < 0 or returncode == 0xC0000005


def failing_features(ffmpeg):
    """Runs every component of features.tsv once and returns a description of each run that fails: a component an
    ffmpeg lists can still crash or refuse its input, which would surface as unrelated test errors. A run that
    crashes is made up to three times, as the test kit's Oracle does (ffmpeg 7.1's libaom wrapper crashes now and
    then in its muxing thread on macOS); a crash that repeats fails."""
    failures = []
    with tempfile.TemporaryDirectory(prefix='smalt-ffmpeg-smoke-') as work:
        for feature, commands in smoke_runs(work):
            if commands is None:
                failures.append('features.tsv lists %s, which check.py has no smoke run for' % feature)
                continue
            for arguments in commands:
                # A demuxer's read-back must print nothing at warning level.
                quiet = feature.startswith('demuxer ') and arguments is commands[-1]
                command = [ffmpeg, '-hide_banner', '-nostdin', '-y', '-v', 'warning'] + arguments
                for attempt in range(3):
                    result = subprocess.run(command, capture_output=True, text=True, errors='replace',
                                            timeout=300, stdin=subprocess.DEVNULL)
                    if not crashed(result.returncode):
                        break
                    print('warning: ffmpeg %s crashed (exit %d), attempt %d: %s\n%s'
                          % (feature, result.returncode, attempt + 1, ' '.join(command), crash_report()), flush=True)
                if result.returncode != 0 or (quiet and result.stderr.strip()):
                    detail = result.stderr.strip()[-2000:]
                    if crashed(result.returncode):
                        detail += '\n' + crash_report()
                    failures.append('ffmpeg %s fails (exit %d): %s\n%s' % (feature, result.returncode,
                                                                         ' '.join(command), detail))
                    break
    return failures
