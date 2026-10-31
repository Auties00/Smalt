"""Makes the I/O behavior samples of Flash Video under core/src/test/resources/samples/flv.

ffmpeg writes the legacy files (Sorenson Spark with MP3, H.264 with AAC and an onMetaData key frame index) and the
Enhanced RTMP files of its muxer (HEVC, VP9 with colorInfo metadata frames, AV1). The enhanced audio file, which
ffmpeg 7.1 cannot write, is assembled here from the Enhanced RTMP v2 specification: Opus as track 0 and FLAC as
track 1 of a multitrack stream, their sequence starts in one ManyTracksManyCodecs message, a ModEx
TimestampOffsetNano on the first Opus frame, a sequence end per track, and the packets of ffmpeg's Ogg Opus and Ogg
FLAC encodings of a sine tone. Pin the result with pin-samples.py.

Usage: python -I make-flv-samples.py <ffmpeg> <repository root>
"""
import os
import struct
import subprocess
import sys
import tempfile


def run(arguments):
    subprocess.run(arguments, check=True)


def ogg_packets(path):
    """Returns the packets of the first logical stream of an Ogg file."""
    data = open(path, 'rb').read()
    packets = []
    current = b''
    position = 0
    while position + 27 <= len(data):
        if data[position:position + 4] != b'OggS':
            raise ValueError('lost Ogg sync at %d' % position)
        segments = data[position + 26]
        table = data[position + 27:position + 27 + segments]
        body = position + 27 + segments
        for size in table:
            current += data[body:body + size]
            body += size
            if size < 255:
                packets.append(current)
                current = b''
        position = body
    return packets


def amf_string(text):
    raw = text.encode('utf-8')
    return b'\x02' + struct.pack('>H', len(raw)) + raw


def amf_name(text):
    raw = text.encode('utf-8')
    return struct.pack('>H', len(raw)) + raw


def amf_number(value):
    return b'\x00' + struct.pack('>d', value)


def fourcc_number(code):
    return float(struct.unpack('>I', code.encode('ascii'))[0])


def tag(kind, timestamp, body):
    header = bytes([kind]) + struct.pack('>I', len(body))[1:] + struct.pack('>I', timestamp & 0xFFFFFF)[1:]
    header += bytes([(timestamp >> 24) & 0xFF]) + b'\x00\x00\x00'
    return header + body + struct.pack('>I', len(body) + 11)


def enhanced_audio(ffmpeg, path):
    with tempfile.TemporaryDirectory() as scratch:
        opus_file = os.path.join(scratch, 'opus.ogg')
        flac_file = os.path.join(scratch, 'flac.ogg')
        run([ffmpeg, '-v', 'error', '-y', '-bitexact', '-f', 'lavfi', '-i',
             'sine=frequency=440:sample_rate=48000:duration=1', '-c:a', 'libopus', '-b:a', '24k', '-ac', '1',
             '-frame_duration', '20', opus_file])
        run([ffmpeg, '-v', 'error', '-y', '-bitexact', '-f', 'lavfi', '-i',
             'sine=frequency=660:sample_rate=22050:duration=1', '-c:a', 'flac', '-frame_size', '2205', '-ac', '1',
             '-f', 'ogg', flac_file])
        opus = ogg_packets(opus_file)
        flac = ogg_packets(flac_file)
    opus_head = opus[0]
    opus_frames = opus[2:]
    if not opus_head.startswith(b'OpusHead'):
        raise ValueError('no OpusHead')
    first = flac[0]
    if first[:5] != b'\x7fFLAC':
        raise ValueError('no Ogg FLAC header')
    header_count = struct.unpack('>H', first[7:9])[0]
    flac_head = first[9:]
    flac_frames = flac[1 + header_count:]
    block = struct.unpack('>H', flac_head[8:10])[0]
    rate = (struct.unpack('>I', flac_head[18:22])[0] >> 12) & 0xFFFFF
    events = []
    for index, frame in enumerate(opus_frames):
        timestamp = index * 20
        if index == 0:
            # ModEx TimestampOffsetNano: 3 bytes of data, then CodedFrames
            body = bytes([0x97, 2]) + b'\x00\x01\x23' + bytes([0x01]) + b'Opus' + frame
        else:
            body = bytes([0x91]) + b'Opus' + frame
        events.append((timestamp, 0, body))
    for index, frame in enumerate(flac_frames):
        timestamp = index * block * 1000 // rate
        body = bytes([0x95, 0x01]) + b'fLaC' + bytes([1]) + frame
        events.append((timestamp, 1, body))
    events.sort(key=lambda event: (event[0], event[1]))
    last = events[-1][0]
    metadata = amf_string('onMetaData') + b'\x08' + struct.pack('>I', 4)
    metadata += amf_name('duration') + amf_number((last + 20) / 1000.0)
    metadata += amf_name('audiocodecid') + amf_number(fourcc_number('Opus'))
    metadata += amf_name('audiosamplerate') + amf_number(48000)
    metadata += amf_name('audioTrackIdInfoMap') + b'\x03' + amf_name('1') + b'\x03'
    metadata += amf_name('audiocodecid') + amf_number(fourcc_number('fLaC'))
    metadata += amf_name('samplerate') + amf_number(rate) + amf_name('channels') + amf_number(1)
    metadata += b'\x00\x00\x09' + b'\x00\x00\x09' + b'\x00\x00\x09'
    out = b'FLV\x01\x04' + struct.pack('>I', 9) + struct.pack('>I', 0)
    out += tag(18, 0, metadata)
    starts = bytes([0x95, 0x20])
    for code, track, config in (('Opus', 0, opus_head), ('fLaC', 1, flac_head)):
        starts += code.encode('ascii') + bytes([track]) + struct.pack('>I', len(config))[1:] + config
    out += tag(8, 0, starts)
    for timestamp, _, body in events:
        out += tag(8, timestamp, body)
    out += tag(8, last, bytes([0x92]) + b'Opus')
    out += tag(8, last, bytes([0x95, 0x02]) + b'fLaC' + bytes([1]))
    with open(path, 'wb') as output:
        output.write(out)


def main():
    ffmpeg, root = sys.argv[1], sys.argv[2]
    samples = os.path.join(root, 'core', 'src', 'test', 'resources', 'samples', 'flv')
    os.makedirs(samples, exist_ok=True)
    ff = [ffmpeg, '-hide_banner', '-v', 'error', '-y', '-bitexact']
    video = ['-f', 'lavfi', '-i', 'testsrc2=size=160x120:rate=15:duration=2']
    run(ff + video + ['-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=22050:duration=2', '-c:v', 'flv1',
                      '-q:v', '6', '-g', '10', '-c:a', 'libmp3lame', '-b:a', '32k', '-ac', '1', '-shortest',
                      os.path.join(samples, 'flv1-mp3.flv')])
    run(ff + video + ['-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100:duration=2', '-c:v', 'libx264',
                      '-preset', 'ultrafast', '-crf', '32', '-g', '10', '-bf', '2', '-pix_fmt', 'yuv420p',
                      '-x264-params', 'threads=1:sliced-threads=0', '-c:a', 'aac', '-b:a', '48k', '-ac', '1',
                      '-shortest', '-flvflags', 'add_keyframe_index', os.path.join(samples, 'h264-aac.flv')])
    run(ff + ['-f', 'lavfi', '-i', 'testsrc2=size=160x120:rate=15:duration=1', '-c:v', 'libx265', '-preset',
              'ultrafast', '-crf', '34', '-g', '5', '-bf', '0', '-pix_fmt', 'yuv420p', '-x265-params',
              'pools=1:frame-threads=1:log-level=error', os.path.join(samples, 'hevc.flv')])
    run(ff + ['-f', 'lavfi', '-i', 'testsrc2=size=160x120:rate=15:duration=1', '-c:v', 'libvpx-vp9', '-deadline',
              'realtime', '-cpu-used', '8', '-b:v', '100k', '-g', '5', '-threads', '1', '-color_primaries', 'bt709',
              '-color_trc', 'bt709', '-colorspace', 'bt709', os.path.join(samples, 'vp9.flv')])
    run(ff + ['-f', 'lavfi', '-i', 'testsrc2=size=64x48:rate=15:duration=0.6', '-c:v', 'libaom-av1', '-cpu-used',
              '8', '-crf', '40', '-g', '4', '-threads', '1', os.path.join(samples, 'av1.flv')])
    enhanced_audio(ffmpeg, os.path.join(samples, 'opus-flac-multitrack.flv'))


if __name__ == '__main__':
    main()
